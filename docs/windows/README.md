# KOne desktop development

This branch contains the Kotlin/Compose application under `desktop/`, a Go
service and core configuration integration, Windows MSI packaging source, and
unit/integration tests. It is an implementation branch, not just an HTML mockup.
`preview.html` remains a synthetic browser reference rather than a live tunnel.

Inspect `desktop/build.gradle.kts`, `desktop/service/`, and `desktop/packaging/`
for current commands and requirements. Preserve the existing adapter/system-proxy
behavior, managed activation, update verification, and account-policy boundaries.
The English source cleanup must not import unrelated Android functionality.

Use English UI labels and accessible health text. Country-name aliases remain
functional input compatibility data. Scene identifiers, geometry, motion, and
image paths are independent of their translated display labels.

Build and test on the appropriate platform before release. Windows installation,
service lifecycle, TUN/system-proxy behavior, tray integration, protocol links,
update/rollback, suspend/resume, and uninstall cleanup require separate evidence.
Source presence and a successful static merge do not establish these results.

Keep release keys, device credentials, private network values, and personal
photographs outside the repository. Preserve all upstream font and code licenses.
Do not deploy, publish, or change active network settings as part of translation.
