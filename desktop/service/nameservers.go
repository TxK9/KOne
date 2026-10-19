package main

import (
	"strconv"
	"strings"
)

// NameServer is a DNS server of the person's own for the names under one domain (Settings →
// DNS), as a company's intranet names are answered only by its own server.
type NameServer struct {
	Domain string `json:"domain"`
	Server string `json:"server"`
}

// mostNameServers bounds the domains given their own server.
const mostNameServers = 16

// nameServerGroup is one server and the domains it answers, under the tag the template gives it.
type nameServerGroup struct {
	tag, server string
	domains     []any
}

// NameServers are the person's servers as the template uses them: domains written as sites are,
// servers as a plain IPv4 or IPv6 address, anything else dropped, each domain once (the first
// given wins), 16 domains at most, grouped by server in the order first given and tagged
// own-dns-1, own-dns-2…. The App keeps the same (core/NameServers.kt).
func NameServers(entries []NameServer) []nameServerGroup {
	var groups []nameServerGroup
	index := map[string]int{}
	seen := map[string]bool{}
	count := 0
	for _, entry := range entries {
		domain := strings.ToLower(strings.TrimSpace(entry.Domain))
		if len(domain) > 253 || !site.MatchString(domain) || seen[domain] {
			continue
		}
		server, ok := plainAddress(entry.Server)
		if !ok || count == mostNameServers {
			continue
		}
		seen[domain] = true
		count++
		at, known := index[server]
		if !known {
			at = len(groups)
			index[server] = at
			groups = append(groups, nameServerGroup{tag: "own-dns-" + strconv.Itoa(at+1), server: server})
		}
		groups[at].domains = append(groups[at].domains, domain)
	}
	return groups
}

// nameServerRules send the names under each domain to its server.
func nameServerRules(groups []nameServerGroup) []any {
	var rules []any
	for _, group := range groups {
		rules = append(rules, map[string]any{"domain_suffix": group.domains, "server": group.tag})
	}
	return rules
}

// nameServerServers are the servers themselves, asked straight out, as the direct server is.
func nameServerServers(groups []nameServerGroup) []any {
	var servers []any
	for _, group := range groups {
		servers = append(servers, map[string]any{"type": "udp", "tag": group.tag, "server": group.server})
	}
	return servers
}
