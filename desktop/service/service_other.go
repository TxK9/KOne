//go:build !windows

package main

import (
	"errors"
	"os/exec"
)

func runService(bool) error { return errors.New("kone-service runs on Windows only") }

func hideWindow(*exec.Cmd) {}
