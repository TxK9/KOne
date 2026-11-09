# Source integrity and profile checks (2026-11-09)

## Missing core source

`SNAPSHOT.json` lists 2151 copied files. Six were absent from the repository.
One was a Chinese-language issue template, which was removed on purpose. The
other five were Go source files dropped by the root `.gitignore` patterns
`**/device/` and `**/build/`:

- `core/transport/device/{device,stack,system,system_stack}.go`
- `core/cmd/internal/build/main.go`

Without `transport/device`, the masque, openvpn and openconnect protocols do not
compile, so `go build ./cmd/sing-box` failed. These five files were restored from
upstream SagerNet/sing-box at the pinned commit
`c992297988288565a24a6d36e2cf4d77cb835fcd`. Each file's SHA-256 matches its
`SNAPSHOT.json` entry. The ignore rules still apply everywhere else, but these two
upstream directories are now listed as exceptions.

## Checks executed

| Check | Result |
| --- | --- |
| `go build -tags "$(cat release/DEFAULT_BUILD_TAGS_OTHERS)" ./cmd/sing-box` (Go 1.25.5, linux/amd64) | Succeeded after the restore; failed before it |
| `go vet` and `go test` on `transport/device`, `cmd/internal/build` and `experimental/locale` | Passed |
| `PairedProfileTest` (11) and `CountryProfileGroupingTest` (7) | Passed in a standalone Kotlin/JVM harness that compiles the production source files with a stub `Profile` |
| `sing-box check` on configs that `PairedProfile.fromVlessLink` built from synthetic Reality, TLS and IPv6 links | Passed. A negative control (unsupported flow) was rejected |

## Profile behavior changes

- VLESS links with an IPv6 literal no longer put URI brackets into `server`.
  This also affects the TLS `server_name` fallback, where brackets would break
  certificate verification.
- A blank `fp` falls back to the default fingerprint.
- A VLESS `encryption` value other than `none` is rejected. The pinned core does
  not implement VLESS encryption.
- Country grouping now matches two- and three-letter codes only in capitals, so
  "de", "us" and "ca" written as ordinary words no longer assign a country. It
  also recognises Traditional Chinese names and plain "Korea".

## Limits

The full Android Gradle build could not run in this environment because the
network policy blocks `jitpack.io` and `api.xposed.info` (403). Because of that,
none of the following were exercised: Android compilation, Android-dependent unit
tests, lint, spotless, APK packaging, installation, the libbox AARs, live
networking and Tailscale connectivity. `sing-box check` validates configuration
structure only. It does not establish that a connection works.
