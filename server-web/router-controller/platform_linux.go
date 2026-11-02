//go:build linux

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

func bootNow() (time.Duration, error) {
	var ts syscall.Timespec
	_, _, code := syscall.Syscall(syscall.SYS_CLOCK_GETTIME, 7, uintptr(unsafe.Pointer(&ts)), 0)
	if code != 0 {
		return 0, errors.New("boottime_clock_unavailable")
	}
	return time.Duration(ts.Sec)*time.Second + time.Duration(ts.Nsec), nil
}
func socketControl(mark uint32) func(string, string, syscall.RawConn) error {
	if mark == 0 {
		return nil
	}
	return func(_, _ string, raw syscall.RawConn) error {
		var setErr error
		err := raw.Control(func(fd uintptr) {
			setErr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_MARK, int(mark))
		})
		if err != nil || setErr != nil {
			return errors.New("control_socket_mark_failed")
		}
		return nil
	}
}
func childAttributes(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGKILL}
}
func lockFile(ctx context.Context, file *os.File) error {
	for {
		err := syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
		if err == nil {
			return nil
		}
		if err != syscall.EWOULDBLOCK && err != syscall.EAGAIN {
			return errors.New("file_lock_failed")
		}
		select {
		case <-ctx.Done():
			return errors.New("file_lock_busy")
		case <-time.After(20 * time.Millisecond):
		}
	}
}
func unlockFile(file *os.File) { _ = syscall.Flock(int(file.Fd()), syscall.LOCK_UN) }

func monitorNetwork(ctx context.Context, changed func(), failed func()) error {
	fd, err := syscall.Socket(syscall.AF_NETLINK, syscall.SOCK_RAW|syscall.SOCK_CLOEXEC|syscall.SOCK_NONBLOCK, syscall.NETLINK_ROUTE)
	if err != nil {
		return errors.New("network_monitor_unavailable")
	}
	groups := uint32(0x1 | 0x10 | 0x40 | 0x100 | 0x400)
	if err = syscall.Bind(fd, &syscall.SockaddrNetlink{Family: syscall.AF_NETLINK, Groups: groups}); err != nil {
		syscall.Close(fd)
		return errors.New("network_monitor_unavailable")
	}
	socket := os.NewFile(uintptr(fd), "controller-netlink")
	go func() {
		defer socket.Close()
		buffer := make([]byte, 64<<10)
		for {
			if ctx.Err() != nil {
				return
			}
			count, _, err := syscall.Recvfrom(fd, buffer, 0)
			if err == syscall.EAGAIN || err == syscall.EWOULDBLOCK {
				select {
				case <-ctx.Done():
					return
				case <-time.After(20 * time.Millisecond):
				}
				continue
			}
			if err != nil {
				if ctx.Err() == nil {
					failed()
				}
				return
			}
			messages, err := syscall.ParseNetlinkMessage(buffer[:count])
			if err != nil {
				failed()
				return
			}
			for _, message := range messages {
				switch message.Header.Type {
				case syscall.RTM_NEWLINK, syscall.RTM_DELLINK, syscall.RTM_NEWADDR, syscall.RTM_DELADDR, syscall.RTM_NEWROUTE, syscall.RTM_DELROUTE:
					changed()
				case syscall.NLMSG_OVERRUN, syscall.NLMSG_ERROR:
					failed()
					return
				}
			}
		}
	}()
	return nil
}
func rootConnectionCheck(address string) error {
	listener, err := net.Listen("tcp4", address)
	if err != nil {
		return errors.New("loopback_stub_port_unavailable")
	}
	return listener.Close()
}
func processIdentity(pid int) string {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return ""
	}
	end := strings.LastIndexByte(string(data), ')')
	if end < 0 {
		return ""
	}
	parts := strings.Fields(string(data[end+1:]))
	if len(parts) < 20 {
		return ""
	}
	return parts[19]
}
