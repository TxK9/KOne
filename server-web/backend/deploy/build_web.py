"""Bundle local ES modules before packaging; esbuild is a build-time tool only."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess

BASE = Path(__file__).resolve().parents[1]


def source_hash():
    paths = [BASE / 'web/app.js', *sorted((BASE / 'web/js').rglob('*.js'))]
    content = b''.join(p.relative_to(BASE).as_posix().encode() + b'\0' + p.read_bytes() + b'\0' for p in paths)
    return hashlib.sha256(content).hexdigest()


def verify():
    bundle = BASE / 'web/app.bundle.js'
    if not bundle.is_file() or not bundle.read_text(encoding="utf-8").startswith('// KOne source ' + source_hash() + '\n'):
        raise ValueError('Frontend bundle is stale; run deploy/build_web.py with esbuild before packaging')
    html = (BASE / 'web/index.html').read_text(encoding="utf-8")
    for path, fingerprint in re.findall(r'(?:src|href)="/([^"?]+)\?v=([0-9a-f]+)"', html):
        if hashlib.sha256((BASE / 'web' / path).read_bytes()).hexdigest()[:16] != fingerprint:
            raise ValueError('Asset fingerprint is stale: ' + path)


def build(esbuild):
    subprocess.run([str(esbuild), str(BASE / 'web/app.js'), '--bundle', '--minify', '--format=iife',
                    '--target=es2020', '--charset=utf8', '--outfile=' + str(BASE / 'web/app.bundle.js'),
                    '--banner:js=// KOne source ' + source_hash()], check=True)
    index = BASE / 'web/index.html'
    html = index.read_text(encoding="utf-8").replace('src="/app.js"', 'src="/app.bundle.js"')
    for path in ('app.bundle.js', 'app.css', 'theme.js', 'vendor/qrcodegen.js', 'favicon.svg'):
        fingerprint = hashlib.sha256((BASE / 'web' / path).read_bytes()).hexdigest()[:16]
        html = re.sub(r'((?:src|href)="/' + re.escape(path) + r')(?:\?v=[^"\s]+)?"', r'\1?v=' + fingerprint + '"', html)
    with index.open("w", encoding="utf-8", newline="\n") as output:
        output.write(html)
    verify()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--esbuild', type=Path, required=True)
    build(parser.parse_args().esbuild)
