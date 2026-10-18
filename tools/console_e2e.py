#!/usr/bin/env python3
"""A disposable KOne console for testing application activation without live server data.

It starts the console from KONE_CONSOLE_DIR on 127.0.0.1:8790 with a temporary database (the
real console's var/ is never touched), sets itself up with a random admin password that is never
printed, creates a synthetic line (a node at node.invalid that never connects), a group and
a customer, and writes a one-time key to <dir>/invite.txt. Commands are read from <dir>/cmd:
status, quota, revoke, invite, stop; results go to <dir>/out.json.

    python3 -B tools/console_e2e.py /tmp/kone-e2e &
    adb reverse tcp:8790 tcp:8790
    KONE_CONSOLE=http://127.0.0.1:8790 tools/build_share.sh /tmp/kone-e2e/apk
    adb install /tmp/kone-e2e/apk/KOne-share-*.apk     # cc.txwd.kone, separate from the personal edition
    # open it with `adb shell am start -n cc.txwd.kone/io.nekohasekai.sfa.compose.MainActivity`
    # (not `monkey`, which also taps somewhere at random), enter the key from invite.txt.
    echo revoke > /tmp/kone-e2e/cmd ; echo stop > /tmp/kone-e2e/cmd
    adb uninstall cc.txwd.kone ; adb reverse --remove tcp:8790

Never start the VPN in this disposable copy: it can replace an active VPN connection.
"""
import http.client, json, os, secrets, sys, tempfile, threading, time
from pathlib import Path

console_source = os.environ.get('KONE_CONSOLE_DIR')
if not console_source:
    raise SystemExit('Set KONE_CONSOLE_DIR to the authorized console source directory.')
CONSOLE = Path(console_source).expanduser().resolve()
HERE = Path(sys.argv[1]); HERE.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(CONSOLE))
from server import create_server  # noqa: E402

tmp = tempfile.TemporaryDirectory(prefix='kone-e2e-')
server = create_server(port=8790, data_dir=Path(tmp.name) / 'data')
threading.Thread(target=server.serve_forever, daemon=True).start()
session = {}


def call(path, payload=None, admin=True, method=None):
    c = http.client.HTTPConnection('127.0.0.1', 8790, timeout=10)
    headers = {'Content-Type': 'application/json'}
    if admin and session:
        headers.update({'Cookie': session['cookie'], 'X-CSRF-Token': session['csrf']})
    c.request(method or ('POST' if payload is not None else 'GET'), path, json.dumps(payload) if payload is not None else None, headers)
    r = c.getresponse(); raw = r.read(); hdr = dict(r.getheaders()); c.close()
    return r.status, (json.loads(raw) if raw else None), hdr


status, data, headers = call('/api/auth/setup', {'password': secrets.token_urlsafe(24)}, admin=False)
assert status == 200, status
session['cookie'] = headers['Set-Cookie'].split(';')[0]; session['csrf'] = data['csrfToken']
outbound = {'type': 'vless', 'tag': 'test', 'server': 'node.invalid', 'server_port': 443,
            'uuid': '11111111-1111-4111-8111-111111111111', 'tls': {'enabled': True, 'server_name': 'node.invalid'}}
profile = call('/api/profiles', {'name': 'Test route', 'config': {'outbounds': [outbound]}})[1]['id']
group_fields = {'name': 'Test group', 'profileId': profile, 'quotaBytes': 100 * 1024 ** 3, 'deviceLimit': 1, 'validDays': 30}
group = call('/api/customer-groups', group_fields)[1]['id']
user = call('/api/users', {'name': 'Test client', 'groupId': group})[1]['id']


def invite():
    (HERE / 'invite.txt').write_text(call('/api/customer-users/' + user + '/invite', {})[1]['inviteCode'])


invite()
(HERE / 'out.json').write_text(json.dumps({'ready': True}))
cmd = HERE / 'cmd'
while True:
    time.sleep(0.5)
    if not cmd.exists():
        continue
    order = cmd.read_text().strip(); cmd.unlink()
    if order == 'status':
        (HERE / 'out.json').write_text(json.dumps(call('/api/customer-users/' + user + '/detail')[1], ensure_ascii=False, indent=1))
    elif order == 'quota':
        (HERE / 'out.json').write_text(json.dumps({'quota': call('/api/customer-groups/' + group + '/update', dict(group_fields, quotaBytes=50 * 1024 ** 3))[0]}))
    elif order == 'revoke':
        result = call('/api/customer-users/' + user + '/revoke', {})
        (HERE / 'out.json').write_text(json.dumps({'revoke': result[0], 'body': result[1]}, ensure_ascii=False))
    elif order == 'invite':
        invite(); (HERE / 'out.json').write_text(json.dumps({'invite': True}))
    elif order == 'stop':
        break
server.shutdown(); server.server_close(); tmp.cleanup()
(HERE / 'invite.txt').unlink(missing_ok=True)
(HERE / 'out.json').write_text(json.dumps({'stopped': True}))
