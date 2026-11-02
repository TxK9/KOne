// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"
)

type Stub struct {
	NodeID   string `json:"nodeId"`
	Section  string `json:"section"`
	Port     int    `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
	Name     string `json:"name"`
}
type Registry struct {
	Format   int             `json:"format"`
	DeviceID string          `json:"deviceId"`
	Entries  map[string]Stub `json:"entries"`
}
type Plan struct {
	Config         []byte
	Stubs          []Stub
	ConfigRevision string
	Revision       string
	Generation     int64
	Roots          []string
}

func newRegistry(device string) Registry {
	return Registry{Format: 1, DeviceID: device, Entries: make(map[string]Stub)}
}
func loadRegistry(c Config) (Registry, error) {
	r := newRegistry(c.DeviceID)
	b, err := readPrivate(c.StatePath, 1<<20)
	if err != nil {
		if _, e := os.Lstat(c.StatePath); os.IsNotExist(e) {
			return r, nil
		}
		return r, err
	}
	if decodeStrict(b, &r) != nil || r.Format != 1 || r.DeviceID != c.DeviceID || r.Entries == nil || len(r.Entries) > c.PortSlots {
		return r, errors.New("invalid_stub_registry")
	}
	used := map[int]bool{}
	for id, stub := range r.Entries {
		if id != stub.NodeID || !uuidPattern.MatchString(id) || stub.Section != sectionID(c.DeviceID, id) || stub.Port < c.PortBase || stub.Port >= c.PortBase+c.PortSlots || used[stub.Port] || stub.Username != "kone" || !regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`).MatchString(stub.Password) {
			return r, errors.New("invalid_stub_registry")
		}
		used[stub.Port] = true
	}
	return r, nil
}
func (r *Registry) save(c Config) error {
	b, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}
	return atomicPrivate(c.StatePath, append(b, '\n'))
}
func sectionID(device, id string) string {
	return "kone_" + strings.ReplaceAll(device, "-", "")[:8] + "_" + strings.ReplaceAll(id, "-", "")
}
func (r *Registry) stub(c Config, node NodeMetadata) (Stub, error) {
	if old, ok := r.Entries[node.ID]; ok {
		old.Name = node.Name
		r.Entries[node.ID] = old
		return old, nil
	}
	used := map[int]bool{}
	for _, stub := range r.Entries {
		used[stub.Port] = true
	}
	port := c.PortBase
	for used[port] && port < c.PortBase+c.PortSlots {
		port++
	}
	if port >= c.PortBase+c.PortSlots {
		return Stub{}, errors.New("stub_port_registry_full")
	}
	password, err := randomWord(32)
	if err != nil {
		return Stub{}, err
	}
	stub := Stub{NodeID: node.ID, Section: sectionID(c.DeviceID, node.ID), Port: port, Username: "kone", Password: password, Name: node.Name}
	r.Entries[node.ID] = stub
	return stub, nil
}

func jsonObject(raw json.RawMessage) (map[string]any, error) {
	if !utf8.Valid(raw) || validateJSON(raw) != nil {
		return nil, errors.New("invalid_node_json")
	}
	var value map[string]any
	d := json.NewDecoder(bytes.NewReader(raw))
	d.UseNumber()
	if d.Decode(&value) != nil || value == nil {
		return nil, errors.New("invalid_node_json")
	}
	return value, nil
}
func permitted(m map[string]any, keys ...string) error {
	set := map[string]bool{}
	for _, k := range keys {
		set[k] = true
	}
	for key := range m {
		if !set[key] {
			return errors.New("unsupported_node_options")
		}
	}
	return nil
}
func stringValue(value any, maximum int) (string, bool) {
	s, ok := value.(string)
	if !ok || s == "" || len(s) > maximum || strings.IndexFunc(s, func(r rune) bool { return r < 32 || r == 127 }) >= 0 {
		return "", false
	}
	return s, true
}
func numberValue(value any, low, high int) (int, bool) {
	s, ok := value.(json.Number)
	if !ok {
		return 0, false
	}
	n, err := s.Int64()
	return int(n), err == nil && n >= int64(low) && n <= int64(high)
}
func mapValue(value any) (map[string]any, bool) { m, ok := value.(map[string]any); return m, ok }
func stringList(value any, maximum int) ([]string, bool) {
	a, ok := value.([]any)
	if !ok || len(a) > maximum {
		return nil, false
	}
	out := make([]string, 0, len(a))
	for _, v := range a {
		s, ok := stringValue(v, 256)
		if !ok {
			return nil, false
		}
		out = append(out, s)
	}
	return out, true
}
func validHost(value any) bool {
	s, ok := stringValue(value, 253)
	return ok && (net.ParseIP(s) != nil || regexp.MustCompile(`^[A-Za-z0-9_.-]+$`).MatchString(s))
}

func validateTLS(value any, kind string) error {
	tls, ok := mapValue(value)
	if !ok {
		return errors.New("invalid_tls_options")
	}
	if permitted(tls, "enabled", "server_name", "insecure", "alpn", "utls", "reality", "certificate_public_key_sha256") != nil || tls["enabled"] != true {
		return errors.New("unsupported_tls_options")
	}
	if v, exists := tls["server_name"]; exists && !validHost(v) {
		return errors.New("invalid_tls_options")
	}
	if v, exists := tls["insecure"]; exists {
		if _, ok := v.(bool); !ok {
			return errors.New("invalid_tls_options")
		}
	}
	if v, exists := tls["alpn"]; exists {
		if _, ok := stringList(v, 16); !ok {
			return errors.New("invalid_tls_options")
		}
	}
	if v, exists := tls["utls"]; exists {
		u, ok := mapValue(v)
		if !ok || permitted(u, "enabled", "fingerprint") != nil || u["enabled"] != true {
			return errors.New("unsupported_tls_options")
		}
		fp, _ := u["fingerprint"].(string)
		if !regexp.MustCompile(`^(chrome|firefox|edge|safari|360|qq|ios|android|random|randomized)$`).MatchString(fp) {
			return errors.New("unsupported_tls_options")
		}
	}
	if v, exists := tls["reality"]; exists {
		r, ok := mapValue(v)
		if kind != "vless" || !ok || permitted(r, "enabled", "public_key", "short_id") != nil || r["enabled"] != true {
			return errors.New("unsupported_reality_options")
		}
		key, ok := r["public_key"].(string)
		decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(key, "="))
		if !ok || err != nil || len(decoded) != 32 {
			return errors.New("invalid_reality_key")
		}
		sid, ok := r["short_id"].(string)
		if !ok || len(sid) > 16 || len(sid)%2 != 0 {
			return errors.New("invalid_reality_short_id")
		}
		if _, err = hex.DecodeString(sid); err != nil {
			return errors.New("invalid_reality_short_id")
		}
	}
	if v, exists := tls["certificate_public_key_sha256"]; exists {
		pins, ok := stringList(v, 16)
		if !ok || len(pins) == 0 {
			return errors.New("invalid_certificate_pins")
		}
		for _, pin := range pins {
			b, err := base64.StdEncoding.DecodeString(pin)
			if err != nil || len(b) != 32 {
				return errors.New("invalid_certificate_pins")
			}
		}
	}
	return nil
}
func validateOutbound(node map[string]any, protocol string) error {
	common := []string{"type", "tag", "server", "server_port", "detour", "tcp_fast_open"}
	switch protocol {
	case "vless":
		common = append(common, "uuid", "flow", "tls", "packet_encoding")
	case "socks":
		common = append(common, "version", "username", "password", "network")
	case "hysteria2":
		common = append(common, "password", "tls", "obfs")
	default:
		return errors.New("unsupported_node_protocol")
	}
	if permitted(node, common...) != nil {
		return errors.New("unsupported_node_options")
	}
	if node["type"] != protocol || !validHost(node["server"]) {
		return errors.New("invalid_node_options")
	}
	if _, ok := numberValue(node["server_port"], 1, 65535); !ok {
		return errors.New("invalid_node_port")
	}
	if v, exists := node["tcp_fast_open"]; exists {
		if _, ok := v.(bool); !ok {
			return errors.New("invalid_node_options")
		}
	}
	if protocol == "vless" {
		id, ok := node["uuid"].(string)
		if !ok || !uuidPattern.MatchString(strings.ToLower(id)) {
			return errors.New("invalid_node_uuid")
		}
		if flow, exists := node["flow"]; exists && flow != "" && flow != "xtls-rprx-vision" {
			return errors.New("unsupported_node_flow")
		}
		if p, exists := node["packet_encoding"]; exists && p != "xudp" && p != "packetaddr" {
			return errors.New("unsupported_packet_encoding")
		}
	}
	if protocol == "socks" {
		if v, exists := node["version"]; exists && v != "5" {
			return errors.New("unsupported_socks_version")
		}
		for _, key := range []string{"username", "password"} {
			if v, exists := node[key]; exists {
				if _, ok := stringValue(v, 1024); !ok {
					return errors.New("invalid_socks_authentication")
				}
			}
		}
		if v, exists := node["network"]; exists && v != "tcp" && v != "udp" {
			return errors.New("unsupported_socks_network")
		}
	}
	if protocol == "hysteria2" {
		if _, ok := stringValue(node["password"], 1024); !ok {
			return errors.New("invalid_node_authentication")
		}
		if value, exists := node["obfs"]; exists {
			o, ok := mapValue(value)
			if !ok || permitted(o, "type", "password") != nil || o["type"] != "salamander" {
				return errors.New("unsupported_hysteria_obfuscation")
			}
			if _, ok := stringValue(o["password"], 1024); !ok {
				return errors.New("invalid_hysteria_obfuscation")
			}
		}
	}
	if value, exists := node["tls"]; exists {
		if err := validateTLS(value, protocol); err != nil {
			return err
		}
	} else if protocol == "hysteria2" || node["flow"] == "xtls-rprx-vision" {
		return errors.New("tls_required")
	}
	return nil
}

func canonicalJSON(value any) ([]byte, error) {
	var encoded bytes.Buffer
	encoder := json.NewEncoder(&encoded)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return nil, err
	}
	raw := bytes.TrimSuffix(encoded.Bytes(), []byte{'\n'})
	var output bytes.Buffer
	for index := 0; index < len(raw); {
		if raw[index] == '\\' && index+1 < len(raw) {
			if raw[index+1] == '\\' {
				output.Write(raw[index : index+2])
				index += 2
				continue
			}
			if bytes.HasPrefix(raw[index:], []byte(`\u2028`)) {
				output.WriteString("\u2028")
				index += 6
				continue
			}
			if bytes.HasPrefix(raw[index:], []byte(`\u2029`)) {
				output.WriteString("\u2029")
				index += 6
				continue
			}
		}
		output.WriteByte(raw[index])
		index++
	}
	return output.Bytes(), nil
}

func validatePolicyRules(route map[string]any) ([]any, error) {
	if permitted(route, "final", "rules") != nil {
		return nil, errors.New("unsupported_managed_route")
	}
	if route["rules"] == nil {
		return nil, nil
	}
	rules, ok := route["rules"].([]any)
	if !ok || len(rules) > 16 {
		return nil, errors.New("unsupported_managed_route")
	}
	for _, value := range rules {
		rule, ok := mapValue(value)
		if !ok || permitted(rule, "action", "domain", "domain_suffix") != nil || rule["action"] != "reject" {
			return nil, errors.New("unsupported_managed_route")
		}
		domains := 0
		for _, key := range []string{"domain", "domain_suffix"} {
			if v, yes := rule[key]; yes {
				a, ok := stringList(v, 128)
				if !ok {
					return nil, errors.New("unsupported_managed_route")
				}
				domains += len(a)
			}
		}
		if domains == 0 {
			return nil, errors.New("unsupported_managed_route")
		}
	}
	return rules, nil
}

func makePlan(c Config, view ConfigReply, registry *Registry) (Plan, error) {
	plan := Plan{ConfigRevision: view.ConfigRevision, Revision: view.Revision}
	if view.Controller == nil || validateMetadata(*view.Controller, c.DeviceID) != nil || view.Controller.Mode != "enabled" || !view.Controller.Required {
		return plan, errors.New("explicit_controller_enrollment_required")
	}
	meta := view.Controller
	plan.Generation = meta.AuthGeneration
	plan.Roots = append([]string(nil), meta.RootNodeIDs...)
	if len(meta.Nodes) == 0 || len(meta.Nodes) > 200 || len(meta.RootNodeIDs) == 0 || len(meta.RootNodeIDs) > 200 || len(meta.NodeIDs) != len(meta.Nodes) {
		return plan, errors.New("invalid_managed_node_inventory")
	}
	config, err := jsonObject(view.Config)
	if err != nil {
		return plan, err
	}
	if permitted(config, "outbounds", "route") != nil {
		return plan, errors.New("unsupported_managed_configuration")
	}
	canonical, err := canonicalJSON(config)
	if err != nil {
		return plan, errors.New("invalid_managed_configuration")
	}
	hash := sha256.Sum256(canonical)
	if hex.EncodeToString(hash[:]) != view.ConfigRevision {
		return plan, errors.New("configuration_revision_mismatch")
	}
	byID := map[string]NodeMetadata{}
	byTag := map[string]NodeMetadata{}
	for _, node := range meta.Nodes {
		if _, valid := stringValue(node.Name, 320); !valid || utf8.RuneCountInString(node.Name) > 80 {
			return plan, errors.New("invalid_managed_node_name")
		}
		if !uuidPattern.MatchString(node.ID) || node.Revision < 1 || node.Tag == "" || utf8.RuneCountInString(node.Tag) > 256 || len(node.DependencyIDs) > 1 || byID[node.ID].ID != "" || byTag[node.Tag].ID != "" {
			return plan, errors.New("invalid_managed_node_inventory")
		}
		byID[node.ID] = node
		byTag[node.Tag] = node
	}
	seenIDs := map[string]bool{}
	for _, id := range meta.NodeIDs {
		if byID[id].ID == "" || seenIDs[id] {
			return plan, errors.New("invalid_managed_node_inventory")
		}
		seenIDs[id] = true
	}
	roots := map[string]bool{}
	for _, id := range meta.RootNodeIDs {
		if byID[id].ID == "" || roots[id] {
			return plan, errors.New("invalid_authorized_roots")
		}
		roots[id] = true
	}
	visited := map[string]bool{}
	visiting := map[string]bool{}
	var visit func(string, int) error
	visit = func(id string, depth int) error {
		if depth > 32 || visiting[id] || byID[id].ID == "" {
			return errors.New("invalid_managed_dependency_graph")
		}
		if visited[id] {
			return nil
		}
		visiting[id] = true
		for _, dep := range byID[id].DependencyIDs {
			if err := visit(dep, depth+1); err != nil {
				return err
			}
		}
		delete(visiting, id)
		visited[id] = true
		return nil
	}
	for id := range roots {
		if err := visit(id, 0); err != nil {
			return plan, err
		}
	}
	if len(visited) != len(byID) {
		return plan, errors.New("ungranted_dependency_data")
	}
	rawNodes, ok := config["outbounds"].([]any)
	if !ok || len(rawNodes) > 201 {
		return plan, errors.New("invalid_managed_configuration")
	}
	outbounds := []any{}
	found := map[string]bool{}
	for _, raw := range rawNodes {
		node, ok := mapValue(raw)
		if !ok {
			return plan, errors.New("invalid_managed_configuration")
		}
		tag, _ := node["tag"].(string)
		if node["type"] == "selector" {
			if permitted(node, "type", "tag", "outbounds", "default") != nil {
				return plan, errors.New("unsupported_managed_selector")
			}
			choices, ok := stringList(node["outbounds"], 200)
			if !ok || len(choices) != len(roots) {
				return plan, errors.New("unsupported_managed_selector")
			}
			selected := map[string]bool{}
			for _, choice := range choices {
				id := byTag[choice].ID
				if !roots[id] || selected[id] {
					return plan, errors.New("unsupported_managed_selector")
				}
				selected[id] = true
			}
			if byTag[tag].ID != "" || !selected[byTag[fmt.Sprint(node["default"])].ID] {
				return plan, errors.New("unsupported_managed_selector")
			}
			continue
		}
		descriptor, ok := byTag[tag]
		if !ok || found[descriptor.ID] {
			return plan, errors.New("invalid_managed_node_inventory")
		}
		if err := validateOutbound(node, descriptor.Protocol); err != nil {
			return plan, err
		}
		if server, _ := node["server"].(string); net.ParseIP(server) == nil && c.ControlResolver == "" {
			return plan, errors.New("independent_control_resolver_required")
		}
		deps := descriptor.DependencyIDs
		detour, _ := node["detour"].(string)
		if len(deps) == 0 && detour != "" || len(deps) == 1 && detour != byID[deps[0]].Tag {
			return plan, errors.New("managed_dependency_mismatch")
		}
		if c.ControlMark != 0 {
			node["routing_mark"] = c.ControlMark
		}
		outbounds = append(outbounds, node)
		found[descriptor.ID] = true
	}
	if len(found) != len(byID) {
		return plan, errors.New("missing_managed_node")
	}
	route, ok := mapValue(config["route"])
	if !ok {
		return plan, errors.New("invalid_managed_route")
	}
	rules, err := validatePolicyRules(route)
	if err != nil {
		return plan, err
	}
	inbounds := []any{}
	for _, id := range meta.RootNodeIDs {
		stub, err := registry.stub(c, byID[id])
		if err != nil {
			return plan, err
		}
		plan.Stubs = append(plan.Stubs, stub)
		inbound := "root-" + id
		inbounds = append(inbounds, map[string]any{"type": "socks", "tag": inbound, "listen": "127.0.0.1", "listen_port": stub.Port, "users": []any{map[string]any{"username": stub.Username, "password": stub.Password}}})
		rules = append(rules, map[string]any{"inbound": []string{inbound}, "action": "route", "outbound": byID[id].Tag})
	}
	rules = append(rules, map[string]any{"action": "reject"})
	runtimeRoute := map[string]any{"rules": rules}
	runtimeConfig := map[string]any{"log": map[string]any{"disabled": true}, "inbounds": inbounds, "outbounds": outbounds, "route": runtimeRoute}
	if c.ControlResolver != "" {
		dns := map[string]any{"type": "udp", "tag": "controller-bootstrap", "server": c.ControlResolver, "server_port": 53}
		if c.ControlMark != 0 {
			dns["routing_mark"] = c.ControlMark
		}
		runtimeConfig["dns"] = map[string]any{"servers": []any{dns}, "final": "controller-bootstrap"}
		runtimeRoute["default_domain_resolver"] = "controller-bootstrap"
	}
	plan.Config, err = json.Marshal(runtimeConfig)
	if err != nil {
		return plan, errors.New("runtime_encoding_failed")
	}
	sort.Slice(plan.Stubs, func(i, j int) bool { return plan.Stubs[i].NodeID < plan.Stubs[j].NodeID })
	return plan, nil
}
