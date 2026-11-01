"""Persistent fleet control plane. No SSH, shell commands, or network calls here."""
import hashlib
import ipaddress
import json
import math
import re
import secrets
import ssl
import time
import uuid
from datetime import datetime, timezone
from urllib.parse import urlsplit

from customers import CLAUDE_DOMAINS


class FleetError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def uid():
    return str(uuid.uuid4())


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def text(value, maximum=80):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum or any(ord(c) < 32 for c in value):
        raise FleetError(400, "请填写有效名称或地址")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        raise FleetError(400, "内容含无效字符")
    return value.strip()


def host(value):
    value = text(value, 253)
    try:
        return str(ipaddress.ip_address(value.strip("[]")))
    except ValueError:
        if not re.fullmatch(r"(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", value) or ".." in value:
            raise FleetError(400, "地址须为 IP 或域名")
        return value.lower()


def port(value):
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 65535:
        raise FleetError(400, "端口须为 1–65535")
    return value


def required(connection, table, identifier):
    row = connection.execute("SELECT * FROM " + table + " WHERE id=?", (identifier,)).fetchone()
    if not row:
        raise FleetError(404, "记录不存在，请刷新")
    return row


def host_metrics(data):
    """Allowlisted resource values; a node cannot persist arbitrary payload fields."""
    if not isinstance(data, dict):
        return {}
    result = {}
    for key, maximum in {"hostname":100, "osName":100, "kernel":100, "cpuModel":160, "agentVersion":32}.items():
        value = data.get(key)
        if isinstance(value, str) and 0 < len(value) <= maximum and all(ord(c) >= 32 for c in value):
            result[key] = value
    limits = {"cpuCores":100000, "uptime":10**11, "memoryTotal":10**17, "memoryUsed":10**17,
              "diskTotal":10**19, "diskUsed":10**19, "networkReceived":10**21, "networkSent":10**21,
              "downloadRate":10**17, "uploadRate":10**17}
    for key, maximum in limits.items():
        value = data.get(key)
        if type(value) is int and 0 <= value <= maximum:
            result[key] = value
    for key in ("cpuPercent", "memoryPercent", "diskPercent"):
        value = data.get(key)
        if type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 100:
            result[key] = round(value, 1)
    load = data.get("load")
    if isinstance(load, list) and len(load) == 3 and all(type(v) in (int,float) and math.isfinite(v) and 0 <= v <= 10**6 for v in load):
        result["load"] = [round(v, 2) for v in load]
    for prefix in ("memory", "disk"):
        if prefix+"Total" in result and result.get(prefix+"Used", 0) > result[prefix+"Total"]:
            result.pop(prefix+"Used", None)
            result.pop(prefix+"Percent", None)
    return result


class Fleet:
    def __init__(self, store):
        self.store = store
        with store.connect() as c:
            if c.execute("PRAGMA user_version").fetchone()[0] > 5:
                raise ValueError("数据库来自较新的版本")
            c.executescript("""
                CREATE TABLE IF NOT EXISTS fleet_servers(
                  id TEXT PRIMARY KEY, name TEXT NOT NULL, address TEXT NOT NULL,
                  os TEXT NOT NULL DEFAULT '', arch TEXT NOT NULL DEFAULT '',
                  token_hash TEXT, certificate TEXT NOT NULL DEFAULT '',
                  last_seen REAL, mode TEXT NOT NULL DEFAULT 'unknown', revoked INTEGER NOT NULL DEFAULT 0,
                  desired INTEGER NOT NULL DEFAULT 0, applied INTEGER NOT NULL DEFAULT 0,
                  runtime_status TEXT NOT NULL DEFAULT 'unregistered', created TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS fleet_enrollments(
                  token_hash TEXT PRIMARY KEY, server_id TEXT NOT NULL REFERENCES fleet_servers(id),
                  expires REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS fleet_nodes(
                  id TEXT PRIMARY KEY, server_id TEXT NOT NULL REFERENCES fleet_servers(id),
                  name TEXT NOT NULL, kind TEXT NOT NULL, port INTEGER NOT NULL,
                  enabled INTEGER NOT NULL DEFAULT 1, created TEXT NOT NULL,
                  UNIQUE(server_id,kind,port));
                CREATE TABLE IF NOT EXISTS fleet_exits(
                  id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL,
                  address TEXT NOT NULL, port INTEGER NOT NULL, username TEXT NOT NULL,
                  password TEXT NOT NULL, tls INTEGER NOT NULL DEFAULT 0, created TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS fleet_routes(
                  id TEXT PRIMARY KEY, name TEXT NOT NULL, node_ids TEXT NOT NULL,
                  exit_id TEXT REFERENCES fleet_exits(id), created TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS fleet_assignments(
                  user_id TEXT PRIMARY KEY REFERENCES users(id), route_id TEXT NOT NULL REFERENCES fleet_routes(id),
                  profile_id TEXT REFERENCES profiles(id), status TEXT NOT NULL DEFAULT 'pending');
                CREATE TABLE IF NOT EXISTS fleet_credentials(
                  route_id TEXT NOT NULL REFERENCES fleet_routes(id),
                  node_id TEXT NOT NULL REFERENCES fleet_nodes(id),
                  principal TEXT NOT NULL, credential TEXT NOT NULL,
                  PRIMARY KEY(route_id,node_id,principal));
                CREATE TABLE IF NOT EXISTS fleet_jobs(
                  id TEXT PRIMARY KEY, server_id TEXT NOT NULL REFERENCES fleet_servers(id),
                  revision INTEGER NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL,
                  lease TEXT, lease_until REAL, message TEXT NOT NULL DEFAULT '',
                  created TEXT NOT NULL, finished TEXT);
                CREATE TABLE IF NOT EXISTS fleet_probes(
                  route_id TEXT PRIMARY KEY REFERENCES fleet_routes(id),
                  ip TEXT, checked TEXT, status TEXT NOT NULL DEFAULT 'unverified');
                CREATE TABLE IF NOT EXISTS fleet_host_metrics(
                  server_id TEXT PRIMARY KEY REFERENCES fleet_servers(id),
                  payload TEXT NOT NULL, received REAL NOT NULL);
            """)
            version = c.execute("PRAGMA user_version").fetchone()[0]
            c.execute("PRAGMA user_version="+str(max(2,version)))
        store.restrict_files()

    def audit(self, c, title, detail, status="完成"):
        c.execute("INSERT INTO records(time,title,detail,result) VALUES(?,?,?,?)", (now(), title, detail, status))

    def enrollment(self, data):
        name, address = text(data.get("name")), host(data.get("address"))
        token, identifier = secrets.token_urlsafe(32), uid()
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            c.execute("INSERT INTO fleet_servers(id,name,address,created) VALUES(?,?,?,?)", (identifier, name, address, now()))
            c.execute("INSERT INTO fleet_enrollments VALUES(?,?,?,0)", (digest(token), identifier, time.time()+900))
            self.audit(c, "创建接入凭据", name, "等待接入")
        return {"id": identifier, "token": token, "expiresIn": 900}

    def renew_enrollment(self, identifier, reinstall=False, address=None):
        token = secrets.token_urlsafe(32)
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            s = required(c, "fleet_servers", identifier)
            if s["revoked"]:
                raise FleetError(409, "该接入已撤销，请重新添加服务器")
            if s["token_hash"] and not reinstall:
                raise FleetError(409, "服务器已接入")
            if reinstall:
                if address is not None:
                    c.execute("UPDATE fleet_servers SET address=? WHERE id=?",(host(address),identifier))
                c.execute("UPDATE fleet_servers SET token_hash=NULL,certificate='',last_seen=NULL,applied=0,runtime_status='unregistered' WHERE id=?", (identifier,))
                c.execute("DELETE FROM fleet_host_metrics WHERE server_id=?", (identifier,))
                c.execute("UPDATE fleet_jobs SET status='superseded',finished=? WHERE server_id=? AND status IN ('queued','leased')", (now(),identifier))
                for route in c.execute("SELECT * FROM fleet_routes").fetchall():
                    if any(required(c,'fleet_nodes',n)["server_id"] == identifier for n in json.loads(route["node_ids"])):
                        c.execute("UPDATE fleet_assignments SET status='pending' WHERE route_id=?", (route["id"],))
                        c.execute("UPDATE fleet_probes SET status='stale' WHERE route_id=?", (route["id"],))
                self.audit(c, "准备重装接入", s["name"], "等待新节点")
            c.execute("UPDATE fleet_enrollments SET used=1 WHERE server_id=?", (identifier,))
            c.execute("INSERT INTO fleet_enrollments VALUES(?,?,?,0)", (digest(token), identifier, time.time()+900))
        return {"id": identifier, "token": token, "expiresIn": 900}

    def register(self, data):
        token = text(data.get("token"), 128)
        certificate = data.get("certificate", "")
        if not isinstance(certificate, str) or len(certificate) > 16384:
            raise FleetError(400, "证书无效")
        try:
            ssl.PEM_cert_to_DER_cert(certificate)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.load_verify_locations(cadata=certificate)
        except (ValueError, TypeError, ssl.SSLError):
            raise FleetError(400, "节点需提供有效 PEM 证书")
        mode = data.get("mode")
        if mode not in ("stage", "process", "systemd"):
            raise FleetError(400, "节点运行模式无效")
        secret = secrets.token_urlsafe(48)
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            ticket = c.execute("SELECT * FROM fleet_enrollments WHERE token_hash=?", (digest(token),)).fetchone()
            if not ticket or ticket["used"] or ticket["expires"] < time.time():
                raise FleetError(401, "接入凭据已失效")
            s = required(c, "fleet_servers", ticket["server_id"])
            if s["revoked"]:
                raise FleetError(401, "接入凭据已撤销")
            if "address" in data and host(data["address"]) != s["address"]:
                raise FleetError(400, "证书地址与控制台连接地址不一致")
            if s["token_hash"]:
                raise FleetError(409, "服务器已接入")
            c.execute("UPDATE fleet_servers SET token_hash=?,certificate=?,last_seen=?,os=?,arch=?,mode=?,runtime_status='idle' WHERE id=?",
                      (digest(secret), certificate, time.time(), text(data.get("os"), 100),
                       text(data.get("arch"), 32), mode, s["id"]))
            c.execute("UPDATE fleet_enrollments SET used=1 WHERE server_id=?", (s["id"],))
            if c.execute("SELECT 1 FROM fleet_nodes WHERE server_id=?", (s["id"],)).fetchone():
                affected = {s["id"]}
                for route in c.execute("SELECT * FROM fleet_routes").fetchall():
                    nodes = [required(c,'fleet_nodes',n) for n in json.loads(route["node_ids"])]
                    if any(n["server_id"] == s["id"] for n in nodes):
                        affected.update(n["server_id"] for n in nodes)
                for identifier in sorted(affected):
                    peer = required(c,'fleet_servers',identifier)
                    ready = True
                    for linked in c.execute("SELECT node_ids FROM fleet_routes").fetchall():
                        path = [required(c,'fleet_nodes',n) for n in json.loads(linked["node_ids"])]
                        if any(n["server_id"] == identifier for n in path):
                            if any(not required(c,'fleet_servers',n["server_id"])["token_hash"] or
                                   required(c,'fleet_servers',n["server_id"])["revoked"] for n in path):
                                ready = False
                    if peer["token_hash"] and not peer["revoked"] and ready:
                        self.queue(c, identifier)
            self.audit(c, "服务器已接入", s["name"])
        return {"serverId": s["id"], "credential": secret, "address": s["address"]}

    def authenticate(self, bearer):
        if not isinstance(bearer, str) or not bearer.startswith("Bearer ") or len(bearer) > 256:
            raise FleetError(401, "节点认证失败")
        with self.store.connect() as c:
            s = c.execute("SELECT * FROM fleet_servers WHERE token_hash=? AND revoked=0", (digest(bearer[7:]),)).fetchone()
            if not s:
                raise FleetError(401, "节点认证失败")
            return dict(s)

    def state(self):
        with self.store.connect() as c:
            c.execute("BEGIN")
            servers = []
            for s in c.execute("SELECT * FROM fleet_servers ORDER BY created,id"):
                metric = c.execute("SELECT payload,received FROM fleet_host_metrics WHERE server_id=?", (s["id"],)).fetchone()
                servers.append({"id": s["id"], "name": s["name"], "address": s["address"], "os": s["os"], "arch": s["arch"],
                                "registered": bool(s["token_hash"]), "online": not s["revoked"] and bool(s["last_seen"] and time.time()-s["last_seen"] < 90),
                                "lastSeen": s["last_seen"], "mode": s["mode"], "revoked": bool(s["revoked"]),
                                "desired": s["desired"], "applied": s["applied"], "status": s["runtime_status"],
                                "metrics": json.loads(metric["payload"]) if metric else {},
                                "metricsAt": metric["received"] if metric else None,
                                "metricsFresh": bool(metric and not s["revoked"] and time.time()-metric["received"] < 90)})
            nodes = [{"id": n["id"], "serverId": n["server_id"], "name": n["name"], "kind": n["kind"], "port": n["port"], "enabled": bool(n["enabled"])}
                     for n in c.execute("SELECT * FROM fleet_nodes ORDER BY created,id")]
            exits = [{"id": e["id"], "name": e["name"], "kind": e["kind"], "address": e["address"], "port": e["port"], "tls": bool(e["tls"])}
                     for e in c.execute("SELECT * FROM fleet_exits ORDER BY created,id")]
            routes = []
            for r in c.execute("SELECT * FROM fleet_routes ORDER BY created,id"):
                assignments = [dict(a) for a in c.execute("SELECT user_id AS userId,status FROM fleet_assignments WHERE route_id=?", (r["id"],))]
                probe = c.execute("SELECT ip,checked,status FROM fleet_probes WHERE route_id=?", (r["id"],)).fetchone()
                routes.append({"id": r["id"], "name": r["name"], "nodeIds": json.loads(r["node_ids"]),
                               "exitId": r["exit_id"], "tcpOnly": bool(r["exit_id"]), "assignments": assignments,
                               "probe": dict(probe) if probe else {"ip": None, "checked": None, "status": "unverified"}})
            for r in routes:
                linked = [s for s in servers if any(n["serverId"] == s["id"] for n in nodes if n["id"] in r["nodeIds"])]
                r["status"] = ("blocked" if any(s["revoked"] for s in linked) else
                               "paused" if any(not n["enabled"] for n in nodes if n["id"] in r["nodeIds"]) else
                               "pending" if any(s["status"] != "running" or s["desired"] != s["applied"] for s in linked) else
                               "offline" if any(not s["online"] for s in linked) else "running")
            jobs = [dict(j) for j in c.execute("SELECT id,server_id AS serverId,revision,status,message,created,finished FROM fleet_jobs ORDER BY rowid DESC LIMIT 100")]
            return {"servers": servers, "nodes": nodes, "exits": exits, "routes": routes, "jobs": jobs}

    def add_node(self, data):
        kind = data.get("kind")
        if kind not in ("vless", "hysteria2"):
            raise FleetError(409, "V＋H 尚未完成兼容性验证")
        server_id, number = text(data.get("serverId")), port(data.get("port"))
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            s = required(c, "fleet_servers", server_id)
            if not s["token_hash"] or s["revoked"]:
                raise FleetError(409, "请先接入服务器")
            if c.execute("SELECT 1 FROM fleet_nodes WHERE server_id=? AND kind=? AND port=?", (server_id, kind, number)).fetchone():
                raise FleetError(409, "协议端口已存在")
            identifier = uid()
            c.execute("INSERT INTO fleet_nodes VALUES(?,?,?,?,?,1,?)", (identifier, server_id, text(data.get("name")), kind, number, now()))
            self.queue(c, server_id)
            self.audit(c, "部署协议", s["name"] + " / " + kind, "等待节点执行")
        return {"ok": True, "id": identifier}

    def add_exit(self, data):
        kind = data.get("kind")
        if kind not in ("socks", "http"):
            raise FleetError(400, "支持 SOCKS5 或 HTTP CONNECT 出口")
        username, password = data.get("username", ""), data.get("password", "")
        if any(not isinstance(v, str) or len(v) > 1024 or any(ord(ch) < 32 for ch in v) for v in (username, password)):
            raise FleetError(400, "出口凭据无效")
        tls = data.get("tls", False)
        if not isinstance(tls, bool) or (tls and kind != "http"):
            raise FleetError(400, "TLS 仅适用于 HTTPS CONNECT")
        identifier = uid()
        with self.store.connect() as c:
            c.execute("INSERT INTO fleet_exits VALUES(?,?,?,?,?,?,?,?,?)",
                      (identifier, text(data.get("name")), kind, host(data.get("address")), port(data.get("port")),
                       username, password, int(tls), now()))
            self.audit(c, "新增出口", text(data.get("name")))
        return {"ok": True, "id": identifier}

    def add_route(self, data):
        ids = data.get("nodeIds")
        if not isinstance(ids, list) or not 1 <= len(ids) <= 4 or any(not isinstance(i, str) for i in ids) or len(set(ids)) != len(ids):
            raise FleetError(400, "线路须包含 1–4 个不重复节点")
        identifier = uid()
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            route_nodes = [required(c, "fleet_nodes", i) for i in ids]
            if len(set(n["server_id"] for n in route_nodes)) != len(ids):
                raise FleetError(400, "同一 VPS 不得重复经过")
            exit_id = data.get("exitId") or None
            if exit_id:
                required(c, "fleet_exits", text(exit_id))
            c.execute("INSERT INTO fleet_routes VALUES(?,?,?,?,?)", (identifier, text(data.get("name")), json.dumps(ids), exit_id, now()))
            for n in route_nodes:
                self.queue(c, n["server_id"])
            self.audit(c, "保存线路", text(data.get("name")), "未分配")
        return {"ok": True, "id": identifier}

    def credentials(self, c, route_id, node_id, principal):
        row = c.execute("SELECT credential FROM fleet_credentials WHERE route_id=? AND node_id=? AND principal=?",
                        (route_id, node_id, principal)).fetchone()
        if row:
            return row[0]
        n = required(c, "fleet_nodes", node_id)
        value = uid() if n["kind"] == "vless" else secrets.token_urlsafe(32)
        c.execute("INSERT INTO fleet_credentials VALUES(?,?,?,?)", (route_id, node_id, principal, value))
        return value

    def outbound(self, c, node_id, credential, tag):
        n = required(c, "fleet_nodes", node_id)
        s = required(c, "fleet_servers", n["server_id"])
        return {"type": n["kind"], "tag": tag, "server": s["address"], "server_port": n["port"],
                ("uuid" if n["kind"] == "vless" else "password"): credential,
                "tls": {"enabled": True, "server_name": s["address"], "certificate": s["certificate"]}}

    def external_outbound(self, c, exit_id, tag):
        e = required(c, "fleet_exits", exit_id)
        result = {"type": e["kind"], "tag": tag, "server": e["address"], "server_port": e["port"]}
        if e["kind"] == "socks":
            result["version"] = "5"
        if e["username"]:
            result["username"], result["password"] = e["username"], e["password"]
        if e["tls"]:
            result["tls"] = {"enabled": True, "server_name": e["address"]}
        return result

    def compile(self, c, server_id):
        inbounds, outbounds, rules = [], [{"type": "direct", "tag": "direct"}], []
        customer_columns={row[1] for row in c.execute('PRAGMA table_info(customer_accounts)')}
        revoked={r[0] for r in c.execute('SELECT user_id FROM customer_accounts WHERE revoked=1')} if 'revoked' in customer_columns else set()
        no_claude={r[0] for r in c.execute('SELECT user_id FROM customer_accounts WHERE block_claude=1')} if 'block_claude' in customer_columns else set()
        refused = set()
        for n in c.execute("SELECT * FROM fleet_nodes WHERE server_id=? AND enabled=1 ORDER BY id", (server_id,)):
            accounts = []
            for r in c.execute("SELECT * FROM fleet_routes ORDER BY id"):
                ids = json.loads(r["node_ids"])
                if n["id"] not in ids:
                    continue
                index = ids.index(n["id"])
                principals = [a[0] for a in c.execute("SELECT user_id FROM fleet_assignments WHERE route_id=? ORDER BY user_id", (r["id"],)) if a[0] not in revoked]
                # A route probe account follows precisely the same exit as customer traffic.
                principals.append("probe")
                for principal in principals:
                    account = r["id"] + "/" + principal
                    if principal in no_claude:
                        refused.add(account)
                    credential = self.credentials(c, r["id"], n["id"], principal)
                    accounts.append({"name": account, ("uuid" if n["kind"] == "vless" else "password"): credential})
                    tag = "direct"
                    if index < len(ids)-1:
                        tag = "next-" + n["id"] + "-" + account.replace("/", "-")
                        next_credential = self.credentials(c, r["id"], ids[index+1], principal)
                        outbounds.append(self.outbound(c, ids[index+1], next_credential, tag))
                    elif r["exit_id"]:
                        tag = "exit-" + n["id"] + "-" + account.replace("/", "-")
                        outbounds.append(self.external_outbound(c, r["exit_id"], tag))
                    rules.append({"inbound": ["in-"+n["id"]], "auth_user": [account], "action": "route", "outbound": tag})
            # Keep idle services closed to unauthenticated traffic even before a route exists.
            if not accounts:
                accounts = [{"name": "unassigned", ("uuid" if n["kind"] == "vless" else "password"): uid() if n["kind"] == "vless" else secrets.token_urlsafe(32)}]
            inbounds.append({"type": n["kind"], "tag": "in-"+n["id"], "listen": "::", "listen_port": n["port"],
                             "users": accounts, "tls": {"enabled": True, "certificate_path": "tls.crt", "key_path": "tls.key"}})
        # Reject unmatched users, never silently send an incomplete chain DIRECT.
        rules.append({"action": "reject"})
        if refused:
            # Claude is refused for the customers the owner chose. Only their connections are
            # sniffed for the site name, so nobody else's traffic waits on it.
            names = sorted(refused)
            rules[:0] = [{"auth_user": names, "action": "sniff"},
                         {"auth_user": names, "domain_suffix": list(CLAUDE_DOMAINS), "action": "reject"}]
        return {"log": {"level": "error", "timestamp": False}, "inbounds": inbounds,
                "outbounds": outbounds, "route": {"rules": rules}}

    def queue(self, c, server_id):
        s = required(c, "fleet_servers", server_id)
        if s["revoked"]:
            raise FleetError(409, "线路包含已撤销服务器")
        revision = s["desired"] + 1
        for j in c.execute("SELECT id,payload FROM fleet_jobs WHERE server_id=? AND status='queued'", (server_id,)).fetchall():
            if json.loads(j["payload"]).get("kind") == "apply":
                c.execute("UPDATE fleet_jobs SET status='superseded',finished=? WHERE id=?", (now(), j["id"]))
        payload = {"kind": "apply", "config": self.compile(c, server_id)}
        serialized = json.dumps(payload)
        if len(serialized.encode("utf-8")) > 768 * 1024:
            raise FleetError(413, "节点配置过大，请减少单台服务器的用户和线路")
        c.execute("INSERT INTO fleet_jobs(id,server_id,revision,payload,status,created) VALUES(?,?,?,?,?,?)",
                  (uid(), server_id, revision, serialized, "queued", now()))
        c.execute("UPDATE fleet_servers SET desired=?,runtime_status='pending' WHERE id=?", (revision, server_id))

    def assign(self, data):
        ids = data.get("userIds")
        if not isinstance(ids, list) or not ids or len(ids) > 1000 or any(not isinstance(i, str) for i in ids):
            raise FleetError(400, "请选择用户")
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            r = required(c, "fleet_routes", text(data.get("routeId")))
            route_nodes = [required(c, "fleet_nodes", i) for i in json.loads(r["node_ids"])]
            if any(not n["enabled"] for n in route_nodes):
                raise FleetError(409, "线路包含已停用节点")
            for n in route_nodes:
                s = required(c, "fleet_servers", n["server_id"])
                if s["revoked"] or not s["token_hash"]:
                    raise FleetError(409, "请先接入线路上的服务器")
            affected = {n["server_id"] for n in route_nodes}
            for user_id in dict.fromkeys(ids):
                required(c, "users", user_id)
                columns={r[1] for r in c.execute('PRAGMA table_info(customer_accounts)')}
                if 'revoked' in columns and c.execute('SELECT 1 FROM customer_accounts WHERE user_id=? AND revoked=1',(user_id,)).fetchone():
                    raise FleetError(409,'客户服务已收回，请先恢复')
                old = c.execute("SELECT route_id FROM fleet_assignments WHERE user_id=?", (user_id,)).fetchone()
                if old:
                    old_route = required(c, "fleet_routes", old["route_id"])
                    affected.update(required(c, "fleet_nodes", i)["server_id"] for i in json.loads(old_route["node_ids"]))
                c.execute("INSERT INTO fleet_assignments(user_id,route_id,status) VALUES(?,?,'pending') ON CONFLICT(user_id) DO UPDATE SET route_id=excluded.route_id,status='pending',profile_id=NULL",
                          (user_id, r["id"]))
            # Reconcile old and new paths, without restarting unrelated VPS services.
            for identifier in sorted(affected):
                s = required(c, "fleet_servers", identifier)
                if not s["revoked"]:
                    self.queue(c, identifier)
            self.audit(c, "分配线路", r["name"] + " / " + str(len(set(ids))) + " 位用户", "等待节点应用")
        return {"ok": True}

    def retry(self, server_id):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            s = required(c, "fleet_servers", server_id)
            if not s["token_hash"]:
                raise FleetError(409, "请先接入服务器")
            self.queue(c, server_id)
            self.audit(c, "重新下发配置", s["name"], "等待节点执行")
        return {"ok": True}

    def toggle_node(self, node_id, data):
        enabled = data.get("enabled")
        if not isinstance(enabled, bool):
            raise FleetError(400, "节点状态无效")
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            n = required(c, "fleet_nodes", node_id)
            c.execute("UPDATE fleet_nodes SET enabled=? WHERE id=?", (int(enabled), node_id))
            for r in c.execute("SELECT id,node_ids FROM fleet_routes").fetchall():
                if node_id not in json.loads(r["node_ids"]):
                    continue
                active = all(required(c, "fleet_nodes", i)["enabled"] for i in json.loads(r["node_ids"]))
                c.execute("UPDATE fleet_assignments SET status=? WHERE route_id=?", ("pending" if active else "blocked", r["id"]))
                c.execute("UPDATE fleet_probes SET status='stale' WHERE route_id=?", (r["id"],))
            self.queue(c, n["server_id"])
            self.audit(c, "启用协议" if enabled else "停用协议", n["name"], "等待节点执行")
        return {"ok": True}

    def poll(self, server_id, health=None):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            c.execute("UPDATE fleet_servers SET last_seen=? WHERE id=?", (time.time(), server_id))
            if health:
                if "metrics" in health:
                    metrics = host_metrics(health["metrics"])
                    if metrics:
                        c.execute("INSERT INTO fleet_host_metrics VALUES(?,?,?) ON CONFLICT(server_id) DO UPDATE SET payload=excluded.payload,received=excluded.received",
                                  (server_id,json.dumps(metrics,allow_nan=False),time.time()))
                s = required(c, "fleet_servers", server_id)
                # Availability of the management agent and the proxy runtime are independent.
                if s["runtime_status"] == "running" and health.get("runtime") == "stopped":
                    c.execute("UPDATE fleet_servers SET runtime_status='stopped' WHERE id=?", (server_id,))
                elif (s["runtime_status"] == "stopped" and health.get("runtime") == "running"
                      and health.get("revision") == s["applied"] == s["desired"]):
                    c.execute("UPDATE fleet_servers SET runtime_status='running' WHERE id=?", (server_id,))
            current = c.execute("SELECT * FROM fleet_jobs WHERE server_id=? AND status='leased' ORDER BY revision LIMIT 1", (server_id,)).fetchone()
            if current and current["lease_until"] > time.time():
                return {"job": None}
            job = current or c.execute("SELECT * FROM fleet_jobs WHERE server_id=? AND status='queued' ORDER BY revision LIMIT 1", (server_id,)).fetchone()
            if not job:
                return {"job": None}
            lease = secrets.token_urlsafe(24)
            c.execute("UPDATE fleet_jobs SET status='leased',lease=?,lease_until=? WHERE id=?", (lease, time.time()+180, job["id"]))
            return {"job": {"id": job["id"], "revision": job["revision"], "lease": lease, **json.loads(job["payload"])}}

    def probe(self, route_id):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            r = required(c, "fleet_routes", route_id)
            ns = [required(c, "fleet_nodes", i) for i in json.loads(r["node_ids"])]
            ss = [required(c, "fleet_servers", n["server_id"]) for n in ns]
            if any(not n["enabled"] for n in ns) or any(s["revoked"] or s["runtime_status"] != "running" or s["applied"] != s["desired"]
                   or not s["last_seen"] or time.time()-s["last_seen"] >= 90 for s in ss):
                raise FleetError(409, "请等待线路上的节点应用完成并在线")
            recent = c.execute("SELECT status,checked FROM fleet_probes WHERE route_id=?", (route_id,)).fetchone()
            if recent and recent["status"] == "pending":
                active = c.execute("SELECT payload FROM fleet_jobs WHERE server_id=? AND status IN ('queued','leased')",
                                   (ss[0]["id"],)).fetchall()
                if any(json.loads(j["payload"]).get("routeId") == route_id for j in active):
                    raise FleetError(409, "该线路正在检测")
            credential = self.credentials(c, route_id, ns[0]["id"], "probe")
            outbound = self.outbound(c, ns[0]["id"], credential, "probe-route")
            # Measure from the entry listener through the exact configured egress.
            # This avoids NAT hairpin requirements; TLS still verifies the registered address.
            outbound["server"] = "127.0.0.1"
            payload = {"kind": "probe", "routeId": route_id, "outbound": outbound,
                       "pathRevisions": {s["id"]: s["desired"] for s in ss}}
            c.execute("INSERT INTO fleet_jobs(id,server_id,revision,payload,status,created) VALUES(?,?,?,?,?,?)",
                      (uid(), ss[0]["id"], ss[0]["desired"], json.dumps(payload), "queued", now()))
            c.execute("INSERT INTO fleet_probes(route_id,status) VALUES(?,'pending') ON CONFLICT(route_id) DO UPDATE SET status='pending'",
                      (route_id,))
            self.audit(c, "检测线路出口", r["name"], "等待节点执行")
        return {"ok": True}

    def complete(self, server_id, data):
        status = data.get("status")
        if status not in ("running", "staged", "failed", "probed"):
            raise FleetError(400, "任务状态无效")
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            j = required(c, "fleet_jobs", text(data.get("jobId")))
            lease = data.get("lease")
            if not isinstance(lease, str) or not lease.isascii() or len(lease)>128:
                raise FleetError(403, "任务凭据不匹配")
            if j["server_id"] != server_id or not secrets.compare_digest(j["lease"] or "", lease):
                raise FleetError(403, "任务凭据不匹配")
            if j["status"] in ("running", "staged", "failed", "probed"):
                return {"ok": True}
            if j["status"] != "leased":
                raise FleetError(409, "任务已失效")
            s = required(c, "fleet_servers", server_id)
            payload = json.loads(j["payload"])
            is_probe = payload["kind"] == "probe"
            if is_probe:
                if status not in ("probed", "failed"):
                    raise FleetError(400, "出口检测结果无效")
                r = required(c, "fleet_routes", payload["routeId"])
                unchanged = all((lambda ss: not ss["revoked"] and ss["desired"] == revision and
                                ss["applied"] == revision and ss["runtime_status"] == "running")
                                (required(c, "fleet_servers", identifier))
                                for identifier, revision in payload["pathRevisions"].items())
                if status == "probed":
                    try:
                        address = ipaddress.ip_address(data.get("ip", ""))
                    except ValueError:
                        raise FleetError(400, "检测结果须为 IP 地址")
                    if not address.is_global:
                        raise FleetError(400, "出口检测须返回公网 IP")
                    status = "probed" if unchanged else "superseded"
                final = "verified" if status == "probed" else "stale" if status == "superseded" else "failed"
                c.execute("UPDATE fleet_jobs SET status=?,message=?,finished=? WHERE id=?",
                          (status, {"probed":"出口检测完成","failed":"出口检测失败","superseded":"检测期间线路已改变"}[status], now(), j["id"]))
                if status == "probed":
                    c.execute("UPDATE fleet_probes SET ip=?,checked=?,status=? WHERE route_id=?", (str(address), now(), final, r["id"]))
                else:
                    c.execute("UPDATE fleet_probes SET status=? WHERE route_id=?", (final, r["id"]))
                self.audit(c, "线路出口检测", r["name"], "完成" if status=="probed" else "未通过")
                return {"ok": True}
            if status == "probed":
                raise FleetError(400, "部署任务不能返回出口检测结果")
            if status == "running" and s["mode"] == "stage":
                raise FleetError(400, "暂存模式不能上报运行成功")
            # Whitelisted result messages avoid persisting raw logs or secrets.
            code = data.get("code", "")
            if not isinstance(code, str):
                code = "internal_error"
            messages = {"config_invalid":"核心配置校验失败", "start_failed":"启动失败，已尝试恢复原配置",
                        "port_busy":"端口已占用", "engine_missing":"核心尚未安装", "staged":"配置已暂存",
                        "running":"服务已启动", "internal_error":"节点执行失败"}
            message = messages.get(code, messages.get(status, "节点执行失败"))
            c.execute("UPDATE fleet_jobs SET status=?,message=?,finished=? WHERE id=?", (status, message, now(), j["id"]))
            if j["revision"] == s["desired"]:
                c.execute("UPDATE fleet_servers SET applied=?,runtime_status=?,last_seen=? WHERE id=?",
                          (j["revision"] if status=="running" else s["applied"], status, time.time(), server_id))
            self.audit(c, "节点执行结果", s["name"] + " / " + message, {"running":"服务已启动","staged":"仅暂存","failed":"失败"}[status])
            self.publish_ready(c)
        return {"ok": True}

    def publish_ready(self, c):
        pending = c.execute("SELECT * FROM fleet_assignments WHERE status='pending'").fetchall()
        for a in pending:
            columns={r[1] for r in c.execute('PRAGMA table_info(customer_accounts)')}
            if 'revoked' in columns and c.execute('SELECT 1 FROM customer_accounts WHERE user_id=? AND revoked=1',(a['user_id'],)).fetchone():
                c.execute("UPDATE fleet_assignments SET status='revoked' WHERE user_id=?",(a['user_id'],))
                continue
            r = required(c, "fleet_routes", a["route_id"])
            ns = [required(c, "fleet_nodes", i) for i in json.loads(r["node_ids"])]
            servers = [required(c, "fleet_servers", n["server_id"]) for n in ns]
            if any(s["revoked"] or s["runtime_status"]!="running" or s["applied"]!=s["desired"] for s in servers):
                continue
            entry = ns[0]
            credential = self.credentials(c, r["id"], entry["id"], a["user_id"])
            outbound = self.outbound(c, entry["id"], credential, "kone-route")
            if r["exit_id"]:
                outbound["network"] = "tcp"  # External proxy UDP capability is not verified in v1.
            config = {"log": {"level": "warn"}, "outbounds": [outbound], "route": {"final": "kone-route"}}
            profile_id = uid()
            version = c.execute("SELECT COALESCE(MAX(version_number),0)+1 FROM profiles").fetchone()[0]
            user = required(c, "users", a["user_id"])
            c.execute("INSERT INTO profiles VALUES(?,?,?,?,?,?,?,?,?,?)",
                      (profile_id, r["name"]+" · "+user["name"], version, json.dumps(config, ensure_ascii=False),
                       1, json.dumps([entry["kind"]]), 0, 0, now(), "线路生成"))
            c.execute("UPDATE users SET profile_id=? WHERE id=?", (profile_id, a["user_id"]))
            c.execute("UPDATE fleet_assignments SET status='ready',profile_id=? WHERE user_id=?", (profile_id, a["user_id"]))
            self.audit(c, "客户配置已生成", user["name"] + " / " + r["name"], "客户端未同步")

    def revoke(self, identifier):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            s = required(c, "fleet_servers", identifier)
            c.execute("UPDATE fleet_servers SET revoked=1,runtime_status='revoked' WHERE id=?", (identifier,))
            c.execute("UPDATE fleet_enrollments SET used=1 WHERE server_id=?", (identifier,))
            c.execute("UPDATE fleet_jobs SET status='superseded',finished=? WHERE server_id=? AND status IN ('queued','leased')", (now(), identifier))
            for r in c.execute("SELECT id,node_ids FROM fleet_routes").fetchall():
                if any(required(c,"fleet_nodes",i)["server_id"] == identifier for i in json.loads(r["node_ids"])):
                    c.execute("UPDATE fleet_assignments SET status='blocked' WHERE route_id=?", (r["id"],))
                    c.execute("UPDATE fleet_probes SET status='stale' WHERE route_id=?", (r["id"],))
            self.audit(c, "撤销管理接入", s["name"], "服务未远程停止")
        return {"ok": True}
