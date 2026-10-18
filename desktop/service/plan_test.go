package main

import (
	"bytes"
	"encoding/json"
	"reflect"
	"testing"
)

const claudeRule = `{"domain_suffix": ["anthropic.com", "claude.ai", "claude.com", "claudeusercontent.com"], "action": "reject"}`

func parse(t *testing.T, text string) map[string]any {
	t.Helper()
	decoder := json.NewDecoder(bytes.NewReader([]byte(text)))
	decoder.UseNumber()
	var value map[string]any
	if err := decoder.Decode(&value); err != nil {
		t.Fatal(err)
	}
	return value
}

func source(t *testing.T, block bool) map[string]any {
	rules := ""
	if block {
		rules = claudeRule
	}
	return parse(t, `{
	  "outbounds": [
	    {"type": "vless", "tag": "\u7f8e\u56fd", "server": "203.0.113.10", "server_port": 443, "uuid": "00000000-0000-0001-0000-000000000001"},
	    {"type": "trojan", "tag": "\u65e5\u672c", "server": "203.0.113.11", "server_port": 443, "password": "example"},
	    {"type": "direct", "tag": "direct"}
	  ],
	  "route": {"rules": [`+rules+`]}
	}`)
}

var options = Options{Rules: "C:/Program Files/KOne/core/rules", Port: 9090, Secret: "secret", Tun: true}

func rules(config map[string]any, section string) []any {
	return config[section].(map[string]any)["rules"].([]any)
}

func TestClaudeBlockComesRightAfterSniffAndInDns(t *testing.T) {
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	route := rules(config, "route")
	if route[0].(map[string]any)["action"] != "sniff" || route[1].(map[string]any)["action"] != "hijack-dns" {
		t.Fatalf("sniff and DNS hijack come first: %v", route[:2])
	}
	claude := Refusals(parse(t, `{"route": {"rules": [`+claudeRule+`]}}`))[0]
	if !reflect.DeepEqual(route[2], claude) {
		t.Fatalf("the Claude block is third: %v", route[2])
	}
	for _, rule := range route[3:] {
		if _, ok := rule.(map[string]any)["outbound"]; !ok {
			t.Fatalf("every later rule routes somewhere: %v", rule)
		}
	}
	if !reflect.DeepEqual(rules(config, "dns")[0], claude) {
		t.Fatalf("DNS refuses the same names first: %v", rules(config, "dns")[0])
	}
}

func TestNoBlockRefusesNothing(t *testing.T) {
	config, _, _ := Build(source(t, false), options)
	for _, section := range []string{"route", "dns"} {
		for _, rule := range rules(config, section) {
			if rule.(map[string]any)["action"] == "reject" {
				t.Fatalf("%s refuses something: %v", section, rule)
			}
		}
	}
}

func TestOnlyRulesThatRefuseNamesAreKept(t *testing.T) {
	kept := Refusals(parse(t, `{"route": {"rules": [
	  {"ip_cidr": ["0.0.0.0/0"], "action": "reject"},
	  {"domain_suffix": ["claude.ai"], "inbound": "tun-in", "action": "reject"},
	  {"domain_suffix": ["claude.ai"], "outbound": "direct"},
	  {"domain": [" claude.com ", ""], "domain_suffix": ["anthropic.com"], "action": "reject"}
	]}}`))
	want := []any{map[string]any{"domain": []any{"claude.com"}, "domain_suffix": []any{"anthropic.com"}, "action": "reject"}}
	if !reflect.DeepEqual(kept, want) {
		t.Fatalf("kept %v", kept)
	}
}

func TestOutboundsNamingFilesAreNeverRun(t *testing.T) {
	config, nodes, err := Build(parse(t, `{"outbounds": [
	  {"type": "vless", "tag": "\u7f8e\u56fd", "server": "203.0.113.10", "server_port": 443, "uuid": "00000000-0000-0001-0000-000000000001",
	   "tls": {"enabled": true, "certificate_path": "C:/Windows/System32/config/SAM"}},
	  {"type": "ssh", "tag": "Relay", "server": "203.0.113.12", "private_key_path": "C:/Users/x/.ssh/id_ed25519"},
	  {"type": "trojan", "tag": "\u65e5\u672c", "server": "203.0.113.11", "server_port": 443, "password": "example"}
	]}`), options)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(nodes, []string{"\u65e5\u672c"}) {
		t.Fatalf("nodes %v", nodes)
	}
	text, _ := json.Marshal(config)
	if bytes.Contains(text, []byte("_path\"")) && !bytes.Contains(text, []byte("initial_path")) {
		t.Fatalf("a path survived: %s", text)
	}
	if _, _, err := Build(parse(t, `{"outbounds": [{"type": "trojan", "tag": "a", "server": "203.0.113.1", "tls": {"ech": {"config_path": "x"}}}]}`), options); err == nil {
		t.Fatal("a source whose only node names a file must be refused")
	}
}

func TestChainsStayWholeAndClashingTagsMoveAside(t *testing.T) {
	config, nodes, err := Build(parse(t, `{"outbounds": [
	  {"type": "vless", "tag": "proxy", "server": "203.0.113.10", "server_port": 443, "uuid": "00000000-0000-0001-0000-000000000001"},
	  {"type": "trojan", "tag": "Japan 2", "server": "203.0.113.11", "server_port": 443, "password": "example", "detour": "relay"},
	  {"type": "shadowsocks", "tag": "relay", "server": "203.0.113.12", "server_port": 8388, "method": "aes-128-gcm", "password": "example"},
	  {"type": "naive", "tag": "naive", "server": "203.0.113.13"},
	  {"type": "block", "tag": "block"}
	]}`), Options{Selected: "Japan 2", Rules: "r", Port: 1, Secret: "s", Tun: true})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(nodes, []string{"proxy-2", "Japan 2"}) {
		t.Fatalf("nodes %v", nodes)
	}
	outbounds := config["outbounds"].([]any)
	selector := outbounds[0].(map[string]any)
	if selector["default"] != "Japan 2" {
		t.Fatalf("starts on %v", selector["default"])
	}
	tags := map[string]map[string]any{}
	for _, item := range outbounds {
		tags[item.(map[string]any)["tag"].(string)] = item.(map[string]any)
	}
	if tags["Japan 2"]["detour"] != "relay" || tags["relay"] == nil {
		t.Fatal("the relay is kept and still reached")
	}
	if tags["naive"] != nil || tags["block"] != nil {
		t.Fatal("naive and block are not carried")
	}
}

func TestBrokenSourcesAreRefused(t *testing.T) {
	for _, text := range []string{
		`{}`,
		`{"outbounds": [{"type": "direct", "tag": "direct"}]}`,
		`{"outbounds": [{"type": "vless", "tag": "a", "detour": "missing"}]}`,
		`{"outbounds": [{"type": "vless", "tag": "a", "detour": "b"}, {"type": "vless", "tag": "b", "detour": "a"}]}`,
	} {
		if _, _, err := Build(parse(t, text), options); err == nil {
			t.Fatalf("accepted %s", text)
		}
	}
}

func TestWithoutTunThereIsNoInbound(t *testing.T) {
	probe := options
	probe.Tun = false
	config, _, _ := Build(source(t, true), probe)
	if len(config["inbounds"].([]any)) != 0 {
		t.Fatal("a probe has no inbound")
	}
}
