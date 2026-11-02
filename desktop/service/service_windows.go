//go:build windows

package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
	"golang.org/x/sys/windows/svc"
)

const (
	serviceName = "KOneService"
	pipeName    = `\\.\pipe\KOne`
	// SYSTEM and Administrators in full; interactive users may read and write, nobody else may open it.
	pipeSecurity = "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)"
)

var (
	kernel32                    = windows.NewLazySystemDLL("kernel32.dll")
	getNamedPipeClientProcessId = kernel32.NewProc("GetNamedPipeClientProcessId")
	peekControllerPipe          = kernel32.NewProc("PeekNamedPipe")
)

// runService serves KOne until Windows stops the service (or, in a console, until Ctrl+C).
func runService(console bool) error {
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	home := filepath.Dir(executable)
	server := &server{home: home, console: console, core: newCore(filepath.Join(home, "sing-box.exe")),
		sets: &ruleSetStore{folder: filepath.Join(home, "rules", "sets"), parse: singBoxParses(filepath.Join(home, "sing-box.exe"))}}
	stopNetwork := server.watchControllerNetwork()
	defer stopNetwork()
	if console {
		go func() {
			interrupt := make(chan os.Signal, 1)
			signal.Notify(interrupt, os.Interrupt)
			<-interrupt
			server.core.stop(0, "stopped")
			os.Exit(0)
		}()
		return server.serve()
	}
	return svc.Run(serviceName, &handler{server: server})
}

type handler struct{ server *server }

func (h *handler) Execute(_ []string, requests <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	failed := make(chan error, 1)
	go func() { failed <- h.server.serve() }()
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown | svc.AcceptPowerEvent}
	for {
		select {
		case <-failed:
			// The pipe could not be made (another program holds its name): let Windows restart the service.
			h.server.core.stop(0, "stopped")
			return true, 1
		case request := <-requests:
			switch request.Cmd {
			case svc.Interrogate:
				status <- request.CurrentStatus
			case svc.PowerEvent:
				if request.EventType == 4 || request.EventType == 6 || request.EventType == 7 || request.EventType == 18 {
					h.server.controllerNetworkEpoch.Add(1)
					h.server.stopManaged(0, "controller_network_changed")
				}
			case svc.Stop, svc.Shutdown:
				h.server.stopManaged(0, "stopped")
				status <- svc.Status{State: svc.StopPending}
				h.server.core.stop(0, "stopped")
				return false, 0
			}
		}
	}
}

type server struct {
	home                   string // the folder of kone-service.exe, sing-box.exe and rules\
	console                bool
	core                   *core
	sets                   *ruleSetStore // the person's category lists, in rules\sets\
	sessions               atomic.Uint64
	managedMu              sync.Mutex
	controllerAckMu        sync.Mutex
	managed                *managedSession
	managedOwner           uint64
	controllerNetworkReady bool
	controllerNetworkEpoch atomic.Uint64
}

// serve makes instances of the pipe and gives each caller its own session. The first instance
// is made with FILE_FLAG_FIRST_PIPE_INSTANCE, so a program that took the name first is noticed
// rather than served beside.
func (s *server) serve() error {
	descriptor, err := windows.SecurityDescriptorFromString(pipeSecurity)
	if err != nil {
		return err
	}
	attributes := &windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: descriptor}
	name, _ := windows.UTF16PtrFromString(pipeName)
	first := true
	for {
		flags := uint32(windows.PIPE_ACCESS_DUPLEX)
		if first {
			flags |= windows.FILE_FLAG_FIRST_PIPE_INSTANCE
		}
		pipe, err := windows.CreateNamedPipe(name, flags,
			windows.PIPE_TYPE_BYTE|windows.PIPE_READMODE_BYTE|windows.PIPE_WAIT|windows.PIPE_REJECT_REMOTE_CLIENTS,
			windows.PIPE_UNLIMITED_INSTANCES, 64<<10, 64<<10, 0, attributes)
		if err != nil {
			return err
		}
		first = false
		if err := windows.ConnectNamedPipe(pipe, nil); err != nil && !errors.Is(err, windows.ERROR_PIPE_CONNECTED) {
			windows.CloseHandle(pipe)
			continue
		}
		go s.session(pipe)
	}
}

// session answers one caller, one line of JSON in, one line out, until it hangs up. A core this
// session started stops when it hangs up: KOne quitting, or crashing, ends the connection.
func (s *server) session(pipe windows.Handle) {
	connection := &pipeFile{handle: pipe}
	defer func() {
		windows.FlushFileBuffers(pipe)
		windows.DisconnectNamedPipe(pipe)
		windows.CloseHandle(pipe)
	}()
	if !s.trusted(pipe) {
		write(connection, map[string]any{"ok": false, "error": "untrusted"})
		return
	}
	id := s.sessions.Add(1)
	defer s.core.stop(id, "stopped")
	defer s.stopManaged(id, "controller_session_closed")
	reader := bufio.NewReaderSize(connection, 64<<10)
	for {
		line, err := readLine(reader)
		if err != nil {
			return
		}
		var request struct {
			ManagedProxy bool                `json:"managedProxy"`
			Controller   *ControllerBinding  `json:"controller"`
			Op           string              `json:"op"`
			Source       map[string]any      `json:"source"`
			Selected     string              `json:"selected"`
			Mode         string              `json:"mode"`
			Bypass       []string            `json:"bypass"`
			Direct       []string            `json:"direct"`
			Proxied      []string            `json:"proxied"`
			Routes       []Rule              `json:"routes"`
			BlockAds     bool                `json:"blockAds"`
			Remote       string              `json:"remoteDns"`
			Domestic     string              `json:"directDns"`
			IPv6         bool                `json:"ipv6"`
			Hosts        []Host              `json:"hosts"`
			Servers      []NameServer        `json:"nameServers"`
			Region       string              `json:"region"`
			FakeIP       bool                `json:"fakeIp"`
			Unmatched    bool                `json:"unmatchedDirect"`
			Countries    map[string][]string `json:"countries"`
			Since        int64               `json:"since"`
			Name         string              `json:"name"`
			Data         []byte              `json:"data"` // base64 in the JSON
			Digest       string              `json:"sha256"`
		}
		decoder := json.NewDecoder(strings.NewReader(line))
		decoder.UseNumber()
		if err := decoder.Decode(&request); err != nil {
			write(connection, map[string]any{"ok": false, "error": "bad request"})
			continue
		}
		var reply map[string]any
		switch request.Op {
		case "hello":
			reply = map[string]any{"ok": true, "version": version, "controllerProtocol": 1, "managedLeaseWatchdog": s.controllerNetworkReady, "managedProxySupported": s.controllerNetworkReady}
		case "connect":
			if request.Controller != nil {
				request.Controller.proxy = request.ManagedProxy
				request.Controller.alive = func() bool { ok, _, _ := peekControllerPipe.Call(uintptr(pipe), 0, 0, 0, 0, 0); return ok != 0 }
			}
			reply = s.connect(id, request.Source, Options{Selected: request.Selected, Mode: request.Mode, Bypass: request.Bypass,
				Direct: request.Direct, Proxied: request.Proxied, Routes: s.sets.Usable(request.Routes), BlockAds: request.BlockAds,
				Remote: request.Remote, Domestic: request.Domestic, IPv6: request.IPv6, Hosts: request.Hosts, NameServers: request.Servers, UnmatchedDirect: request.Unmatched, Region: request.Region, FakeIP: request.FakeIP, Countries: request.Countries}, request.Controller)
		case "status":
			reply = s.core.status(id, request.Since)
		case "ruleSets":
			reply = map[string]any{"ok": true, "sets": s.sets.Kept()}
		case "putRuleSet":
			if err := s.sets.Put(request.Name, request.Data, request.Digest); err != nil {
				reply = map[string]any{"ok": false, "error": "rule_set_invalid"}
			} else {
				reply = map[string]any{"ok": true}
			}
		case "disconnect":
			s.stopManaged(id, "stopped")
			s.core.stop(id, "stopped")
			reply = map[string]any{"ok": true}
		default:
			reply = map[string]any{"ok": false, "error": "unknown op"}
		}
		if write(connection, reply) != nil {
			return
		}
	}
}

// connect builds the configuration from the source itself, the fixed template with only the
// nodes and the Claude block taken from the source and the person's choices in [chosen] (all
// checked again in Build), and starts the core on it.
func (s *server) connect(id uint64, source map[string]any, chosen Options, bindings ...*ControllerBinding) map[string]any {
	var controller *ControllerBinding
	if len(bindings) > 0 {
		controller = bindings[0]
	}
	networkEpoch := s.controllerNetworkEpoch.Load()
	var guard *managedSession
	if controller != nil {
		if !s.controllerNetworkReady {
			return map[string]any{"ok": false, "error": "controller_service_unsupported"}
		}
		client, err := newControllerClient(*controller)
		if err != nil {
			return map[string]any{"ok": false, "error": err.Error()}
		}
		ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
		prepared, authoritative, err := client.prepare(ctx, source, serviceElapsedMillis)
		cancel()
		if err != nil {
			return map[string]any{"ok": false, "error": err.Error()}
		}
		guard, source = prepared, authoritative
	}
	port, err := freePort()
	if err != nil {
		return map[string]any{"ok": false, "error": err.Error()}
	}
	secret := make([]byte, 16)
	if _, err := rand.Read(secret); err != nil {
		return map[string]any{"ok": false, "error": err.Error()}
	}
	options := chosen
	options.Rules = filepath.ToSlash(filepath.Join(s.home, "rules"))
	options.Port = port
	options.Secret = hex.EncodeToString(secret)
	options.Tun = controller == nil || !controller.proxy
	options.Mixed = 0
	if controller != nil && controller.proxy {
		mixed, err := freePort()
		if err != nil {
			return map[string]any{"ok": false, "error": "controller_invalid"}
		}
		options.Mixed = mixed
	}
	if controller != nil {
		origin, parseErr := url.Parse(controller.Endpoint)
		if parseErr != nil {
			return map[string]any{"ok": false, "error": "controller_invalid"}
		}
		options.ControllerHost = origin.Hostname()
		options.ControllerPort = 443
		if origin.Port() != "" {
			port, err := strconv.Atoi(origin.Port())
			if err != nil || port < 1 || port > 65535 {
				return map[string]any{"ok": false, "error": "controller_invalid"}
			}
			options.ControllerPort = port
		}
	}
	config, _, err := Build(source, options)
	if err != nil {
		return map[string]any{"ok": false, "error": "config_invalid"}
	}
	text, err := json.Marshal(config)
	if err != nil {
		return map[string]any{"ok": false, "error": "config_invalid"}
	}
	s.managedMu.Lock()
	s.core.mutex.Lock()
	busy := s.core.owner != 0 && s.core.owner != id
	s.core.mutex.Unlock()
	if busy {
		s.managedMu.Unlock()
		if guard != nil {
			guard.close()
		}
		return map[string]any{"ok": false, "error": "busy"}
	}
	if guard != nil {
		guard.reportMu = &s.controllerAckMu
		guard.reportAllowed = func(status string) bool {
			s.managedMu.Lock()
			defer s.managedMu.Unlock()
			if status == "stopped" || status == "failed" {
				return s.managed == nil || s.managed == guard || s.managed.info.DeviceID != guard.info.DeviceID
			}
			return s.managed == guard && s.managedOwner == id
		}
	}
	previous := s.managed
	s.managed, s.managedOwner = guard, id
	if previous != nil {
		previous.close()
	}
	start := func() error {
		if guard != nil && (networkEpoch != s.controllerNetworkEpoch.Load() || controller.alive != nil && !controller.alive()) {
			return controllerDenied("network_changed")
		}
		return s.core.start(id, text, options.Port, options.Secret, options.Mixed, func() error {
			if guard != nil && (!guard.validLocked() || networkEpoch != s.controllerNetworkEpoch.Load() || controller.alive != nil && !controller.alive()) {
				return controllerDenied("expired")
			}
			return nil
		})
	}
	if guard != nil {
		err = guard.start(start)
	} else {
		err = start()
	}
	if err != nil {
		s.managed = nil
		if guard != nil {
			guard.close()
		}
		s.core.abort(id, "stopped")
	}
	s.managedMu.Unlock()
	if err != nil {
		return map[string]any{"ok": false, "error": err.Error()}
	}
	if guard != nil {
		guard.watch(func() bool {
			s.core.mutex.Lock()
			defer s.core.mutex.Unlock()
			return s.core.owner == id && s.core.state == "running"
		}, func(reason string) { s.finishManaged(id, guard, reason) })
	}
	return map[string]any{"ok": true}
}

func (s *server) finishManaged(id uint64, guard *managedSession, reason string) {
	s.managedMu.Lock()
	current := s.managed == guard && s.managedOwner == id
	var observation *childExit
	if current {
		s.managed = nil
		observation = s.core.abort(id, reason)
	}
	s.managedMu.Unlock()
	guard.close()
	if current {
		go observeChildExit(observation, 2*time.Second, func(status string) {
			if status == "failed" {
				s.core.markStopUnconfirmed(id, observation)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			_ = guard.ack(ctx, status)
		})
	}
}

func (s *server) stopManaged(id uint64, reason string) {
	s.managedMu.Lock()
	guard, owner := s.managed, s.managedOwner
	s.managedMu.Unlock()
	if guard != nil && (id == 0 || id == owner) {
		s.finishManaged(owner, guard, reason)
	}
}

// trusted is true for the KOne.exe installed beside this service (two levels up from
// core\kone-service.exe), whose folder only administrators can write. A test machine may allow
// any caller with HKLM\SOFTWARE\KOne AllowAnyClient=1, which again only an administrator can set.
func (s *server) trusted(pipe windows.Handle) bool {
	if s.console || allowAnyClient() {
		return true
	}
	var pid uint32
	if ok, _, _ := getNamedPipeClientProcessId.Call(uintptr(pipe), uintptr(unsafe.Pointer(&pid))); ok == 0 {
		return false
	}
	process, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return false
	}
	defer windows.CloseHandle(process)
	buffer := make([]uint16, windows.MAX_LONG_PATH)
	size := uint32(len(buffer))
	if err := windows.QueryFullProcessImageName(process, 0, &buffer[0], &size); err != nil {
		return false
	}
	caller := filepath.Clean(windows.UTF16ToString(buffer[:size]))
	expected := filepath.Clean(filepath.Join(filepath.Dir(s.home), "KOne.exe"))
	return strings.EqualFold(caller, expected)
}

func allowAnyClient() bool {
	key, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\KOne`, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer key.Close()
	value, _, err := key.GetIntegerValue("AllowAnyClient")
	return err == nil && value == 1
}

// hideWindow keeps a helper run by the service (sing-box check) from opening a console.
func hideWindow(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
}

func freePort() (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

// pipeFile reads and writes one end of the pipe.
type pipeFile struct{ handle windows.Handle }

func (p *pipeFile) Read(buffer []byte) (int, error) {
	var done uint32
	err := windows.ReadFile(p.handle, buffer, &done, nil)
	if err != nil {
		if errors.Is(err, windows.ERROR_BROKEN_PIPE) || errors.Is(err, windows.ERROR_PIPE_NOT_CONNECTED) {
			return int(done), io.EOF
		}
		return int(done), err
	}
	if done == 0 && len(buffer) > 0 {
		return 0, io.EOF
	}
	return int(done), nil
}

func (p *pipeFile) Write(buffer []byte) (int, error) {
	var done uint32
	err := windows.WriteFile(p.handle, buffer, &done, nil)
	return int(done), err
}

func write(out io.Writer, reply map[string]any) error {
	text, err := json.Marshal(reply)
	if err != nil {
		return err
	}
	_, err = out.Write(append(text, '\n'))
	return err
}

// readLine reads one request, refusing one longer than maxMessage.
func readLine(reader *bufio.Reader) (string, error) {
	var line []byte
	for {
		chunk, more, err := reader.ReadLine()
		if err != nil {
			return "", err
		}
		line = append(line, chunk...)
		if len(line) > maxMessage {
			return "", errors.New("request too long")
		}
		if !more {
			return string(line), nil
		}
	}
}
