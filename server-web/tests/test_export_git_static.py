"""Offline synthetic Git-object tests; never run against Windows or production."""
from contextlib import contextmanager
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

BASE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('exporter', BASE / 'tools/export_git_static.py')
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)
MANIFESTS = {'release.json': b'{"versionCode":10041}\n', 'windows-release.json': b'{"version":"1.0.70"}\n'}
PINS = {name: hashlib.sha256(value).hexdigest() for name, value in MANIFESTS.items()}


class GitExportTest(unittest.TestCase):
    def setUp(self):
        (BASE / '.test-work').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=BASE / '.test-work')
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.out = self.root / 'out'
        self.out.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Synthetic test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'core.autocrlf', 'false')
        files = {'website/index.html': b'<html lang="en">Test</html>\n',
                 'website/app.js': 'const label="中文";\n'.encode(),
                 'website/assets/model.bin': b'\xff\r\n\x00',
                 **{'website/' + name: value for name, value in MANIFESTS.items()},
                 **{'backend/web/' + name: b'test\n' for name in e.REQUIRED['console']}}
        for name, value in files.items():
            self.write(name, value)
        self.commit = self.save()
        self.pins = patch.dict(e.PINNED_MANIFESTS, PINS, clear=True)
        self.pins.start()

    def tearDown(self):
        self.pins.stop()
        self.temp.cleanup()

    def git(self, *args, data=None):
        result = subprocess.run(['git', '-C', str(self.repo), *args], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise AssertionError(result.stderr.decode('utf-8', errors='replace'))
        return result.stdout

    def write(self, name, value):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)

    def save(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'Synthetic fixture')
        return self.git('rev-parse', 'HEAD').decode().strip()

    def export(self, kind='site', identifier='test', commit=None):
        return e.export(kind, identifier, self.repo, commit or self.commit, self.out)

    def test_deterministic_ustar_and_receipt_are_portable(self):
        a = self.export(identifier='a')
        b = self.export(identifier='b')
        self.assertEqual(a['archive_sha256'], b['archive_sha256'])
        self.assertEqual((self.out / a['archive']).read_bytes(), (self.out / b['archive']).read_bytes())
        with tarfile.open(self.out / a['archive']) as archive:
            names = archive.getnames()
            self.assertEqual(names, sorted(names))
            for member in archive:
                self.assertTrue(member.isfile())
                self.assertEqual((member.mode, member.uid, member.gid, member.mtime), (0o644, 0, 0, 0))
                self.assertFalse(member.pax_headers)
                self.assertFalse(member.name.startswith('./'))
        content = (self.out / a['archive']).read_bytes()
        self.assertEqual(content[257:265], b'ustar\x0000')
        receipt = (self.out / 'site-a.receipt.json').read_bytes()
        self.assertNotIn(b'\r', receipt)
        self.assertNotIn(str(self.repo).encode(), receipt)
        self.assertEqual(json.loads(receipt)['commit'], self.commit)
        self.assertEqual(a['approval'], 'candidate-only-not-approved-or-deployed')

    def test_clean_crlf_checkout_exports_lf_git_objects(self):
        clone = self.root / 'crlf-clone'
        subprocess.run(['git', 'clone', '-c', 'core.autocrlf=true', '--no-hardlinks', str(self.repo), str(clone)],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertIn(b'\r\n', (clone / 'website/release.json').read_bytes())
        status = subprocess.check_output(['git', '-C', str(clone), 'status', '--porcelain'])
        self.assertEqual(status, b'')
        subprocess.run(['git', '-C', str(clone), 'checkout', '--force', 'HEAD'], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertIn(b'\r\n', (clone / 'website/release.json').read_bytes())
        clean = self.export(identifier='lf')
        crlf = e.export('site', 'crlf', clone, self.commit, self.out)
        self.assertEqual(clean['archive_sha256'], crlf['archive_sha256'])
        with tarfile.open(self.out / crlf['archive']) as archive:
            self.assertEqual(archive.extractfile('release.json').read(), MANIFESTS['release.json'])

    def test_dirty_and_untracked_worktree_are_ignored_not_repaired(self):
        original = self.export(identifier='before')
        self.write('website/release.json', b'CHANGED\r\n')
        self.write('website/injected.js', b'untracked')
        before = self.git('status', '--porcelain')
        result = self.export(identifier='after')
        self.assertEqual(result['archive_sha256'], original['archive_sha256'])
        self.assertEqual(self.git('status', '--porcelain'), before)
        self.assertEqual((self.repo / 'website/release.json').read_bytes(), b'CHANGED\r\n')
        self.assertEqual(self.git('rev-parse', 'HEAD').decode().strip(), self.commit)

    def test_full_commit_required_and_tree_objects_refused(self):
        for ref in ['HEAD', self.commit[:12], '--all', 'A' * 40]:
            with self.subTest(ref=ref), self.assertRaises(e.ExportError):
                self.export(commit=ref)
        tree = self.git('rev-parse', 'HEAD^{tree}').decode().strip()
        with self.assertRaises(e.ExportError):
            self.export(commit=tree)

    def test_manifest_changes_fail_and_partial_archive_is_removed(self):
        self.write('website/release.json', b'{"versionCode":99999}\n')
        changed = self.save()
        with self.assertRaisesRegex(e.ExportError, 'immutable_release_manifest_mismatch'):
            self.export(commit=changed)
        self.assertEqual(list(self.out.iterdir()), [])

    def test_real_manifest_pins_cannot_be_overridden_by_cli(self):
        self.pins.stop()
        with self.assertRaisesRegex(e.ExportError, 'immutable_release_manifest_mismatch'):
            self.export()
        self.pins.start()

    def test_noncanonical_git_text_is_refused_not_rewritten(self):
        for value, code in [(b'x\r\n', 'noncanonical_crlf'), (b'\xef\xbb\xbfx\n', 'utf8_bom'),
                            (b'\xff\n', 'invalid_utf8'), (b'x\x00', 'nul_in_text')]:
            with self.subTest(code=code):
                # Write a literal blob into the index without Git clean filters.
                oid = self.git('hash-object', '-w', '--stdin', data=value).decode().strip()
                self.git('update-index', '--add', '--cacheinfo', '100644', oid, 'website/app.js')
                tree = self.git('write-tree').decode().strip()
                commit = self.git('commit-tree', tree, '-p', self.commit, '-m', 'Literal test blob').decode().strip()
                with self.assertRaisesRegex(e.ExportError, code):
                    self.export(commit=commit)
                self.assertEqual(list(self.out.iterdir()), [])

    def test_binary_bytes_are_unchanged(self):
        result = self.export()
        with tarfile.open(self.out / result['archive']) as archive:
            self.assertEqual(archive.extractfile('assets/model.bin').read(), b'\xff\r\n\x00')

    def test_smudge_filters_are_not_invoked(self):
        self.write('.gitattributes', b'*.js filter=unexpected\n')
        commit = self.save()
        self.git('config', 'filter.unexpected.smudge', 'git version')
        result = self.export(commit=commit)
        with tarfile.open(self.out / result['archive']) as archive:
            self.assertEqual(archive.extractfile('app.js').read(), 'const label="中文";\n'.encode())

    def test_symlinks_and_submodules_are_refused(self):
        for mode, kind in [('120000', 'link'), ('160000', 'submodule')]:
            with self.subTest(kind=kind):
                oid = self.commit if mode == '160000' else self.git('hash-object', '-w', '--stdin', data=b'/etc/passwd').decode().strip()
                self.git('update-index', '--add', '--cacheinfo', mode, oid, 'website/unsafe.js')
                tree = self.git('write-tree').decode().strip()
                commit = self.git('commit-tree', tree, '-p', self.commit, '-m', 'Synthetic unsafe entry').decode().strip()
                with self.assertRaisesRegex(e.ExportError, 'links_and_submodules'):
                    self.export(commit=commit)

    def test_hidden_files_and_download_store_are_excluded(self):
        self.write('website/.private.txt', b'not payload')
        self.write('website/.cache/value.js', b'not payload')
        self.write('website/downloads/test.apk', b'not payload')
        commit = self.save()
        original = self.export(identifier='old')
        result = self.export(identifier='new', commit=commit)
        self.assertEqual(original['archive_sha256'], result['archive_sha256'])

    def test_unsupported_files_and_case_collisions_are_refused(self):
        self.write('website/install.py', b'print(1)\n')
        commit = self.save()
        with self.assertRaisesRegex(e.ExportError, 'static_suffix'):
            self.export(commit=commit)
        self.git('rm', '-q', 'website/install.py')
        # Index-only path makes this test valid on case-insensitive filesystems too.
        oid = self.git('rev-parse', self.commit + ':website/app.js').decode().strip()
        self.git('update-index', '--add', '--cacheinfo', '100644', oid, 'website/App.js')
        tree = self.git('write-tree').decode().strip()
        commit = self.git('commit-tree', tree, '-p', self.commit, '-m', 'Case collision').decode().strip()
        with self.assertRaisesRegex(e.ExportError, 'case_colliding'):
            self.export(commit=commit)

    def test_lfs_pointer_is_refused_in_binary_suffix(self):
        self.write('website/assets/pointer.png', b'version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 42\n')
        commit = self.save()
        with self.assertRaisesRegex(e.ExportError, 'lfs_pointer'):
            self.export(commit=commit)

    def test_existing_outputs_are_never_overwritten(self):
        self.export()
        with self.assertRaisesRegex(e.ExportError, 'output_already_exists'):
            self.export()
        self.assertEqual(len(list(self.out.iterdir())), 2)

    def test_console_required_files_and_namespace(self):
        result = self.export(kind='console')
        self.assertEqual({r['path'] for r in result['files']}, e.REQUIRED['console'])
        self.assertEqual(result['pinned_release_manifests'], {})
        self.git('rm', '-q', 'backend/web/theme.js')
        commit = self.save()
        with self.assertRaisesRegex(e.ExportError, 'required_assets'):
            self.export(kind='console', identifier='missing', commit=commit)

    def test_sizes_and_limits_are_checked_from_git_metadata(self):
        with patch.dict(e.MAX_FILE, {'site': 1}):
            with self.assertRaisesRegex(e.ExportError, 'single_file_limit'):
                self.export()
        with patch.dict(e.LIMITS, {'site': 1}):
            with self.assertRaisesRegex(e.ExportError, 'payload_limit'):
                self.export()

    def test_nested_github_source_root_has_identical_payload(self):
        first = self.export(identifier='root')
        for folder in ['website', 'backend']:
            import shutil
            shutil.copytree(self.repo / folder, self.repo / 'server-web' / folder)
        self.write('server-web/website/downloads/ignored.apk', b'ignored')
        commit = self.save()
        nested = e.export('site', 'nested', self.repo, commit, self.out, 'server-web')
        self.assertEqual(first['archive_sha256'], nested['archive_sha256'])
        self.assertEqual(nested['tree_prefix'], 'server-web/website')
        for root in ['../escape', '/absolute', 'a//b', '.git']:
            with self.assertRaisesRegex(e.ExportError, 'source_root_invalid'):
                e.export('site', 'invalid', self.repo, commit, self.out, root)

    def test_invalid_ids_and_paths_are_refused(self):
        for identifier in ['../escape', 'space here', '-option', 'x' * 49]:
            with self.assertRaises(e.ExportError):
                self.export(identifier=identifier)
        for path in ['../escape.js', '/root.js', 'a//b.js', 'a/../b.js', 'a' * 97 + '.js', 'one/' * 13 + 'x.js', '中文.js']:
            with self.subTest(path=path), self.assertRaises(e.ExportError):
                e.validate_name(path, 'site')


if __name__ == '__main__':
    unittest.main()
