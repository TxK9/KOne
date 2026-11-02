// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeClock struct {
	mu         sync.Mutex
	boot, mono time.Duration
	notify     chan struct{}
}

func (c *fakeClock) Boot() (time.Duration, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.notify != nil {
		select {
		case c.notify <- struct{}{}:
		default:
		}
	}
	return c.boot, nil
}
func (c *fakeClock) Mono() time.Duration { c.mu.Lock(); defer c.mu.Unlock(); return c.mono }
func (c *fakeClock) advance(boot, mono time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.boot += boot
	c.mono += mono
}

type fakeProcess struct {
	done      chan error
	once      sync.Once
	stopCount atomic.Int32
	hold      bool
}

func (p *fakeProcess) Done() <-chan error { return p.done }
func (p *fakeProcess) Stop() {
	p.stopCount.Add(1)
	if !p.hold {
		p.exit()
	}
}
func (p *fakeProcess) exit() { p.once.Do(func() { close(p.done) }) }

type fakeCore struct {
	mu             sync.Mutex
	starts, checks int
	current        *fakeProcess
	hold           bool
	checkError     error
}

func (c *fakeCore) Version(context.Context) (string, error) { return "1.14.4", nil }
func (c *fakeCore) Check(context.Context, []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.checks++
	return c.checkError
}
func (c *fakeCore) Start(ctx context.Context, _ []byte, _ []Stub) (Process, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if ctx.Err() != nil {
		return nil, errors.New("cancelled_before_start")
	}
	c.starts++
	p := &fakeProcess{done: make(chan error), hold: c.hold}
	c.current = p
	go func() { <-ctx.Done(); p.Stop() }()
	return p, nil
}
func (c *fakeCore) snapshot() (int, *fakeProcess) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.starts, c.current
}

type fakeWriter struct {
	mu           sync.Mutex
	activeWrites int
	fail         bool
}

func (w *fakeWriter) Apply(_ context.Context, _ Registry, wanted []Stub) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(wanted) > 0 {
		w.activeWrites++
		if w.fail {
			return 0, errors.New("stub_ownership_conflict")
		}
	}
	return 0, nil
}

type fakeControl struct {
	mu            sync.Mutex
	view          ConfigReply
	leaseError    error
	leaseHook     func()
	block         chan struct{}
	entered       chan struct{}
	stopAcks      int
	runningAcks   int
	assertStopped func()
	ttl           int
}

func (a *fakeControl) Preflight(context.Context) error { return nil }
func (a *fakeControl) Register(context.Context, bool) (ControllerMetadata, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return *a.view.Controller, nil
}
func (a *fakeControl) Configuration(context.Context) (ConfigReply, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.view, nil
}
func (a *fakeControl) Lease(ctx context.Context, view ConfigReply) (LeaseReply, error) {
	a.mu.Lock()
	err, hook, block, entered, ttl := a.leaseError, a.leaseHook, a.block, a.entered, a.ttl
	a.mu.Unlock()
	if entered != nil {
		select {
		case entered <- struct{}{}:
		default:
		}
	}
	if hook != nil {
		hook()
	}
	if block != nil {
		<-block
	}
	if err != nil {
		return LeaseReply{}, err
	}
	if ttl == 0 {
		ttl = 60
	}
	return LeaseReply{Format: 1, DeviceID: testDevice, Mode: "enabled", Required: true, TTLSeconds: ttl, AuthGeneration: view.Controller.AuthGeneration, ConfigRevision: view.ConfigRevision}, nil
}
func (a *fakeControl) Ack(_ context.Context, _ ConfigReply, status string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if status == "running" {
		a.runningAcks++
	}
	return nil
}
func (a *fakeControl) AckStopped(context.Context, ControllerMetadata) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.assertStopped != nil {
		a.assertStopped()
	}
	a.stopAcks++
	return nil
}
func (a *fakeControl) counts() (int, int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.runningAcks, a.stopAcks
}
func fakeDaemon(t *testing.T) (*Daemon, *fakeClock, *fakeControl, *fakeCore, *fakeWriter) {
	t.Helper()
	clock := &fakeClock{}
	api := &fakeControl{view: testView()}
	core := &fakeCore{}
	writer := &fakeWriter{}
	d := newDaemon(testConfig(t), api, core, writer, newRegistry(testDevice), clock)
	d.saveRegistry = nil
	d.saveStatus = nil
	return d, clock, api, core, writer
}
func waitClosed(t *testing.T, ch <-chan error) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(700 * time.Millisecond):
		t.Fatal("owned core was not cancelled independently")
	}
}
func TestNoManagedCoreBeforeFreshLeaseAndNoFalseStoppedAck(t *testing.T) {
	d, _, api, core, _ := fakeDaemon(t)
	api.leaseError = errors.New("control_transport_failed")
	if d.Step(context.Background()) == nil {
		t.Fatal("offline lease permitted")
	}
	starts, _ := core.snapshot()
	if starts != 0 {
		t.Fatal("core started without authority")
	}
	_, stopped := api.counts()
	if stopped != 0 {
		t.Fatal("stopped acknowledged without observing an owned process exit")
	}
}
func TestRenewalReusesCoreButRevocationWaitsForExitBeforeAck(t *testing.T) {
	d, _, api, core, _ := fakeDaemon(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := d.Step(ctx); err != nil {
		t.Fatal(err)
	}
	_, process := core.snapshot()
	api.assertStopped = func() {
		if !processFinished(process) {
			t.Error("stop ACK preceded process exit")
		}
	}
	if err := d.Step(ctx); err != nil {
		t.Fatal(err)
	}
	starts, _ := core.snapshot()
	if starts != 1 {
		t.Fatal("renewal restarted the core")
	}
	api.mu.Lock()
	api.leaseError = &APIError{Status: 403, Code: "authorization_revoked", Controller: api.view.Controller}
	api.mu.Unlock()
	if d.Step(ctx) == nil {
		t.Fatal("revocation ignored")
	}
	waitClosed(t, process.Done())
	_, stopped := api.counts()
	if stopped != 1 {
		t.Fatal("confirmed stop not acknowledged")
	}
}
func TestLeaseWatchdogStopsCoreWhileRenewalRequestIsBlocked(t *testing.T) {
	d, clock, api, core, _ := fakeDaemon(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := d.Step(ctx); err != nil {
		t.Fatal(err)
	}
	_, process := core.snapshot()
	go d.gate.Watch(ctx)
	block, entered := make(chan struct{}), make(chan struct{}, 1)
	api.mu.Lock()
	api.block = block
	api.entered = entered
	api.mu.Unlock()
	finished := make(chan error, 1)
	go func() { finished <- d.Step(ctx) }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("request did not block")
	}
	clock.advance(61*time.Second, 61*time.Second)
	waitClosed(t, process.Done())
	select {
	case <-finished:
		t.Fatal("test request was not independently blocked")
	default:
	}
	close(block)
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("expired request permitted")
		}
	case <-time.After(time.Second):
		t.Fatal("worker did not return")
	}
}
func TestNetworkEpochAndSuspendInvalidateAuthority(t *testing.T) {
	t.Run("between lease and start", func(t *testing.T) {
		d, _, api, core, _ := fakeDaemon(t)
		api.leaseHook = func() { d.gate.Invalidate("network_epoch_changed") }
		if d.Step(context.Background()) == nil {
			t.Fatal("stale epoch permitted")
		}
		starts, _ := core.snapshot()
		if starts != 0 {
			t.Fatal("stale epoch core started")
		}
	})
	t.Run("suspend", func(t *testing.T) {
		d, clock, _, core, _ := fakeDaemon(t)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		if err := d.Step(ctx); err != nil {
			t.Fatal(err)
		}
		_, process := core.snapshot()
		clock.mu.Lock()
		clock.notify = make(chan struct{}, 1)
		notify := clock.notify
		clock.mu.Unlock()
		go d.gate.Watch(ctx)
		select {
		case <-notify:
		case <-time.After(time.Second):
			t.Fatal("watchdog did not start")
		}
		clock.advance(10*time.Second, 0)
		waitClosed(t, process.Done())
	})
}
func TestUnconfirmedExitNeverAcknowledgesStoppedAndBlocksReplacement(t *testing.T) {
	d, _, api, core, _ := fakeDaemon(t)
	core.hold = true
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := d.Step(ctx); err != nil {
		t.Fatal(err)
	}
	_, process := core.snapshot()
	api.mu.Lock()
	api.leaseError = errors.New("control_transport_failed")
	api.mu.Unlock()
	if d.Step(ctx) == nil {
		t.Fatal("held process stop falsely succeeded")
	}
	_, stopped := api.counts()
	if stopped != 0 || d.Snapshot().StopConfirmed {
		t.Fatal("unconfirmed stop claimed")
	}
	starts, _ := core.snapshot()
	if starts != 1 {
		t.Fatal("unexpected process")
	}
	process.exit()
}
func TestCoreValidationAndStubFailureStayFailClosed(t *testing.T) {
	t.Run("validator", func(t *testing.T) {
		d, _, _, core, _ := fakeDaemon(t)
		core.checkError = errors.New("core_check_failed")
		if d.Step(context.Background()) == nil {
			t.Fatal("bad core accepted")
		}
		starts, _ := core.snapshot()
		if starts != 0 {
			t.Fatal("unvalidated core started")
		}
	})
	t.Run("stub ownership", func(t *testing.T) {
		d, _, api, core, writer := fakeDaemon(t)
		writer.fail = true
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		if d.Step(ctx) == nil {
			t.Fatal("foreign stub overwrite ignored")
		}
		_, p := core.snapshot()
		waitClosed(t, p.Done())
		running, _ := api.counts()
		if running != 0 {
			t.Fatal("failed stub plan reported running")
		}
	})
}
