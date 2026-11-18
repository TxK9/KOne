"""Claude refused for the customers the owner chose: in the configuration their app receives, and on
the console's own servers for their accounts only. Disposable consoles, no network."""
import http.client
import importlib.util
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
from server import create_server, ConsoleStore  # noqa: E402
from customers import Customers, CLAUDE_DOMAINS, CustomerError  # noqa: E402
from fleet import Fleet  # noqa: E402

spec = importlib.util.spec_from_file_location("claude_test_agent", BASE / "node" / "agent.py")
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)

REFUSE = {"domain_suffix": list(CLAUDE_DOMAINS), "action": "reject"}
SOURCE_RULES = [{"domain_suffix": ["example.cn"], "outbound": "direct"}]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class ClaudeConfigTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="kone-claude-api-")
        self.server = create_server(port=0, data_dir=Path(self.tmp.name) / "data")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.cookie = self.csrf = None
        code, data, headers = self.request("/api/auth/setup", {"password": "local-claude-test-only"}, admin=False)
        self.assertEqual(code, 200)
        self.cookie, self.csrf = headers["Set-Cookie"].split(";")[0], data["csrfToken"]
        profile = self.request("/api/profiles", {"name": "测试配置", "config": {
            "outbounds": [{"type": "vless", "tag": "test", "server": "node.invalid", "server_port": 443,
                           "uuid": "11111111-1111-4111-8111-111111111111", "tls": {"enabled": True}},
                          {"type": "direct", "tag": "direct"}],
            "route": {"rules": SOURCE_RULES, "final": "test"}}})[1]["id"]
        group = self.request("/api/customer-groups", {"name": "测试分组", "profileId": profile,
                                                      "quotaBytes": 0, "deviceLimit": 2, "validDays": 0})[1]["id"]
        self.user = self.request("/api/users", {"name": "测试客户", "groupId": group})[1]["id"]
        invite = self.request("/api/customer-users/" + self.user + "/invite", {})[1]["inviteCode"]
        self.device = self.request("/api/client/claim", {"inviteCode": invite, "deviceName": "测试设备"}, admin=False)[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.tmp.cleanup()

    def request(self, path, payload=None, bearer=None, admin=True):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if admin and self.cookie:
            headers.update({"Cookie": self.cookie, "X-CSRF-Token": self.csrf})
        if bearer:
            headers["Authorization"] = "Bearer " + bearer
        try:
            connection.request("POST" if payload is not None else "GET", path,
                               json.dumps(payload) if payload is not None else None, headers)
            response = connection.getresponse()
            raw = response.read()
            return response.status, json.loads(raw) if raw else None, dict(response.getheaders())
        finally:
            connection.close()

    def config(self):
        code, envelope, _ = self.request("/api/client/config", bearer=self.device["credential"], admin=False)
        self.assertEqual(code, 200)
        return envelope

    def summary(self):
        return next(u for u in self.request("/api/customers")[1]["users"] if u["id"] == self.user)

    def set_claude(self, blocked):
        return self.request("/api/customer-users/" + self.user + "/claude", {"blocked": blocked})

    def test_nobody_is_blocked_until_the_owner_chooses(self):
        self.assertFalse(self.summary()["blockClaude"])
        self.assertEqual(self.config()["config"]["route"]["rules"], SOURCE_RULES)

    def test_blocking_puts_the_rule_first_and_unblocking_restores_the_same_revision(self):
        before = self.config()
        code, result, _ = self.set_claude(True)
        self.assertEqual((code, result), (200, {"ok": True}))
        self.assertTrue(self.summary()["blockClaude"])
        blocked = self.config()
        self.assertEqual(blocked["config"]["route"]["rules"], [REFUSE] + SOURCE_RULES)
        self.assertEqual(blocked["config"]["route"]["final"], "test")
        self.assertEqual(blocked["config"]["outbounds"], before["config"]["outbounds"])
        self.assertNotEqual(blocked["revision"], before["revision"])
        # Setting the same value again changes nothing and logs nothing.
        self.assertEqual(self.set_claude(True)[0], 200)
        self.assertEqual(self.config()["revision"], blocked["revision"])
        self.assertEqual(self.set_claude(False)[0], 200)
        self.assertEqual(self.config()["revision"], before["revision"])
        events = [e["message"] for e in self.request("/api/customer-users/" + self.user + "/detail")[1]["events"]]
        self.assertEqual(events.count("拦下 Claude"), 1)
        self.assertEqual(events.count("放行 Claude"), 1)

    def test_a_config_without_route_gets_one(self):
        store = self.server.app.store
        with store.connect() as c:
            c.execute("UPDATE profiles SET config_json=?", (json.dumps({"outbounds": [{"type": "direct", "tag": "direct"}]}),))
        self.set_claude(True)
        self.assertEqual(self.config()["config"]["route"], {"rules": [REFUSE]})

    def test_only_a_boolean_is_accepted(self):
        for value in ("yes", 1, None):
            self.assertEqual(self.set_claude(value)[0], 400)
        self.assertFalse(self.summary()["blockClaude"])
        self.assertEqual(self.request("/api/customer-users/00000000-0000-4000-8000-00000000abcd/claude", {"blocked": True})[0], 404)

    def test_the_column_is_added_without_a_new_schema_version(self):
        with self.server.app.store.connect() as c:
            self.assertEqual(c.execute("PRAGMA user_version").fetchone()[0], 5)
            self.assertIn("block_claude", {row[1] for row in c.execute("PRAGMA table_info(customer_accounts)")})


class ClaudeServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="kone-claude-fleet-")
        self.root = Path(self.tmp.name)
        self.store = ConsoleStore(self.root / "console")
        self.fleet = Fleet(self.store)
        self.customers = Customers(self.store, self.fleet)

    def tearDown(self):
        self.tmp.cleanup()

    def enroll(self, name):
        ticket = self.fleet.enrollment({"name": name, "address": "127.0.0.1"})
        directory = self.root / ticket["id"]
        directory.mkdir()
        certificate = agent.certificate(directory, "127.0.0.1")
        self.fleet.register({"token": ticket["token"], "certificate": certificate, "os": "test", "arch": "arm64", "mode": "stage"})
        return ticket["id"]

    def compiled(self, server_id):
        with self.store.connect() as c:
            return self.fleet.compile(c, server_id)

    def desired(self, server_id):
        return next(s["desired"] for s in self.fleet.state()["servers"] if s["id"] == server_id)

    def test_servers_refuse_claude_only_for_the_chosen_customer(self):
        entry, other = self.enroll("入口"), self.enroll("别处")
        node = self.fleet.add_node({"serverId": entry, "kind": "vless", "name": "vless", "port": free_port()})["id"]
        self.fleet.add_node({"serverId": other, "kind": "vless", "name": "vless", "port": free_port()})
        route = self.fleet.add_route({"name": "线路", "nodeIds": [node]})["id"]
        chosen, kept = self.store.add_user({"name": "甲"})["id"], self.store.add_user({"name": "乙"})["id"]
        self.fleet.assign({"routeId": route, "userIds": [chosen, kept]})
        plain = self.compiled(entry)["route"]["rules"]
        self.assertFalse(any(rule.get("action") == "sniff" for rule in plain))

        before, untouched = self.desired(entry), self.desired(other)
        self.customers.claude(chosen, {"blocked": True})
        self.assertEqual(self.desired(entry), before + 1)
        self.assertEqual(self.desired(other), untouched)
        rules = self.compiled(entry)["route"]["rules"]
        account = route + "/" + chosen
        self.assertEqual(rules[0], {"auth_user": [account], "action": "sniff"})
        self.assertEqual(rules[1], {"auth_user": [account], "domain_suffix": list(CLAUDE_DOMAINS), "action": "reject"})
        self.assertEqual(rules[2:], plain)

        self.customers.claude(chosen, {"blocked": False})
        self.assertEqual(self.desired(entry), before + 2)
        self.assertEqual(self.compiled(entry)["route"]["rules"], plain)

    def test_a_customer_without_a_route_needs_no_server(self):
        user = self.store.add_user({"name": "丙"})["id"]
        self.assertEqual(self.customers.claude(user, {"blocked": True}), {"ok": True})
        with self.assertRaises(CustomerError):
            self.customers.claude(user, {"blocked": "true"})


if __name__ == "__main__":
    unittest.main()
