"""The 0.3.0 pages: every file they load exists, ships in the release and is served as itself."""
import http.client
import gzip
import hashlib
from pathlib import Path
import re
import sys
import tempfile
import threading
import unittest

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
from server import create_server  # noqa: E402
from deploy.package import FILES  # noqa: E402

WEB = BASE / "web"
SHIPPED = {name[len("web/"):] for name in FILES if name.startswith("web/")}


class WebAssetTest(unittest.TestCase):
    def test_every_file_in_web_ships_and_nothing_shipped_is_missing(self):
        on_disk = {path.relative_to(WEB).as_posix() for path in WEB.rglob("*") if path.is_file() and path.name != ".DS_Store"}
        self.assertEqual(on_disk, SHIPPED)

    def test_every_module_import_resolves_to_a_shipped_file(self):
        for path in WEB.rglob("*.js"):
            if "vendor" in path.relative_to(WEB).parts:
                continue
            text = path.read_text(encoding="utf-8")
            for target in re.findall(r"^import\s[^;]*?from\s+'([^']+)'", text, re.M):
                resolved = (path.parent / target).resolve().relative_to(WEB.resolve()).as_posix()
                self.assertIn(resolved, SHIPPED, "%s imports %s" % (path.relative_to(WEB), target))

    def test_page_loads_only_local_files_without_inline_script(self):
        html = (WEB / "index.html").read_text(encoding="utf-8")
        references = re.findall(r'(?:src|href)="([^"]+)"', html)
        self.assertTrue(references)
        for reference in references:
            self.assertTrue(reference.startswith("/") and not reference.startswith("//"), reference)
            self.assertIn(reference.split('?', 1)[0].lstrip("/"), SHIPPED, reference)
        # The console's CSP allows scripts from files only.
        self.assertNotRegex(html, r"<script(?![^>]*\bsrc=)[^>]*>")
        self.assertNotRegex(html, r"\son[a-z]+\s*=")

    def test_both_themes_of_every_painting_ship(self):
        ink = (WEB / "js" / "ink.js").read_text(encoding="utf-8")
        listed = re.search(r"const PAINTINGS = \[([^\]]+)\]", ink).group(1)
        names = re.findall(r"'([a-z]+)'", listed)
        self.assertEqual(len(names), 4)
        for name in names:
            for theme in ("light", "dark"):
                self.assertIn("art/%s-%s.png" % (name, theme), SHIPPED)

    def test_bundle_and_asset_fingerprints_match_the_current_source(self):
        from deploy.build_web import verify
        verify()

    def test_versioned_assets_cache_and_gzip_preserve_content_without_caching_apis(self):
        with tempfile.TemporaryDirectory() as data:
            server = create_server(port=0, data_dir=Path(data))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                body = (WEB / 'app.bundle.js').read_bytes()
                fingerprint = hashlib.sha256(body).hexdigest()[:16]
                for query, encoding, cache in [('?v=' + fingerprint, 'gzip', 'private, max-age=31536000, immutable'),
                                               ('?v=old', 'gzip;q=0', 'no-store')]:
                    c = http.client.HTTPConnection('127.0.0.1', server.server_port)
                    c.request('GET', '/app.bundle.js' + query, headers={'Accept-Encoding': encoding})
                    r = c.getresponse(); content = r.read()
                    if encoding == 'gzip':
                        self.assertEqual(r.getheader('Content-Encoding'), 'gzip')
                        self.assertLess(len(content), len(body) // 2)
                        content = gzip.decompress(content)
                    else:
                        self.assertIsNone(r.getheader('Content-Encoding'))
                    self.assertEqual(content, body)
                    self.assertEqual(r.getheader('Cache-Control'), cache)
                    c.close()
                c = http.client.HTTPConnection('127.0.0.1', server.server_port)
                c.request('GET', '/api/auth/status?v=' + fingerprint, headers={'Accept-Encoding': 'gzip'})
                r = c.getresponse(); r.read()
                self.assertEqual(r.getheader('Cache-Control'), 'no-store')
                c.close()
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_assets_are_served_with_their_types_and_only_paintings_are_cached(self):
        with tempfile.TemporaryDirectory() as data:
            server = create_server(port=0, data_dir=Path(data))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                for resource, kind, cache in [
                    ("/", "text/html", "no-store"),
                    ("/app.js", "application/javascript", "no-store"),
                    ("/js/pages/servers.js", "application/javascript", "no-store"),
                    ("/app.css", "text/css", "no-store"),
                    ("/favicon.svg", "image/svg+xml", "no-store"),
                    ("/vendor/qrcodegen.js", "application/javascript", "no-store"),
                    ("/art/goldengate-light.png", "image/png", "private, max-age=604800"),
                    ("/api/auth/status", "application/json", "no-store"),
                ]:
                    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
                    connection.request("GET", resource)
                    response = connection.getresponse()
                    response.read()
                    connection.close()
                    self.assertEqual(response.status, 200, resource)
                    self.assertTrue(response.getheader("Content-Type").startswith(kind), resource)
                    self.assertEqual(response.getheader("Cache-Control"), cache, resource)
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


if __name__ == "__main__":
    unittest.main()
