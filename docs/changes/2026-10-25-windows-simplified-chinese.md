# Windows Simplified Chinese release

The Windows interface now has a UTF-8 Simplified Chinese message catalog with
English fallback. Activation, navigation, settings, node/rule forms, diagnostics,
connection notices, tray menus and accessibility descriptions use the same
language choice. The title bar exposes the choice before activation; Settings
stores it for subsequent launches. Provider-owned names, credentials, protocol
values and configuration keys keep their original contents.

The release is built with `-Pkone.language=zh-CN`, so an existing installation
without a saved language preference opens in Chinese after upgrading. System
default and English remain available. Chinese uses Microsoft YaHei UI on Windows
and an installed CJK fallback during development. The installer uses WiX zh-CN
resources, language 2052 and the corresponding UI font/codepage.

The version row now displays the actual installer version. Production builds
also receive the existing customer API address, an architecture-specific update
manifest URL and the public update-signing key in
`desktop/packaging/update-public-key.txt`. The private half remains with the
release operator. Development builds retain their explicit opt-in update setup.
The existing updater verifies Ed25519 manifest signatures and the complete MSI
hash before installation. MSI Authenticode signing is independent of the update
manifest signature.

Validation includes catalog placeholder integrity, English fallback, preference
persistence, live language changes, unchanged protocol values, accessibility
regressions and real Compose rendering at 100%, 150% and 200% in light and dark
themes. Native Windows installation/upgrade and publication receipts are recorded
separately from host JVM checks. This release does not alter Android or the
management service.
