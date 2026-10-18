# KOne desktop development

This branch contains a Kotlin/Compose desktop client, a Go service, sing-box
integration, Windows MSI packaging, and automated checks. Read the current
source, workflows and build files for authoritative behavior. Historical
screenshots, browser previews and earlier test descriptions are separate from
current acceptance.

## Development and client languages

Use English for repository documentation, code comments, default UI resources,
commit messages and development conversations. Keep localization APIs and stable
resource identifiers intact. Release builds may add a Chinese or another locale
pack through the separate integration process. An English development environment
does not require an English-only release client.

## Layout

- `desktop/src/main/kotlin/`: application, account, console, core, node and UI code.
- `desktop/service/`: privileged Windows service and configuration planning.
- `desktop/packaging/`: MSI, download page and packaging helpers.
- `desktop/src/test/`: synthetic service, API, configuration and UI tests.
- `desktop/src/main/resources/scenes/`: scene catalog and runtime resources.
- `core/`: the pinned sing-box source used by platform builds.
- `.github/workflows/windows.yml`: Windows build and installer checks.

The client and service must remain separate security contexts. The UI runs as an
ordinary account. Privileged tunnel actions belong to the installed service and
its authenticated local channel. Preserve the existing TUN/system-proxy modes,
cleanup and recovery behavior when editing labels or layouts.

## Managed account protocol

The management service is maintained separately. Use a synthetic server in tests.
Never connect automated fixtures to production accounts or private infrastructure.

| Endpoint | Purpose and relevant fields |
| --- | --- |
| `POST /api/client/claim` | Redeem an invitation with `inviteCode` and `deviceName`; receive device identity and credential. |
| `GET /api/client/config` | Fetch the authorized configuration using a bearer credential; preserve format, policy and revision semantics. |
| `POST /api/client/ack` | Acknowledge `received`, `applied` or `failed` for an exact revision using the documented result codes. |
| `POST /api/client/diagnostics` | Reserved bounded event and usage reporting; enable only under its approved disclosure and consent design. |

The `kone://claim` handler accepts an HTTPS service address and invitation code.
Do not log bearer credentials or include them in screenshots. Store persistent
credentials using the existing platform protection. An expired invitation,
revocation, usage expiry or device limit must remain distinguishable from a
network failure. Source behavior and contract tests define exact response handling.

Managed reject rules must be preserved when generating a client configuration.
Domain restrictions, DNS handling, TLS/IP bypass resistance and direct exceptions
need their own tests. A label or successful start call does not establish policy
enforcement. Never add browsing-content collection as part of a UI change.

Imported nodes (dashboard ⋮ → Import from clipboard / Import from file) use the
Android share-link reader ported unchanged to `desktop/.../node/ShareLinks.kt`.
Each is checked by sing-box before it is kept, sealed with DPAPI in
`imported.bin`, appended after the managed nodes and built through the same
template, so managed reject rules apply to it. They are usable only on an
activated device that has a managed configuration; the selected imported node
can be removed with its ×. Manual entry and custom categories are not built, so
the menu omits them.

Tailscale Home is not implemented on Windows. It would run Tailscale inside the
SYSTEM service and keep its login on the device, and home routes must stay
limited to private ranges so they cannot bypass managed rejects. It needs an
owner decision first.

## Interface and rendering

The dashboard, activation, logs, tools, settings, tray icon and tray panel share
the established ink style. Status symbols must remain readable in both themes.
Country recognition aliases are compatibility data, not user metadata. Translated
country and scene labels must not alter identifiers, coordinates, asset URLs,
geometry, animation quantities or motion parameters.

Scene weather requests use the selected city's coordinates, not device location.
Preserve visibility-based refresh, fallback behavior and the source's attribution.
Ambient audio is synthesized; it must remain opt-in and stop when its rendering
context is inactive. Review current provider terms before any commercial release;
this document does not establish ongoing service eligibility or pricing.

Pause rendering when hidden or minimized. Test hardware and software rendering,
remote desktops, high-DPI displays, keyboard access, tray placement and long labels.
The browser `preview.html` is a synthetic reference, not a functioning VPN.

## Updates and packaging

Preserve HTTPS-only update delivery, signed manifests, a strictly newer version,
expected size and SHA-256, and separate x64/ARM64 artifacts. Update signing keys,
code-signing credentials and production service configuration remain external.
Missing release settings must disable the corresponding feature safely.

MSI packaging must register the approved service and protocol handler, preserve
credential state during an authorized upgrade, reject downgrades, and clean up
its own service, adapter and proxy changes on disconnect or uninstall. Do not
replace another application's system-proxy configuration.

## Validation

Linux can compile supported source, run synthetic unit tests and render reference
images when dependencies are available. Windows CI provides platform build and
installer evidence. A real Windows machine is still required before distribution.

Test activation, TUN and system proxy, egress, direct/proxy rules, managed rejects,
tray actions, shortcuts, autostart, disconnect, application exit, upgrade,
downgrade refusal, suspend/resume and uninstall. Check x64 and ARM64 independently.
List the exact checks actually run; do not treat planned workflow steps as results.

Preserve upstream code and font licenses. Keep personal images, signing material,
account state, private addresses and conversational handoffs out of version control.
Do not publish, deploy, buy signing services or change active network settings
as a side effect of translation. See `RELEASE.md` for the release checklist.
