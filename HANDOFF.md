# KOne development handoff

This branch contains the source listed in `README.md`. Inspect its current build
files, version metadata, and tests before changing behavior. Historical records
apply to their original artifacts and are not current acceptance results.

Use English development documentation and default UI labels; preserve release localization hooks. Preserve functional identifiers,
protocol fields, detection aliases, upstream attribution, and license notices.
Inspect concurrent changes before writing and avoid replacing unrelated edits.

Language changes are reviewed independently from feature changes. Do not import
uncommitted functionality, replace the native core, change signing identity,
clear application data, or modify active network settings to complete translation.

Use the Android Gradle wrapper with the SDK, JDK, and AAR inputs required by this
branch's build files. A development signature cannot replace an installation
using a different signing identity. Never uninstall to bypass that mismatch.

Record the exact checks executed and their limits. Compilation, unit tests,
rendering, installation, live networking, update recovery, and visual acceptance
are separate evidence levels. Obtain deployment and billing authorization before
creating public services or paid infrastructure.

## Development and release languages

Repository documentation, comments, default display resources and development
conversations use English. The Linux workspace uses an English locale. These
choices do not restrict the release client's language. Retain the original
client language packs and language selection, stable resource keys, formatting
placeholders and protocol fields. Translation resources may contain their target
languages. Missing translations fall back to English. Keep development and design
notes in English. Do not replace business identifiers, country recognition aliases
or external user data merely to translate a label.

Only explicitly shared project files belong in this repository. Keep private
conversations, local device information, credentials, signing material, personal
images and unrelated notes outside it. English text alone is not anonymity or
a restriction on the provider's account and network metadata.

## Known source gaps (2026-11-21)

The initial export dropped five core source files that `SNAPSHOT.json` lists as
copied: `core/cmd/internal/build/main.go` and the four files in
`core/transport/device/`. The root `.gitignore` rules `**/build/` and
`**/device/` excluded them; those paths are now re-included. Until the files are
restored from the original snapshot (verify each against its `SNAPSHOT.json`
SHA-256), `go build ./cmd/sing-box` fails because `protocol/masque`,
`protocol/openvpn` and `protocol/openconnect` import `transport/device`, and the
`make` release targets that run `./cmd/internal/build` fail. The recorded AARs in
`dependencies/` are unaffected.

A cloud session without access to `jitpack.io` and the Sonatype snapshot
repository cannot resolve the Android dependency graph. In that environment the
pure-JVM selfuse tests can be compiled with a standalone `kotlinc` against
`org.json:json:20250517` and `junit:junit:4.13.2`; this is unit-test evidence
only, not an Android build.
