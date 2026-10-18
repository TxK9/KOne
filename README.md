# KOne

KOne is a modified client based on the official sing-box for Android (SFA) application.
The checked-in source and build metadata define this branch's capabilities.

- `android/`: Android application and tests.
- `core/`: pinned sing-box source.
- `dependencies/`: recorded Android core artifacts.
- `docs/`: development specifications and historical evidence.
- `desktop/`: Kotlin/Compose desktop application, core integration, and Windows packaging source.
- `tools/`: existing build and integration helpers.

Read `HANDOFF.md` for the development boundary. `SNAPSHOT.json` describes the initial
export, not subsequent changes or current acceptance. Preserve upstream licenses
and third-party notices. Never commit personal images, credentials, private
network settings, signing material, account state, or unrelated personal files.

This language cleanup does not establish that an artifact builds, installs,
connects, or passes live device tests. Those checks require separate evidence.

## Development and release languages

Repository documentation, comments, default display resources and development
conversations use English. The Linux workspace uses an English locale. These
choices do not permanently restrict the release client's language: preserve
localization APIs, stable resource keys, formatting placeholders and protocol
fields. A separately reviewed release language pack may provide Chinese or
another language during local integration. Do not replace business identifiers,
country recognition aliases or external user data merely to translate a label.

Only explicitly shared project files belong in this repository. Keep private
conversations, local device information, credentials, signing material, personal
images and unrelated notes outside it. English text alone is not anonymity or
a restriction on the provider's account and network metadata.
