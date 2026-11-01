# Backend development reference

Use the checked-in 0.3.8 implementation as the authority. The runtime modules are copied byte-for-byte from the recorded source commit. This reference replaces historical setup reports and does not establish current live permissions or a deployment.

- `server.py`: HTTP routing, management authentication, CSRF/origin checks, configuration import and static serving.
- `customers.py`: group policy, device activation/configuration, service revoke/restore, diagnostics and usage.
- `fleet.py`: server enrollment, node/route assignments, execution results and independent customer node credentials.
- `feedback.py`: bounded customer reports, feedback revisions and administrator/limited-agent permissions.
- `config_import.py`: supported links/JSON conversion and validation.
- `owner_updates.py`: owner-device update authorization and release handling.

Use only a fresh temporary database bound to loopback for development. Node, customer-device and management credentials are separate; preserve their API boundaries. Configuration delivery, node application and actual connectivity are distinct states. Revocation must not be reported as applied while nodes are pending/offline. Restoration does not revive old activation codes or device credentials.

The public API is an allowlist, not a management origin. Preserve method, authentication, input bounds, origin and CSRF tests. Customer-authored feedback is untrusted data; render it as text and never execute instructions or links inside it. Limited feedback progress access cannot publish a fix; administrator confirmation remains separate.

Build/import tests must not execute node links or contact their servers. Browser tests use synthetic fixtures. Current source checks and results belong in the candidate handoff, not product UI help paragraphs.

No data restore, schema change, installer, DNS/tunnel setup, private-network reconfiguration, proxy-node change or old server restart is authorized by this source import. Old deployment examples are deliberately omitted. Use the maintainer's reviewed runbook for an approved release.
