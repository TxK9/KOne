// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"syscall"
	"time"
)

type Control interface {
	Preflight(context.Context) error
	Register(context.Context, bool) (ControllerMetadata, error)
	Configuration(context.Context) (ConfigReply, error)
	Lease(context.Context, ConfigReply) (LeaseReply, error)
	Ack(context.Context, ConfigReply, string) error
	AckStopped(context.Context, ControllerMetadata) error
}
type Status struct {
	Format                         int    `json:"format"`
	Version                        string `json:"version"`
	PID                            int    `json:"pid"`
	ProcessStart                   string `json:"processStart"`
	State                          string `json:"state"`
	Error                          string `json:"error,omitempty"`
	DeviceID                       string `json:"deviceId"`
	Mode                           string `json:"mode"`
	Generation                     int64  `json:"authGeneration"`
	AuthorizedRoots                int    `json:"authorizedRoots"`
	CoreReportedRunning            bool   `json:"coreReportedRunning"`
	StopConfirmed                  bool   `json:"stopConfirmed"`
	LeaseDeadlineBoot              int64  `json:"leaseDeadlineBoot"`
	ControlPathCheckedWithoutChild bool   `json:"controlPathCheckedWithoutChild"`
	CoreVersion                    string `json:"coreVersion,omitempty"`
	RetainedInactiveStubs          int    `json:"retainedInactiveStubs"`
}
type Daemon struct {
	config            Config
	api               Control
	core              Core
	writer            StubWriter
	registry          Registry
	gate              *Gate
	clock             Clock
	prepared          bool
	process           Process
	currentConfig     string
	currentGeneration int64
	nextRenew         time.Duration
	lastMetadata      *ControllerMetadata
	status            Status
	statusMu          sync.Mutex
	saveRegistry      func() error
	saveStatus        func(Status)
}

func (d *Daemon) statusUpdate(state string, err error) {
	d.statusMu.Lock()
	d.status.State = state
	d.status.Error = safeError(err)
	d.status.LeaseDeadlineBoot = int64(d.gate.Deadline())
	snapshot := d.status
	d.statusMu.Unlock()
	if d.saveStatus != nil {
		d.saveStatus(snapshot)
	}
}
func (d *Daemon) Snapshot() Status { d.statusMu.Lock(); defer d.statusMu.Unlock(); return d.status }
func processFinished(process Process) bool {
	if process == nil {
		return true
	}
	select {
	case <-process.Done():
		return true
	default:
		return false
	}
}
func (d *Daemon) stop(reason string, meta *ControllerMetadata) error {
	d.gate.Reject(reason)
	hadOwned := d.process != nil
	confirmed := d.Snapshot().StopConfirmed
	if d.process != nil {
		d.process.Stop()
		timer := time.NewTimer(2 * time.Second)
		defer timer.Stop()
		select {
		case <-d.process.Done():
		case <-timer.C:
			d.statusMu.Lock()
			d.status.StopConfirmed = false
			d.statusMu.Unlock()
			d.statusUpdate("stop_unconfirmed", errors.New("owned_core_stop_unconfirmed"))
			return errors.New("owned_core_stop_unconfirmed")
		}
	}
	if hadOwned {
		confirmed = true
	}
	d.process = nil
	d.currentConfig = ""
	d.nextRenew = 0
	d.statusMu.Lock()
	d.status.CoreReportedRunning = false
	d.status.StopConfirmed = confirmed
	d.status.AuthorizedRoots = 0
	d.statusMu.Unlock()
	retained, cleanupErr := cleanupStubs(d.config, d.writer, d.registry)
	d.statusMu.Lock()
	d.status.RetainedInactiveStubs = retained
	d.statusMu.Unlock()
	if meta == nil {
		meta = d.lastMetadata
	}
	if meta != nil && hadOwned && confirmed {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = d.api.AckStopped(ctx, *meta)
		cancel()
	}
	d.lastMetadata = nil
	if cleanupErr != nil {
		d.statusUpdate("stopped", cleanupErr)
	} else {
		d.statusUpdate("stopped", nil)
	}
	return cleanupErr
}
func (d *Daemon) failed(err error) error {
	var denied *APIError
	var meta *ControllerMetadata
	if errors.As(err, &denied) && denied.Controller != nil && validateMetadata(*denied.Controller, d.config.DeviceID) == nil {
		copy := *denied.Controller
		meta = &copy
		d.statusMu.Lock()
		d.status.Mode = copy.Mode
		d.status.Generation = copy.AuthGeneration
		d.statusMu.Unlock()
	}
	if stopErr := d.stop(safeError(err), meta); stopErr != nil {
		return stopErr
	}
	d.statusUpdate("authorization_unavailable", err)
	return err
}
func (d *Daemon) Step(ctx context.Context) error {
	if _, err := d.clock.Boot(); err != nil {
		return d.failed(err)
	}
	if d.process != nil && processFinished(d.process) {
		if err := d.stop("managed_core_exited", nil); err != nil {
			return err
		}
	}
	if !d.prepared {
		if d.process != nil {
			return d.failed(errors.New("preflight_requires_stopped_core"))
		}
		value, err := d.core.Version(ctx)
		if err != nil {
			return d.failed(err)
		}
		if err = d.core.Check(ctx, syntheticCoreConfig()); err != nil {
			return d.failed(err)
		}
		if err = d.api.Preflight(ctx); err != nil {
			return d.failed(err)
		}
		if _, err = d.api.Register(ctx, true); err != nil {
			return d.failed(err)
		}
		d.prepared = true
		d.statusMu.Lock()
		d.status.CoreVersion = value
		d.status.ControlPathCheckedWithoutChild = true
		d.statusMu.Unlock()
	}
	view, err := d.api.Configuration(ctx)
	if err != nil {
		return d.failed(err)
	}
	if view.Controller == nil {
		return d.failed(errors.New("missing_authority_metadata"))
	}
	metadata := *view.Controller
	d.statusMu.Lock()
	d.status.Mode = metadata.Mode
	d.status.Generation = metadata.AuthGeneration
	d.statusMu.Unlock()
	if metadata.Mode != "enabled" || !metadata.Required {
		return d.failed(errors.New("explicit_controller_enrollment_required"))
	}
	changed := d.currentConfig != view.ConfigRevision || d.currentGeneration != metadata.AuthGeneration
	if d.process != nil && changed {
		if err = d.stop("managed_configuration_changed", &metadata); err != nil {
			return err
		}
	}
	epoch := d.gate.Epoch()
	plan, err := makePlan(d.config, view, &d.registry)
	if err != nil {
		return d.failed(err)
	}
	if d.saveRegistry != nil {
		if err = d.saveRegistry(); err != nil {
			return d.failed(err)
		}
	}
	running := d.process != nil && d.gate.Active() && !changed
	if !running {
		if d.process != nil {
			if err = d.stop("authorization_not_active", &metadata); err != nil {
				return err
			}
			epoch = d.gate.Epoch()
		}
		if err = d.core.Check(ctx, plan.Config); err != nil {
			return d.failed(err)
		}
	}
	began, err := d.clock.Boot()
	if err != nil {
		return d.failed(err)
	}
	lease, err := d.api.Lease(ctx, view)
	if err != nil {
		return d.failed(err)
	}
	if lease.TTLSeconds < 1 || lease.TTLSeconds > 60 {
		return d.failed(errors.New("invalid_online_lease"))
	}
	deadline := began + time.Duration(lease.TTLSeconds)*time.Second
	now, err := d.clock.Boot()
	if err != nil || deadline <= now || d.gate.Epoch() != epoch {
		return d.failed(errors.New("authorization_epoch_changed"))
	}
	if running {
		if err = d.gate.Renew(epoch, deadline); err != nil {
			return d.failed(err)
		}
	} else {
		authorized, err := d.gate.Arm(ctx, epoch, deadline)
		if err != nil {
			return d.failed(err)
		}
		process, startErr := d.core.Start(authorized, plan.Config, plan.Stubs)
		d.process = process
		if startErr != nil {
			return d.failed(startErr)
		}
		if process == nil {
			return d.failed(errors.New("managed_core_start_failed"))
		}
		go func() { <-process.Done(); d.gate.InvalidateIf(epoch, "managed_core_exited") }()
		if !d.gate.Active() {
			return d.failed(errors.New("authorization_epoch_changed"))
		}
		updateCtx, cancel := context.WithTimeout(authorized, 2*time.Second)
		retained, updateErr := d.writer.Apply(updateCtx, d.registry, plan.Stubs)
		cancel()
		if updateErr != nil {
			return d.failed(updateErr)
		}
		d.statusMu.Lock()
		d.status.RetainedInactiveStubs = retained
		d.statusMu.Unlock()
	}
	if !d.gate.Active() || processFinished(d.process) {
		return d.failed(errors.New("authorization_epoch_changed"))
	}
	d.currentConfig, d.currentGeneration = view.ConfigRevision, metadata.AuthGeneration
	renewAfter := time.Duration(lease.TTLSeconds) * time.Second / 3
	if renewAfter > 20*time.Second {
		renewAfter = 20 * time.Second
	}
	d.nextRenew = began + renewAfter
	d.lastMetadata = &metadata
	d.statusMu.Lock()
	d.status.CoreReportedRunning = true
	d.status.StopConfirmed = false
	d.status.AuthorizedRoots = len(plan.Roots)
	d.statusMu.Unlock()
	// Running means this supervised core has loaded; it is not a remote-node connectivity measurement.
	_ = d.api.Ack(ctx, view, "running")
	if !d.gate.Active() {
		return d.failed(errors.New("lease_expired"))
	}
	d.statusUpdate("guarded", nil)
	return nil
}
func (d *Daemon) Run(ctx context.Context) error {
	go d.gate.Watch(ctx)
	for {
		if ctx.Err() != nil {
			_ = d.stop("daemon_stopped", nil)
			return nil
		}
		_ = d.Step(ctx)
		wait := 5 * time.Second
		if d.gate.Active() {
			now, err := d.clock.Boot()
			if err != nil {
				d.gate.Invalidate("boottime_clock_failed")
				continue
			}
			wait = d.nextRenew - now
			if wait < 0 {
				wait = 0
			}
		}
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			_ = d.stop("daemon_stopped", nil)
			return nil
		case <-d.gate.wake:
			timer.Stop()
		case <-timer.C:
		}
	}
}
func ensureRuntime(config Config) error {
	if err := os.MkdirAll(config.RuntimeDir, 0700); err != nil {
		return errors.New("private_runtime_unavailable")
	}
	info, err := os.Lstat(config.RuntimeDir)
	if err != nil || !info.IsDir() || info.Mode().Perm() != 0700 {
		return errors.New("private_runtime_must_be_mode_0700")
	}
	owner, ok := info.Sys().(*syscall.Stat_t)
	expected := uint32(os.Geteuid())
	if runtime.GOOS == "linux" {
		expected = 0
	}
	if !ok || owner.Uid != expected {
		return errors.New("unsafe_runtime_owner")
	}
	return nil
}
func newDaemon(config Config, api Control, core Core, writer StubWriter, registry Registry, clock Clock) *Daemon {
	d := &Daemon{config: config, api: api, core: core, writer: writer, registry: registry, clock: clock, gate: newGate(clock), status: Status{Format: 1, Version: version, PID: os.Getpid(), ProcessStart: processIdentity(os.Getpid()), DeviceID: config.DeviceID, State: "stopped", StopConfirmed: false}}
	var lastRegistry []byte
	d.saveRegistry = func() error {
		data, err := json.Marshal(d.registry)
		if err != nil {
			return errors.New("registry_encoding_failed")
		}
		if bytes.Equal(data, lastRegistry) {
			return nil
		}
		if err = d.registry.save(config); err != nil {
			return err
		}
		lastRegistry = data
		return nil
	}
	d.saveStatus = func(status Status) {
		data, err := json.Marshal(status)
		if err == nil {
			_ = atomicPrivate(filepath.Join(config.RuntimeDir, "status.json"), append(data, '\n'))
		}
	}
	return d
}
