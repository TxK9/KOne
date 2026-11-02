// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"os"
	"testing"
	"time"
)

func TestOfflineCoreWithExplicitChecker(t *testing.T) {
	path := os.Getenv("KONE_OFFLINE_CORE")
	if path == "" {
		t.Skip("an existing offline checker was not supplied")
	}
	config := testConfig(t)
	config.CorePath = path
	core := ExecCore{Config: config}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	if _, err := core.Version(ctx); err != nil {
		t.Fatal(err)
	}
	if err := core.Check(ctx, syntheticCoreConfig()); err != nil {
		t.Fatal("offline capability probe rejected:", err)
	}
	registry := newRegistry(testDevice)
	plan, err := makePlan(config, testView(), &registry)
	if err != nil {
		t.Fatal(err)
	}
	if err = core.Check(ctx, plan.Config); err != nil {
		t.Fatal("synthetic chained SOCKS/Hysteria2 configuration rejected:", err)
	}
	view := testView()
	key := base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{1}, 32))
	node := map[string]any{"type": "vless", "tag": "VLESS", "server": "vless.invalid", "server_port": 443, "uuid": testVless, "flow": "xtls-rprx-vision", "tls": map[string]any{"enabled": true, "server_name": "tls.invalid", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}, "reality": map[string]any{"enabled": true, "public_key": key, "short_id": "0123"}}}
	view.Config, _ = json.Marshal(map[string]any{"outbounds": []any{node}, "route": map[string]any{"final": "VLESS"}})
	view.Controller.Nodes = []NodeMetadata{{ID: testVless, Name: "VLESS", Protocol: "vless", Revision: 1, Tag: "VLESS", DependencyIDs: []string{}}}
	view.Controller.NodeIDs = []string{testVless}
	view.Controller.RootNodeIDs = []string{testVless}
	refreshHash(&view)
	plan, err = makePlan(config, view, &registry)
	if err != nil {
		t.Fatal(err)
	}
	if err = core.Check(ctx, plan.Config); err != nil {
		t.Fatal("synthetic VLESS/Reality configuration rejected:", err)
	}
}
func TestPrivateFilePermissionsAndSymlinksAreRejected(t *testing.T) {
	config := testConfig(t)
	config.Enabled = false
	if err := saveConfig(config.StatePath, config); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(config.StatePath); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(config.StatePath, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(config.StatePath); err == nil {
		t.Fatal("readable credential file accepted")
	}
	if err := os.Chmod(config.StatePath, 0600); err != nil {
		t.Fatal(err)
	}
	alias := config.StatePath + ".alias"
	if err := os.Symlink(config.StatePath, alias); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(alias); err == nil {
		t.Fatal("credential symlink accepted")
	}
}
