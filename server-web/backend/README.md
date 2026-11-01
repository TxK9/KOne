# KOne management service

Python 3.12 with no third-party runtime dependency. Bind local development to loopback and use a fresh temporary data directory. Production settings and customer data must never be copied into this repository or Windows.

The current production service is 0.3.8. Its business, authentication and database code are unchanged by the 2026-11-01 UI release. `web/` contains the accepted English/Simplified Chinese frontend and its reproducible module sources.

Run Python regressions with `python -B -m unittest discover -s tests -p "test_*.py"`. JavaScript/browser checks require Node and the test packages described in the root README. Rebuild the bundle with `python deploy/build_web.py --esbuild PATH_TO_ESBUILD_EXECUTABLE`; use the official esbuild executable from a local npm installation.

The source API documents describe existing behavior and may contain historical deployment examples. The repository root README and CLAUDE.md define the current AWS deployment boundary. Do not run historical installers on production.
