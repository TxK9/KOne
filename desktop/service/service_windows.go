//go:build windows

package main

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
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
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
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
			case svc.Stop, svc.Shutdown:
				status <- svc.Status{State: svc.StopPending}
				h.server.core.stop(0, "stopped")
				return false, 0
			}
		}
	}
}

type server struct {
	home     string // the folder of kone-service.exe, sing-box.exe and rules\
	console  bool
	core     *core
	sets     *ruleSetStore // the person's category lists, in rules\sets\
	sessions atomic.Uint64
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
	reader := bufio.NewReaderSize(connection, 64<<10)
	for {
		line, err := readLine(reader)
		if err != nil {
			return
		}
		var request struct {
			Op       string         `json:"op"`
			Source   map[string]any `json:"source"`
			Selected string         `json:"selected"`
			Mode     string         `json:"mode"`
			Bypass   []string       `json:"bypass"`
			Direct   []string       `json:"direct"`
			Proxied  []string       `json:"proxied"`
			Routes   []Rule         `json:"routes"`
			BlockAds bool           `json:"blockAds"`
			Remote   string         `json:"remoteDns"`
			Domestic string         `json:"directDns"`
			IPv6     bool           `json:"ipv6"`
			Hosts    []Host         `json:"hosts"`
			Since    int64          `json:"since"`
			Name     string         `json:"name"`
			Data     []byte         `json:"data"` // base64 in the JSON
			Digest   string         `json:"sha256"`
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
			reply = map[string]any{"ok": true, "version": version}
		case "connect":
			reply = s.connect(id, request.Source, Options{Selected: request.Selected, Mode: request.Mode, Bypass: request.Bypass,
				Direct: request.Direct, Proxied: request.Proxied, Routes: s.sets.Usable(request.Routes), BlockAds: request.BlockAds,
				Remote: request.Remote, Domestic: request.Domestic, IPv6: request.IPv6, Hosts: request.Hosts})
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
func (s *server) connect(id uint64, source map[string]any, chosen Options) map[string]any {
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
	options.Tun = true
	options.Mixed = 0
	config, _, err := Build(source, options)
	if err != nil {
		return map[string]any{"ok": false, "error": "config_invalid"}
	}
	text, err := json.Marshal(config)
	if err != nil {
		return map[string]any{"ok": false, "error": "config_invalid"}
	}
	if err := s.core.start(id, text, options.Port, options.Secret); err != nil {
		return map[string]any{"ok": false, "error": err.Error()}
	}
	return map[string]any{"ok": true}
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
