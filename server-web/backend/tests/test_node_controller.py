"""Node control security checks use synthetic credentials and temporary databases."""
import base64
import copy
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
from server import ConsoleStore, create_server
from customers import Customers
from fleet import Fleet
from node_controller import ControllerError, NODE_TYPES, TRANSPORTS
from deploy.maintenance import backup, verify, inspect_database, restore

PASSWORD = 'synthetic-controller-secret'


def source(chain=True):
    nodes = [{'type': 'hysteria2', 'tag': 'Relay', 'server': 'relay.invalid', 'server_port': 443,
              'password': PASSWORD, 'tls': {'enabled': True, 'server_name': 'tls.invalid'}}]
    if chain:
        nodes.append({'type': 'socks', 'tag': 'Exit', 'server': 'exit.invalid', 'server_port': 1080,
                      'username': 'synthetic-user', 'password': PASSWORD, 'detour': 'Relay'})
    return {'outbounds': nodes, 'route': {'final': nodes[-1]['tag']}}


def capabilities(platform='windows'):
    return {'protocol': 1, 'platform': platform, 'clientVersion': 'test-1', 'startupLease': True,
            'runtimeLease': True, 'protocols': sorted(NODE_TYPES), 'transports': sorted(TRANSPORTS)}


class NodeControllerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='kone-node-controller-')
        self.root = Path(self.temp.name)
        self.server = create_server(port=0, data_dir=self.root/'data')
        self.store, self.customers = self.server.app.store, self.server.app.customers
        self.controller = self.server.app.node_controller
        self.group = self.customers.save_group({'name': 'Synthetic group', 'deviceLimit': 4})['id']
        self.user = self.store.add_user({'name': 'Synthetic owner', 'groupId': self.group})['id']
        self.profile = self.store.import_profile({'name': 'Legacy source', 'config': source(False), 'userIds': [self.user]})['id']
        self.device = self.claim(self.user)

    def tearDown(self):
        self.server.server_close()
        self.temp.cleanup()

    def claim(self, user):
        invite = self.customers.invite(user)
        return self.customers.claim({'inviteCode': invite['inviteCode'], 'deviceName': 'Synthetic device'})

    def state(self, device=None):
        target = (device or self.device)['deviceId']
        return next(d for d in self.controller.listing()['devices'] if d['id'] == target)

    def imported(self, chain=True, **payload):
        result = self.controller.import_nodes({'configText': json.dumps(source(chain)), **payload})
        return {n['name']: n for n in result['nodes']}

    def enroll(self, device=None):
        device = device or self.device
        self.controller.register(device['deviceId'], capabilities())
        self.controller.configure(device['deviceId'], {'enabled': True, 'expectedGeneration': self.state(device)['authGeneration']})

    def grant(self, nodes, device=None):
        device = device or self.device
        self.controller.grant(device['deviceId'], {'nodeIds': nodes, 'expectedGeneration': self.state(device)['authGeneration']})

    def config(self, device=None):
        return self.customers.configuration((device or self.device)['deviceId'], controller_protocol=1)

    def lease(self, view=None, device=None, **changes):
        device = device or self.device
        view = view or self.config(device)
        body = {'protocol': 1, 'nonce': uuid.uuid4().hex, 'authGeneration': self.state(device)['authGeneration'], 'configRevision': view['configRevision']}
        body.update(changes)
        return self.controller.lease(device['deviceId'], body)

    def test_legacy_configuration_is_unchanged_and_online_lease_is_policy_checked(self):
        before = self.customers.configuration(self.device['deviceId'])
        self.controller.register(self.device['deviceId'], capabilities())
        self.assertEqual(self.customers.configuration(self.device['deviceId']), before)
        lease = self.lease(before)
        self.assertEqual(lease['mode'], 'legacy')
        self.assertFalse(lease['required'])
        self.assertEqual(lease['ttlSeconds'], 60)
        self.assertEqual(lease['notAfter'] - lease['serverTime'], 60)
        self.customers.set_member(self.user, 'pause', {'paused': True})
        with self.assertRaises(ControllerError) as error:
            self.lease(before)
        self.assertEqual((error.exception.status, error.exception.code), (403, 'account_denied'))

    def test_import_ids_deduplicate_and_metadata_does_not_disclose_connections(self):
        nodes = self.imported()
        again = self.controller.import_nodes({'configText': json.dumps(source())})
        self.assertEqual(again['imported'], 0)
        self.assertEqual({n['id'] for n in again['nodes']}, {n['id'] for n in nodes.values()})
        listing = json.dumps(self.controller.listing())
        for private in (PASSWORD, 'relay.invalid', 'exit.invalid', 'synthetic-user'):
            self.assertNotIn(private, listing)
        self.assertEqual(nodes['Exit']['dependencyIds'], [nodes['Relay']['id']])
        self.assertEqual(self.controller.detail(nodes['Exit']['id'])['config']['outbounds'][0]['password'], PASSWORD)
        self.assertNotIn('detour', self.controller.detail(nodes['Exit']['id'])['config']['outbounds'][0])

    def test_device_specific_grants_include_chain_dependencies_without_other_nodes(self):
        nodes = self.imported()
        phone = self.claim(self.user)
        self.enroll()
        self.enroll(phone)
        self.grant([nodes['Exit']['id']])
        self.grant([nodes['Relay']['id']], phone)
        view = self.config()
        self.assertEqual(view['controller']['rootNodeIds'], [nodes['Exit']['id']])
        self.assertEqual(set(view['controller']['nodeIds']), {n['id'] for n in nodes.values()})
        mapping = {n['id']: n['tag'] for n in view['controller']['nodes']}
        exit_node = next(n for n in view['config']['outbounds'] if n['tag'] == mapping[nodes['Exit']['id']])
        self.assertEqual(exit_node['detour'], mapping[nodes['Relay']['id']])
        self.assertEqual(self.config(phone)['controller']['nodeIds'], [nodes['Relay']['id']])
        self.assertEqual(self.store.state()['users'][0]['profile'], self.profile)

    def test_capability_reporting_does_not_enable_or_publish_and_unsupported_devices_are_refused(self):
        self.controller.register(self.device['deviceId'], capabilities())
        self.assertEqual(self.state()['controllerMode'], 'legacy')
        self.assertFalse(self.state()['ownerPublish'])
        with self.assertRaises(ControllerError):
            self.controller.register(self.device['deviceId'], {**capabilities(), 'ownerPublish': True})
        self.controller.register(self.device['deviceId'], {**capabilities(), 'runtimeLease': False})
        with self.assertRaises(ControllerError):
            self.controller.configure(self.device['deviceId'], {'enabled': True, 'expectedGeneration': 0})
        self.assertEqual(self.state()['controllerMode'], 'legacy')

    def test_nonce_and_generation_config_checks_are_authoritative(self):
        nodes = self.imported(False)
        self.enroll()
        self.grant([nodes['Relay']['id']])
        view = self.config()
        lease = self.lease(view)
        with self.assertRaises(ControllerError) as error:
            self.lease(view, nonce=lease['nonce'])
        self.assertEqual(error.exception.code, 'nonce_reused')
        for changed, code in [({'authGeneration': -1}, 'stale_generation'), ({'configRevision': '0' * 64}, 'stale_config')]:
            with self.assertRaises(ControllerError) as error:
                self.lease(view, **changed)
            self.assertEqual(error.exception.code, code)
        with self.assertRaises(ControllerError):
            self.lease(view, nodeIds=[str(uuid.uuid4())])
        self.assertEqual(lease['deviceId'], self.device['deviceId'])

    def test_legacy_mode_revoke_is_sticky_and_never_returns_old_profile_permission(self):
        self.controller.register(self.device['deviceId'], capabilities())
        old = self.config()
        self.controller.revoke(self.device['deviceId'], {'expectedGeneration': 0})
        self.assertEqual(self.state()['controllerMode'], 'revoked')
        with self.assertRaises(ControllerError) as error:
            self.lease(old)
        self.assertEqual(error.exception.code, 'authorization_revoked')
        with self.assertRaises(ControllerError):
            self.customers.configuration(self.device['deviceId'])
        with self.assertRaises(ControllerError):
            self.controller.publishing(self.device['deviceId'], {'allowed': True, 'expectedGeneration': self.state()['authGeneration']})
        self.controller.configure(self.device['deviceId'], {'enabled': True, 'expectedGeneration': self.state()['authGeneration']})
        self.assertEqual(self.state()['grantedNodeIds'], [])
        with self.assertRaises(ControllerError):
            self.config()

    def test_node_deletion_revokes_dependent_root_grants_and_restore_does_not_resurrect(self):
        nodes = self.imported()
        self.enroll()
        self.grant([nodes['Exit']['id']])
        previous = self.config()
        impact = self.controller.impact(nodes['Relay']['id'])
        self.assertEqual(impact['devices'][0]['affectedRootNodeIds'], [nodes['Exit']['id']])
        with self.assertRaises(ControllerError):
            self.controller.delete(nodes['Relay']['id'], {'expectedRevision': 1})
        self.controller.delete(nodes['Relay']['id'], {'expectedRevision': 1, 'confirmImpact': True})
        self.assertEqual(self.state()['grantedNodeIds'], [])
        with self.assertRaises(ControllerError):
            self.lease(previous)
        self.controller.restore(nodes['Relay']['id'], {'expectedRevision': 2})
        self.assertEqual(self.state()['grantedNodeIds'], [])
        self.grant([nodes['Exit']['id']])
        self.assertEqual(set(self.config()['controller']['nodeIds']), {n['id'] for n in nodes.values()})

    def test_tombstone_and_stale_node_updates_are_not_silently_overwritten(self):
        node = self.imported(False)['Relay']
        self.controller.update(node['id'], {'expectedRevision': 1, 'name': 'Renamed'})
        with self.assertRaises(ControllerError):
            self.controller.update(node['id'], {'expectedRevision': 1, 'name': 'Stale'})
        self.controller.delete(node['id'], {'expectedRevision': 2})
        with self.assertRaises(ControllerError) as error:
            self.imported(False)
        self.assertEqual(error.exception.code, 'node_deleted')
        self.assertEqual(self.controller.detail(node['id'])['revision'], 3)

    def test_chain_cycles_missing_dependencies_and_cross_customer_grants_fail_atomically(self):
        nodes = self.imported()
        with self.assertRaises(ControllerError):
            self.controller.update(nodes['Relay']['id'], {'expectedRevision': 1, 'dependencyIds': [nodes['Exit']['id']]})
        self.assertEqual(self.controller.detail(nodes['Relay']['id'])['dependencyIds'], [])
        with self.assertRaises(ControllerError):
            self.controller.update(nodes['Exit']['id'], {'expectedRevision': 1, 'dependencyIds': [str(uuid.uuid4())]})
        other = self.store.add_user({'name': 'Other customer', 'groupId': self.group})['id']
        private = self.imported(False, ownerUserId=other)['Relay']
        self.enroll()
        with self.assertRaises(ControllerError) as error:
            self.grant([private['id']])
        self.assertEqual(error.exception.code, 'tenant_mismatch')
        self.assertEqual(self.state()['grantedNodeIds'], [])

    def test_running_ack_needs_fresh_lease_stale_ack_is_rejected_and_expiry_is_visible(self):
        node = self.imported(False)['Relay']
        self.enroll()
        self.grant([node['id']])
        view = self.config()
        body = {'protocol': 1, 'authGeneration': self.state()['authGeneration'], 'revision': view['revision'],
                'configRevision': view['configRevision'], 'status': 'running', 'selectedNodeId': node['id']}
        with self.assertRaises(ControllerError) as error:
            self.controller.ack(self.device['deviceId'], body)
        self.assertEqual(error.exception.code, 'lease_required')
        lease = self.lease(view)
        self.controller.ack(self.device['deviceId'], body)
        self.assertEqual(self.state()['status'], 'applied')
        with patch('node_controller.time.time', return_value=lease['notAfter'] + 1):
            state = self.state()
        self.assertNotEqual(state['status'], 'applied')
        self.assertTrue(state['leaseExpired'])
        self.controller.update(node['id'], {'expectedRevision': 1, 'name': 'Changed'})
        with self.assertRaises(ControllerError):
            self.controller.ack(self.device['deviceId'], body)
        self.grant([])
        self.controller.ack(self.device['deviceId'], {'protocol': 1, 'authGeneration': self.state()['authGeneration'], 'status': 'stopped'})
        self.assertEqual(self.state()['ackStatus'], 'stopped')

    def test_app_only_accounts_can_receive_explicit_nodes_but_expiry_and_device_limit_still_apply(self):
        app_user = self.store.add_user({'name': 'App owner', 'groupId': self.group, 'appOnly': True})['id']
        device = self.claim(app_user)
        self.enroll(device)
        self.grant([self.imported(False)['Relay']['id']], device)
        view = self.config(device)
        self.assertEqual(self.lease(view, device)['mode'], 'enabled')
        with self.store.connect() as c:
            c.execute('UPDATE customer_groups SET valid_days=1 WHERE id=?', (self.group,))
            c.execute('UPDATE customer_accounts SET joined=0 WHERE user_id=?', (app_user,))
        with self.assertRaises(ControllerError) as error:
            self.lease(view, device)
        self.assertEqual(error.exception.code, 'account_denied')
        with self.store.connect() as c:
            c.execute('UPDATE customer_groups SET valid_days=0,device_limit=0 WHERE id=?', (self.group,))
        with self.assertRaises(ControllerError):
            self.config(device)

    def test_owner_publish_is_explicit_scoped_idempotent_and_never_auto_grants(self):
        body = {'protocol': 1, 'requestId': str(uuid.uuid4()), 'configText': json.dumps(source()), 'selectedTags': ['Exit']}
        with self.assertRaises(ControllerError) as error:
            self.controller.publish(self.device['deviceId'], body)
        self.assertEqual(error.exception.code, 'publishing_forbidden')
        self.controller.publishing(self.device['deviceId'], {'allowed': True, 'expectedGeneration': 0})
        created = self.controller.publish(self.device['deviceId'], body)
        self.assertFalse(created['automaticallyGranted'])
        self.assertEqual(self.controller.publish(self.device['deviceId'], body), created)
        self.assertEqual(self.state()['grantedNodeIds'], [])
        self.assertTrue(all(n['ownerUserId'] == self.user for n in created['nodes']))
        with self.assertRaises(ControllerError):
            self.controller.publish(self.device['deviceId'], {**body, 'selectedTags': ['Relay']})
        self.assertGreater(self.lease(self.config())['authGeneration'], 0)

    def test_schema_six_backup_is_complete_and_reinitialization_does_not_downgrade(self):
        nodes = self.imported()
        self.enroll()
        self.grant([nodes['Exit']['id']])
        self.lease(self.config())
        archive = self.root/'backup.zip'
        manifest = backup(self.root/'data', archive)
        self.assertEqual(manifest['user_version'], 6)
        self.assertIn('controller_grants', manifest['counts'])
        self.assertEqual(verify(archive)['user_version'], 6)
        Fleet(self.store)
        Customers(self.store)
        self.assertEqual(inspect_database(self.store.path)['user_version'], 6)
        with self.store.connect() as c:
            c.execute('PRAGMA user_version=7')
        with self.assertRaises(ValueError):
            ConsoleStore(self.root/'data')

    def test_restoring_pre_revoke_controller_snapshot_requires_explicit_reauthorization(self):
        import hashlib
        nodes = self.imported()
        self.enroll()
        self.grant([nodes['Exit']['id']])
        before = self.config()
        self.lease(before)
        self.controller.publishing(self.device['deviceId'], {'allowed': True, 'expectedGeneration': self.state()['authGeneration']})
        archive = self.root/'controller-before-revoke.zip'
        backup(self.root/'data', archive)
        archive_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
        self.controller.revoke(self.device['deviceId'], {'expectedGeneration': self.state()['authGeneration']})
        metadata = restore(archive, self.root/'restored-controller')
        self.assertTrue(metadata['controllerAuthorizationReset'])
        self.assertEqual(metadata['restoredSchemaVersion'], 6)
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), archive_hash)
        recovered = create_server(port=0, data_dir=self.root/'restored-controller')
        try:
            control = recovered.app.node_controller
            state = control.listing()['devices'][0]
            self.assertEqual(state['controllerMode'], 'revoked')
            self.assertEqual(state['grantedNodeIds'], [])
            self.assertFalse(state['ownerPublish'])
            self.assertIsNone(state['lastAckAt'])
            self.assertIsNone(state['leaseNotAfter'])
            self.assertEqual(len(control.listing()['nodes']), len(nodes))
            with self.assertRaises(ControllerError):
                control.lease(self.device['deviceId'], {'protocol': 1, 'nonce': uuid.uuid4().hex, 'authGeneration': state['authGeneration'], 'configRevision': before['configRevision']})
            control.configure(self.device['deviceId'], {'enabled': True, 'expectedGeneration': state['authGeneration']})
            self.assertEqual(control.listing()['devices'][0]['grantedNodeIds'], [])
        finally:
            recovered.server_close()

    def test_v5_pre_registration_restore_migrates_and_blocks_old_devices_not_new_invites(self):
        import hashlib
        directory = self.root/'legacy-v5'
        store = ConsoleStore(directory)
        fleet = Fleet(store)
        customers = Customers(store, fleet)
        group = customers.save_group({'name': 'Legacy group', 'deviceLimit': 3})['id']
        user = store.add_user({'name': 'Legacy owner', 'groupId': group})['id']
        store.import_profile({'name': 'Legacy route', 'config': source(False), 'userIds': [user]})
        ticket = customers.invite(user)
        device = customers.claim({'inviteCode': ticket['inviteCode'], 'deviceName': 'Pre-registration device'})
        old_view = customers.configuration(device['deviceId'])
        unused = customers.invite(user)['inviteCode']
        original_state = store.state()
        archive = self.root/'legacy-v5.zip'
        self.assertEqual(backup(directory, archive)['user_version'], 5)
        original_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
        restored = self.root/'legacy-restored'
        result = restore(archive, restored)
        self.assertEqual((result['backupSchemaVersion'], result['restoredSchemaVersion']), (5, 6))
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), original_hash)
        recovered = create_server(port=0, data_dir=restored)
        try:
            app = recovered.app
            self.assertEqual(app.store.state()['profiles'], original_state['profiles'])
            self.assertEqual(app.store.state()['users'], original_state['users'])
            self.assertEqual(app.customers.configuration(device['deviceId'])['configRevision'], old_view['configRevision'])
            with self.assertRaises(ControllerError):
                app.customers.configuration(device['deviceId'], controller_protocol=1)
            state = app.node_controller.listing()['devices'][0]
            self.assertEqual(state['controllerMode'], 'revoked')
            app.node_controller.register(device['deviceId'], capabilities())
            with self.assertRaises(ControllerError):
                app.node_controller.lease(device['deviceId'], {'protocol': 1, 'nonce': uuid.uuid4().hex, 'authGeneration': state['authGeneration'], 'configRevision': old_view['configRevision']})
            from customers import CustomerError
            with self.assertRaises(CustomerError):
                app.customers.claim({'inviteCode': unused, 'deviceName': 'Old invitation'})
            fresh = app.customers.invite(user)['inviteCode']
            new_device = app.customers.claim({'inviteCode': fresh, 'deviceName': 'New device'})
            registration = app.node_controller.register(new_device['deviceId'], capabilities())
            self.assertEqual(registration['controller']['mode'], 'legacy')
            view = app.customers.configuration(new_device['deviceId'], controller_protocol=1)
            self.assertEqual(app.node_controller.lease(new_device['deviceId'], {'protocol': 1, 'nonce': uuid.uuid4().hex, 'authGeneration': registration['controller']['authGeneration'], 'configRevision': view['configRevision']})['mode'], 'legacy')
        finally:
            recovered.server_close()

    def test_standalone_customer_helper_cannot_bypass_opted_in_controller(self):
        node = self.imported(False)['Relay']
        self.enroll()
        self.grant([node['id']])
        from customers import CustomerError
        standalone = Customers(self.store)
        with self.assertRaises(CustomerError):
            standalone.configuration(self.device['deviceId'], controller_protocol=1)
        with self.assertRaises(CustomerError):
            standalone.configuration(self.device['deviceId'])


import test_public_api as public_fixture


class NodeControllerAPITest(unittest.TestCase):
    setUp = public_fixture.PublicAPITest.setUp
    tearDown = public_fixture.PublicAPITest.tearDown
    request = public_fixture.PublicAPITest.request
    claim = public_fixture.PublicAPITest.claim

    def nodes(self):
        status, result, _ = self.request('POST', '/api/node-controller/nodes/import', {'configText': json.dumps(source())})
        self.assertEqual(status, 200)
        return {n['name']: n for n in result['nodes']}

    def test_admin_namespace_never_accepts_device_bearer_or_public_host(self):
        device = self.claim()
        payload = {'configText': json.dumps(source())}
        for method, path in [('GET', '/api/node-controller'), ('POST', '/api/node-controller/nodes/import')]:
            self.assertEqual(self.request(method, path, payload if method == 'POST' else None, public=True, bearer=device['credential'])[0], 404)
            self.assertEqual(self.request(method, path, payload if method == 'POST' else None, bearer=device['credential'], extra={'Cookie': ''})[0], 401)
        self.assertEqual(self.request('POST', '/api/node-controller/nodes/import', payload, extra={'X-CSRF-Token': ''})[0], 403)
        self.assertEqual(self.request('GET', '/api/node-controller')[1]['nodes'], [])

    def test_public_lease_authorizes_legacy_then_respects_sticky_controller_revoke(self):
        device = self.claim()
        # This fixture's original direct-only legacy profile remains valid legacy behavior.
        code, view, _ = self.request('GET', '/api/client/config', public=True, bearer=device['credential'])
        self.assertEqual(code, 200)
        self.assertNotIn('controller', view)
        code, registered, _ = self.request('POST', '/api/client/controller/register', capabilities(), public=True, bearer=device['credential'])
        self.assertEqual(code, 200)
        code, current, _ = self.request('GET', '/api/client/config', public=True, bearer=device['credential'], extra={'X-KOne-Controller-Protocol': '1'})
        self.assertEqual(current['configRevision'], view['configRevision'])
        self.assertEqual(current['revision'], view['revision'])
        self.assertEqual(current['controller']['mode'], 'legacy')
        body = {'protocol': 1, 'nonce': uuid.uuid4().hex, 'authGeneration': registered['controller']['authGeneration'], 'configRevision': view['configRevision']}
        code, lease, headers = self.request('POST', '/api/client/controller/lease', body, public=True, bearer=device['credential'])
        self.assertEqual((code, lease['deviceId'], lease['mode']), (200, device['deviceId'], 'legacy'))
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(self.request('POST', '/api/client/controller/lease', body, public=True)[0], 401)
        self.assertEqual(self.request('POST', '/api/client/controller/lease?unexpected=1', body, public=True, bearer=device['credential'])[0], 404)
        self.assertEqual(self.request('POST', '/api/node-controller/devices/' + device['deviceId'] + '/revoke', {'expectedGeneration': 0})[0], 200)
        body.update(authGeneration=1, nonce=uuid.uuid4().hex)
        code, denied, _ = self.request('POST', '/api/client/controller/lease', body, public=True, bearer=device['credential'])
        self.assertEqual((code, denied['errorCode']), (403, 'authorization_revoked'))
        self.assertEqual(denied['controller']['mode'], 'revoked')
        self.assertEqual(self.request('GET', '/api/client/config', public=True, bearer=device['credential'], extra={'X-KOne-Controller-Protocol': '1'})[0], 403)

    def test_complete_device_grant_config_lease_ack_and_private_details(self):
        device = self.claim()
        nodes = self.nodes()
        target = '/api/node-controller/devices/' + device['deviceId']
        self.assertEqual(self.request('POST', '/api/client/controller/register', capabilities(), public=True, bearer=device['credential'])[0], 200)
        self.assertEqual(self.request('POST', target + '/configure', {'enabled': True, 'expectedGeneration': 0})[0], 200)
        self.assertEqual(self.request('POST', target + '/grants', {'nodeIds': [nodes['Exit']['id']], 'expectedGeneration': 1})[0], 200)
        self.assertEqual(self.request('GET', '/api/client/config', public=True, bearer=device['credential'])[0], 426)
        code, view, _ = self.request('GET', '/api/client/config', public=True, bearer=device['credential'], extra={'X-KOne-Controller-Protocol': '1'})
        self.assertEqual(code, 200)
        self.assertEqual(view['controller']['rootNodeIds'], [nodes['Exit']['id']])
        self.assertEqual(len(view['controller']['nodes']), 2)
        lease_body = {'protocol': 1, 'nonce': uuid.uuid4().hex, 'authGeneration': 2, 'configRevision': view['configRevision']}
        self.assertEqual(self.request('POST', '/api/client/controller/lease', lease_body, public=True, bearer=device['credential'])[0], 200)
        ack = {'protocol': 1, 'authGeneration': 2, 'configRevision': view['configRevision'], 'revision': view['revision'], 'status': 'running', 'selectedNodeId': nodes['Exit']['id']}
        self.assertEqual(self.request('POST', '/api/client/controller/ack', ack, public=True, bearer=device['credential'])[0], 200)
        state = self.request('GET', '/api/node-controller')[1]
        self.assertEqual(state['devices'][0]['status'], 'applied')
        self.assertNotIn(PASSWORD, json.dumps(state))
        private = '/api/node-controller/nodes/' + nodes['Exit']['id'] + '/config'
        self.assertEqual(self.request('GET', private, public=True, bearer=device['credential'])[0], 404)
        self.assertEqual(self.request('GET', private, bearer=device['credential'], extra={'Cookie': ''})[0], 401)
        self.assertEqual(self.request('GET', private)[1]['config']['outbounds'][0]['password'], PASSWORD)
        self.assertEqual(self.request('POST', target + '/grants', {'nodeIds': [], 'expectedGeneration': 1})[0], 409)
        self.assertEqual(self.request('POST', target + '/grants', {'nodeIds': [], 'expectedGeneration': 2})[0], 200)
        self.assertEqual(self.request('POST', '/api/client/controller/ack', ack, public=True, bearer=device['credential'])[0], 409)
        stopped = {'protocol': 1, 'authGeneration': 3, 'status': 'stopped'}
        self.assertEqual(self.request('POST', '/api/client/controller/ack', stopped, public=True, bearer=device['credential'])[0], 200)

    def test_public_registration_cannot_self_grant_publishing(self):
        device = self.claim()
        self.assertEqual(self.request('POST', '/api/client/controller/register', {**capabilities(), 'ownerPublish': True}, public=True, bearer=device['credential'])[0], 400)
        body = {'protocol': 1, 'requestId': str(uuid.uuid4()), 'configText': json.dumps(source()), 'selectedTags': ['Exit']}
        code, result, _ = self.request('POST', '/api/client/controller/nodes/publish', body, public=True, bearer=device['credential'])
        self.assertEqual((code, result['errorCode']), (403, 'publishing_forbidden'))
        self.assertEqual(self.request('GET', '/api/node-controller')[1]['nodes'], [])
