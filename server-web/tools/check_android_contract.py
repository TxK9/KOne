"""Exercise real compiled Android API classes against a disposable backend candidate."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--server-root', type=Path, required=True)
parser.add_argument('--classes', type=Path, required=True)
parser.add_argument('--stdlib', type=Path, required=True)
parser.add_argument('--json-jar', type=Path, required=True)
parser.add_argument('--driver', type=Path, default=Path(__file__).with_name('AndroidContract.java'))
args = parser.parse_args()
sys.path.insert(0, str(args.server_root / 'backend/tests'))
import test_customers

fixture = test_customers.CustomerAPITest()
fixture.setUp()
try:
    other = fixture.claim(fixture.new_user('Synthetic other customer', fixture.group), name='Synthetic other phone')
    body = dict(requestId='11111111-1111-4111-8111-111111111111', category='other', description='Other device fixture',
                appVersion='1.0.0', versionCode=10056, androidVersion='15', manufacturer='Synthetic', model='Fixture', network='offline')
    assert fixture.request('/api/client/feedback', body, bearer=other['credential'], admin=False)[0] == 200
    app_only = fixture.request('/api/users', {'name': 'Synthetic app-only customer', 'appOnly': True})[1]['id']
    with fixture.server.app.store.connect() as db:
        config_name = db.execute('SELECT name FROM profiles WHERE id=?', (fixture.profile,)).fetchone()[0]
        group_name = db.execute('SELECT name FROM customer_groups WHERE id=?', (fixture.group,)).fetchone()[0]
    initial = dict(origin='http://127.0.0.1:' + str(fixture.server.server_port), invite=fixture.invite(),
                   appOnlyInvite=fixture.invite(app_only),
                   feedbackToken=fixture.request('/api/feedback/access', {'action': 'rotate'})[1]['token'],
                   configName=config_name, groupName=group_name)
    with tempfile.TemporaryDirectory(prefix='kone-contract-') as temporary:
        classpath = os.pathsep.join(str(p.resolve()) for p in [args.classes, args.stdlib, args.json_jar])
        subprocess.run(['javac', '-cp', classpath, '-d', temporary, str(args.driver)], check=True)
        child = subprocess.Popen(['java', '-cp', temporary + os.pathsep + classpath, 'AndroidContract'],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            child.stdin.write(json.dumps(initial) + '\n'); child.stdin.flush()
            for line in child.stdout:
                if not line.startswith('PHASE '):
                    print(line.rstrip(), flush=True)
                    continue
                phase = line.strip().split(' ', 1)[1]
                if phase == 'claimed':
                    child.stdin.write(fixture.invite() + '\n'); child.stdin.flush()
                    continue
                if phase == 'initial':
                    with fixture.server.app.store.connect() as db:
                        device = db.execute('SELECT id,ack_status,app_version FROM customer_devices WHERE user_id=?', (fixture.user,)).fetchone()
                        assert device['ack_status'] == 'applied'
                        assert device['app_version'] == '1.0.0contract'
                        device_id = device['id']
                    assert fixture.request('/api/customer-groups/' + fixture.group + '/update', {'name': 'Contract revised group'})[0] == 200
                elif phase == 'revised':
                    assert fixture.request('/api/customer-users/' + fixture.user + '/pause', {'paused': True})[0] == 200
                elif phase == 'paused':
                    assert fixture.request('/api/customer-users/' + fixture.user + '/pause', {'paused': False})[0] == 200
                    with fixture.server.app.store.connect() as db:
                        db.execute('UPDATE customer_accounts SET joined=? WHERE user_id=?', (time.time() - 31 * 86400, fixture.user))
                elif phase == 'expired':
                    assert fixture.request('/api/customer-groups/' + fixture.group + '/update', {'validDays': 0})[0] == 200
                elif phase == 'no-expiry':
                    assert fixture.request('/api/customer-users/' + fixture.user + '/devices/' + device_id + '/revoke', {})[0] == 200
                elif phase != 'finished':
                    raise AssertionError('Unexpected contract phase')
                child.stdin.write('ok\n'); child.stdin.flush()
            code = child.wait(timeout=30)
            error = child.stderr.read()
            if code:
                raise RuntimeError('Client contract process failed: ' + error)
            print('PASS backend acknowledgement state and disposable fixture lifecycle', flush=True)
        finally:
            if child.poll() is None:
                child.kill(); child.wait()
finally:
    fixture.tearDown()
