# Fleet API

- `POST /api/fleet/enrollments`: create node enrollment.
- `POST /api/node/register`: register a node.
- `POST /api/node/poll`: poll for work.
- `POST /api/node/result`: submit execution results.
- `POST /api/node/usage`: submit usage.

Registered nodes use `Authorization: Bearer <node-token>`.
Implementation: `fleet.py`, `node/agent.py` and `server.py`.
