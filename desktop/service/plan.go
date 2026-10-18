package main

import (
	"errors"
	"strconv"
	"strings"
)

// The KOne configuration, built here by the service and not taken from the App: what runs as
// SYSTEM is always this template, with only the nodes and the Claude block coming from the
// console's source. desktop/src/main/kotlin/cc/txwd/kone/core/KoneConfig.kt builds the same
// for the App's own uses (node list, delay probe); a test checks the two agree.

const proxyTag = "proxy"

// proxyTypes are the outbounds kept as nodes. naive is left out: it needs libcronet.dll, which
// the App does not carry.
var proxyTypes = map[string]bool{
	"vless": true, "vmess": true, "trojan": true, "shadowsocks": true, "socks": true, "http": true,
	"hysteria": true, "hysteria2": true, "tuic": true, "ssh": true, "anytls": true, "shadowtls": true,
}

// reserved are the tags the template uses itself; a source outbound with one of them is renamed.
var reserved = map[string]bool{proxyTag: true, "direct": true, "block": true, "dns": true, "local-dns": true, "proxy-dns": true, "tun-in": true}

var errInvalid = errors.New("config_invalid")

// Options are what besides the source shapes a configuration.
type Options struct {
	Selected string // the node to start on, if it is still there
	Rules    string // the folder holding geosite-cn.srs and geoip-cn.srs
	Port     int    // clash_api on 127.0.0.1
	Secret   string
	Tun      bool // false: the nodes only, for testing delays without rights
}

// Build turns the console's source into the configuration the core runs.
func Build(source map[string]any, options Options) (map[string]any, []string, error) {
	list, _ := source["outbounds"].([]any)
	if list == nil {
		return nil, nil, errInvalid
	}
	var all []map[string]any
	for _, item := range list {
		// An outbound that names a file is never run: as SYSTEM it could read or write any file.
		if outbound, ok := item.(map[string]any); ok && !namesAFile(outbound) {
			all = append(all, outbound)
		}
	}
	var proxies []map[string]any
	byTag := map[string]map[string]any{}
	for _, outbound := range all {
		if proxyTypes[text(outbound, "type")] {
			proxies = append(proxies, outbound)
		}
		if tag := text(outbound, "tag"); strings.TrimSpace(tag) != "" {
			byTag[tag] = outbound
		}
	}
	if len(proxies) == 0 {
		return nil, nil, errInvalid
	}

	// Whatever a node goes through is kept, however long the chain; a loop or a missing link is refused.
	support := map[string]map[string]any{}
	var supportOrder []string
	var follow func(outbound map[string]any, depth int) error
	follow = func(outbound map[string]any, depth int) error {
		next := text(outbound, "detour")
		if strings.TrimSpace(next) == "" {
			return nil
		}
		if depth > 8 {
			return errInvalid
		}
		if _, seen := support[next]; seen {
			return nil
		}
		target, ok := byTag[next]
		if !ok || (!proxyTypes[text(target, "type")] && text(target, "type") != "direct") {
			return errInvalid
		}
		support[next] = target
		supportOrder = append(supportOrder, next)
		return follow(target, depth+1)
	}
	for _, proxy := range proxies {
		if err := follow(proxy, 0); err != nil {
			return nil, nil, err
		}
	}
	var members []map[string]any
	for _, proxy := range proxies {
		if _, isSupport := support[text(proxy, "tag")]; !isSupport {
			members = append(members, proxy)
		}
	}
	if len(members) == 0 {
		return nil, nil, errInvalid
	}

	// Names stay as the console gave them; a tag that clashes with the template's, or repeats, moves aside.
	used := map[string]bool{}
	renamed := map[string]string{}
	claim := func(wanted string) string {
		base := wanted
		if strings.TrimSpace(base) == "" {
			base = "node"
		}
		tag := base
		for index := 2; used[tag] || reserved[tag]; index++ {
			tag = base + "-" + strconv.Itoa(index)
		}
		used[tag] = true
		return tag
	}
	nodes := make([]string, len(members))
	for i, member := range members {
		name := text(member, "tag")
		nodes[i] = claim(name)
		if strings.TrimSpace(name) != "" {
			renamed[name] = nodes[i]
		}
	}
	supportTags := map[string]string{}
	for _, old := range supportOrder {
		supportTags[old] = claim(old)
		renamed[old] = supportTags[old]
	}
	retag := func(outbound map[string]any, tag string) map[string]any {
		copied := make(map[string]any, len(outbound))
		for key, value := range outbound {
			copied[key] = value
		}
		copied["tag"] = tag
		if detour := text(outbound, "detour"); detour != "" {
			if to, ok := renamed[detour]; ok {
				copied["detour"] = to
			}
		}
		return copied
	}
	start := nodes[0]
	for _, tag := range nodes {
		if tag == options.Selected {
			start = tag
		}
	}

	outbounds := []any{map[string]any{"type": "selector", "tag": proxyTag, "outbounds": anyList(nodes), "default": start}}
	for i, member := range members {
		outbounds = append(outbounds, retag(member, nodes[i]))
	}
	for _, old := range supportOrder {
		outbounds = append(outbounds, retag(support[old], supportTags[old]))
	}
	outbounds = append(outbounds, map[string]any{"type": "direct", "tag": "direct"})

	refused := Refusals(source)
	dnsRules := []any{}
	routeRules := []any{map[string]any{"action": "sniff"}, map[string]any{"protocol": "dns", "action": "hijack-dns"}}
	for _, rule := range refused {
		dnsRules = append(dnsRules, rule)
		routeRules = append(routeRules, rule)
	}
	dnsRules = append(dnsRules,
		map[string]any{"clash_mode": "Direct", "server": "local-dns"},
		map[string]any{"clash_mode": "Global", "server": "proxy-dns"},
		map[string]any{"rule_set": []any{"geosite-cn"}, "server": "local-dns"},
	)
	routeRules = append(routeRules,
		map[string]any{"clash_mode": "Direct", "outbound": "direct"},
		map[string]any{"clash_mode": "Global", "outbound": proxyTag},
		map[string]any{"ip_is_private": true, "outbound": "direct"},
		map[string]any{"clash_mode": "Rule", "rule_set": []any{"geosite-cn", "geoip-cn"}, "outbound": "direct"},
		map[string]any{"clash_mode": "Rule", "outbound": proxyTag},
	)
	ruleSets := []any{}
	for _, set := range [][2]string{{"geosite-cn", "sing-geosite"}, {"geoip-cn", "sing-geoip"}} {
		ruleSets = append(ruleSets, map[string]any{
			"type": "remote", "tag": set[0], "format": "binary",
			"url":          "https://raw.githubusercontent.com/SagerNet/" + set[1] + "/rule-set/" + set[0] + ".srs",
			"initial_path": options.Rules + "/" + set[0] + ".srs",
			"http_client":  map[string]any{"detour": proxyTag, "domain_resolver": "local-dns"},
		})
	}
	inbounds := []any{}
	if options.Tun {
		inbounds = append(inbounds, map[string]any{
			"type": "tun", "tag": "tun-in", "interface_name": "KOne",
			"address":    []any{"172.19.0.1/30", "fdfe:dcba:9876::1/126"},
			"auto_route": true,
			// On Windows strict routing keeps DNS from leaking past the tunnel.
			"strict_route": true,
		})
	}
	config := map[string]any{
		"log": map[string]any{"level": "warn", "timestamp": true},
		"dns": map[string]any{
			"servers": []any{
				map[string]any{"type": "udp", "tag": "local-dns", "server": "223.5.5.5"},
				map[string]any{"type": "https", "tag": "proxy-dns", "server": "1.1.1.1", "detour": proxyTag},
			},
			"rules":    dnsRules,
			"final":    "proxy-dns",
			"strategy": "ipv4_only",
		},
		"inbounds":  inbounds,
		"outbounds": outbounds,
		"route": map[string]any{
			"rules":                   routeRules,
			"rule_set":                ruleSets,
			"auto_detect_interface":   true,
			"default_domain_resolver": "local-dns",
			"final":                   proxyTag,
		},
		// No cache file: the core runs as SYSTEM and writes nothing anywhere.
		"experimental": map[string]any{
			"clash_api": map[string]any{
				"external_controller": "127.0.0.1:" + strconv.Itoa(options.Port),
				"secret":              options.Secret,
				"default_mode":        "Rule",
			},
		},
	}
	return config, nodes, nil
}

// Refusals are the source's rules that refuse domain names and do nothing else: the console's
// Claude block. A reject rule with any other condition, or without a name, is ignored, since it
// could refuse far more than names. The list of names is the console's, not the App's.
func Refusals(source map[string]any) []any {
	route, _ := source["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	var kept []any
	for _, item := range rules {
		rule, ok := item.(map[string]any)
		if !ok || text(rule, "action") != "reject" {
			continue
		}
		plain := true
		for key := range rule {
			if key != "action" && key != "domain" && key != "domain_suffix" {
				plain = false
			}
		}
		if !plain {
			continue
		}
		refusal := map[string]any{}
		for _, field := range []string{"domain", "domain_suffix"} {
			values, _ := rule[field].([]any)
			var names []any
			for _, value := range values {
				if name, ok := value.(string); ok && strings.TrimSpace(name) != "" {
					names = append(names, strings.TrimSpace(name))
				}
			}
			if len(names) > 0 {
				refusal[field] = names
			}
		}
		if len(refusal) > 0 {
			refusal["action"] = "reject"
			kept = append(kept, refusal)
		}
	}
	return kept
}

// namesAFile is true when anything in the outbound is a file path option (certificate_path,
// key_path, private_key_path, ech config_path and the like).
func namesAFile(value any) bool {
	switch typed := value.(type) {
	case map[string]any:
		for key, inner := range typed {
			if strings.HasSuffix(strings.ToLower(key), "_path") || namesAFile(inner) {
				return true
			}
		}
	case []any:
		for _, inner := range typed {
			if namesAFile(inner) {
				return true
			}
		}
	}
	return false
}

func text(object map[string]any, key string) string {
	value, _ := object[key].(string)
	return value
}

func anyList(values []string) []any {
	out := make([]any, len(values))
	for i, value := range values {
		out[i] = value
	}
	return out
}
