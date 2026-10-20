package main

// Fake-IP DNS, an option (off unless chosen): the names that would be answered through the node
// get an address from a reserved range at once instead, and sing-box gives the connection its
// name back, so the node resolves it where it is. Every other answer is as it was: the Claude
// block, fixed answers, the person's own servers, the direct names and Direct mode keep real
// addresses. The App builds the same (shared FakeIp.kt); the consistency test holds the two
// together.

const fakeDNSTag = "fake-dns"

// realNames keep real answers even through the node: Windows checks the Internet by looking one up
// (dns.msftncsi.com must be 131.107.255.255) and says "No Internet" otherwise.
var realNames = []any{"msftncsi.com", "msftconnecttest.com"}

// named are the rule keys that already say which names a rule matches; such a rule is not one
// the real names meet.
var named = map[string]bool{"domain": true, "domain_suffix": true, "domain_keyword": true, "domain_regex": true, "rule_set": true}

// fakeDNSServer is the fake-IP server, with an IPv6 range only when names are answered with IPv6.
func fakeDNSServer(ipv6 bool) map[string]any {
	server := map[string]any{"type": "fakeip", "tag": fakeDNSTag, "inet4_range": "198.18.0.0/15"}
	if ipv6 {
		server["inet6_range"] = "fc00::/18"
	}
	return server
}

// fakeIPRules precedes each rule that asks the node's DNS with its twin asking the fake-IP server
// for address queries (A, and AAAA with IPv6), and adds one more at the end for what no rule
// answers. Other query types still go to the node's DNS.
func fakeIPRules(rules []any, ipv6 bool) []any {
	types := []any{"A"}
	if ipv6 {
		types = append(types, "AAAA")
	}
	out := make([]any, 0, len(rules)*2+1)
	for _, item := range rules {
		if rule, ok := item.(map[string]any); ok && rule["server"] == "proxy-dns" {
			// The real names, under the same conditions, keep the real answer first.
			plain := true
			for key := range rule {
				if named[key] {
					plain = false
				}
			}
			if plain {
				real := make(map[string]any, len(rule)+1)
				for key, value := range rule {
					real[key] = value
				}
				real["domain_suffix"] = realNames
				out = append(out, real)
			}
			twin := make(map[string]any, len(rule)+1)
			for key, value := range rule {
				twin[key] = value
			}
			twin["query_type"] = types
			twin["server"] = fakeDNSTag
			out = append(out, twin)
		}
		out = append(out, item)
	}
	out = append(out, map[string]any{"domain_suffix": realNames, "server": "proxy-dns"})
	return append(out, map[string]any{"query_type": types, "server": fakeDNSTag})
}
