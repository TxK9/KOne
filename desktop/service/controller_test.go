package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type controllerFixture struct {
	server    *httptest.Server
	binding   ControllerBinding
	mu        sync.Mutex
	order     []string
	nonces    []string
	denyLease bool
	mutate    func(map[string]any)
	block     chan struct{}
}

func fixture(t *testing.T) *controllerFixture {
	t.Helper()
	f := &controllerFixture{}
	f.binding = ControllerBinding{DeviceID: "device", Credential: strings.Repeat("s", 64), ConfigRevision: strings.Repeat("a", 64), Revision: strings.Repeat("b", 64), AuthGeneration: 7, ManagedSource: map[string]any{"outbounds": []any{map[string]any{"type": "socks", "tag": "managed", "server": "192.0.2.1", "server_port": json.Number("443")}}}}
	f.server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+f.binding.Credential || r.Header.Get("X-KOne-Controller-Protocol") != "1" {
			t.Error("missing device authorization or protocol header")
			w.WriteHeader(401)
			return
		}
		f.mu.Lock()
		f.order = append(f.order, r.URL.Path)
		f.mu.Unlock()
		info := map[string]any{"format": 1, "deviceId": "device", "mode": "enabled", "required": true, "authGeneration": 7, "nodeIds": []string{"entry", "relay"}}
		reply := map[string]any{}
		switch r.URL.Path {
		case "/api/client/controller/register":
			reply["controller"] = info
		case "/api/client/config":
			reply = map[string]any{"format": 1, "configKind": "sing-box-source", "configRevision": f.binding.ConfigRevision, "revision": f.binding.Revision, "config": f.binding.ManagedSource, "controller": info}
		case "/api/client/controller/lease":
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil {
				w.WriteHeader(400)
				return
			}
			nonce, _ := body["nonce"].(string)
			f.mu.Lock()
			f.nonces = append(f.nonces, nonce)
			deny, mutate, block := f.denyLease, f.mutate, f.block
			f.mu.Unlock()
			if block != nil {
				select {
				case <-block:
				case <-r.Context().Done():
					return
				}
			}
			if deny {
				w.WriteHeader(403)
				_ = json.NewEncoder(w).Encode(map[string]any{"errorCode": "authorization_revoked", "controller": map[string]any{"authGeneration": 8}})
				return
			}
			reply = map[string]any{"format": 1, "deviceId": "device", "mode": "enabled", "required": true, "authGeneration": 7, "configRevision": f.binding.ConfigRevision, "nonce": nonce, "serverTime": 1000, "notAfter": 1060, "ttlSeconds": 60, "nodeIds": []string{"entry", "relay"}}
			if mutate != nil {
				mutate(reply)
			}
		case "/api/client/controller/ack":
			reply["ok"] = true
		default:
			w.WriteHeader(404)
			return
		}
		_ = json.NewEncoder(w).Encode(reply)
	}))
	f.binding.Endpoint = f.server.URL
	t.Cleanup(f.server.Close)
	return f
}
func (f *controllerFixture) client(t *testing.T) *controllerClient {
	t.Helper()
	c, err := newControllerClient(f.binding)
	if err != nil {
		t.Fatal(err)
	}
	c.http.Transport = f.server.Client().Transport
	return c
}

func TestControllerRequiresFreshAuthorityBeforeEveryStart(t *testing.T) {
	f := fixture(t)
	c := f.client(t)
	now := int64(1000)
	guard, source, err := c.prepare(context.Background(), f.binding.ManagedSource, func() int64 { return now })
	if err != nil {
		t.Fatal(err)
	}
	if source == nil || guard == nil {
		t.Fatal("no guarded source")
	}
	started := false
	if err = guard.start(func() error { started = true; return nil }); err != nil || !started {
		t.Fatal("fresh authority did not start")
	}
	if err = guard.renew(context.Background()); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.order) < 3 || f.order[0] != "/api/client/controller/register" || f.order[1] != "/api/client/config" || f.order[2] != "/api/client/controller/lease" {
		t.Fatal("engine authorization order changed")
	}
	if len(f.nonces) != 2 || f.nonces[0] == f.nonces[1] || len(f.nonces[0]) != 64 {
		t.Fatal("each check needs a fresh unpredictable nonce")
	}
}

func TestControllerNeverStartsWhenAuthorizationIsUnavailable(t *testing.T) {
	f := fixture(t)
	f.denyLease = true
	guard, _, err := f.client(t).prepare(context.Background(), f.binding.ManagedSource, func() int64 { return 1000 })
	if err == nil || guard != nil {
		t.Fatal("revoked authority admitted a core")
	}
	c, err := newControllerClient(f.binding)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = c.prepare(context.Background(), f.binding.ManagedSource, func() int64 { return 1000 }); err == nil {
		t.Fatal("untrusted TLS was accepted")
	}
}

func TestControllerRejectsWrongContextAndUnsafeLifetime(t *testing.T) {
	cases := []func(map[string]any){func(r map[string]any) { r["nonce"] = "replayed" }, func(r map[string]any) { r["deviceId"] = "other" }, func(r map[string]any) { r["authGeneration"] = 6 }, func(r map[string]any) { r["configRevision"] = strings.Repeat("f", 64) }, func(r map[string]any) { r["nodeIds"] = []string{"entry"} }, func(r map[string]any) { r["ttlSeconds"] = 61 }, func(r map[string]any) { r["required"] = false }}
	for i, mutate := range cases {
		t.Run(string(rune('a'+i)), func(t *testing.T) {
			f := fixture(t)
			f.mutate = mutate
			if g, _, err := f.client(t).prepare(context.Background(), f.binding.ManagedSource, func() int64 { return 1000 }); err == nil || g != nil {
				t.Fatal("mismatched response admitted traffic")
			}
		})
	}
}

func TestControllerRejectsChangedManagedPrefix(t *testing.T) {
	f := fixture(t)
	wrong := map[string]any{"outbounds": []any{map[string]any{"type": "socks", "tag": "managed", "server": "192.0.2.2", "server_port": json.Number("443")}}}
	if _, _, err := f.client(t).prepare(context.Background(), wrong, func() int64 { return 1000 }); err == nil {
		t.Fatal("caller replaced an authorized node")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, path := range f.order {
		if path == "/api/client/controller/lease" {
			t.Fatal("bad source should fail before lease")
		}
	}
}

func TestControllerDeadlineStopsWithoutGuiOrCompletedRenewal(t *testing.T) {
	f := fixture(t)
	f.mutate = func(r map[string]any) { r["notAfter"] = 1030 }
	var clock atomic.Int64
	clock.Store(1000)
	guard, _, err := f.client(t).prepare(context.Background(), f.binding.ManagedSource, clock.Load)
	if err != nil {
		t.Fatal(err)
	}
	if guard.deadline != 31_000 {
		t.Fatalf("shortened server deadline ignored: %d", guard.deadline)
	}
	stopped := make(chan string, 1)
	guard.watch(func() bool { return false }, func(reason string) { stopped <- reason })
	f.mu.Lock()
	f.block = make(chan struct{})
	block := f.block
	f.mu.Unlock()
	renewed := make(chan error, 1)
	go func() { renewed <- guard.renew(context.Background()) }()
	clock.Store(31_000)
	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("service watchdog waited for GUI or HTTP")
	}
	close(block)
	select {
	case err := <-renewed:
		if err == nil {
			t.Fatal("late HTTP response extended a stopped session")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("renewal did not finish")
	}
	if err := guard.start(func() error { t.Fatal("expired core started"); return nil }); err == nil {
		t.Fatal("closed session admitted a start")
	}
}

func TestControllerRejectsRedirectAndPlaintextEndpoint(t *testing.T) {
	for _, endpoint := range []string{"http://127.0.0.1", "https://user@example.invalid", "https://example.invalid/?query=secret", "https://example.invalid/#fragment"} {
		b := ControllerBinding{Endpoint: endpoint}
		if _, err := newControllerClient(b); err == nil {
			t.Fatal("unsafe endpoint accepted")
		}
	}
	f := fixture(t)
	targetHits := atomic.Int64{}
	redirect := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { targetHits.Add(1); w.WriteHeader(200) }))
	defer redirect.Close()
	original := f.server.Config.Handler
	f.server.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, redirect.URL, http.StatusFound) })
	defer func() { f.server.Config.Handler = original }()
	if _, _, err := f.client(t).prepare(context.Background(), f.binding.ManagedSource, func() int64 { return 1000 }); err == nil {
		t.Fatal("redirect was accepted")
	}
	if targetHits.Load() != 0 {
		t.Fatal("credential-bearing redirect was followed")
	}
}

func TestControllerOriginUsesOnlyExactDirectAuthorityRoute(t *testing.T) {
	source := map[string]any{"outbounds": []any{map[string]any{"type": "socks", "tag": "node", "server": "192.0.2.1", "server_port": json.Number("443")}}}
	config, _, err := Build(source, Options{Port: 9090, Secret: "synthetic", ControllerHost: "controller.example.invalid", ControllerPort: 8443, Region: "global", Mode: "Global"})
	if err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)["rules"].([]any)
	found := false
	for _, raw := range route {
		rule := raw.(map[string]any)
		if names, ok := rule["domain"].([]any); ok && len(names) == 1 && names[0] == "controller.example.invalid" {
			found = rule["outbound"] == "direct" && rule["network"] == "tcp" && rule["port"] == 8443
			if _, wide := rule["domain_suffix"]; wide {
				t.Fatal("control origin widened")
			}
			break
		}
	}
	if !found {
		t.Fatal("authority would depend on the managed proxy")
	}
	dns := config["dns"].(map[string]any)["rules"].([]any)
	if len(dns) == 0 || dns[0].(map[string]any)["server"] != "local-dns" {
		t.Fatal("authority DNS would depend on proxy DNS")
	}
	if _, _, err = Build(source, Options{ControllerHost: "*.example.invalid", ControllerPort: 443}); err == nil {
		t.Fatal("wildcard authority bypass accepted")
	}
}

func TestControllerProxyPlanKeepsLoopbackListenerWithoutTun(t *testing.T) {
	source := map[string]any{"outbounds": []any{map[string]any{"type": "socks", "tag": "node", "server": "192.0.2.1", "server_port": json.Number("443")}}}
	config, _, err := Build(source, Options{Port: 9090, Secret: "synthetic", Tun: false, Mixed: 12345, ControllerHost: "controller.example.invalid", ControllerPort: 443, Region: "global"})
	if err != nil {
		t.Fatal(err)
	}
	inbounds := config["inbounds"].([]any)
	if len(inbounds) != 1 {
		t.Fatalf("proxy mode unexpectedly made %d inbounds", len(inbounds))
	}
	inbound := inbounds[0].(map[string]any)
	if inbound["type"] != "mixed" || inbound["listen"] != "127.0.0.1" || inbound["listen_port"] != 12345 {
		t.Fatal("proxy listener widened or silently switched to TUN")
	}
}

func TestControllerFencesReportsFromReplacedSessions(t *testing.T) {
	f := fixture(t)
	guard, _, err := f.client(t).prepare(context.Background(), f.binding.ManagedSource, func() int64 { return 1000 })
	if err != nil {
		t.Fatal(err)
	}
	guard.reportAllowed = func(string) bool { return false }
	if err := guard.ack(context.Background(), "running"); err != nil {
		t.Fatal(err)
	}
	if err := guard.ack(context.Background(), "stopped"); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, path := range f.order {
		if path == "/api/client/controller/ack" {
			t.Fatal("obsolete session reported over the current session")
		}
	}
}
