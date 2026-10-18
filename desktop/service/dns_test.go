package main

import (
	"reflect"
	"testing"
)

func TestDNSChoicesAreChecked(t *testing.T) {
	remote := map[string]string{
		"":                                 "",
		"google":                           "google",
		"https://dns.example.com":          "https://dns.example.com:443/dns-query",
		" https://DNS.Example.com:8443/q ": "https://dns.example.com:8443/q",
		"https://203.0.113.53/dns-query":   "https://203.0.113.53:443/dns-query",
		"https://[2001:DB8::53]:853/dns":   "https://[2001:db8::53]:853/dns",
		"http://dns.example.com":           "",
		"https://dns.example.com/a?b=c":    "",
		"https://user@dns.example.com":     "",
		"https://localhost":                "",
		"https://dns.example.com:0":        "",
		"https://[203.0.113.53]":           "",
		"tls://1.1.1.1":                    "",
	}
	for in, want := range remote {
		if got := RemoteDNSChoice(in); got != want {
			t.Errorf("remote %q: got %q, want %q", in, got, want)
		}
	}
	direct := map[string]string{
		"":              "",
		"dnspod":        "dnspod",
		" 203.0.113.1 ": "203.0.113.1",
		"2001:DB8::1":   "2001:db8::1",
		"10.0.0.0/8":    "",
		"dns.example":   "",
		"cloudflare":    "",
	}
	for in, want := range direct {
		if got := DirectDNSChoice(in); got != want {
			t.Errorf("direct %q: got %q, want %q", in, got, want)
		}
	}
}

func TestDNSServersFollowTheChoices(t *testing.T) {
	options := options
	config, _, _ := Build(source(t, true), options)
	dns := config["dns"].(map[string]any)
	want := []any{
		map[string]any{"type": "udp", "tag": "local-dns", "server": "223.5.5.5"},
		map[string]any{"type": "https", "tag": "proxy-dns", "server": "1.1.1.1", "detour": proxyTag},
	}
	if !reflect.DeepEqual(dns["servers"], want) || dns["strategy"] != "ipv4_only" {
		t.Fatalf("the defaults changed: %v %v", dns["servers"], dns["strategy"])
	}
	options.Remote = "https://dns.example.com:8443/q"
	options.Domestic = "2001:db8::1"
	options.IPv6 = true
	config, _, _ = Build(source(t, true), options)
	dns = config["dns"].(map[string]any)
	want = []any{
		map[string]any{"type": "udp", "tag": "local-dns", "server": "2001:db8::1"},
		map[string]any{"type": "https", "tag": "proxy-dns", "server": "dns.example.com", "server_port": 8443, "path": "/q", "detour": proxyTag, "domain_resolver": "local-dns"},
	}
	if !reflect.DeepEqual(dns["servers"], want) || dns["strategy"] != "prefer_ipv4" {
		t.Fatalf("got %v %v", dns["servers"], dns["strategy"])
	}
	options.Remote = "http://evil.example.com"
	options.Domestic = "evil"
	config, _, _ = Build(source(t, true), options)
	if servers := config["dns"].(map[string]any)["servers"].([]any); servers[0].(map[string]any)["server"] != "223.5.5.5" || servers[1].(map[string]any)["server"] != "1.1.1.1" {
		t.Fatalf("a refused choice falls back to the defaults: %v", servers)
	}
}
