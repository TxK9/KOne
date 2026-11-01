"""Share-link regression checks. All hosts and credentials are synthetic."""
import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest
from urllib.parse import urlencode

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
from config_import import ConfigImportError, parse_node_links
from server import APIError, ConsoleStore, validate_config

UUID = "11111111-2222-4333-8444-555555555555"
KEY = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")


def vless(**changes):
    params = {"type": "tcp", "encryption": "none", "security": "reality", "pbk": KEY,
              "fp": "chrome", "sni": "camouflage.invalid", "sid": "0123456789abcdef",
              "spx": "/test-path", "flow": "xtls-rprx-vision"}
    for key, value in changes.items():
        if value is None:
            params.pop(key, None)
        else:
            params[key] = value
    return "vless://" + UUID + "@node.invalid:666/?" + urlencode(params) + "#%E7%BE%8E%E5%9B%BD"


class ShareLinkTest(unittest.TestCase):
    def test_reality_link_preserves_connection_parameters(self):
        config, warnings = parse_node_links(vless())
        node = config["outbounds"][0]
        self.assertEqual(node["server"], "node.invalid")
        self.assertEqual(node["server_port"], 666)
        self.assertEqual(node["uuid"], UUID)
        self.assertEqual(node["flow"], "xtls-rprx-vision")
        self.assertEqual(node["tls"]["server_name"], "camouflage.invalid")
        self.assertEqual(node["tls"]["utls"], {"enabled": True, "fingerprint": "chrome"})
        self.assertEqual(node["tls"]["reality"], {"enabled": True, "public_key": KEY, "short_id": "0123456789abcdef"})
        self.assertEqual(config["route"]["final"], node["tag"])
        self.assertEqual(validate_config(config)["nodes"], 1)
        self.assertEqual(len(warnings), 1)
        self.assertNotIn("spx", json.dumps(config))

    def test_websocket_tls_fields_are_preserved(self):
        config, _ = parse_node_links(vless(security="tls", pbk=None, sid=None, spx=None, flow=None,
                                           type="ws", path="/node?ed=2048", host="web.invalid",
                                           alpn="h2,http/1.1", allowInsecure="0"))
        node = config["outbounds"][0]
        self.assertEqual(node["transport"], {"type": "ws", "path": "/node?ed=2048", "headers": {"Host": "web.invalid"}})
        self.assertEqual(node["tls"]["alpn"], ["h2", "http/1.1"])
        self.assertFalse(node["tls"]["insecure"])

    def test_hy2_ipv6_percent_encoded_auth_and_default_port(self):
        config, warnings = parse_node_links("hy2://user%3Ap%40ss%2Bword@[2001:db8::1]/?sni=hy.invalid&insecure=0&obfs=salamander&obfs-password=test%2Bsecret#HY2")
        node = config["outbounds"][0]
        self.assertEqual(node["type"], "hysteria2")
        self.assertEqual(node["server"], "2001:db8::1")
        self.assertEqual(node["server_port"], 443)
        self.assertEqual(node["password"], "user:p@ss+word")
        self.assertEqual(node["obfs"]["password"], "test+secret")
        self.assertFalse(node["tls"]["insecure"])
        self.assertEqual(warnings, [])

    def test_multiple_links_keep_unique_selectable_tags(self):
        config, _ = parse_node_links(vless() + "\n" + vless() + "\n" + "hysteria2://test-password@backup.invalid:8443/#direct")
        self.assertEqual(validate_config(config)["nodes"], 3)
        selector = config["outbounds"][0]
        self.assertEqual(selector["type"], "selector")
        tags = [node["tag"] for node in config["outbounds"]]
        self.assertEqual(len(tags), len(set(tags)))
        self.assertEqual(selector["outbounds"], tags[1:-1])
        self.assertEqual(config["route"]["final"], "proxy")

    def test_invalid_or_unsupported_fields_fail_without_silent_loss(self):
        cases = [vless(pbk="invalid"), vless(sid="xyz"), vless(encryption="unsupported"),
                 vless(type="ws"), vless(type="quic"), vless(extra="do-not-drop"),
                 vless().replace("#", "&security=tls#"), vless().replace(UUID, "private-invalid-uuid"),
                 vless().replace(":666/", ":0/"), vless().replace("node.invalid", "[broken"),
                 "hy2://password@node.invalid/?insecure=maybe", "hy2://password@node.invalid/?pinSHA256=secret-pin",
                 "https://subscription.invalid/private-token", "hy2://%ZZ@node.invalid/",
                 "hy2://password@node.invalid/?sni=x&sni=y",
                 "hy2://password@node.invalid/?sni=first.invalid&peer=second.invalid",
                 "hy2://password@node.invalid/?obfs-password=secret",
                 "hy2://password@node.invalid/?sni=%0Asecret",
                 "vless://" + UUID + "@node.invalid:443/?security=tls&type=grpc&path=unused",
                 "hy2://password@node.invalid/private-path"]
        for text in cases:
            with self.subTest(case=cases.index(text)):
                with self.assertRaises(ConfigImportError) as error:
                    parse_node_links(text)
                for secret in (UUID, "private-invalid-uuid", "secret-pin", "private-token", "test-password"):
                    self.assertNotIn(secret, str(error.exception))

    def test_plain_vless_does_not_enable_tls(self):
        config, _ = parse_node_links("vless://" + UUID + "@node.invalid:1234/?encryption=none#Plain")
        self.assertNotIn("tls", config["outbounds"][0])

    def test_import_and_assignment_are_atomic_and_json_remains_lossless(self):
        with tempfile.TemporaryDirectory(prefix="kone-import-test-") as temp:
            store = ConsoleStore(temp)
            user = store.add_user({"name": "测试客户"})["id"]
            imported = store.import_profile({"name": "节点链接测试", "configText": vless(), "userIds": [user]})
            self.assertEqual(store.state()["users"][0]["profile"], imported["id"])
            self.assertEqual(store.config(imported["id"])[1]["outbounds"][0]["uuid"], UUID)
            with self.assertRaises(APIError):
                store.import_profile({"name": "无效批量", "configText": vless() + "\nhttps://subscription.invalid/secret", "userIds": [user]})
            self.assertEqual(len(store.state()["profiles"]), 1)
            self.assertEqual(store.state()["users"][0]["profile"], imported["id"])
            raw = '{"outbounds":[{"type":"direct","tag":"direct"}],"metadata":{"serial":9223372036854775807123456789}}'
            original = store.import_profile({"name": "原 JSON", "configText": raw})
            self.assertEqual(store.config(original["id"])[1], json.loads(raw))
            with self.assertRaises(APIError):
                store.import_profile({"name": "重复字段", "configText": '{"outbounds":[],"outbounds":[]}'})


if __name__ == "__main__":
    unittest.main()
