//go:build windows

package main

import (
	"bufio"
	"bytes"
	"errors"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
)

var (
	attachConsole         = kernel32.NewProc("AttachConsole")
	freeConsole           = kernel32.NewProc("FreeConsole")
	setConsoleCtrlHandler = kernel32.NewProc("SetConsoleCtrlHandler")
	// DnsFlushResolverCache is what ipconfig /flushdns calls.
	dnsFlushResolverCache = windows.NewLazySystemDLL("dnsapi.dll").NewProc("DnsFlushResolverCache")
	consoleMutex          sync.Mutex
)

const keptLines = 500

type logLine struct {
	seq  int64
	text string
}

// core runs sing-box.exe for one session at a time: the configuration on stdin, its output
// kept in memory for that session's Logs, nothing written to disk.
type core struct {
	binary string

	mutex            sync.Mutex
	owner            uint64 // the session running it; 0 when none
	last             uint64 // the session that started the last run, whose log this is
	state            string // stopped, starting, running
	end              string // why the last run ended
	port             int
	mixed            int
	secret           string
	command          *exec.Cmd
	job              *childJob
	exited           chan struct{}
	observation      *childExit
	terminationError bool
	logs             []logLine
	next             int64
}

func newCore(binary string) *core { return &core{binary: binary, state: "stopped"} }

func (c *core) start(owner uint64, config []byte, port int, secret string, mixed int, beforeResume ...func() error) error {
	c.mutex.Lock()
	if c.owner != 0 && c.owner != owner {
		c.mutex.Unlock()
		return errors.New("busy")
	}
	c.mutex.Unlock()
	previous := c.abort(owner, "stopped")
	if previous != nil {
		select {
		case <-previous.done:
			if !previous.confirmed.Load() {
				return errors.New("termination_unconfirmed")
			}
		case <-time.After(2 * time.Second):
			return errors.New("termination_unconfirmed")
		}
	}

	command := exec.Command(c.binary, "run", "-c", "stdin", "--disable-color")
	command.Stdin = bytes.NewReader(config)
	output, err := command.StdoutPipe()
	if err != nil {
		return err
	}
	command.Stderr = command.Stdout
	// A console of its own, hidden, so it can be asked to stop the way Ctrl+C would.
	command.SysProcAttr = &syscall.SysProcAttr{CreationFlags: windows.CREATE_NEW_CONSOLE | windows.CREATE_SUSPENDED, HideWindow: true}
	var job *childJob
	started := false
	err = launchContained(
		func() error { var err error; job, err = newChildJob(); return err },
		func() error { err := command.Start(); started = err == nil; return err },
		func() error { return job.assign(command.Process.Pid) },
		func() error {
			for _, check := range beforeResume {
				if err := check(); err != nil {
					return err
				}
			}
			return resumeContainedChild(command.Process.Pid)
		},
		func() {
			if started {
				_ = command.Process.Kill()
				go func() { _ = command.Wait() }()
			}
			job.close()
		},
	)
	if err != nil {
		return err
	}
	observation := newChildExit()
	exited := observation.done

	c.mutex.Lock()
	c.owner, c.last, c.state, c.end, c.port, c.secret, c.command, c.exited = owner, owner, "starting", "", port, secret, command, exited
	c.mixed = mixed
	c.observation = observation
	c.terminationError = false
	c.job = job
	c.logs = nil
	c.mutex.Unlock()

	go func() {
		lines := bufio.NewScanner(output)
		lines.Buffer(make([]byte, 64<<10), 1<<20)
		for lines.Scan() {
			c.log(lines.Text())
		}
	}()
	go func() {
		err := command.Wait()
		confirmed := command.ProcessState != nil && command.ProcessState.Exited()
		_ = job.close()
		c.mutex.Lock()
		if c.command == command {
			if c.end == "" {
				c.end = "exited " + exitCode(err)
			}
			if confirmed {
				c.state, c.owner, c.command, c.job, c.mixed = "stopped", 0, nil, nil, 0
			} else {
				c.state = "unconfirmed"
				c.end = "termination_unconfirmed"
				c.terminationError = true
			}
		}
		c.mutex.Unlock()
		observation.finish(confirmed)
		flushResolver()
	}()
	go c.watch(command, port, secret)
	return nil
}

// watch marks the core running once its API answers, and stops it if it never does.
func (c *core) watch(command *exec.Cmd, port int, secret string) {
	client := &http.Client{Timeout: time.Second}
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		c.mutex.Lock()
		gone := c.command != command
		c.mutex.Unlock()
		if gone {
			return
		}
		request, _ := http.NewRequest("GET", "http://127.0.0.1:"+strconv.Itoa(port)+"/version", nil)
		request.Header.Set("Authorization", "Bearer "+secret)
		if response, err := client.Do(request); err == nil {
			response.Body.Close()
			if response.StatusCode == 200 {
				c.mutex.Lock()
				if c.command == command {
					c.state = "running"
				}
				c.mutex.Unlock()
				flushResolver()
				return
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	c.mutex.Lock()
	owner := c.owner
	same := c.command == command
	c.mutex.Unlock()
	if same {
		c.stop(owner, "no answer")
	}
}

// Authorization loss cuts the process immediately; process reaping and resolver cleanup are separate.
func (c *core) abort(owner uint64, reason string) *childExit {
	c.mutex.Lock()
	command, job, observation := c.command, c.job, c.observation
	if command == nil {
		valid := owner == 0 || c.last == owner
		c.mutex.Unlock()
		if valid {
			return observation
		}
		return nil
	}
	if owner != 0 && c.owner != owner {
		c.mutex.Unlock()
		return nil
	}
	c.end, c.state = reason, "stopping"
	c.mutex.Unlock()
	jobErr := job.close()
	killErr := command.Process.Kill()
	c.mutex.Lock()
	if c.command == command && (jobErr != nil || killErr != nil && killErr != os.ErrProcessDone) {
		c.terminationError = true
	}
	c.mutex.Unlock()
	return observation
}

func (c *core) markStopUnconfirmed(owner uint64, observation *childExit) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	if c.owner == owner && c.command != nil && c.observation == observation && !observation.confirmed.Load() {
		c.state = "unconfirmed"
		c.terminationError = true
	}
}

// stop ends the core if [owner] started it (0: whoever did), asking it first as Ctrl+C would
// so it takes its routes and adapter down itself, and ending it outright after eight seconds.
func (c *core) stop(owner uint64, reason string) {
	c.mutex.Lock()
	command, exited := c.command, c.exited
	if command == nil || (owner != 0 && c.owner != owner) {
		c.mutex.Unlock()
		return
	}
	c.end = reason
	c.mutex.Unlock()

	interrupt(command.Process.Pid)
	select {
	case <-exited:
	case <-time.After(8 * time.Second):
		_ = command.Process.Kill()
		<-exited
	}
}

func (c *core) status(owner uint64, since int64) map[string]any {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	reply := map[string]any{"ok": true, "state": c.state, "end": c.end}
	if c.owner != 0 && c.owner != owner {
		// Someone else's connection: that it exists, nothing more.
		reply["state"] = "busy"
		return reply
	}
	if c.last != owner {
		// The last run was another session's: its log is not this one's to read.
		return reply
	}
	if c.state != "stopped" {
		if c.mixed > 0 {
			reply["mixedPort"] = c.mixed
		}
		reply["port"], reply["secret"] = c.port, c.secret
	}
	var lines []string
	for _, line := range c.logs {
		if line.seq >= since {
			lines = append(lines, line.text)
		}
	}
	reply["logs"], reply["next"] = lines, c.next
	return reply
}

func (c *core) log(text string) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.logs = append(c.logs, logLine{c.next, strings.TrimRight(text, "\r")})
	c.next++
	if len(c.logs) > keptLines {
		c.logs = c.logs[len(c.logs)-keptLines:]
	}
}

// interrupt sends Ctrl+C to the core's own console: the service attaches to it for a moment,
// with its own handling of Ctrl+C switched off for good (a service has no use for it).
func interrupt(pid int) {
	consoleMutex.Lock()
	defer consoleMutex.Unlock()
	freeConsole.Call()
	if ok, _, _ := attachConsole.Call(uintptr(pid)); ok == 0 {
		return
	}
	setConsoleCtrlHandler.Call(0, 1)
	_ = windows.GenerateConsoleCtrlEvent(windows.CTRL_C_EVENT, 0)
	freeConsole.Call()
}

func exitCode(err error) string {
	var exit *exec.ExitError
	if errors.As(err, &exit) {
		return strconv.Itoa(exit.ExitCode())
	}
	if err != nil {
		return "1"
	}
	return "0"
}

// flushResolver empties Windows' own DNS cache when a core starts answering and when it stops.
// With Fake IP, each run hands out stand-in addresses afresh from the start of its range, so an
// answer Windows kept from the last run (for up to ten minutes) could lead a program to another
// site; without Fake IP it costs one fresh lookup per name. Failure only leaves the cache as it was.
func flushResolver() {
	if dnsFlushResolverCache.Find() == nil {
		_, _, _ = dnsFlushResolverCache.Call()
	}
}
