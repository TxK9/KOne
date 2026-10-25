# Android everyday flows: settings, account, updates, feedback

Android screens and UI resources only. No shared core (`shared/`), desktop, build
configuration, signing or update-feed change.

## What changed

- **Settings** is grouped: Account and App at the top (App shows the version, or
  "Update available" with a dot), then Connection (Service, Routing, Profile
  Override), Advanced (Remote Control, Privileged Enhancement) and Help
  (Feedback). Rows share `KnineRowGroup`/`KnineNavRow`
  (`compose/component/KnineRows.kt`): one accent icon, a chevron or a dot in the
  same trailing slot, presses clipped by the card instead of per-row 12 dp
  corners, `Role.Button`, and a spoken state for the dot.
- **Account** (customer edition, linked copies only, `settings/account`): the
  binding's state as weather with the console's reason one tap away, plan,
  route, data allowance, device limit, end date (ending within three days or
  ended shows its weather), this device's name, Sync now, Feedback and Unlink
  (confirmed; uses the existing `ManagedSync.unbind`, after which the App returns
  to the access key page by itself). Pure date/weather rules are in
  `AccountFacts.kt` with JVM tests.
- **Feedback** is offered only where it can be sent (a linked customer copy).
  An older saved entry elsewhere explains why instead of showing a dead row. The
  description shows its length near the 4,000-character limit. All labels are
  resources.
- **Updates**: App settings has a Check for updates row whose line says
  Checking…, the release and its phase, Up to date, Cannot check, or (owner
  edition without enrolment) that signing in to the console is needed. The
  version row's tap now opens Copy rather than secretly checking. The Settings
  tab and Settings row raise the dot for KOne's own feed, not only the upstream
  checker that KOne builds do not use. The console's toast points to the new row.
- **Tools**: shared rows and chevrons; the tab no longer counts crash, OOM and
  power reports, whose pages are deliberately unreachable, so the badge could
  never clear. Taildrop counts remain.
- **Rows that show a value** (`TestRow`, used by network tests, feedback, the
  update prompt and Account) give the title up to 60% of the width when the two
  collide; values wrap and ellipsize instead of cutting titles to a letter at
  large text sizes. Rows with details announce "Show details"/"Hide details".
- **Import messages** use plurals ("Imported 1 node") and the empty clipboard
  says what to do next.
- Remaining English literals on these screens moved to `kone_hubs.xml` and
  `kone_account.xml`. No translations were added; other locales fall back to
  English.

## Deliberately not done

- No diagnostics toggle: `/api/client/diagnostics` waits for its approved
  disclosure and consent design (`docs/windows/README.md`). `ManagedState`
  keeps diagnostics off by default.
- Dashboard deck and switcher internals still carry some English literals
  (category editor, copy toasts, "System HTTP proxy").

## Cross-module needs

- Console contract: document `policy.expiresAt` (Android reads an ISO-8601
  instant, local date-time or bare date) and the `period` vocabulary, and whether
  `quotaBytes = 0` means unlimited (Android hides the row).
- Console: usage so far is not delivered, so Account shows only the allowance.
- Console: unlinking leaves the device record until it is revoked there, so it
  still counts toward the device limit. A client release endpoint would free it.
- Windows: the same Account summary and update-row wording would keep the
  clients aligned.
