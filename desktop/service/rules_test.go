package main

import (
	"reflect"
	"strconv"
	"testing"
)

func TestRulesAreCheckedAndWrittenOneWay(t *testing.T) {
	got := UserRules([]Rule{
		{"suffix", " Example.COM ", "direct"},
		{"suffix", "example.com", "direct"},
		{"domain", "*.example.com", "block"},
		{"keyword", "Ads", "block"},
		{"keyword", "a", "block"},
		{"cidr", "192.168.1.77/24", "direct"},
		{"cidr", "10.0.0.1", "proxy"},
		{"cidr", "2001:DB8:0:0:1::5/64", "block"},
		{"cidr", "::ffff:102:304", "block"},
		{"cidr", "010.0.0.1/8", "block"},
		{"cidr", "10.0.0.0/08", "block"},
		{"cidr", "fe80::1%eth0", "block"},
		{"program", "Steam.exe", "proxy"},
		{"program", `C:\x\cmd.exe`, "direct"},
		{"port", "443", "block"},
		{"port", "1000:2000", "direct"},
		{"port", "2000-1000", "direct"},
		{"port", "0443", "direct"},
		{"port", "70000", "direct"},
		{"suffix", "example.org", "reject"},
		{"geoip", "cn", "direct"},
	})
	want := []Rule{
		{"suffix", "example.com", "direct"},
		{"keyword", "ads", "block"},
		{"cidr", "192.168.1.0/24", "direct"},
		{"cidr", "10.0.0.1/32", "proxy"},
		{"cidr", "2001:db8::/64", "block"},
		{"program", "Steam.exe", "proxy"},
		{"port", "443", "block"},
		{"port", "1000-2000", "direct"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v\nwant %v", got, want)
	}
	many := make([]Rule, 300)
	for i := range many {
		many[i] = Rule{"suffix", "s" + strconv.Itoa(i) + ".example", "direct"}
	}
	if n := len(UserRules(many)); n != mostRules {
		t.Fatalf("kept %d rules", n)
	}
}

func TestRulesComeAfterTheClaudeBlockAndAdsAfterThem(t *testing.T) {
	options := options
	options.Routes = []Rule{{"suffix", "claude.ai", "direct"}, {"keyword", "tracker", "block"}, {"cidr", "203.0.113.0/24", "proxy"}, {"port", "8443", "direct"}}
	options.BlockAds = true
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	index := func(match func(map[string]any) bool) int {
		for i, rule := range rules {
			if match(rule.(map[string]any)) {
				return i
			}
		}
		return -1
	}
	block := index(func(r map[string]any) bool { return r["action"] == "reject" && r["domain_suffix"] != nil && r["domain_suffix"].([]any)[0] != "claude.ai" })
	claude := index(func(r map[string]any) bool { s, _ := r["domain_suffix"].([]any); return len(s) == 1 && s[0] == "claude.ai" })
	cidr := index(func(r map[string]any) bool { return r["ip_cidr"] != nil })
	ads := index(func(r map[string]any) bool { s, _ := r["rule_set"].([]any); return len(s) == 1 && s[0] == adsRuleSet })
	if block < 0 || claude < 0 || cidr < 0 || ads < 0 || !(block < claude && claude < cidr && cidr < ads) {
		t.Fatalf("order: block %d, claude.ai %d, cidr %d, ads %d: %v", block, claude, cidr, ads, rules)
	}
	if rules[cidr].(map[string]any)["clash_mode"] != "Rule" {
		t.Fatal("a rule through the node only matters in Rule mode")
	}
	if rules[ads].(map[string]any)["action"] != "reject" {
		t.Fatal("ads are refused")
	}
	sets := route["rule_set"].([]any)
	if len(sets) != 3 || sets[2].(map[string]any)["initial_path"] != options.Rules+"/"+adsRuleSet+".srs" {
		t.Fatalf("rule sets: %v", sets)
	}
	for _, rule := range config["dns"].(map[string]any)["rules"].([]any) {
		r := rule.(map[string]any)
		if r["ip_cidr"] != nil || r["port"] != nil {
			t.Fatalf("addresses and ports have no DNS rule: %v", r)
		}
	}
	options.BlockAds = false
	plain, _, _ := Build(source(t, true), options)
	if n := len(plain["route"].(map[string]any)["rule_set"].([]any)); n != 2 {
		t.Fatalf("without ad blocking its rule set is not loaded (%d sets)", n)
	}
}
