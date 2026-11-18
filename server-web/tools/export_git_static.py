#!/usr/bin/env python3
"""Export reviewed Git blobs to deterministic static-release USTAR, without checkout.

This tool never fetches, checks out, builds, uploads, runs hooks, or publishes.
A receipt proves content identity, not approval or production acceptance.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile

BASELINE_COMMIT = '558f3e00eaba82e3412d7eb28e2047f59f5819e8'
PINNED_MANIFESTS = {
    'release.json': '93bf59c47e40d49bcb3303b9b23d11f2047842daf05494e64d2b57110f64e279',
    'windows-release.json': 'baa07187ee97665be6d17c56e535fb1c9f471c6d3d89b8906d7da9f72620690b',
}
PREFIXES = {'site': 'website', 'console': 'backend/web'}
SAFE_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9-]{0,47}\Z')
SAFE_COMPONENT = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,95}\Z')
COMMIT = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64})\Z')
SUFFIXES = {'.html', '.css', '.js', '.mjs', '.json', '.svg', '.png', '.jpg', '.jpeg',
            '.gif', '.webp', '.avif', '.ico', '.woff', '.woff2', '.ttf', '.map', '.txt'}
TEXT = {'.html', '.css', '.js', '.mjs', '.json', '.svg', '.map', '.txt'}
EXTENSIONLESS = {'world/vendor/suncalc-LICENSE'}
REQUIRED = {
    'site': {'index.html', 'release.json', 'windows-release.json'},
    'console': {'index.html', 'app.bundle.js', 'app.css', 'theme.js', 'favicon.svg', 'vendor/qrcodegen.js'},
}
LIMITS = {'site': 100 * 1024**2, 'console': 20 * 1024**2}
MAX_FILE = {'site': 25 * 1024**2, 'console': 8 * 1024**2}
MAX_ENTRIES = 5000


class ExportError(ValueError):
    """Stable, non-sensitive error code."""


def require(condition, code):
    if not condition:
        raise ExportError(code)


def sha256(value):
    return hashlib.sha256(value).hexdigest()


class Repository:
    def __init__(self, path):
        self.path = Path(path).resolve(strict=True)
        self.git = shutil.which('git')
        require(self.git is not None, 'git_not_found')
        # Avoid replacement objects and optional index locks. Never invoke a filter.
        self.command = [self.git, '--no-optional-locks', '--no-replace-objects', '-C', str(self.path)]

    def read(self, *arguments):
        result = subprocess.run(self.command + list(arguments), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, check=False)
        require(result.returncode == 0, 'git_object_read_failed')
        return result.stdout

    def commit(self, value):
        require(COMMIT.fullmatch(value) is not None, 'full_commit_sha_required')
        actual = self.read('rev-parse', '--verify', '--end-of-options', value + '^{commit}').decode('ascii').strip()
        require(actual == value, 'commit_object_required')
        return actual

    def members(self, commit, prefix, exclude_downloads=False):
        raw = self.read('ls-tree', '-r', '-z', '--long', '--full-tree', commit, '--', prefix + '/')
        members = []
        for record in raw.split(b'\0'):
            if not record:
                continue
            metadata, raw_path = record.split(b'\t', 1)
            mode, kind, oid, size = metadata.split()
            try:
                path = raw_path.decode('ascii')
            except UnicodeError:
                raise ExportError('static_path_must_be_ascii') from None
            require(path.startswith(prefix + '/'), 'git_prefix_mismatch')
            relative = path[len(prefix) + 1:]
            parts = relative.split('/')
            if any(part.startswith('.') for part in parts) or exclude_downloads and parts[0].lower() == 'downloads':
                continue
            require(kind == b'blob' and mode in {b'100644', b'100755'}, 'links_and_submodules_forbidden')
            require(size.isdigit(), 'git_blob_size_invalid')
            members.append((relative, oid.decode('ascii'), int(size)))
        return members


def validate_name(name, kind):
    parts = name.split('/')
    require(len(name) <= 240 and len(parts) <= 12 and
            all(SAFE_COMPONENT.fullmatch(part) is not None for part in parts), 'static_path_invalid')
    suffix = PurePosixPath(name).suffix.lower()
    require(suffix in SUFFIXES or kind == 'site' and (suffix == '.bin' or name in EXTENSIONLESS),
            'static_suffix_forbidden')


def validate_blob(name, content):
    require(not content.startswith(b'version https://git-lfs.github.com/spec/v1'), 'lfs_pointer_not_payload')
    if PurePosixPath(name).suffix.lower() in TEXT or name in EXTENSIONLESS:
        require(not content.startswith(b'\xef\xbb\xbf'), 'utf8_bom_forbidden')
        require(b'\r' not in content, 'noncanonical_crlf_in_git_blob')
        require(b'\0' not in content, 'nul_in_text_blob')
        try:
            content.decode('utf-8', errors='strict')
        except UnicodeError:
            raise ExportError('invalid_utf8_in_git_blob') from None


def collect(repository, kind, commit, source_root=''):
    require(not source_root or len(source_root) <= 240 and len(source_root.split('/')) <= 12 and all(SAFE_COMPONENT.fullmatch(part) for part in source_root.split('/')), 'source_root_invalid')
    prefix = (source_root + '/' if source_root else '') + PREFIXES[kind]
    members = repository.members(commit, prefix, exclude_downloads=kind == 'site')
    names, folded, entries, total = set(), set(), set(), 0
    for name, oid, size in members:
        validate_name(name, kind)
        require(name not in names and name.casefold() not in folded, 'duplicate_or_case_colliding_path')
        names.add(name)
        folded.add(name.casefold())
        entries.add(name)
        entries.update(str(p) for p in PurePosixPath(name).parents if str(p) != '.')
        require(size <= MAX_FILE[kind], 'single_file_limit')
        total += size
    require(REQUIRED[kind].issubset(names), 'required_assets_missing')
    require(total <= LIMITS[kind] and len(entries) <= MAX_ENTRIES, 'payload_limit')
    return sorted(members), total


def export(kind, identifier, repo, commit, output, source_root=''):
    require(kind in PREFIXES and SAFE_ID.fullmatch(identifier) is not None, 'export_arguments_invalid')
    repository = Repository(repo)
    commit = repository.commit(commit)
    members, payload_bytes = collect(repository, kind, commit, source_root)
    output = Path(output).resolve(strict=True)
    require(output.is_dir(), 'output_directory_required')
    archive_path = output / (kind + '-' + identifier + '.tar')
    receipt_path = output / (kind + '-' + identifier + '.receipt.json')
    require(not archive_path.exists() and not receipt_path.exists(), 'output_already_exists')
    created = []
    records = []
    try:
        with archive_path.open('xb') as target:
            created.append(archive_path)
            with tarfile.open(fileobj=target, mode='w', format=tarfile.USTAR_FORMAT) as archive:
                for name, oid, size in members:
                    content = repository.read('cat-file', 'blob', oid)
                    require(len(content) == size, 'git_blob_size_changed')
                    validate_blob(name, content)
                    digest = sha256(content)
                    if kind == 'site' and name in PINNED_MANIFESTS:
                        require(digest == PINNED_MANIFESTS[name], 'immutable_release_manifest_mismatch')
                    member = tarfile.TarInfo(name)
                    member.size, member.mode, member.mtime = size, 0o644, 0
                    member.uid = member.gid = 0
                    member.uname = member.gname = ''
                    archive.addfile(member, io.BytesIO(content))
                    records.append({'path': name, 'git_blob': oid, 'bytes': size, 'sha256': digest})
            target.flush()
            os.fsync(target.fileno())
        archive_digest = hashlib.sha256()
        with archive_path.open('rb') as source:
            for block in iter(lambda: source.read(1024 * 1024), b''):
                archive_digest.update(block)
        receipt = {
            'format': 1, 'kind': kind, 'identifier': identifier, 'commit': commit,
            'tree_prefix': (source_root + '/' if source_root else '') + PREFIXES[kind], 'source': 'git-objects-no-filters', 'worktree_ignored': True,
            'archive': archive_path.name, 'archive_bytes': archive_path.stat().st_size,
            'archive_sha256': archive_digest.hexdigest(), 'payload_bytes': payload_bytes,
            'file_count': len(records), 'files': records,
            'pinned_release_manifests': PINNED_MANIFESTS if kind == 'site' else {},
            'approval': 'candidate-only-not-approved-or-deployed',
        }
        with receipt_path.open('x', encoding='utf-8', newline='\n') as target:
            created.append(receipt_path)
            json.dump(receipt, target, indent=2, ensure_ascii=True)
            target.write('\n')
            target.flush()
            os.fsync(target.fileno())
        return receipt
    except BaseException:
        for path in reversed(created):
            path.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=tuple(PREFIXES))
    parser.add_argument('identifier')
    parser.add_argument('--source-root', default='', help='Relative source subtree, such as server-web, without checkout')
    parser.add_argument('--repo', required=True, type=Path)
    parser.add_argument('--commit', required=True, help='Full reviewed commit SHA; branch names are refused')
    parser.add_argument('--output', required=True, type=Path, help='Existing candidate-output directory')
    args = parser.parse_args()
    try:
        receipt = export(args.kind, args.identifier, args.repo, args.commit, args.output, args.source_root)
        print(json.dumps({key: receipt[key] for key in ['kind', 'commit', 'archive', 'archive_sha256', 'file_count', 'approval']}, sort_keys=True))
    except (ExportError, OSError, UnicodeError, tarfile.TarError) as error:
        print(json.dumps({'error': str(error) if isinstance(error, ExportError) else 'export_io_failed'}), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.dont_write_bytecode = True
    raise SystemExit(main())
