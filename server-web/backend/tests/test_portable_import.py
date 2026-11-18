"""Offline interchange checks using synthetic nodes and temporary databases."""
import ast
import base64
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
from config_import import ConfigImportError
from portable_import import parse_import_text, portable_config, merge_config_nodes, export_node_links
from server import APIError, ConsoleStore, parse_json_text, validate_config

IDENTITY = '11111111-2222-4333-8444-555555555555'
KEY = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip('=')
PASSWORD = 'synthetic-secret-do-not-echo'


def parse(value):
    return parse_import_text(value, parse_json_text)


def clash(**changes):
    node = {'name': 'First node', 'type': 'vless', 'server': 'node.invalid', 'port': 443, 'uuid': IDENTITY,
            'tls': True, 'servername': 'tls.invalid', 'client-fingerprint': 'chrome',
            'reality-opts': {'public-key': KEY, 'short-id': '0123'}, 'flow': 'xtls-rprx-vision'}
    node.update(changes)
    return node


class PortableImportTest(unittest.TestCase):
    def test_clash_yaml_preserves_reality_and_ws(self):
        raw = '''proxies:
  - name: First node
    type: vless
    server: node.invalid
    port: 443
    uuid: ''' + IDENTITY + '''
    tls: true
    servername: tls.invalid
    reality-opts: {public-key: ''' + KEY + ''', short-id: "0123"}
    flow: xtls-rprx-vision
  - name: Backup
    type: trojan
    server: backup.invalid
    port: 8443
    password: synthetic-secret-do-not-echo
    network: ws
    ws-opts: {path: /gateway, headers: {Host: front.invalid}}
proxy-groups: [{name: Router group, type: select, proxies: [First node, Backup]}]
rules: [MATCH,Router group]
'''
        config, warnings, format_ = parse(raw)
        self.assertEqual(format_, 'clash-yaml')
        self.assertEqual(validate_config(config)['nodes'], 2)
        nodes = {n['tag']: n for n in config['outbounds']}
        self.assertEqual(nodes['First node']['tls']['reality']['public_key'], KEY)
        self.assertEqual(nodes['First node']['tls']['reality']['short_id'], '0123')
        self.assertEqual(nodes['Backup']['transport']['headers'], {'Host': 'front.invalid'})
        self.assertEqual(nodes['Backup']['password'], PASSWORD)
        self.assertNotIn('rules', config['route'])
        self.assertEqual(len(warnings), 1)

    def test_xray_reality_and_chain(self):
        first = {'protocol': 'vless', 'tag': 'Exit', 'settings': {'vnext': [{'address': 'exit.invalid', 'port': 443, 'users': [{'id': IDENTITY, 'encryption': 'none', 'flow': 'xtls-rprx-vision'}]}]}, 'streamSettings': {'network': 'tcp', 'security': 'reality', 'realitySettings': {'serverName': 'tls.invalid', 'fingerprint': 'chrome', 'publicKey': KEY, 'shortId': '0123'}}, 'proxySettings': {'tag': 'Relay', 'transportLayer': True}}
        relay = {'protocol': 'trojan', 'tag': 'Relay', 'settings': {'servers': [{'address': 'relay.invalid', 'port': 443, 'password': PASSWORD}]}, 'streamSettings': {'security': 'tls', 'tlsSettings': {'serverName': 'relay.invalid'}}}
        config, warnings, format_ = parse(json.dumps({'outbounds': [first, relay, {'protocol': 'freedom', 'tag': 'direct'}]}))
        self.assertEqual(format_, 'xray-json')
        exit_node = next(n for n in config['outbounds'] if n['tag'] == 'Exit')
        self.assertEqual(exit_node['detour'], 'Relay')
        self.assertEqual(exit_node['tls']['reality']['public_key'], KEY)
        self.assertEqual(len(warnings), 1)
        first['proxySettings']['tag'] = 'Missing'
        with self.assertRaises(ConfigImportError):
            parse(json.dumps({'outbounds': [first, relay]}))

    def test_common_links_and_base64_subscription(self):
        ss_auth = base64.urlsafe_b64encode(('aes-128-gcm:' + PASSWORD).encode()).decode().rstrip('=')
        vmess = {'v': '2', 'ps': 'VMess', 'add': 'vmess.invalid', 'port': '443', 'id': IDENTITY, 'aid': '0', 'scy': 'auto', 'net': 'ws', 'type': 'none', 'host': 'front.invalid', 'path': '/ws', 'tls': 'tls', 'sni': 'tls.invalid'}
        links = '\n'.join(['ss://' + ss_auth + '@ss.invalid:443#Shadowsocks', 'trojan://' + PASSWORD + '@trojan.invalid:443?security=tls&sni=tls.invalid#Trojan', 'vmess://' + base64.b64encode(json.dumps(vmess).encode()).decode()])
        config, _, _ = parse(links)
        self.assertEqual(validate_config(config)['nodes'], 3)
        nodes = {n['type']: n for n in config['outbounds']}
        self.assertEqual(nodes['shadowsocks']['password'], PASSWORD)
        self.assertEqual(nodes['vmess']['transport']['path'], '/ws')
        encoded = base64.b64encode(links.encode()).decode()
        self.assertEqual(parse(encoded)[0], config)
        self.assertEqual(parse(encoded)[2], 'base64-links')

    def test_unknown_options_are_not_silently_dropped(self):
        examples = [clash(plugin='unhandled'), clash(network='quic'), clash(**{'reality-opts': {'public-key': KEY, 'short-id': 'g'}}), clash(**{'dialer-proxy': 'Missing'})]
        for node in examples:
            with self.subTest(case=examples.index(node)):
                with self.assertRaises(ConfigImportError) as error:
                    parse(json.dumps({'proxies': [node]}))
                self.assertNotIn(IDENTITY, str(error.exception))
                self.assertNotIn(KEY, str(error.exception))

    def test_yaml_is_bounded_and_rejects_aliases_duplicates_tags(self):
        examples = ['proxies: &nodes []\ncopy: *nodes', 'proxies: []\nproxies: []', 'proxies: !!python/object/apply:os.system [echo secret]', 'proxies: ' + '[' * 40 + '0' + ']' * 40, 'proxies: []\nnumber: .inf', 'proxies: []\ndate: 2026-11-02']
        for example in examples:
            with self.subTest(case=examples.index(example)):
                with self.assertRaises(ConfigImportError):
                    parse(example)

    def test_import_limits_json_duplicates_and_invalid_types(self):
        examples = ['x' * (512 * 1024 + 1), '', '{"outbounds":[],"outbounds":[]}', '{"proxies": [null]}', '{"proxies": []}', '{"outbounds":[{"protocol":"vless","mux":true}]}', 'trojan://x@node.invalid:443?insecure=yes', 'https://subscription.invalid/' + PASSWORD]
        for example in examples:
            with self.subTest(case=examples.index(example)):
                with self.assertRaises(ConfigImportError) as error:
                    parse(example)
                self.assertNotIn(PASSWORD, str(error.exception))

    def test_nonstandard_router_pin_is_explicitly_rejected(self):
        raw = {'outbounds': [{'protocol': 'hysteria', 'tag': 'Relay', 'settings': {'address': 'relay.invalid', 'port': 443, 'version': 2}, 'streamSettings': {'security': 'tls', 'hysteriaSettings': {'version': 2, 'auth': PASSWORD}, 'tlsSettings': {'pinnedPeerCertSha256': 'synthetic-pin'}, 'finalmask': {'udp': []}}}]}
        with self.assertRaisesRegex(ConfigImportError, 'certificate pinning or transport masks'):
            parse(json.dumps(raw))

    def test_singbox_import_is_lossless(self):
        raw = {'outbounds': [{'type': 'direct', 'tag': 'direct'}], 'inbounds': [{'type': 'tun', 'tag': 'router'}], 'metadata': {'large': 9223372036854775807123456789}}
        config, warnings, format_ = parse(json.dumps(raw))
        self.assertEqual(config, raw)
        self.assertEqual(format_, 'sing-box')
        self.assertEqual(warnings, [])

    def test_portable_export_keeps_chains_and_strips_router_runtime(self):
        original = {'inbounds': [{'type': 'tun', 'tag': 'router'}], 'dns': {'servers': []}, 'route': {'rules': [{'process_name': ['test']}], 'final': 'Exit'}, 'outbounds': [{'type': 'socks', 'tag': 'Exit', 'server': 'exit.invalid', 'server_port': 1080, 'detour': 'Relay'}, {'type': 'hysteria2', 'tag': 'Relay', 'server': 'relay.invalid', 'server_port': 443, 'password': PASSWORD, 'tls': {'enabled': True}}]}
        before = copy.deepcopy(original)
        config = portable_config(original)
        self.assertEqual([n for n in config['outbounds'] if n['type'] != 'selector'], before['outbounds'])
        selector = config['outbounds'][0]
        self.assertEqual(selector['default'], 'Exit')
        self.assertEqual(config['route'], {'final': selector['tag']})
        self.assertNotIn('inbounds', config)
        self.assertNotIn('dns', config)
        self.assertEqual(original, before)
        original['outbounds'].pop()
        with self.assertRaises(ConfigImportError):
            portable_config(original)

    def test_portable_export_rejects_cycles_and_router_bindings(self):
        config = {'outbounds': [{'type': 'socks', 'tag': 'A', 'detour': 'B'}, {'type': 'socks', 'tag': 'B', 'detour': 'A'}]}
        with self.assertRaises(ConfigImportError):
            portable_config(config)
        config['outbounds'] = [{'type': 'socks', 'tag': 'A', 'bind_interface': 'router-only'}]
        with self.assertRaises(ConfigImportError):
            portable_config(config)

    def test_preview_has_no_credentials_or_database_side_effects(self):
        with tempfile.TemporaryDirectory(prefix='kone-preview-') as directory:
            store = ConsoleStore(directory)
            raw = json.dumps({'proxies': [clash()]})
            before = store.state()
            result = store.preview_profile({'configText': raw})
            self.assertEqual(result['nodes'], 1)
            self.assertEqual(store.state(), before)
            output = json.dumps(result)
            for secret in (IDENTITY, KEY, 'node.invalid', 'tls.invalid'):
                self.assertNotIn(secret, output)
            imported = store.import_profile({'name': 'Synthetic', 'configText': raw})
            self.assertEqual(store.config(imported['id'])[1]['outbounds'][0]['uuid'], IDENTITY)

    def test_passwall_uci_keeps_chain_and_current_default(self):
        raw = """config global 'settings'
 option node 'rules'
config nodes 'rules'
 option protocol '_shunt'
 option default_node 'relay'
config nodes 'exit'
 option type 'Xray'
 option protocol 'socks'
 option remarks 'Exit'
 option address 'exit.invalid'
 option port '1080'
 option username 'synthetic'
 option password 'synthetic-secret-do-not-echo'
 option chain_proxy '1'
 option preproxy_node 'relay'
config nodes 'relay'
 option type 'Xray'
 option protocol 'hysteria2'
 option remarks 'Relay'
 option address 'relay.invalid'
 option port '443'
 option tls_serverName 'tls.invalid'
 option hysteria2_auth_password 'synthetic-secret-do-not-echo'
"""
        config, warnings, format_ = parse(raw)
        self.assertEqual(format_, 'PassWall UCI')
        self.assertEqual(config['route']['final'], 'Relay')
        self.assertEqual(config['outbounds'][0]['detour'], 'Relay')
        portable = portable_config(config)
        self.assertEqual(portable['outbounds'][0]['default'], 'Relay')
        self.assertIn('Exit', portable['outbounds'][0]['outbounds'])
        with self.assertRaisesRegex(ConfigImportError, 'cannot preserve proxy chains'):
            export_node_links(config)
        with self.assertRaises(ConfigImportError):
            parse(raw.replace("option preproxy_node 'relay'", "option preproxy_node 'missing'"))
        with self.assertRaises(ConfigImportError):
            parse("config nodes 'x'\n option password '" + 'x' * 9000)

    def test_merge_preserves_default_and_rules_and_is_idempotent(self):
        first = {'type': 'trojan', 'tag': 'Original', 'server': 'original.invalid', 'server_port': 443, 'password': PASSWORD, 'tls': {'enabled': True}}
        existing = {'outbounds': [{'type': 'selector', 'tag': 'Main', 'outbounds': ['Original'], 'default': 'Original'}, first], 'route': {'final': 'Main', 'rules': [{'domain': ['local.invalid'], 'outbound': 'Original'}]}, 'dns': {'servers': []}, 'inbounds': [{'type': 'tun', 'tag': 'Preserved'}]}
        incoming, _, _ = parse(json.dumps({'proxies': [clash(name='Original'), {'type': 'trojan', 'name': 'Original duplicate', 'server': 'original.invalid', 'port': 443, 'password': PASSWORD}]}))
        merged, added = merge_config_nodes(existing, incoming)
        self.assertEqual(added, 1)
        self.assertEqual(merged['outbounds'][0]['default'], 'Original')
        self.assertIn('Original-2', merged['outbounds'][0]['outbounds'])
        self.assertEqual(merged['route'], existing['route'])
        self.assertEqual(merged['inbounds'], existing['inbounds'])
        again, added = merge_config_nodes(merged, incoming)
        self.assertEqual(added, 0)
        self.assertEqual(again, merged)
        self.assertEqual(len(existing['outbounds'][0]['outbounds']), 1)
        implicit = copy.deepcopy(existing)
        del implicit['route']['final']
        implicit_merged, _ = merge_config_nodes(implicit, incoming)
        self.assertNotIn('final', implicit_merged['route'])
        self.assertEqual(implicit_merged['outbounds'][0]['default'], 'Original')
        single, _ = merge_config_nodes({'outbounds': [first]}, incoming)
        self.assertEqual(single['outbounds'][0]['default'], 'Original')
        self.assertEqual(single['route']['final'], single['outbounds'][0]['tag'])

    def test_share_link_feed_roundtrip_and_chain_rejection(self):
        original, _, _ = parse(json.dumps({'proxies': [clash(), {'type': 'trojan', 'name': 'Backup', 'server': 'backup.invalid', 'port': 443, 'password': PASSWORD}]}))
        links = export_node_links(original)
        config, _, _ = parse(links)
        nodes = [n for n in config['outbounds'] if n['type'] in {'vless', 'trojan'}]
        self.assertEqual(len(nodes), 2)
        self.assertEqual(nodes[0]['tls']['reality']['public_key'], KEY)
        self.assertEqual(nodes[1]['password'], PASSWORD)
        original['outbounds'][1]['detour'] = 'Backup'
        with self.assertRaises(ConfigImportError):
            export_node_links(original)

    def test_every_new_message_has_both_locales(self):
        source = (BASE/'web/js/i18n.js').read_text()
        english = json.loads(source.split('export const EN = ', 1)[1].split(';\n\nexport const LOCALES', 1)[0])
        chinese = json.loads(source.split('const ZH_PORTABLE = ', 1)[1].split(';\nexport const LOCALE_KEY', 1)[0])
        for node in ast.walk(ast.parse((BASE/'portable_import.py').read_text() + '\n' + (BASE/'passwall_import.py').read_text())):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == 'fail' and node.args and isinstance(node.args[0], ast.Constant):
                message = node.args[0].value
                self.assertEqual(english[message], message)
                self.assertIn(message, chinese)


import test_server as api_fixture


class PortableAPITest(unittest.TestCase):
    setUp = api_fixture.ConsoleAPITest.setUp
    tearDown = api_fixture.ConsoleAPITest.tearDown
    request = api_fixture.ConsoleAPITest.request
    login_first = api_fixture.ConsoleAPITest.login_first

    def test_preview_and_export_require_admin(self):
        self.assertEqual(self.request('POST', '/api/profiles/preview', {'configText': '{}'})[0], 401)
        self.assertEqual(self.request('GET', '/api/profiles/00000000-0000-4000-8000-000000000000/portable')[0], 401)

    def test_authenticated_preview_import_and_portable_download(self):
        self.login_first()
        raw = json.dumps({'proxies': [clash()]})
        status, preview, _ = self.request('POST', '/api/profiles/preview', {'configText': raw})
        self.assertEqual(status, 200)
        self.assertEqual(preview['nodes'], 1)
        self.assertNotIn(IDENTITY, json.dumps(preview))
        self.assertEqual(self.request('GET', '/api/state')[1]['profiles'], [])
        status, imported, _ = self.request('POST', '/api/profiles', {'name': 'Synthetic', 'configText': raw})
        self.assertEqual(status, 200)
        status, config, headers = self.request('GET', '/api/profiles/' + imported['id'] + '/portable')
        self.assertEqual(status, 200)
        self.assertEqual(config['outbounds'][0]['uuid'], IDENTITY)
        self.assertNotIn('inbounds', config)
        self.assertIn('attachment', headers['Content-Disposition'])

    def test_preview_requires_csrf_and_uses_sanitized_errors(self):
        self.login_first()
        raw = 'https://subscription.invalid/' + PASSWORD
        self.assertEqual(self.request('POST', '/api/profiles/preview', {'configText': raw}, csrf=False)[0], 403)
        status, payload, _ = self.request('POST', '/api/profiles/preview', {'configText': raw})
        self.assertEqual(status, 400)
        self.assertNotIn(PASSWORD, json.dumps(payload))

    def test_canonical_update_keeps_devices_and_changes_revision(self):
        self.login_first()
        store, customers = self.server.app.store, self.server.app.customers
        user = store.add_user({'name': 'Synthetic user'})['id']
        original = {'outbounds': [{'type': 'trojan', 'tag': 'Original', 'server': 'original.invalid', 'server_port': 443, 'password': PASSWORD, 'tls': {'enabled': True}}], 'route': {'final': 'Original'}}
        profile = store.import_profile({'name': 'Shared pool', 'config': original, 'userIds': [user]})['id']
        invite = customers.invite(user)
        device = customers.claim({'inviteCode': invite['inviteCode'], 'deviceName': 'Synthetic Windows'})
        before = customers.configuration(device['deviceId'])
        revision = store.state()['profiles'][0]['revision']
        raw = json.dumps({'proxies': [clash()]})
        status, preview, _ = self.request('POST', '/api/profiles/preview', {'configText': raw, 'profileId': profile, 'expectedRevision': revision})
        self.assertEqual((status, preview['addedNodes'], preview['nodes']), (200, 1, 2))
        status, result, _ = self.request('POST', '/api/profiles/' + profile + '/update', {'configText': raw, 'expectedRevision': revision})
        self.assertEqual((status, result['addedNodes']), (200, 1))
        after = customers.configuration(device['deviceId'])
        self.assertNotEqual(after['revision'], before['revision'])
        self.assertEqual(customers.authenticate('Bearer ' + device['credential']), device['deviceId'])
        self.assertEqual(store.state()['users'][0]['profile'], profile)
        self.assertEqual(len(store.state()['profiles']), 1)
        selector = after['config']['outbounds'][0]
        self.assertEqual(selector['default'], 'Original')
        self.assertEqual(len(selector['outbounds']), 2)
        self.assertEqual(self.request('POST', '/api/profiles/' + profile + '/update', {'configText': raw, 'expectedRevision': revision})[0], 409)
        with store.connect() as connection:
            saved = connection.execute('SELECT config_json FROM profile_revisions WHERE profile_id=?', (profile,)).fetchone()
        self.assertEqual(json.loads(saved['config_json']), original)

    def test_scoped_subscription_tracks_updates_and_revocation(self):
        self.login_first()
        store = self.server.app.store
        raw = json.dumps({'proxies': [clash()]})
        profile = store.import_profile({'name': 'Shared', 'configText': raw})['id']
        created = store.create_subscription(profile)
        token = created['token']
        path = '/api/client/subscriptions/' + token + '/sing-box.json'
        status, data, headers = self.request('GET', path, auth=False)
        self.assertEqual(status, 200)
        self.assertIn('no-store', headers['Cache-Control'])
        self.assertEqual(headers['Referrer-Policy'], 'no-referrer')
        self.assertEqual(data['outbounds'][0]['uuid'], IDENTITY)
        self.assertNotIn(token, json.dumps(store.state()))
        current = next(p for p in store.state()['profiles'] if p['id'] == profile)['revision']
        store.update_profile(profile, {'configText': json.dumps({'proxies': [{'type': 'trojan', 'name': 'Later node', 'server': 'later.invalid', 'port': 443, 'password': PASSWORD}]}), 'expectedRevision': current})
        status, updated, _ = self.request('GET', path, auth=False)
        self.assertEqual(status, 200)
        self.assertEqual(validate_config(updated)['nodes'], 2)
        with store.connect() as connection:
            stored = connection.execute('SELECT token_hash FROM profile_subscriptions').fetchone()[0]
        self.assertNotEqual(stored, token)
        with self.assertRaises(api_fixture.server_module.APIError):
            store.create_subscription(profile)
        self.assertEqual(self.request('GET', path.replace(token, 'x' * 43), auth=False)[0], 404)
        self.assertEqual(self.request('GET', path + '?token=not-allowed', auth=False)[0], 404)
        self.assertEqual(self.request('POST', '/api/profiles/' + profile + '/subscription/revoke', {}, auth=False)[0], 401)
        self.assertEqual(self.request('POST', '/api/profiles/' + profile + '/subscription/revoke', {})[0], 200)
        self.assertEqual(self.request('GET', path, auth=False)[0], 404)



import test_public_api as public_fixture


class SubscriptionPublicAPITest(unittest.TestCase):
    setUp = public_fixture.PublicAPITest.setUp
    tearDown = public_fixture.PublicAPITest.tearDown
    request = public_fixture.PublicAPITest.request

    def test_public_gateway_accepts_only_scoped_feed_reads(self):
        store = self.server.app.store
        profile = store.import_profile({'name': 'Scoped pool', 'configText': json.dumps({'proxies': [clash()]})})['id']
        status, created, _ = self.request('POST', '/api/profiles/' + profile + '/subscription', {})
        self.assertEqual(status, 200)
        from urllib.parse import urlsplit
        path = urlsplit(created['singBoxUrl']).path
        self.assertEqual(self.request('GET', path, public=True)[0], 200)
        self.assertEqual(self.request('HEAD', path, public=True)[0], 200)
        self.assertEqual(self.request('POST', path, {}, public=True)[0], 405)
        self.assertEqual(self.request('GET', path + '?unexpected=1', public=True)[0], 404)
        self.assertEqual(self.request('GET', '/api/profiles/' + profile + '/portable', public=True)[0], 404)
        self.assertEqual(self.request('POST', '/api/profiles/' + profile + '/subscription', {}, public=True)[0], 404)
        status, body, _ = self.request('GET', urlsplit(created['shareLinksUrl']).path, public=True)
        self.assertEqual(status, 200)
        self.assertIn('vless://', base64.b64decode(body).decode())


if __name__ == '__main__':
    unittest.main()
