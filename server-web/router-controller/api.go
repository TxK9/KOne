// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

type NodeMetadata struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Protocol      string   `json:"protocol"`
	Revision      int64    `json:"revision"`
	Tag           string   `json:"tag"`
	DependencyIDs []string `json:"dependencyIds"`
}
type ControllerMetadata struct {
	Format            int            `json:"format"`
	DeviceID          string         `json:"deviceId"`
	Mode              string         `json:"mode"`
	Required          bool           `json:"required"`
	AuthGeneration    int64          `json:"authGeneration"`
	TTLSeconds        int            `json:"ttlSeconds"`
	RenewAfterSeconds int            `json:"renewAfterSeconds"`
	OwnerPublish      bool           `json:"ownerPublish"`
	RootNodeIDs       []string       `json:"rootNodeIds,omitempty"`
	NodeIDs           []string       `json:"nodeIds,omitempty"`
	Nodes             []NodeMetadata `json:"nodes,omitempty"`
}
type ConfigReply struct {
	Format         int                 `json:"format"`
	ConfigKind     string              `json:"configKind"`
	ConfigName     string              `json:"configName"`
	GroupName      *string             `json:"groupName"`
	Revision       string              `json:"revision"`
	ConfigRevision string              `json:"configRevision"`
	Config         json.RawMessage     `json:"config"`
	Policy         json.RawMessage     `json:"policy"`
	Controller     *ControllerMetadata `json:"controller"`
}
type LeaseReply struct {
	Format         int      `json:"format"`
	Nonce          string   `json:"nonce"`
	DeviceID       string   `json:"deviceId"`
	Mode           string   `json:"mode"`
	Required       bool     `json:"required"`
	ServerTime     int64    `json:"serverTime"`
	NotAfter       int64    `json:"notAfter"`
	TTLSeconds     int      `json:"ttlSeconds"`
	AuthGeneration int64    `json:"authGeneration"`
	ConfigRevision string   `json:"configRevision"`
	NodeIDs        []string `json:"nodeIds"`
	RootNodeIDs    []string `json:"rootNodeIds"`
}
type APIError struct {
	Status     int
	Code       string
	Controller *ControllerMetadata
}

func (e *APIError) Error() string { return "control_request_denied" }

type Client struct {
	config Config
	origin *url.URL
	http   *http.Client
}

func newClient(c Config) (*Client, error) {
	u, err := origin(c.Server)
	if err != nil {
		return nil, err
	}
	d := &net.Dialer{Timeout: 4 * time.Second, KeepAlive: 20 * time.Second, Control: socketControl(c.ControlMark)}
	if c.ControlResolver != "" {
		resolverAddress := net.JoinHostPort(c.ControlResolver, "53")
		d.Resolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return (&net.Dialer{Timeout: 3 * time.Second, Control: socketControl(c.ControlMark)}).DialContext(ctx, network, resolverAddress)
		}}
	}
	transport := &http.Transport{Proxy: nil, DialContext: d.DialContext, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, ServerName: u.Hostname()}, TLSHandshakeTimeout: 4 * time.Second, ResponseHeaderTimeout: 4 * time.Second, MaxConnsPerHost: 2, MaxIdleConnsPerHost: 2}
	client := &http.Client{Transport: transport, Timeout: 8 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("control_redirect_refused") }}
	return &Client{config: c, origin: u, http: client}, nil
}

func (c *Client) call(ctx context.Context, method, path string, body any, out any) error {
	if !strings.HasPrefix(path, "/api/client/") || strings.ContainsAny(path, "?#") {
		return errors.New("invalid_control_path")
	}
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return errors.New("request_encoding_failed")
		}
		reader = strings.NewReader(string(b))
	}
	request, err := http.NewRequestWithContext(ctx, method, c.origin.String()+path, reader)
	if err != nil {
		return errors.New("control_request_failed")
	}
	request.Header.Set("Authorization", "Bearer "+c.config.Credential)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cache-Control", "no-store")
	request.Header.Set("X-KOne-Controller-Protocol", "1")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := c.http.Do(request)
	if err != nil {
		return errors.New("control_transport_failed")
	}
	defer response.Body.Close()
	b, err := io.ReadAll(io.LimitReader(response.Body, (1<<20)+1))
	if err != nil || len(b) > 1<<20 {
		return errors.New("control_response_too_large")
	}
	if response.StatusCode != 200 {
		var failure struct {
			Error      string              `json:"error"`
			Code       string              `json:"errorCode"`
			Controller *ControllerMetadata `json:"controller"`
		}
		if decodeStrict(b, &failure) != nil {
			return &APIError{Status: response.StatusCode, Code: "unknown_response"}
		}
		code := failure.Code
		if !regexp.MustCompile(`^[a-z_]{1,48}$`).MatchString(code) {
			code = "unknown_response"
		}
		return &APIError{Status: response.StatusCode, Code: code, Controller: failure.Controller}
	}
	if !strings.Contains(strings.ToLower(response.Header.Get("Cache-Control")), "no-store") {
		return errors.New("control_response_not_private")
	}
	if err = decodeStrict(b, out); err != nil {
		return errors.New("invalid_control_response")
	}
	return nil
}

func (c *Client) Register(ctx context.Context, ready bool) (ControllerMetadata, error) {
	body := map[string]any{"protocol": 1, "platform": "router", "clientVersion": version, "startupLease": ready, "runtimeLease": ready, "protocols": []string{"vless", "socks", "hysteria2"}, "transports": []string{"tcp", "quic"}}
	var reply struct {
		OK         bool               `json:"ok"`
		Controller ControllerMetadata `json:"controller"`
	}
	err := c.call(ctx, http.MethodPost, "/api/client/controller/register", body, &reply)
	if err == nil && (!reply.OK || validateMetadata(reply.Controller, c.config.DeviceID) != nil) {
		err = errors.New("invalid_registration")
	}
	return reply.Controller, err
}
func validateMetadata(m ControllerMetadata, deviceID string) error {
	if m.Format != 1 || m.DeviceID != deviceID || m.AuthGeneration < 0 || m.TTLSeconds < 1 || m.TTLSeconds > 60 || m.RenewAfterSeconds < 1 || m.RenewAfterSeconds > 20 {
		return errors.New("invalid_controller_metadata")
	}
	if (m.Mode == "legacy" && m.Required) || (m.Mode != "legacy" && m.Mode != "enabled" && m.Mode != "revoked") || (m.Mode != "legacy" && !m.Required) {
		return errors.New("invalid_controller_mode")
	}
	return nil
}
func (c *Client) Configuration(ctx context.Context) (ConfigReply, error) {
	var reply ConfigReply
	err := c.call(ctx, http.MethodGet, "/api/client/config", nil, &reply)
	if err != nil {
		return reply, err
	}
	if reply.Format != 1 || reply.ConfigKind != "sing-box-source" || !hexDigest(reply.Revision) || !hexDigest(reply.ConfigRevision) || len(reply.Config) == 0 || len(reply.Policy) == 0 || reply.Controller == nil {
		return reply, errors.New("invalid_configuration_envelope")
	}
	if err = validateMetadata(*reply.Controller, c.config.DeviceID); err != nil {
		return reply, err
	}
	return reply, nil
}
func hexDigest(value string) bool {
	if len(value) != 64 {
		return false
	}
	b, err := hex.DecodeString(value)
	return err == nil && len(b) == 32 && value == strings.ToLower(value)
}
func randomWord(bytes int) (string, error) {
	b := make([]byte, bytes)
	if _, err := rand.Read(b); err != nil {
		return "", errors.New("secure_random_unavailable")
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func (c *Client) Lease(ctx context.Context, view ConfigReply) (LeaseReply, error) {
	var lease LeaseReply
	if view.Controller == nil {
		return lease, errors.New("missing_authority_metadata")
	}
	nonce, err := randomWord(32)
	if err != nil {
		return lease, err
	}
	body := map[string]any{"protocol": 1, "nonce": nonce, "configRevision": view.ConfigRevision, "authGeneration": view.Controller.AuthGeneration}
	if err = c.call(ctx, http.MethodPost, "/api/client/controller/lease", body, &lease); err != nil {
		return lease, err
	}
	if lease.Format != 1 || lease.Nonce != nonce || lease.DeviceID != c.config.DeviceID || lease.Mode != view.Controller.Mode || lease.Required != view.Controller.Required || lease.AuthGeneration != view.Controller.AuthGeneration || lease.ConfigRevision != view.ConfigRevision || lease.TTLSeconds < 1 || lease.TTLSeconds > 60 || lease.NotAfter-lease.ServerTime != int64(lease.TTLSeconds) || lease.ServerTime <= 0 {
		return lease, errors.New("invalid_online_lease")
	}
	if !equalStrings(lease.NodeIDs, view.Controller.NodeIDs) || !equalStrings(lease.RootNodeIDs, view.Controller.RootNodeIDs) {
		return lease, errors.New("lease_node_set_mismatch")
	}
	return lease, nil
}
func (c *Client) Ack(ctx context.Context, view ConfigReply, status string) error {
	if view.Controller == nil {
		return errors.New("missing_authority_metadata")
	}
	body := map[string]any{"protocol": 1, "authGeneration": view.Controller.AuthGeneration, "revision": view.Revision, "configRevision": view.ConfigRevision, "status": status}
	var reply struct {
		OK bool `json:"ok"`
	}
	if err := c.call(ctx, http.MethodPost, "/api/client/controller/ack", body, &reply); err != nil {
		return err
	}
	if !reply.OK {
		return errors.New("invalid_acknowledgment")
	}
	return nil
}
func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// Preflight never grants traffic. It observes the control path while no managed core exists.
func (c *Client) Preflight(ctx context.Context) error {
	view, err := c.Configuration(ctx)
	if err == nil {
		_, err = c.Lease(ctx, view)
		if err == nil {
			return nil
		}
	}
	var denied *APIError
	if errors.As(err, &denied) && denied.Controller != nil && validateMetadata(*denied.Controller, c.config.DeviceID) == nil && (denied.Code == "no_grants" || denied.Code == "authorization_revoked" || denied.Code == "stale_generation") {
		return nil
	}
	// A legacy App-only account has no configuration yet. Register false before probing the guard.
	if errors.As(err, &denied) && denied.Status == 409 && denied.Controller == nil {
		meta, regErr := c.Register(ctx, false)
		if regErr != nil {
			return regErr
		}
		probe := ConfigReply{ConfigRevision: strings.Repeat("0", 64), Controller: &meta}
		_, probeErr := c.Lease(ctx, probe)
		if errors.As(probeErr, &denied) && denied.Controller != nil && validateMetadata(*denied.Controller, c.config.DeviceID) == nil && (denied.Code == "no_grants" || denied.Code == "authorization_revoked") {
			return nil
		}
	}
	return errors.New("independent_control_preflight_failed")
}
func (c *Client) AckStopped(ctx context.Context, meta ControllerMetadata) error {
	if validateMetadata(meta, c.config.DeviceID) != nil {
		return errors.New("missing_authority_metadata")
	}
	var reply struct {
		OK bool `json:"ok"`
	}
	err := c.call(ctx, http.MethodPost, "/api/client/controller/ack", map[string]any{"protocol": 1, "authGeneration": meta.AuthGeneration, "status": "stopped"}, &reply)
	if err != nil {
		return err
	}
	if !reply.OK {
		return errors.New("invalid_acknowledgment")
	}
	return nil
}

func requiredJSON(data []byte, keys ...string) error {
	var object map[string]json.RawMessage
	if json.Unmarshal(data, &object) != nil || object == nil {
		return errors.New("missing_control_fields")
	}
	for _, key := range keys {
		value, exists := object[key]
		if !exists || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return errors.New("missing_control_fields")
		}
	}
	return nil
}
func (m *ControllerMetadata) UnmarshalJSON(data []byte) error {
	if err := requiredJSON(data, "format", "deviceId", "mode", "required", "authGeneration", "ttlSeconds", "renewAfterSeconds", "ownerPublish"); err != nil {
		return err
	}
	type plain ControllerMetadata
	var value plain
	if err := decodeStrict(data, &value); err != nil {
		return err
	}
	*m = ControllerMetadata(value)
	return nil
}
func (n *NodeMetadata) UnmarshalJSON(data []byte) error {
	if err := requiredJSON(data, "id", "name", "protocol", "revision", "tag", "dependencyIds"); err != nil {
		return err
	}
	type plain NodeMetadata
	var value plain
	if err := decodeStrict(data, &value); err != nil {
		return err
	}
	*n = NodeMetadata(value)
	return nil
}
func (l *LeaseReply) UnmarshalJSON(data []byte) error {
	if err := requiredJSON(data, "format", "nonce", "deviceId", "mode", "required", "serverTime", "notAfter", "ttlSeconds", "authGeneration", "configRevision", "nodeIds", "rootNodeIds"); err != nil {
		return err
	}
	type plain LeaseReply
	var value plain
	if err := decodeStrict(data, &value); err != nil {
		return err
	}
	*l = LeaseReply(value)
	return nil
}
func (c *ConfigReply) UnmarshalJSON(data []byte) error {
	if err := requiredJSON(data, "format", "configKind", "configName", "revision", "configRevision", "config", "policy", "controller"); err != nil {
		return err
	}
	var fields map[string]json.RawMessage
	_ = json.Unmarshal(data, &fields)
	if _, exists := fields["groupName"]; !exists {
		return errors.New("missing_control_fields")
	}
	if err := requiredJSON(fields["controller"], "rootNodeIds", "nodeIds", "nodes"); err != nil {
		return err
	}
	type plain ConfigReply
	var value plain
	if err := decodeStrict(data, &value); err != nil {
		return err
	}
	*c = ConfigReply(value)
	return nil
}
