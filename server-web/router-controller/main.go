// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"errors"
	"flag"
	"io"
	"net"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"sync/atomic"
	"syscall"
	"time"
)

func claimInput(path string) (string, string, string, error) {
	b, err := readPrivate(path, 8<<10)
	if err != nil {
		return "", "", "", err
	}
	var input struct {
		Server string `json:"server"`
		Code   string `json:"inviteCode"`
		Name   string `json:"deviceName"`
	}
	b = bytes.TrimSpace(b)
	if bytes.HasPrefix(b, []byte{'{'}) {
		if decodeStrict(b, &input) != nil {
			return "", "", "", errors.New("invalid_private_claim_input")
		}
	} else {
		link, err := url.Parse(string(b))
		if err != nil || link.Scheme != "kone" || link.Host != "claim" || link.Path != "" || link.Fragment != "" {
			return "", "", "", errors.New("invalid_claim_link")
		}
		query, err := url.ParseQuery(link.RawQuery)
		if err != nil || len(query) != 2 || len(query["server"]) != 1 || len(query["code"]) != 1 {
			return "", "", "", errors.New("invalid_claim_link")
		}
		input.Server = query.Get("server")
		input.Code = query.Get("code")
	}
	if _, err = origin(input.Server); err != nil {
		return "", "", "", err
	}
	if len(input.Code) < 1 || len(input.Code) > 128 || !bearerPattern.MatchString(input.Code) {
		return "", "", "", errors.New("invalid_claim_code")
	}
	if input.Name == "" {
		input.Name = "KOne router"
	}
	if _, ok := stringValue(input.Name, 60); !ok {
		return "", "", "", errors.New("invalid_device_name")
	}
	return input.Server, input.Code, input.Name, nil
}
func acquireDaemonLock(config Config) (*os.File, error) {
	if err := ensureRuntime(config); err != nil {
		return nil, err
	}
	path := filepath.Join(config.RuntimeDir, "daemon.lock")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return nil, errors.New("daemon_lock_unavailable")
	}
	if info, err := file.Stat(); err != nil || checkPrivate(info) != nil {
		file.Close()
		return nil, errors.New("unsafe_daemon_lock")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if err = lockFile(ctx, file); err != nil {
		file.Close()
		return nil, errors.New("daemon_already_running")
	}
	return file, nil
}
func offlineCheck(ctx context.Context, config Config) (string, error) {
	if err := rootLinux(); err != nil {
		return "", err
	}
	if err := ensureRuntime(config); err != nil {
		return "", err
	}
	if err := trustedExecutable(config.UCIPath, false); err != nil {
		return "", err
	}
	core := ExecCore{Config: config}
	version, err := core.Version(ctx)
	if err != nil {
		return "", err
	}
	if err = core.Check(ctx, syntheticCoreConfig()); err != nil {
		return "", err
	}
	if _, err = bootNow(); err != nil {
		return "", err
	}
	return version, nil
}
func onlineCheck(ctx context.Context, config Config, api *Client) error {
	host, _ := origin(config.Server)
	if net.ParseIP(host.Hostname()) == nil && config.ControlResolver == "" {
		return errors.New("independent_control_resolver_required")
	}
	monitorCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	var failed atomic.Bool
	if err := monitorNetwork(monitorCtx, cancel, func() { failed.Store(true); cancel() }); err != nil {
		return err
	}
	if err := api.Preflight(monitorCtx); err != nil {
		if monitorCtx.Err() != nil && ctx.Err() == nil {
			return errors.New("network_epoch_changed")
		}
		return err
	}
	if monitorCtx.Err() != nil {
		return errors.New("network_epoch_changed")
	}
	_, err := api.Register(monitorCtx, true)
	if failed.Load() {
		return errors.New("network_monitor_failed")
	}
	if monitorCtx.Err() != nil && ctx.Err() == nil {
		return errors.New("network_epoch_changed")
	}
	return err
}

func serve(ctx context.Context, path string, config Config) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := rootLinux(); err != nil {
		return err
	}
	if !config.Enabled {
		return nil
	}
	lock, err := acquireDaemonLock(config)
	if err != nil {
		return err
	}
	defer lock.Close()
	defer unlockFile(lock)
	api, err := newClient(config)
	if err != nil {
		return err
	}
	registry, err := loadRegistry(config)
	if err != nil {
		return err
	}
	writer := FileUCI{Config: config}
	daemon := newDaemon(config, api, ExecCore{Config: config}, writer, registry, systemClock{start: time.Now()})
	if err = trustedExecutable(config.UCIPath, false); err != nil {
		return err
	}
	if _, err = cleanupStubs(config, writer, registry); err != nil {
		return err
	}
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	fatal := make(chan string, 1)
	if err = monitorNetwork(runCtx, func() { daemon.gate.Invalidate("network_epoch_changed") }, func() {
		daemon.gate.Invalidate("network_monitor_failed")
		select {
		case fatal <- "network_monitor_failed":
		default:
		}
		cancel()
	}); err != nil {
		return err
	}
	before, err := os.Lstat(path)
	if err != nil {
		return errors.New("private_file_unavailable")
	}
	go func() {
		ticker := time.NewTicker(500 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-runCtx.Done():
				return
			case <-ticker.C:
				current, err := os.Lstat(path)
				if err != nil || !os.SameFile(before, current) || !before.ModTime().Equal(current.ModTime()) || before.Size() != current.Size() || checkPrivate(current) != nil {
					daemon.gate.Invalidate("private_configuration_changed")
					cancel()
					return
				}
			}
		}
	}()
	err = daemon.Run(runCtx)
	select {
	case code := <-fatal:
		return errors.New(code)
	default:
		return err
	}
}
func mainAction(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return errors.New("command_required")
	}
	command := args[0]
	flags := flag.NewFlagSet(command, flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	configPath := flags.String("config", defaultConfig, "")
	claimFile := flags.String("input-file", "", "")
	corePath := flags.String("core", "/usr/bin/sing-box", "")
	resolver := flags.String("resolver", "", "")
	mark := flags.Uint("control-mark", 0, "")
	online := flags.Bool("online", false, "")
	if flags.Parse(args[1:]) != nil || flags.NArg() != 0 || uint64(*mark) > uint64(^uint32(0)) {
		return errors.New("invalid_command_options")
	}
	if command == "version" {
		return output(map[string]any{"version": version, "protocol": 1, "target": "linux-arm64"})
	}
	if command == "enroll" {
		if err := rootLinux(); err != nil {
			return err
		}
		if *claimFile == "" {
			return errors.New("private_claim_file_required")
		}
		if _, err := os.Lstat(*configPath); err == nil {
			return errors.New("configuration_already_exists")
		}
		server, code, name, err := claimInput(*claimFile)
		if err != nil {
			return err
		}
		c := Config{Format: 1, Enabled: false, Server: server, CorePath: *corePath, UCIPath: "/sbin/uci", PassWallPath: "/etc/config/passwall2", StatePath: filepath.Join(filepath.Dir(*configPath), "stubs.json"), RuntimeDir: "/run/kone-router-controller", ControlResolver: *resolver, ControlMark: uint32(*mark), PortBase: 600, PortSlots: 400}
		api, err := newClient(c)
		if err != nil {
			return err
		}
		var reply struct {
			DeviceID   string `json:"deviceId"`
			Credential string `json:"credential"`
			Format     int    `json:"format"`
		}
		if err = api.call(ctx, "POST", "/api/client/claim", map[string]any{"inviteCode": code, "deviceName": name}, &reply); err != nil {
			return err
		}
		c.DeviceID, c.Credential = reply.DeviceID, reply.Credential
		if reply.Format != 1 || c.Validate() != nil {
			return errors.New("invalid_claim_response")
		}
		if err = saveConfig(*configPath, c); err != nil {
			return err
		}
		_, registrationErr := api.Register(ctx, false)
		return output(map[string]any{"enrolled": true, "deviceId": c.DeviceID, "enabled": false, "capabilitiesReady": false, "registrationPending": registrationErr != nil})
	}
	c, err := loadConfig(*configPath)
	if err != nil {
		return err
	}
	switch command {
	case "is-enabled":
		if err = rootLinux(); err != nil {
			return err
		}
		if !c.Enabled {
			return errors.New("adapter_disabled")
		}
		return nil
	case "status":
		var status Status
		b, readErr := readPrivate(filepath.Join(c.RuntimeDir, "status.json"), 64<<10)
		if readErr != nil || decodeStrict(b, &status) != nil || status.Format != 1 || status.DeviceID != c.DeviceID {
			return output(map[string]any{"enabled": c.Enabled, "state": "not_running", "authorizationValid": false})
		}
		live := status.ProcessStart != "" && processIdentity(status.PID) == status.ProcessStart
		now, clockErr := bootNow()
		valid := c.Enabled && live && clockErr == nil && now < time.Duration(status.LeaseDeadlineBoot) && status.CoreReportedRunning
		return output(map[string]any{"enabled": c.Enabled, "daemonLive": live, "state": status.State, "authorizationValid": valid, "coreReportedRunning": status.CoreReportedRunning && live, "stopConfirmed": status.StopConfirmed, "authGeneration": status.Generation, "authorizedRoots": status.AuthorizedRoots, "retainedInactiveStubs": status.RetainedInactiveStubs, "error": status.Error})
	case "serve":
		return serve(ctx, *configPath, c)
	case "check", "activate":
		lock, err := acquireDaemonLock(c)
		if err != nil {
			return err
		}
		defer lock.Close()
		defer unlockFile(lock)
		version, err := offlineCheck(ctx, c)
		if err != nil {
			return err
		}
		if command == "activate" || *online {
			api, err := newClient(c)
			if err != nil {
				return err
			}
			if err = onlineCheck(ctx, c, api); err != nil {
				return err
			}
			if command == "activate" {
				view, err := api.Configuration(ctx)
				if err != nil {
					return err
				}
				registry, err := loadRegistry(c)
				if err != nil {
					return err
				}
				if _, err = makePlan(c, view, &registry); err != nil {
					return err
				}
				if _, err = api.Lease(ctx, view); err != nil {
					return err
				}
				c.Enabled = true
				if err = saveConfig(*configPath, c); err != nil {
					return err
				}
			}
		}
		return output(map[string]any{"checked": true, "coreVersion": version, "controlPathCheckedWithoutChild": *online || command == "activate", "enabled": c.Enabled, "serviceStarted": false})
	case "disable":
		if err = rootLinux(); err != nil {
			return err
		}
		c.Enabled = false
		if err = saveConfig(*configPath, c); err != nil {
			return err
		}
		return output(map[string]any{"enabled": false, "runtimeStopRequested": true})
	default:
		return errors.New("unknown_command")
	}
}
func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	if err := mainAction(ctx, os.Args[1:]); err != nil {
		_ = output(map[string]any{"ok": false, "error": safeError(err)})
		os.Exit(1)
	}
}
