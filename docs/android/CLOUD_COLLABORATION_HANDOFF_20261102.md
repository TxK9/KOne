# Source collaboration and language checkpoint

This is a coordinator handoff, not an access grant or an external message. Claude Code is
expected to run on the user's Windows desktop; that does not authorize Windows-client work.
Source editing and permission to administer a running service remain separate decisions.

## Exact source lines

Repository: private `TxK9/KOne`. Remote readback on 2026-11-02 matched these source lines:

| Draft PR | Head branch / reviewed head | Base branch / exact base |
| --- | --- | --- |
| [#10](https://github.com/TxK9/KOne/pull/10) | `codex/server-web-collab-20261101` / `b37275d9ec568c73a92b6bc0e7ef6805061b4ed9` | `main` / `180ac16164a75a10b00f887f2d5b48fcbba4ee5f` |
| [#12](https://github.com/TxK9/KOne/pull/12) | `codex/server-cloud-acceptance-20261101` / `7f8cead2323276304979bfa0a61fe705a7f76f70` | `codex/server-web-collab-20261101` / `b37275d9ec568c73a92b6bc0e7ef6805061b4ed9` |
| [#13](https://github.com/TxK9/KOne/pull/13) | `codex/android-cloud-acceptance-20261101` / validated implementation `98046b919303f4a9d31fc13788d724c77da37b7b` | `codex/android-ui-20261101` / `0dd7a9ff0d78743d8910e05041bcbb07c7d598a5` |

The Android PR also receives evidence/test-only commits; its final published head is recorded
in the PR body and cloud task result. Check remote heads again before editing or pushing.
Potential concurrent website/backend work must be reconciled; do not overwrite or combine
it with Android work. `main` is not a substitute for the nominated candidates.

## Claude Code source workflow

Use the Windows desktop's already authorized repository connection. No Windows SSH key,
CLI token, production credential or new sign-in is supplied by this handoff. If the actor
cannot read the private repository, identify that exact source-access blocker; this task
does not silently create an invitation, credential or permission.

Create a new independent `claude/server-<task>` worktree/branch from the current reviewed
server-web candidate. Read `server-web/CLOUD_TASK.md`, `CLAUDE.md`, `README.md`,
`SOURCE_BASELINE.json` and the PR #12 report. Work only under `server-web/`, retaining
website locales, console locales, contracts and release-manifest bytes. Do not edit the
Android/core/dependency or Windows-client lines, other sessions, production state or `main`.
Return English commits and a draft PR for coordinator review; do not merge or deploy.

Python 3.12 and Node are the development prerequisites; the service has no third-party
Python runtime dependency. Use the checked-in npm development versions when needed and a
local official esbuild executable. A Code agent can select the Windows executable/launcher;
the user need not supply credentials or run a separate terminal setup.

Run from `server-web/` using the available Python 3 launcher:

```text
python -B -m unittest discover -s tests
python -B -m unittest discover -s backend/tests -p "test_*.py"
node --test backend/tests/i18n.test.mjs backend/tests/owner-download.test.mjs
node backend/tests/audit-catalog.cjs
node website-tests/test-site.mjs
python backend/tests/run_ui.py
python backend/deploy/build_web.py --esbuild PATH_TO_LOCAL_ESBUILD
```

The browser check additionally needs the repository's development Playwright dependency and
a supported local browser. Rebuild and review bundle/source hashes after frontend edits.
Use only new synthetic state. For a disposable management preview, the supported entry is
`python backend/server.py --host 127.0.0.1 --port 8768 --data-dir NEW_TEMP_DIRECTORY`.
Only its new local setup may use synthetic test credentials. Shut down the owned process
after checks; never select an existing database or expose this port publicly.

Website sources are in `website/`; management UI source modules are in `backend/web/js/`
with the generated bundle under `backend/web/`. Editing them requires source permission,
not a production administrator session. A local preview does not grant live backend access.

## Integration contract

The current compiled Android API classes pass the PR #12 disposable backend harness: 12
client scenarios plus lifecycle assertions. The management UI uses a same-origin admin
session cookie and `X-CSRF-Token` for mutations; Host/Origin controls remain enforced.
Device, node, limited-feedback and owner-update authorities remain separate.

| Device route | Contract |
| --- | --- |
| `POST /api/client/claim` | One-time invite plus device name; format 1 response with device ID and credential |
| `GET /api/client/config` | Device bearer; format 1 sing-box source, revisions, names and policy |
| `POST /api/client/ack` | Issued revision with received/applied/failed status and fixed report code; stale revision returns 409 |
| `POST /api/client/diagnostics` | Bounded version/network/allowlisted events, not arbitrary logs or configuration |
| `/api/client/feedback` | Device bearer; bounded idempotent reports and device-scoped history |

Policy expiry is nullable Unix seconds; quota/device limit are numeric and period is
`YYYY-MM`. Android candidate guards preserve these schemas and the existing 403/409/
revocation mappings. Run the saved JVM/backend harness with the complete compiled Android
class root and test jars if changing shared contracts; never reuse customer state.

Real-service access still needs a named actor, approved operations/data scope, exact
website/admin origins, transport/session method and revocation/lifetime decision. Deployment
also needs a reviewed target/release/rollout/recovery decision. No existing admin session,
password, grant, network exposure or production access is provided here. No external Claude
agent was contacted.

## Language audit finding

[The scoped audit](cloud-state-matrix-20261102/candidate-language-audit.json) compares PR #12
against `b37275d9...` and Android candidate changes against `0dd7a9ff...`, and separately
inventories tracked baseline content. No actual Han, escaped Han or non-Latin letter
sequence was introduced in the audited candidate diffs. Authored cloud prose/comments,
commit and PR text are English. The character scan supplements manual prose review; it
does not identify the language of every Latin-script word.

The entire repository is not literally English-only outside translation resource files.
The server baseline contains UI-language glosses/examples in 14 comment tokens across ten
JavaScript files; sampled comments already explain their purpose in English and reference
existing UI/art terms. Its quotation-source document includes the legitimate Chinese UI
quotation and attribution. Android's nominated baseline contains Chinese development text
in `docs/android/CURRENT.md`, `docs/android/DIRECTION.md` and `tools/ANDROID_VISUAL.md`.
Those are pre-existing baseline documents, not new cloud prose.

Existing multilingual product resources, arbitrary user data, fixtures, quotations and city
artwork were preserved. No newly authored non-translation Chinese prose required correction.
A repository-wide migration of pre-existing development documents is a separate scoped
editorial change; the zero-introduced finding must not be presented as zero Chinese globally.

## Product-language and download status

The management console itself, separately from the public website, has English and
Simplified Chinese infrastructure. `backend/web/js/i18n.js` declares `en`/`zh-CN`, stores
the choice in `kone.admin.locale`, and otherwise chooses Chinese for a Chinese browser
locale and English for other browser locales. `backend/web/app.js` exposes the selector on
login, the management shell and boot-error UI. Numbers/dates/plurals and known backend
system errors/record titles are formatted or mapped. Names, arbitrary user text and provider
data are not automatically translated. The prior 562-call-site/725-entry catalog audit and
86 bilingual browser checks remain dated evidence, not a fresh test of every live route.
There is no third admin UI language in this candidate. The public site separately has four.

Android `AppSettingsScreen.kt` offers a system-default choice and packaged app locales,
using `AppCompatDelegate.setApplicationLocales`; an empty locale list restores system
following. Default unqualified resources are English (`resources.properties`). Supplied
locale resource directories include Simplified/Traditional Chinese, Persian and Russian.
An inventory of translatable string keys found 1041 default keys and 418 KOne/Knine keys;
Simplified Chinese defines all 1041/418. Persian and Russian define 570 stock keys and zero
of those 418 custom keys; Traditional Chinese defines 577 stock keys and zero of the 418.
Missing values fall back to English. These are string-key counts, not proof of complete UI
translation or every plural/array. A system-following default is not a forced-English UI.

API 33+ locale persistence is platform-handled. The earlier checkpoint found no explicit
AppCompat storage opt-in for older APIs. The follow-up reproduced missing API 32 persistence,
enabled the documented private automatic storage, and verified writing, restoration in a
separate host test JVM, and returning to the system default. This is synthetic framework
evidence, not an OEM/device restart result. See the
[locale/crash follow-up](CLOUD_LOCALE_CRASH_20261102.md) and the
[official AppCompat locale API](https://developer.android.com/reference/androidx/appcompat/app/AppCompatDelegate#setApplicationLocales(androidx.core.os.LocaleListCompat)).

The reviewed website source pins Android **1.0.0, build 10041**, arm64-v8a, in both
`website/release.json` and the HTML fallback. The manifest also says invite-client,
activation required, approved/ready, and activation service pending. The relative APK URL is
`downloads/KOne-client-1.0.0-b10041-arm64-v8a.apk`; the source canonical origin is
`https://kone.qingwanli.com/`. JavaScript reads the origin's `release.json` at runtime, so a
live manifest could differ. No live fetch, APK download or deployed-file verification was
performed for this finding. Android development remains **1.0.0, build 10056**; a draft PR
and successful compilation do not publish that APK or update the website download.

## Observing an authorized test Android

Codex can review supplied screenshots and short recordings of synthetic test states. With
an already authorized ADB connection available to its execution host, it can also capture
an installed test app's display using `adb exec-out screencap -p` or a short `screenrecord`
session, then inspect the captured artifacts. Those capabilities are documented in the
[Android ADB guide](https://developer.android.com/tools/adb#screencap). The current cloud
runtime does not automatically inherit the Windows desktop's device connection.

Observation of an already installed test app needs no production signing key or service
credential. Installing a new APK is a separate authorized step and requires a signed
artifact; an unsigned compilation is not installable. Device connection/pairing, remote
transport, installation and interactive operations were not performed here. No public ADB
port, account-token sharing or network-policy change is implied. Use disposable test state,
no real VPN traffic, and avoid personal notifications/account screens or unrestricted logs.

The useful hardware matrix is launch/activation without a live account, loading/offline/
retry/empty states, EN/ZH/system locale changes, 200% font/RTL, keyboard/insets/rotation,
pause/resume and WebView recreation. Frame/resource measurements and real TalkBack traversal
need device evidence; still images alone cannot establish smoothness or resource cleanup.
