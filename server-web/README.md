# KOne server source

This is a source-only development candidate imported from commit `558f3e00eaba82e3412d7eb28e2047f59f5819e8`. It contains the multilingual public website, bilingual management frontend and unchanged 0.3.8 service implementation. It contains no runtime settings, database, private keys, account sessions or download installers.

- `website/`: English, Simplified Chinese, Japanese and French product site.
- `backend/`: service, management UI and synthetic regression fixtures.
- `tools/export_git_static.py`: exact-commit Git-object exporter; no checkout or publication.
- `tests/`: exporter integrity tests.
- `SOURCE_BASELINE.json`: source and immutable installer-manifest references.

Start with `DESKTOP_ENTRY.md`, then use `LOCAL_TASK.md` or `CLOUD_TASK.md` in a new, independent Desktop Code session. Existing sessions and the Windows client are untouched. The proposed GitHub branch is not available until the coordinator records its publication.

## Development

Use Python 3.12 and local Node test/build dependencies. The service has no third-party Python runtime dependency. Run only against disposable loopback fixtures. The Code agent carries out commands; the user need not type terminal setup or sign into another CLI.

From this folder, useful checks are:

```text
python -B -m unittest discover -s tests
python -B -m unittest discover -s backend/tests -p "test_*.py"
node --test backend/tests/i18n.test.mjs backend/tests/owner-download.test.mjs
node backend/tests/audit-catalog.cjs
node website-tests/test-site.mjs
python backend/tests/run_ui.py
```

Use the checked-in npm development versions when dependencies are needed. Do not install a build stack or agent on production. Tests do not establish native Windows client behavior, live node connectivity or production acceptance.

After editing frontend modules, build `backend/web/app.bundle.js` with `backend/deploy/build_web.py` and a locally installed official esbuild executable before committing. Preserve its source hash and asset fingerprints. The Code agent chooses its platform's executable; do not copy a binary from another system.

## Exact-object packaging

`CANDIDATE_TO_RELEASE.md` defines the source→Codex review→approved promotion path. The exporter requires the full reviewed Git commit, not HEAD or an abbreviated ref, and an existing output folder. For a surrounding GitHub checkout use:

```text
python server-web/tools/export_git_static.py site TASK-ID --repo . --source-root server-web --commit FULL_COMMIT_SHA --output out
python server-web/tools/export_git_static.py console TASK-ID --repo . --source-root server-web --commit FULL_COMMIT_SHA --output out
```

For the standalone server repository, omit `--source-root server-web` and run its tool path. The archive is canonical USTAR; its receipt records every Git blob and SHA-256. Working-tree CRLF, uncommitted edits and smudge filters are ignored. Invalid committed UTF-8/LF text, changed release manifests and links are rejected rather than rewritten. A receipt is not deployment approval.

See `docs/backend-reference.md` for the service development boundary. Historical operational reports and the root publisher implementation are intentionally excluded. Backup mechanisms, main merges, production operations and new GitHub workflows/runners are outside this candidate.
