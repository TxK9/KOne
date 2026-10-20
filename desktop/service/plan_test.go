package main

import (
	"bytes"
	"encoding/json"
	"reflect"
	"strconv"
	"strings"
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

func TestTestCoresGoStraightOutOnlyWithTheTunnelAndAfterTheClaudeBlock(t *testing.T) {
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	block, core := -1, -1
	for i, rule := range rules {
		r := rule.(map[string]any)
		if r["action"] == "reject" && block < 0 {
			block = i
		}
		if names, ok := r["process_name"].([]any); ok && len(names) == 1 && names[0] == "sing-box.exe" {
			core = i
		}
	}
	if block < 0 || core < 0 || block > core {
		t.Fatalf("the Claude block (%d) must come before the test cores' rule (%d)", block, core)
	}
	probe := options
	probe.Tun = false
	plain, _, _ := Build(source(t, true), probe)
	for _, rule := range plain["route"].(map[string]any)["rules"].([]any) {
		if names, ok := rule.(map[string]any)["process_name"].([]any); ok && names[0] == "sing-box.exe" {
			t.Fatal("without the tunnel there is nothing to go round")
		}
	}
}

func TestWhatNothingMatchesGoesStraightOutWhenAsked(t *testing.T) {
	options := options
	config, _, _ := Build(source(t, true), options)
	route := config["route"].(map[string]any)["rules"].([]any)
	if last := route[len(route)-1].(map[string]any); last["outbound"] != proxyTag || last["clash_mode"] != "Rule" {
		t.Fatalf("by default what nothing matches goes through the node: %v", last)
	}
	options.UnmatchedDirect = true
	config, _, _ = Build(source(t, true), options)
	route = config["route"].(map[string]any)["rules"].([]any)
	if last := route[len(route)-1].(map[string]any); last["outbound"] != "direct" || last["clash_mode"] != "Rule" {
		t.Fatalf("asked, it goes straight out in Rule mode: %v", last)
	}
	dns := config["dns"].(map[string]any)["rules"].([]any)
	if last := dns[len(dns)-1].(map[string]any); last["server"] != "local-dns" || last["clash_mode"] != "Rule" {
		t.Fatalf("and its names are answered straight out: %v", last)
	}
	if route[2].(map[string]any)["action"] != "reject" {
		t.Fatal("the Claude block still comes first")
	}
}

func TestOutsideChinaThereIsNoChinaSplit(t *testing.T) {
	options := options
	options.Region = "global"
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	text, _ := json.Marshal(config)
	for _, china := range []string{"geosite-cn", "geoip-cn", "223.5.5.5"} {
		if strings.Contains(string(text), china) {
			t.Fatalf("%s outside China: %s", china, text)
		}
	}
	dns := config["dns"].(map[string]any)
	if server := dns["servers"].([]any)[0].(map[string]any); server["server"] != "1.1.1.1" {
		t.Fatalf("direct DNS outside China: %v", server)
	}
	// The Claude block still answers first, and what nothing matched still goes through the node.
	if dns["rules"].([]any)[0].(map[string]any)["action"] != "reject" {
		t.Fatalf("refusals first: %v", dns["rules"])
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	if last := rules[len(rules)-1].(map[string]any); last["clash_mode"] != "Rule" || last["outbound"] != proxyTag {
		t.Fatalf("final rule: %v", last)
	}
	// A preset chosen outside China is kept as chosen.
	options.Domestic = "quad9"
	config, _, _ = Build(source(t, true), options)
	if server := config["dns"].(map[string]any)["servers"].([]any)[0].(map[string]any); server["server"] != "9.9.9.9" {
		t.Fatalf("chosen direct DNS: %v", server)
	}
}

func TestFakeIPAnswersOnlyWhatGoesThroughTheNode(t *testing.T) {
	options := options
	options.FakeIP = true
	options.Direct = []string{"example.org"}
	options.Proxied = []string{"example.com"}
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	dns := config["dns"].(map[string]any)
	rules := dns["rules"].([]any)
	// The Claude block still answers first, with nothing fake before it.
	if rules[0].(map[string]any)["action"] != "reject" {
		t.Fatalf("refusals first: %v", rules)
	}
	for i, item := range rules {
		rule := item.(map[string]any)
		if rule["server"] == "local-dns" && rule["query_type"] != nil {
			t.Fatalf("a direct name made fake: %v", rule)
		}
		// Each fake answer stands just before the rule it fakes, which still answers other types.
		if rule["server"] == fakeDNSTag && i+1 < len(rules) {
			next := rules[i+1].(map[string]any)
			if next["server"] != "proxy-dns" {
				t.Fatalf("fake answer without its twin: %v then %v", rule, next)
			}
		}
	}
	if last := rules[len(rules)-1].(map[string]any); last["server"] != fakeDNSTag {
		t.Fatalf("what nothing matched is not faked: %v", last)
	}
	servers := dns["servers"].([]any)
	if fake := servers[len(servers)-1].(map[string]any); fake["type"] != "fakeip" || fake["inet6_range"] != nil {
		t.Fatalf("fake-IP server without IPv6: %v", fake)
	}
	// Off, there is no trace of it.
	options.FakeIP = false
	config, _, _ = Build(source(t, true), options)
	text, _ := json.Marshal(config)
	if strings.Contains(string(text), fakeDNSTag) {
		t.Fatalf("fake-IP while off: %s", text)
	}
}

func TestRulesThroughACountryUseItsNodesAndAskItsDNS(t *testing.T) {
	options := options
	options.Routes = []Rule{{"suffix", "example.com", "via:JP"}, {"set", "geosite-netflix", "via:US"}, {"suffix", "example.org", "via:SG"}, {"cidr", "203.0.113.0/24", "via:JP"}}
	// Japan's list names a node this source does not have; Singapore has none here at all.
	options.Countries = map[string][]string{"JP": {"Japan", "Osaka"}, "US": {"United States"}, "SG": {"Singapore"}}
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	groups := map[string]map[string]any{}
	for _, item := range config["outbounds"].([]any) {
		outbound := item.(map[string]any)
		if tag := text(outbound, "tag"); strings.HasPrefix(tag, "via-") {
			groups[tag] = outbound
		}
	}
	if len(groups) != 2 || !reflect.DeepEqual(groups["via-JP"]["outbounds"], []any{"Japan"}) || !reflect.DeepEqual(groups["via-US"]["outbounds"], []any{"United States"}) || groups["via-JP"]["type"] != "urltest" {
		t.Fatalf("groups: %v", groups)
	}
	routes := rules(config, "route")
	if routes[2].(map[string]any)["action"] != "reject" {
		t.Fatalf("the Claude block is not first: %v", routes)
	}
	want := map[string]string{"example.com": "via-JP", "geosite-netflix": "via-US", "example.org": proxyTag, "203.0.113.0/24": "via-JP"}
	for _, item := range routes {
		rule := item.(map[string]any)
		for _, field := range []string{"domain_suffix", "rule_set", "ip_cidr"} {
			values, _ := rule[field].([]any)
			if len(values) != 1 {
				continue
			}
			if to, ok := want[values[0].(string)]; ok {
				if rule["outbound"] != to || rule["clash_mode"] != "Rule" {
					t.Fatalf("%v goes to %v", values[0], rule)
				}
				delete(want, values[0].(string))
			}
		}
	}
	if len(want) != 0 {
		t.Fatalf("rules missing: %v", want)
	}
	// Names are asked through the same country; an address range has no DNS rule.
	asked := map[string]any{}
	for _, item := range rules(config, "dns") {
		rule := item.(map[string]any)
		for _, field := range []string{"domain_suffix", "rule_set", "ip_cidr"} {
			if values, _ := rule[field].([]any); len(values) == 1 {
				asked[values[0].(string)] = rule["server"]
			}
		}
	}
	if asked["example.com"] != "proxy-dns-JP" || asked["geosite-netflix"] != "proxy-dns-US" || asked["example.org"] != "proxy-dns" || asked["203.0.113.0/24"] != nil {
		t.Fatalf("dns: %v", asked)
	}
	var japan map[string]any
	for _, item := range config["dns"].(map[string]any)["servers"].([]any) {
		if server := item.(map[string]any); server["tag"] == "proxy-dns-JP" {
			japan = server
		}
	}
	if japan == nil || japan["detour"] != "via-JP" || japan["type"] != "https" || japan["server"] != "1.1.1.1" {
		t.Fatalf("Japan's DNS: %v", japan)
	}
	// With Fake IP, the names through Japan are faked too, so Japan's node looks them up.
	options.FakeIP = true
	config, _, _ = Build(source(t, true), options)
	dnsRules := rules(config, "dns")
	for i, item := range dnsRules {
		if rule := item.(map[string]any); rule["server"] == "proxy-dns-JP" {
			if twin := dnsRules[i-1].(map[string]any); twin["server"] != fakeDNSTag || !reflect.DeepEqual(twin["domain_suffix"], rule["domain_suffix"]) {
				t.Fatalf("no fake twin before %v: %v", rule, twin)
			}
		}
	}
}

func TestANodeTaggedLikeACountrysGroupMovesAside(t *testing.T) {
	options := options
	options.Routes = []Rule{{"suffix", "example.com", "via:JP"}}
	options.Countries = map[string][]string{"JP": {"via-JP-2"}}
	config, nodes, err := Build(parse(t, `{"outbounds": [
	  {"type": "trojan", "tag": "via-JP", "server": "203.0.113.11", "server_port": 443, "password": "example"},
	  {"type": "trojan", "tag": "Tokyo", "server": "203.0.113.12", "server_port": 443, "password": "example"}
	]}`), options)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(nodes, []string{"via-JP-2", "Tokyo"}) {
		t.Fatalf("nodes: %v", nodes)
	}
	tags := 0
	for _, item := range config["outbounds"].([]any) {
		if outbound := item.(map[string]any); outbound["tag"] == "via-JP" {
			tags++
			if !reflect.DeepEqual(outbound["outbounds"], []any{"via-JP-2"}) {
				t.Fatalf("group: %v", outbound)
			}
		}
	}
	if tags != 1 {
		t.Fatalf("via-JP is tagged %d times", tags)
	}
}
