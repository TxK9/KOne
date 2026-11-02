package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"net/url"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"time"
)

var controllerRevision = regexp.MustCompile(`^[0-9a-f]{64}$`)
var controllerSecret = regexp.MustCompile(`^[A-Za-z0-9_-]{16,256}$`)

// Only the authenticated local pipe carries this context; it is never logged or written.
type ControllerBinding struct {
	proxy          bool
	alive          func() bool
	Endpoint       string         `json:"endpoint"`
	DeviceID       string         `json:"deviceId"`
	Credential     string         `json:"credential"`
	ConfigRevision string         `json:"configRevision"`
	Revision       string         `json:"revision"`
	AuthGeneration int64          `json:"authGeneration"`
	ManagedSource  map[string]any `json:"managedSource"`
}

type controllerInfo struct {
	Format     int      `json:"format"`
	DeviceID   string   `json:"deviceId"`
	Mode       string   `json:"mode"`
	Required   bool     `json:"required"`
	Generation int64    `json:"authGeneration"`
	NodeIDs    []string `json:"nodeIds"`
}

type controllerDelivery struct {
	Format         int             `json:"format"`
	Kind           string          `json:"configKind"`
	Revision       string          `json:"revision"`
	ConfigRevision string          `json:"configRevision"`
	Config         map[string]any  `json:"config"`
	Controller     *controllerInfo `json:"controller"`
}

type controllerReply struct {
	controllerInfo
	Nonce          string  `json:"nonce"`
	ServerTime     float64 `json:"serverTime"`
	NotAfter       float64 `json:"notAfter"`
	TTL            int64   `json:"ttlSeconds"`
	ConfigRevision string  `json:"configRevision"`
}

type controllerFailure struct {
	code       string
	generation int64
}

func (e *controllerFailure) Error() string { return e.code }
func controllerDenied(code string) error {
	return &controllerFailure{code: "controller_" + code, generation: -1}
}

type controllerClient struct {
	base    string
	binding ControllerBinding
	http    *http.Client
}

func newControllerClient(binding ControllerBinding) (*controllerClient, error) {
	parsed, err := url.Parse(binding.Endpoint)
	if err != nil || len(binding.Endpoint) > 512 || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" || parsed.Opaque != "" || strings.ContainsAny(binding.Endpoint, "\r\n\t ") || !controllerSecret.MatchString(binding.Credential) || binding.DeviceID == "" || len(binding.DeviceID) > 128 || binding.AuthGeneration < 0 || !controllerRevision.MatchString(binding.ConfigRevision) || !controllerRevision.MatchString(binding.Revision) || binding.ManagedSource == nil {
		return nil, controllerDenied("invalid")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.TLSClientConfig = &tls.Config{MinVersion: tls.VersionTLS12}
	return &controllerClient{base: strings.TrimRight(binding.Endpoint, "/"), binding: binding, http: &http.Client{Transport: transport, Timeout: 8 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("redirect refused") }}}, nil
}

func (c *controllerClient) call(ctx context.Context, method, path string, body any, out any) error {
	var content io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return controllerDenied("invalid")
		}
		content = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, c.base+path, content)
	if err != nil {
		return controllerDenied("invalid")
	}
	request.Header.Set("Authorization", "Bearer "+c.binding.Credential)
	request.Header.Set("X-KOne-Controller-Protocol", "1")
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cache-Control", "no-store")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := c.http.Do(request)
	if err != nil {
		return controllerDenied("unavailable")
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, (2<<20)+1))
	if err != nil || len(data) > 2<<20 {
		return controllerDenied("invalid")
	}
	if response.StatusCode != 200 {
		var denied struct {
			Code       string `json:"errorCode"`
			Controller struct {
				Generation int64 `json:"authGeneration"`
			} `json:"controller"`
		}
		_ = json.Unmarshal(data, &denied)
		allowed := map[string]bool{"device_revoked": true, "account_denied": true, "authorization_revoked": true, "no_grants": true, "stale_generation": true, "stale_config": true, "client_upgrade_required": true, "lease_required": true}
		if !allowed[denied.Code] {
			denied.Code = "denied"
		}
		return &controllerFailure{code: "controller_" + denied.Code, generation: denied.Controller.Generation}
	}
	if out == nil {
		return nil
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	if decoder.Decode(out) != nil {
		return controllerDenied("invalid")
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return controllerDenied("invalid")
	}
	return nil
}

func controllerRegistration() map[string]any {
	return map[string]any{"protocol": 1, "platform": "windows", "clientVersion": version, "startupLease": true, "runtimeLease": true, "protocols": []string{"vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls", "socks", "http"}, "transports": []string{"tcp", "ws", "grpc", "httpupgrade", "quic"}}
}

// Fetching fresh configuration never authorizes traffic. The separate nonce-bound lease does.
func (c *controllerClient) prepare(ctx context.Context, combined map[string]any, clock func() int64) (*managedSession, map[string]any, error) {
	var registration struct {
		Controller controllerInfo `json:"controller"`
	}
	if err := c.call(ctx, "POST", "/api/client/controller/register", controllerRegistration(), &registration); err != nil {
		return nil, nil, err
	}
	info := registration.Controller
	if info.Format != 1 || info.DeviceID != c.binding.DeviceID || info.Generation != c.binding.AuthGeneration || (info.Mode != "legacy" && info.Mode != "enabled") || info.Required != (info.Mode == "enabled") {
		return nil, nil, controllerDenied("stale_generation")
	}
	var delivery controllerDelivery
	if err := c.call(ctx, "GET", "/api/client/config", nil, &delivery); err != nil {
		return nil, nil, err
	}
	if delivery.Format != 1 || delivery.Kind != "sing-box-source" || delivery.ConfigRevision != c.binding.ConfigRevision || delivery.Revision != c.binding.Revision || !reflect.DeepEqual(delivery.Config, c.binding.ManagedSource) {
		return nil, nil, controllerDenied("stale_config")
	}
	if delivery.Controller != nil {
		dc := delivery.Controller
		if dc.Format != 1 || dc.DeviceID != info.DeviceID || dc.Generation != info.Generation || dc.Mode != info.Mode || dc.Required != info.Required {
			return nil, nil, controllerDenied("stale_generation")
		}
		info.NodeIDs = append([]string(nil), dc.NodeIDs...)
		if info.Mode == "enabled" && len(info.NodeIDs) == 0 {
			return nil, nil, controllerDenied("invalid")
		}
	} else {
		return nil, nil, controllerDenied("invalid")
	}
	source, err := controlledSource(delivery.Config, combined)
	if err != nil {
		return nil, nil, err
	}
	session := &managedSession{client: c, info: info, clock: clock, delivery: delivery, done: make(chan struct{}), reportMu: &sync.Mutex{}}
	if err = session.renew(ctx); err != nil {
		return nil, nil, err
	}
	return session, source, nil
}

func controlledSource(managed, combined map[string]any) (map[string]any, error) {
	original, ok := managed["outbounds"].([]any)
	if !ok {
		return nil, controllerDenied("invalid")
	}
	all, ok := combined["outbounds"].([]any)
	if !ok || len(all) < len(original) || !reflect.DeepEqual(all[:len(original)], original) {
		return nil, controllerDenied("stale_config")
	}
	result := make(map[string]any, len(managed))
	for k, v := range managed {
		result[k] = v
	}
	result["outbounds"] = all
	return result, nil
}

func sameControllerIDs(a, b []string) bool {
	if len(a) != len(b) || len(a) > 512 {
		return false
	}
	seen := map[string]bool{}
	for _, id := range a {
		if id == "" || len(id) > 128 || seen[id] {
			return false
		}
		seen[id] = true
	}
	for _, id := range b {
		if !seen[id] {
			return false
		}
		delete(seen, id)
	}
	return len(seen) == 0
}

// The service owns this deadline independently of the GUI, pipe polling, and HTTP renewal.
type managedSession struct {
	client         *controllerClient
	info           controllerInfo
	delivery       controllerDelivery
	clock          func() int64
	mu             sync.Mutex
	deadline       int64
	lastClock      int64
	closed         bool
	done           chan struct{}
	cancel         context.CancelFunc
	stop           func(string)
	stopGeneration int64
	reportMu       *sync.Mutex
	reportAllowed  func(string) bool
}

func (s *managedSession) renew(ctx context.Context) error {
	nonceBytes := make([]byte, 32)
	if _, err := rand.Read(nonceBytes); err != nil {
		return controllerDenied("invalid")
	}
	nonce := hex.EncodeToString(nonceBytes)
	started := s.clock()
	var reply controllerReply
	err := s.client.call(ctx, "POST", "/api/client/controller/lease", map[string]any{"protocol": 1, "nonce": nonce, "configRevision": s.delivery.ConfigRevision, "authGeneration": s.info.Generation}, &reply)
	if err != nil {
		return err
	}
	now := s.clock()
	duration := reply.NotAfter - reply.ServerTime
	if reply.Format != 1 || reply.Nonce != nonce || reply.DeviceID != s.info.DeviceID || reply.Mode != s.info.Mode || reply.Required != s.info.Required || reply.Generation != s.info.Generation || reply.ConfigRevision != s.delivery.ConfigRevision || !sameControllerIDs(reply.NodeIDs, s.info.NodeIDs) || reply.TTL < 1 || reply.TTL > 60 || math.IsNaN(duration) || math.IsInf(duration, 0) || duration <= 0 || duration > float64(reply.TTL)+0.001 || started < 0 || now < started || now-started >= reply.TTL*1000 {
		return controllerDenied("invalid")
	}
	lifetime := int64(math.Floor(duration * 1000))
	if lifetime > reply.TTL*1000 {
		lifetime = reply.TTL * 1000
	}
	if lifetime <= 0 || now-started >= lifetime || started > math.MaxInt64-lifetime {
		return controllerDenied("invalid")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.lastClock > now {
		return controllerDenied("expired")
	}
	s.deadline = started + lifetime
	s.lastClock = now
	return nil
}

func (s *managedSession) validLocked() bool {
	now := s.clock()
	valid := !s.closed && now >= s.lastClock && now < s.deadline
	s.lastClock = now
	return valid
}
func (s *managedSession) start(start func() error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.validLocked() {
		return controllerDenied("expired")
	}
	err := start()
	if err == nil && !s.validLocked() {
		return controllerDenied("expired")
	}
	return err
}
func (s *managedSession) close() {
	s.mu.Lock()
	if !s.closed {
		s.closed = true
		close(s.done)
		if s.cancel != nil {
			s.cancel()
		}
	}
	s.mu.Unlock()
}
func (s *managedSession) deny(err error) {
	code := "controller_expired"
	var failure *controllerFailure
	if errors.As(err, &failure) {
		code = failure.code
		s.mu.Lock()
		s.stopGeneration = failure.generation
		s.mu.Unlock()
	}
	s.close()
	if s.stop != nil {
		s.stop(code)
	}
}
func (s *managedSession) ack(ctx context.Context, status string) error {
	s.reportMu.Lock()
	defer s.reportMu.Unlock()
	if s.reportAllowed != nil && !s.reportAllowed(status) {
		return nil
	}
	if status == "running" {
		s.mu.Lock()
		valid := s.validLocked()
		s.mu.Unlock()
		if !valid {
			return controllerDenied("expired")
		}
	}
	body := map[string]any{"protocol": 1, "authGeneration": s.info.Generation, "revision": s.delivery.Revision, "configRevision": s.delivery.ConfigRevision, "status": status}
	if status == "stopped" || status == "failed" {
		s.mu.Lock()
		if s.stopGeneration >= s.info.Generation {
			body["authGeneration"] = s.stopGeneration
		}
		s.mu.Unlock()
		if status == "stopped" {
			delete(body, "revision")
			delete(body, "configRevision")
		}
	}
	err := s.client.call(ctx, "POST", "/api/client/controller/ack", body, nil)
	var failure *controllerFailure
	if status == "stopped" && errors.As(err, &failure) && failure.code == "controller_stale_generation" && failure.generation >= s.info.Generation {
		body["authGeneration"] = failure.generation
		err = s.client.call(ctx, "POST", "/api/client/controller/ack", body, nil)
	}
	return err
}

func (s *managedSession) watch(running func() bool, stop func(string)) {
	ctx, cancel := context.WithCancel(context.Background())
	s.mu.Lock()
	s.cancel = cancel
	s.stop = stop
	alreadyClosed := s.closed
	s.mu.Unlock()
	if alreadyClosed {
		cancel()
		return
	}
	go func() {
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-s.done:
				return
			case <-ticker.C:
				s.mu.Lock()
				valid := s.validLocked()
				s.mu.Unlock()
				if !valid {
					s.deny(controllerDenied("expired"))
					return
				}
			}
		}
	}()
	go func() {
		renew := time.NewTicker(20 * time.Second)
		ready := time.NewTicker(100 * time.Millisecond)
		defer renew.Stop()
		defer ready.Stop()
		reported := false
		for {
			select {
			case <-s.done:
				return
			case <-ready.C:
				if !reported && running() {
					ackContext, cancelAck := context.WithTimeout(context.Background(), 8*time.Second)
					err := s.ack(ackContext, "running")
					cancelAck()
					if err != nil {
						s.deny(err)
						return
					}
					reported = true
				}
			case <-renew.C:
				if err := s.renew(ctx); err != nil {
					s.deny(err)
					return
				}
			}
		}
	}()
}
