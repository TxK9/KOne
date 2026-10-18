# KOne technical architecture

The repository contains the Android application in `android/`, the proxy core in
`core/`, and project documentation in `docs/`. Management-server deployment and
production credentials are outside this source tree.

## Client and core boundary

The Android client owns presentation, profile storage, platform permissions and
VPN lifecycle integration. The core owns protocol configuration, routing, DNS,
statistics and supported endpoint behavior. Validate configuration before a
service restart and retain a recoverable last-known-good profile.

Personal and managed editions must preserve their own behavior and authorization
boundaries. Managed entitlement, endpoint policy and remote maintenance require
explicit contracts; a presentation flag is not an authorization boundary.

## Managed updates and diagnostics

Use authenticated requests, bounded payloads and explicit versioned response
schemas. Keep operational secrets out of source, logs, UI previews and build
artifacts. Examples must use synthetic identifiers and documentation addresses.

Release handling must verify package identity, signing compatibility, version,
artifact integrity and failure recovery. Check foreground/background transitions,
network-interface changes, interrupted downloads and configuration rejection
before accepting a release.

Diagnostics should report the minimum state required to identify a fault. Apply
retention limits and redact credentials, full proxy links and private endpoint
information. Never expose an unrestricted command channel as a maintenance API.

## Validation limits

Architecture notes do not certify a deployed service or a working device build.
Record build/test results against the exact revision and artifact. Network and
lifecycle acceptance require real execution appropriate to the affected change.
Keep deployment status separate from implementation status.

Upstream protocol and configuration references are maintained in the English
[core documentation](../../core/docs/index.md). Preserve all upstream licensing
and attribution requirements when distributing the application or core.
