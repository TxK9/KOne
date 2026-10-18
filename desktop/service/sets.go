package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Category lists (Rules → Category) are sing-box binary rule sets the App downloads, checks and
// hands over the pipe. The service checks them again on its own, stores them in rules\sets\
// beside the template's lists, and the template reads them from there as local files: the core
// never fetches them, and the service never goes to the network for them.

// mostRuleSetBytes bounds one list; the largest SagerNet publishes is well under it, and its
// base64 fits in one message on the pipe.
const mostRuleSetBytes = 4 << 20

// ruleSetVersions are the binary format versions this sing-box reads ("SRS" and one byte).
const newestRuleSetVersion = 5

var errRuleSet = errors.New("rule_set_invalid")

// CheckRuleSet is the cheap part of the checks: the name, the size, the digest the App sent,
// and the header of a sing-box binary rule set.
func CheckRuleSet(name string, data []byte, digest string) error {
	if !setName.MatchString(name) || builtInSets[name] {
		return errRuleSet
	}
	if len(data) < 5 || len(data) > mostRuleSetBytes {
		return errRuleSet
	}
	sum := sha256.Sum256(data)
	if !strings.EqualFold(hex.EncodeToString(sum[:]), digest) {
		return errRuleSet
	}
	if !bytes.Equal(data[:3], []byte("SRS")) || data[3] < 1 || data[3] > newestRuleSetVersion {
		return errRuleSet
	}
	return nil
}

// ruleSetStore keeps the lists in one folder, one write at a time.
type ruleSetStore struct {
	folder string
	// parse reads a list the way the core will (sing-box check on a configuration using it).
	parse func(path string) error
	mutex sync.Mutex
}

// Put checks [data] as [name] and keeps it. The new list is written beside the old one, read by
// sing-box, and only then put in its place: a list that fails leaves the one before it as it was.
func (s *ruleSetStore) Put(name string, data []byte, digest string) error {
	if err := CheckRuleSet(name, data, digest); err != nil {
		return err
	}
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if err := os.MkdirAll(s.folder, 0o755); err != nil {
		return err
	}
	target := filepath.Join(s.folder, name+".srs")
	fresh := target + ".new"
	if err := os.WriteFile(fresh, data, 0o644); err != nil {
		return err
	}
	if err := s.parse(fresh); err != nil {
		os.Remove(fresh)
		return errRuleSet
	}
	if err := os.Rename(fresh, target); err != nil {
		os.Remove(fresh)
		return err
	}
	return nil
}

// Kept is each list in the folder with its SHA-256, so the App sends only what changed.
func (s *ruleSetStore) Kept() map[string]string {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	kept := map[string]string{}
	entries, _ := os.ReadDir(s.folder)
	for _, entry := range entries {
		name, isList := strings.CutSuffix(entry.Name(), ".srs")
		if !isList || !entry.Type().IsRegular() || !setName.MatchString(name) {
			continue
		}
		data, err := os.ReadFile(filepath.Join(s.folder, entry.Name()))
		if err != nil || len(data) > mostRuleSetBytes {
			continue
		}
		sum := sha256.Sum256(data)
		kept[name] = hex.EncodeToString(sum[:])
	}
	return kept
}

// Usable are [rules] without those naming a list the folder does not have: a list not yet
// handed over leaves its rules out rather than stopping the connection.
func (s *ruleSetStore) Usable(rules []Rule) []Rule {
	kept := s.Kept()
	var usable []Rule
	for _, rule := range rules {
		if rule.Kind == "set" {
			if _, ok := kept[strings.ToLower(strings.TrimSpace(rule.Value))]; !ok {
				continue
			}
		}
		usable = append(usable, rule)
	}
	return usable
}

// singBoxParses asks the sing-box at [binary] to read the list at [path] as the template uses it.
func singBoxParses(binary string) func(path string) error {
	return func(path string) error {
		config, err := json.Marshal(map[string]any{
			"route": map[string]any{"rule_set": []any{map[string]any{"type": "local", "tag": "check", "format": "binary", "path": filepath.ToSlash(path)}}},
		})
		if err != nil {
			return err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		command := exec.CommandContext(ctx, binary, "check", "-c", "stdin", "--disable-color")
		command.Stdin = bytes.NewReader(config)
		hideWindow(command)
		return command.Run()
	}
}
