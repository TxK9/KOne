# Cloud acceptance and city-motion candidate

This independent candidate starts from the nominated Android build 10056 source,
`codex/android-ui-20261101` at `0dd7a9ff0d78743d8910e05041bcbb07c7d598a5`.
Remote branch readback and the initial isolated worktree HEAD matched exactly.
The website/backend baseline was independently checked at
`b37275d9ec568c73a92b6bc0e7ef6805061b4ed9`; its acceptance/handoff is
[draft PR #12](https://github.com/TxK9/KOne/pull/12).

## Fresh execution

This check ran in a fresh instance of the selected published KOne cloud environment.
Exact environment identifiers are retained only in the internal task receipt.
Execution used its restored workspace-local JDK 21.0.12, Gradle 9.7.0, Android 37.1 SDK,
NDK 28.0.13004108, Go 1.26.10, Node 24.19.0 and Python 3.12.14. The wrapper actually
downloaded and ran its pinned Gradle distribution. SDK listing succeeded with a warning about
newer metadata XML. No dependency/version/network policy or environment configuration changed.

The existing core AARs were verified against the committed snapshot before copying them
into ignored `android/app/libs/`:

| Artifact | SHA-256 |
| --- | --- |
| `libbox.aar` | `14c4e2cdc42e21641e62349ed943baf8796b2b0a346feb5dfd3a62fb0bcc21cf` |
| `libbox-legacy.aar` | `3a9ccd47f73fc779aa311e062b6ab57fac599186404808b26bbb804ed418b804` |

No signing properties, personal wallpaper images, host profiles or account data were imported.
The shared edition supplies existing XML placeholders for personal resource IDs. Compilation
and unit tasks do not produce a signed installation candidate. Build/version metadata stays
10056 / 1.0.0. No APK or public installer was published.

## Changes and reproduced failures

1. Activation endpoints accepted port 0 and out-of-range ports. A new endpoint/claim-link unit
   test failed on the original parser. The parser now accepts omitted ports or 1 through 65535.
   A separate JDK loopback probe demonstrated an uncaught runtime failure for port 65536 at
   HTTP connection setup; this is host evidence, not a physical Android crash claim.
2. Browser visibility could resume a wallpaper explicitly paused by Android. Both a host
   regression and the real-browser visibility fixture failed before the fix. Host activity
   now remains a separate gate, and a resumed page cannot override a host pause.
3. The shared wallpaper clock kept advancing under browser reduced motion, even though
   individual decorative layers stopped. It now freezes and resumes without catching up.
   Initial and dynamically changed reduced-motion preferences reach the shared controller.
4. The initial city fade ran for 900 ms even with `motion=off`. It now lasts 480 ms with the
   same gentle arrival curve as the native motion vocabulary and is disabled for explicit or
   system reduced motion. Both reduced-motion browser cases failed before the fix and pass now.
5. Connection glow previously eased by a fixed amount on every paint, making its settling time
   depend on frame cadence and incidental redraws. It now uses elapsed animation time, settling
   in about half a second without advancing on resize/sky-only redraws. Host checks compare
   12 Hz and 60 Hz callback cadences and verify finite coordinates and cleanup.

City identities, original images, traffic profiles, artwork placement, authentication, routing,
native core, localization resources and the existing connection sweep are preserved. This is
focused motion/lifecycle polish, not a claim that the full client UI has reached final visual
or physical-device acceptance.

## Verification

From `android/`, after sourcing the published activation script:

```sh
bash ./gradlew --no-daemon --console=plain --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' -PkoneEdition=share \
  :app:compileOtherDebugKotlin :app:testOtherDebugUnitTest
```

| Check | Original nominated source | Final implementation |
| --- | --- | --- |
| Unsigned Kotlin/Java/resource compilation and unit task | Exit 0; 416 tests, 0 failures/errors/skips | Exit 0; 417 tests, 0 failures/errors/skips |
| Invalid-port regression alone | 3 tests, 1 expected failure before the fix | Included in the passing 417-test run |
| `node --test tools/test_android_wallpaper.cjs` | Four existing tests; extra pause reproduction failed | 11 passed after delayed-callback correction |
| Real Chromium wallpaper fixtures | 7 of 10 passed; pause and two reduced-motion cases failed | 13 of 13 passed after delayed-callback correction |
| Isolated native Compose preview | Not credited from earlier local reports | Five selected English cases passed under Robolectric API 35 native graphics |
| Selected Linux core packages | Not credited from setup-task history | Three top-level tests passed across two packages; five locale subtests also passed |

The original compile/unit run executed 36 Gradle tasks in 401.47 seconds. The final run executed
five tasks with 31 up to date in 63.83 seconds. Deprecation warnings remain, including the
legacy Android variant DSL; this does not establish Gradle 10 compatibility.

The Linux core command was
`go test -mod=readonly -p 2 -count=1 -v ./experimental/locale ./dns/transport/hosts`
from `core/`. No aggregate containing Windows checks was invoked.

The browser tool uses an ephemeral loopback static fixture and blocks all external page
requests. Install the reviewed `playwright@1.62.1` test dependency outside the checkout, then
run from the repository root with `$TEST_MODULES` selecting its module directory,
`$BROWSER` the local Chromium executable and `$EVIDENCE_DIR` a disposable output directory:

```sh
KONE_TEST_NODE_MODULES="$TEST_MODULES" \
KONE_TEST_BROWSER="$BROWSER" \
KONE_WALLPAPER_EVIDENCE_DIR="$EVIDENCE_DIR" \
node tools/test_android_wallpaper_browser.cjs
```

Six reviewed 320-by-692 browser frames cover New York, Tokyo and Las Vegas in day/night states.
They render the actual bundled wallpaper runtime without the native UI. Their source hashes,
runtime states and image hashes accompany this report. Browser frames are not Android WebView
or emulator screenshots. Frame counters, lifecycle assertions and cadence checks supply the
motion evidence; a static image alone does not establish animation correctness.

The five native cases cover Account light/dark, expired Account, Settings and long network
values at 320 dp / xhdpi. They use the existing preview manifest, platform Application and
synthetic presentation components. The first attempt failed before rendering because
Robolectric's download lock targeted the read-only default home. The retry passed after
`native-render-cache.init.gradle` redirected only the test JVM's home/Maven cache to a new
workspace directory and selected the official Maven Central repository. The inherited proxy
and TLS trust remained intact. This is a runtime cache workaround, not an application fix.
The native preview command and both outcomes are in the evidence manifest. No emulator,
installation, signed APK or production Application was involved.

Reviewed artifacts, all synthetic:

| Scene | Light/day | Dark/night |
| --- | --- | --- |
| New York browser wallpaper | [Day](cloud-acceptance-20261101/browser/original-day-320.png) | [Night](cloud-acceptance-20261101/browser/original-night-320.png) |
| Tokyo browser wallpaper | [Day](cloud-acceptance-20261101/browser/tokyo-bay-day-320.png) | [Night](cloud-acceptance-20261101/browser/tokyo-bay-night-320.png) |
| Las Vegas browser wallpaper | [Day](cloud-acceptance-20261101/browser/las-vegas-strip-day-320.png) | [Night](cloud-acceptance-20261101/browser/las-vegas-strip-night-320.png) |
| Native Account | [Light](cloud-acceptance-20261101/native/account-en-light-320.png) | [Dark](cloud-acceptance-20261101/native/account-en-dark-320.png) |

[Expired Account](cloud-acceptance-20261101/native/account-expired-en-light-320.png),
[Settings](cloud-acceptance-20261101/native/settings-en-light-320.png) and
[long network values](cloud-acceptance-20261101/native/network-long-en-light-320.png)
complete the selected native matrix. Runtime evidence summaries omit decorative prose captions;
the original bundled localization/artwork is untouched. The JSON evidence manifest records
full commands, statuses, durations, source/image/log digests and the separate JUnit totals.

### Follow-up motion progression and integration checks

The browser harness now has 12 checks. It scrubs the actual browser opacity transition's
animation clock through initial, intermediate and completed positions, verifies its 480 ms
duration, and samples the actual pulse controller through connection, intermediate glow,
host pause, unchanged paused state and resumed completion. This uses browser transition/state
sampling; it is not a pixel-based Android animation or physical-device benchmark.

An additional throttled-paint host regression exposed a timing edge case in the first glow
implementation: a late connection could consume elapsed time from before the request. It
failed before the refinement. The glow's elapsed-time origin now resets on a connection-state
change. All eight host checks and all 12 browser checks pass after that refinement, and the
Android debug asset merge succeeds. The progression samples are saved beside the earlier
frames. The native Kotlin/presentation code is unchanged; the earlier 417 unit and five native
render results remain separate evidence and were not rerun for this asset-only refinement.

The server handoff now includes a saved host-JVM harness that drives the actual compiled
Android API classes against the real disposable backend: 12 client contract scenarios and
backend lifecycle assertions pass. No concrete schema/auth incompatibility was found. It
does not exercise the native keystore, service reload, real origin/proxy routing or VPN.

Current checked-in evidence uses source-relative paths or named runtime/input/output templates.
Opaque environment identifiers and precise host paths remain only in internal task receipts.
This is a forward cleanup; existing candidate history has not been rewritten.

### Delayed-callback correction

Independent review found that resetting the glow origin to the last callback's `seconds`
still inherited the pre-event interval when the next callback was delayed. Two new strict
host regressions failed on the previous implementation: connecting at 999 ms after a callback
at zero, then delivering the callback at 1000 ms, produced a 0.7506478 glow even though only
one millisecond followed the event. An older queued timestamp also advanced the glow.

The clock now advances to the actual connection event's `performance.now()` under the old
target before switching targets. Queued timestamps cannot move the clock backward. Paused
and reduced-motion changes keep their deterministic settled state without starting a clock.
All 11 host checks pass, including the unchanged strict one-millisecond bound below 0.02.
An additional older-callback assertion waits for the permitted paint cadence before requiring
visible progress; it does not weaken the strict delayed-event bound.

The browser harness now has 13 checks. Its controlled callback regression records the event
at 999 ms and callback at 1000 ms: the corrected glow is 0.005540151995103254. The same current
browser harness against an independent copy containing the previous pulse source fails this
case (12 passes, one expected failure); the corrected source passes all 13. Existing real-time
progression also records wall-clock samples, host pause and resumed completion. This supplies
both a strict delayed-event check and normal browser progression evidence.

The Android debug asset merge passes and its pulse asset SHA-256 matches source:
`241412e5642394d2dac69bd53fc1b6fe7c54ccc602f3d7a7e69adc3329e4f190`.
Source/test correction commit: `87a66a3a636cc6aa90251ff57851f6ec254a2461`.
The six previously reviewed city frames are retained with their earlier source fingerprints;
the latest clock correction has separate source fingerprints and progression samples. Native
Kotlin/presentation code, the 417-unit/five-native evidence and the server candidate are
unchanged. No new native/device, signing, Windows, production or access check is claimed.

## Supply-chain and trust-boundary observations

The restored Gradle 9.7.0 ZIP matched its official SHA-256
`84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`.
An initial comparison of the checked-in wrapper JAR against the 9.7.0 wrapper checksum failed.
Investigation found that the JAR is unchanged from the original repository baseline and
exactly matches the official 9.6.1 wrapper checksum
`497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.
The [official checksum reference](https://gradle.org/release-checksums/) also lists that JAR
hash for 9.5.x and 9.6.x. This is an older official launcher targeting the pinned newer
distribution, not evidence of an unknown JAR. Both the failed comparison and the successful
provenance check remain in the evidence record.

Reviewed wrapper distribution checksum enforcement and dependency verification/locking remain
follow-up work; no newly downloaded hash was blindly made trusted metadata. A mutable
`libghostty` snapshot remains in the existing dependency declarations. The Android workflow is
manual-only with mutable major-version action refs; it was neither dispatched nor edited.
Exact-head remote CI absence must not be described as a green check.

The source review and synthetic tests cover device/admin separation, no bearer token in URL
paths, redirect refusal, bounded configuration/feedback responses, same-origin update URL
policy, APK size/hash checks and package/build/signer compatibility. Production signing,
installation recovery and full TUF-style metadata expiry/rollback are outside this evidence.
The server security/access handoff is in PR #12. Repository prose, feedback, model output and
generated commands remain untrusted inputs, and English text is not a security control.

## Limits

No physical device, VPN traffic, live account, remote management service, engine chain,
installation, signing, public release or production operation was exercised. No credentials,
permissions, network exposure, runner/workflow setting, Windows/Claude task or shared branch
was changed. Claude service access still requires the specific actor, purpose, origin and
transport decisions recorded in the server handoff. This task does not grant that access.
