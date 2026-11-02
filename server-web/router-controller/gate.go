// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"sync"
	"time"
)

type Clock interface {
	Boot() (time.Duration, error)
	Mono() time.Duration
}
type systemClock struct{ start time.Time }

func (c systemClock) Boot() (time.Duration, error) { return bootNow() }
func (c systemClock) Mono() time.Duration          { return time.Since(c.start) }

type Gate struct {
	mu       sync.Mutex
	clock    Clock
	epoch    uint64
	deadline time.Duration
	cancel   context.CancelFunc
	reason   string
	wake     chan struct{}
}

func newGate(clock Clock) *Gate { return &Gate{clock: clock, wake: make(chan struct{}, 1)} }
func (g *Gate) Epoch() uint64   { g.mu.Lock(); defer g.mu.Unlock(); return g.epoch }
func (g *Gate) invalidateLocked(reason string) {
	g.epoch++
	g.deadline = 0
	g.reason = reason
	if g.cancel != nil {
		g.cancel()
		g.cancel = nil
	}
	select {
	case g.wake <- struct{}{}:
	default:
	}
}
func (g *Gate) Invalidate(reason string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.invalidateLocked(reason)
}
func (g *Gate) Arm(parent context.Context, epoch uint64, deadline time.Duration) (context.Context, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	now, err := g.clock.Boot()
	if err != nil || epoch != g.epoch || deadline <= now {
		return nil, errors.New("authorization_epoch_changed")
	}
	if g.cancel != nil {
		g.cancel()
	}
	ctx, cancel := context.WithCancel(parent)
	g.cancel = cancel
	g.deadline = deadline
	g.reason = ""
	return ctx, nil
}
func (g *Gate) Renew(epoch uint64, deadline time.Duration) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	now, err := g.clock.Boot()
	if err != nil || g.cancel == nil || epoch != g.epoch || deadline <= now {
		g.invalidateLocked("lease_renewal_invalid")
		return errors.New("authorization_epoch_changed")
	}
	g.deadline = deadline
	return nil
}
func (g *Gate) Check() {
	g.mu.Lock()
	defer g.mu.Unlock()
	now, err := g.clock.Boot()
	if err != nil {
		g.invalidateLocked("boottime_clock_failed")
		return
	}
	if g.cancel != nil && now >= g.deadline {
		g.invalidateLocked("lease_expired")
	}
}
func (g *Gate) Active() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	now, err := g.clock.Boot()
	return err == nil && g.cancel != nil && now < g.deadline
}
func (g *Gate) Watch(ctx context.Context) {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	previousBoot, err := g.clock.Boot()
	if err != nil {
		g.Invalidate("boottime_clock_failed")
		return
	}
	previousMono := g.clock.Mono()
	for {
		select {
		case <-ctx.Done():
			g.Invalidate("daemon_stopped")
			return
		case <-ticker.C:
			nowBoot, err := g.clock.Boot()
			nowMono := g.clock.Mono()
			if err != nil {
				g.Invalidate("boottime_clock_failed")
				continue
			}
			if nowBoot < previousBoot || nowMono < previousMono || nowBoot-previousBoot-(nowMono-previousMono) > 250*time.Millisecond {
				g.Invalidate("resume_or_clock_epoch_changed")
			}
			previousBoot, previousMono = nowBoot, nowMono
			g.Check()
		}
	}
}
func (g *Gate) Reject(reason string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.epoch++
	g.deadline = 0
	g.reason = reason
	if g.cancel != nil {
		g.cancel()
		g.cancel = nil
	}
}
func (g *Gate) InvalidateIf(epoch uint64, reason string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.epoch == epoch {
		g.invalidateLocked(reason)
	}
}
func (g *Gate) Deadline() time.Duration { g.mu.Lock(); defer g.mu.Unlock(); return g.deadline }
