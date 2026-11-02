// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"debug/elf"
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sync"
	"syscall"
	"time"
)

type cappedBuffer struct {
	buffer   bytes.Buffer
	limit    int
	overflow bool
}

func (b *cappedBuffer) Write(data []byte) (int, error) {
	length := len(data)
	remaining := b.limit - b.buffer.Len()
	if remaining < length {
		b.overflow = true
		if remaining > 0 {
			_, _ = b.buffer.Write(data[:remaining])
		}
	} else {
		_, _ = b.buffer.Write(data)
	}
	return length, nil
}
func runLimited(ctx context.Context, path string, args []string, stdin []byte, maximum int) ([]byte, error) {
	cmd := exec.CommandContext(ctx, path, args...)
	childAttributes(cmd)
	cmd.Env = []string{"PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LANG=C", "LC_ALL=C"}
	cmd.Stdin = bytes.NewReader(stdin)
	cmd.Stderr = io.Discard
	capture := &cappedBuffer{limit: maximum}
	if maximum > 0 {
		cmd.Stdout = capture
	} else {
		cmd.Stdout = io.Discard
	}
	if err := cmd.Run(); err != nil {
		return nil, errors.New("local_command_failed")
	}
	if capture.overflow {
		return nil, errors.New("local_command_output_exceeded")
	}
	return capture.buffer.Bytes(), nil
}
func trustedExecutable(path string, core bool) error {
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		return errors.New("required_executable_missing")
	}
	info, err := os.Stat(resolved)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0111 == 0 || info.Mode().Perm()&0022 != 0 {
		return errors.New("unsafe_executable")
	}
	expected := uint32(os.Geteuid())
	if runtime.GOOS == "linux" {
		expected = 0
	}
	for current := resolved; ; current = filepath.Dir(current) {
		stat, err := os.Stat(current)
		if err != nil {
			return errors.New("unsafe_executable_path")
		}
		owner, ok := stat.Sys().(*syscall.Stat_t)
		if !ok || owner.Uid != expected && owner.Uid != 0 || stat.Mode().Perm()&0022 != 0 {
			return errors.New("unsafe_executable_path")
		}
		if current == filepath.Dir(current) {
			break
		}
	}
	if core && runtime.GOOS == "linux" {
		file, err := elf.Open(resolved)
		if err != nil {
			return errors.New("core_is_not_native_elf")
		}
		defer file.Close()
		if runtime.GOARCH != "arm64" || file.Machine != elf.EM_AARCH64 || file.Class != elf.ELFCLASS64 {
			return errors.New("unsupported_core_architecture")
		}
	}
	return nil
}

type Process interface {
	Done() <-chan error
	Stop()
}
type Core interface {
	Check(context.Context, []byte) error
	Start(context.Context, []byte, []Stub) (Process, error)
	Version(context.Context) (string, error)
}
type ExecCore struct{ Config Config }

func (c ExecCore) Version(ctx context.Context) (string, error) {
	if err := trustedExecutable(c.Config.CorePath, true); err != nil {
		return "", err
	}
	versionCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	output, err := runLimited(versionCtx, c.Config.CorePath, []string{"version"}, nil, 4096)
	if err != nil {
		return "", errors.New("core_version_check_failed")
	}
	match := regexp.MustCompile(`(?m)^sing-box version (1\.14\.[0-9]+)(?:\s|$)`).FindSubmatch(output)
	if len(match) != 2 {
		return "", errors.New("unsupported_core_version")
	}
	return string(match[1]), nil
}
func (c ExecCore) write(data []byte) (string, func(), error) {
	directory, err := os.MkdirTemp(c.Config.RuntimeDir, "core-")
	if err != nil {
		return "", nil, errors.New("core_staging_failed")
	}
	path := filepath.Join(directory, "config.json")
	if err = os.WriteFile(path, data, 0600); err != nil {
		os.RemoveAll(directory)
		return "", nil, errors.New("core_staging_failed")
	}
	return path, func() { _ = os.RemoveAll(directory) }, nil
}
func (c ExecCore) Check(ctx context.Context, data []byte) error {
	if err := trustedExecutable(c.Config.CorePath, true); err != nil {
		return err
	}
	path, cleanup, err := c.write(data)
	if err != nil {
		return err
	}
	defer cleanup()
	check, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	if _, err = runLimited(check, c.Config.CorePath, []string{"check", "-c", path}, nil, 0); err != nil {
		return errors.New("core_check_failed")
	}
	return nil
}

type execProcess struct {
	cmd     *exec.Cmd
	cancel  context.CancelFunc
	done    chan error
	once    sync.Once
	cleanup func()
}

func (p *execProcess) Done() <-chan error { return p.done }
func (p *execProcess) Stop() {
	p.once.Do(func() {
		p.cancel()
		if p.cmd.Process != nil {
			_ = p.cmd.Process.Kill()
		}
	})
}
func (c ExecCore) Start(ctx context.Context, data []byte, stubs []Stub) (Process, error) {
	if err := trustedExecutable(c.Config.CorePath, true); err != nil {
		return nil, err
	}
	for _, stub := range stubs {
		if err := rootConnectionCheck(net.JoinHostPort("127.0.0.1", itoa(stub.Port))); err != nil {
			return nil, err
		}
	}
	path, cleanup, err := c.write(data)
	if err != nil {
		return nil, err
	}
	childCtx, cancel := context.WithCancel(ctx)
	cmd := exec.CommandContext(childCtx, c.Config.CorePath, "run", "-c", path)
	childAttributes(cmd)
	cmd.Env = []string{"PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LANG=C", "LC_ALL=C"}
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	process := &execProcess{cmd: cmd, cancel: cancel, done: make(chan error, 1), cleanup: cleanup}
	if err = cmd.Start(); err != nil {
		cancel()
		cleanup()
		return nil, errors.New("managed_core_start_failed")
	}
	go func() { err := cmd.Wait(); cleanup(); process.done <- err; close(process.done) }()
	deadline := time.NewTimer(3 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(25 * time.Millisecond)
	defer tick.Stop()
	for {
		all := true
		for _, stub := range stubs {
			connection, err := net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", itoa(stub.Port)), 80*time.Millisecond)
			if err != nil {
				all = false
				break
			}
			_ = connection.Close()
		}
		if all && childCtx.Err() == nil {
			return process, nil
		}
		select {
		case <-childCtx.Done():
			process.Stop()
			return process, errors.New("managed_core_authorization_cancelled")
		case <-process.done:
			return process, errors.New("managed_core_stopped")
		case <-deadline.C:
			process.Stop()
			return process, errors.New("managed_core_not_ready")
		case <-tick.C:
		}
	}
}
func syntheticCoreConfig() []byte {
	return []byte(`{"log":{"disabled":true},"inbounds":[{"type":"socks","tag":"offline-check","listen":"127.0.0.1","listen_port":999}],"outbounds":[{"type":"hysteria2","tag":"probe-relay","server":"192.0.2.10","server_port":443,"password":"synthetic-capability-probe","tls":{"enabled":true,"server_name":"tls.invalid"}},{"type":"vless","tag":"probe-vless","server":"192.0.2.11","server_port":443,"uuid":"33333333-3333-4333-8333-333333333333","flow":"xtls-rprx-vision","tls":{"enabled":true,"server_name":"tls.invalid","utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,"public_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE","short_id":"0123"}}},{"type":"socks","tag":"probe-exit","server":"192.0.2.12","server_port":1080,"version":"5","detour":"probe-relay"}],"route":{"rules":[{"action":"reject"}]}}`)
}
