"""Private owner releases and revocable, download-only device credentials.

Artifacts live outside the web root. No credential is embedded in an APK or URL.
Only an authenticated console session can enroll or revoke a device.
"""
import hashlib
import json
import re
import secrets
import threading
import time
from pathlib import Path
from customers import CustomerError as APIError

APK_PATH = re.compile(r'/downloads/KOne-owner-b([1-9][0-9]{0,8})\.apk')
DEVICE_ID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}')

class OwnerUpdates:
    def __init__(self, store):
        self.store = store
        self.directory = store.data_dir / 'owner-updates'
        self._verified = None
        self._lock = threading.Lock()
        with store.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS owner_update_devices (
                device_id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL,
                created_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)''')
        store.restrict_files()

    def enroll(self, payload):
        identifier = payload.get('deviceId', '')
        name = payload.get('name', '自用手机')
        if not isinstance(identifier, str) or not DEVICE_ID.fullmatch(identifier):
            raise APIError(400, '设备标识无效')
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80 or any(ord(c) < 32 for c in name):
            raise APIError(400, '设备名称无效')
        token = 'kou_' + secrets.token_urlsafe(32)
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            count = db.execute('SELECT COUNT(*) FROM owner_update_devices WHERE revoked=0 AND device_id<>?', (identifier,)).fetchone()[0]
            if count >= 20: raise APIError(409, '请先移除不用的更新设备')
            db.execute('INSERT OR REPLACE INTO owner_update_devices VALUES (?,?,?,?,0)',
                       (identifier, name.strip(), hashlib.sha256(token.encode()).hexdigest(), int(time.time())))
        self.store.restrict_files()
        return {'credential': token}

    def authenticate(self, header):
        if not re.fullmatch(r'Bearer kou_[A-Za-z0-9_-]{43}', header or ''):
            raise APIError(401, '请在自用版管理中心登录以启用更新')
        digest = hashlib.sha256(header[7:].encode()).hexdigest()
        with self.store.connect() as db:
            row = db.execute('SELECT device_id FROM owner_update_devices WHERE token_hash=? AND revoked=0', (digest,)).fetchone()
        if row is None: raise APIError(401, '更新授权已失效，请在管理中心重新登录')
        return row[0]

    def devices(self):
        with self.store.connect() as db:
            return [dict(r) for r in db.execute('SELECT device_id AS id,name,created_at AS createdAt FROM owner_update_devices WHERE revoked=0 ORDER BY created_at DESC')]

    def revoke(self, identifier):
        if not isinstance(identifier, str) or not DEVICE_ID.fullmatch(identifier): raise APIError(400, '设备标识无效')
        with self.store.connect() as db:
            db.execute('UPDATE owner_update_devices SET revoked=1 WHERE device_id=?', (identifier,))
        return {'ok': True}

    def release(self):
        manifest = self.directory / 'release.json'
        if not manifest.exists(): return {'updateFormat': 1, 'state': 'empty'}
        try:
            if self.directory.is_symlink() or manifest.is_symlink() or manifest.stat().st_size > 65536: raise ValueError()
            value = json.loads(manifest.read_text(encoding='utf-8'))
            code = value['versionCode']
            if type(code) is not int or not 1 <= code <= 999999999: raise ValueError()
            path = '/downloads/KOne-owner-b%d.apk' % code
            if value['url'] != path or value['applicationId'] != 'cc.txwd.tianxiawudi.proxy' or value['channel'] != 'owner': raise ValueError()
            if value.get('updateFormat') != 1 or value.get('state') != 'ready' or value.get('approvedForDownload') is not True: raise ValueError()
            if type(value['bytes']) is not int or not 0 < value['bytes'] <= 256*1024*1024: raise ValueError()
            if not re.fullmatch(r'[0-9a-f]{64}', value['sha256']): raise ValueError()
            apk = self.directory / Path(path).name
            stat = apk.stat()
            if apk.is_symlink() or not apk.is_file() or stat.st_size != value['bytes']: raise ValueError()
            identity = (str(apk), stat.st_mtime_ns, stat.st_size, value['sha256'])
            with self._lock:
                if self._verified != identity:
                    with apk.open('rb') as stream:
                        digest_state = hashlib.sha256()
                        for chunk in iter(lambda: stream.read(64 * 1024), b''): digest_state.update(chunk)
                        digest = digest_state.hexdigest()
                    if digest != value['sha256']: raise ValueError()
                    self._verified = identity
            # Keep the same visible/manual update policy as the customer edition.
            value['automaticUpdate'] = False
            value['allowMeteredUpdate'] = False
            return value
        except (OSError, ValueError, KeyError, TypeError):
            raise APIError(503, '自用版安装包尚未准备好')

    def artifact(self, path):
        release = self.release()
        if release.get('url') != path: raise APIError(404, '此安装包已更新，请重新检查版本')
        return self.directory / Path(path).name, release
