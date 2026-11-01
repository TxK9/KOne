# Static candidate boundary

Only Codex's integration owner promotes reviewed candidates in this workflow. Claude prepares source commits and tests; it does not publish. The existing `kone-dev` account, Git transport and constrained static publisher were exercised previously, but current connectivity and sudo behavior have not been rechecked in this task. No access settings were changed.

The existing Git repository is `/srv/kone-dev/repository.git`. Windows Local may use the already configured `kone-aws-dev` alias and its Windows-generated key. Cloud uses the existing private GitHub connection and has no production key or publisher credentials.

The constrained publisher's documented root command is `/usr/local/sbin/kone-web-release`. It permits fixed status, site/console publish and rollback operations, never arbitrary sudo commands or direct production data access. Do not call it from this toolkit. The fixed runtime locations and root-owned verification policy remain unchanged.

For committed static candidates, the maintainer runs the Git-object exporter locally. It enforces the original static asset classes and immutable Android/Windows release-manifest hashes. Build the frontend bundle before committing modules; the exporter intentionally does not build or replace a stale bundle. Review service changes separately.

Do not use worktree `tar`, `git checkout --force`, or newline rewriting as an integrity workaround. CRLF text in a clean Windows worktree is not a canonical committed payload. `git cat-file blob <oid>` reads the exact object without smudge filters; this is the source of every exported file.
