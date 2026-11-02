"""API integration tests using temporary data and an ephemeral loopback port."""

import http.client
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch


SERVER_PATH = Path(__file__).resolve().parents[1] / "server.py"
SPEC = importlib.util.spec_from_file_location("kone_console_server", str(SERVER_PATH))
server_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server_module)


class ConsoleAPITest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="kone-console-test-")
        self.root = Path(self.temp.name)
        self.web = self.root / "web"
        self.web.mkdir()
        (self.web / "index.html").write_text("<h1>KOne</h1>", encoding="utf-8")
        (self.root / "private.json").write_text('{"secret":"must-not-be-served"}', encoding="utf-8")
        self.server = server_module.create_server("127.0.0.1", 0, self.root / "data", self.web,
                                                 public_origin="https://console.example.ts.net")
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.cookie = None
        self.csrf = None

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.temp.cleanup()

    def request(self, method, path, payload=None, auth=True, csrf=True, extra_headers=None, raw_body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        headers = {"Host": "127.0.0.1:%d" % self.port}
        if auth and self.cookie:
            headers["Cookie"] = self.cookie
        if csrf and self.csrf:
            headers["X-CSRF-Token"] = self.csrf
        if extra_headers:
            headers.update(extra_headers)
        body = raw_body
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        if method == "POST":
            headers.setdefault("Content-Type", "application/json")
            if body is None:
                body = b"{}"
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        response_headers = dict(response.getheaders())
        data = response.read()
        connection.close()
        try:
            parsed = json.loads(data)
        except ValueError:
            parsed = data
        return response.status, parsed, response_headers

    def login_first(self):
        status, data, headers = self.request("POST", "/api/auth/setup", {"password": "a long test password"})
        self.assertEqual(status, 200)
        self.cookie = headers["Set-Cookie"].split(";", 1)[0]
        self.csrf = data["csrfToken"]
        self.assertIn("HttpOnly", headers["Set-Cookie"])
        self.assertIn("SameSite=Strict", headers["Set-Cookie"])

    def profile(self, name="香港配置"):
        config = {"outbounds": [{"type": "vless", "tag": "hk", "server": "example.invalid",
                                 "uuid": "secret-uuid", "tls": {"enabled": True}},
                                {"type": "direct", "tag": "direct"}],
                  "dns": {"servers": [{"type": "udp", "server": "1.1.1.1"}]},
                  "route": {"rules": [{"domain_suffix": ["example.invalid"], "outbound": "direct"}]},
                  "unknown_extension": {"unicode": "原配置", "enabled": False, "values": [1, None]}}
        status, data, _ = self.request("POST", "/api/profiles", {"name": name, "config": config, "source": "file"})
        self.assertEqual(status, 200)
        return data["id"], config

    def user(self, name):
        status, data, _ = self.request("POST", "/api/users", {"name": name})
        self.assertEqual(status, 200)
        return data["id"]

    def test_empty_start_and_authentication_lifecycle(self):
        status, data, _ = self.request("GET", "/api/auth/status")
        self.assertEqual((status, data), (200, {"configured": False, "authenticated": False, "csrfToken": None}))
        self.assertEqual(self.request("GET", "/api/state")[0], 401)
        self.assertEqual(self.request("POST", "/api/auth/setup", {"password": "short"})[0], 400)
        self.login_first()
        status, state, _ = self.request("GET", "/api/state")
        self.assertEqual(state, {"profiles": [], "users": [], "records": []})
        self.assertEqual(self.request("POST", "/api/auth/setup", {"password": "new password long enough"})[0], 409)
        self.assertEqual(self.request("POST", "/api/auth/logout", {})[0], 200)
        self.assertEqual(self.request("GET", "/api/state")[0], 401)
        self.assertEqual(self.request("POST", "/api/auth/login", {"password": "incorrect"})[0], 401)
        status, data, headers = self.request("POST", "/api/auth/login", {"password": "a long test password"})
        self.assertEqual(status, 200)
        self.cookie = headers["Set-Cookie"].split(";", 1)[0]
        self.csrf = data["csrfToken"]
        self.assertEqual(self.request("GET", "/api/state")[0], 200)
        password = self.server.app.store.password_hash()
        self.assertTrue(password.startswith("pbkdf2-sha256$600000$"))
        self.assertNotIn("a long test password", password)

    def test_import_preserves_content_after_database_reopen_without_exposing_secrets_in_state(self):
        self.login_first()
        profile_id, config = self.profile()
        status, state, _ = self.request("GET", "/api/state")
        profile = state["profiles"][0]
        self.assertEqual(profile["version"], "v001")
        self.assertEqual((profile["nodes"], profile["protocols"], profile["dnsCount"], profile["ruleCount"]),
                         (1, ["vless", "direct"], 1, 1))
        self.assertEqual(profile["regions"], [])
        self.assertNotIn("secret-uuid", json.dumps(state))
        status, downloaded, headers = self.request("GET", "/api/profiles/%s/config" % profile_id)
        self.assertEqual((status, downloaded), (200, config))
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertIn("attachment", headers["Content-Disposition"])
        reopened = server_module.ConsoleStore(self.root / "data")
        self.assertEqual(reopened.config(profile_id), ("香港配置", config))
        self.assertEqual(os.stat(str(reopened.path)).st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(str(reopened.data_dir)).st_mode & 0o777, 0o700)

    def test_config_requires_session_and_csrf_required_for_mutation(self):
        self.login_first()
        profile_id, _ = self.profile()
        self.assertEqual(self.request("GET", "/api/profiles/%s/config" % profile_id, auth=False)[0], 401)
        self.assertEqual(self.request("POST", "/api/users", {"name": "不可创建"}, csrf=False)[0], 403)
        self.assertEqual(self.request("POST", "/api/users", {"name": "不可创建"},
                                      extra_headers={"X-CSRF-Token": "wrong"})[0], 403)
        self.assertEqual(self.server.app.store.state()["users"], [])

    def test_raw_config_rejects_duplicate_fields_without_changing_database(self):
        self.login_first()
        user_id = self.user("甲")
        baseline = self.server.app.store.state()
        config_text = ('{"outbounds":[{"type":"direct","tag":"first","tag":"second"}]}')
        status, data, _ = self.request("POST", "/api/profiles", {
            "name": "重复字段", "configText": config_text, "source": "paste", "userIds": [user_id],
            "config": {"outbounds": [{"type": "direct"}]}})
        self.assertEqual(status, 400)
        self.assertIn("duplicate keys", data["error"])
        self.assertEqual(self.server.app.store.state(), baseline)
        for numeric_text in ("NaN", "Infinity", "-Infinity", "1e309"):
            status, _, _ = self.request("POST", "/api/profiles", {
                "name": "无效数值", "configText": '{"outbounds":[{"type":"direct"}],"value":' + numeric_text + '}'})
            self.assertEqual(status, 400, numeric_text)
            self.assertEqual(self.server.app.store.state(), baseline)

    def test_raw_config_preserves_large_integer_on_download(self):
        self.login_first()
        integer = 9007199254740993
        config_text = ('{"outbounds":[{"type":"direct"}],"private_extension":'
                       '{"integer":9007199254740993,"negative":-9007199254740993}}')
        status, data, _ = self.request("POST", "/api/profiles", {
            "name": "精度保留", "configText": config_text, "source": "file"})
        self.assertEqual(status, 200)
        profile_id = data["id"]
        status, downloaded, _ = self.request("GET", "/api/profiles/%s/config" % profile_id)
        self.assertEqual(status, 200)
        self.assertEqual(downloaded["private_extension"], {"integer": integer, "negative": -integer})
        reopened = server_module.ConsoleStore(self.root / "data")
        self.assertEqual(reopened.config(profile_id)[1]["private_extension"]["integer"], integer)

    def test_assignment_validation_is_atomic_and_updates_only_selected_users(self):
        self.login_first()
        first_profile, _ = self.profile("常用配置")
        second_profile, _ = self.profile("备用配置")
        user_a = self.user("甲")
        user_b = self.user("乙")
        self.assertEqual(self.request("POST", "/api/assignments", {"profileId": first_profile, "userIds": [user_a, user_b]})[0], 200)
        records_before = len(self.server.app.store.state()["records"])
        status, _, _ = self.request("POST", "/api/assignments", {"profileId": second_profile, "userIds": [user_a, "missing"]})
        self.assertEqual(status, 400)
        state = self.server.app.store.state()
        self.assertEqual({u["id"]: u["profile"] for u in state["users"]}, {user_a: first_profile, user_b: first_profile})
        self.assertEqual(len(state["records"]), records_before)
        self.assertEqual(self.request("POST", "/api/assignments", {"profileId": second_profile, "userIds": []})[0], 400)
        self.assertEqual(self.request("POST", "/api/assignments", {"profileId": second_profile, "userIds": [user_a]})[0], 200)
        state = self.server.app.store.state()
        self.assertEqual({u["id"]: u["profile"] for u in state["users"]}, {user_a: second_profile, user_b: first_profile})
        self.assertTrue(all(u["deviceCount"] == 0 for u in state["users"]))

    def test_import_with_assignments_is_atomic(self):
        self.login_first()
        user_a = self.user("甲")
        config = {"outbounds": [{"type": "direct", "tag": "direct"}]}
        records_before = len(self.server.app.store.state()["records"])
        status, _, _ = self.request("POST", "/api/profiles", {
            "name": "不可部分导入", "config": config, "userIds": [user_a, "missing"]})
        self.assertEqual(status, 400)
        state = self.server.app.store.state()
        self.assertEqual(state["profiles"], [])
        self.assertIsNone(state["users"][0]["profile"])
        self.assertEqual(len(state["records"]), records_before)
        status, data, _ = self.request("POST", "/api/profiles", {
            "name": "同时导入和分配", "config": config, "userIds": [user_a]})
        self.assertEqual(status, 200)
        state = self.server.app.store.state()
        self.assertEqual(state["profiles"][0]["version"], "v001")
        self.assertEqual(state["users"][0]["profile"], data["id"])

    def test_state_returns_one_consistent_snapshot_during_concurrent_import(self):
        store = self.server.app.store
        user_id = store.add_user({"name": "甲"})["id"]
        original_connect = store.connect
        writer_finished = threading.Event()
        errors = []
        writer = None

        def import_and_assign():
            try:
                store.import_profile({"name": "并发导入", "config": {
                    "outbounds": [{"type": "direct"}]}, "userIds": [user_id]})
            except Exception as error:
                errors.append(error)
            finally:
                writer_finished.set()

        @contextmanager
        def interleaved_connection():
            with original_connect() as connection:
                def before_users(statement):
                    nonlocal writer
                    if writer is None and (statement.startswith("SELECT * FROM users") or statement.startswith("SELECT u.*")):
                        writer = threading.Thread(target=import_and_assign)
                        writer.start()
                        # A writer can commit between reads if no snapshot transaction exists.
                        writer_finished.wait(timeout=1)
                connection.set_trace_callback(before_users)
                yield connection

        with patch.object(store, "connect", interleaved_connection):
            snapshot = store.state()
        writer.join(timeout=3)
        self.assertFalse(writer.is_alive())
        self.assertEqual(errors, [])
        profile_ids = {profile["id"] for profile in snapshot["profiles"]}
        for user in snapshot["users"]:
            self.assertTrue(user["profile"] is None or user["profile"] in profile_ids)
        final_state = store.state()
        self.assertEqual(final_state["users"][0]["profile"], final_state["profiles"][0]["id"])

    def test_rejects_cross_origin_untrusted_host_and_ignores_forwarded_headers(self):
        self.login_first()
        self.assertEqual(self.request("POST", "/api/users", {"name": "甲"}, extra_headers={"Origin": "https://evil.invalid"})[0], 403)
        self.assertEqual(self.request("GET", "/api/state", extra_headers={"Host": "evil.invalid"})[0], 403)
        self.assertEqual(self.request("POST", "/api/users", {"name": "甲"}, extra_headers={
            "Origin": "https://evil.invalid", "X-Forwarded-Host": "127.0.0.1:%d" % self.port,
            "X-Forwarded-Proto": "https"})[0], 403)
        self.assertEqual(self.request("POST", "/api/users", {"name": "甲"}, extra_headers={
            "Origin": "http://127.0.0.1:%d" % self.port})[0], 200)
        self.assertEqual(self.request("POST", "/api/users", {"name": "乙"}, extra_headers={
            "Host": "console.example.ts.net", "Origin": "https://console.example.ts.net"})[0], 200)
        self.assertEqual(self.request("POST", "/api/users", {"name": "丙"}, extra_headers={
            "Host": "console.example.ts.net", "Origin": "http://127.0.0.1:%d" % self.port})[0], 403)

    def test_static_directory_traversal_and_symlink_escape_are_rejected(self):
        self.assertEqual(self.request("GET", "/")[0], 200)
        for path in ("/../private.json", "/%2e%2e/private.json", "/%2e%2e%2fprivate.json",
                     "/%2e%2e%5cprivate.json", "/%00private.json", "/api/../private.json"):
            self.assertEqual(self.request("GET", path)[0], 401 if path.startswith("/api/") else 404, path)
        (self.web / "escape.json").symlink_to(self.root / "private.json")
        self.assertEqual(self.request("GET", "/escape.json")[0], 404)

    def test_invalid_config_input_and_body_limit_leave_database_unchanged(self):
        self.login_first()
        invalid = ({}, {"outbounds": []}, {"outbounds": ["vless"]}, {"outbounds": [{}]},
                   {"outbounds": [{"type": "vless", "tag": "same"}, {"type": "direct", "tag": "same"}]},
                   {"outbounds": [{"type": "direct"}], "dns": []})
        for config in invalid:
            self.assertEqual(self.request("POST", "/api/profiles", {"name": "配置", "config": config})[0], 400)
        for body in (b'{"name":"a","name":"b"}', b'{"config":NaN}', b'[]', b'bad JSON'):
            self.assertEqual(self.request("POST", "/api/profiles", raw_body=body)[0], 400)
        self.assertEqual(self.request("POST", "/api/profiles", raw_body=b"{}", extra_headers={
            "Content-Length": str(server_module.MAX_BODY_BYTES + 1)})[0], 413)
        self.assertEqual(self.request("POST", "/api/profiles", raw_body=b"{}", extra_headers={
            "Content-Type": "text/plain"})[0], 415)
        self.assertEqual(self.server.app.store.state()["profiles"], [])

    def test_rename_retains_version_and_content(self):
        self.login_first()
        profile_id, config = self.profile()
        status, _, _ = self.request("POST", "/api/profiles/%s/rename" % profile_id, {"name": "新名称"})
        self.assertEqual(status, 200)
        self.assertEqual(self.server.app.store.config(profile_id), ("新名称", config))
        self.assertEqual(self.server.app.store.state()["profiles"][0]["version"], "v001")


if __name__ == "__main__":
    unittest.main()
