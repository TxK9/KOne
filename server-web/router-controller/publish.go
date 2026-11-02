// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"unicode/utf8"
)

type Publication struct {
	NativeUCI    string
	SelectedTags []string
	NodeCount    int
}
type PublishNode struct {
	ID             string   `json:"id"`
	Name           string   `json:"name"`
	Protocol       string   `json:"protocol"`
	Revision       int64    `json:"revision"`
	Deleted        bool     `json:"deleted"`
	DependencyIDs  []string `json:"dependencyIds"`
	GrantCount     int      `json:"grantCount"`
	OwnerUserID    *string  `json:"ownerUserId"`
	OriginDeviceID *string  `json:"originDeviceId"`
	Updated        float64  `json:"updated"`
}
type PublishReply struct {
	OK                   bool          `json:"ok"`
	Imported             int           `json:"imported"`
	Nodes                []PublishNode `json:"nodes"`
	Warnings             []string      `json:"warnings"`
	AutomaticallyGranted bool          `json:"automaticallyGranted"`
	LibraryScope         string        `json:"libraryScope"`
}

func (p *PublishReply) UnmarshalJSON(data []byte) error {
	if err := requiredJSON(data, "ok", "imported", "nodes", "warnings", "automaticallyGranted", "libraryScope"); err != nil {
		return err
	}
	type plain PublishReply
	var value plain
	if err := decodeStrict(data, &value); err != nil {
		return err
	}
	*p = PublishReply(value)
	return nil
}

type OwnerPublisher interface {
	PublishingStatus(context.Context) (ControllerMetadata, error)
	PublishNative(context.Context, Publication) (PublishReply, error)
}

func (c *Client) PublishingStatus(ctx context.Context) (ControllerMetadata, error) {
	var reply struct {
		OK         bool               `json:"ok"`
		Controller ControllerMetadata `json:"controller"`
	}
	err := c.call(ctx, "GET", "/api/client/controller/status", nil, &reply)
	if err == nil && (!reply.OK || validateMetadata(reply.Controller, c.config.DeviceID) != nil) {
		err = errors.New("invalid_owner_authorization")
	}
	return reply.Controller, err
}
func (c *Client) PublishNative(ctx context.Context, publication Publication) (PublishReply, error) {
	var reply PublishReply
	requestID, err := randomUUID()
	if err != nil {
		return reply, err
	}
	body := map[string]any{"protocol": 1, "requestId": requestID, "configText": publication.NativeUCI, "selectedTags": publication.SelectedTags, "libraryScope": "server"}
	err = c.call(ctx, "POST", "/api/client/controller/nodes/publish", body, &reply)
	if err == nil && (!reply.OK || reply.LibraryScope != "server" || reply.AutomaticallyGranted || reply.Imported < 0 || reply.Imported > publication.NodeCount || len(reply.Nodes) != publication.NodeCount) {
		err = errors.New("server_publication_scope_unconfirmed")
	}
	for _, node := range reply.Nodes {
		if !uuidPattern.MatchString(node.ID) || node.Deleted || node.Revision < 1 || node.OwnerUserID != nil {
			err = errors.New("invalid_publication_result")
		}
	}
	return reply, err
}
func randomUUID() (string, error) {
	data := make([]byte, 16)
	if _, err := rand.Read(data); err != nil {
		return "", errors.New("secure_random_unavailable")
	}
	data[6] = data[6]&0x0f | 0x40
	data[8] = data[8]&0x3f | 0x80
	encoded := hex.EncodeToString(data)
	return encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:], nil
}

var originalID = regexp.MustCompile(`^[A-Za-z0-9_]{1,64}$`)
var reservedStubID = regexp.MustCompile(`^kone_[0-9a-f]{8}_[0-9a-f]{32}$`)

func selectedOriginalIDs(raw string) ([]string, error) {
	if strings.TrimSpace(raw) == "" {
		return nil, errors.New("explicit_original_node_selection_required")
	}
	seen := map[string]bool{}
	var result []string
	for _, id := range strings.Split(raw, ",") {
		id = strings.TrimSpace(id)
		if !originalID.MatchString(id) {
			return nil, errors.New("invalid_original_node_identifier")
		}
		if !seen[id] {
			result = append(result, id)
			seen[id] = true
		}
	}
	if len(result) == 0 || len(result) > 200 {
		return nil, errors.New("original_node_selection_too_large")
	}
	return result, nil
}
func originalOptions(section uciSection) (map[string]string, error) {
	if section.Kind != "nodes" || section.Name == "" {
		return nil, errors.New("select_original_proxy_nodes_only")
	}
	if reservedStubID.MatchString(section.Name) || len(section.Options["kone_controller_owner"]) > 0 || len(section.Options["kone_controller_node_id"]) > 0 {
		return nil, errors.New("managed_stub_publication_forbidden")
	}
	metadata := map[string]bool{"group": true, "add_mode": true}
	options := map[string]string{}
	for key, values := range section.Options {
		if metadata[key] {
			continue
		}
		if len(values) != 1 {
			return nil, errors.New("unsupported_original_node_list_option")
		}
		if _, ok := stringValue(values[0], 8192); !ok && values[0] != "" {
			return nil, errors.New("invalid_original_node_option")
		}
		options[key] = values[0]
	}
	protocol := options["protocol"]
	common := []string{"type", "protocol", "remarks", "address", "port", "transport", "tls", "tls_serverName", "tls_allowInsecure", "fingerprint", "utls", "chain_proxy", "preproxy_node", "tcp_guise", "tcpMptcp", "tcp_fast_open", "happy_eyeballs", "timeout"}
	fields := map[string][]string{"vless": {"uuid", "flow", "encryption", "reality", "reality_publicKey", "reality_shortId", "reality_spiderX"}, "socks": {"username", "password"}, "hysteria2": {"hysteria2_auth_password", "hysteria2_obfs_type", "hysteria2_obfs_password", "hysteria2_hop_interval"}}
	extras, ok := fields[protocol]
	if !ok {
		return nil, errors.New("unsupported_original_node_protocol")
	}
	allowed := map[string]bool{}
	for _, key := range append(common, extras...) {
		allowed[key] = true
	}
	for key := range options {
		if !allowed[key] {
			return nil, errors.New("unsupported_original_node_options")
		}
	}
	if implementation := options["type"]; implementation != "" && implementation != "Xray" && implementation != "Sing-Box" && implementation != "sing-box" {
		return nil, errors.New("unsupported_original_node_implementation")
	}
	if transport := options["transport"]; transport != "" && transport != "raw" && transport != "tcp" {
		return nil, errors.New("unsupported_original_node_transport")
	}
	if disguise := options["tcp_guise"]; disguise != "" && disguise != "none" {
		return nil, errors.New("unsupported_original_node_transport")
	}
	for _, key := range []string{"tls", "tls_allowInsecure", "utls", "tcpMptcp", "tcp_fast_open", "happy_eyeballs", "reality"} {
		if value, exists := options[key]; exists && value != "0" && value != "1" {
			return nil, errors.New("invalid_original_boolean_option")
		}
	}
	if options["tcpMptcp"] == "1" || options["happy_eyeballs"] == "1" {
		return nil, errors.New("unsupported_active_original_dial_option")
	}
	port, err := strconv.Atoi(options["port"])
	if err != nil || port < 1 || port > 65535 || !regexp.MustCompile(`^[0-9]+$`).MatchString(options["port"]) {
		return nil, errors.New("original_port_hopping_not_supported")
	}
	if !validHost(options["address"]) {
		return nil, errors.New("invalid_original_server_address")
	}
	if address := net.ParseIP(options["address"]); (address != nil && (address.IsLoopback() || address.IsUnspecified())) || strings.EqualFold(options["address"], "localhost") {
		return nil, errors.New("local_only_node_publication_forbidden")
	}
	if timeout, exists := options["timeout"]; exists {
		seconds, err := strconv.Atoi(timeout)
		if err != nil || seconds < 1 || seconds > 120 {
			return nil, errors.New("unsupported_original_dial_timeout")
		}
	}
	if interval, exists := options["hysteria2_hop_interval"]; exists {
		seconds, err := strconv.Atoi(interval)
		if err != nil || seconds < 1 || seconds > 3600 {
			return nil, errors.New("unsupported_original_hop_interval")
		}
	}
	if mode := options["chain_proxy"]; mode != "" && mode != "0" && mode != "1" {
		return nil, errors.New("unsupported_original_chain_mode")
	}
	// A dormant preproxy selector is not a dependency and is not published.
	if options["chain_proxy"] != "1" {
		delete(options, "preproxy_node")
	}
	return options, nil
}

func projectOriginal(data []byte, selected []string) (Publication, error) {
	var result Publication
	if len(selected) == 0 || len(selected) > 200 || len(data) > 2<<20 {
		return result, errors.New("explicit_original_node_selection_required")
	}
	sections, err := parseUCI(data)
	if err != nil {
		return result, err
	}
	byID := map[string]uciSection{}
	for _, section := range sections {
		if section.Name != "" {
			byID[section.Name] = section
		}
	}
	converted := map[string]map[string]string{}
	visiting := map[string]bool{}
	var order []string
	var include func(string, int) error
	include = func(id string, depth int) error {
		if !originalID.MatchString(id) || depth > 32 || visiting[id] {
			return errors.New("invalid_original_dependency_graph")
		}
		if _, exists := converted[id]; exists {
			return nil
		}
		section, exists := byID[id]
		if !exists {
			return errors.New("original_dependency_missing")
		}
		options, err := originalOptions(section)
		if err != nil {
			return err
		}
		visiting[id] = true
		if options["chain_proxy"] == "1" {
			dependency := options["preproxy_node"]
			if dependency == "" {
				return errors.New("original_dependency_missing")
			}
			if err = include(dependency, depth+1); err != nil {
				return err
			}
		}
		delete(visiting, id)
		converted[id] = options
		order = append(order, id)
		if len(order) > 200 {
			return errors.New("original_node_selection_too_large")
		}
		return nil
	}
	roots := map[string]bool{}
	for _, id := range selected {
		if roots[id] {
			return result, errors.New("duplicate_original_root")
		}
		roots[id] = true
		if err = include(id, 0); err != nil {
			return result, err
		}
	}
	tags := map[string]string{}
	used := map[string]bool{}
	for _, id := range order {
		label := strings.TrimSpace(converted[id]["remarks"])
		if label == "" {
			label = id
		}
		if !utf8.ValidString(label) || utf8.RuneCountInString(label) > 80 {
			return result, errors.New("invalid_original_node_name")
		}
		if _, ok := stringValue(label, 320); !ok {
			return result, errors.New("invalid_original_node_name")
		}
		tag := label
		if used[tag] {
			short := []rune(label)
			if len(short) > 60 {
				short = short[:60]
			}
			suffix := id
			if len(suffix) > 8 {
				suffix = suffix[:8]
			}
			tag = string(short) + " [" + suffix + "]"
			counter := 2
			for used[tag] {
				tag = string(short) + " [" + suffix + "-" + itoa(counter) + "]"
				counter++
			}
		}
		used[tag] = true
		tags[id] = tag
	}
	var output strings.Builder
	for _, id := range order {
		options := converted[id]
		options["remarks"] = tags[id]
		output.WriteString("config nodes " + uciQuote(id) + "\n")
		keys := make([]string, 0, len(options))
		for key := range options {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			output.WriteString("\toption " + key + " " + uciQuote(options[key]) + "\n")
		}
		output.WriteByte('\n')
	}
	for _, id := range selected {
		result.SelectedTags = append(result.SelectedTags, tags[id])
	}
	result.NativeUCI = output.String()
	result.NodeCount = len(order)
	if len(result.NativeUCI) > 512<<10 {
		return Publication{}, errors.New("selected_publication_too_large")
	}
	return result, nil
}

func readOriginalUCI(ctx context.Context, path string) ([]byte, error) {
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return nil, errors.New("original_uci_unavailable")
	}
	defer file.Close()
	if err = lockFile(ctx, file); err != nil {
		return nil, err
	}
	defer unlockFile(file)
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0022 != 0 {
		return nil, errors.New("unsafe_original_uci")
	}
	owner, ok := info.Sys().(*syscall.Stat_t)
	if !ok || owner.Uid != 0 {
		return nil, errors.New("unsafe_original_uci_owner")
	}
	data, err := io.ReadAll(io.LimitReader(file, (2<<20)+1))
	if err != nil || len(data) > 2<<20 {
		return nil, errors.New("original_uci_too_large")
	}
	current, err := os.Lstat(path)
	if err != nil || !os.SameFile(info, current) {
		return nil, errors.New("original_uci_changed")
	}
	return data, nil
}
func publishSelected(ctx context.Context, config Config, api OwnerPublisher, load func() ([]byte, error), selected []string) (PublishReply, error) {
	var result PublishReply
	meta, err := api.PublishingStatus(ctx)
	if err != nil {
		return result, err
	}
	if validateMetadata(meta, config.DeviceID) != nil || !meta.OwnerPublish || meta.Mode == "revoked" {
		return result, errors.New("fresh_owner_publishing_authorization_required")
	}
	data, err := load()
	if err != nil {
		return result, err
	}
	publication, err := projectOriginal(data, selected)
	if err != nil {
		return result, err
	}
	return api.PublishNative(ctx, publication)
}
