package main

import (
	"sync/atomic"
	"time"
)

// The child cannot execute its application code until a kill-on-parent lifetime is attached.
func launchContained(create, suspended, assign, resume func() error, abort func()) error {
	for _, step := range []func() error{create, suspended, assign, resume} {
		if err := step(); err != nil {
			abort()
			return err
		}
	}
	return nil
}

// An exit observation belongs to one child, even when a later child takes its place.
type childExit struct {
	done      chan struct{}
	confirmed atomic.Bool
}

func newChildExit() *childExit             { return &childExit{done: make(chan struct{})} }
func (e *childExit) finish(confirmed bool) { e.confirmed.Store(confirmed); close(e.done) }

// A termination request is not evidence that the process exited.
func observeChildExit(exit *childExit, timeout time.Duration, report func(string)) {
	if exit == nil {
		report("failed")
		return
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-exit.done:
		if exit.confirmed.Load() {
			report("stopped")
		} else {
			report("failed")
		}
	case <-timer.C:
		report("failed")
		<-exit.done
		if exit.confirmed.Load() {
			report("stopped")
		}
	}
}
