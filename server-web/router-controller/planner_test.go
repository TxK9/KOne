// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

const testDevice = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const testRelay = "11111111-1111-4111-8111-111111111111"
const testExit = "22222222-2222-4222-8222-222222222222"
const testVless = "33333333-3333-4333-8333-333333333333"
const upstreamSecret = "synthetic-upstream-secret-not-a-stub-password"

func testConfig(t *testing.T) Config {
	t.Helper()
	return Config{Format: 1, Enabled: true, Server: "https://controller.invalid", DeviceID: testDevice, Credential: "synthetic-device-credential", CorePath: "/usr/bin/sing-box", UCIPath: "/sbin/uci", PassWallPath: "/etc/config/passwall2", StatePath: filepath.Join(t.TempDir(), "stubs.json"), RuntimeDir: t.TempDir(), ControlResolver: "192.0.2.53", PortBase: 600, PortSlots: 400}
}
func refreshHash(view *ConfigReply) {
	object, _ := jsonObject(view.Config)
	canonical, _ := canonicalJSON(object)
	hash := sha256.Sum256(canonical)
	view.ConfigRevision = hex.EncodeToString(hash[:])
	delivery := sha256.Sum256([]byte("delivery-" + view.ConfigRevision))
	view.Revision = hex.EncodeToString(delivery[:])
}
func testView() ConfigReply {
	relayTag, exitTag := "Relay [11111111]", "Exit [22222222]"
	configuration := map[string]any{"outbounds": []any{map[string]any{"type": "hysteria2", "tag": relayTag, "server": "relay.invalid", "server_port": 443, "password": upstreamSecret, "tls": map[string]any{"enabled": true, "server_name": "tls.invalid", "insecure": true}}, map[string]any{"type": "socks", "tag": exitTag, "server": "exit.invalid", "server_port": 1080, "version": "5", "username": "synthetic-user", "password": upstreamSecret, "detour": relayTag}}, "route": map[string]any{"final": exitTag}}
	raw, _ := json.Marshal(configuration)
	view := ConfigReply{Format: 1, ConfigKind: "sing-box-source", ConfigName: "KOne", Config: raw, Policy: json.RawMessage(`{"deviceLimit":3}`), Controller: &ControllerMetadata{Format: 1, DeviceID: testDevice, Mode: "enabled", Required: true, AuthGeneration: 2, TTLSeconds: 60, RenewAfterSeconds: 20, RootNodeIDs: []string{testExit}, NodeIDs: []string{testRelay, testExit}, Nodes: []NodeMetadata{{ID: testRelay, Name: "Relay", Protocol: "hysteria2", Revision: 1, Tag: relayTag, DependencyIDs: []string{}}, {ID: testExit, Name: "Exit", Protocol: "socks", Revision: 1, Tag: exitTag, DependencyIDs: []string{testRelay}}}}}
	refreshHash(&view)
	return view
}
func changeConfig(view *ConfigReply, change func(map[string]any)) {
	object, _ := jsonObject(view.Config)
	change(object)
	view.Config, _ = json.Marshal(object)
	refreshHash(view)
}
func TestPlanPreservesChainAndKeepsUpstreamCredentialsOutOfPassWall(t *testing.T) {
	config := testConfig(t)
	registry := newRegistry(testDevice)
	view := testView()
	plan, err := makePlan(config, view, &registry)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Stubs) != 1 || plan.Stubs[0].NodeID != testExit || plan.Stubs[0].Port >= 1024 {
		t.Fatal("wrong root stub")
	}
	if bytes.Contains(stubSection(testDevice, plan.Stubs[0]), []byte(upstreamSecret)) {
		t.Fatal("upstream credential leaked into stub")
	}
	var runtimeConfig map[string]any
	if json.Unmarshal(plan.Config, &runtimeConfig) != nil {
		t.Fatal("invalid runtime")
	}
	inbounds := runtimeConfig["inbounds"].([]any)
	inbound := inbounds[0].(map[string]any)
	if inbound["listen"] != "127.0.0.1" {
		t.Fatal("non-loopback listener")
	}
	outbounds := runtimeConfig["outbounds"].([]any)
	if outbounds[1].(map[string]any)["detour"] != view.Controller.Nodes[0].Tag {
		t.Fatal("chain flattened")
	}
	if outbounds[0].(map[string]any)["tls"].(map[string]any)["insecure"] != true {
		t.Fatal("original TLS setting changed")
	}
	rules := runtimeConfig["route"].(map[string]any)["rules"].([]any)
	if rules[len(rules)-1].(map[string]any)["action"] != "reject" {
		t.Fatal("missing default rejection")
	}
}
func TestStubIdentityIsStableAndRevokedPortsAreNotReused(t *testing.T) {
	config := testConfig(t)
	registry := newRegistry(testDevice)
	view := testView()
	first, err := makePlan(config, view, &registry)
	if err != nil {
		t.Fatal(err)
	}
	if err = registry.save(config); err != nil {
		t.Fatal(err)
	}
	loaded, err := loadRegistry(config)
	if err != nil {
		t.Fatal(err)
	}
	second, err := makePlan(config, view, &loaded)
	if err != nil {
		t.Fatal(err)
	}
	if first.Stubs[0] != second.Stubs[0] {
		t.Fatal("stub identity changed")
	}
	next, err := loaded.stub(config, NodeMetadata{ID: testVless, Name: "Another root"})
	if err != nil {
		t.Fatal(err)
	}
	if next.Port == first.Stubs[0].Port || next.Password == first.Stubs[0].Password {
		t.Fatal("old stub authority reused")
	}
	info, _ := os.Stat(config.StatePath)
	if info.Mode().Perm() != 0600 {
		t.Fatal("registry permissions")
	}
}
func TestPlannerRejectsBrokenGraphAndConfigurationMismatch(t *testing.T) {
	cases := []struct {
		name, want string
		change     func(*ConfigReply)
	}{
		{"digest", "configuration_revision_mismatch", func(v *ConfigReply) { v.ConfigRevision = string(bytes.Repeat([]byte{'0'}, 64)) }},
		{"missing dependency", "invalid_managed_dependency_graph", func(v *ConfigReply) { v.Controller.Nodes[1].DependencyIDs = []string{testVless} }},
		{"cycle", "invalid_managed_dependency_graph", func(v *ConfigReply) { v.Controller.Nodes[0].DependencyIDs = []string{testExit} }},
		{"ungranted extra", "ungranted_dependency_data", func(v *ConfigReply) { v.Controller.RootNodeIDs = []string{testRelay} }},
		{"detour mismatch", "managed_dependency_mismatch", func(v *ConfigReply) {
			changeConfig(v, func(c map[string]any) { delete(c["outbounds"].([]any)[1].(map[string]any), "detour") })
		}},
		{"unknown TLS", "unsupported_tls_options", func(v *ConfigReply) {
			changeConfig(v, func(c map[string]any) {
				c["outbounds"].([]any)[0].(map[string]any)["tls"].(map[string]any)["unrecognized_pin"] = "do-not-drop"
			})
		}},
		{"unsupported transport", "unsupported_node_options", func(v *ConfigReply) {
			changeConfig(v, func(c map[string]any) {
				c["outbounds"].([]any)[1].(map[string]any)["transport"] = map[string]any{"type": "ws"}
			})
		}},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			view := testView()
			test.change(&view)
			registry := newRegistry(testDevice)
			_, err := makePlan(testConfig(t), view, &registry)
			if err == nil || err.Error() != test.want {
				t.Fatalf("got %v, want %s", err, test.want)
			}
		})
	}
}
func TestVlessRealityAndPinsArePreserved(t *testing.T) {
	view := testView()
	key := base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{1}, 32))
	pin := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{2}, 32))
	node := map[string]any{"type": "vless", "tag": "VLESS", "server": "vless.invalid", "server_port": 443, "uuid": testVless, "flow": "xtls-rprx-vision", "tls": map[string]any{"enabled": true, "server_name": "tls.invalid", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}, "reality": map[string]any{"enabled": true, "public_key": key, "short_id": "0123"}, "certificate_public_key_sha256": []string{pin}}}
	view.Config, _ = json.Marshal(map[string]any{"outbounds": []any{node}, "route": map[string]any{"final": "VLESS"}})
	view.Controller.Nodes = []NodeMetadata{{ID: testVless, Name: "VLESS", Protocol: "vless", Revision: 1, Tag: "VLESS", DependencyIDs: []string{}}}
	view.Controller.NodeIDs = []string{testVless}
	view.Controller.RootNodeIDs = []string{testVless}
	refreshHash(&view)
	registry := newRegistry(testDevice)
	plan, err := makePlan(testConfig(t), view, &registry)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(plan.Config, []byte(pin)) || !bytes.Contains(plan.Config, []byte(key)) {
		t.Fatal("TLS security fields lost")
	}
}
func TestManagedRejectRulesStayAheadOfStubRoutes(t *testing.T) {
	view := testView()
	changeConfig(&view, func(c map[string]any) {
		c["route"].(map[string]any)["rules"] = []any{map[string]any{"action": "reject", "domain_suffix": []string{"blocked.invalid"}}}
	})
	registry := newRegistry(testDevice)
	plan, err := makePlan(testConfig(t), view, &registry)
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	_ = json.Unmarshal(plan.Config, &config)
	rules := config["route"].(map[string]any)["rules"].([]any)
	if rules[0].(map[string]any)["action"] != "reject" {
		t.Fatal("server rule bypassed")
	}
}
func TestCanonicalJSONHandlesCultureAndLiteralEscapes(t *testing.T) {
	value := map[string]any{"label": "\u7f8e\u56fd<&>\u2028", "literal": `\u2028`}
	data, err := canonicalJSON(value)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if json.Unmarshal(data, &decoded) != nil || decoded["label"] != value["label"] || decoded["literal"] != value["literal"] {
		t.Fatal("canonical encoding changed data")
	}
}
