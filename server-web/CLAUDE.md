# KOne server development

Work only under `server-web/` in this repository, on a new `claude/server-<task>` or `codex/server-<task>` branch. The surrounding Android/core/dependencies and any active Windows-client task belong to other owners. Do not change their branches, files, sessions or tests.

Use English development communication, commits and documentation. Product resources must preserve complete English/Simplified Chinese/Japanese/French for the website and English/Simplified Chinese for the management UI. Keep product copy concise: labels, actions, errors and real state. Preserve all user-authored data and functional identifiers.

`SOURCE_BASELINE.json` records the imported source independently from this repository's main branch. Production is AWS only; do not restart a legacy database. Use synthetic state on loopback for all development. Preserve API/authentication boundaries, installer paths/bytes/checksums, customer credentials and database behavior. No schema changes are part of this import.

Develop through the selected Desktop Local or Cloud environment. No separate CLI login is required. Cloud gets source through the existing private GitHub connection; it must not obtain a Windows SSH key, account token or production access. Local may use the existing Windows-generated SSH key through the already configured Git alias. Do not alter host trust, copy keys, forward agents, create secrets, accounts, permissions, runners, workflows or remote agents.

Commit a candidate and return its full SHA, changed paths and test evidence. Codex owns unified review and any separately authorized backup/release. Never merge main or publish from this development task. Console JavaScript executes in the logged-in administrator origin, so static files require security and behavior review.

Do not run a backup mechanism or copy Claude history/authentication. Backup remains proposal-only. Do not mount disks, share folders, create schedules or change another session.
