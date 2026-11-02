# Navigation clarity and management error boundaries

This additive pass resumes [draft PR #13](https://github.com/TxK9/KOne/pull/13) from
`851833c01f8e1fdd49a9c4467d24c861e5d6a461`. Remote candidate/base readback matched the
saved heads before editing. The nominated Android base remains
`0dd7a9ff0d78743d8910e05041bcbb07c7d598a5`; server draft #12 remains untouched at
`7f8cead2323276304979bfa0a61fe705a7f76f70`.

The selected published cloud runtime resumed successfully with its enforced restricted
network policy. Actual Java/Node/Python commands confirmed restoration; Gradle 9.7.0
performed unsigned compilation and tests. Native fixtures use Robolectric API 35 native
graphics, the platform Application and synthetic state, without the production Application.

## Cohesive UI pass

The dashboard selector, account content and shared Settings/Tools navigation rows were
inspected in source and actual native synthetic renders. The resulting changes stay within
the existing visual language: accent icons, grouped surfaces, subdued supporting text and
the original city artwork.

Commit `e36fe99990d6207749a0bccde2b124e0d04a6e72` gives navigation titles a medium weight,
keeps complete labels instead of a two-line ellipsis, and uses explicit 12/8 dp gaps around
the text column. The extra text width keeps the existing "Privileged Enhancement" label
whole at both normal and 200% font size. Rows retain a stable trailing badge/chevron slot,
button actions, badge semantics and a 64 dp minimum layout height.

The folded dashboard now distinguishes loading from an empty node list with the existing
localized loading label and a small native progress indicator. Settled empty state retains
the existing choose-node action. Its minimum layout height is explicitly 48 dp. The prior
40 dp layout measurement does not prove the platform's expanded touch region was too small.
No route, city identity, asset, localization string, version or account behavior changed.

| Synthetic component at 320 dp | Before | After |
| --- | --- | --- |
| Hub, light | [Before](cloud-polish-20261102/before/hub-light.png) | [After](cloud-polish-20261102/after/hub-light.png) |
| Hub, dark | [Before](cloud-polish-20261102/before/hub-dark.png) | [After](cloud-polish-20261102/after/hub-dark.png) |
| Hub, 200% font | [Truncated label](cloud-polish-20261102/before/hub-font200.png) | [Complete label](cloud-polish-20261102/after/hub-font200.png) |
| Dashboard loading | [Empty label](cloud-polish-20261102/before/dashboard-loading.png) | [Loading state](cloud-polish-20261102/after/dashboard-loading.png) |
| Dashboard empty | [Before](cloud-polish-20261102/before/dashboard-empty.png) | [After](cloud-polish-20261102/after/dashboard-empty.png) |

All ten before/after frames were inspected. The empty-state pixels are identical: its
additional layout space does not move the existing text/art. An intermediate render
restored the complete large-font label but orphaned its final letter; the final gap
refinement removes that awkward break. These are component renders, not complete navigation
or physical-device screenshots, and do not establish device animation smoothness.

## Focused security changes

Commit `5997beb0dc411eb23d07a3e9b185e810b05ff57d` addresses a reproduced client boundary:
arbitrary server `error` text could enter exception/detail fields later used by activation
or persisted management status. Four stand-in HTTP regressions fail before the guard:
reflected device credential, reflected claim code, control characters and an oversized reason.
Failure details now reject the current request's exact credential/claim code, control
characters and text over 160 characters. Existing short provider-authored reasons and
localized system messages remain supported. Rejected detail uses the existing fixed failure
message; error text cannot change the HTTP-derived authorization state.

This is a bounded reflection/storage/display safeguard. It is not a general detector for
every secret, encoded reflection or every logging sink. Source search found no additional
runtime logging call in the inspected management/feedback/update files requiring a patch.
No claim of complete logging or authentication audit follows from that search.

The same commit restricts the fallback JitPack repository to the two declared GitHub groups
and the Xposed repository to its declared API group. The Sonatype snapshot group filter
already existed. This reduces unrelated artifact lookups/fallback opportunities; it is not
exclusive source pinning or artifact authentication. Versions, repository URLs, network
policy and credentials are unchanged.

## Executed checks

| Check | Result |
| --- | --- |
| First new fixture compilation | Failed: three invalid generic calls to the installed Robolectric API in the new test helper; fixed before any render result was credited |
| Initial before run | 15 tests: 10 passed, 5 failed; three UI regressions and two then-reached security regressions; five baseline frames saved |
| Focused security reproduction | 11 API tests: 7 passed, four new regressions failed; the claim reflection fixture uses HTTP 409 so its response body is actually read |
| First implementation compile/selected checks | Passed: 24 tests, zero failures/errors/skips (13 native UI and 11 API tests) |
| Final refined UI compile/native checks | Passed: 13 tests, zero failures/errors/skips (five new hub/selector, seven account, one dashboard semantics) |
| Final ordinary unit suite and unsigned compilation | Passed: 421 tests, zero failures/errors/skips |
| Updated compiled client / disposable backend contract | Passed: 12 client scenarios plus backend acknowledgement/lifecycle assertions |
| Dependency lock write/read feasibility | Both passed using a temporary external lock and strict readback; no active lockfile or generated trust metadata committed |
| Before/final dependency inventory | Identical: 265 modules, 190 artifact observations, three changing snapshot modules |

The final ordinary run uses the temporary strict lock and offline restored cache for the
selected runtime configuration. Earlier compile/selected runs used the normal published
network configuration after the repository filters. This does not prove a new machine with
empty dependency caches can resolve every build variant. Existing Gradle/Compose test API
deprecations remain. No release, signing, installation or Windows task was invoked.

[The new evidence directory](cloud-polish-20261102/) contains sanitized commands, outcomes,
log digests, source/image hashes, selected JUnit cases, aggregate counts and dependency
observations. Original logs and exact runtime receipts stay outside Git. The updated compiled production client also passes the existing disposable backend harness,
including paused/expired/revoked states and wrong-audience rejection. It does not exercise
native keystore, service reload or real-origin/proxy routing. Previous wallpaper
18-host/19-browser evidence is retained separately and was not rerun: those JavaScript
assets are byte-identical to the preceding candidate.

## Locking and verification feasibility

The isolated real-project probe wrote a lock for `otherDebugRuntimeClasspath` outside the
checkout, then successfully read it in strict mode during the final compile/unit run.
Gradle explicitly warned that the three `libghostty` `0.1.0-SNAPSHOT` coordinates can retain
their version while changing contents. It also emitted an empty settings-catalog lock;
that experimental file was retained externally and removed from the source checkout.

Version locking is technically feasible for this configuration, but cannot supply artifact
integrity for changing snapshots. See the [official locking guidance](https://docs.gradle.org/current/userguide/dependency_locking.html).
An adoption pass still needs a reviewed immutable replacement or reviewed verification
provenance for those artifacts and coverage across runtime/compile/test/plugin and supported
variants. The 190 local SHA-256 observations are evidence, not trusted verification metadata.
The [Gradle verification guidance](https://docs.gradle.org/9.7.0/userguide/dependency_verification.html)
requires review of bootstrap results; automatically recording current cache hashes would
not establish independent trust. No generated verification file was enabled.

## Remaining work and gated decisions

Independent follow-up remains possible: wider navigation/disabled/badge/RTL fixtures,
additional loading/error flows, broader log/input review, dependency provenance/variant
coverage and reviewed workflow action pins. This pass improves shared hub presentation and
one dashboard state; it does not finish the entire Android UI or security backlog.

Actual TalkBack traversal, OEM WebView behavior, full city/UI combinations, service reload,
keystore/process recreation, VPN traffic, installation recovery and device frame/resource
measurements remain unverified. No device result is credited from Robolectric.

Claude integration is separately gated on the named actor, allowed operations/data scope,
website/admin origins, transport/session method and lifecycle/revocation expectations.
Deployment additionally needs an explicit target environment, release artifact/signing path,
approval owner and rollout/recovery decision. The existing server contract/handoff in draft
#12 is ready for review; this task neither grants access nor merges/deploys anything.
No production/customer database, secret provisioning, public release, external Claude contact,
Windows/Claude task, environment configuration or paid infrastructure was changed.
