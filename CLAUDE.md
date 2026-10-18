# KOne development

This repository contains the modified SFA Android client in `android/`, sing-box source in `core/`, build tools in `tools/`, and development documentation in `docs/`. The management server is separate.

Read `HANDOFF.md` for source and build details. Windows work is specified in `docs/windows/README.md`; `docs/windows/preview.html` is a visual prototype, not a functioning VPN client.

- Use English for project documentation, interface text, comments, and development handoffs.
- Keep changes within the assigned project scope. Do not inspect unrelated folders, personal records, model histories, or private tooling state.
- Never commit personal photographs, real node credentials, device identifiers, private network settings, signing keys, passwords, or authentication state. Use reserved documentation addresses in examples.
- Inspect `git status` and the target file's current contents before editing. Preserve concurrent work and never reset or replace unrelated changes.
- UI work follows the existing ink-and-paper palette, restrained weather indicators, aligned rows, and unobtrusive controls. Preserve functional behavior while refining presentation.
- Keep personal and managed distribution behavior separate. Activation and server policy must be enforced by the appropriate variant without changing unrelated profiles.
- Reuse the existing authorized signing path for Android releases. Do not create a replacement signing identity, uninstall the application, or clear application data to bypass installation failures.
- Do not change active device, router, proxy, or network settings as part of documentation or visual-preview work.
- Report what was changed and what was actually verified. Source inspection, unit tests, builds, installation, and live network validation are different evidence levels.
- Confirm architectural changes, new paid services, and public deployment before making them.

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
