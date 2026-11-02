# Disabled navigation, RTL states and bearer boundaries

This pass resumes draft PR #13 from `42edac0938dd4e53d2dcff85782df4d7b80291b4`.
Android's nominated base remains `0dd7a9ff0d78743d8910e05041bcbb07c7d598a5`.
Server PR #12 was read/tested at `7f8cead2323276304979bfa0a61fe705a7f76f70` and
was not modified. Potential concurrent Claude Code work is kept separate.

## Reproduced fixes

Commit `30f453a0f364ba51abcbbfcf3920c616277cf36f` adds an explicit enabled guard inside
the shared navigation callback. Before the fix, pointer input was blocked but a direct
semantic click invoked the disabled row's handler once. Afterward both actions are no-ops;
enabling the row still invokes the action once. This is a proven direct-semantics behavior,
not a claim that every real TalkBack implementation bypasses disabled state. The existing
privilege caller's dimmed presentation remains unchanged.

Commit `98046b919303f4a9d31fc13788d724c77da37b7b` validates claim credentials as strings
and validates bearer values before management/feedback transport. Previously, all four
invalid claim variants (CR/LF, spaces, oversized value, JSON object) returned credentials
that the caller could store. A malformed stored management credential escaped as a JDK
argument exception. Feedback attempted connection creation before rejecting malformed input.
Three regression tests failed before these guards and pass now.

Bearer values follow the [RFC 6750 header grammar](https://www.rfc-editor.org/rfc/rfc6750#section-2.1)
with a client-side 1–512 character bound. The size bound is a KOne defensive limit, not an
RFC requirement; current backend-issued values are 64 characters. Valid punctuation and
the 512-character boundary remain supported. Invalid claim responses yield the existing
server failure; malformed stored device credentials yield the existing revoked/reclaim
state before making a request. Feedback uses its fixed authorization failure without
creating a connection or scheduling a deadline.

The isolated JDK header probe confirms that header rejection happens before a connect call
and its argument exception contains the synthetic credential. The probe prints a boolean,
not the value. Source inspection also found the global crash handler writing throwable
stack/message fields. The new guard removes this demonstrated argument-failure path;
it does not establish general crash-report redaction or a real production credential leak.

## Matrix and outcomes

| Check | Actual result |
| --- | --- |
| Initial API matrix | 24 tests, 21 passed and three credential regressions failed |
| Initial native matrix | 10 tests, nine passed and the disabled semantic-click test failed |
| First combined corrected run | 43 passed: 18 native UI and 25 API checks |
| Final native matrix | 19 passed, zero failures/errors/skips |
| Final ordinary suite and unsigned compilation | 427 passed, zero failures/errors/skips |
| Updated actual-client/backend fixture | 12 client scenarios plus backend acknowledgement/lifecycle assertions passed |
| Focused RTL follow-ups | Badge/arrow case, content-direction probe and two loading punctuation cases passed |

The final native count includes eleven hub/selector/feedback cases, seven account cases and
one dashboard semantics case. The added state coverage verifies disabled pointer/semantic
actions, button/badge semantics, 200% font text layout, badge pixels outside text bounds in
both directions/themes, mirrored arrow presentation, loading-to-empty selection, feedback
busy/read-only input, failure disclosure, retry/draft preservation and image-removal indices.
No additional runtime layout or transition change was justified by those passing cases.

The error boundary keeps its exact 160/161-character behavior, preserves the existing
localized refusal fixture and normal provider wording, and excludes exact request secrets
on unmapped 500/503 failures. It does not claim encoded or partial-secret reflection protection.

Saved RTL frames use English fixture/resource text in an RTL layout. A separate layout probe
checks a real existing Persian resource and Latin paragraph/punctuation direction; no new
translation was authored and no text-direction runtime patch was warranted. This is not a
complete Persian/Arabic/Hebrew UI or OEM glyph-rendering certification.

Reviewed synthetic frames:

| State | Evidence |
| --- | --- |
| Disabled / enabled navigation | [Disabled](cloud-state-matrix-20261102/native/navigation-disabled.png), [enabled](cloud-state-matrix-20261102/native/navigation-enabled.png) |
| 200% font, light | [LTR](cloud-state-matrix-20261102/native/navigation-light-ltr-font200.png), [RTL](cloud-state-matrix-20261102/native/navigation-light-rtl-font200.png) |
| 200% font, dark | [LTR](cloud-state-matrix-20261102/native/navigation-dark-ltr-font200.png), [RTL](cloud-state-matrix-20261102/native/navigation-dark-rtl-font200.png) |
| RTL dashboard | [Loading](cloud-state-matrix-20261102/native/dashboard-rtl-font200-loading.png), [empty](cloud-state-matrix-20261102/native/dashboard-rtl-font200-empty.png) |
| RTL feedback | [Busy](cloud-state-matrix-20261102/native/feedback-rtl-font200-busy.png), [error](cloud-state-matrix-20261102/native/feedback-rtl-font200-error.png), [images](cloud-state-matrix-20261102/native/feedback-rtl-font200-images.png) |

The magenta badge is a synthetic geometric marker; product badge colors were not changed.
Frames show components in a disposable scroll container, not full application navigation.
The evidence manifest preserves failed/pass commands, JUnit cases, source/image/log hashes
and the unchanged wallpaper fingerprint. No real account, keystore, device or VPN was used.

## Nearby input/error/log review

The inspected activation/claim path bounds endpoint/link inputs, sends JSON without executing
links, and now validates response credentials before the storage boundary. Management requests
keep redirect refusal, bounded bodies, HTTP-derived states and report allowlists. Feedback
keeps bounded bodies, fixed system failures, receipt checks and its whole-request deadline.
The explicit activation-share action uses a fixed report shape and the shown failure; it
does not directly append the entered claim code. No new runtime logging call was found in
the management/feedback files themselves.

The global `CrashReportManager` remains a high-value broader redaction review: its raw
throwable stack and exception-reason storage should receive adversarial synthetic fixtures
before any wider sanitizer is designed. This pass guards the demonstrated malformed-header
path and does not silently claim the global sink is sanitized.

## Remaining evidence and decisions

Executable source work remains: a dedicated locale restart/persistence matrix below API 33,
full navigation/back-stack/permission-screen fixtures, coverage outside the selected state
matrix, global crash-report redaction and reviewed dependency provenance/all-variant policy.
These are not blocked by Claude access and are not credited as completed here.

Hardware evidence still needs an authorized test device/emulator: actual TalkBack/keyboard,
OEM WebView/process recreation, service/keystore restoration, frame cadence, memory/power,
rotation/insets and installation/recovery. Synthetic rendering does not prove smoothness.
Claude runtime access, credentials/grants, production and deployment remain separately gated.
No Windows build/test, new trust metadata, signing, installation, production operation,
environment change, customer database, merge or external Claude contact occurred.

[The collaboration/language/status handoff](CLOUD_COLLABORATION_HANDOFF_20261102.md) records
the exact source lines, current language coverage and source-only download version finding.
