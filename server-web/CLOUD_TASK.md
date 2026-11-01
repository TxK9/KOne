Work on KOne server development in this new Cloud Code session only, using the existing GitHub connection to private `TxK9/KOne`.

First verify that this session is based on the coordinator-reviewed `codex/server-web-collab-20261101` branch and contains `server-web/SOURCE_BASELINE.json`. If the UI started from another branch, fetch the reviewed branch and create a new `claude/server-<task>` branch in this session's isolated checkout. Do not switch another task, edit main, merge the import PR, or change Android/core/dependencies/Windows files. If the reviewed source branch is not available, report that exact blocker instead of assuming it exists.

Read `server-web/CLAUDE.md` and `server-web/README.md`. The server-source baseline is recorded separately from the surrounding Android repository's commit. Work only under `server-web/`. Use local Cloud tools and synthetic data. Do not connect to production, install a remote agent, add a runner/workflow, request deployment secrets, or create accounts or permissions. This session does not have the Windows SSH key and must not obtain it. Do not copy CLI account tokens into the session.

Preserve the current website's four locales, the console's two locales, release manifest bytes, business/authentication/API behavior and customer credentials. UI copy should contain concise labels, actions, errors and real state. Never translate arbitrary user text.

Commit changes on the isolated task branch and return a candidate/draft PR with the full commit SHA, changed files, exact tests and limits. Codex owns unified review and any separately authorized backup/promotion. The user should work in Desktop, not type a separate terminal setup.
