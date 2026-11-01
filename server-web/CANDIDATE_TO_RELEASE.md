# Candidate to release

The developer's output is committed source, not a production release.

1. Desktop Local or Cloud creates a separate task branch and records its full commit SHA, changed paths, tests, limits and intended surface (site, console UI, or service logic).
2. Codex reviews the actual commit and current production state. A clean worktree and a previous SSH test are not acceptance evidence. Console JavaScript runs in an authenticated administrator origin, so static-only publication still requires review.
3. Code/Git/artifact backup remains independent of the Claude account. A new backup mechanism is proposal-only. No agent, history/account export, shared drive, disk mount or schedule is part of this toolkit. Any release backup uses the separately approved maintainer process.
4. Codex exports reviewed static payloads from the exact Git commit using `tools/export_git_static.py`. For this GitHub layout pass `--source-root server-web`; for the existing standalone server repository omit it. Check the receipt's commit, file hashes and two fixed release-manifest hashes.
5. Only the integration maintainer uses the existing restricted publisher after deployment authorization. Source-code commits, tests and the draft PR do not trigger publication. Service Python changes require separate promotion; the static tool cannot deploy them or a database migration.
6. The maintainer checks the actual release, health and rollback receipt, then records the accepted source reference. Do not fast-forward main merely because a package was created.

The existing root verifier checks static suffixes, archive format, fixed installer manifests, bounds and ownership. It does not prove a UI change is safe or functionally accepted. Keep its policy unchanged.

The exporter never reads worktree files. It validates UTF-8/LF text in Git blobs rather than rewriting bytes, leaves binary payloads intact, rejects links/submodules/LFS pointers and creates uncompressed USTAR with fixed metadata. A noncanonical committed blob is a source issue to review, not permission to loosen server validation. Receipts carry no local repository path or credentials.
