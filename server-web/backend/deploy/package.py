#!/usr/bin/env python3
"""Create a source-only immutable release. Explicit whitelist excludes runtime data."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zipfile

BASE = Path(__file__).resolve().parents[1]
VERSION = "0.3.9"
FILES = ["server.py", "config_import.py", "fleet.py", "customers.py", "feedback.py", "owner_updates.py", "README.md",
         "web/index.html", "web/app.css", "web/app.js", "web/app.bundle.js", "web/theme.js", "web/favicon.svg",
         "web/js/api.js", "web/js/core.js", "web/js/icons.js", "web/js/ink.js", "web/js/layer.js", "web/js/owner-download.js",
         "web/js/i18n.js", "web/js/store.js", "web/js/weather.js", "web/js/widgets.js",
         "web/js/pages/overview.js", "web/js/pages/servers.js", "web/js/pages/routes.js",
         "web/js/pages/customers.js", "web/js/pages/configs.js", "web/js/pages/records.js", "web/js/pages/feedback.js",
         "web/art/liberty-light.png", "web/art/liberty-dark.png", "web/art/goldengate-light.png", "web/art/goldengate-dark.png",
         "web/art/halfdome-light.png", "web/art/halfdome-dark.png", "web/art/rainier-light.png", "web/art/rainier-dark.png",
         "web/vendor/qrcodegen.js",
         "node/agent.py", "node/install.sh",
         "docs/fleet-first-run.md", "docs/private-access.md", "docs/quotation-sources.md", "docs/migration.md", "docs/customer-api.md",
         "deploy/package.py", "deploy/build_web.py", "deploy/install-center.py", "deploy/run-center.py", "deploy/update-center.py", "deploy/windows-update.cmd",
         "deploy/center_common.py", "deploy/maintenance.py", "deploy/nginx-node.conf.example",
         "deploy/cloudflared-api.yml.example", "docs/public-api.md", "docs/feedback-api.md", "deploy/feedback_agent.py"]

FILES += ['portable_import.py', 'passwall_import.py', 'vendor/__init__.py', 'vendor/PyYAML-LICENSE.txt', 'vendor/PyYAML-PROVENANCE.json', 'vendor/yaml/__init__.py', 'vendor/yaml/composer.py', 'vendor/yaml/constructor.py', 'vendor/yaml/cyaml.py', 'vendor/yaml/dumper.py', 'vendor/yaml/emitter.py', 'vendor/yaml/error.py', 'vendor/yaml/events.py', 'vendor/yaml/loader.py', 'vendor/yaml/nodes.py', 'vendor/yaml/parser.py', 'vendor/yaml/reader.py', 'vendor/yaml/representer.py', 'vendor/yaml/resolver.py', 'vendor/yaml/scanner.py', 'vendor/yaml/serializer.py', 'vendor/yaml/tokens.py']


def build(output):
    try:
        from deploy.build_web import verify
    except ModuleNotFoundError:
        from build_web import verify
    verify()
    output.mkdir(parents=True, exist_ok=True)
    payloads = {}
    for name in FILES:
        path = BASE / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("missing or symlinked source: " + name)
        payloads[name] = path.read_bytes()
    manifest = {"version": VERSION, "files": {name: hashlib.sha256(value).hexdigest() for name,value in payloads.items()}}
    payloads["release.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    archive = output / ("kone-console-" + VERSION + ".tar.gz")
    portable = output / ("kone-console-" + VERSION + ".zip")
    if archive.exists() or portable.exists():
        raise ValueError("This release archive already exists and will not be overwritten")
    with archive.open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
        with tarfile.open(fileobj=gz, mode="w") as tar:
            for name in sorted(payloads):
                info = tarfile.TarInfo("kone-console-" + VERSION + "/" + name)
                info.mode = 0o644
                info.mtime = 0
                info.size = len(payloads[name])
                tar.addfile(info, io.BytesIO(payloads[name]))
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(archive.suffix + ".sha256").write_text(checksum + "  " + archive.name + "\n")
    with zipfile.ZipFile(portable, "x", zipfile.ZIP_DEFLATED) as bundle:
        for name in sorted(payloads):
            info = zipfile.ZipInfo("kone-console-" + VERSION + "/" + name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, payloads[name])
    portable.with_suffix(".zip.sha256").write_text(hashlib.sha256(portable.read_bytes()).hexdigest() + "  " + portable.name + "\n")
    print("Created source release: " + str(portable))
    return archive, manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=BASE / "release")
    args = parser.parse_args()
    build(args.output.resolve())
