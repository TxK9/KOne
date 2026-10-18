package main

import (
	"net/netip"
	"regexp"
	"strconv"
	"strings"
)

// Rule is one of the person's routing rules: what it matches (Kind and Value) and where that
// goes (Action). The App sends them in order; the first that matches wins. They come after
// the Claude block, so none of them can reach round it.
type Rule struct {
	Kind   string `json:"kind"`
	Value  string `json:"value"`
	Action string `json:"action"`
}

// The kinds a rule can match on, and the sing-box field each fills.
var ruleFields = map[string]string{
	"domain": "domain", "suffix": "domain_suffix", "keyword": "domain_keyword",
	"cidr": "ip_cidr", "program": "process_name", "port": "port",
}

var ruleActions = map[string]bool{"proxy": true, "direct": true, "block": true}

// keyword is part of a domain name: letters, digits, dots and hyphens, two or more.
var keyword = regexp.MustCompile(`^[a-z0-9.-]{2,63}$`)

// mostRules bounds the person's rules.
const mostRules = 256

// UserRules are the person's rules as the template uses them: each checked and written one
// way (lower-case names, networks from their first address, ports as "a" or "a-b"), anything
// else dropped, each rule once, 256 at most. The App keeps the same (KoneConfig.rules).
func UserRules(rules []Rule) []Rule {
	seen := map[Rule]bool{}
	var kept []Rule
	for _, rule := range rules {
		if _, ok := ruleFields[rule.Kind]; !ok || !ruleActions[rule.Action] {
			continue
		}
		value, ok := ruleValue(rule.Kind, rule.Value)
		if !ok {
			continue
		}
		clean := Rule{Kind: rule.Kind, Value: value, Action: rule.Action}
		if seen[clean] {
			continue
		}
		seen[clean] = true
		kept = append(kept, clean)
		if len(kept) == mostRules {
			break
		}
	}
	return kept
}

func ruleValue(kind, value string) (string, bool) {
	value = strings.TrimSpace(value)
	switch kind {
	case "domain", "suffix":
		value = strings.ToLower(value)
		return value, len(value) <= 253 && site.MatchString(value)
	case "keyword":
		value = strings.ToLower(value)
		return value, keyword.MatchString(value)
	case "program":
		return value, program.MatchString(value)
	case "cidr":
		return network(value)
	case "port":
		return portValue(value)
	}
	return "", false
}

// plainIPv4 is four decimal numbers without leading zeros; plainIPv6 is hex and colons only,
// so no zone, no embedded IPv4: both sides then read every accepted address the same way.
var (
	plainIPv4 = regexp.MustCompile(`^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$`)
	plainIPv6 = regexp.MustCompile(`^[0-9a-f:]{2,39}$`)
)

// network is an address range written from its first address ("192.168.1.0/24"); an address
// alone is that one address.
func network(value string) (string, bool) {
	address, bits, hasBits := strings.Cut(strings.ToLower(value), "/")
	v4 := plainIPv4.MatchString(address)
	if !v4 && !(strings.Contains(address, ":") && plainIPv6.MatchString(address)) {
		return "", false
	}
	ip, err := netip.ParseAddr(address)
	if err != nil || ip.Is4() != v4 || ip.Is4In6() {
		return "", false
	}
	width := ip.BitLen()
	if hasBits {
		if bits == "" || (len(bits) > 1 && bits[0] == '0') {
			return "", false
		}
		n, err := strconv.Atoi(bits)
		if err != nil || n < 1 || n > width {
			return "", false
		}
		width = n
	}
	prefix, err := ip.Prefix(width)
	if err != nil {
		return "", false
	}
	return prefix.String(), true
}

// portValue is one port ("443") or a range ("1000-2000", or "1000:2000").
func portValue(value string) (string, bool) {
	low, high, isRange := strings.Cut(strings.ReplaceAll(value, ":", "-"), "-")
	from, ok := portNumber(low)
	if !ok {
		return "", false
	}
	if !isRange {
		return strconv.Itoa(from), true
	}
	to, ok := portNumber(high)
	if !ok || to < from {
		return "", false
	}
	return strconv.Itoa(from) + "-" + strconv.Itoa(to), true
}

func portNumber(text string) (int, bool) {
	text = strings.TrimSpace(text)
	if text == "" || len(text) > 5 || (len(text) > 1 && text[0] == '0') {
		return 0, false
	}
	n, err := strconv.Atoi(text)
	return n, err == nil && n >= 1 && n <= 65535
}

// ruleMatch is the sing-box condition for a checked rule.
func ruleMatch(rule Rule) map[string]any {
	if rule.Kind == "port" {
		if from, to, isRange := strings.Cut(rule.Value, "-"); isRange {
			return map[string]any{"port_range": []any{from + ":" + to}}
		}
		n, _ := strconv.Atoi(rule.Value)
		return map[string]any{"port": []any{n}}
	}
	return map[string]any{ruleFields[rule.Kind]: []any{rule.Value}}
}

// routeRule is where a connection matching [rule] goes: refused, or straight out, in every
// mode; through the node in Rule mode only, as Global already sends everything there and
// Direct nothing.
func routeRule(rule Rule) map[string]any {
	out := ruleMatch(rule)
	switch rule.Action {
	case "block":
		out["action"] = "reject"
	case "direct":
		out["outbound"] = "direct"
	default:
		out["clash_mode"] = "Rule"
		out["outbound"] = proxyTag
	}
	return out
}

// dnsRule answers names the way [rule] routes them; only rules on names and programs have one.
func dnsRule(rule Rule) map[string]any {
	if rule.Kind == "cidr" || rule.Kind == "port" {
		return nil
	}
	out := ruleMatch(rule)
	switch rule.Action {
	case "block":
		out["action"] = "reject"
	case "direct":
		out["server"] = "local-dns"
	default:
		out["clash_mode"] = "Rule"
		out["server"] = "proxy-dns"
	}
	return out
}
