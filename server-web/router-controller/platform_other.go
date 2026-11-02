//go:build !linux

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"syscall"
	"time"
)

func bootNow() (time.Duration, error) { return 0, errors.New("linux_boottime_required") }
func socketControl(mark uint32) func(string, string, syscall.RawConn) error {
	if mark == 0 {
		return nil
	}
	return func(string, string, syscall.RawConn) error { return errors.New("linux_socket_mark_required") }
}
func childAttributes(cmd *exec.Cmd)            {}
func lockFile(context.Context, *os.File) error { return errors.New("linux_file_lock_required") }
func unlockFile(*os.File)                      {}
func monitorNetwork(context.Context, func(), func()) error {
	return errors.New("linux_network_monitor_required")
}
func rootConnectionCheck(string) error { return errors.New("linux_root_required") }
func processIdentity(int) string       { return "" }
