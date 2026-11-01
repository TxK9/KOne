Work on the KOne server website and management UI in this new Desktop Local session only.

Use a separate server-source clone/worktree and create a new `claude/server-<task>` branch. Leave the active Windows client, every other conversation and checkout, Android source and core alone. Do not run Windows client, Gradle or JVM tests for this task. Do not change Claude settings or install another login flow.

Read the server project's `CLAUDE.md` and `SOURCE_BASELINE.json`. If this independent source folder has not been prepared, acquire it using the existing Windows-generated SSH key through the existing `kone-aws-dev` Git alias, or the reviewed private GitHub branch. Verify the exact source commit first. Never copy or display private keys, forward an SSH agent, disable host-key checks, create secrets, or repair account permissions. If the existing route is unavailable, report the failure and stop dependent operations.

Develop locally with synthetic test data. Preserve website English/Simplified Chinese/Japanese/French, management English/Simplified Chinese, installer manifests, API/authentication semantics and customer credentials. Product labels should be concise actions and real states. Keep user-authored text unchanged. No production access, publishing, schedules, backup agents, folder sharing or disk mounts.

Commit the candidate on its task branch. Return the full commit SHA, changed paths, exact test results and limits, and any remaining UI questions. Codex performs the unified review and any separately authorized backup/release. Do not merge main or deploy. Carry out necessary commands through this Code session; do not ask the user to type a terminal workflow.
