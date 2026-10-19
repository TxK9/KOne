package main

import (
	"reflect"
	"strconv"
	"testing"
)

func TestNameServersAreCheckedAndGroupedByServer(t *testing.T) {
	got := NameServers([]NameServer{
		{" Corp.Example.org ", "192.0.2.53"},
		{"lab.example.org", "2001:DB8::53"},
		{"intra.example.net", "192.0.2.53"},
		{"corp.example.org", "198.51.100.53"}, // again: the first wins
		{"bad name", "192.0.2.53"},
		{"single", "192.0.2.53"},
		{"range.example.org", "192.0.2.0/24"},
		{"word.example.org", "dns.example.org"}, // a server is an address, never a name to look up
	})
	want := []nameServerGroup{
		{tag: "own-dns-1", server: "192.0.2.53", domains: []any{"corp.example.org", "intra.example.net"}},
		{tag: "own-dns-2", server: "2001:db8::53", domains: []any{"lab.example.org"}},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("name servers: %+v", got)
	}
	var many []NameServer
	for i := 0; i < 20; i++ {
		many = append(many, NameServer{"d" + strconv.Itoa(i) + ".example.org", "192.0.2." + strconv.Itoa(i+1)})
	}
	if bounded := NameServers(many); len(bounded) != mostNameServers {
		t.Fatalf("16 domains at most: %d", len(bounded))
	}
}

func TestNameServersAnswerAfterTheClaudeBlockAndTheHosts(t *testing.T) {
	options := options
	options.Hosts = []Host{{"nas.example.org", "192.0.2.10"}}
	options.NameServers = []NameServer{{"corp.example.org", "192.0.2.53"}, {"claude.ai", "192.0.2.66"}}
	config, _, err := Build(source(t, true), options)
	if err != nil {
		t.Fatal(err)
	}
	dns := config["dns"].(map[string]any)
	rules := dns["rules"].([]any)
	if rules[0].(map[string]any)["action"] != "reject" {
		t.Fatalf("the Claude block answers first: %v", rules[0])
	}
	// The refusals, the host's A and AAAA, then the person's servers: one given claude.ai never
	// answers for it, as the refusal comes first.
	if own := rules[3].(map[string]any); !reflect.DeepEqual(own, map[string]any{"domain_suffix": []any{"corp.example.org"}, "server": "own-dns-1"}) {
		t.Fatalf("own servers: %v", own)
	}
	if own := rules[4].(map[string]any); own["server"] != "own-dns-2" {
		t.Fatalf("own servers: %v", own)
	}
	servers := dns["servers"].([]any)
	if !reflect.DeepEqual(servers[2], map[string]any{"type": "udp", "tag": "own-dns-1", "server": "192.0.2.53"}) || len(servers) != 4 {
		t.Fatalf("servers: %v", servers)
	}
}
