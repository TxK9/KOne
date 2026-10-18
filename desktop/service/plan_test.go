package main

import (
	"bytes"
	"encoding/json"
	"reflect"
	"strconv"
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
	    {"type": "vless", "tag": "United States", "server": "203.0.113.10", "server_port": 443, "uuid": "00000000-0000-0001-0000-000000000001"},
	    {"type": "trojan", "tag": "Japan", "server": "203.0.113.11", "server_port": 443, "password": "example"},
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
	  {"type": "vless", "tag": "United States", "server": "203.0.113.10", "server_port": 443, "uuid": "00000000-0000-0001-0000-000000000001",
	   "tls": {"enabled": true, "certificate_path": "C:/Windows/System32/config/SAM"}},
	  {"type": "ssh", "tag": "Relay", "server": "203.0.113.12", "private_key_path": "C:/Users/x/.ssh/id_ed25519"},
	  {"type": "trojan", "tag": "Japan", "server": "203.0.113.11", "server_port": 443, "password": "example"}
	]}`), options)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(nodes, []string{"Japan"}) {
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

func TestOnlyProgramFileNamesAreSentStraightOut(t *testing.T) {
	got := Programs([]string{"WeChat.exe", " steam.EXE ", "wechat.exe", `C:\Windows\cmd.exe`, "../x.exe", "notes.txt", "a:b.exe", ".exe", ""})
	want := []any{"WeChat.exe", "steam.EXE"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	many := make([]string, 100)
	for i := range many {
		many[i] = "p" + strconv.Itoa(i) + ".exe"
	}
	if n := len(Programs(many)); n != 64 {
		t.Fatalf("kept %d programs, want 64", n)
	}
}

func TestProgramsSentStraightOutStillMeetTheClaudeBlockFirst(t *testing.T) {
	config, _, err := Build(source(t, true), Options{Rules: "/r", Port: 9090, Secret: "s", Tun: true, Bypass: []string{"WeChat.exe"}, Mode: "Global"})
	if err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	block := -1
	straight := -1
	for i, rule := range rules {
		r := rule.(map[string]any)
		if r["action"] == "reject" && block < 0 {
			block = i
		}
		if _, ok := r["process_name"]; ok {
			straight = i
		}
	}
	if block < 0 || straight < 0 || block > straight {
		t.Fatalf("the Claude block (%d) must come before the programs sent straight out (%d): %v", block, straight, rules)
	}
	if route["find_process"] != true {
		t.Fatal("find_process is off")
	}
	api := config["experimental"].(map[string]any)["clash_api"].(map[string]any)
	if api["default_mode"] != "Global" {
		t.Fatalf("mode %v", api["default_mode"])
	}
	again, _, _ := Build(source(t, true), Options{Rules: "/r", Port: 9090, Secret: "s", Mode: "Everything"})
	if again["experimental"].(map[string]any)["clash_api"].(map[string]any)["default_mode"] != "Rule" {
		t.Fatal("an unknown mode must start as Rule")
	}
}

func TestOnlyPlainDomainsAreKeptAsSites(t *testing.T) {
	got := Sites([]string{"Example.CN", " example.cn ", "a.b.example.org", "com", "*.example.com", "https://example.net/", "203.0.113.7", "-bad.example", "x_y.example", ""})
	want := []any{"example.cn", "a.b.example.org"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	many := make([]string, 300)
	for i := range many {
		many[i] = "s" + strconv.Itoa(i) + ".example"
	}
	if n := len(Sites(many)); n != 256 {
		t.Fatalf("kept %d sites, want 256", n)
	}
}

func TestSitesComeAfterTheClaudeBlockAndProxiedOnlyInRule(t *testing.T) {
	options := options
	options.Direct = []string{"example.cn", "claude.ai"}
	options.Proxied = []string{"example.com"}
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	for _, part := range []string{"route", "dns"} {
		rules := config[part].(map[string]any)["rules"].([]any)
		block, direct, proxied := -1, -1, -1
		for i, rule := range rules {
			r := rule.(map[string]any)
			if r["action"] == "reject" && block < 0 {
				block = i
			}
			if suffix, ok := r["domain_suffix"].([]any); ok && r["action"] == nil {
				if suffix[0] == "example.cn" {
					direct = i
					if r["clash_mode"] != nil {
						t.Fatalf("%s: sites sent straight out hold in every mode: %v", part, r)
					}
				}
				if suffix[0] == "example.com" {
					proxied = i
					if r["clash_mode"] != "Rule" {
						t.Fatalf("%s: sites through the node only matter in Rule: %v", part, r)
					}
				}
			}
		}
		if block < 0 || direct < 0 || proxied < 0 || block > direct || direct > proxied {
			t.Fatalf("%s: the Claude block (%d) must come before the sites (%d, %d): %v", part, block, direct, proxied, rules)
		}
	}
}

func TestTheSystemProxyPortListensOnLoopbackOnly(t *testing.T) {
	options := options
	options.Tun = false
	options.Mixed = 7890
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	inbounds := config["inbounds"].([]any)
	if len(inbounds) != 1 {
		t.Fatalf("inbounds: %v", inbounds)
	}
	mixed := inbounds[0].(map[string]any)
	if mixed["type"] != "mixed" || mixed["listen"] != "127.0.0.1" || mixed["listen_port"] != 7890 {
		t.Fatalf("mixed inbound: %v", mixed)
	}
	// The Claude block still comes first, whatever the inbound.
	first := config["route"].(map[string]any)["rules"].([]any)[2].(map[string]any)
	if first["action"] != "reject" {
		t.Fatalf("third rule: %v", first)
	}
}
