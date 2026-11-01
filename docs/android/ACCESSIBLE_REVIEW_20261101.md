# Accessible-state and security follow-up

This records the independently feasible follow-up after the initial cloud acceptance.
It does not depend on Claude access or a physical device. The nominated source remains
`0dd7a9ff0d78743d8910e05041bcbb07c7d598a5`; implementation and evidence are in
[draft PR #13](https://github.com/TxK9/KOne/pull/13).

## Reproduced and corrected

**Wallpaper terminal failure.** A metadata failure or partially attached/rendering layer
set an error and stopped the controller, but a subsequent host `active(true)` callback
could start the clock and layers again. A render failure could also leave `ready=true`.
Three new host regressions failed before the correction. A failed controller now gates
activity on the absence of an error and clears readiness. It retains the existing static
painting and city identity; a new page instance can initialize normally.

Commit `0665a9dd2e53c403d18e2ac674dfbeaa99a10744` contains the narrow runtime fix and
regressions. All 14 host tests and all 15 Chromium checks pass. Browser metadata and sky
faults remain not ready and stopped after host connection/activity and motion-preference
callbacks. Sampled time stays fixed and no attached layer is running. These are injected
synthetic errors, not evidence of a failure on a physical Android device.

**Optional native fixture compatibility.** Enabling the existing `knineVisualTests`
source set exposed six compilation errors from three obsolete `CollapsedPanel` calls.
After updating those calls, 14 of 15 selected tests passed; the remaining offline account
test selected an old English label. It now reads the existing localized resource instead
of duplicating product copy. The obsolete exit-address fixture is replaced by a real
address-named selected profile: visible address text is absent and the existing globe
retains its accessible content description. This deliberately does not assert that the
address is absent from accessibility semantics. Commit
`1c23f76a1fecb68700fdb7f384fb38532740db80` changes test fixtures only.

**Gradle distribution integrity.** The existing pinned Gradle 9.7.0 distribution now
requires its independently reviewed official SHA-256 in wrapper properties. The version,
HTTPS distribution URL and official older wrapper JAR remain unchanged. Commit
`47f5ced3ad33f9971e4aeca225f030d171608912` includes an isolated real-wrapper probe:
a fresh cache with a deliberately wrong hash exits 1 with checksum verification failure;
a separate fresh cache accepts the reviewed archive and reports Gradle 9.7.0. The probe
uses the already verified local official archive, not a fresh remote supply-chain test.
The source for the pin is the [official Gradle checksum reference](https://gradle.org/release-checksums/).

**Post-ready sky-update failure.** Follow-up injection on
`7fd58e518b639f5e0c46f7bd4a8e12e5851b7492` armed a `sky.render` or layer `setSky` fault
only after successful readiness, then invoked the actual registered 30-second callback or
host resume. All four cases failed in both harnesses: the thrown exception left
`ready=true`, `error=null` and `running=true`. This was a separate lifecycle gap from the
initialization failures above. Commit `0366948015c3a4eb07b41ef630ac5f270ff3a1b2` limits
exception handling to the sky-update pipeline and shares terminal cleanup with initialization.
It preserves the cause in state, clears readiness, freezes the clock, stops layers/timers and
keeps the static painting visible. A failed update cannot reschedule or continue initialization.
The optional pulse fallback and unrelated event operations are unchanged.

All **18 host tests and 19 Chromium checks pass** after the correction. Four recorded browser
samples remain stopped through subsequent host/visibility/reduced-motion callbacks. Healthy
pages continue to pass the existing checks. The timer probe invokes its captured real callback
directly without waiting 30 seconds. Android debug asset merge passes and matches source
SHA-256 `cd2edbd8b82cef3b97235f934d931393f16f87c71a39c6c3e8ca2d930afbb111`.
Earlier native results below are retained separately and were not rerun for this asset-only fix.

## Additional executed evidence

The final selected native run passes **18 tests, zero failures/errors/skips** under
Robolectric API 35 native graphics, using the platform Application and synthetic fixtures:

| Tests | Count | Verified behavior |
| --- | ---: | --- |
| `AccountVisualTest` | 7 | Account actions, offline reason disclosure and syncing, 200% font wrapping, minute expiry, timezone/locale dates, settings hub badges |
| `DashboardSemanticsTest` | 1 | Folded node selector exposes button semantics and invokes selection |
| Selected `PrivacyUiTest` | 1 | Address-named selected profile uses a globe instead of visible address text while preserving its accessible identifier |
| `FeedbackDraftTest` | 6 | Binding isolation, failed-upload retry identity, restart/network preservation, newer-draft protection from older receipts, completion/receipt semantics |
| `WallpaperAssetClientTest` | 3 | Local MIME/loading rules, external/file/content/encoded-traversal refusal, 604 approved day/night images for 302 cities and 31 countries, exact image digests and size bound |

The native run also merges the updated Android debug assets. Merged `wallpaper.js`
matches source SHA-256 `2622f0adb02bc882d8d2456954076d586d8314b2ea91df7698fa9cf16b526d18`.
The four newly reviewed renders are
[synced Account](cloud-acceptance-20261101/native/account-synced-light.png),
[offline Account](cloud-acceptance-20261101/native/account-offline-dark.png),
[200% font Account](cloud-acceptance-20261101/native/account-paused-font200.png) and
[Settings hub](cloud-acceptance-20261101/native/settings-hub-light.png).
The earlier 417 ordinary unit tests and five preview renders remain separate results;
they were not rerun or added to this selected-run count. The full optional visual suite
was not executed. No private-photo fixture was imported.

The exact selected Gradle command, failed attempts, durations, exit statuses, log digests,
JUnit cases and image hashes are in [the evidence manifest](cloud-acceptance-20261101/evidence.json).
Its path templates resolve to the published cloud runtime and disposable local inputs.

## Evaluated backlog

| Area | Evidence and current disposition | Next meaningful check |
| --- | --- | --- |
| Loading, empty and busy states | Source review of `ActivationScreen` and `FeedbackScreen` found endpoint/key validation, a submit busy guard, null-binding refusal and client-instance guards for late completion. Feedback intentionally leaves its brief binding lookup blank, then offers the existing unlinked explanation. No further runtime change was justified by this review. These screens were not exercised end to end by this follow-up. | Add focused navigation/binding-change UI fixtures if changing those flows; do not infer complete UI acceptance from source inspection. |
| Local draft isolation and stale completion | Six executed synthetic draft tests pass. Existing binding-derived storage and upload identity checks preserve another binding or a newer draft. | Device process recreation and keystore-backed binding changes remain unverified. |
| Motion and cleanup | 18 host and 19 real-browser checks cover host pause, reduced motion, elapsed-time glow, delayed callbacks, initialization and post-ready sky-update failures, and stopped clocks/layers. | Real Android WebView process loss/recreation, repeated navigation, background resource use and GPU/frame timing need instrumentation or a device. |
| Accessibility and presentation | Selected semantics, 200% font wrapping and four account/settings renders pass and were reviewed. Existing city artwork/localization is preserved. | Actual TalkBack traversal, hardware keyboard behavior, platform font/contrast settings, all city/UI combinations and WebView error appearance remain unverified. |
| Input and logging boundaries | The endpoint port regression is fixed; earlier parser/API tests remain recorded. This review found no additional managed/feedback runtime logging call requiring token redaction. No token or customer input was introduced into checked-in evidence. | Broader log sinks and every external input path require a separate systematic audit; absence of a call in these inspected files is not proof of complete redaction. |
| Local wallpaper resource boundary | Three executed asset-client tests reject nonlocal/network/traversal paths and verify the complete approved image set. The native component has no JavaScript bridge. | Browser and JVM fixtures do not establish behavior of every OEM WebView or a compromised Android process. |
| Supply chain | Distribution checksum enforcement is now implemented and both rejection/acceptance cases pass. Existing dependency versions and wrapper JAR are unchanged. | Review mutable `libghostty` snapshot, dependency locking/verification metadata and workflow action pins. Those source-level reviews remain feasible follow-up work; changing credentials, runner trust or network policy is a separate authorization decision. |
| Cross-candidate contracts | Server draft #12 runs actual compiled Android API classes against a disposable backend: 12 client scenarios and lifecycle assertions pass; no concrete schema/auth mismatch was found. | Native service reload/keystore, real proxy/origin behavior and a real engine are not covered. |
| Claude integration | A reviewable backend/website handoff exists in draft #12. No external agent was contacted. | Specify actor, purpose/scope, origin and transport before granting access. No credential, sharing or exposure change is authorized by this report. |

This pass exhausted the concrete failures reproduced in the selected accessible subset.
It does not exhaust the broader UI or security backlog. Additional runtime changes need
evidence from the remaining checks; cosmetic churn or speculative rewrites are not
supported by these results. No Windows build/test, signing, installation, public release,
production operation, customer database, merge or access grant occurred.
