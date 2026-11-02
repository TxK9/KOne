GO ?= go
OUT ?= build

.PHONY: build test
build:
	mkdir -p $(OUT)
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 GOTOOLCHAIN=local $(GO) build -trimpath -buildvcs=false -ldflags='-s -w' -o $(OUT)/kone-router-controller .

test:
	GOTOOLCHAIN=local $(GO) test -race ./...
