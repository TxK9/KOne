# Fresh cloud acceptance and integration handoff

This is candidate evidence from a fresh instance of the published KOne environment on
2026-11-01. It does not authorize a merge, deployment, access grant or release.

## Source and execution identity

Repository: private `TxK9/KOne`.

| Line | Branch | Exact nominated and remote-read SHA |
| --- | --- | --- |
| Website/backend | `codex/server-web-collab-20261101` | `b37275d9ec568c73a92b6bc0e7ef6805061b4ed9` |
| Android | `codex/android-ui-20261101` | `0dd7a9ff0d78743d8910e05041bcbb07c7d598a5` |

The candidates were fetched separately and checked out in isolated worktrees. The original
checkout stayed clean at `180ac16164a75a10b00f887f2d5b48fcbba4ee5f`. No candidate was
combined with another line. This server candidate changes only `server-web/`.

Environment instance: `ccarenv_b64_Y2NhcmVudl8xYTcwMjhmOTY2NTg4MTkxOWQ2ZTQ4ODUwNjVmYTNlNQ`.
Published configuration version:
`16baad87-4a9f-4117-8815-e6f385322ec3~cecfgver_6ae77c0f6e7881a0826b2b400af1de1a`.
The instance initially reported starting, then running/connected with current observations
and the restricted HTTP policy enforced. No environment or policy setting was changed.

Actual commands verified Python 3.12.14, Node 24.19.0, npm 11.9.0, JDK/javac 21.0.12,
Gradle 9.7.0, Go 1.26.10, SDK command-line tools 19.0 and esbuild 0.28.2. SDK listing
confirmed Android 36 and 37.1 platforms, build-tools 36/37 and NDK 28.0.13004108.
The SDK tool warns about newer XML metadata; listing succeeds. The restored Gradle ZIP
SHA-256 is `84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`.
The restored JDK archive SHA-256 is
`33cc8a4ba4163b003bbf3516824861ee49e7295e6a2879ec91316c2aead78b80`.

## Verified server baseline

Run from `server-web/`, with the restored activation script sourced:

| Command or check | Actual result |
| --- | --- |
| `python3 -B -m unittest discover -s tests -v` | 18 passed |
| `python3 -B -m unittest discover -s backend/tests -p 'test_*.py' -v` | 91 run: 90 passed, 1 skipped |
| `node --test backend/tests/i18n.test.mjs backend/tests/owner-download.test.mjs` | 12 passed |
| `node backend/tests/audit-catalog.cjs` | Passed: 562 localized call sites and 725 English catalog entries |
| `node website-tests/test-site.mjs` | 23 checks passed |
| `python3 backend/deploy/build_web.py --esbuild /workspace/kone-env/node-tools/node_modules/.bin/esbuild` | Exit 0; rebuilt bundle and fingerprints exactly match Git |
| Two fresh loopback start/request/shutdown cycles | Both passed; auth status and HTML checked; state removed |
| `python3 -B backend/tests/run_ui.py` | 86 bilingual UI checks passed; four paintings in both themes, reduced motion and compatibility checks passed |

Browser checks used local `playwright@1.62.1`, the checked-in development version, installed
with scripts disabled outside the repository, and `/usr/bin/chromium` selected explicitly.
They ran only against parent-owned temporary synthetic fixtures and stopped their server.
The real-engine test was skipped because `KONE_TEST_ENGINE` was absent. It is not a pass.

Canonical exports were made from the exact nominated Git objects, without checkout filters:

| Export | Files | Archive SHA-256 |
| --- | --- | --- |
| Website | 66 | `ba884c012e8a1d46b1f1f14b8e6acade8b3048873d27ca2a740c532141e37b41` |
| Console | 32 | `e40a661f1d002abadf4f5baf7811829670e5e08b83817f3637dc0f4ab4fd0a57` |

Commands, elapsed times, exit statuses and original local-log digests are recorded in
`cloud-acceptance-20261101.json`. Raw logs remain in the managed task workspace; credentials,
customer state, screenshots of personal content and external host profiles are excluded.

## Additional authorization regression coverage

`backend/tests/test_role_boundaries.py` adds three disposable integration tests, with 57
negative boundary assertions across valid device/node/limited-feedback credentials,
expired admin sessions and public-host management methods. All three pass.
The complete backend suite was rerun after adding them: 94 run, 93 passed, one real-engine
skip; the 18 exporter tests passed again. See the evidence manifest.
No reproducible server runtime defect was established by this scoped review, so its business,
authentication, schema, UI bundle and release manifest bytes are preserved.

## Claude collaboration handoff

Start source work from the website/backend candidate above and create a separate task branch.
Read `CLOUD_TASK.md`, `CLAUDE.md`, `README.md` and `SOURCE_BASELINE.json`. Source access and
management-service access are different permissions. This task supplies a source handoff only;
it does not contact Claude, read its sessions, grant access or create credentials.

For a local synthetic service, create a new disposable directory and run
`python3 backend/server.py --host 127.0.0.1 --port 8768 --data-dir NEW_TEMP_DIRECTORY`.
Use fresh synthetic setup credentials only. Shut down the owned process after checks.
Do not point this command at existing runtime state or expose the loopback port.

| Interface | Contract and trust boundary |
| --- | --- |
| Website | `website/`; four existing locales; fixed installer paths/manifests; no admin credential |
| Console | `backend/web/`; local same-origin assets; admin session cookie plus `X-CSRF-Token` for mutations; allowed Host/Origin |
| `POST /api/client/claim` | `{inviteCode, deviceName}`; one-time/expiring invite; returns `{format:1, deviceId, credential}` |
| `GET /api/client/config` | Device `Authorization: Bearer ...`; `format:1`, `configKind:sing-box-source`, `revision`, `configRevision`, `config`, names and `policy` |
| Policy | `quotaBytes`, `deviceLimit`, nullable Unix-seconds `expiresAt`, `period` as `YYYY-MM`; names and provider wording stay data |
| `POST /api/client/ack` | Device bearer; `{revision,status,code}`; status `received/applied/failed`; stale/unissued revision returns 409 |
| `POST /api/client/diagnostics` | Device bearer; bounded appVersion/network/events; no arbitrary logs/configuration |
| `/api/client/feedback` | Device bearer; bounded/idempotent reports and device-scoped history; no admin authority |
| `/api/node/register`, `/poll`, `/result`, `/usage` | Separate node enrollment/credential audience; no device/admin authority |
| `GET /api/feedback/inbox`, feedback status POST | Separate optional limited-agent bearer; active feedback/progress only; cannot confirm release or administer customers |
| Owner updates | Separate owner-device authority; same-origin/package/channel/hash/size/version checks; no signing material in this task |

The Android nominated source reads these client routes in `ManagedApi.kt` and `FeedbackApi.kt`.
Source inspection found compatible fields and status semantics, including numeric expiry.
The server's real HTTP fixtures and Android's stand-in HTTP unit tests are independent checks;
this handoff does not claim a device-to-server end-to-end run.

Real Claude access still requires an explicit decision about the actor, approved source
repository access, source-only versus limited-feedback versus admin purpose, exact service
origin and approved transport. An administrator must separately approve any credential grant
or network exposure. Do not solve missing access by sharing an admin session or downloading
production state. Existing access controls remain in force.

## Scoped security assessment

The passing tests exercise device/customer isolation, revoked and expired credentials,
one-time claims, node/device/admin separation, optimistic revision checks, CSRF/origin/Host,
public route allowlisting, traversal/symlink rejection, strict JSON/input bounds, redacted
configuration summaries, limited-feedback revocation and synthetic restore behavior.
Feedback renders through text nodes, not executable HTML. Node commands use argument arrays;
configuration import parses data without executing links. Node engine installation checks a
pinned hash and extracts a regular named member rather than trusting archive paths.

Customer feedback, repository prose, generated suggestions and model output remain untrusted
data. English text is not a security boundary. The dev task has no production/customer/signing
inputs. No agent prompt can authorize access grants, public exposure or deployment.

Supply-chain follow-ups remain review prerequisites, not silently applied changes: the server
npm versions are exact but there is no committed npm lockfile; Android has a snapshot dependency,
no reviewed dependency verification metadata, and no wrapper distribution checksum property.
The Android workflow is manual-only and uses mutable major-version action references; explicit
least-privilege workflow permissions and reviewed action pins deserve a separate review.
No workflow was run or modified. No Windows compilation/tests were performed. Hashes from a
new download alone must not be promoted into trusted verification metadata.

Android source inspection finds same-origin update URL checks, size/hash verification,
strict package/build/signer checks and no redirect following. It does not implement a full
TUF expiry/rollback metadata system. This is a limitation of that review scope, not evidence
that update authenticity was tested on a device. No new cryptography or signing identity was
introduced. Existing synthetic backup/restore tests do not establish production recovery.

Review references supplied by the coordinator:
[OWASP authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html),
[REST security](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html),
[prompt injection](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html),
[logging](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html),
[GitHub Actions security](https://docs.github.com/en/actions/reference/security/secure-use),
[Gradle security](https://docs.gradle.org/current/userguide/best_practices_security.html),
[TUF metadata](https://theupdateframework.io/docs/metadata/).
These are review guidance, not proof that this candidate is secure or compliant.

## Remaining evidence levels

No real engine/node path, customer service, production operation, physical Android device,
VPN connection, installation, signing or OS update flow was exercised. Android acceptance and
subsequent polish are recorded on its independent candidate line. No automatic merge, public
installer change, purchase, security setting change or production deployment was made.
