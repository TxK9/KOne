# Customer API

- `POST /api/client/claim`: redeem an activation code.
- `GET /api/client/config`: retrieve device configuration.
- `POST /api/client/ack`: acknowledge configuration.
- `POST /api/client/diagnostics`: submit diagnostics.

Device requests after activation use `Authorization: Bearer <device-token>`.
Implementation: `customers.py` and `server.py`.
