// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"strings"
	"testing"
)

func TestUCIPatchPreservesForeignNodesGlobalRulesAndDNS(t *testing.T) {
	config := testConfig(t)
	registry := newRegistry(testDevice)
	view := testView()
	plan, err := makePlan(config, view, &registry)
	if err != nil {
		t.Fatal(err)
	}
	foreign := []byte("# User configuration stays byte-for-byte.\nconfig global 'global'\n\toption node 'original'\n\toption dns_mode 'original-dns'\n\nconfig nodes 'original'\n\toption protocol 'vless'\n\toption password 'old-private-value'\nconfig nodes 'shunt'\n\toption protocol '_shunt'\n\toption default_node 'original'\n")
	updated, retained, err := patchPassWall(foreign, testDevice, registry, plan.Stubs)
	if err != nil {
		t.Fatal(err)
	}
	if retained != 0 || !bytes.HasPrefix(updated, foreign) {
		t.Fatal("foreign configuration changed")
	}
	if bytes.Contains(updated, []byte(upstreamSecret)) {
		t.Fatal("upstream credential placed in UCI")
	}
	again, _, err := patchPassWall(updated, testDevice, registry, plan.Stubs)
	if err != nil || !bytes.Equal(again, updated) {
		t.Fatal("stub update is not idempotent")
	}
	removed, _, err := patchPassWall(updated, testDevice, registry, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(removed, foreign) || !bytes.Contains(removed, []byte("kone_controller_owner")) || !bytes.Contains(removed, []byte("KOne unavailable:")) {
		t.Fatal("inactive stub was deleted or foreign nodes changed")
	}
}
func TestReferencedRevokedStubRemainsClosedPortTombstone(t *testing.T) {
	config := testConfig(t)
	registry := newRegistry(testDevice)
	plan, err := makePlan(config, testView(), &registry)
	if err != nil {
		t.Fatal(err)
	}
	stub := plan.Stubs[0]
	foreign := []byte("config global 'global'\n option node '" + stub.Section + "'\n")
	active, _, err := patchPassWall(foreign, testDevice, registry, plan.Stubs)
	if err != nil {
		t.Fatal(err)
	}
	revoked, count, err := patchPassWall(active, testDevice, registry, nil)
	if err != nil {
		t.Fatal(err)
	}
	if count != 1 || !bytes.HasPrefix(revoked, foreign) || !bytes.Contains(revoked, []byte("KOne unavailable:")) {
		t.Fatal("referenced stub tombstone was removed")
	}
	if !bytes.Contains(revoked, []byte("127.0.0.1")) {
		t.Fatal("unsafe tombstone")
	}
}
func TestUCIOwnershipAndMultilineQuotingAreNotGuessed(t *testing.T) {
	config := testConfig(t)
	registry := newRegistry(testDevice)
	plan, err := makePlan(config, testView(), &registry)
	if err != nil {
		t.Fatal(err)
	}
	stub := plan.Stubs[0]
	collision := []byte("config nodes '" + stub.Section + "'\n option address 'foreign.invalid'\n")
	if _, _, err = patchPassWall(collision, testDevice, registry, plan.Stubs); err == nil {
		t.Fatal("foreign section overwritten")
	}
	original := []byte("config nodes 'foreign'\n option password 'first line\nconfig nodes fake\nlast line'\n option note 'apostrophe'\\''s value'\n")
	output, _, err := patchPassWall(original, testDevice, registry, plan.Stubs)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(output, original) {
		t.Fatal("multiline foreign content changed")
	}
	malformed := []byte("config nodes 'foreign'\n option password 'unfinished")
	if _, _, err = patchPassWall(malformed, testDevice, registry, plan.Stubs); err == nil {
		t.Fatal("invalid UCI accepted")
	}
	stub.Name = "Owner's node"
	registry.Entries[stub.NodeID] = stub
	rendered := stubSection(testDevice, stub)
	sections, err := parseUCI(rendered)
	if err != nil {
		t.Fatal(err)
	}
	value, _ := oneOption(sections[0], "remarks")
	if value != "KOne Owner's node" {
		t.Fatal("UCI quote not preserved")
	}
	if strings.Contains(string(rendered), "global") {
		t.Fatal("unexpected global option")
	}
}
