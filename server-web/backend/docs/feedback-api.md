# Feedback API

- `GET|POST /api/client/feedback`: device feedback, authenticated with the device token.
- `GET /api/feedback`: administrator listing.
- `GET /api/feedback/inbox`: limited-agent listing.
- `POST /api/feedback/{id}/status`: update status.
- `GET /api/feedback/images/{uuid}`: retrieve an authorized image.

Implementation and role checks: `feedback.py` and `server.py`.
