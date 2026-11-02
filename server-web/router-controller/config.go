// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"unicode/utf8"
)

const version = "0.1.0"
const defaultConfig = "/etc/kone-router-controller/config.json"

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var bearerPattern = regexp.MustCompile(`^[A-Za-z0-9._~+/-]+=*$`)

type Config struct {
	Format          int    `json:"format"`
	Enabled         bool   `json:"enabled"`
	Server          string `json:"server"`
	DeviceID        string `json:"deviceId"`
	Credential      string `json:"credential"`
	CorePath        string `json:"corePath"`
	UCIPath         string `json:"uciPath"`
	PassWallPath    string `json:"passWallPath"`
	StatePath       string `json:"statePath"`
	RuntimeDir      string `json:"runtimeDir"`
	ControlResolver string `json:"controlResolver"`
	ControlMark     uint32 `json:"controlMark"`
	PortBase        int    `json:"portBase"`
	PortSlots       int    `json:"portSlots"`
}

func decodeStrict(data []byte, target any) error {
	if err := validateJSON(data); err != nil {
		return err
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if err := d.Decode(target); err != nil {
		return errors.New("invalid_json")
	}
	if err := d.Decode(new(any)); err != io.EOF {
		return errors.New("invalid_json")
	}
	return nil
}

func origin(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" || u.Opaque != "" {
		return nil, errors.New("invalid_control_origin")
	}
	if strings.ContainsAny(u.Hostname(), "\\ \r\n\t") {
		return nil, errors.New("invalid_control_origin")
	}
	if u.Port() != "" {
		port, err := strconv.Atoi(u.Port())
		if err != nil || port < 1 || port > 65535 {
			return nil, errors.New("invalid_control_origin")
		}
	}
	u.Path = ""
	return u, nil
}

func (c Config) Validate() error {
	u, err := origin(c.Server)
	if err != nil {
		return err
	}
	if c.Format != 1 || !uuidPattern.MatchString(c.DeviceID) || len(c.Credential) < 1 || len(c.Credential) > 512 || !bearerPattern.MatchString(c.Credential) {
		return errors.New("invalid_private_configuration")
	}
	for _, p := range []string{c.CorePath, c.UCIPath, c.PassWallPath, c.StatePath, c.RuntimeDir} {
		if !filepath.IsAbs(p) || filepath.Clean(p) != p {
			return errors.New("invalid_private_path")
		}
	}
	if filepath.Base(c.PassWallPath) != "passwall2" {
		return errors.New("unsupported_passwall_package")
	}
	if c.PortBase < 256 || c.PortSlots < 1 || c.PortSlots > 512 || c.PortBase+c.PortSlots > 1024 {
		return errors.New("use_privileged_loopback_ports")
	}
	if c.ControlResolver != "" && net.ParseIP(c.ControlResolver) == nil {
		return errors.New("control_resolver_must_be_literal_ip")
	}
	if c.Enabled && net.ParseIP(u.Hostname()) == nil && c.ControlResolver == "" {
		return errors.New("independent_control_resolver_required")
	}
	return nil
}

func checkPrivate(info os.FileInfo) error {
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 {
		return errors.New("private_file_must_be_regular_mode_0600")
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return errors.New("unsupported_file_owner_check")
	}
	expected := uint32(os.Geteuid())
	if runtime.GOOS == "linux" {
		expected = 0
	}
	if stat.Uid != expected {
		return errors.New("private_file_has_wrong_owner")
	}
	return nil
}

func readPrivate(path string, maximum int64) ([]byte, error) {
	before, err := os.Lstat(path)
	if err != nil {
		return nil, errors.New("private_file_unavailable")
	}
	if err = checkPrivate(before); err != nil {
		return nil, err
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, errors.New("private_file_unavailable")
	}
	defer f.Close()
	after, err := f.Stat()
	if err != nil || !os.SameFile(before, after) {
		return nil, errors.New("private_file_changed")
	}
	b, err := io.ReadAll(io.LimitReader(f, maximum+1))
	if err != nil || int64(len(b)) > maximum {
		return nil, errors.New("private_file_too_large")
	}
	return b, nil
}

func atomicPrivate(path string, data []byte) error {
	parent := filepath.Dir(path)
	if err := os.MkdirAll(parent, 0700); err != nil {
		return errors.New("private_directory_unavailable")
	}
	info, err := os.Stat(parent)
	if err != nil || !info.IsDir() || info.Mode().Perm()&0022 != 0 {
		return errors.New("private_directory_is_writable_by_others")
	}
	if info, err := os.Lstat(path); err == nil {
		if err := checkPrivate(info); err != nil {
			return err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return errors.New("private_file_unavailable")
	}
	f, err := os.CreateTemp(parent, ".kone-")
	if err != nil {
		return errors.New("private_file_write_failed")
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return errors.New("private_file_write_failed")
	}
	if err = os.Rename(tmp, path); err != nil {
		return errors.New("private_file_replace_failed")
	}
	if directory, err := os.Open(parent); err == nil {
		_ = directory.Sync()
		_ = directory.Close()
	}
	return nil
}

func loadConfig(path string) (Config, error) {
	var c Config
	b, err := readPrivate(path, 64<<10)
	if err != nil {
		return c, err
	}
	if err = decodeStrict(b, &c); err != nil {
		return c, err
	}
	return c, c.Validate()
}
func saveConfig(path string, c Config) error {
	if err := c.Validate(); err != nil {
		return err
	}
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return atomicPrivate(path, append(b, '\n'))
}
func rootLinux() error {
	if runtime.GOOS != "linux" || os.Geteuid() != 0 {
		return errors.New("linux_root_required")
	}
	return nil
}
func safeError(err error) string {
	if err == nil {
		return ""
	}
	s := err.Error()
	if regexp.MustCompile(`^[a-z0-9_]{1,80}$`).MatchString(s) {
		return s
	}
	return "operation_failed"
}
func output(value any) error {
	b, err := json.Marshal(value)
	if err != nil {
		return fmt.Errorf("status_encoding_failed")
	}
	_, err = os.Stdout.Write(append(b, '\n'))
	return err
}

func validateJSON(data []byte) error {
	if !utf8.Valid(data) {
		return errors.New("invalid_json")
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	tokens := 0
	var value func(int) error
	value = func(depth int) error {
		tokens++
		if depth > 40 || tokens > 100000 {
			return errors.New("invalid_json")
		}
		token, err := d.Token()
		if err != nil {
			return errors.New("invalid_json")
		}
		if delimiter, ok := token.(json.Delim); ok {
			switch delimiter {
			case '{':
				seen := map[string]bool{}
				for d.More() {
					key, err := d.Token()
					name, ok := key.(string)
					if err != nil || !ok || seen[name] {
						return errors.New("invalid_json")
					}
					seen[name] = true
					if err = value(depth + 1); err != nil {
						return err
					}
				}
				end, err := d.Token()
				if err != nil || end != json.Delim('}') {
					return errors.New("invalid_json")
				}
			case '[':
				for d.More() {
					if err = value(depth + 1); err != nil {
						return err
					}
				}
				end, err := d.Token()
				if err != nil || end != json.Delim(']') {
					return errors.New("invalid_json")
				}
			default:
				return errors.New("invalid_json")
			}
		}
		return nil
	}
	if err := value(0); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return errors.New("invalid_json")
	}
	return nil
}
