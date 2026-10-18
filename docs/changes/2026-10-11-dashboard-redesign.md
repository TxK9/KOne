# Dashboard presentation notes

The dashboard combines a background layer with a compact connection panel. Keep
connection state, selected node, routing mode and essential traffic statistics
visible without covering the main visual subject.

- Use stable typography and restrained contrast treatment. Bright areas may need
  a translucent surface; dark backgrounds can retain a transparent presentation.
- Keep decorative motion separate from service state. Transitions should settle
  predictably and respect reduced-motion preferences.
- Keep connection actions, node selection and navigation recognizable in both
  light and dark palettes.
- Use synthetic profiles, documentation IP addresses and neutral artwork in
  repository previews. External images must remain local to the preview unless
  a separate import action is explicitly selected.

The [English design preview](../design/knine-dashboard-mockup.html) demonstrates
layout and interaction with synthetic data. It does not control a VPN, contact
an endpoint or establish device/network acceptance.

Production verification belongs to the exact Android build and its tests.
Historical screenshots or a browser prototype are not substitutes for service,
routing, lifecycle and accessibility checks.
