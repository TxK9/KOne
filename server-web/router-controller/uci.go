// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"
)

type uciDirective struct {
	Start, End int
	Words      []string
}
type uciSection struct {
	Start, End int
	Kind, Name string
	Options    map[string][]string
}

// Parse only UCI syntax. Unrelated sections are retained as their original bytes.
func uciDirectives(data []byte) ([]uciDirective, error) {
	var result []uciDirective
	var words []string
	var token strings.Builder
	started := false
	start := -1
	quote := byte(0)
	escaped := false
	comment := false
	flush := func() {
		if started {
			words = append(words, token.String())
			token.Reset()
			started = false
		}
	}
	finish := func(end int) {
		flush()
		if len(words) > 0 {
			result = append(result, uciDirective{Start: start, End: end, Words: words})
		}
		words = nil
		start = -1
	}
	for i, ch := range data {
		if start >= 0 && i-start > 64<<10 {
			return nil, errors.New("uci_directive_too_large")
		}
		if comment {
			if ch == '\n' {
				comment = false
				finish(i + 1)
			}
			continue
		}
		if escaped {
			escaped = false
			if ch == '\n' {
				continue
			}
			token.WriteByte(ch)
			continue
		}
		if quote != '\'' && ch == '\\' {
			if start < 0 {
				start = i
			}
			started = true
			escaped = true
			continue
		}
		if quote != 0 {
			if ch == quote {
				quote = 0
			} else {
				token.WriteByte(ch)
			}
			continue
		}
		if ch == '#' {
			flush()
			comment = true
			continue
		}
		if ch == '\'' || ch == '"' {
			if start < 0 {
				start = i
			}
			started = true
			quote = ch
			continue
		}
		if ch == '\n' || ch == ';' {
			finish(i + 1)
			continue
		}
		if ch == ' ' || ch == '\t' || ch == '\r' {
			flush()
			continue
		}
		if start < 0 {
			start = i
		}
		started = true
		token.WriteByte(ch)
	}
	if quote != 0 || escaped {
		return nil, errors.New("uci_unfinished_value")
	}
	finish(len(data))
	return result, nil
}
func parseUCI(data []byte) ([]uciSection, error) {
	directives, err := uciDirectives(data)
	if err != nil {
		return nil, err
	}
	var sections []uciSection
	names := map[string]bool{}
	for _, directive := range directives {
		w := directive.Words
		if w[0] == "config" {
			if len(w) < 2 || len(w) > 3 {
				return nil, errors.New("invalid_uci_configuration")
			}
			section := uciSection{Start: directive.Start, End: directive.End, Kind: w[1], Options: map[string][]string{}}
			if len(w) == 3 {
				section.Name = w[2]
				if names[section.Name] {
					return nil, errors.New("duplicate_uci_section")
				}
				names[section.Name] = true
			}
			sections = append(sections, section)
			continue
		}
		if w[0] == "package" && len(sections) == 0 && len(w) == 2 {
			continue
		}
		if len(sections) == 0 || len(w) != 3 || (w[0] != "option" && w[0] != "list") {
			return nil, errors.New("unsupported_uci_syntax")
		}
		last := &sections[len(sections)-1]
		last.End = directive.End
		last.Options[w[1]] = append(last.Options[w[1]], w[2])
	}
	return sections, nil
}
func oneOption(section uciSection, key string) (string, bool) {
	values := section.Options[key]
	returnValue := ""
	if len(values) == 1 {
		returnValue = values[0]
	}
	return returnValue, len(values) == 1
}
func uciQuote(value string) string               { return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'" }
func stubSection(owner string, stub Stub) []byte { return renderStub(owner, stub, true) }
func renderStub(owner string, stub Stub, active bool) []byte {
	label := "KOne " + stub.Name
	if !active {
		label = "KOne unavailable: " + stub.Name
	}
	fields := [][2]string{{"type", "Xray"}, {"protocol", "socks"}, {"remarks", label}, {"address", "127.0.0.1"}, {"port", itoa(stub.Port)}, {"username", stub.Username}, {"password", stub.Password}, {"kone_controller_owner", owner}, {"kone_controller_node_id", stub.NodeID}}
	var b strings.Builder
	b.WriteString("config nodes " + uciQuote(stub.Section) + "\n")
	for _, field := range fields {
		b.WriteString("\toption " + field[0] + " " + uciQuote(field[1]) + "\n")
	}
	return []byte(b.String())
}
func itoa(value int) string { return fmt.Sprintf("%d", value) }

func patchPassWall(original []byte, owner string, registry Registry, wanted []Stub) ([]byte, int, error) {
	if len(original) > 2<<20 {
		return nil, 0, errors.New("passwall_configuration_too_large")
	}
	sections, err := parseUCI(original)
	if err != nil {
		return nil, 0, err
	}
	known := map[string]Stub{}
	for _, stub := range registry.Entries {
		known[stub.Section] = stub
	}
	owned := map[string]uciSection{}
	for _, section := range sections {
		expected, knownSection := known[section.Name]
		declared, hasOwner := oneOption(section, "kone_controller_owner")
		if !knownSection {
			if hasOwner && declared == owner {
				return nil, 0, errors.New("owned_stub_registry_missing")
			}
			continue
		}
		node, hasNode := oneOption(section, "kone_controller_node_id")
		address, _ := oneOption(section, "address")
		protocol, _ := oneOption(section, "protocol")
		kind, _ := oneOption(section, "type")
		if section.Kind != "nodes" || !hasOwner || declared != owner || !hasNode || node != expected.NodeID || address != "127.0.0.1" || protocol != "socks" || kind != "Xray" {
			return nil, 0, errors.New("stub_ownership_conflict")
		}
		owned[section.Name] = section
	}
	ranges := make([]uciSection, 0, len(owned))
	for _, section := range owned {
		ranges = append(ranges, section)
	}
	sort.Slice(ranges, func(i, j int) bool { return ranges[i].Start < ranges[j].Start })
	desired := map[string]Stub{}
	for _, stub := range wanted {
		expected, ok := known[stub.Section]
		if !ok || expected.NodeID != stub.NodeID || expected.Port != stub.Port || expected.Username != stub.Username || expected.Password != stub.Password {
			return nil, 0, errors.New("invalid_stub_plan")
		}
		if _, duplicate := desired[stub.Section]; duplicate {
			return nil, 0, errors.New("duplicate_stub_plan")
		}
		desired[stub.Section] = stub
	}
	targets := map[string]Stub{}
	for sectionID, section := range owned {
		_ = section
		targets[sectionID] = known[sectionID]
	}
	for sectionID, stub := range desired {
		targets[sectionID] = stub
	}
	unchanged := true
	retained := 0
	for sectionID, stub := range targets {
		section, exists := owned[sectionID]
		_, active := desired[sectionID]
		if !active {
			retained++
		}
		if !exists || !bytes.Equal(original[section.Start:section.End], renderStub(owner, stub, active)) {
			unchanged = false
		}
	}
	if unchanged {
		return original, retained, nil
	}
	// Inactive managed IDs remain closed-loopback tombstones, including stale UI selections.
	var output bytes.Buffer
	offset := 0
	for _, section := range ranges {
		output.Write(original[offset:section.Start])
		offset = section.End
	}
	output.Write(original[offset:])
	ordered := make([]string, 0, len(targets))
	for sectionID := range targets {
		ordered = append(ordered, sectionID)
	}
	sort.Strings(ordered)
	for _, sectionID := range ordered {
		if output.Len() > 0 && !bytes.HasSuffix(output.Bytes(), []byte{'\n'}) {
			output.WriteByte('\n')
		}
		output.WriteByte('\n')
		_, active := desired[sectionID]
		output.Write(renderStub(owner, targets[sectionID], active))
	}

	return output.Bytes(), retained, nil
}

type StubWriter interface {
	Apply(context.Context, Registry, []Stub) (int, error)
}
type FileUCI struct {
	Config Config
	Run    func(context.Context, string, []string, []byte, int) ([]byte, error)
}

func (u FileUCI) Apply(ctx context.Context, registry Registry, wanted []Stub) (int, error) {
	run := u.Run
	if run == nil {
		run = runLimited
	}
	pending, err := run(ctx, u.Config.UCIPath, []string{"-q", "changes", "passwall2"}, nil, 1)
	if err != nil || len(bytes.TrimSpace(pending)) != 0 {
		return 0, errors.New("passwall_has_pending_changes")
	}
	file, err := os.OpenFile(u.Config.PassWallPath, os.O_RDWR|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return 0, errors.New("passwall_file_unavailable")
	}
	defer file.Close()
	if err = lockFile(ctx, file); err != nil {
		return 0, err
	}
	defer unlockFile(file)
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0022 != 0 {
		return 0, errors.New("unsafe_passwall_file")
	}
	uid, ok := info.Sys().(*syscall.Stat_t)
	if !ok || uid.Uid != 0 {
		return 0, errors.New("unsafe_passwall_owner")
	}
	current, err := os.Lstat(u.Config.PassWallPath)
	if err != nil || !os.SameFile(info, current) {
		return 0, errors.New("passwall_changed_during_update")
	}
	original, err := io.ReadAll(io.LimitReader(file, (2<<20)+1))
	if err != nil || len(original) > 2<<20 {
		return 0, errors.New("passwall_configuration_too_large")
	}
	result, retained, err := patchPassWall(original, u.Config.DeviceID, registry, wanted)
	if err != nil {
		return 0, err
	}
	if bytes.Equal(result, original) {
		return retained, nil
	}
	stage, err := os.MkdirTemp(u.Config.RuntimeDir, "uci-")
	if err != nil {
		return 0, errors.New("uci_staging_failed")
	}
	defer os.RemoveAll(stage)
	staged := filepath.Join(stage, "passwall2")
	if err = os.WriteFile(staged, result, 0600); err != nil {
		return 0, errors.New("uci_staging_failed")
	}
	// Loading an absolute private file avoids UCI's shared pending delta directory.
	if _, err = run(ctx, u.Config.UCIPath, []string{"-q", "export", staged}, nil, 0); err != nil {
		return 0, errors.New("uci_validation_failed")
	}
	if delta, err := os.Stat("/tmp/.uci/passwall2"); err == nil && delta.Size() > 0 {
		return 0, errors.New("passwall_has_pending_changes")
	}
	current, err = os.Lstat(u.Config.PassWallPath)
	if err != nil || !os.SameFile(info, current) {
		return 0, errors.New("passwall_changed_during_update")
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return 0, errors.New("passwall_recheck_failed")
	}
	again, err := io.ReadAll(io.LimitReader(file, (2<<20)+1))
	if err != nil || !bytes.Equal(again, original) {
		return 0, errors.New("passwall_changed_during_update")
	}
	replacement, err := os.CreateTemp(filepath.Dir(u.Config.PassWallPath), ".kone-stubs-")
	if err != nil {
		return 0, errors.New("uci_update_failed")
	}
	temporary := replacement.Name()
	defer os.Remove(temporary)
	if err = replacement.Chown(0, int(uid.Gid)); err != nil {
		return 0, errors.New("uci_update_failed")
	}
	if err = replacement.Chmod(info.Mode().Perm()); err == nil {
		_, err = replacement.Write(result)
	}
	if err == nil {
		err = replacement.Sync()
	}
	closeErr := replacement.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return 0, errors.New("uci_update_failed")
	}
	if ctx.Err() != nil {
		return 0, errors.New("uci_update_cancelled")
	}
	if err = os.Rename(temporary, u.Config.PassWallPath); err != nil {
		return 0, errors.New("uci_update_failed")
	}
	if directory, e := os.Open(filepath.Dir(u.Config.PassWallPath)); e == nil {
		_ = directory.Sync()
		_ = directory.Close()
	}
	return retained, nil
}

func cleanupStubs(config Config, writer StubWriter, registry Registry) (int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	return writer.Apply(ctx, registry, nil)
}
