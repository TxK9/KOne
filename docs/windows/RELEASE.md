# KOne Windows release checklist

Release builds may include localized client resources while the repository and
development environment remain English. Preserve resource keys and protocol
fields across localization. A translated source tree is not a release approval.

## Required acceptance

Before distribution, test the intended signed or explicitly reviewed artifact on
real supported x64 and ARM64 Windows devices as applicable:

1. Install the MSI with the expected publisher and architecture; launch the client.
2. Redeem a test invitation against an authorized test service. Verify credential
   protection and handling of revoked, expired and unassigned accounts.
3. Connect in TUN mode and check the actual egress. Exercise proxy/direct rules and
   assigned domain restrictions, including DNS and TLS/IP bypass paths.
4. Import a share link from the clipboard and from a file; connect through it and
   confirm the assigned domain restrictions still apply; remove it.
5. Exercise system-proxy mode. Confirm the prior settings are restored after
   disconnect, application exit, upgrade failure and uninstall.
6. Test tray, shortcuts, autostart, window visibility, DPI, both themes and the
   selected scene. Weather uses scene coordinates; audio remains opt-in.
7. Reopen the application, reboot and suspend/resume. Check service authentication
   and cleanup; an ordinary process must not gain an unrestricted service channel.
8. Upgrade in place, preserve account data, reject a downgrade and test update
   manifest/signature/hash/size failures. Test interrupted updates and recovery.
9. Uninstall and verify that the application's service, adapter and owned files
   are removed and unrelated network settings remain intact.

Record exact versions, artifact digests, platform, executed tests and failures.
CI success and source-level tests do not replace real-device acceptance.

## Build artifacts

Inspect the current Windows workflow for actual job and artifact names. Its
packaging outputs include architecture-specific MSI files, checksum files and a
website bundle. When present, `KOne-website`, `KOne-msi` and `KOne-msi-arm64` are
workflow artifacts, not evidence that they were publicly deployed. Artifact
retention and runner availability depend on repository settings.

Use HTTPS for distribution. Upload verified installer/checksum assets before
publishing a manifest that points to them. A hard-to-guess path and `noindex`
are not access control; use authentication where distribution must be private.

## External release configuration

Configure only the settings required by the release. Never commit secrets.

| Setting | Role |
| --- | --- |
| `KONE_CONSOLE` | Public HTTPS management service URL, if configured. |
| `KONE_SITE` | Application website metadata. |
| `KONE_DOWNLOADS` | HTTPS installer/update directory. |
| `KONE_UPDATE_KEY` | Update verification public key. |
| `KONE_UPDATE_SIGNING_KEY` | External private key for signing update manifests. |
| `KONE_SIGNING_ENDPOINT`, `KONE_SIGNING_ACCOUNT`, `KONE_SIGNING_PROFILE` | Optional code-signing service configuration. |
| `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` | External credentials used only by the approved signing workflow. |

Generate release keys only after explicit approval. Back up private keys securely,
keep them outside the repository and model workspace, and plan key rotation before
use. Verify the current key format and task name in the packaging source rather
than copying commands from historical notes.

Unsigned installers can trigger publisher or reputation warnings. Code signing
and SmartScreen reputation are separate checks; a signature does not guarantee
that Windows will suppress a warning. Check current vendor eligibility, terms
and pricing before selecting or purchasing a signing service. Do not bypass OS
security warnings automatically.

## Publication

Only publish after acceptance and explicit release authorization. Verify the
artifact fetched from the actual distribution URL against its expected SHA-256.
Publish the architecture-appropriate manifest and signature after the assets are
available, and test update/rollback using the supported release path. Never treat
an English documentation or packaging change as deployment authorization.
