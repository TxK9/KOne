package main

import (
	"net/netip"
	"regexp"
	"strconv"
	"strings"
)

// The DNS servers a person can choose: the remote one, asked through the node, and the direct
// one, asked straight out for names that stay out of the tunnel. Anything else is refused, so
// the default ("") keeps the template's own servers.
var remotePresets = map[string]string{"cloudflare": "1.1.1.1", "google": "8.8.8.8", "quad9": "9.9.9.9"}
var directPresets = map[string]string{"alidns": "223.5.5.5", "dnspod": "119.29.29.29"}

// dohPath is the path of a DNS-over-HTTPS address: letters, digits and a few marks.
var dohPath = regexp.MustCompile(`^/[A-Za-z0-9/_.~-]{0,99}$`)

// remoteDNS is the server asked through the node: a preset, or a DNS-over-HTTPS address
// (https://host[:port][/path]) whose host is an address or a domain name; a domain is looked
// up straight out, as the node's own address is.
func remoteDNS(choice string) map[string]any {
	server := map[string]any{"type": "https", "tag": "proxy-dns", "server": "1.1.1.1", "detour": proxyTag}
	if address, ok := remotePresets[choice]; ok {
		server["server"] = address
		return server
	}
	host, port, path, ok := dohAddress(choice)
	if !ok {
		return server
	}
	server["server"] = host
	if port != 443 {
		server["server_port"] = port
	}
	if path != "/dns-query" {
		server["path"] = path
	}
	if _, isAddress := plainAddress(host); !isAddress {
		server["domain_resolver"] = "local-dns"
	}
	return server
}

// directDNS is the server asked straight out: a preset, or an address (plain DNS over UDP).
func directDNS(choice string) map[string]any {
	server := map[string]any{"type": "udp", "tag": "local-dns", "server": "223.5.5.5"}
	if address, ok := directPresets[choice]; ok {
		server["server"] = address
	} else if address, ok := plainAddress(choice); ok {
		server["server"] = address
	}
	return server
}

// RemoteDNSChoice and DirectDNSChoice are the choices as the template takes them: a preset's
// name, a checked custom address, or "" for the default. The App keeps the same (KoneConfig).
func RemoteDNSChoice(choice string) string {
	choice = strings.TrimSpace(choice)
	if _, ok := remotePresets[choice]; ok {
		return choice
	}
	if host, port, path, ok := dohAddress(choice); ok {
		if strings.Contains(host, ":") {
			host = "[" + host + "]"
		}
		return "https://" + host + ":" + strconv.Itoa(port) + path
	}
	return ""
}

func DirectDNSChoice(choice string) string {
	choice = strings.TrimSpace(choice)
	if _, ok := directPresets[choice]; ok {
		return choice
	}
	if address, ok := plainAddress(choice); ok {
		return address
	}
	return ""
}

// dohAddress reads https://host[:port][/path]; the host is a plain address (IPv6 in brackets)
// or a domain name of two labels or more.
func dohAddress(text string) (string, int, string, bool) {
	rest, ok := strings.CutPrefix(strings.TrimSpace(text), "https://")
	if !ok || len(rest) > 200 {
		return "", 0, "", false
	}
	authority, path := rest, "/dns-query"
	if slash := strings.IndexByte(rest, '/'); slash >= 0 {
		authority, path = rest[:slash], rest[slash:]
	}
	if !dohPath.MatchString(path) {
		return "", 0, "", false
	}
	host, port := authority, 443
	if strings.HasPrefix(authority, "[") {
		end := strings.IndexByte(authority, ']')
		if end < 0 {
			return "", 0, "", false
		}
		host = authority[1:end]
		after := authority[end+1:]
		if after != "" {
			number, ok := strings.CutPrefix(after, ":")
			if !ok {
				return "", 0, "", false
			}
			p, ok := portNumber(number)
			if !ok {
				return "", 0, "", false
			}
			port = p
		}
		address, ok := plainAddress(host)
		if !ok || !strings.Contains(address, ":") {
			return "", 0, "", false
		}
		return address, port, path, true
	}
	if name, number, hasPort := strings.Cut(authority, ":"); hasPort {
		p, ok := portNumber(number)
		if !ok {
			return "", 0, "", false
		}
		host, port = name, p
	}
	host = strings.ToLower(host)
	if address, ok := plainAddress(host); ok && !strings.Contains(address, ":") {
		return address, port, path, true
	}
	if len(host) > 253 || !site.MatchString(host) {
		return "", 0, "", false
	}
	return host, port, path, true
}

// plainAddress is one IPv4 or IPv6 address written as rules write them, without a range.
func plainAddress(text string) (string, bool) {
	text = strings.ToLower(strings.TrimSpace(text))
	if strings.Contains(text, "/") {
		return "", false
	}
	written, ok := network(text)
	if !ok {
		return "", false
	}
	prefix, err := netip.ParsePrefix(written)
	if err != nil {
		return "", false
	}
	return prefix.Addr().String(), true
}

// dnsStrategy is how names are answered: IPv4 only, or IPv4 first with IPv6 allowed.
func dnsStrategy(ipv6 bool) string {
	if ipv6 {
		return "prefer_ipv4"
	}
	return "ipv4_only"
}
