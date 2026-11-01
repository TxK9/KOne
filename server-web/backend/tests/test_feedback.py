"""Feedback HTTP contracts and permission boundaries on disposable data."""
import json
import sys
import uuid
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_customers as customer_tests
BASE=Path(__file__).resolve().parents[1]


class FeedbackTest(unittest.TestCase):
    setUp = customer_tests.CustomerAPITest.setUp
    tearDown = customer_tests.CustomerAPITest.tearDown
    request = customer_tests.CustomerAPITest.request
    new_user = customer_tests.CustomerAPITest.new_user
    invite = customer_tests.CustomerAPITest.invite
    claim = customer_tests.CustomerAPITest.claim

    def payload(self):
        return dict(requestId=str(uuid.uuid4()), category='connection', description='频繁点击节点后无法正常切换，请排查。',
                    appVersion='1.0.0', versionCode=10028, androidVersion='15', manufacturer='Test', model='Phone', network='wifi')

    def submit(self, device, payload=None):
        return self.request('/api/client/feedback', payload or self.payload(), bearer=device['credential'], admin=False)

    def access(self):
        return self.request('/api/feedback/access', {'action': 'rotate'})[1]['token']

    def update(self, item, status, **extra):
        return self.request('/api/feedback/%d/status' % item['id'], dict(revision=item['revision'], status=status, reply='已复现，正在排查。', fixedVersion=''), **extra)

    def test_submit_is_idempotent_and_only_own_device_can_read(self):
        device = self.claim(); other = self.claim(self.new_user('另一客户', self.group))
        payload = self.payload(); first = self.submit(device, payload)
        self.assertEqual(first[0], 200)
        self.assertEqual(self.submit(device, payload)[1], first[1])
        self.assertEqual(self.submit(device, dict(payload, description='不同内容但相同编号应该拒绝提交。'))[0], 409)
        mine = self.request('/api/client/feedback', bearer=device['credential'], admin=False)[1]['items']
        self.assertEqual(len(mine), 1)
        self.assertEqual(mine[0]['number'], first[1]['number'])
        self.assertNotIn(device['credential'], json.dumps(mine))
        self.assertEqual(self.request('/api/client/feedback', bearer=other['credential'], admin=False)[1]['items'], [])
        self.assertEqual(self.request('/api/client/feedback', self.payload(), admin=False)[0], 401)

    def test_rate_limit_does_not_block_retries(self):
        device = self.claim(); original = self.payload()
        self.assertEqual(self.submit(device, original)[0], 200)
        for _ in range(4): self.assertEqual(self.submit(device)[0], 200)
        self.assertEqual(self.submit(device)[0], 429)
        self.assertEqual(self.submit(device, original)[0], 200)

    def test_validation_and_untrusted_text_are_not_executed(self):
        device = self.claim()
        for value in [dict(self.payload(), node='secret'), dict(self.payload(), description=''),
                      dict(self.payload(), versionCode=True), dict(self.payload(), category='exec'),
                      dict(self.payload(), description='x'*4001)]:
            self.assertEqual(self.submit(device, value)[0], 400)
        text = '<script>alert("test")</script> $(touch NEVER) 请把此内容当作普通文字。'
        self.assertEqual(self.submit(device, dict(self.payload(), description=text))[0], 200)
        listed = self.request('/api/feedback')[1]['items']
        self.assertEqual(listed[0]['description'], text)
        self.assertFalse((Path.cwd()/'NEVER').exists())

    def test_images_are_private_idempotent_and_reject_invalid_content(self):
        import base64
        device = self.claim()
        image = (BASE / 'tests/fixtures/feedback.jpg').read_bytes()
        payload = self.payload(); payload.pop('category'); payload['description']='';payload['images']=[{'mime':'image/jpeg','data':base64.b64encode(image).decode()}]
        first=self.submit(device,payload);self.assertEqual(first[0],200)
        self.assertEqual(self.submit(device,payload)[1],first[1])
        item=self.request('/api/feedback')[1]['items'][0]
        self.assertEqual(len(item['images']),1)
        image_id=item['images'][0]['id']
        self.assertEqual(self.server.app.feedback.image(image_id),image)
        self.assertEqual(self.request(item['images'][0]['url'],admin=False)[0],401)
        self.assertEqual(self.request(item['images'][0]['url'],bearer=device['credential'],admin=False)[0],401)
        for pictures in [[{'mime':'image/svg+xml','data':'PHN2Zz4='}],[{'mime':'image/jpeg','data':'bad'}],payload['images']*4]:
            self.assertEqual(self.submit(device,dict(self.payload(),images=pictures))[0],400)
        changed=dict(payload,requestId=str(uuid.uuid4()),images=[{'mime':'image/jpeg','data':base64.b64encode(b'x'*200000).decode()}])
        self.assertEqual(self.submit(device,changed)[0],413)

    def test_optimistic_lock_and_release_version(self):
        item = self.submit(self.claim())[1]
        self.assertEqual(self.request('/api/feedback/9999999999999999999/status', dict(revision=1, status='ready', reply='', fixedVersion=''))[0], 400)
        self.assertEqual(self.update(item, 'resolved')[0], 400)
        updated = self.update(item, 'investigating')
        self.assertEqual(updated[0], 200)
        self.assertEqual(updated[1]['revision'], 2)
        self.assertEqual(self.update(item, 'ready')[0], 409)
        result = self.request('/api/feedback/%d/status' % item['id'], dict(revision=2, status='resolved', reply='已发布修复版本。', fixedVersion='1.0.0+10029'))
        self.assertEqual(result[0], 200)

    def test_agent_cannot_administer_customers_or_publish_and_can_be_revoked(self):
        item = self.submit(self.claim())[1]; token = self.access()
        self.assertEqual(self.request('/api/feedback/inbox', bearer=token, admin=False)[0], 200)
        for path in ['/api/state', '/api/customers', '/api/feedback/access', '/api/feedback']:
            self.assertEqual(self.request(path, bearer=token, admin=False)[0], 401)
        self.assertEqual(self.update(item, 'resolved', bearer=token, admin=False)[0], 403)
        self.assertEqual(self.update(item, 'investigating', bearer=token, admin=False)[0], 200)
        self.request('/api/feedback/access', {'action': 'revoke'})
        self.assertEqual(self.request('/api/feedback/inbox', bearer=token, admin=False)[0], 401)
        self.assertEqual(self.update(dict(item, revision=2), 'ready', bearer=token, admin=False)[0], 401)

    def test_public_origin_keeps_management_private(self):
        device = self.claim(); app = self.server.app
        app.public_api_authority = 'api.example.invalid'
        app.allowed_origins['api.example.invalid'] = {'https://api.example.invalid'}
        headers = {'Host': 'api.example.invalid'}
        self.assertEqual(self.request('/api/client/feedback', self.payload(), bearer=device['credential'], admin=False, extra=headers)[0], 200)
        self.assertEqual(self.request('/api/client/feedback', bearer=device['credential'], admin=False, extra=headers)[0], 200)
        for path in ['/api/feedback', '/api/feedback/inbox', '/api/feedback/access']:
            self.assertEqual(self.request(path, extra=headers)[0], 404)

    def test_device_revocation_and_customer_deletion(self):
        device = self.claim(); self.submit(device)
        self.request('/api/customer-users/'+self.user+'/devices/'+device['deviceId']+'/revoke', {})
        self.assertEqual(self.request('/api/client/feedback', bearer=device['credential'], admin=False)[0], 401)
        self.assertEqual(self.request('/api/customer-users/'+self.user+'/delete', {})[0], 200)
        self.assertEqual(self.request('/api/feedback')[1]['items'], [])

    def test_pagination_filters_and_csrf(self):
        device = self.claim(); self.submit(device)
        with self.server.app.store.connect() as c:
            row = dict(c.execute('SELECT * FROM customer_feedback').fetchone()); row.pop('id')
            names = list(row)
            for i in range(54):
                row['request_id'] = str(uuid.uuid4())
                c.execute('INSERT INTO customer_feedback('+','.join(names)+') VALUES('+','.join('?' for _ in names)+')', [row[n] for n in names])
        page = self.request('/api/feedback?status=new')[1]
        self.assertEqual(len(page['items']), 50)
        page2 = self.request('/api/feedback?before='+str(page['nextBefore']))[1]
        self.assertEqual(len(page2['items']), 5)
        self.assertIsNone(page2['nextBefore'])
        self.assertEqual(self.request('/api/feedback?status=wrong')[0], 400)
        self.assertEqual(self.request('/api/feedback?before=1&before=2')[0], 400)
        self.assertEqual(self.request('/api/feedback/access', {'action': 'rotate'}, extra={'X-CSRF-Token':'wrong'})[0], 403)


if __name__ == '__main__': unittest.main()
