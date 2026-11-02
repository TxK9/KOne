# Management access

Default listener: `127.0.0.1:8768`.
Additional management origins: repeat `--public-origin HTTPS_ORIGIN`.

Sessions use the `kone_session` cookie. Mutating administrator requests also require
`X-CSRF-Token`. Routing and origin checks are implemented in `server.py`.
