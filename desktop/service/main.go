// kone-service is KOne's Windows service. It runs as SYSTEM, starts with Windows, and is the
// only part of KOne with the rights a VPN needs, so connecting never asks for them. KOne talks
// to it over the named pipe \\.\pipe\KOne; see service_windows.go for who may and what it does.
//
//	kone-service run           as the service (the installer registers it)
//	kone-service debug         the same in a console, for tests
//	kone-service build-config  print the configuration for a plan on stdin (tests)
//	kone-service version
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
)

var version = "dev"

func main() {
	command := ""
	if len(os.Args) > 1 {
		command = os.Args[1]
	}
	switch command {
	case "run":
		exit(runService(false))
	case "debug":
		exit(runService(true))
	case "build-config":
		exit(buildConfig(os.Stdin, os.Stdout))
	case "version":
		fmt.Println("kone-service", version)
	default:
		fmt.Fprintln(os.Stderr, "usage: kone-service run | debug | build-config | version")
		os.Exit(2)
	}
}

func exit(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// buildConfig reads {"source": …, "selected", "rules", "port", "secret", "tun", "mode", "bypass", "direct", "proxied", "routes", "blockAds", "mixed"} and prints the
// configuration the service would run, so the App's tests can check they build the same.
func buildConfig(in io.Reader, out io.Writer) error {
	var request struct {
		Source   map[string]any `json:"source"`
		Selected string         `json:"selected"`
		Rules    string         `json:"rules"`
		Port     int            `json:"port"`
		Secret   string         `json:"secret"`
		Tun      bool           `json:"tun"`
		Mode     string         `json:"mode"`
		Bypass   []string       `json:"bypass"`
		Direct   []string       `json:"direct"`
		Proxied  []string       `json:"proxied"`
		Routes   []Rule         `json:"routes"`
		BlockAds bool           `json:"blockAds"`
		Mixed    int            `json:"mixed"`
	}
	decoder := json.NewDecoder(io.LimitReader(in, maxMessage))
	decoder.UseNumber()
	if err := decoder.Decode(&request); err != nil {
		return err
	}
	config, _, err := Build(request.Source, Options{Selected: request.Selected, Rules: request.Rules, Port: request.Port, Secret: request.Secret, Tun: request.Tun, Mode: request.Mode, Bypass: request.Bypass, Direct: request.Direct, Proxied: request.Proxied, Routes: request.Routes, BlockAds: request.BlockAds, Mixed: request.Mixed})
	if err != nil {
		return err
	}
	encoder := json.NewEncoder(out)
	encoder.SetEscapeHTML(false)
	return encoder.Encode(config)
}

// maxMessage bounds one request on the pipe or stdin.
const maxMessage = 8 << 20
