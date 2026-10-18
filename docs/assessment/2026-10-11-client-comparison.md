# Android client architecture comparison

KOne builds on the native Android SFA client and the sing-box core. The existing
Kotlin/Compose application, VPN lifecycle and configuration interfaces are the
current integration boundary.

| Component | Role | Integration consideration |
| --- | --- | --- |
| SFA and sing-box | Native Android client and proxy core | Preserve lifecycle, configuration validation and routing behavior. |
| v2rayNG and Xray | Android node and subscription management | UI patterns can inform the design; core-specific configuration is not interchangeable. |
| Mihomo clients | Provider and policy-group workflows | Translate semantics explicitly before accepting another configuration format. |
| Cross-platform clients | Shared desktop/mobile presentation | Reuse only after checking platform lifecycle and licensing requirements. |

Keep node import, subscription parsing, routing selection and managed account
policy as separate layers. A protocol appearing in another client's documentation
does not establish compatible import behavior in this application.

Product changes need configuration fixtures, validation of invalid inputs,
service lifecycle checks and a release/rollback path. Source inspection alone
does not establish network reliability, power consumption or device compatibility.

Preserve upstream license and attribution notices. Any code reuse requires a
license review for the specific source and dependency version.

References: [SFA](https://github.com/SagerNet/sing-box-for-android),
[sing-box](https://github.com/SagerNet/sing-box),
[v2rayNG](https://github.com/2dust/v2rayNG),
[Mihomo](https://github.com/MetaCubeX/mihomo).
