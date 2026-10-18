//go:build !windows

package main

import "errors"

func runService(bool) error { return errors.New("kone-service runs on Windows only") }
