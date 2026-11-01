# Android UI candidate — 2026-11-01

The candidate continues local development commit `8014f6a66af2f1e20d120294058d8a90fe10ea07`
(build 10055). This is an unpublished integration line. Public build 10041 remains at
`f7a76ca0055ca4f4733d995cf9d7dcc0980cc2db`; the candidate is not a public release.

## Changes

- Fill 311 missing Simplified Chinese resource entries, including plurals and errors.
- Move appearance, dashboard, status, activation, node editing and sharing labels into
  paired resources. Show language names in their own language. Keep generated locale
  discovery so a new resource locale does not require a separate hardcoded picker list.
- Translate fixed managed states at the UI boundary. Preserve provider-authored reasons,
  profile names and stored identifiers. Format relative times using the app's resources.
- Remove the activation page's redundant introductory sentence; retain field labels,
  actions, failure states and recovery actions.
- Accept the console's integer/fractional Unix-seconds expiry values, including JSON
  exponent notation. Keep ISO/date compatibility and reject nonfinite, overflowing and
  excessive-exponent inputs. The quota period remains provider data (`YYYY-MM` in the
  current console); profile, plan and device names are not automatically translated.
- Give helicopter motion a finite drift value. The previous canvas path produced `NaN`
  coordinates; city density, timing and the existing reduced-motion behavior are unchanged.

The UI patch does not change authentication, routing policy, subscriptions, account
storage, the native core or server protocols. The reused `libbox.aar` is byte-identical
to the published baseline (`14c4e2cdc42e21641e62349ed943baf8796b2b0a346feb5dfd3a62fb0bcc21cf`).

## Validation

All 1,046 translatable default entries have a Simplified Chinese counterpart. XML parsing,
duplicate-key checks and format-placeholder parity pass. The baseline native rendering
suite passes five cases; the localized working preview passes nine. Eleven focused account
tests pass, including Unix-seconds precision, time zones and the expiry boundary. Four host
wallpaper checks pass, including canvas coordinates and lifecycle/reduced-motion behavior
for twelve city-profile cases. Final signed-artifact metadata is recorded separately.

Actual API 35 emulator frames show the account page in English/Chinese and light/dark at
320 dp / 1.15 font scale. The first language-dropdown stress fixture contained arbitrary
long option names; it is marked as stress evidence and does not claim support for those
languages. The generated release locale configuration lists English, Persian, Russian,
Simplified Chinese and Traditional Chinese. Only English/Simplified Chinese coverage was
completed in this patch; other existing packs retain English fallback.

The isolated visual app uses synthetic data and its own package. It provides evidence
for production Compose components, not physical-phone, VPN, account or server acceptance.
No production package has been installed or cleared.
