"""Device-scoped feedback and a narrowly scoped automation inbox.

Feedback is untrusted text, never a command, instruction, URL to fetch or template.
This module deliberately does not collect logs, node details, images or credentials.
"""
import base64
import binascii
import struct
import hashlib
import hmac
import json
import re
import secrets
import time
import uuid
from datetime import datetime, timezone
from urllib.parse import parse_qs

from customers import CustomerError


CATEGORIES = {"connection", "speed", "qr", "display", "other"}
NETWORKS = {"wifi", "cellular", "ethernet", "offline", "unknown"}
STATUSES = {"new", "investigating", "needs_info", "ready", "resolved"}
AGENT_STATUSES = {"investigating", "needs_info", "ready"}
FIELDS = {"requestId", "category", "description", "appVersion", "versionCode",
          "androidVersion", "manufacturer", "model", "network"}
PAGE_SIZE = 50


MAX_IMAGE_BYTES = 192 * 1024

def image_bytes(value):
    if not isinstance(value, dict) or set(value) != {'mime', 'data'} or value['mime'] != 'image/jpeg' or not isinstance(value['data'], str):
        raise CustomerError(400, "图片格式无效")
    if len(value['data']) > (MAX_IMAGE_BYTES + 2) // 3 * 4:
        raise CustomerError(413, "图片过大")
    try:
        data = base64.b64decode(value['data'], validate=True)
    except (ValueError, binascii.Error):
        raise CustomerError(400, "图片编码无效")
    if not 4 < len(data) <= MAX_IMAGE_BYTES or not data.startswith(b'\xff\xd8') or not data.endswith(b'\xff\xd9'):
        raise CustomerError(400, "请使用有效图片")
    # Check JPEG frame dimensions before serving; do not decode unbounded pixel data on the server.
    at, dimensions = 2, None
    while at + 4 <= len(data):
        if data[at] != 255: break
        while at < len(data) and data[at] == 255: at += 1
        if at >= len(data): break
        marker = data[at]; at += 1
        if marker in (0xD9, 0xDA): break
        if marker in (0x01, *range(0xD0, 0xD8)): continue
        length = int.from_bytes(data[at:at+2], 'big')
        if length < 2 or at + length > len(data): break
        if marker in (0xC0, 0xC1, 0xC2) and length >= 8:
            height, width = struct.unpack('>HH', data[at+3:at+7]); dimensions = (width, height)
            break
        at += length
    if not dimensions or min(dimensions) < 1 or max(dimensions) > 2048:
        raise CustomerError(400, "图片尺寸无效")
    return data


def clean_text(value, maximum, minimum=0, multiline=False):
    if not isinstance(value, str):
        raise CustomerError(400, "反馈文本格式无效")
    value = value.strip()
    if not minimum <= len(value) <= maximum:
        raise CustomerError(400, "反馈文本长度不符合要求")
    allowed = "\n\r\t" if multiline else ""
    if any((ord(ch) < 32 and ch not in allowed) or ord(ch) == 127 for ch in value):
        raise CustomerError(400, "反馈文本含无效字符")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        raise CustomerError(400, "反馈文本含无效字符")
    return value


def choice(value, options):
    if not isinstance(value, str) or value not in options:
        raise CustomerError(400, "反馈选项无效")
    return value


def positive_integer(value):
    if type(value) is not int or not 1 <= value <= 2**31 - 1:
        raise CustomerError(400, "反馈版本格式无效")
    return value


def fields(data, expected):
    if not isinstance(data, dict) or set(data) != expected:
        raise CustomerError(400, "反馈字段不完整或含不支持的字段")


def iso(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


class Feedback:
    def __init__(self, store):
        self.store = store
        with store.connect() as connection:
            # Additive tables: existing schema-5 backup/restore and customer deletion work.
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS customer_feedback (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id TEXT NOT NULL REFERENCES customer_devices(id) ON DELETE CASCADE,
                    request_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
                    category TEXT NOT NULL, description TEXT NOT NULL,
                    app_version TEXT NOT NULL, version_code INTEGER NOT NULL,
                    android_version TEXT NOT NULL, manufacturer TEXT NOT NULL,
                    model TEXT NOT NULL, network TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'new', reply TEXT NOT NULL DEFAULT '',
                    fixed_version TEXT NOT NULL DEFAULT '', revision INTEGER NOT NULL DEFAULT 1,
                    created REAL NOT NULL, updated REAL NOT NULL,
                    UNIQUE(device_id, request_id)
                );
                CREATE INDEX IF NOT EXISTS customer_feedback_device_created
                    ON customer_feedback(device_id, created);
                CREATE INDEX IF NOT EXISTS customer_feedback_status_id
                    ON customer_feedback(status, id);
                CREATE TABLE IF NOT EXISTS feedback_images (
                    id TEXT PRIMARY KEY, feedback_id INTEGER NOT NULL REFERENCES customer_feedback(id) ON DELETE CASCADE,
                    content BLOB NOT NULL, sha256 TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS feedback_images_feedback ON feedback_images(feedback_id);
                CREATE TABLE IF NOT EXISTS feedback_access (
                    id INTEGER PRIMARY KEY CHECK(id=1), token_hash TEXT NOT NULL, updated REAL NOT NULL
                );
            """)
        store.restrict_files()

    @staticmethod
    def checked_device(connection, identifier):
        if not connection.execute("SELECT 1 FROM customer_devices WHERE id=? AND revoked=0", (identifier,)).fetchone():
            raise CustomerError(401, "设备认证失败")

    @staticmethod
    def item(row):
        # No device token, user credential, node configuration or other customer data.
        return {"id": row["id"], "number": "FB-%06d" % row["id"],
                "category": row["category"], "description": row["description"],
                "appVersion": row["app_version"], "versionCode": row["version_code"],
                "androidVersion": row["android_version"], "manufacturer": row["manufacturer"],
                "model": row["model"], "network": row["network"], "status": row["status"],
                "reply": row["reply"], "fixedVersion": row["fixed_version"],
                "revision": row["revision"], "createdAt": iso(row["created"]), "updatedAt": iso(row["updated"])}

    @staticmethod
    def receipt(row):
        return {"id": row["id"], "number": "FB-%06d" % row["id"],
                "status": row["status"], "revision": row["revision"]}

    def submit(self, identifier, data):
        if not isinstance(data, dict) or set(data) - (FIELDS | {'images'}) or FIELDS - {'category'} - set(data):
            raise CustomerError(400, "反馈字段无效")
        pictures = data.get('images', [])
        if not isinstance(pictures, list) or len(pictures) > 3:
            raise CustomerError(400, "最多添加三张图片")
        images = [image_bytes(picture) for picture in pictures]
        request_id = clean_text(data["requestId"], 36, 36)
        try:
            request_id = str(uuid.UUID(request_id))
        except ValueError:
            raise CustomerError(400, "反馈请求编号无效")
        value = {"requestId": request_id,
                 "category": choice(data.get("category", "other"), CATEGORIES),
                 "description": clean_text(data["description"], 4000, 0 if images else 1, multiline=True),
                 "appVersion": clean_text(data["appVersion"], 32),
                 "versionCode": positive_integer(data["versionCode"]),
                 "androidVersion": clean_text(data["androidVersion"], 32),
                 "manufacturer": clean_text(data["manufacturer"], 64),
                 "model": clean_text(data["model"], 80),
                 "network": choice(data["network"], NETWORKS)}
        if images:
            value['imageHashes'] = [hashlib.sha256(image).hexdigest() for image in images]
        fingerprint = hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                                separators=(",", ":")).encode("utf-8")).hexdigest()
        now = time.time()
        with self.store.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self.checked_device(connection, identifier)
            row = connection.execute("SELECT * FROM customer_feedback WHERE device_id=? AND request_id=?",
                                     (identifier, request_id)).fetchone()
            if row:
                if not hmac.compare_digest(row["payload_hash"], fingerprint):
                    raise CustomerError(409, "此反馈编号已用于其他内容，请重新提交")
                return self.receipt(row)
            counts = connection.execute("""SELECT COUNT(*) AS daily,
                COALESCE(SUM(CASE WHEN created>=? THEN 1 ELSE 0 END),0) AS hourly
                FROM customer_feedback WHERE device_id=? AND created>=?""",
                (now - 3600, identifier, now - 86400)).fetchone()
            if counts["hourly"] >= 5 or counts["daily"] >= 20:
                raise CustomerError(429, "反馈提交过于频繁，请稍后再试")
            cursor = connection.execute("""INSERT INTO customer_feedback
                (device_id,request_id,payload_hash,category,description,app_version,version_code,
                 android_version,manufacturer,model,network,created,updated)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""", (identifier, request_id, fingerprint,
                value["category"], value["description"], value["appVersion"], value["versionCode"],
                value["androidVersion"], value["manufacturer"], value["model"], value["network"], now, now))
            for image in images:
                connection.execute('INSERT INTO feedback_images(id,feedback_id,content,sha256) VALUES(?,?,?,?)',
                                   (str(uuid.uuid4()), cursor.lastrowid, image, hashlib.sha256(image).hexdigest()))
            row = connection.execute("SELECT * FROM customer_feedback WHERE id=?", (cursor.lastrowid,)).fetchone()
        return self.receipt(row)

    def for_device(self, identifier):
        with self.store.connect() as connection:
            connection.execute("BEGIN")
            self.checked_device(connection, identifier)
            rows = connection.execute("SELECT * FROM customer_feedback WHERE device_id=? ORDER BY id DESC LIMIT ?",
                                      (identifier, PAGE_SIZE)).fetchall()
        return {"items": [self.item(row) for row in rows]}

    @staticmethod
    def query(query, inbox=False):
        try:
            params = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=2) if query else {}
        except ValueError:
            raise CustomerError(400, "反馈筛选参数无效")
        if set(params) - ({"before"} if inbox else {"before", "status"}) or any(len(v) != 1 for v in params.values()):
            raise CustomerError(400, "反馈筛选参数无效")
        status = choice(params.get("status", ["all"])[0], STATUSES | {"all"})
        before = params.get("before", [None])[0]
        if before is not None:
            if not re.fullmatch(r"[1-9][0-9]{0,18}", before) or int(before) > 2**63 - 1:
                raise CustomerError(400, "反馈分页参数无效")
            before = int(before)
        return status, before

    @staticmethod
    def authenticate_agent(connection, bearer):
        if not isinstance(bearer, str) or not re.fullmatch(r"Bearer kfb_[A-Za-z0-9_-]{64}", bearer):
            raise CustomerError(401, "反馈接入认证失败")
        value = hashlib.sha256(bearer[7:].encode("ascii")).hexdigest()
        row = connection.execute("SELECT token_hash FROM feedback_access WHERE id=1").fetchone()
        if not row or not hmac.compare_digest(row["token_hash"], value):
            raise CustomerError(401, "反馈接入认证失败")

    def listing(self, query, bearer=None):
        status, before = self.query(query, inbox=bearer is not None)
        clauses, args = [], []
        if bearer is not None:
            clauses.append("status IN ('new','investigating','needs_info','ready')")
        elif status != "all":
            clauses.append("status=?")
            args.append(status)
        if before is not None:
            clauses.append("id<?")
            args.append(before)
        sql = "SELECT * FROM customer_feedback" + (" WHERE " + " AND ".join(clauses) if clauses else "")
        with self.store.connect() as connection:
            connection.execute("BEGIN")
            if bearer is not None:
                self.authenticate_agent(connection, bearer)
            rows = connection.execute(sql + " ORDER BY id DESC LIMIT ?", (*args, PAGE_SIZE + 1)).fetchall()
        page = rows[:PAGE_SIZE]
        items = [self.item(row) for row in page]
        with self.store.connect() as connection:
            for item in items:
                item['images'] = [{'id': image['id'], 'url': '/api/feedback/images/' + image['id']} for image in
                    connection.execute('SELECT id FROM feedback_images WHERE feedback_id=? ORDER BY rowid', (item['id'],))]
        return {"items": items,
                "nextBefore": page[-1]["id"] if len(rows) > PAGE_SIZE else None}

    def update(self, identifier, data, bearer=None):
        if type(identifier) is not int or not 1 <= identifier <= 2**63 - 1:
            raise CustomerError(400, "反馈编号无效")
        fields(data, {"revision", "status", "reply", "fixedVersion"})
        revision = positive_integer(data["revision"])
        status = choice(data["status"], STATUSES)
        reply = clean_text(data["reply"], 2000, multiline=True)
        fixed_version = clean_text(data["fixedVersion"], 32)
        if bearer is not None and status not in AGENT_STATUSES:
            raise CustomerError(403, "自动排查只能更新排查进展，发布结果须由管理员确认")
        if status == "resolved" and not fixed_version:
            raise CustomerError(400, "已解决的反馈须填写修复版本")
        with self.store.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if bearer is not None:
                self.authenticate_agent(connection, bearer)
            row = connection.execute("SELECT * FROM customer_feedback WHERE id=?", (identifier,)).fetchone()
            if not row:
                raise CustomerError(404, "反馈不存在")
            if row["revision"] != revision:
                raise CustomerError(409, "反馈已更新，请刷新后重试")
            if bearer is not None and row["status"] == "resolved":
                raise CustomerError(403, "已解决的反馈只能由管理员重新打开")
            connection.execute("""UPDATE customer_feedback SET status=?,reply=?,fixed_version=?,
                revision=revision+1,updated=? WHERE id=?""", (status, reply, fixed_version, time.time(), identifier))
            result = self.item(connection.execute("SELECT * FROM customer_feedback WHERE id=?", (identifier,)).fetchone())
        return result

    def access(self):
        with self.store.connect() as connection:
            enabled = connection.execute("SELECT 1 FROM feedback_access WHERE id=1").fetchone() is not None
        return {"enabled": enabled}

    def change_access(self, data):
        fields(data, {"action"})
        action = choice(data["action"], {"rotate", "revoke"})
        with self.store.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if action == "revoke":
                connection.execute("DELETE FROM feedback_access WHERE id=1")
                return {"enabled": False}
            token = "kfb_" + secrets.token_urlsafe(48)
            digest = hashlib.sha256(token.encode("ascii")).hexdigest()
            connection.execute("""INSERT INTO feedback_access(id,token_hash,updated) VALUES(1,?,?)
                ON CONFLICT(id) DO UPDATE SET token_hash=excluded.token_hash,updated=excluded.updated""",
                (digest, time.time()))
        return {"enabled": True, "token": token}

    def image(self, identifier, bearer=None):
        with self.store.connect() as connection:
            if bearer is not None: self.authenticate_agent(connection, bearer)
            row = connection.execute('SELECT content FROM feedback_images WHERE id=?', (identifier,)).fetchone()
            if not row: raise CustomerError(404, "图片不存在")
            return row['content']
