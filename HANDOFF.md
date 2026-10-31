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

Existing build helpers are under `tools/`. Their required environment variables
and external inputs must be configured explicitly. Keep signing keys and private
photographs outside version control. Validate package/signature compatibility
before an authorized in-place installation; never uninstall to bypass a mismatch.

`desktop/` contains a real desktop implementation and Windows packaging source.
Read `docs/windows/README.md` and the desktop build scripts. Source presence does
not establish a successful Windows installer, tunnel, service, or runtime test.

Record the exact checks executed and their limits. Compilation, unit tests,
rendering, installation, live networking, update recovery, and visual acceptance
are separate evidence levels. Obtain deployment and billing authorization before
creating public services or paid infrastructure.

## Current Android line

`docs/android/CURRENT.md` describes where Android work stands: the integration branch
(`claude/polish-10042`), how parallel feature branches own disjoint files, the design system
every screen follows, where features live, and what has and has not been checked on a device.
Start Android work from that branch's head and follow its working rules.

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
