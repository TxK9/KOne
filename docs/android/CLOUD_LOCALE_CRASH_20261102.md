# Locale persistence and application-owned JVM crash text

This additive pass starts at published Android draft PR #13 head
`ba78f3c81f7d1f5c3adf14acb42b653f06fd5765`. The nominated base remains
`0dd7a9ff0d78743d8910e05041bcbb07c7d598a5`; build 10056 / version 1.0.0 is unchanged.
The published KOne cloud runtime performed these synthetic checks independently of a
personal computer. Server sources and PR #12 were not modified.

## Reproduced behavior and corrections

An API 32 AppCompat host accepted `zh-CN` in memory but did not write an application-locale
record. The manifest lacked the documented automatic-storage opt-in. Commit
`6bc8ffefc79edce0eb109225b79363fcd383615e` adds that opt-in to AppCompat's disabled,
non-exported metadata service. The merged manifest retains both restrictions; no permission
or network exposure was added. The existing system/default selector and translations remain.

The final writer test obtains the actual AppCompat private XML record. A separate Gradle
test-worker JVM, verified to have a different process identity and empty in-memory locales,
receives only that synthetic record and restores `zh-CN`. Selecting an empty locale list
then removes the saved record and restores system following. Process identities and host
paths stay outside Git. This checks framework persistence in a fresh host process, not an
OEM app restart, reinstall, Android 12-to-13 upgrade or real-device keystore/service state.
API 33+ retains platform storage. See the
[official AppCompat API](https://developer.android.com/reference/androidx/appcompat/app/AppCompatDelegate#setApplicationLocales(androidx.core.os.LocaleListCompat)).

The real crash handler also persisted synthetic secrets from throwable messages, causes
and suppressed exceptions into pending stack/metadata files. Two crash tests failed before
the correction; benign failure context already passed. Commit
`e8a5a9010db54b2e91046daea86698e7e7471fd2` redacts recognized credential syntax before
writing application-owned pending JVM stacks and exception reasons: bearer values, selected
credential key/value assignments and JSON string fields, authorization/cookie headers and
HTTP(S) user information. It preserves exception types, stack frames, causes/suppressed
structure, ordinary diagnostic fields, valid metadata JSON and delegation to the previous
uncaught-exception handler. There is no new remote telemetry or credential.

This bounded sanitizer does not identify every private value. Arbitrary descriptions,
encoded/partial secrets, unrecognized fields, multiline private keys, native Go logs,
historical pending/archive files and explicit raw-configuration export are not covered.
The previous Android handler still receives the original throwable; this does not sanitize
system logcat. Review an export before sharing it. Existing user files are not rewritten.

## Actual validation

| Check | Result |
| --- | --- |
| Runnable pre-fix regressions | Four cases: one passed; locale storage and two secret-persistence cases failed |
| Final focused writer/crash run | 11 passed, zero failures/errors/skips: one locale writer, three handler/file checks and seven sanitizer cases |
| Fresh-worker locale restoration/reset | One passed, zero failures/errors/skips |
| Ordinary suite and unsigned Kotlin compilation | 434 passed, zero failures/errors/skips |
| Native UI and crash-handler regression run | 22 passed, zero failures/errors/skips: 19 existing UI cases plus three actual pending-file cases |
| Merged manifest | Private disabled service, automatic locale storage enabled |

Three earlier fixture compilation failures are recorded separately: attempting to subclass
the final application, a removed Robolectric helper, and using a host process API directly
against Android's compile classpath. They executed no tests and are not product failures.
The corrected crash fixture attaches the real final application without running production
`onCreate`, invokes the actual registered handler with synthetic throwables, and restores
the previous handler/removes its temporary files. It does not start a VPN/native engine.

The seven sanitizer cases cover quoted/escaped JSON, authorization/cookie line boundaries,
HTTP user information and token queries, unterminated quoted values, benign field-name
preservation and idempotence. These are a finite syntax matrix, not a universal privacy claim.
The earlier 12 client/backend contract scenarios and wallpaper checks remain dated evidence
for unchanged code and were not repeated by this pass.

## Actual feedback submission callback follow-up

The previous presentation fixture supplied its own busy callback and therefore did not
prove the production screen's duplicate-submission behavior. A new native test invokes
the actual screen's captured submit callback twice immediately, before recomposition can
disable the row. It uses real `FeedbackDraft` private storage and real `FeedbackApi` request
handling with a held synthetic `HttpURLConnection`. Binding acquisition is separated into
an internal content entry so the fixture needs no real credential, keystore or application
startup; the public screen still obtains its normal managed binding. The existing submit
guard, coroutine, payload, failure and completion logic are unchanged.

Two immediate callbacks produce one pending POST with its bearer header and disabled
Sending row. A controlled 503 restores the enabled action and preserves the draft without
a receipt. Two immediate retry callbacks produce exactly one further pending request,
with the identical saved payload/request ID. A controlled 201 records the receipt, clears
the matching draft and displays its number. The test contains no replacement busy guard.
No production concurrency defect was reproduced, so no guard behavior was changed.

A temporary counterfactual removed only the production `if (busy) return` line. The single
test then failed at the one-pending-request condition after ten seconds. The original source
was restored byte-for-byte before verification/commit. This expected failure is a fixture
sensitivity check, not a defect in the published candidate. The restored final selection
passes **23 native checks** (the preceding 22 plus this submission-path case); the ordinary
suite and unsigned compilation again pass **434 tests**, all with zero failures/errors/skips.

Run `--tests '*.FeedbackSubmissionTest'` with the same native opt-in/cache setup to reproduce
the isolated check. It verifies callback/storage/API integration with controlled transport,
not live TLS, an Android keystore, a real backend transaction or device scheduling.
The [feedback evidence](cloud-locale-crash-20261102/feedback/evidence.json) records the
counterfactual, restored run, final JUnit cases and source/log hashes.

## Reproduction and remaining work

From `android/`, use the published JDK/SDK/AAR setup and the saved
`cloud-acceptance-20261101/native-render-cache.init.gradle`. Set
`KONE_ROBOLECTRIC_CACHE` to a disposable local Robolectric cache and use
`-PkoneEdition=share -PknineVisualTests=true`. Set `KONE_LOCALE_FIXTURE` to a new disposable
directory and run `:app:testOtherDebugUnitTest --tests '*.LocalePersistenceWriteTest'`, then
run `--tests '*.LocalePersistenceRestoreTest'` in a separate Gradle invocation. Do not run
both phases in one worker; without a selected fixture both cross-process tests are skipped.
The evidence manifest retains full command templates, exit statuses and local log hashes.

Ordinary checks use `:app:compileOtherDebugKotlin :app:testOtherDebugUnitTest` without the
visual opt-in. The final native selection uses `HubPolishVisualTest`, `AccountVisualTest`,
`DashboardSemanticsTest` and `CrashReportPersistenceTest`. No signing or APK installation is
required for these host checks.

Further independently feasible work includes binding-replacement/cancellation scenarios,
full navigation/back-stack/permission-screen fixtures, broader export redaction design and
reviewed all-variant dependency provenance. Real TalkBack, process/OS upgrades, OEM WebView,
frame cadence, memory/power and device installation still require an authorized test device.
Claude live access/deployment remain separate actor/scope/origin/transport decisions.
No Windows build/test, production operation, merge, grant, purchase or environment change
was performed. A successful unsigned compile does not publish or install build 10056.

[Sanitized commands, JUnit cases and hashes](cloud-locale-crash-20261102/evidence.json)
support this checkpoint. [The collaboration handoff](CLOUD_COLLABORATION_HANDOFF_20261102.md)
contains language coverage and the source-only website download finding.

The subsequent [cancellation/diagnostic matrix](CLOUD_CANCELLATION_DIAGNOSTICS_20261102.md)
adds the defined navigation/binding checks, bounded crash-input regression and redaction of
historical diagnostic copies. Original historical files and explicit configuration inclusion
remain unchanged; its final counts supersede this earlier checkpoint's totals.
