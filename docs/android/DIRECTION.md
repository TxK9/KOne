# KOne for Android: where it stands and where it goes

KOne for Android is SFA (sing-box for Android) with KOne's own layer on top. This page is the
Android counterpart of `docs/windows/DIRECTION.md`: what is there, how it compares, and what is
built next. Claims of being better than other clients wait for device evidence.

## What it has

From upstream SFA: profiles (local and remote whole configurations), node groups with URL
tests, Clash modes, the connection list, logs, QR scan and generation, per-app proxy through
Android's VPN API, Tailscale (with Taildrop and SSH), OpenVPN and OpenConnect endpoints, speed,
network-quality and STUN tests, crash, OOM and power reports, a Quick Settings tile, Shizuku and
root privileges, and an update checker.

KOne's own layer:

- the console's managed configuration (activation by key, sync, the Claude block kept first);
- share-link import (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS);
- the ink-and-paper dashboard, country decks, node weather and exit address;
- Home (回家) over Tailscale, with private subnets;
- backup and restore.

## Where it was behind the Windows App (and the leading clients)

Until this round the template was fixed: no sites of the person's own, no rules, no ad
blocking, DNS fixed at 223.5.5.5 and 1.1.1.1, IPv4 only, and everything unmatched through the
node. v2rayNG, NekoBox, Clash Meta for Android and Hiddify all let people choose these.

Other gaps, in order of what people meet first:

1. No ordered rules of one's own (domain, IP range, port, app) and no category lists.
2. No Clash YAML or subscription-of-links import (whole-configuration remote profiles only).
3. No diagnosis that names the cause: network, sign-in page, clock, DNS, node.
4. No hosts or DNS for domains.
5. Android-specific causes nobody names: Private DNS set to a hostname, battery limits on the
   VPN, another VPN app as always-on.

## Built this round

- **Routing** (Settings → Routing): sites straight out or through the node, Block ads, what
  nothing matches, the remote and direct DNS servers, IPv6. Applied to every configuration
  KOne built, managed or from a share link, in one stretch of the template after the console's
  refusals, so no choice reaches round the Claude block. Own ordered rules (site, name ending,
  word, address range, app by package, port). A notice when Android's Private DNS answers apps
  past KOne's DNS. Rules pasted from Clash or v2rayN (an app by its package; a rule blocking
  the ad list turns on Block ads). All kept in backups. Built and unit-tested in CI (`android.yml`);
  not run on a device, which the owner has deferred until the App is ready for it.
- **Where you are**: outside China by default in the shared edition (no China split, Cloudflare
  as the direct DNS), China kept for the owner's edition and as a choice; switching back
  restores the template exactly.
- **One shared core** (`shared/`), approved by the owner: names, rule kinds and checks, the
  Clash and v2rayN rule import, the category catalog and list keeper, the Clash proxies reader,
  regions and diagnosis checks, built into both Apps from source and tested in the Windows
  build beside the Go consistency test.
- **Category lists** in rules (List: Netflix, YouTube, OpenAI, regions and the rest of the
  Windows catalog, or any SagerNet list by name). The phone keeps each list itself, checked by
  its own sing-box, and the configurations read local copies: a rule waits for its list, and
  says why, so a list that cannot be downloaded never stops KOne from starting. Refreshed daily
  by ETag; a list that arrives later reloads the service once.
- **Clash profile import**: a Clash profile on the clipboard imports each usable proxy as a
  node, as share links do, provider notices left out, through the shared reader.
- **Diagnose** (Tools): the network as Android judged it, another VPN, Private DNS, battery
  limits, Data Saver, the clock, names and sites, then the one thing to do.
- **Android CI**: the shared edition with the debug key, unit tests and an assembled build. No
  APK is uploaded; releases keep the authorized signing path in `tools/`.

## Next, without a decision

1. Hosts and DNS for domains.
2. Subscriptions of links or Clash profiles that refresh on their own, as on Windows.
3. Speed tests and smart selection by measured loss, from the Windows App.

## Needing the owner

| Proposal | Why | Cost or risk |
| --- | --- | --- |
| Allow `jitpack.io` and `api.xposed.info` in this development environment's network settings | Local Android builds; today only CI can build the App | None for the App; a development setting |
| A device test round | The one gap no code closes | Deferred by the owner until the App is ready |

Decided by the owner: one shared core (done); no new Chinese text, as the market is outside
China (the existing language packs stay); device testing later.
