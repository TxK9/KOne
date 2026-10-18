package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func digest(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

var listBytes = append([]byte("SRS\x01"), []byte("compressed rules")...)

func TestRuleSetsAreCheckedBeforeAnythingIsWritten(t *testing.T) {
	good := listBytes
	cases := []struct {
		name   string
		data   []byte
		digest string
		ok     bool
	}{
		{"geosite-netflix", good, digest(good), true},
		{"geosite-geolocation-!cn", good, digest(good), true},
		{"geoip-jp", good, digest(good), true},
		{"geosite-cn", good, digest(good), false},             // the template's own
		{adsRuleSet, good, digest(good), false},               // the template's own
		{"../geosite-netflix", good, digest(good), false},     // not a name
		{"geosite-netflix.srs", good, digest(good), false},    // no dots
		{"GEOSITE-NETFLIX", good, digest(good), false},        // written one way
		{"geosite-netflix", good, digest([]byte("x")), false}, // not what was sent
		{"geosite-netflix", []byte("SRS\x09abc"), digest([]byte("SRS\x09abc")), false},
		{"geosite-netflix", []byte("PK\x03\x04zip"), digest([]byte("PK\x03\x04zip")), false},
		{"geosite-netflix", []byte("SRS"), digest([]byte("SRS")), false},
	}
	for _, c := range cases {
		if err := CheckRuleSet(c.name, c.data, c.digest); (err == nil) != c.ok {
			t.Errorf("%s %q: %v", c.name, c.data, err)
		}
	}
	big := make([]byte, mostRuleSetBytes+1)
	copy(big, "SRS\x01")
	if CheckRuleSet("geosite-netflix", big, digest(big)) == nil {
		t.Error("a list over 4 MiB is refused")
	}
}

func TestAListThatSingBoxCannotReadLeavesTheLastOneInPlace(t *testing.T) {
	folder := filepath.Join(t.TempDir(), "sets")
	reads := true
	store := &ruleSetStore{folder: folder, parse: func(path string) error {
		if filepath.Ext(path) != ".new" {
			return fmt.Errorf("checked %s, not the new copy", path)
		}
		if !reads {
			return errors.New("parse rule-set")
		}
		return nil
	}}
	if err := store.Put("geosite-netflix", listBytes, digest(listBytes)); err != nil {
		t.Fatal(err)
	}
	newer := append([]byte("SRS\x02"), []byte("newer rules")...)
	reads = false
	if err := store.Put("geosite-netflix", newer, digest(newer)); err == nil {
		t.Fatal("a list sing-box cannot read is refused")
	}
	kept, _ := os.ReadFile(filepath.Join(folder, "geosite-netflix.srs"))
	if string(kept) != string(listBytes) {
		t.Fatalf("the last good list stays: %q", kept)
	}
	if _, err := os.Stat(filepath.Join(folder, "geosite-netflix.srs.new")); !os.IsNotExist(err) {
		t.Fatal("the refused copy is removed")
	}
	if store.Put("geosite-other", []byte("junk"), digest([]byte("junk"))) == nil {
		t.Fatal("junk is refused before sing-box is asked")
	}
	reads = true
	if err := store.Put("geosite-netflix", newer, digest(newer)); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(folder, "not-a-list.srs"), listBytes, 0o644)
	os.WriteFile(filepath.Join(folder, "geoip-jp.srs.new"), listBytes, 0o644)
	if got := store.Kept(); !reflect.DeepEqual(got, map[string]string{"geosite-netflix": digest(newer)}) {
		t.Fatalf("kept: %v", got)
	}
}

func TestRulesNamingAListNotHandedOverAreLeftOut(t *testing.T) {
	store := &ruleSetStore{folder: t.TempDir(), parse: func(string) error { return nil }}
	if err := store.Put("geosite-netflix", listBytes, digest(listBytes)); err != nil {
		t.Fatal(err)
	}
	rules := []Rule{{"set", "geosite-netflix", "proxy"}, {"set", "geoip-jp", "direct"}, {"suffix", "example.com", "proxy"}}
	want := []Rule{{"set", "geosite-netflix", "proxy"}, {"suffix", "example.com", "proxy"}}
	if got := store.Usable(rules); !reflect.DeepEqual(got, want) {
		t.Fatalf("usable: %v", got)
	}
}

// With a real sing-box (KONE_SINGBOX), the check is the core's own reading of the file.
func TestSingBoxReadsTheListsItWillRun(t *testing.T) {
	binary := os.Getenv("KONE_SINGBOX")
	if binary == "" {
		t.Skip("no sing-box binary here (KONE_SINGBOX)")
	}
	real, err := os.ReadFile("../src/main/resources/rules/geosite-cn.srs")
	if err != nil {
		t.Fatal(err)
	}
	folder := t.TempDir()
	parse := singBoxParses(binary)
	good := filepath.Join(folder, "good.srs")
	os.WriteFile(good, real, 0o644)
	if err := parse(good); err != nil {
		t.Fatalf("a real list: %v", err)
	}
	bad := filepath.Join(folder, "bad.srs")
	os.WriteFile(bad, listBytes, 0o644)
	if parse(bad) == nil {
		t.Fatal("a list with a good header and broken body is refused")
	}
}
