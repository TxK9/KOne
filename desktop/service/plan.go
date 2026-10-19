package main

import (
	"errors"
	"regexp"
	"strconv"
	"strings"
)

// The KOne configuration, built here by the service and not taken from the App: what runs as
// SYSTEM is always this template, with only the nodes and the Claude block coming from the
// console's source. desktop/src/main/kotlin/cc/txwd/kone/core/KoneConfig.kt builds the same
// for the App's own uses (node list, delay probe); a test checks the two agree.

const proxyTag = "proxy"

// autoTag is the selector's Auto: whichever node answers fastest; there only with two nodes or more.
const autoTag = "auto"

// proxyTypes are the outbounds kept as nodes. naive is left out: it needs libcronet.dll, which
// the App does not carry.
var proxyTypes = map[string]bool{
	"vless": true, "vmess": true, "trojan": true, "shadowsocks": true, "socks": true, "http": true,
	"hysteria": true, "hysteria2": true, "tuic": true, "ssh": true, "anytls": true, "shadowtls": true,
}

// adsRuleSet is the ad-blocking rule set carried beside the China ones (v2fly's category-ads-all).
const adsRuleSet = "geosite-category-ads-all"

// reserved are the tags the template uses itself; a source outbound with one of them is renamed.
var reserved = map[string]bool{proxyTag: true, autoTag: true, "direct": true, "block": true, "dns": true, "local-dns": true, "proxy-dns": true, "tun-in": true, "mixed-in": true}

var errInvalid = errors.New("config_invalid")

// Options are what besides the source shapes a configuration.
type Options struct {
	Selected string // the node to start on, if it is still there
	Rules    string // the folder holding geosite-cn.srs and geoip-cn.srs
	Port     int    // clash_api on 127.0.0.1
	Secret   string
	Tun      bool     // false: the nodes only, for testing delays without rights
	Mode     string   // Rule, Global or Direct; anything else is Rule
	Bypass   []string // programs sent straight out, by file name (WeChat.exe)
	Direct   []string // sites sent straight out, in every mode, by domain (example.cn and what is under it)
	Proxied  []string // sites through the node in Rule too, by domain
	Routes   []Rule   // the person's own rules, in order, after the lists above
	BlockAds bool     // refuse the domains of the ad-blocking rule set
	Remote   string   // the DNS server asked through the node: a preset or a DNS-over-HTTPS address; "" Cloudflare
	Domestic string   // the DNS server asked straight out: a preset or an address; "" AliDNS
	IPv6     bool     // answer names with IPv6 addresses too (IPv4 first)
	Mixed    int      // a local proxy port on 127.0.0.1 for the system proxy, when not 0 (the App's own, never the service's)
	Hosts    []Host   // fixed answers for chosen names, after the Claude block
	// NameServers answer the names under chosen domains from servers of the person's own, after
	// the Claude block and the fixed answers.
	NameServers []NameServer
	// UnmatchedDirect sends what no rule, list or category matched straight out in Rule mode
	// (and answers its names from the direct DNS), instead of through the node.
	UnmatchedDirect bool
}

// modes are the clash modes the core can start in.
var modes = map[string]bool{"Rule": true, "Global": true, "Direct": true}

// program is a file name Windows would take, ending in .exe, with no path.
var program = regexp.MustCompile(`(?i)^[^\\/:*?"<>|\x00-\x1f]{1,128}\.exe$`)

// Programs are the file names to send straight out: anything with a path or that Windows would
// not take as a file name is dropped, each name once, 64 at most. The App keeps the same
// (KoneConfig.programs). Sent straight out is all they can be: the Claude block comes first.
func Programs(names []string) []any {
	seen := map[string]bool{}
	var kept []any
	for _, name := range names {
		name = strings.TrimSpace(name)
		if !program.MatchString(name) || seen[strings.ToLower(name)] {
			continue
		}
		seen[strings.ToLower(name)] = true
		kept = append(kept, name)
		if len(kept) == 64 {
			break
		}
	}
	return kept
}

// site is a domain name of two labels or more, in lower case, its last starting with a letter:
// no address, path, port or wildcard.
var site = regexp.MustCompile(`^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$`)

// Sites are the person's sites, as domains: anything but a plain domain name of two labels or
// more is dropped, each name once, 256 at most. The App keeps the same
// (KoneConfig.sites). The Claude block comes before them, so neither list reaches round it.
func Sites(names []string) []any {
	seen := map[string]bool{}
	var kept []any
	for _, name := range names {
		name = strings.ToLower(strings.TrimSpace(name))
		if len(name) > 253 || !site.MatchString(name) || seen[name] {
			continue
		}
		seen[name] = true
		kept = append(kept, name)
		if len(kept) == 256 {
			break
		}
	}
	return kept
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
	auto := len(nodes) >= 2
	start := nodes[0]
	for _, tag := range nodes {
		if tag == options.Selected {
			start = tag
		}
	}

	if auto && options.Selected == autoTag {
		start = autoTag
	}
	choices := anyList(nodes)
	if auto {
		choices = append([]any{autoTag}, choices...)
	}
	outbounds := []any{map[string]any{"type": "selector", "tag": proxyTag, "outbounds": choices, "default": start}}
	// The App tests the node in use every few seconds and every node often, which also feeds this
	// group; its own sweep of every node can be rare, so large subscriptions stay cheap.
	if auto {
		outbounds = append(outbounds, map[string]any{
			"type": "urltest", "tag": autoTag, "outbounds": anyList(nodes),
			"url": "https://www.gstatic.com/generate_204", "interval": "10m", "tolerance": 50,
		})
	}
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
	// KOne's own test cores (delays, speed) reach their nodes straight out, not through the tunnel.
	if options.Tun {
		routeRules = append(routeRules, map[string]any{"process_name": []any{"sing-box.exe"}, "outbound": "direct"})
	}
	// Fixed answers: after the Claude block, so none can answer for a refused name; resolved here
	// before routing, so a node is never asked for a name only this computer knows.
	if hosts := Hosts(options.Hosts); len(hosts) > 0 {
		dnsRules = append(dnsRules, hostRules(hosts)...)
		routeRules = append(routeRules, map[string]any{"domain": hostNamesOf(hosts), "action": "resolve"})
	}
	// The person's own servers for chosen domains: after the Claude block too, so none answers for a refused name.
	ownServers := NameServers(options.NameServers)
	dnsRules = append(dnsRules, nameServerRules(ownServers)...)
	if straight := Programs(options.Bypass); len(straight) > 0 {
		dnsRules = append(dnsRules, map[string]any{"process_name": straight, "server": "local-dns"})
		routeRules = append(routeRules, map[string]any{"process_name": straight, "outbound": "direct"})
	}
	if direct := Sites(options.Direct); len(direct) > 0 {
		dnsRules = append(dnsRules, map[string]any{"domain_suffix": direct, "server": "local-dns"})
		routeRules = append(routeRules, map[string]any{"domain_suffix": direct, "outbound": "direct"})
	}
	if proxied := Sites(options.Proxied); len(proxied) > 0 {
		dnsRules = append(dnsRules, map[string]any{"clash_mode": "Rule", "domain_suffix": proxied, "server": "proxy-dns"})
		routeRules = append(routeRules, map[string]any{"clash_mode": "Rule", "domain_suffix": proxied, "outbound": proxyTag})
	}
	own := UserRules(options.Routes)
	for _, rule := range own {
		if dns := dnsRule(rule); dns != nil {
			dnsRules = append(dnsRules, dns)
		}
		routeRules = append(routeRules, routeRule(rule))
	}
	// Ads are refused in every mode, after the person's own rules so a site they let through stays reachable.
	if options.BlockAds {
		dnsRules = append(dnsRules, map[string]any{"rule_set": []any{adsRuleSet}, "action": "reject"})
		routeRules = append(routeRules, map[string]any{"rule_set": []any{adsRuleSet}, "action": "reject"})
	}
	dnsRules = append(dnsRules,
		map[string]any{"clash_mode": "Direct", "server": "local-dns"},
		map[string]any{"clash_mode": "Global", "server": "proxy-dns"},
		map[string]any{"rule_set": []any{"geosite-cn"}, "server": "local-dns"},
	)
	unmatched := proxyTag
	if options.UnmatchedDirect {
		dnsRules = append(dnsRules, map[string]any{"clash_mode": "Rule", "server": "local-dns"})
		unmatched = "direct"
	}
	routeRules = append(routeRules,
		map[string]any{"clash_mode": "Direct", "outbound": "direct"},
		map[string]any{"clash_mode": "Global", "outbound": proxyTag},
		map[string]any{"ip_is_private": true, "outbound": "direct"},
		map[string]any{"clash_mode": "Rule", "rule_set": []any{"geosite-cn", "geoip-cn"}, "outbound": "direct"},
		map[string]any{"clash_mode": "Rule", "outbound": unmatched},
	)
	ruleSets := []any{}
	sets := [][2]string{{"geosite-cn", "sing-geosite"}, {"geoip-cn", "sing-geoip"}}
	if options.BlockAds {
		sets = append(sets, [2]string{adsRuleSet, "sing-geosite"})
	}
	for _, set := range sets {
		ruleSets = append(ruleSets, map[string]any{
			"type": "remote", "tag": set[0], "format": "binary",
			"url":          "https://raw.githubusercontent.com/SagerNet/" + set[1] + "/rule-set/" + set[0] + ".srs",
			"initial_path": options.Rules + "/" + set[0] + ".srs",
			"http_client":  map[string]any{"detour": proxyTag, "domain_resolver": "local-dns"},
		})
	}
	// The person's category lists, kept in the rules folder's sets\ by the service (or the App,
	// for its own cores): local files, never fetched by the core.
	for _, name := range RuleSets(own) {
		ruleSets = append(ruleSets, map[string]any{"type": "local", "tag": name, "format": "binary", "path": options.Rules + "/sets/" + name + ".srs"})
	}
	inbounds := []any{}
	if options.Mixed > 0 {
		inbounds = append(inbounds, map[string]any{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": options.Mixed})
	}
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
			"servers":  append([]any{directDNS(options.Domestic), remoteDNS(options.Remote)}, nameServerServers(ownServers)...),
			"rules":    dnsRules,
			"final":    "proxy-dns",
			"strategy": dnsStrategy(options.IPv6),
		},
		"inbounds":  inbounds,
		"outbounds": outbounds,
		"route": map[string]any{
			"rules":                   routeRules,
			"rule_set":                ruleSets,
			"auto_detect_interface":   true,
			"find_process":            true,
			"default_domain_resolver": "local-dns",
			"final":                   proxyTag,
		},
		// No cache file: the core runs as SYSTEM and writes nothing anywhere.
		"experimental": map[string]any{
			"clash_api": map[string]any{
				"external_controller": "127.0.0.1:" + strconv.Itoa(options.Port),
				"secret":              options.Secret,
				"default_mode":        mode(options.Mode),
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

func mode(wanted string) string {
	if modes[wanted] {
		return wanted
	}
	return "Rule"
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
