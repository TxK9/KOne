// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func mockClient(t *testing.T, handler http.HandlerFunc) (*Client, *httptest.Server) {
	t.Helper()
	server := httptest.NewTLSServer(handler)
	t.Cleanup(server.Close)
	config := testConfig(t)
	config.Server = server.URL
	client, err := newClient(config)
	if err != nil {
		t.Fatal(err)
	}
	trusted := server.Client()
	client.http.Transport = trusted.Transport
	client.http.Timeout = time.Second
	return client, server
}
func respondJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}
func positiveLease(view ConfigReply, nonce string) map[string]any {
	return map[string]any{"format": 1, "nonce": nonce, "deviceId": testDevice, "mode": view.Controller.Mode, "required": view.Controller.Required, "serverTime": 1000, "notAfter": 1060, "ttlSeconds": 60, "authGeneration": view.Controller.AuthGeneration, "configRevision": view.ConfigRevision, "nodeIds": view.Controller.NodeIDs, "rootNodeIds": view.Controller.RootNodeIDs}
}
func TestControlHTTPSNonceAndConfigurationBinding(t *testing.T) {
	view := testView()
	requests := 0
	client, _ := mockClient(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.Header.Get("Authorization") != "Bearer synthetic-device-credential" || r.Header.Get("X-KOne-Controller-Protocol") != "1" {
			t.Error("missing private authority headers")
		}
		switch r.URL.Path {
		case "/api/client/config":
			respondJSON(w, view)
		case "/api/client/controller/lease":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			respondJSON(w, positiveLease(view, body["nonce"].(string)))
		default:
			t.Error("unexpected endpoint")
		}
	})
	fetched, err := client.Configuration(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	lease, err := client.Lease(context.Background(), fetched)
	if err != nil {
		t.Fatal(err)
	}
	if lease.ConfigRevision != fetched.ConfigRevision || requests != 2 {
		t.Fatal("lease binding mismatch")
	}
	transport := newClientMust(t, testConfig(t)).http.Transport.(*http.Transport)
	if transport.Proxy != nil || transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("unsafe control transport")
	}
}
func newClientMust(t *testing.T, c Config) *Client {
	t.Helper()
	client, err := newClient(c)
	if err != nil {
		t.Fatal(err)
	}
	return client
}
func TestLeaseMalformedMissingDuplicateAndUnknownResponsesFailClosed(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(map[string]any)
		raw    string
	}{
		{"nonce", func(m map[string]any) { m["nonce"] = "wrong" }, ""}, {"device", func(m map[string]any) { m["deviceId"] = testRelay }, ""}, {"generation", func(m map[string]any) { m["authGeneration"] = 99 }, ""}, {"config", func(m map[string]any) { m["configRevision"] = strings.Repeat("0", 64) }, ""}, {"ttl", func(m map[string]any) { m["ttlSeconds"] = 61; m["notAfter"] = 1061 }, ""}, {"expiry", func(m map[string]any) { m["notAfter"] = 999999 }, ""}, {"unknown", func(m map[string]any) { m["extraPermission"] = true }, ""}, {"missing false field", func(m map[string]any) { delete(m, "required") }, ""}, {"null array", func(m map[string]any) { m["nodeIds"] = nil }, ""}, {"duplicate", nil, `{"format":1,"format":1}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			view := testView()
			client, _ := mockClient(t, func(w http.ResponseWriter, r *http.Request) {
				var body map[string]any
				_ = json.NewDecoder(r.Body).Decode(&body)
				response := positiveLease(view, body["nonce"].(string))
				w.Header().Set("Cache-Control", "no-store")
				if test.raw != "" {
					_, _ = io.WriteString(w, test.raw)
					return
				}
				test.mutate(response)
				respondJSON(w, response)
			})
			if _, err := client.Lease(context.Background(), view); err == nil {
				t.Fatal("malformed authority accepted")
			}
		})
	}
}
func TestRedirectsUnknownLegacyMetadataAndUntrustedTLSAreDenied(t *testing.T) {
	client, _ := mockClient(t, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "https://unrelated.invalid/?credential="+upstreamSecret, http.StatusFound)
	})
	_, err := client.Configuration(context.Background())
	if err == nil || strings.Contains(err.Error(), upstreamSecret) {
		t.Fatal("redirect or private error leaked")
	}
	view := testView()
	raw, _ := json.Marshal(view)
	var object map[string]any
	_ = json.Unmarshal(raw, &object)
	delete(object, "controller")
	missing, _ := mockClient(t, func(w http.ResponseWriter, r *http.Request) { respondJSON(w, object) })
	if _, err = missing.Configuration(context.Background()); err == nil {
		t.Fatal("missing metadata permitted")
	}
	untrusted := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { respondJSON(w, view) }))
	defer untrusted.Close()
	config := testConfig(t)
	config.Server = untrusted.URL
	bad := newClientMust(t, config)
	if _, err = bad.Configuration(context.Background()); err == nil {
		t.Fatal("untrusted certificate permitted")
	}
}
func TestPrivateConfigurationValidationRejectsDuplicateKeysAndUnsafeOrigins(t *testing.T) {
	for _, raw := range []string{`{"format":1,"format":1}`, `{"format":1} {}`, `{"a":[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[0]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]}`} {
		var out map[string]any
		if decodeStrict([]byte(raw), &out) == nil {
			t.Fatal("ambiguous JSON accepted")
		}
	}
	for _, server := range []string{"http://controller.invalid", "https://user:secret@controller.invalid", "https://controller.invalid/path", "https://controller.invalid/?token=secret", "https://controller.invalid:0", "https://controller.invalid:70000"} {
		if _, err := origin(server); err == nil {
			t.Fatalf("unsafe origin accepted")
		}
	}
	config := testConfig(t)
	config.Enabled = false
	if config.Validate() != nil {
		t.Fatal("invalid synthetic config")
	}

}
func TestOversizedPrivateRequestIsRejectedBeforeTransmission(t *testing.T) {
	requests := 0
	client, _ := mockClient(t, func(w http.ResponseWriter, r *http.Request) { requests++; respondJSON(w, map[string]any{"ok": true}) })
	var reply struct {
		OK bool `json:"ok"`
	}
	err := client.call(context.Background(), "POST", "/api/client/controller/nodes/publish", map[string]any{"configText": strings.Repeat("<", 250000)}, &reply)
	if err == nil || err.Error() != "control_request_too_large" || requests != 0 {
		t.Fatal("oversized private publication was transmitted")
	}
}
