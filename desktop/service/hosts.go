package main

import (
	"strings"
)

// Host is one fixed answer the person set: a name and an address for it (Settings → Hosts).
type Host struct {
	Name    string `json:"name"`
	Address string `json:"address"`
}

// hostNames and hostAddresses bound the fixed answers: names, and addresses for one name.
const (
	hostNames     = 64
	hostAddresses = 8
)

// hostGroup is a name and its addresses, IPv4 and IPv6 apart.
type hostGroup struct {
	name   string
	v4, v6 []string
}

// Hosts are the person's fixed answers as the template uses them: names written as sites are
// (lower case, two labels or more), addresses as plain IPv4 or IPv6 without a range, anything
// else dropped, each pair once, grouped by name in the order first given, 64 names and 8
// addresses a name at most. The App keeps the same (core/Hosts.kt).
func Hosts(entries []Host) []hostGroup {
	var groups []hostGroup
	index := map[string]int{}
	seen := map[string]bool{}
	for _, entry := range entries {
		name := strings.ToLower(strings.TrimSpace(entry.Name))
		if len(name) > 253 || !site.MatchString(name) {
			continue
		}
		raw := strings.TrimSpace(entry.Address)
		if strings.Contains(raw, "/") {
			continue
		}
		prefix, ok := network(raw)
		if !ok {
			continue
		}
		address, _, _ := strings.Cut(prefix, "/")
		if seen[name+" "+address] {
			continue
		}
		at, known := index[name]
		if !known {
			if len(groups) == hostNames {
				continue
			}
			at = len(groups)
			index[name] = at
			groups = append(groups, hostGroup{name: name})
		}
		group := &groups[at]
		if len(group.v4)+len(group.v6) == hostAddresses {
			continue
		}
		seen[name+" "+address] = true
		if strings.Contains(address, ":") {
			group.v6 = append(group.v6, address)
		} else {
			group.v4 = append(group.v4, address)
		}
	}
	return groups
}

// hostRules answer each name from its fixed addresses, IPv4 and IPv6 apart; a name with none of
// one kind answers that kind with nothing, rather than asking a server.
func hostRules(groups []hostGroup) []any {
	var rules []any
	for _, group := range groups {
		for _, kind := range []struct {
			query, record string
			addresses     []string
		}{{"A", "A", group.v4}, {"AAAA", "AAAA", group.v6}} {
			rule := map[string]any{"domain": []any{group.name}, "query_type": []any{kind.query}, "action": "predefined"}
			if len(kind.addresses) > 0 {
				var answers []any
				for _, address := range kind.addresses {
					answers = append(answers, group.name+". IN "+kind.record+" "+address)
				}
				rule["answer"] = answers
			}
			rules = append(rules, rule)
		}
	}
	return rules
}

// hostNamesOf are the names with fixed answers, for the route rule that resolves them here.
func hostNamesOf(groups []hostGroup) []any {
	var names []any
	for _, group := range groups {
		names = append(names, group.name)
	}
	return names
}
