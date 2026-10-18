package main

import (
	"reflect"
	"strconv"
	"testing"
)

func TestHostsAreCheckedAndGroupedByName(t *testing.T) {
	entries := []Host{
		{" NAS.Example.org ", "192.0.2.10"},
		{"nas.example.org", "2001:DB8::10"},
		{"nas.example.org", "192.0.2.10"}, // again
		{"printer.example.org", "192.0.2.20"},
		{"bad name", "192.0.2.1"},                  // not a name
		{"single", "192.0.2.1"},                    // one label
		{"range.example.org", "192.0.2.0/24"},      // a range
		{"zone.example.org", "fe80::1%eth0"},       // a zone
		{"mapped.example.org", "::ffff:192.0.2.1"}, // IPv4 written as IPv6
		{"leading.example.org", "192.0.02.1"},      // not written one way
		{"word.example.org", "localhost"},          // not an address
	}
	got := Hosts(entries)
	want := []hostGroup{
		{name: "nas.example.org", v4: []string{"192.0.2.10"}, v6: []string{"2001:db8::10"}},
		{name: "printer.example.org", v4: []string{"192.0.2.20"}},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("hosts: %+v", got)
	}

	var many []Host
	for i := 0; i < 70; i++ {
		many = append(many, Host{"h" + strconv.Itoa(i) + ".example.org", "192.0.2.1"})
	}
	for i := 0; i < 12; i++ {
		many = append(many, Host{"h0.example.org", "198.51.100." + strconv.Itoa(i+1)})
	}
	bounded := Hosts(many)
	if len(bounded) != hostNames || len(bounded[0].v4) != hostAddresses {
		t.Fatalf("64 names and 8 addresses a name at most: %d names, %d addresses", len(bounded), len(bounded[0].v4))
	}
}

func TestHostsAnswerAfterTheClaudeBlockAndAreResolvedBeforeRouting(t *testing.T) {
	options := options
	options.Hosts = []Host{{"nas.example.org", "192.0.2.10"}, {"claude.ai", "192.0.2.66"}}
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	dns := config["dns"].(map[string]any)["rules"].([]any)
	if dns[0].(map[string]any)["action"] != "reject" {
		t.Fatalf("the Claude block answers first: %v", dns[0])
	}
	a := dns[1].(map[string]any)
	if !reflect.DeepEqual(a["domain"], []any{"nas.example.org"}) || !reflect.DeepEqual(a["query_type"], []any{"A"}) ||
		!reflect.DeepEqual(a["answer"], []any{"nas.example.org. IN A 192.0.2.10"}) || a["action"] != "predefined" {
		t.Fatalf("A: %v", a)
	}
	if aaaa := dns[2].(map[string]any); !reflect.DeepEqual(aaaa["query_type"], []any{"AAAA"}) || aaaa["answer"] != nil {
		t.Fatalf("no IPv6 address: an empty answer, not a server's: %v", aaaa)
	}
	route := config["route"].(map[string]any)["rules"].([]any)
	// sniff, hijack-dns, the Claude block, the test cores, then the hosts' names resolved here.
	if r := route[4].(map[string]any); r["action"] != "resolve" || !reflect.DeepEqual(r["domain"], []any{"nas.example.org", "claude.ai"}) {
		t.Fatalf("resolve: %v", r)
	}
	if r := route[2].(map[string]any); r["action"] != "reject" {
		t.Fatalf("the Claude block still routes first: %v", r)
	}
	options.Hosts = nil
	plain, _, _ := Build(source(t, true), options)
	for _, rule := range plain["route"].(map[string]any)["rules"].([]any) {
		if rule.(map[string]any)["action"] == "resolve" {
			t.Fatal("no hosts, no resolve rule")
		}
	}
}
