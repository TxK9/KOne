"""Negative integration checks for the source collaboration handoff.

All credentials and records are generated inside a disposable loopback fixture.
"""
import time
import unittest

import test_customers as fixtures


class RoleBoundaryTest(unittest.TestCase):
    setUp = fixtures.CustomerAPITest.setUp
    tearDown = fixtures.CustomerAPITest.tearDown
    request = fixtures.CustomerAPITest.request
    new_user = fixtures.CustomerAPITest.new_user
    invite = fixtures.CustomerAPITest.invite
    claim = fixtures.CustomerAPITest.claim
    enroll = fixtures.CustomerAPITest.enroll

    def test_valid_non_admin_bearers_cannot_read_or_mutate_management(self):
        device = self.claim(name='Synthetic device')['credential']
        node = self.enroll('Synthetic node')['credential']
        feedback = self.request('/api/feedback/access', {'action': 'rotate'})[1]['token']
        reads = ['/api/state', '/api/customers', '/api/fleet', '/api/owner-release',
                 '/api/feedback', '/api/feedback/access',
                 '/api/profiles/' + self.profile + '/config']
        writes = ['/api/users', '/api/profiles', '/api/customer-groups',
                  '/api/fleet/enrollments', '/api/feedback/access',
                  '/api/owner-update/enroll', '/api/customer-users/' + self.user + '/delete']
        for role, token in [('device', device), ('node', node), ('feedback', feedback)]:
            for path in reads:
                with self.subTest(role=role, method='GET', path=path):
                    self.assertEqual(self.request(path, bearer=token, admin=False)[0], 401)
            for path in writes:
                with self.subTest(role=role, method='POST', path=path):
                    self.assertEqual(self.request(path, {}, bearer=token, admin=False)[0], 401)

    def test_expired_admin_session_cannot_be_revived_by_its_csrf_token(self):
        token = self.cookie.split('=', 1)[1]
        with self.server.app.session_lock:
            self.server.app.sessions[token]['expires'] = time.time() - 1
        self.assertEqual(self.request('/api/state')[0], 401)
        self.assertEqual(self.request('/api/users', {'name': 'Synthetic user'})[0], 401)
        self.assertEqual(self.request('/api/feedback/access', {'action': 'rotate'})[0], 401)

    def test_public_host_cannot_use_admin_cookie_for_management_methods(self):
        app = self.server.app
        app.public_api_authority = 'api.example.invalid'
        app.allowed_origins['api.example.invalid'] = {'https://api.example.invalid'}
        headers = {'Host': 'api.example.invalid'}
        for path in ['/api/state', '/api/customers', '/api/fleet', '/api/feedback/access']:
            for method in ['GET', 'HEAD', 'POST']:
                with self.subTest(path=path, method=method):
                    self.assertEqual(self.request(path, {} if method == 'POST' else None,
                                                  method=method, extra=headers)[0], 404)


if __name__ == '__main__':
    unittest.main()
