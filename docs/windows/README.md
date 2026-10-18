# KOne for Windows

This directory specifies the planned Windows client and contains an interactive visual prototype. `preview.html` uses synthetic nodes, latency, traffic, and a reserved documentation address. Its buttons simulate state changes; it does not connect a tunnel or contact the management server.

## Product scope

The target is a managed x64 Windows desktop client using the repository's pinned sing-box core. Exact supported Windows versions must be confirmed against current core, runtime, and security-support requirements before release.

The proposed UI uses Kotlin and Compose Multiplatform. Keep a new desktop implementation separate from Android until shared modules have a reviewed dependency boundary. The management server is maintained separately.

Planned behavior:

- Require managed activation before connecting; support invitation codes and validated `kone://claim` links.
- Import assigned nodes into the application's configuration template and enforce applicable server policy.
- Present categories, latency, weather-style health indicators, light and dark appearances, and a compact tray panel.
- Leave Tailscale home settings disabled and unset by default for managed distributions.
- Keep credentials, private network details, and diagnostic content out of ordinary logs and exports.

These are requirements, not claims of completed Windows implementation.

## Visual reference

The prototype contains four views:

1. Dashboard: navigation rail, moving ink landscape, category tabs, node rows, connection state, traffic, and connection duration.
2. Activation: invitation-code field and an explicit start action. The preview's paste button inserts a synthetic example; it does not read the clipboard.
3. Tray panel: compact artwork, selected node, health, traffic, duration, and connection controls.
4. Tray icons: mountain mark with a colored health badge for light and dark taskbars.

The English health seals use `S` (healthy), `M` (slow), `!` (unreachable), and `Z` (disconnected), paired with weather glyphs and accessible labels. The state keys in the prototype remain internal identifiers.

| State | Seal | Seal background | Seal text | Light-theme text | Dark-theme text |
|---|---|---|---|---|---|
| Healthy | S | `#2F7D66` | `#FFFFFF` | `#2F7D66` | `#63C29E` |
| Slow | M | `#E0A800` | `#1C2622` | `#9A6F00` | `#E8B520` |
| Unreachable | ! | `#B3362A` | `#FFFFFF` | `#B3362A` | `#F0806F` |
| Disconnected | Z | `#6B7470` | `#FFFFFF` | `#5C6561` | `#9AA3A0` |

Colors and typography are defined in the prototype's CSS variables. Reuse the Android health presentation and procedural artwork where practical; `art/` contains rendered assets for the browser preview. Do not infer a real node location or device state from the samples.

## Management integration

Treat `android/app/src/main/java/io/nekohasekai/sfa/managed/ManagedApi.kt` and related model classes as the current client-side reference. Confirm the server contract independently before implementing a Windows client.

| Endpoint | Purpose |
|---|---|
| `POST /api/client/claim` | Exchange `inviteCode` and a bounded `deviceName` for `deviceId` and `credential` |
| `GET /api/client/config` | Fetch a versioned sing-box source configuration and policy using bearer authentication |
| `POST /api/client/ack` | Acknowledge receipt, application, or failure for a revision |
| `POST /api/client/diagnostics` | Submit only supported, consented diagnostic fields |

Validate `kone://claim` input, require an approved HTTPS server, and never log invitation codes or bearer credentials. Windows credential protection, such as per-user DPAPI storage, remains an implementation decision requiring tests. Keep credentials out of portable backups.

Managed domain restrictions must be enforced when present in an assigned policy, including matching DNS behavior. Reuse the Android template's supported domain-only rule interpretation rather than copying arbitrary source routing rules. Policy is supplied by the server, not inferred from account identity, location, or local files. Additional monitoring or diagnostic event types require a reviewed contract and authorization flow.

Use a fake service or an isolated test deployment for development. Do not test against an active production account or private administration endpoint.

## Proposed implementation

- Create a separate `desktop/` Gradle project for Kotlin/JVM and Compose Multiplatform.
- Compile a Windows/amd64 core from `core/` using the recorded build inputs and `core/release/DEFAULT_BUILD_TAGS_WINDOWS`.
- Verify the Windows TUN and driver packaging requirements against the pinned core; do not assume the prototype establishes compatibility.
- Keep the UI unprivileged. If a privileged service is needed, design authenticated local IPC, least privilege, installation, upgrade, and removal together.
- Package on a Windows runner using a validated installer toolchain. Register `kone://` only with explicit installation scope.
- Decide service startup, automatic launch, update verification, and code signing before distribution. Do not enable paid services or purchase certificates without approval.

## Validation

Linux development can cover shared logic, compilation, unit tests, and rendered UI comparisons. Windows-specific installation, service lifecycle, TUN connectivity, tray behavior, and protocol registration require Windows execution. Hosted runners can provide build and smoke-test evidence, but successful CI alone does not replace real desktop acceptance.

Before release, validate activation, configuration updates, rollback, revocation, applicable domain policy, suspend/resume behavior, and uninstall cleanup on a supported Windows machine. Mark each unexecuted check explicitly. No installer or working Windows tunnel is supplied by this prototype.

## Repository boundaries

Use English documentation and UI labels. Preserve licenses and legitimate dependency references. Do not commit personal images, node credentials, authentication state, device identifiers, or private network settings. Inspect concurrent changes before editing and avoid unrelated Android, signing, or deployment changes during Windows UI work.
