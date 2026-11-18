# KOne server

`website/` contains the public site; `backend/` contains the management UI and Python service.
Requires Python 3.12 and Node.js 24+. Run from `server-web/`:

```sh
npm install
python -B backend/server.py --host 127.0.0.1 --port 8768 --data-dir backend/var
python -B -m unittest discover -s tests
python -B -m unittest discover -s backend/tests -p "test_*.py"
npm run test:backend-js
npm run test:catalog
npm run test:website
python -B backend/deploy/build_web.py --esbuild node_modules/.bin/esbuild
```

Static exports, run from the repository root with an existing output directory:

```sh
python server-web/tools/export_git_static.py site TASK-ID --repo . --source-root server-web --commit FULL_COMMIT_SHA --output out
python server-web/tools/export_git_static.py console TASK-ID --repo . --source-root server-web --commit FULL_COMMIT_SHA --output out
```
