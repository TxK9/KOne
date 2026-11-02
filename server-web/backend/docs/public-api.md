# Public API

`--api-origin` selects the restricted public API origin.
Allowed routes and methods are defined by `PUBLIC_API_ROUTES` in `server.py`;
owner APK paths are matched separately. Other paths are rejected on this origin.

Proxy templates: `deploy/cloudflared-api.yml.example` and `deploy/nginx-node.conf.example`.
