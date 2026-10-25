# Client synchronization

The integration starts from `ee2c320c0680b3f2ff9914dc50b205aeff09b195` on
`claude/windows-desktop`. Android retains the previously developed update,
feedback, connection-check and runtime routing code while taking the shared
subscription, routing and diagnostic implementation from this branch.

Android build 10040 uses the existing separate application IDs and signing
identity. It does not enable silent installation. Publish the verified APK and
matching source archive before changing the existing update manifest.

The final runtime guard preserves explicitly configured direct sites and Fake IP before Global mode.

Subscription refresh compares JSON content independently of formatting and
object-key order. Changes to rule order, node values and policy still apply.
Video compatibility is an optional, per-profile setting, off by default. It
rejects proxied UDP/443 with a response so HTTPS clients can try TCP, preserving
earlier Home, direct and administrative rules. It is not a throughput guarantee
or a diagnosis of a particular service. TCP-only applications are unaffected.

The owner's Tools page includes Router, an embedded HTTPS LuCI/PassWall2 page.
It reuses the router's authentication, proxy engine and native rule editor.
Only the configured origin may load; there is no JavaScript-to-native bridge,
password capture, arbitrary command API or public listener. An owner may provide
an independently verified SHA-256 certificate fingerprint to trust that
router's valid self-signed certificate. Changed or expired self-signed
certificates remain blocked. No router credentials or addresses are built in.
This integration is an embedded management surface, not a native router adapter
or a device-scoped direct channel for Android applications.

`windows-candidate.yml` builds and validates both Windows architectures without
passing intermediate files through Actions artifacts. Only installers that pass
tests, install/upgrade, service and uninstall checks are saved in a private draft
release. It does not update the public website. Candidate installers are unsigned;
the existing download disclosure must remain until a signed release is supplied.

Validation at integration: 303 Android JVM tests and 46 shared JVM tests pass.
These checks do not establish physical-device playback, native router operation,
Windows installer acceptance or publication. Use the corresponding build/run and
deployment receipts for those results.
