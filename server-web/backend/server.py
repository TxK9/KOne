#!/usr/bin/env python3
"""Local KOne console: static UI, authenticated API, and SQLite storage."""

import argparse
import base64
import gzip
from functools import lru_cache
from contextlib import contextmanager
import hashlib
import hmac
import http.cookies
import ipaddress
import json
import math
import mimetypes
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlencode, urlsplit
import uuid
from fleet import Fleet, FleetError
from customers import Customers, CustomerError, DEFAULT_GROUP
from feedback import Feedback
from owner_updates import OwnerUpdates, APK_PATH as OWNER_APK_PATH
from deploy.center_common import DataLock
from config_import import ConfigImportError
from portable_import import parse_import_text, portable_config, merge_config_nodes, export_node_links


BASE_DIR = Path(__file__).resolve().parent
MAX_BODY_BYTES = 1024 * 1024
SESSION_SECONDS = 24 * 60 * 60
COOKIE_NAME = "kone_session"
SUBSCRIPTION_PATH = re.compile(r"/api/client/subscriptions/([A-Za-z0-9_-]{43})/(sing-box\.json|nodes\.txt)")


@lru_cache(maxsize=32)
def compressed_static(body):
    return gzip.compress(body, compresslevel=6, mtime=0)
PUBLIC_API_ROUTES = {
    "/api/node/register": {"POST"},
    "/api/node/poll": {"POST"},
    "/api/node/result": {"POST"},
    "/api/node/usage": {"POST"},
    "/api/client/claim": {"POST"},
    "/api/client/config": {"GET"},
    "/api/client/ack": {"POST"},
    "/api/client/diagnostics": {"POST"},
    "/api/client/feedback": {"GET", "POST"},
    "/api/owner/update": {"GET"},
    "/downloads/install-node.sh": {"GET", "HEAD"},
    "/downloads/kone-node.py": {"GET", "HEAD"},
}


class APIError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def timestamp():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def clean_name(value):
    if not isinstance(value, str):
        raise APIError(400, "请输入名称")
    name = value.strip()
    if not name or len(name) > 80 or any(ord(char) < 32 or ord(char) == 127 for char in name):
        raise APIError(400, "名称须为 1 至 80 个字符")
    try:
        name.encode("utf-8")
    except UnicodeEncodeError:
        raise APIError(400, "名称含无效字符")
    return name


def selected_user_ids(value, allow_empty=False):
    if (not isinstance(value, list) or (not value and not allow_empty) or len(value) > 1000
            or any(not isinstance(item, str) or not item for item in value)):
        raise APIError(400, "请选择有效用户" if allow_empty else "请选择至少一位用户")
    return list(dict.fromkeys(value))


def parse_json_text(text):
    """Reject duplicate keys and non-finite numbers; retain integer precision."""
    def invalid_constant(value):
        raise ValueError("non-finite number")

    def finite_float(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError("floating point overflow")
        return number

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    return json.loads(text, parse_constant=invalid_constant, parse_float=finite_float,
                      object_pairs_hook=unique_object)


def validate_config(config):
    """Structural inspection only; does not run the sing-box core."""
    if not isinstance(config, dict):
        raise APIError(400, "配置须为 JSON 对象")
    outbounds = config.get("outbounds")
    if not isinstance(outbounds, list) or not outbounds:
        raise APIError(400, "配置缺少非空 outbounds 列表")
    tags = set()
    protocols = []
    nodes = 0
    non_nodes = {"direct", "block", "dns", "selector", "urltest"}
    for outbound in outbounds:
        if not isinstance(outbound, dict):
            raise APIError(400, "outbounds 项须为对象")
        kind = outbound.get("type")
        if not isinstance(kind, str) or not kind.strip() or len(kind) > 64:
            raise APIError(400, "每个出站须有有效 type")
        kind = kind.strip()
        tag = outbound.get("tag")
        if tag is not None:
            if not isinstance(tag, str) or not tag.strip() or len(tag) > 256:
                raise APIError(400, "出站 tag 无效")
            if tag in tags:
                raise APIError(400, "出站 tag 重复")
            tags.add(tag)
        if kind not in protocols:
            protocols.append(kind)
        if kind not in non_nodes:
            nodes += 1
    dns = config.get("dns", {})
    route = config.get("route", {})
    if not isinstance(dns, dict) or not isinstance(route, dict):
        raise APIError(400, "dns 和 route 须为对象")
    servers = dns.get("servers", [])
    rules = route.get("rules", [])
    if not isinstance(servers, list) or not isinstance(rules, list):
        raise APIError(400, "DNS 服务器和路由规则须为列表")
    return {"nodes": nodes, "protocols": protocols, "dnsCount": len(servers), "ruleCount": len(rules)}


def encode_password(password):
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 600000, dklen=32)
    return "pbkdf2-sha256$600000$%s$%s" % (salt.hex(), digest.hex())


def verify_password(password, encoded):
    try:
        algorithm, rounds, salt, expected = encoded.split("$")
        if algorithm != "pbkdf2-sha256":
            return False
        digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt), int(rounds), dklen=32)
        return hmac.compare_digest(digest, bytes.fromhex(expected))
    except (ValueError, TypeError):
        return False


def parse_origin(value):
    parsed = urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username
            or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("public origin must contain only http(s) scheme and host")
    try:
        parsed.port
    except ValueError:
        raise ValueError("invalid origin port")
    return "%s://%s" % (parsed.scheme, parsed.netloc.lower())


class ConsoleStore:
    def __init__(self, data_dir):
        self.data_dir = Path(data_dir).expanduser().resolve()
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(str(self.data_dir), 0o700)
        self.path = self.data_dir / "console.sqlite3"
        if self.path.is_symlink():
            raise ValueError("database must not be a symbolic link")
        descriptor = os.open(str(self.path), os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600)
        os.close(descriptor)
        os.chmod(str(self.path), 0o600)
        with self.connect() as connection:
            if connection.execute("PRAGMA user_version").fetchone()[0] > 5:
                raise ValueError("数据库来自较新的版本，请先更新管理中心程序")
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS profiles (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, version_number INTEGER NOT NULL UNIQUE,
                    config_json TEXT NOT NULL, nodes INTEGER NOT NULL, protocols_json TEXT NOT NULL,
                    dns_count INTEGER NOT NULL, rule_count INTEGER NOT NULL,
                    updated TEXT NOT NULL, source TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS profile_revisions (
                    profile_id TEXT NOT NULL REFERENCES profiles(id), revision TEXT NOT NULL,
                    config_json TEXT NOT NULL, created TEXT NOT NULL,
                    PRIMARY KEY(profile_id, revision)
                );
                CREATE TABLE IF NOT EXISTS profile_subscriptions (
                    profile_id TEXT PRIMARY KEY REFERENCES profiles(id), token_hash TEXT UNIQUE NOT NULL,
                    created TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL,
                    profile_id TEXT REFERENCES profiles(id), created TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL,
                    title TEXT NOT NULL, detail TEXT NOT NULL, result TEXT NOT NULL
                );
            """)
        self.restrict_files()

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(str(self.path), timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def restrict_files(self):
        for path in self.data_dir.glob("console.sqlite3*"):
            if path.is_file() and not path.is_symlink():
                os.chmod(str(path), 0o600)

    def configured(self):
        with self.connect() as connection:
            return connection.execute("SELECT 1 FROM settings WHERE key = 'password'").fetchone() is not None

    def password_hash(self):
        with self.connect() as connection:
            row = connection.execute("SELECT value FROM settings WHERE key = 'password'").fetchone()
            return row["value"] if row else None

    def setup(self, password):
        encoded = encode_password(password)
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if connection.execute("SELECT 1 FROM settings WHERE key = 'password'").fetchone():
                raise APIError(409, "管理员密码已设置")
            connection.execute("INSERT INTO settings(key, value) VALUES('password', ?)", (encoded,))
        self.restrict_files()

    @staticmethod
    def record(connection, title, detail):
        connection.execute("INSERT INTO records(time, title, detail, result) VALUES (?, ?, ?, ?)",
                           (timestamp(), title, detail, "完成"))

    def state(self):
        with self.connect() as connection:
            connection.execute("BEGIN")
            profiles = []
            for row in connection.execute("SELECT * FROM profiles ORDER BY version_number DESC"):
                profiles.append({
                    "id": row["id"], "name": row["name"],
                    "version": "v%03d" % row["version_number"], "format": "sing-box JSON",
                    "nodes": row["nodes"], "protocols": json.loads(row["protocols_json"]),
                    "regions": [], "dnsCount": row["dns_count"], "ruleCount": row["rule_count"],
                    "updated": row["updated"], "source": row["source"],
                    "revision": hashlib.sha256(row["config_json"].encode("utf-8")).hexdigest(),
                    "subscriptionEnabled": bool(connection.execute("SELECT 1 FROM profile_subscriptions WHERE profile_id=?", (row["id"],)).fetchone()),
                })
            has_customers = connection.execute("SELECT 1 FROM sqlite_master WHERE name='customer_accounts'").fetchone()
            if has_customers:
                rows = connection.execute("""SELECT u.*,COALESCE(u.profile_id,g.profile_id) AS effective_profile,
                    (SELECT COUNT(*) FROM customer_devices d WHERE d.user_id=u.id AND d.revoked=0) AS devices
                    FROM users u LEFT JOIN customer_accounts a ON a.user_id=u.id
                    LEFT JOIN customer_groups g ON g.id=a.group_id ORDER BY u.created,u.rowid""")
                users = [{"id":r["id"],"name":r["name"],"profile":r["effective_profile"],"deviceCount":r["devices"]} for r in rows]
            else:
                users = [{"id":r["id"],"name":r["name"],"profile":r["profile_id"],"deviceCount":0}
                         for r in connection.execute("SELECT * FROM users ORDER BY created,rowid")]
            records = [dict(row) for row in connection.execute(
                "SELECT id, time, title, detail, result FROM records ORDER BY id DESC LIMIT 300")]
            return {"profiles": profiles, "users": users, "records": records}

    def preview_profile(self, payload):
        try:
            config, warnings, format_ = parse_import_text(payload.get("configText"), parse_json_text)
        except ConfigImportError as error:
            raise APIError(400, str(error)) from None
        metadata = validate_config(config)
        if not metadata["nodes"]:
            raise APIError(400, "The configuration has no portable proxy nodes.")
        added = metadata["nodes"]
        if payload.get("profileId"):
            with self.connect() as connection:
                row = self.profile_for_update(connection, payload["profileId"], payload.get("expectedRevision"))
                try:
                    config, added = merge_config_nodes(json.loads(row["config_json"]), config)
                except ConfigImportError as error:
                    raise APIError(400, str(error)) from None
                metadata = validate_config(config)
        return {"ok": True, "format": format_, **metadata, "addedNodes": added, "warnings": warnings}

    @staticmethod
    def profile_for_update(connection, identifier, expected):
        if not isinstance(identifier, str) or not re.fullmatch(r"[0-9a-f-]{36}", identifier):
            raise APIError(400, "请选择配置")
        row = connection.execute("SELECT * FROM profiles WHERE id=?", (identifier,)).fetchone()
        if row is None:
            raise APIError(404, "配置不存在")
        if row["source"] == "线路生成":
            raise APIError(409, "Route-generated configurations must be changed through their route.")
        current = hashlib.sha256(row["config_json"].encode("utf-8")).hexdigest()
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected) or not hmac.compare_digest(expected, current):
            raise APIError(409, "This configuration changed. Refresh it before adding nodes.")
        return row

    def update_profile(self, identifier, payload):
        if payload.get("mode", "merge") != "merge":
            raise APIError(400, "Only additive node updates are supported here.")
        try:
            incoming, warnings, _ = parse_import_text(payload.get("configText"), parse_json_text)
        except ConfigImportError as error:
            raise APIError(400, str(error)) from None
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = self.profile_for_update(connection, identifier, payload.get("expectedRevision"))
            try:
                config, added = merge_config_nodes(json.loads(row["config_json"]), incoming)
            except ConfigImportError as error:
                raise APIError(400, str(error)) from None
            metadata = validate_config(config)
            serialized = json.dumps(config, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
            if len(serialized.encode("utf-8")) > MAX_BODY_BYTES:
                raise APIError(413, "配置过大，请控制在 1 MB 内")
            if json.loads(row["config_json"]) == config:
                return {"ok": True, "id": identifier, "addedNodes": 0, "warnings": warnings}
            old_revision = hashlib.sha256(row["config_json"].encode("utf-8")).hexdigest()
            connection.execute("INSERT OR IGNORE INTO profile_revisions VALUES(?,?,?,?)", (identifier, old_revision, row["config_json"], timestamp()))
            connection.execute("DELETE FROM profile_revisions WHERE profile_id=? AND rowid NOT IN (SELECT rowid FROM profile_revisions WHERE profile_id=? ORDER BY rowid DESC LIMIT 10)", (identifier, identifier))
            version = connection.execute("SELECT COALESCE(MAX(version_number),0)+1 FROM profiles").fetchone()[0]
            connection.execute("UPDATE profiles SET version_number=?,config_json=?,nodes=?,protocols_json=?,dns_count=?,rule_count=?,updated=? WHERE id=?", (version, serialized, metadata["nodes"], json.dumps(metadata["protocols"]), metadata["dnsCount"], metadata["ruleCount"], timestamp(), identifier))
            self.record(connection, "导入配置", row["name"])
        self.restrict_files()
        return {"ok": True, "id": identifier, "addedNodes": added, "warnings": warnings}

    def import_profile(self, payload):
        name = clean_name(payload.get("name"))
        warnings = []
        if "configText" in payload:
            config_text = payload["configText"]
            if not isinstance(config_text, str):
                raise APIError(400, "配置文本须为字符串")
            try:
                config, warnings, _ = parse_import_text(config_text, parse_json_text)
            except ConfigImportError as error:
                raise APIError(400, str(error)) from None
        else:
            config = payload.get("config")
        metadata = validate_config(config)
        source = payload.get("source", "paste")
        if source not in ("file", "paste"):
            raise APIError(400, "导入来源无效")
        user_ids = selected_user_ids(payload.get("userIds", []), allow_empty=True)
        try:
            config_json = json.dumps(config, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
            config_size = len(config_json.encode("utf-8"))
        except (ValueError, TypeError, RecursionError):
            raise APIError(400, "配置包含无效 JSON 值")
        if config_size > MAX_BODY_BYTES:
            raise APIError(413, "配置过大，请控制在 1 MB 内")
        profile_id = str(uuid.uuid4())
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            user_names = []
            for user_id in user_ids:
                user = connection.execute("SELECT name FROM users WHERE id = ?", (user_id,)).fetchone()
                if not user:
                    raise APIError(400, "所选用户不存在，请刷新后重试")
                user_names.append(user["name"])
            number = connection.execute("SELECT COALESCE(MAX(version_number), 0) + 1 FROM profiles").fetchone()[0]
            connection.execute("""INSERT INTO profiles(
                id, name, version_number, config_json, nodes, protocols_json, dns_count, rule_count, updated, source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (profile_id, name, number, config_json, metadata["nodes"],
                 json.dumps(metadata["protocols"]), metadata["dnsCount"], metadata["ruleCount"], timestamp(), source))
            self.record(connection, "导入配置", name)
            if user_ids:
                connection.executemany("UPDATE users SET profile_id = ? WHERE id = ?",
                                       [(profile_id, user_id) for user_id in user_ids])
                if 'app_only' in {r[1] for r in connection.execute('PRAGMA table_info(customer_accounts)')}:
                    connection.executemany('UPDATE customer_accounts SET app_only=0 WHERE user_id=?',[(u,) for u in user_ids])
                self.record(connection, "分配配置", "%s → %s" % (name, "、".join(user_names[:8])))
        self.restrict_files()
        return {"ok": True, "id": profile_id, "warnings": warnings}

    def create_subscription(self, identifier):
        token = secrets.token_urlsafe(32)
        token_hash = hashlib.sha256(token.encode("ascii")).hexdigest()
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT * FROM profiles WHERE id=?", (identifier,)).fetchone()
            if not row:
                raise APIError(404, "配置不存在")
            if row["source"] == "线路生成":
                raise APIError(409, "Route-generated configurations must use customer enrollment.")
            if connection.execute("SELECT 1 FROM profile_subscriptions WHERE profile_id=?", (identifier,)).fetchone():
                raise APIError(409, "A subscription already exists. Revoke it before creating another link.")
            config = json.loads(row["config_json"])
            try:
                portable_config(config)
            except ConfigImportError as error:
                raise APIError(400, str(error)) from None
            links_error = None
            try:
                export_node_links(config)
            except ConfigImportError as error:
                links_error = str(error)
            connection.execute("INSERT INTO profile_subscriptions VALUES(?,?,?)", (identifier, token_hash, timestamp()))
        self.restrict_files()
        return {"token": token, "linksError": links_error}

    def revoke_subscription(self, identifier):
        with self.connect() as connection:
            connection.execute("DELETE FROM profile_subscriptions WHERE profile_id=?", (identifier,))
        return {"ok": True}

    def subscription(self, token, format_):
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
            raise APIError(404, "Subscription not found.")
        token_hash = hashlib.sha256(token.encode("ascii")).hexdigest()
        with self.connect() as connection:
            row = connection.execute("SELECT p.config_json,s.token_hash FROM profile_subscriptions s JOIN profiles p ON p.id=s.profile_id WHERE s.token_hash=?", (token_hash,)).fetchone()
            if row is None or not hmac.compare_digest(row["token_hash"], token_hash):
                raise APIError(404, "Subscription not found.")
            config = json.loads(row["config_json"])
        try:
            if format_ == "sing-box.json":
                return (json.dumps(portable_config(config), ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n").encode("utf-8"), "application/json; charset=utf-8"
            links = export_node_links(config)
            return base64.b64encode(links.encode("utf-8")) + b"\n", "text/plain; charset=utf-8"
        except ConfigImportError as error:
            raise APIError(409, str(error)) from None

    def config(self, profile_id):
        with self.connect() as connection:
            row = connection.execute("SELECT name, config_json FROM profiles WHERE id = ?", (profile_id,)).fetchone()
            if not row:
                raise APIError(404, "配置不存在")
            return row["name"], json.loads(row["config_json"])

    def add_user(self, payload):
        name = clean_name(payload.get("name"))
        app_only=payload.get('appOnly',False)
        if type(app_only) is not bool:raise APIError(400,'服务方式无效')
        user_id = str(uuid.uuid4())
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            has_customers = connection.execute("SELECT 1 FROM sqlite_master WHERE name='customer_accounts'").fetchone()
            group_id = payload.get("groupId", DEFAULT_GROUP)
            if group_id is not None and not isinstance(group_id,str):
                raise APIError(400,"分组标识无效")
            if has_customers and group_id is not None and not connection.execute("SELECT 1 FROM customer_groups WHERE id=?", (group_id,)).fetchone():
                raise APIError(400, "所选分组不存在")
            connection.execute("INSERT INTO users(id, name, profile_id, created) VALUES (?, ?, NULL, ?)",
                               (user_id, name, timestamp()))
            if has_customers:
                connection.execute("INSERT INTO customer_accounts(user_id,group_id,paused,joined,app_only) VALUES(?,?,0,?,?)",(user_id,group_id,time.time(),int(app_only)))
            self.record(connection, "新增用户", name)
        self.restrict_files()
        return {"ok": True, "id": user_id}

    def assign(self, payload):
        profile_id = payload.get("profileId")
        user_ids = payload.get("userIds")
        if not isinstance(profile_id, str) or not profile_id:
            raise APIError(400, "请选择配置")
        user_ids = selected_user_ids(user_ids)
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            profile = connection.execute("SELECT name,source FROM profiles WHERE id = ?", (profile_id,)).fetchone()
            if not profile:
                raise APIError(404, "配置不存在")
            names = []
            for user_id in user_ids:
                user = connection.execute("SELECT name FROM users WHERE id = ?", (user_id,)).fetchone()
                if not user:
                    raise APIError(400, "所选用户不存在，请刷新后重试")
                if profile['source']=='线路生成' and not connection.execute('SELECT 1 FROM fleet_assignments WHERE user_id=? AND profile_id=?',(user_id,profile_id)).fetchone():
                    raise APIError(400,'客户独立线路配置不能分配给其他用户')
                names.append(user["name"])
            connection.executemany("UPDATE users SET profile_id = ? WHERE id = ?",
                                   [(profile_id, user_id) for user_id in user_ids])
            if 'app_only' in {r[1] for r in connection.execute('PRAGMA table_info(customer_accounts)')}:
                connection.executemany('UPDATE customer_accounts SET app_only=0 WHERE user_id=?',[(u,) for u in user_ids])
            summary = "、".join(names[:8])
            if len(names) > 8:
                summary += " 等 %d 位用户" % len(names)
            self.record(connection, "分配配置", "%s → %s" % (profile["name"], summary))
        self.restrict_files()
        return {"ok": True}

    def rename(self, profile_id, payload):
        name = clean_name(payload.get("name"))
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT name FROM profiles WHERE id = ?", (profile_id,)).fetchone()
            if not row:
                raise APIError(404, "配置不存在")
            connection.execute("UPDATE profiles SET name = ? WHERE id = ?", (name, profile_id))
            self.record(connection, "重命名配置", "%s → %s" % (row["name"], name))
        self.restrict_files()
        return {"ok": True}


class ConsoleApplication:
    def __init__(self, store, web_dir, host, port, public_origin=None):
        self.store = store
        self.fleet = Fleet(store)
        self.customers = Customers(store,self.fleet)
        self.feedback = Feedback(store)
        self.owner_updates = OwnerUpdates(store)
        self.web_dir = Path(web_dir).resolve()
        self.sessions = {}
        self.session_lock = threading.Lock()
        self.login_attempts = {}
        self.login_pruned_at = 0
        self.trusted_proxy = None
        self.public_api_authority = None
        self.allowed_origins = {}
        bind_host = host.strip("[]").lower()
        hosts = {bind_host}
        if bind_host in ("127.0.0.1", "localhost", "0.0.0.0", "::", "::1"):
            hosts.update({"127.0.0.1", "localhost"})
        hosts.discard("0.0.0.0")
        hosts.discard("::")
        for allowed_host in hosts:
            authority = "[%s]" % allowed_host if ":" in allowed_host else allowed_host
            if port != 80:
                authority += ":%d" % port
            self.allowed_origins.setdefault(authority, set()).add("http://" + authority)
        origins = public_origin if isinstance(public_origin, (list, tuple)) else ([public_origin] if public_origin else [])
        for value in origins:
            origin = parse_origin(value)
            authority = urlsplit(origin).netloc
            self.allowed_origins.setdefault(authority, set()).add(origin)

    def check_request(self, handler, mutation=False):
        hosts = handler.headers.get_all("Host", [])
        if len(hosts) != 1:
            raise APIError(403, "访问地址无效")
        authority = hosts[0].strip().lower()
        origins = self.allowed_origins.get(authority)
        if not origins:
            raise APIError(403, "此访问地址未获授权")
        if authority == self.public_api_authority:
            target = urlsplit(handler.path)
            methods = PUBLIC_API_ROUTES.get(target.path)
            if OWNER_APK_PATH.fullmatch(target.path) or SUBSCRIPTION_PATH.fullmatch(target.path): methods = {"GET", "HEAD"}
            if methods is None or target.query or target.fragment:
                raise APIError(404, "接口不存在")
            if handler.command not in methods:
                raise APIError(405, "接口方法无效")
        if mutation:
            supplied = handler.headers.get_all("Origin", [])
            if len(supplied) > 1 or (supplied and supplied[0] not in origins):
                raise APIError(403, "请从控制台页面提交操作")
        return authority

    def new_session(self):
        token = secrets.token_urlsafe(32)
        session = {"csrfToken": secrets.token_urlsafe(32), "expires": time.time() + SESSION_SECONDS}
        with self.session_lock:
            self.prune_sessions()
            if len(self.sessions) >= 20:
                oldest = min(self.sessions, key=lambda key: self.sessions[key]["expires"])
                del self.sessions[oldest]
            self.sessions[token] = session
        return token, session

    def prune_sessions(self):
        now = time.time()
        for token in list(self.sessions):
            if self.sessions[token]["expires"] <= now:
                del self.sessions[token]

    def session(self, handler):
        cookie = http.cookies.SimpleCookie()
        try:
            cookie.load(handler.headers.get("Cookie", ""))
        except http.cookies.CookieError:
            return None, None
        item = cookie.get(COOKIE_NAME)
        if not item:
            return None, None
        with self.session_lock:
            self.prune_sessions()
            return item.value, self.sessions.get(item.value)

    def require_session(self, handler, mutation=False):
        token, session = self.session(handler)
        if session is None:
            raise APIError(401, "请先登录")
        if mutation:
            csrf = handler.headers.get("X-CSRF-Token", "")
            if not hmac.compare_digest(csrf.encode("utf-8"), session["csrfToken"].encode("utf-8")):
                raise APIError(403, "页面已过期，请刷新后重试")
        return token, session

    def cookie(self, handler, token, expired=False):
        authority = handler.headers.get("Host", "").strip().lower()
        secure = any(origin.startswith("https://") for origin in self.allowed_origins.get(authority, []))
        value = "%s=%s; Path=/; HttpOnly; SameSite=Strict; Max-Age=%d" % (
            COOKIE_NAME, token, 0 if expired else SESSION_SECONDS)
        if secure:
            value += "; Secure"
        return value

    def check_login_rate(self, client_ip):
        with self.session_lock:
            now = time.monotonic()
            if now - self.login_pruned_at >= 30 or len(self.login_attempts) >= 4096:
                self.login_attempts = {key: [item for item in values if item > now - 60]
                                       for key, values in self.login_attempts.items()
                                       if values and values[-1] > now - 60}
                self.login_pruned_at = now
            if client_ip not in self.login_attempts and len(self.login_attempts) >= 4096:
                raise APIError(429, "尝试次数过多，请稍后再试")
            attempts = [attempt for attempt in self.login_attempts.get(client_ip, []) if attempt > now - 60]
            if len(attempts) >= 10:
                raise APIError(429, "尝试次数过多，请稍后再试")
            attempts.append(now)
            self.login_attempts[client_ip] = attempts

    def request_ip(self, handler):
        # Opt-in only for the fixed public Host behind our loopback cloudflared.
        # Other proxies, private admin traffic and X-Forwarded-For are not trusted.
        peer = handler.client_address[0]
        authority = handler.headers.get("Host", "").strip().lower()
        if self.trusted_proxy != "cloudflare-tunnel" or authority != self.public_api_authority:
            return peer
        try:
            if not ipaddress.ip_address(peer).is_loopback:
                raise ValueError()
            values = handler.headers.get_all("CF-Connecting-IP", [])
            if len(values) != 1 or len(values[0]) > 45:
                raise ValueError()
            address = ipaddress.ip_address(values[0].strip())
            if not address.is_global or address.is_multicast:
                raise ValueError()
            return str(address)
        except ValueError:
            raise APIError(400, "隧道来源地址无效")


class ConsoleHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "KOneConsole"
    sys_version = ""

    @property
    def app(self):
        return self.server.app

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, format_string, *args):
        # Configuration data, credentials, and request URLs are deliberately not logged.
        pass

    def _send(self, status, body, content_type="application/json; charset=utf-8", headers=None, head=False, cache="no-store"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        response_headers = dict(headers or {})
        self.send_header("Cache-Control", response_headers.pop("Cache-Control", cache))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", response_headers.pop("Referrer-Policy", "same-origin"))
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        for key, value in response_headers.items():
            self.send_header(key, value)
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def _json(self, status, payload, headers=None, head=False):
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self._send(status, body, headers=headers, head=head)

    def _body(self):
        if self.headers.get("Transfer-Encoding"):
            raise APIError(400, "不支持此请求格式")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1:
            raise APIError(400, "请求内容长度无效")
        try:
            length = int(lengths[0])
        except ValueError:
            raise APIError(400, "请求内容长度无效")
        if length < 0 or length > MAX_BODY_BYTES:
            raise APIError(413, "请求过大，请控制在 1 MB 内")
        if self.headers.get_content_type() != "application/json":
            raise APIError(415, "请使用 JSON 提交")
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise APIError(400, "请求内容不完整")
        try:
            body = parse_json_text(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError, RecursionError):
            raise APIError(400, "JSON 格式有误或含重复字段")
        if not isinstance(body, dict):
            raise APIError(400, "请求内容须为 JSON 对象")
        return body

    def _static(self, path, head=False):
        decoded = unquote(path)
        if "\x00" in decoded or "\\" in decoded or any(part in ("..", ".") for part in decoded.split("/")):
            raise APIError(404, "页面不存在")
        relative = decoded.lstrip("/") or "index.html"
        file_path = (self.app.web_dir / relative).resolve()
        try:
            file_path.relative_to(self.app.web_dir)
        except ValueError:
            raise APIError(404, "页面不存在")
        if not file_path.is_file():
            raise APIError(404, "页面不存在")
        # Windows registry associations must not change executable asset MIME types.
        asset_types = {".html": "text/html", ".css": "text/css", ".js": "application/javascript",
                       ".svg": "image/svg+xml", ".png": "image/png"}
        content_type = asset_types.get(file_path.suffix.lower()) or mimetypes.guess_type(str(file_path))[0] or "application/octet-stream"
        if content_type.startswith("text/") or content_type in ("application/javascript", "application/json"):
            content_type += "; charset=utf-8"
        # The ink paintings are large and never change under the same name; a phone on mobile
        # data should not fetch them again on every visit. Everything else stays uncached.
        body = file_path.read_bytes()
        fingerprint = parse_qs(urlsplit(self.path).query).get('v', [None])[0]
        versioned = file_path.suffix.lower() in {'.js', '.css', '.svg', '.png'} and fingerprint == hashlib.sha256(body).hexdigest()[:16]
        cache = ('private, max-age=31536000, immutable' if versioned else
                 'private, max-age=604800' if relative.startswith('art/') and content_type == 'image/png' else 'no-store')
        headers = {}
        if file_path.suffix.lower() in {'.html', '.js', '.css', '.svg'}:
            headers['Vary'] = 'Accept-Encoding'
            if re.search(r'(?:^|,)\s*gzip\s*(?:;\s*q=(?!0(?:\.0*)?(?:\s|,|$))[0-9.]+)?\s*(?:,|$)', self.headers.get('Accept-Encoding', ''), re.I):
                body = compressed_static(body)
                headers['Content-Encoding'] = 'gzip'
        self._send(200, body, content_type=content_type, head=head, cache=cache, headers=headers)

    def _get(self, head=False):
        self.app.check_request(self)
        path = urlsplit(self.path).path
        if path == "/api/owner/update":
            self.app.owner_updates.authenticate(self.headers.get("Authorization", ""))
            self._json(200, self.app.owner_updates.release(), head=head)
            return
        if OWNER_APK_PATH.fullmatch(path):
            if self.headers.get("Host", "").lower() == self.app.public_api_authority or self.headers.get("Authorization"):
                self.app.owner_updates.authenticate(self.headers.get("Authorization", ""))
            else:
                self.app.require_session(self)
            target, release = self.app.owner_updates.artifact(path)
            size = release["bytes"]
            etag = '"' + release["sha256"] + '"'
            start, end, partial = 0, size - 1, False
            ranges = self.headers.get_all("Range", [])
            # HEAD describes the complete representation. Invalid/multiple ranges are ignored;
            # a valid but unsatisfiable range returns 416, after the same authentication.
            requested = ranges[0] if len(ranges) == 1 and not head else ""
            if self.headers.get("If-Range", etag) != etag:
                requested = ""
            match = re.fullmatch(r"bytes=([0-9]{0,12})-([0-9]{0,12})", requested)
            if match and any(match.groups()):
                first, last = match.groups()
                if first:
                    start = int(first)
                    end = min(int(last), size - 1) if last else size - 1
                else:
                    start = max(0, size - int(last))
                if start >= size or end < start:
                    self._send(416, b"", headers={"Content-Range": "bytes */%d" % size,
                               "Accept-Ranges": "bytes", "ETag": etag}, cache="private, no-store")
                    return
                partial = True
            with target.open("rb") as stream:
                self.send_response(206 if partial else 200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(end - start + 1))
                self.send_header("Content-Disposition", 'attachment; filename="%s"' % target.name)
                self.send_header("Cache-Control", "private, no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("ETag", etag)
                if partial:
                    self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
                self.end_headers()
                if not head:
                    stream.seek(start)
                    remaining = end - start + 1
                    try:
                        while remaining:
                            chunk = stream.read(min(64 * 1024, remaining))
                            if not chunk:
                                self.close_connection = True
                                break
                            self.wfile.write(chunk)
                            remaining -= len(chunk)
                    except OSError:
                        # The response already started; never append JSON to a partial APK.
                        self.close_connection = True
            return
        if path == "/api/client/feedback":
            if head or urlsplit(self.path).query:
                raise APIError(405, "请使用 GET 查看反馈")
            identifier = self.app.customers.authenticate(self.headers.get("Authorization", ""))
            self._json(200, self.app.feedback.for_device(identifier))
            return
        image_match = re.fullmatch(r"/api/feedback/images/([0-9a-f-]{36})", path)
        if image_match:
            bearer = self.headers.get("Authorization")
            if bearer is None: self.app.require_session(self)
            content = self.app.feedback.image(image_match[1], bearer=bearer)
            self._send(200, content, "image/jpeg", head=head)
            return
        if path == "/api/feedback/inbox":
            self._json(200, self.app.feedback.listing(urlsplit(self.path).query, bearer=self.headers.get("Authorization", "")), head=head)
            return
        subscription = SUBSCRIPTION_PATH.fullmatch(path)
        if subscription:
            if urlsplit(self.path).query:
                raise APIError(404, "Subscription not found.")
            body, content_type = self.app.store.subscription(subscription[1], subscription[2])
            self._send(200, body, content_type, headers={"Cache-Control": "private, no-store", "Referrer-Policy": "no-referrer"}, head=head)
            return
        if path == "/api/client/config":
            identifier = self.app.customers.authenticate(self.headers.get("Authorization", ""))
            if head:raise APIError(405,"请使用 GET 领取配置")
            self._json(200, self.app.customers.configuration(identifier), head=head)
            return
        if path in ("/downloads/kone-node.py", "/downloads/install-node.sh"):
            target = BASE_DIR / "node" / ("agent.py" if path.endswith(".py") else "install.sh")
            if not target.is_file():
                raise APIError(404, "安装包尚未准备好")
            self._send(200, target.read_bytes(), "text/plain; charset=utf-8", head=head)
            return
        if path == "/api/auth/status":
            _, session = self.app.session(self)
            self._json(200, {"configured": self.app.store.configured(), "authenticated": session is not None,
                             "csrfToken": session["csrfToken"] if session else None}, head=head)
            return
        if path.startswith("/api/"):
            self.app.require_session(self)
            if path == "/api/owner-release":
                self._json(200, {"release": self.app.owner_updates.release(), "devices": self.app.owner_updates.devices()}, head=head)
                return
            if path == "/api/feedback":
                self._json(200, self.app.feedback.listing(urlsplit(self.path).query), head=head)
                return
            if path == "/api/feedback/access":
                self._json(200, self.app.feedback.access(), head=head)
                return
            if path == "/api/state":
                self._json(200, self.app.store.state(), head=head)
                return
            if path == "/api/customers":
                self._json(200, self.app.customers.state(), head=head)
                return
            match = re.fullmatch(r"/api/customer-users/([0-9a-f-]{36})/(detail|diagnostics)", path)
            if match:
                result = self.app.customers.detail(match.group(1))
                if match.group(2) == "diagnostics":
                    body = (json.dumps(result,ensure_ascii=False,allow_nan=False,indent=2)+"\n").encode("utf-8")
                    self._send(200,body,headers={"Content-Disposition":'attachment; filename="customer-diagnostics.json"'},head=head)
                else:
                    self._json(200,result,head=head)
                return
            if path == "/api/fleet":
                result = self.app.fleet.state()
                result["installOrigin"] = getattr(self.app, "node_origin", None)
                self._json(200, result, head=head)
                return
            match = re.fullmatch(r"/api/profiles/([0-9a-f-]{36})/(config|portable)", path)
            if match:
                name, config = self.app.store.config(match.group(1))
                if match.group(2) == "portable":
                    try:
                        config = portable_config(config)
                    except ConfigImportError as error:
                        raise APIError(400, str(error)) from None
                    name += "-nodes"
                body = (json.dumps(config, ensure_ascii=False, allow_nan=False, indent=2) + "\n").encode("utf-8")
                self._send(200, body, headers={"Content-Disposition": "attachment; filename=\"config.json\"; filename*=UTF-8''" + quote(name + ".json", safe="")}, head=head)
                return
            raise APIError(404, "接口不存在")
        self._static(path, head=head)

    def _post(self):
        self.app.check_request(self, mutation=True)
        path = urlsplit(self.path).path
        if path.startswith("/api/client/"):
            payload = self._body()
            if path == "/api/client/claim":
                self.app.check_login_rate("client:" + self.app.request_ip(self))
                result = self.app.customers.claim(payload)
            else:
                identifier = self.app.customers.authenticate(self.headers.get("Authorization", ""))
                if path == "/api/client/ack":
                    result = self.app.customers.ack(identifier,payload)
                elif path == "/api/client/feedback":
                    result = self.app.feedback.submit(identifier, payload)
                elif path == "/api/client/diagnostics":
                    result = self.app.customers.diagnostics(identifier,payload)
                else:
                    raise APIError(404,"客户端接口不存在")
            self._json(200,result)
            return
        if path.startswith("/api/node/"):
            # Agent credentials never grant access to administrator endpoints.
            if path == "/api/node/register":
                self.app.check_login_rate("node:" + self.app.request_ip(self))
                result = self.app.fleet.register(self._body())
            else:
                node = self.app.fleet.authenticate(self.headers.get("Authorization", ""))
                payload = self._body()
                if path == "/api/node/poll":
                    result = self.app.fleet.poll(node["id"], payload)
                elif path == "/api/node/result":
                    result = self.app.fleet.complete(node["id"], payload)
                elif path == "/api/node/usage":
                    result = self.app.customers.ingest_usage(node["id"], payload)
                else:
                    raise APIError(404, "节点接口不存在")
            self._json(200, result)
            return
        if path in ("/api/auth/setup", "/api/auth/login"):
            payload = self._body()
            password = payload.get("password")
            if not isinstance(password, str) or len(password) > 256:
                raise APIError(400, "密码格式无效")
            try:
                password.encode("utf-8")
            except UnicodeEncodeError:
                raise APIError(400, "密码含无效字符")
            self.app.check_login_rate(self.client_address[0])
            if path.endswith("/setup"):
                if len(password) < 8:
                    raise APIError(400, "密码至少需要 8 个字符")
                self.app.store.setup(password)
            else:
                encoded = self.app.store.password_hash()
                if not encoded:
                    raise APIError(409, "请先设置管理员密码")
                if not verify_password(password, encoded):
                    raise APIError(401, "密码不正确")
            token, session = self.app.new_session()
            self._json(200, {"ok": True, "csrfToken": session["csrfToken"]},
                       headers={"Set-Cookie": self.app.cookie(self, token)})
            return
        feedback_match = re.fullmatch(r"/api/feedback/([1-9][0-9]{0,18})/status", path)
        if feedback_match and self.headers.get("Authorization", "").startswith("Bearer "):
            result = self.app.feedback.update(int(feedback_match[1]), self._body(), bearer=self.headers.get("Authorization", ""))
            self._json(200, result)
            return
        token, _ = self.app.require_session(self, mutation=True)
        payload = self._body()
        if path == "/api/owner-update/enroll":
            if not self.app.public_api_authority: raise APIError(503, "更新通道尚未配置")
            result = self.app.owner_updates.enroll(payload)
            result["feed"] = "https://" + self.app.public_api_authority + "/api/owner/update"
            self._json(200, result)
            return
        if path == "/api/owner-update/revoke":
            self._json(200, self.app.owner_updates.revoke(payload.get("deviceId")))
            return
        if path == "/api/feedback/access":
            self._json(200, self.app.feedback.change_access(payload))
            return
        if feedback_match:
            self._json(200, self.app.feedback.update(int(feedback_match[1]), payload))
            return
        if path == "/api/auth/logout":
            with self.app.session_lock:
                self.app.sessions.pop(token, None)
            self._json(200, {"ok": True}, headers={"Set-Cookie": self.app.cookie(self, "", expired=True)})
            return
        if path == "/api/customer-groups":
            result = self.app.customers.save_group(payload)
        elif re.fullmatch(r"/api/customer-groups/[0-9a-f-]{36}/update",path):
            result = self.app.customers.save_group(payload,path.split("/")[-2])
        elif re.fullmatch(r"/api/customer-users/[0-9a-f-]{36}/(group|profile|configure|pause|invite|revoke|restore|delete|claude)",path):
            identifier,action = path.split("/")[-2:]
            if action=='invite':
                result=self.app.customers.invite(identifier)
                if self.app.public_api_authority:
                    result['claimLink']='kone://claim?'+urlencode({
                        'server':'https://'+self.app.public_api_authority,
                        'code':result['inviteCode']})
                result['expiresAt']=int(time.time())+result['expiresIn']
            elif action in ('revoke','restore'):result=self.app.customers.service(identifier,restore=action=='restore')
            elif action=='delete':result=self.app.customers.delete(identifier)
            elif action=='claude':result=self.app.customers.claude(identifier,payload)
            else:result=self.app.customers.set_member(identifier,action,payload)
        elif re.fullmatch(r"/api/customer-users/[0-9a-f-]{36}/devices/[0-9a-f-]{36}/revoke",path):
            result = self.app.customers.revoke(path.split("/")[-4],path.split("/")[-2])
        elif path == "/api/fleet/enrollments":
            result = self.app.fleet.enrollment(payload)
        elif path == "/api/fleet/nodes":
            result = self.app.fleet.add_node(payload)
        elif path == "/api/fleet/exits":
            result = self.app.fleet.add_exit(payload)
        elif path == "/api/fleet/routes":
            result = self.app.fleet.add_route(payload)
        elif path == "/api/fleet/assignments":
            result = self.app.fleet.assign(payload)
        elif re.fullmatch(r"/api/fleet/routes/[0-9a-f-]{36}/probe", path):
            result = self.app.fleet.probe(path.split("/")[-2])
        elif re.fullmatch(r"/api/fleet/nodes/[0-9a-f-]{36}/toggle", path):
            result = self.app.fleet.toggle_node(path.split("/")[-2], payload)
        elif re.fullmatch(r"/api/fleet/servers/[0-9a-f-]{36}/(retry|renew|revoke|reinstall)", path):
            identifier, action = path.split("/")[-2:]
            if action == "reinstall":
                result = self.app.fleet.renew_enrollment(identifier, reinstall=True, address=payload.get("address"))
            else:
                result = {"retry": self.app.fleet.retry, "renew": self.app.fleet.renew_enrollment,
                          "revoke": self.app.fleet.revoke}[action](identifier)
        elif re.fullmatch(r"/api/profiles/[0-9a-f-]{36}/subscription", path):
            if not self.app.public_api_authority:
                raise APIError(503, "The public subscription endpoint is not configured.")
            created = self.app.store.create_subscription(path.split("/")[-2])
            base = "https://" + self.app.public_api_authority + "/api/client/subscriptions/" + created["token"]
            result = {"ok": True, "singBoxUrl": base + "/sing-box.json", "shareLinksUrl": None if created["linksError"] else base + "/nodes.txt", "linksError": created["linksError"]}
        elif re.fullmatch(r"/api/profiles/[0-9a-f-]{36}/subscription/revoke", path):
            result = self.app.store.revoke_subscription(path.split("/")[-3])
        elif re.fullmatch(r"/api/profiles/[0-9a-f-]{36}/update", path):
            result = self.app.store.update_profile(path.split("/")[-2], payload)
        elif path == "/api/profiles/preview":
            result = self.app.store.preview_profile(payload)
        elif path == "/api/profiles":
            result = self.app.store.import_profile(payload)
        elif path == "/api/users":
            result = self.app.store.add_user(payload)
        elif path == "/api/assignments":
            result = self.app.store.assign(payload)
        else:
            match = re.fullmatch(r"/api/profiles/([0-9a-f-]{36})/rename", path)
            if not match:
                raise APIError(404, "接口不存在")
            result = self.app.store.rename(match.group(1), payload)
        self._json(200, result)

    def _dispatch(self, method, head=False):
        try:
            if method == "GET":
                self._get(head=head)
            elif method == "POST":
                self._post()
            else:
                self.app.check_request(self)
                raise APIError(405, "不支持此操作")
        except (APIError, FleetError, CustomerError) as error:
            if method != "GET":
                self.close_connection = True
            self._json(error.status, {"error": error.message}, head=head)
        except (OSError, sqlite3.Error, ValueError, RecursionError):
            self.close_connection = True
            self._json(500, {"error": "操作失败，请稍后重试"}, head=head)

    def do_GET(self):
        self._dispatch("GET")

    def do_HEAD(self):
        self._dispatch("GET", head=True)

    def do_POST(self):
        self._dispatch("POST")

    def do_OPTIONS(self):
        self._dispatch("OPTIONS")

    def do_PUT(self):
        self._dispatch("PUT")

    def do_DELETE(self):
        self._dispatch("DELETE")


class ConsoleHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = True

    def __init__(self, *args, **kwargs):
        self.connection_slots = threading.BoundedSemaphore(64)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        if not self.connection_slots.acquire(blocking=False):
            try:
                request.settimeout(1)
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n"
                                b"Content-Length: 0\r\nRetry-After: 2\r\n\r\n")
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.connection_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connection_slots.release()

    def server_close(self):
        try:
            super().server_close()
        finally:
            if getattr(self, "data_lock", None) is not None:
                self.data_lock.close()


def create_server(host="127.0.0.1", port=8768, data_dir=None, web_dir=None, public_origin=None, node_origin=None, api_origin=None, trusted_proxy=None):
    origins = list(public_origin) if isinstance(public_origin, (list, tuple)) else ([public_origin] if public_origin else [])
    public_api = None
    if api_origin is not None:
        if not isinstance(api_origin, str) or not api_origin.strip():
            raise ValueError("公开 API 入口须是非空 HTTPS 地址")
        public_api = parse_origin(api_origin)
        if not public_api.startswith("https://") or urlsplit(public_api).hostname in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("公开 API 入口须是非本机的 HTTPS 地址")
        if host not in {"127.0.0.1", "::1", "localhost"}:
            raise ValueError("启用公开 API 入口时，后台须仅监听本机")
        origins.append(public_api)
    if trusted_proxy is not None and (trusted_proxy != "cloudflare-tunnel" or not public_api):
        raise ValueError("隧道来源识别只支持已配置公开 API 的 cloudflare-tunnel")
    server_class = ConsoleHTTPServer
    if ":" in host:
        class IPv6Server(ConsoleHTTPServer):
            address_family = socket.AF_INET6
        server_class = IPv6Server
    directory = data_dir if data_dir is not None else BASE_DIR / "var"
    lock = DataLock(directory)
    server = None
    try:
        store = ConsoleStore(directory)
        server = server_class((host, port), ConsoleHandler)
        server.data_lock = lock
        actual_port = server.server_address[1]
        server.app = ConsoleApplication(store, web_dir if web_dir is not None else BASE_DIR / "web",
                                        host, actual_port, public_origin=origins)
        if public_api:
            server.app.public_api_authority = urlsplit(public_api).netloc
        server.app.trusted_proxy = trusted_proxy
        if node_origin:
            origin = parse_origin(node_origin)
            if not origin.startswith("https://") or origin not in server.app.allowed_origins.get(urlsplit(origin).netloc,set()):
                raise ValueError("节点入口须是明确允许的 HTTPS 地址")
            server.app.node_origin = origin
    except Exception:
        if server is not None:
            server.server_close()
        lock.close()
        raise
    return server


def main():
    parser = argparse.ArgumentParser(description="KOne 本地管理控制台")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址，默认仅本机")
    parser.add_argument("--port", type=int, default=8768, help="监听端口，0 表示自动分配")
    parser.add_argument("--data-dir", type=Path, default=BASE_DIR / "var")
    parser.add_argument("--public-origin", action="append", help="显式信任的入口，可重复指定管理员私网入口与节点 HTTPS 入口")
    parser.add_argument("--node-origin", help="供 VPS 安装与同步使用的 HTTPS 入口")
    parser.add_argument("--api-origin", help="仅允许固定节点和客户接口的 HTTPS 公网入口，不提供管理页面")
    parser.add_argument("--trusted-proxy", choices=["cloudflare-tunnel"], help="仅在本机 Cloudflare 隧道后识别客户来源")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("port must be between 0 and 65535")
    try:
        for origin in args.public_origin or []:
            parse_origin(origin)
        server = create_server(args.host, args.port, args.data_dir, public_origin=args.public_origin,
                               node_origin=args.node_origin, api_origin=args.api_origin, trusted_proxy=args.trusted_proxy)
    except (OSError, ValueError) as error:
        parser.exit(1, "无法启动控制台：%s\n" % error)
    address = "[%s]" % args.host if ":" in args.host else args.host
    print("KOne 控制台：http://%s:%d" % (address, server.server_address[1]), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
