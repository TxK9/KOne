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
  Clash and v2rayN rule import, the category catalog and list keeper, share links, subscriptions and the Clash proxies reader, hosts and DNS servers for domains,
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
- **Names** (Settings → Routing): fixed answers, and DNS servers of one's own for the names
  under a domain, after the console's refusals, kept in backups.
- **Subscriptions**: a remote profile's link may be a provider's subscription (links, base64,
  a Clash profile or a sing-box node list). It becomes KOne's own template around those nodes
  and refreshes on the remote profile's interval; a whole sing-box configuration is kept as sent.
- **Choosing a node**: Automatic (the fastest node that answers) leads every profile with
  several nodes, and one per country with two nodes or more ("Automatic · Japan"). Nodes in a
  group keep their provider's names.
- **Fake IP** as an option (Settings → Routing → DNS), mappings kept across restarts.
- **Rules, easier to keep**: tap a rule to change where it goes; copy every rule as Clash rule
  lines, which KOne for Windows (or another client) reads back.
- **Rules through a country** (Settings → Routing → Rules → Country): the fastest of that
  country's nodes carries the rule's traffic in Rule mode ("Through Japan", tested as
  Automatic is), and its names are looked up there too. A country no node says goes through the
  node in use, and the rule says so. The same rule as on Windows.
- **Why** each connection went its way, in the connection's details.
- **Subscriptions, finished**: words that leave nodes out per subscription, and a notification
  once when one has ended, ends within three days or has used 90% of its data.
- **From a connection**: its site sent straight out or through the node with one press.
- **Diagnose** copies a plain-text report (no key, no node addresses), as Windows does.
- **Hosts** take a pasted hosts file.
- **Subscription links** scanned or copied open as a new remote profile, KOne's subscription:
  an https link, or another client's import link holding one (clash://install-config,
  hiddify://import), as sing-box's own import links already did.
- **Android CI**: the shared edition with the debug key, unit tests and an assembled build. No
  APK is uploaded; releases keep the authorized signing path in `tools/`.

## Next, without a decision

1. Site check (try a site straight out and through the node), as on Windows; how the VPN treats
   KOne's own traffic is best confirmed on a phone first.
2. Pause for 15 minutes, then connect again, as on Windows; starting the VPN again from the
   background is subject to Android's limits on starting services, so also after a device check.

## Needing the owner

| Proposal | Why | Cost or risk |
| --- | --- | --- |
| Allow `jitpack.io`, `api.xposed.info` and `central.sonatype.com` in this development environment's network settings | A full local Android build; today the App is compiled here with those four libraries left out (every other error would show), and only CI builds it whole | None for the App; a development setting |
| A device test round | The one gap no code closes | Deferred by the owner until the App is ready |

Decided by the owner: one shared core (done); no new Chinese text, as the market is outside
China (the existing language packs stay); device testing later.
