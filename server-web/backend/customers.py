"""Customer policies, one-time enrollment and device-scoped configuration delivery.

No network probes, remote commands or proxy restarts. Client diagnostic counters
are kept separate from entry-node usage; access gates do not revoke copied nodes.
"""
import hashlib
import json
import re
import secrets
import time
from datetime import datetime, timezone
import uuid
from contextlib import nullcontext

DEFAULT_GROUP = "00000000-0000-4000-8000-000000000001"
TABLES = {"customer_groups", "customer_accounts", "customer_invites", "customer_devices",
          "customer_deliveries", "customer_events", "customer_usage", "customer_node_counters", "customer_service_actions"}
EVENTS = {
    "connection_ok": "连接成功", "connection_failed": "连接失败", "dns_failed": "DNS 失败",
    "config_invalid": "配置校验失败", "network_changed": "网络切换", "app_started": "应用启动",
    "tunnel_stopped": "连接已停止", "retrying": "正在重试", "invitation_issued": "生成领取码",
    "device_bound": "设备已绑定", "device_revoked": "设备凭据已撤销", "group_changed": "调整分组",
    "policy_updated": "分组策略已更新", "profile_changed": "调整配置", "paused": "暂停配置下发",
    "resumed": "恢复配置下发", "received": "客户端报告已收到", "applied": "客户端报告已应用",
    "failed": "客户端报告应用失败", "quota_reached": "已采集用量达到额度",
    "service_revoked": "收回客户服务", "service_restored": "恢复客户服务",
    "claude_blocked": "拦下 Claude", "claude_allowed": "放行 Claude",
}
CLIENT_EVENTS = {"connection_ok", "connection_failed", "dns_failed", "config_invalid",
                 "network_changed", "app_started", "tunnel_stopped", "retrying"}
ACK_CODES = {"ok", "config_invalid", "start_failed", "network_unavailable", "rollback", "unknown"}
# Claude and Anthropic, refused for a customer the owner chose: on the console's own servers,
# and as a rule in the configuration that customer's KOne receives.
CLAUDE_DOMAINS = ("anthropic.com", "claude.ai", "claude.com", "claudeusercontent.com")


class CustomerError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status, self.message = status, message


def text(value, maximum=80):
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > maximum:
        raise CustomerError(400, "文本格式无效")
    value = value.strip()
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
        raise CustomerError(400, "文本含无效字符")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        raise CustomerError(400, "文本含无效字符")
    return value


def integer(value, maximum=2**60, minimum=0):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise CustomerError(400, "数值超出允许范围")
    return value


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def refuse_claude(config):
    """The configuration with Claude refused ahead of every other route rule.

    A KOne app that builds its own template is to carry a source's reject rules over (the phone
    does not yet); a client that uses the source as it is refuses the same domains."""
    route = config.get("route") if isinstance(config.get("route"), dict) else {}
    rules = route.get("rules") if isinstance(route.get("rules"), list) else []
    return {**config, "route": {**route, "rules": [{"domain_suffix": list(CLAUDE_DOMAINS), "action": "reject"}, *rules]}}


def period():
    return datetime.fromtimestamp(time.time(), timezone.utc).strftime("%Y-%m")


def required(c, table, identifier):
    row = c.execute('SELECT * FROM "' + table + '" WHERE id=?', (identifier,)).fetchone()
    if not row:
        raise CustomerError(404, "记录不存在")
    return row


class Customers:
    def __init__(self, store, fleet=None):
        self.store, self.fleet = store, fleet
        with store.connect() as c:
            c.executescript("""
                CREATE TABLE IF NOT EXISTS customer_groups(
                    id TEXT PRIMARY KEY,name TEXT NOT NULL,profile_id TEXT REFERENCES profiles(id),
                    quota_bytes INTEGER NOT NULL,device_limit INTEGER NOT NULL,valid_days INTEGER NOT NULL,
                    updated REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS customer_accounts(
                    user_id TEXT PRIMARY KEY REFERENCES users(id),group_id TEXT REFERENCES customer_groups(id),
                    paused INTEGER NOT NULL DEFAULT 0,joined REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS customer_invites(
                    token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),
                    expires REAL NOT NULL,used INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS customer_devices(
                    id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),name TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,revoked INTEGER NOT NULL DEFAULT 0,created REAL NOT NULL,
                    last_seen REAL,issued_revision TEXT,issued_at REAL,ack_revision TEXT,ack_status TEXT,
                    ack_code TEXT,ack_at REAL,applied_revision TEXT,app_version TEXT,network TEXT,
                    report_rx INTEGER,report_tx INTEGER,report_at REAL);
                CREATE TABLE IF NOT EXISTS customer_deliveries(
                    device_id TEXT NOT NULL REFERENCES customer_devices(id),revision TEXT NOT NULL,
                    envelope TEXT NOT NULL,issued REAL NOT NULL,PRIMARY KEY(device_id,revision));
                CREATE TABLE IF NOT EXISTS customer_events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL REFERENCES users(id),
                    device_id TEXT,code TEXT NOT NULL,source TEXT NOT NULL,created REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS customer_usage(
                    user_id TEXT NOT NULL REFERENCES users(id),period TEXT NOT NULL,rx INTEGER NOT NULL,
                    tx INTEGER NOT NULL,updated REAL NOT NULL,coverage_start REAL NOT NULL,
                    PRIMARY KEY(user_id,period));
                CREATE TABLE IF NOT EXISTS customer_node_counters(
                    server_id TEXT NOT NULL REFERENCES fleet_servers(id),user_id TEXT NOT NULL REFERENCES users(id),
                    period TEXT NOT NULL,route_id TEXT NOT NULL,epoch TEXT NOT NULL,rx INTEGER NOT NULL,tx INTEGER NOT NULL,
                    PRIMARY KEY(server_id,user_id,period));
                CREATE TABLE IF NOT EXISTS customer_service_actions(
                    user_id TEXT PRIMARY KEY REFERENCES users(id),action TEXT NOT NULL,
                    revisions_json TEXT NOT NULL,created REAL NOT NULL);
            """)
            columns={row[1] for row in c.execute('PRAGMA table_info(customer_accounts)')}
            if 'revoked' not in columns:
                c.execute('ALTER TABLE customer_accounts ADD COLUMN revoked INTEGER NOT NULL DEFAULT 0')
            if 'app_only' not in columns:
                c.execute('ALTER TABLE customer_accounts ADD COLUMN app_only INTEGER NOT NULL DEFAULT 0')
            # Added in 0.3.2 without a schema version: a 0.3.1 program still opens the database.
            if 'block_claude' not in columns:
                c.execute('ALTER TABLE customer_accounts ADD COLUMN block_claude INTEGER NOT NULL DEFAULT 0')
            c.execute("INSERT OR IGNORE INTO customer_groups VALUES(?,?,NULL,0,1,0,?)",
                      (DEFAULT_GROUP, "默认分组", time.time()))
            c.execute("INSERT OR IGNORE INTO customer_accounts(user_id,group_id,paused,joined) SELECT id,?,0,? FROM users",
                      (DEFAULT_GROUP, time.time()))
            c.execute("PRAGMA user_version=" + str(max(5, c.execute("PRAGMA user_version").fetchone()[0])))
        store.restrict_files()

    @staticmethod
    def event(c, user_id, code, device_id=None, source="management"):
        c.execute("INSERT INTO customer_events(user_id,device_id,code,source,created) VALUES(?,?,?,?,?)",
                  (user_id, device_id, code, source, time.time()))
        c.execute("DELETE FROM customer_events WHERE created<?", (time.time()-7*86400,))
        c.execute("DELETE FROM customer_events WHERE id NOT IN (SELECT id FROM customer_events ORDER BY id DESC LIMIT 5000)")

    def member(self, c, user_id):
        user = required(c, "users", user_id)
        c.execute("INSERT OR IGNORE INTO customer_accounts(user_id,group_id,paused,joined) VALUES(?,?,0,?)", (user_id, DEFAULT_GROUP, time.time()))
        account = c.execute("SELECT * FROM customer_accounts WHERE user_id=?", (user_id,)).fetchone()
        group = c.execute("SELECT * FROM customer_groups WHERE id=?", (account["group_id"],)).fetchone()
        return user, account, group

    @staticmethod
    def policy(account, group, month=None):
        return {"quotaBytes": group["quota_bytes"] if group else 0,
                "deviceLimit": group["device_limit"] if group else 1,
                "expiresAt": account["joined"]+group["valid_days"]*86400 if group and group["valid_days"] else None,
                "period": month or period(), "periodTimezone": "UTC", "quotaScope": "per_customer",
                "nodeEnforcement": "not_connected"}

    @staticmethod
    def usage(c, user_id, month=None):
        row = c.execute("SELECT * FROM customer_usage WHERE user_id=? AND period=?", (user_id,month or period())).fetchone()
        if not row:
            return {"source": "not_collected", "downloadBytes": None, "uploadBytes": None,
                    "totalBytes": None, "updated": None, "coverageStart": None, "fresh": False}
        return {"source": "node_entry", "downloadBytes": row["rx"], "uploadBytes": row["tx"],
                "totalBytes": row["rx"]+row["tx"], "updated": row["updated"],
                "coverageStart": row["coverage_start"], "fresh": time.time()-row["updated"] < 90}

    def context(self, c, user_id, month=None):
        user, account, group = self.member(c, user_id)
        month=month or period()
        policy = self.policy(account, group,month)
        usage = self.usage(c, user_id,month)
        blocked = ("revoked" if account["revoked"] else "paused" if account["paused"] else "expired" if policy["expiresAt"] and time.time()>=policy["expiresAt"]
                   else "quota_reached" if policy["quotaBytes"] and usage["totalBytes"] is not None
                   and usage["totalBytes"]>=policy["quotaBytes"] else None)
        control=self.node_control(c,user_id)
        assignment=c.execute('SELECT status FROM fleet_assignments WHERE user_id=?',(user_id,)).fetchone()
        if not blocked and not account['app_only'] and control['action']=='restore' and control['nodes'] and (control['status']!='applied' or assignment and assignment['status']!='ready'):
            blocked='nodes_pending'
        profile_id = user["profile_id"] or (group["profile_id"] if group else None)
        if account['app_only']:profile_id=None
        profile = c.execute("SELECT * FROM profiles WHERE id=?", (profile_id,)).fetchone()
        if profile and profile['source']=='线路生成' and not c.execute('SELECT 1 FROM fleet_assignments WHERE user_id=? AND profile_id=? AND status=\'ready\'',(user_id,profile_id)).fetchone() and not blocked:
            blocked='nodes_pending'
        policy["quotaMeter"] = usage["source"]
        return user, account, group, policy, usage, blocked, profile

    def check_access(self, context):
        user, account, group, policy, usage, blocked, profile = context
        if blocked:
            raise CustomerError(409 if blocked=='nodes_pending' else 403, {"revoked":"客户服务已收回","paused":"已暂停配置下发","expired":"使用期已结束",
                                      "quota_reached":"已采集流量达到额度","nodes_pending":"等待节点恢复权限"}[blocked])

    def envelope(self, context):
        self.check_access(context)
        user, account, group, policy, usage, blocked, profile = context
        if not profile:
            raise CustomerError(409, "App 已开通，未分配节点" if account['app_only'] else "尚未分配配置")
        config = json.loads(profile["config_json"])
        if account["block_claude"]:
            config = refuse_claude(config)
        # The digest covers policy and config, not changing traffic samples.
        value = {"format": 1, "configName": profile["name"], "config": config,
                 "policy": {k:v for k,v in policy.items() if k != "quotaMeter"},
                 "configKind": "sing-box-source", "groupName": group["name"] if group else None}
        value["configRevision"] = digest(json.dumps(value["config"],ensure_ascii=False,sort_keys=True,separators=(",",":")))
        value["revision"] = digest(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",",":")))
        return value

    def summary(self, c, user_id):
        context = self.context(c, user_id)
        user, account, group, policy, usage, blocked, profile = context
        devices = c.execute("SELECT * FROM customer_devices WHERE user_id=? ORDER BY created,id", (user_id,)).fetchall()
        active = [d for d in devices if not d["revoked"]]
        revision = self.envelope(context)["revision"] if profile and not blocked else None
        return {"id": user["id"], "name": user["name"], "groupId": account["group_id"],
                "appOnly":bool(account['app_only']),"blockClaude":bool(account['block_claude']),
                "groupName": group["name"] if group else "未分组", "paused": bool(account["paused"]), "revoked":bool(account['revoked']),
                "profileId": profile["id"] if profile else None, "profileName": profile["name"] if profile else None,"profileNodes":profile['nodes'] if profile else 0,
                "profileSource": "individual" if user["profile_id"] else "group", "policy": policy,
                "usage": usage, "accessState": blocked or "active", "deviceCount": len(active),
                "receivedCount": sum(d["ack_revision"]==revision and d["ack_status"] in ("received","applied") for d in active) if revision else 0,
                "appliedCount": sum(d["ack_revision"]==revision and d["ack_status"]=="applied" for d in active) if revision else 0,
                "revision": revision, "joined": account["joined"],"nodeControl":self.node_control(c,user_id)}

    @staticmethod
    def node_control(c,user_id):
        action=c.execute('SELECT * FROM customer_service_actions WHERE user_id=?',(user_id,)).fetchone()
        if not action:return {'action':None,'status':'not_requested','nodes':[]}
        targets=json.loads(action['revisions_json']);nodes=[]
        for identifier,revision in targets.items():
            server=c.execute('SELECT * FROM fleet_servers WHERE id=?',(identifier,)).fetchone()
            confirmed=bool(server and not server['revoked'] and server['runtime_status']=='running' and server['applied']>=revision)
            nodes.append({'id':identifier,'name':server['name'] if server else '旧服务器',
                'requiredRevision':revision,'confirmed':confirmed,
                'online':bool(server and server['last_seen'] and time.time()-server['last_seen']<90),
                'status':server['runtime_status'] if server else 'missing'})
        status=('not_managed' if not nodes else 'applied' if all(n['confirmed'] for n in nodes)
                else 'failed' if any(n['status']=='failed' for n in nodes) else 'pending')
        return {'action':action['action'],'status':status,'nodes':nodes,'requestedAt':action['created']}

    def service(self,user_id,restore=False,_connection=None):
        with (nullcontext(_connection) if _connection is not None else self.store.connect()) as c:
            if _connection is None:c.execute('BEGIN IMMEDIATE')
            user,account,_=self.member(c,user_id)
            if bool(account['revoked']) != restore:
                return {'ok':True,'nodeControl':self.node_control(c,user_id)}
            c.execute('UPDATE customer_accounts SET revoked=?,paused=0,joined=CASE WHEN ? THEN ? ELSE joined END WHERE user_id=?',
                      (0 if restore else 1,int(restore),time.time(),user_id))
            # Old codes and device secrets remain invalid after restoration.
            c.execute('UPDATE customer_invites SET used=1 WHERE user_id=?',(user_id,))
            c.execute('UPDATE customer_devices SET revoked=1 WHERE user_id=?',(user_id,))
            assignment=c.execute('SELECT * FROM fleet_assignments WHERE user_id=?',(user_id,)).fetchone()
            targets={}
            identifiers={r[0] for r in c.execute('SELECT DISTINCT n.server_id FROM fleet_credentials fc JOIN fleet_nodes n ON n.id=fc.node_id WHERE fc.principal=?',(user_id,))}
            if assignment:
                route=required(c,'fleet_routes',assignment['route_id'])
                identifiers.update(required(c,'fleet_nodes',n)['server_id'] for n in json.loads(route['node_ids']))
                c.execute('UPDATE fleet_assignments SET status=? WHERE user_id=?',('pending' if restore else 'revoked',user_id))
                c.execute("UPDATE fleet_probes SET status='stale' WHERE route_id=?",(route['id'],))
            if identifiers:
                if self.fleet is None:raise CustomerError(409,'托管线路需要完整管理中心执行收回')
                peers=[required(c,'fleet_servers',i) for i in sorted(identifiers)]
                if restore:
                    c.execute('DELETE FROM fleet_credentials WHERE principal=?',(user_id,))
                for server in peers:
                    targets[server['id']]=server['desired']+1
                    ready=bool(server['token_hash'] and not server['revoked'])
                    for linked in c.execute('SELECT node_ids FROM fleet_routes').fetchall():
                        path=[required(c,'fleet_nodes',n) for n in json.loads(linked['node_ids'])]
                        if any(n['server_id']==server['id'] and n['enabled'] for n in path):
                            ready=ready and all(required(c,'fleet_servers',n['server_id'])['token_hash'] and not required(c,'fleet_servers',n['server_id'])['revoked'] for n in path)
                    if ready:self.fleet.queue(c,server['id'])
            c.execute('INSERT INTO customer_service_actions VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET action=excluded.action,revisions_json=excluded.revisions_json,created=excluded.created',
                      (user_id,'restore' if restore else 'revoke',json.dumps(targets),time.time()))
            self.event(c,user_id,'service_restored' if restore else 'service_revoked')
            self.store.record(c,'恢复客户服务' if restore else '收回客户服务',user['name'])
            control=self.node_control(c,user_id)
        return {'ok':True,'nodeControl':control}

    def delete(self, user_id):
        # Queue removal while the historical paths still exist. Deletion and all
        # credential invalidation commit together, or roll back together on failure.
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            user, _, _ = self.member(c, user_id)
            control = self.service(user_id, _connection=c)['nodeControl']
            c.execute('DELETE FROM customer_deliveries WHERE device_id IN (SELECT id FROM customer_devices WHERE user_id=?)', (user_id,))
            for table in ('customer_invites', 'customer_devices', 'customer_events', 'customer_usage',
                          'customer_node_counters', 'customer_service_actions', 'customer_accounts', 'fleet_assignments'):
                c.execute('DELETE FROM ' + table + ' WHERE user_id=?', (user_id,))
            c.execute('DELETE FROM fleet_credentials WHERE principal=?', (user_id,))
            c.execute('DELETE FROM users WHERE id=?', (user_id,))
            self.store.record(c, '删除客户', user['name'])
        self.store.restrict_files()
        return {'ok': True, 'nodeControl': control}

    def claude(self,user_id,data):
        blocked=data.get('blocked')
        if type(blocked) is not bool:raise CustomerError(400,'Claude 设置无效')
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            user,account,_=self.member(c,user_id)
            if bool(account['block_claude'])==blocked:return {'ok':True}
            c.execute('UPDATE customer_accounts SET block_claude=? WHERE user_id=?',(int(blocked),user_id))
            # The servers on the customer's route refuse Claude for that customer's accounts as soon
            # as they apply the new configuration; nobody else's traffic is sniffed or refused.
            servers=set()
            assignment=c.execute('SELECT route_id FROM fleet_assignments WHERE user_id=?',(user_id,)).fetchone()
            if assignment and not account['revoked']:
                route=required(c,'fleet_routes',assignment['route_id'])
                servers={required(c,'fleet_nodes',n)['server_id'] for n in json.loads(route['node_ids'])}
            if servers and self.fleet is None:raise CustomerError(409,'托管线路需要完整管理中心')
            for identifier in sorted(servers):
                server=required(c,'fleet_servers',identifier)
                if server['token_hash'] and not server['revoked']:self.fleet.queue(c,identifier)
            self.event(c,user_id,'claude_blocked' if blocked else 'claude_allowed')
            self.store.record(c,'拦下 Claude' if blocked else '放行 Claude',user['name'])
        return {'ok':True}

    def state(self):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            users = [self.summary(c, u[0]) for u in c.execute("SELECT id FROM users ORDER BY created,rowid").fetchall()]
            groups = [{"id":g["id"],"name":g["name"],"profileId":g["profile_id"],"quotaBytes":g["quota_bytes"],
                       "deviceLimit":g["device_limit"],"validDays":g["valid_days"],"updated":g["updated"],
                       "memberCount":sum(u["groupId"]==g["id"] for u in users)}
                      for g in c.execute("SELECT * FROM customer_groups ORDER BY updated,id")]
        return {"groups":groups,"users":users,"meterSource":"not_connected","diagnosticRetentionDays":7}

    def detail(self, user_id):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            result = self.summary(c, user_id)
            # Do not serialize stored hashes, immutable config bodies or invitation tokens.
            result["devices"] = [dict(d) for d in c.execute("""SELECT id,name,revoked,created,last_seen,
                issued_revision,issued_at,ack_revision,ack_status,ack_code,ack_at,applied_revision,
                app_version,network,report_rx,report_tx,report_at FROM customer_devices
                WHERE user_id=? ORDER BY created,id""", (user_id,))]
            result["events"] = [{**dict(e),"message":EVENTS.get(e["code"],e["code"])} for e in c.execute(
                "SELECT id,device_id,code,source,created FROM customer_events WHERE user_id=? AND created>=? ORDER BY id DESC LIMIT 100",
                (user_id,time.time()-7*86400))]
            result["invitePending"] = c.execute("SELECT 1 FROM customer_invites WHERE user_id=? AND used=0 AND expires>?", (user_id,time.time())).fetchone() is not None
        return result

    def save_group(self, data, identifier=None):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            old = required(c, "customer_groups", identifier) if identifier else None
            name = text(data.get("name",old["name"] if old else None))
            profile_id = data.get("profileId",old["profile_id"] if old else None)
            if profile_id is not None:
                profile=required(c,"profiles",text(profile_id,36))
                if profile['source']=='线路生成':raise CustomerError(400,'客户独立线路配置不能作为分组公共模板')
            quota = integer(data.get("quotaBytes",old["quota_bytes"] if old else 0))
            limit = integer(data.get("deviceLimit",old["device_limit"] if old else 1),20,1)
            days = integer(data.get("validDays",old["valid_days"] if old else 0),36500)
            identifier = identifier or str(uuid.uuid4())
            c.execute("INSERT INTO customer_groups VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,profile_id=excluded.profile_id,quota_bytes=excluded.quota_bytes,device_limit=excluded.device_limit,valid_days=excluded.valid_days,updated=excluded.updated",
                      (identifier,name,profile_id,quota,limit,days,time.time()))
            for row in c.execute("SELECT user_id FROM customer_accounts WHERE group_id=?",(identifier,)).fetchall():
                self.event(c,row[0],"policy_updated")
            self.store.record(c,"保存分组",name)
        return {"ok":True,"id":identifier}

    def set_member(self, user_id, action, data):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            user,account,_=self.member(c,user_id)
            if action in ("group","configure"):
                identifier=data.get("groupId")
                if identifier is not None:required(c,"customer_groups",text(identifier,36))
                if action=="configure":
                    app_only=data.get('appOnly',bool(account['app_only']))
                    if type(app_only) is not bool:raise CustomerError(400,'服务方式无效')
                    if app_only and not account['app_only'] and c.execute('SELECT 1 FROM customer_devices WHERE user_id=? AND revoked=0',(user_id,)).fetchone():
                        raise CustomerError(409,'已有绑定设备，请先收回并恢复服务，再切换为仅开通 App')
                    profile_id=data.get("profileId")
                    if app_only and profile_id is not None:raise CustomerError(400,'仅开通 App 不能同时分配节点')
                    if profile_id is not None:self.check_owned_profile(c,user_id,profile_id)
                    c.execute("UPDATE users SET profile_id=? WHERE id=?",(profile_id,user_id))
                    c.execute('UPDATE customer_accounts SET app_only=? WHERE user_id=?',(int(app_only),user_id))
                c.execute("UPDATE customer_accounts SET group_id=?,joined=? WHERE user_id=?",(identifier,account["joined"] if identifier==account["group_id"] else time.time(),user_id))
                code="group_changed" if identifier!=account["group_id"] else None
                if action=="configure" and profile_id!=user["profile_id"]:
                    self.event(c,user_id,"profile_changed")
            elif action=="profile":
                identifier=data.get("profileId")
                if identifier is not None:self.check_owned_profile(c,user_id,identifier)
                c.execute("UPDATE users SET profile_id=? WHERE id=?",(identifier,user_id))
                if identifier is not None:c.execute('UPDATE customer_accounts SET app_only=0 WHERE user_id=?',(user_id,))
                code="profile_changed"
            else:
                if type(data.get("paused")) is not bool:raise CustomerError(400,"暂停状态无效")
                c.execute("UPDATE customer_accounts SET paused=? WHERE user_id=?",(int(data["paused"]),user_id))
                code="paused" if data["paused"] else "resumed"
            if code:self.event(c,user_id,code)
        return {"ok":True}

    @staticmethod
    def check_owned_profile(c,user_id,profile_id):
        profile=required(c,'profiles',text(profile_id,36))
        if profile['source']=='线路生成' and not c.execute('SELECT 1 FROM fleet_assignments WHERE user_id=? AND profile_id=?',(user_id,profile_id)).fetchone():
            raise CustomerError(400,'请选择该客户当前的独立线路配置')
        return profile

    def invite(self, user_id):
        token = secrets.token_urlsafe(32)
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            context = self.context(c,user_id)
            if context[5]:raise CustomerError(409,"请先恢复客户的领取权限")
            if context[1]['app_only']:self.check_access(context)
            else:self.envelope(context)
            c.execute("DELETE FROM customer_invites WHERE used=1 OR expires<=?",(time.time(),))
            c.execute("UPDATE customer_invites SET used=1 WHERE user_id=?",(user_id,))
            c.execute("INSERT INTO customer_invites VALUES(?,?,?,0)",(digest(token),user_id,time.time()+900))
            self.event(c,user_id,"invitation_issued")
        return {"ok":True,"inviteCode":token,"expiresIn":900}

    def claim(self, data):
        token,name = text(data.get("inviteCode"),128),text(data.get("deviceName"),60)
        secret,identifier = secrets.token_urlsafe(48),str(uuid.uuid4())
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            ticket=c.execute("SELECT * FROM customer_invites WHERE token_hash=? AND used=0 AND expires>?",(digest(token),time.time())).fetchone()
            if not ticket:raise CustomerError(401,"领取码已失效")
            context=self.context(c,ticket["user_id"])
            if context[1]['app_only']:self.check_access(context)
            else:self.envelope(context)
            count=c.execute("SELECT COUNT(*) FROM customer_devices WHERE user_id=? AND revoked=0",(ticket["user_id"],)).fetchone()[0]
            if count>=context[3]["deviceLimit"]:raise CustomerError(409,"已达到设备绑定上限")
            c.execute("INSERT INTO customer_devices(id,user_id,name,token_hash,created) VALUES(?,?,?,?,?)",
                      (identifier,ticket["user_id"],name,digest(secret),time.time()))
            c.execute("UPDATE customer_invites SET used=1 WHERE token_hash=?",(ticket["token_hash"],))
            self.event(c,ticket["user_id"],"device_bound",identifier)
        return {"deviceId":identifier,"credential":secret,"format":1}

    def authenticate(self, bearer):
        if not isinstance(bearer,str) or not bearer.startswith("Bearer ") or len(bearer)>256:
            raise CustomerError(401,"设备认证失败")
        with self.store.connect() as c:
            device=c.execute("SELECT id FROM customer_devices WHERE token_hash=? AND revoked=0",(digest(bearer[7:]),)).fetchone()
            if not device:raise CustomerError(401,"设备认证失败")
        return device["id"]

    def checked_device(self,c,identifier):
        device=required(c,"customer_devices",identifier)
        if device["revoked"]:raise CustomerError(401,"设备凭据已撤销")
        return device

    def configuration(self, identifier, controller_protocol=None):
        controller = getattr(self, "controller", None)
        if controller_protocol == 1 and controller is not None:
            controller.recover_device(identifier)
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            device=self.checked_device(c,identifier)
            context=self.context(c,device["user_id"])
            allowed=[d[0] for d in c.execute("SELECT id FROM customer_devices WHERE user_id=? AND revoked=0 ORDER BY created,id",(device["user_id"],))]
            controller = getattr(self, "controller", None)
            if controller_protocol == 1 and controller is None and c.execute("PRAGMA user_version").fetchone()[0] >= 6:
                raise CustomerError(503, "Node control is unavailable. Cached managed configurations are not authorized.")
            controlled = False
            if c.execute("SELECT 1 FROM sqlite_master WHERE name='controller_devices'").fetchone():
                control = c.execute("SELECT mode FROM controller_devices WHERE device_id=?", (identifier,)).fetchone()
                controlled = bool(control and control['mode'] != 'legacy')
            if controlled and controller is None:
                raise CustomerError(503, "Node control is unavailable. Cached managed configurations are not authorized.")
            if not controlled and identifier not in allowed[:context[3]["deviceLimit"]]:raise CustomerError(403,"此设备超出分组上限")
            result = controller.configuration(c, device, context, controller_protocol) if controller else self.envelope(context)
            c.execute("INSERT OR IGNORE INTO customer_deliveries VALUES(?,?,?,?)",(identifier,result["revision"],json.dumps(result,ensure_ascii=False),time.time()))
            c.execute("UPDATE customer_devices SET issued_revision=?,issued_at=?,last_seen=? WHERE id=?",(result["revision"],time.time(),time.time(),identifier))
            c.execute("DELETE FROM customer_deliveries WHERE device_id=? AND revision NOT IN (SELECT revision FROM customer_deliveries WHERE device_id=? ORDER BY issued DESC LIMIT 10)",(identifier,identifier))
        return result

    def ack(self, identifier, data):
        controller = getattr(self, "controller", None)
        if controller:
            with self.store.connect() as c:
                controlled = controller._state(c, identifier)["mode"] != "legacy"
            if controlled: return controller.ack(identifier, data, legacy=True)
        revision=text(data.get("revision"),64)
        status=data.get("status");code=data.get("code","ok" if status!="failed" else "unknown")
        if status not in ("received","applied","failed") or not isinstance(code,str) or code not in ACK_CODES:
            raise CustomerError(400,"回执状态或错误码无效")
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            device=self.checked_device(c,identifier)
            current=self.envelope(self.context(c,device["user_id"]))["revision"]
            if revision!=device["issued_revision"] or revision!=current:
                raise CustomerError(409,"配置版本已改变，请重新领取")
            # A repeated/late received ack must not downgrade applied or erase a failure.
            if device["ack_revision"]==revision and (device["ack_status"]==status or
                    status=="received" and device["ack_status"] in ("applied","failed")):
                return {"ok":True}
            c.execute("UPDATE customer_devices SET ack_revision=?,ack_status=?,ack_code=?,ack_at=?,last_seen=?,applied_revision=CASE WHEN ?='applied' THEN ? ELSE applied_revision END WHERE id=?",
                      (revision,status,code,time.time(),time.time(),status,revision,identifier))
            self.event(c,device["user_id"],status,identifier,"client")
        return {"ok":True}

    def diagnostics(self, identifier, data):
        codes=data.get("events",[])
        if not isinstance(codes,list) or len(codes)>10 or any(not isinstance(code,str) or code not in CLIENT_EVENTS for code in codes):
            raise CustomerError(400,"诊断只接受指定事件码")
        app_version=text(data.get("appVersion","unknown"),32)
        if not re.fullmatch(r"[A-Za-z0-9._+\-]{1,32}",app_version):
            raise CustomerError(400,"应用版本无效")
        network=data.get("network","unknown")
        if not isinstance(network,str) or network not in ("wifi","cellular","ethernet","offline","unknown"):
            raise CustomerError(400,"网络类型无效")
        rx=data.get("downloadBytes");tx=data.get("uploadBytes")
        if rx is not None:integer(rx)
        if tx is not None:integer(tx)
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            device=self.checked_device(c,identifier)
            if device["report_at"] and time.time()-device["report_at"]<2:
                raise CustomerError(429,"诊断上报过于频繁")
            c.execute("UPDATE customer_devices SET app_version=?,network=?,report_rx=?,report_tx=?,report_at=?,last_seen=? WHERE id=?",
                      (app_version,network,rx,tx,time.time(),time.time(),identifier))
            for code in codes:self.event(c,device["user_id"],code,identifier,"client")
        return {"ok":True,"usageSource":"client_diagnostic_only"}

    def revoke(self,user_id,identifier):
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            device=required(c,"customer_devices",identifier)
            if device["user_id"]!=user_id:raise CustomerError(404,"设备不属于该客户")
            c.execute("UPDATE customer_devices SET revoked=1 WHERE id=?",(identifier,))
            self.event(c,user_id,"device_revoked",identifier)
        return {"ok":True}

    def ingest_usage(self,server_id,data):
        samples=data.get("samples")
        if not isinstance(samples,list) or not samples or len(samples)>500:
            raise CustomerError(400,"用量样本无效")
        month=period()
        with self.store.connect() as c:
            c.execute("BEGIN IMMEDIATE")
            server=required(c,"fleet_servers",server_id)
            if server["revoked"]:raise CustomerError(401,"节点凭据已撤销")
            seen=set()
            for sample in samples:
                if not isinstance(sample,dict):raise CustomerError(400,"用量样本无效")
                user_id=text(sample.get("userId"),36);route_id=text(sample.get("routeId"),36)
                epoch=text(sample.get("counterEpoch"),80)
                rx=integer(sample.get("downloadBytes"));tx=integer(sample.get("uploadBytes"))
                if user_id in seen:raise CustomerError(400,"同批用户计数重复")
                seen.add(user_id)
                assignment=c.execute("SELECT * FROM fleet_assignments WHERE user_id=? AND route_id=? AND status='ready'",(user_id,route_id)).fetchone()
                route=required(c,"fleet_routes",route_id)
                entry=required(c,"fleet_nodes",json.loads(route["node_ids"])[0])
                if not assignment or entry["server_id"]!=server_id:
                    raise CustomerError(403,"只接受当前线路入口的客户计数")
                old=c.execute("SELECT * FROM customer_node_counters WHERE server_id=? AND user_id=? AND period=?",(server_id,user_id,month)).fetchone()
                continuing = bool(old and old["epoch"]==epoch and old["route_id"]==route_id)
                if continuing and (rx<old["rx"] or tx<old["tx"]):
                    raise CustomerError(409,"同一计数周期发生回退")
                delta_rx=rx-old["rx"] if continuing else 0
                delta_tx=tx-old["tx"] if continuing else 0
                previous=self.usage(c,user_id,month)["totalBytes"]
                if previous is not None:integer(previous+delta_rx+delta_tx)
                c.execute("INSERT INTO customer_node_counters VALUES(?,?,?,?,?,?,?) ON CONFLICT(server_id,user_id,period) DO UPDATE SET route_id=excluded.route_id,epoch=excluded.epoch,rx=excluded.rx,tx=excluded.tx",
                          (server_id,user_id,month,route_id,epoch,rx,tx))
                c.execute("INSERT INTO customer_usage VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,period) DO UPDATE SET rx=rx+excluded.rx,tx=tx+excluded.tx,updated=excluded.updated",
                          (user_id,month,delta_rx,delta_tx,time.time(),time.time()))
                quota=self.context(c,user_id,month)[3]["quotaBytes"]
                if quota and (previous or 0)<quota<=(previous or 0)+delta_rx+delta_tx:
                    self.event(c,user_id,"quota_reached")
        return {"ok":True,"source":"node_entry","nodeEnforcement":"not_connected"}
