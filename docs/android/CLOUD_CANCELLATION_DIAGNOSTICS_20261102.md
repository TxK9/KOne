# Claim cancellation, navigation and diagnostic boundaries

This finite follow-up resumes draft PR #13 at
`9963abe452fa5c97628bc7df24b692f68dd6d366` in the published KOne cloud runtime.
Android's nominated base remains `0dd7a9ff0d78743d8910e05041bcbb07c7d598a5`,
version 1.0.0 / build 10056. The restored JDK 21 / Gradle 9.7 / Android SDK setup
executed the commands independently of a personal computer. No server source was edited.

## Reproduced corrections

Commit `e07a1e52829a7ab1047b1aea16cb37b1fe794ff4` checks coroutine cancellation after
the blocking claim response and after profile removal, before binding/scheduling. Previously,
a cancelled loopback request still invoked four local mutations after its valid response;
cancellation during the controlled removal step still invoked three. Both regressions now
pass. A cancelled queued claim never starts its request; a failed claim releases the mutex
so a subsequent attempt commits once in the existing request/remove/bind/schedule/sync order.
The public claim path uses the tested transaction boundary with its real platform operations.

Activation also caught `CancellationException` as an ordinary activation failure. A native
test reproduced the erroneous failure message; cancellation now propagates separately while
the existing `finally` releases the busy state. The public activation screen retains its real
binding; an internal content entry permits disposable state and controlled transactions.

The activation cases exercise duplicate callbacks before recomposition, failure/retry with
preserved fields, and navigation-host back/re-entry while a claim is pending. Back cancels
the transaction, no late local bind/schedule occurs, the next page retains the service address
and starts with an empty invite field, and a fresh attempt succeeds once. The navigation host
is synthetic and uses real production content; this is not a test of the full MainActivity,
physical Back key, native service or Android keystore.

Feedback's real screen/API/draft checks additionally replace the binding while an old response
is pending and navigate away before acknowledgement. The old completion cannot overwrite the
replacement account's form or receipt. An acknowledged old request still records its receipt
in its own binding and preserves edits made after leaving; returning shows that receipt and
the existing new-feedback action reveals the newer draft. No additional request is sent.
This intentionally preserves an acknowledged receipt after navigation instead of pretending
the server rejected a submission. No new feedback runtime behavior was required.

Cancellation prevents the tested late **local** commit. It does not undo a server-side claim
that already consumed an invite, roll back removal work already completed before cancellation,
or revoke an already committed binding. Remote cancellation/idempotency/abandoned-device
recovery is a separate protocol decision; no automatic grant or revocation was introduced.

## Diagnostics versus restorable data

Commit `4f6f5745232d9db83f20aabf324c96a2bea7f0e1` makes diagnostic copies safer without
rewriting source reports or intentionally restorable configuration.

| Output | Intended contents and tested behavior |
| --- | --- |
| `KoneBackup` | User-controlled node/settings backup, intentionally including node configuration and remote URLs; managed binding/managed profile excluded by existing code. Source is byte-identical to the starting head. No backup write/restore or credential stripping was performed. |
| Crash archive, configuration excluded | Staged configuration is omitted. Historical flat metadata and selected logs redact recognized credential syntax. Malformed metadata becomes explicit safe error JSON. |
| Crash archive, configuration explicitly included | Configuration bytes, including the synthetic restorable node secret, remain identical. This option still intentionally contains sensitive configuration. |
| Generated debug text | The real ZIP text-entry method redacts recognized credential syntax and preserves ordinary diagnostic context. |
| Streamed debug command/text files | A shared text boundary handles chunk-split UTF-8 and credentials, caps each diagnostic line at 32,768 characters, marks truncation and retains following lines. It is wired to command output, log files and proc text. No system command was executed. |
| Framework/APEX binary files | Explicitly bypass text handling. The real binary ZIP-copy branch preserves arbitrary synthetic bytes exactly. |

Six diagnostic-file cases initially had four failures: historical metadata/log credentials,
malformed metadata and generated debug text. Four stream/binary cases initially had two
failures: chunk-split credential disclosure and absent line limits. The corrected ordinary
suite includes all ten passing cases. The staging boundary is the one used by
`createZipArchive`; native ZIP/age encryption itself was not invoked in these host tests.
Source reports, unrelated files, explicit configuration inclusion and backup semantics remain.

A long quoted/escaped synthetic secret also reproduced `StackOverflowError` in the previous
regular expression. Possessive repetition removes that recursive backtracking path. The
focused cases include roughly tens of kilobytes of quoted/unfinished input and an 80-cause
synthetic trace with fixed frames. Both pass the five-second host-test bounds. These finite
checks do not establish arbitrary-input complexity, crash-time OOM survival, a whole-export
size cap or device performance. Stack capture and archived-file loading still allocate data.

Redaction recognizes selected credential fields, bearer/header/cookie syntax and HTTP(S)
user information. Arbitrary names, addresses, descriptions, encoded/partial secrets,
unrecognized syntax, nested non-schema metadata and private-key blocks are not universally
removed. Flat crash metadata remains valid JSON. Device/system logcat contents themselves
are not rewritten; the text boundary applies to copies exported through this diagnostic path.
Explicit configuration exports still require careful handling. No real logs were collected.

### Escaped-value truncation follow-up

Independent review identified one missing boundary: a valid JSON secret whose escaped quote
is split exactly after its backslash at the 32,768-character cap. The retained prefix ends
in an unmatched escape, so the previous quoted-value expression failed and disclosed the
secret prefix. Actual Kotlin tests reproduced that leak and the equivalent direct dangling
quoted-value gap: 16 focused cases ran, with two failed tests and 14 passing tests.

Commit `486d7cbf47837593afcad04b14f229e8adb29fc0` permits an optional dangling escape only
at the existing line-end alternative for recognized JSON/assignment secrets. Possessive
matching and the stream line cap remain unchanged. New cases check password/credential JSON
at offsets -1/0/+1 around the split, double/single-quoted assignments, LF/CRLF/EOF, retained
following frames and exact non-secret `passwordPolicy` prefix preservation. The source JSON
is parsed before truncation to confirm the reproducer is a valid complete input.

The final ordinary suite now passes **453 tests** plus unsigned Kotlin compilation, and
the three actual crash-handler/file checks were rerun successfully. All have zero failures,
errors and skips. The full 28-case UI/file matrix below remains earlier evidence for its
unchanged UI code; it was not rerun wholesale for this text-only correction. Explicit
configuration and binary preservation checks pass in the ordinary suite. No backup/export
selection, configuration bytes, city assets, dependency, PR #12 or device action was changed.
The [boundary evidence](cloud-cancellation-diagnostics-20261102/escape-boundary/evidence.json)
retains failed/pass cases, command templates and source/log hashes. Existing privacy and
crash-time memory limitations still apply.

## Finite acceptance matrix

| Defined item | Actual outcome |
| --- | --- |
| Late blocking claim response | Reproduced local mutations after cancellation; corrected; real ManagedApi loopback request passes |
| Removal/queued cancellation and retry | Four transaction cases pass, including no queued request and exact commit order |
| Actual activation content | Three native cases pass: duplicate/failure/retry, back/re-entry and cancellation-message handling |
| Actual feedback content | Three native cases pass: duplicate/retry, binding replacement and back/newer-edit preservation |
| Diagnostic files and streams | Ten cases pass, including explicit configuration and binary preservation |
| Long/adversarial crash input | Two focused bounded host cases pass after one reproduced stack overflow |
| Ordinary suite + unsigned Kotlin compilation | Latest **453 tests pass**, zero failures/errors/skips; the preceding checkpoint passed 450 |
| Selected Robolectric suite | Earlier complete matrix: **28 pass** (25 native UI plus three crash-handler/file cases). After the boundary correction, the affected three file cases pass again; unchanged UI was not rerun. All recorded runs have zero failures/errors/skips |
| Dependency provenance | Exact special cases and official-source options recorded below; no new trust metadata, lock or version change |
| Physical device/OEM/process/keystore behavior | Not run: no authorized attached device/emulator; synthetic tests do not establish these outcomes |
| Native ZIP/age and live collector execution | Not run in host fixtures; no native engine/root collector invoked |
| Live service/Claude access, signing, installation, deployment | Not attempted; retain separate actor/scope/origin/transport and release approvals |

Earlier 18 wallpaper-host, 19 Chromium and 12 disposable client/backend scenarios remain dated
evidence for unchanged code; they were not repeated or added to these counts. The server's
previous real-engine skip remains a skip. One initial native fixture failed compilation due
to importing a member assertion as an extension; it executed no tests and was corrected.
The initial claim/staging/stream boundaries were extracted without behavior changes to run
the failing fixtures; the red runs are not presented as tests compiled on an untouched checkout.

No defined independent test above is left pending. Unsupported hardware/native outcomes and
the specific provenance decisions below are explicit limits, not passed checks. No Windows
build/test, production/customer database operation, credentials/grant, network policy change,
purchase, external Claude contact, merge, signing or installation occurred.

## Exact dependency provenance decisions

The earlier observed runtime graph remains 265 modules / 190 artifact observations for
`otherDebugRuntimeClasspath`; dependency declarations are unchanged. Observations and local
digests are not independent authenticity receipts. The
[machine-readable list](cloud-cancellation-diagnostics-20261102/dependency-provenance.json)
retains the exact artifact filenames/digests and source references.

| Artifact(s) | Exact unresolved provenance or coverage |
| --- | --- |
| `io.github.sagernet:libghostty-android`, `libghostty-android-extras`, `libghostty-android-compose`, all `0.1.0-SNAPSHOT` | Cached AAR digests are recorded; their exact publishing commit/timestamp/attestation was not authenticated. Upstream documents publication on every main push. Evaluate a reviewed immutable source/release or obtain an authenticated snapshot receipt; do not approve the cache hash as trust. |
| `io.github.sagernet:libghostty-android-compose-legacy:0.1.0-SNAPSHOT` | Declared for otherLegacy; not covered by the observed otherDebug inventory. No invented artifact hash or compatibility result. |
| `com.github.jeziellago:compose-markdown:0.7.2` | Upstream tag resolves to `587e4129c6d3b7a3b31876a5c49e69cd75838df6`; matching the downloaded JitPack AAR to reviewed source/build evidence remains separate. |
| `com.github.jeziellago:Markwon:58aa5aba6a` | Upstream resolves to `58aa5aba6a979a3a9374099c97f9365b09852ba2`; source identity is known, independent artifact/source correspondence is not established. |
| `com.github.topjohnwu.libsu:core:6.0.0`, `service:6.0.0` | Upstream tag resolves to `8c3e80ffe466b89ff875471e542d77e6a1b480c5`; each observed AAR still needs independently reviewed build/signature/digest provenance. |
| `de.robv.android.xposed:api:82` | Compile-only JAR/POM digests recorded. The cached POM contains no SCM URL/connection; no independent publisher receipt was established here. |
| `android/third_party/libxposed-api` | Vendored Git tree `18013dfb2c3416ebe01951ca6f920e25e6783c10`; tree identity is not an upstream revision/authenticity receipt. |
| `dependencies/libbox.aar`, `libbox-legacy.aar` | Existing committed-snapshot digests match the restored inputs; exact core source/build provenance needs a reviewed receipt/rebuild, rather than treating baseline equality as upstream authentication. |
| Other compile/test/plugin/legacy/Play/release graphs | The existing runtime inventory is not all-variant verification metadata. Cover their actual metadata and artifacts before enabling enforcement. |

Upstream's libghostty README currently lists `0.1.0-alpha02` as an alternative release
coordinate. That is an evaluation option only; no upgrade or compatibility claim is made.
Its legacy Compose requirements differ and need separate checks. See the
[official libghostty repository](https://github.com/SagerNet/libghostty-android),
[compose-markdown source](https://github.com/jeziellago/compose-markdown/commit/587e4129c6d3b7a3b31876a5c49e69cd75838df6),
[Markwon source](https://github.com/jeziellago/Markwon/commit/58aa5aba6a979a3a9374099c97f9365b09852ba2),
[libsu source](https://github.com/topjohnwu/libsu/commit/8c3e80ffe466b89ff875471e542d77e6a1b480c5)
and [official Xposed reference](https://api.xposed.info/reference/packages.html).

The prior temporary lock write/read experiment demonstrated resolution feasibility, not
artifact authenticity. Generated verification entries require independent review, including
metadata and dependencies resolved during task execution. Gradle documents this distinction
in its [verification guidance](https://docs.gradle.org/9.7.0/userguide/dependency_verification.html)
and [security guidance](https://docs.gradle.org/9.7.0/userguide/best_practices_security.html).
No lockfile, verification metadata, keyring, repository policy or dependency version was
changed in this pass.

[Commands, failed/pass cases and hashes](cloud-cancellation-diagnostics-20261102/evidence.json)
provide reproducible evidence. The exact final published head is recorded in the PR body
and cloud task result rather than embedded as a self-referential commit.

The final remote concurrency readback on 2026-11-02 found server acceptance branch
`codex/server-cloud-acceptance-20261101` at `d3349bd8eabdab4530b854bfa8916c128b6910ea`
and `codex/server-web-collab-20261101` at `54cddb15d10664bfde1c373eff09537a35d6b771`.
These differ from the server worktree/language-audit snapshot `7f8cead2323276304979bfa0a61fe705a7f76f70`
and original nominated baseline `b37275d9ec568c73a92b6bc0e7ef6805061b4ed9`.
Those concurrent heads were not modified or retested here; earlier server results and this
scan must not be attributed to them. Android's nominated base and remote starting head
were unchanged before its additive push. Server reconciliation/review remains with its owner.
