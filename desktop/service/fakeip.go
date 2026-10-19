package main

// Fake-IP DNS, an option (off unless chosen): the names that would be answered through the node
// get an address from a reserved range at once instead, and sing-box gives the connection its
// name back, so the node resolves it where it is. Every other answer is as it was: the Claude
// block, fixed answers, the person's own servers, the direct names and Direct mode keep real
// addresses. The App builds the same (shared FakeIp.kt); the consistency test holds the two
// together.

const fakeDNSTag = "fake-dns"

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
	return append(out, map[string]any{"query_type": types, "server": fakeDNSTag})
}
