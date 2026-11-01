# Desktop entry

This handoff is prepared for a future, separate server-development session. It does not change an existing Claude session or establish which environment that session currently uses.

## Choose an existing route

| Route | Source available to the session | Credential boundary | Candidate handoff |
| --- | --- | --- | --- |
| Desktop Local on Windows | A separate server-source clone selected in the folder control | Existing Windows SSH alias stays local | Task branch and full commit SHA |
| Desktop Cloud / Default | A pushed private GitHub branch selected for the new session | Existing Claude GitHub connection only; no Windows key | Candidate branch or draft PR |

The Desktop environment selection determines where code runs. Local selects a folder; Cloud selects repositories. A separate worktree can isolate a Git session. These are documented capabilities, not confirmation of the current installation or session. See [official Desktop documentation](https://code.claude.com/docs/en/desktop).

A Cloud environment named Default does not provide local Windows files, its SSH key, or a server checkout. The repository branch must actually contain the server source, and the existing Claude GitHub connection must be able to access that private repository. See [official Cloud documentation](https://code.claude.com/docs/en/claude-code-on-the-web).

Do not choose the production server as a Desktop SSH execution environment. That mode installs/runs remote Claude tooling. The production server is for the existing service and restricted Git/static transport, not agent execution or builds.

## Local route, when the Windows owner is ready

1. Leave the current Windows-client session and checkout untouched.
2. Start a new Code session, select Local, and choose a separate server-source folder. Enable the worktree option when available.
3. Reuse the signed-in account. Paste `LOCAL_TASK.md` as the task. Claude performs the project Git operations through its tools; the user does not need to type terminal commands or log into a separate CLI.
4. If source acquisition is needed, the task may use the existing `kone-aws-dev` Git transport to `/srv/kone-dev/repository.git`, after checking connectivity without changing keys or host trust. Prior successful access is not proof that it is currently available.

## Cloud route, after the candidate branch is published

1. Leave the existing Cloud or Windows task untouched.
2. Start a new Code session using the existing Cloud environment and private `TxK9/KOne` connection. Select the reviewed server collaboration branch if the UI offers a branch selector.
3. Paste `CLOUD_TASK.md`. The task verifies the branch and source before editing. If the session started from a different branch, it fetches/switches only its own new Cloud checkout using the existing GitHub connection.
4. Missing repository access is a reported blocker. Do not export tokens, move private keys, create accounts or change permissions to work around it.

The proposed branch is `codex/server-web-collab-20261101`. Until the coordinator records a remote branch/commit/PR receipt, it is a local candidate only. A private-repository metadata check by Codex does not prove the Claude GitHub app has access.
