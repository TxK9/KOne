"""Owner node inventory, explicit device grants and fresh online traffic leases."""
import copy
import hashlib
import json
import re
import time
import uuid

from config_import import ConfigImportError
from customers import CustomerError, digest, refuse_claude
from portable_import import NODE_TYPES, parse_import_text, portable_config

PROTOCOL = 1
TTL_SECONDS = 60
RENEW_SECONDS = 20
TRANSPORTS = {'tcp', 'ws', 'grpc', 'httpupgrade', 'quic'}
ACK_STATUSES = {'received', 'installed', 'running', 'stopped', 'failed'}


class ControllerError(Exception):
    def __init__(self, status, message, code='invalid_request', controller=None):
        super().__init__(message)
        self.status, self.message, self.code, self.controller = status, message, code, controller

    def payload(self):
        result = {'error': self.message, 'errorCode': self.code}
        if self.controller is not None:
            result['controller'] = self.controller
        return result


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', value):
        raise ControllerError(400, 'Choose a valid node or device identifier.')
    return value


def name(value):
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= 80 or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ControllerError(400, 'Use a node name between 1 and 80 characters.')
    return value.strip()


def ids(value):
    if not isinstance(value, list) or len(value) > 200:
        raise ControllerError(400, 'Choose no more than 200 nodes.')
    return list(dict.fromkeys(identifier(item) for item in value))


def expected(value, actual, label='revision'):
    if type(value) is not int or value != actual:
        raise ControllerError(409, 'The node or device changed. Refresh before trying again.', 'stale_' + label)


class NodeController:
    def __init__(self, store, customers, json_parser):
        self.store, self.customers, self.json_parser = store, customers, json_parser
        with store.connect() as c:
            c.executescript('''
                BEGIN IMMEDIATE;
                CREATE TABLE IF NOT EXISTS controller_nodes(
                    id TEXT PRIMARY KEY, owner_scope TEXT NOT NULL, name TEXT NOT NULL,
                    protocol TEXT NOT NULL, outbound_json TEXT NOT NULL, connection_hash TEXT NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 1, deleted INTEGER NOT NULL DEFAULT 0,
                    origin_device_id TEXT REFERENCES customer_devices(id) ON DELETE SET NULL,
                    created REAL NOT NULL, updated REAL NOT NULL,
                    UNIQUE(owner_scope,connection_hash));
                CREATE TABLE IF NOT EXISTS controller_dependencies(
                    node_id TEXT NOT NULL REFERENCES controller_nodes(id),
                    dependency_id TEXT NOT NULL REFERENCES controller_nodes(id),
                    PRIMARY KEY(node_id,dependency_id));
                CREATE TABLE IF NOT EXISTS controller_devices(
                    device_id TEXT PRIMARY KEY REFERENCES customer_devices(id) ON DELETE CASCADE,
                    mode TEXT NOT NULL DEFAULT 'legacy', auth_generation INTEGER NOT NULL DEFAULT 0,
                    protocol INTEGER NOT NULL DEFAULT 0, platform TEXT NOT NULL DEFAULT '',
                    client_version TEXT NOT NULL DEFAULT '', startup_lease INTEGER NOT NULL DEFAULT 0,
                    runtime_lease INTEGER NOT NULL DEFAULT 0, protocols_json TEXT NOT NULL DEFAULT '[]',
                    transports_json TEXT NOT NULL DEFAULT '[]', owner_publish INTEGER NOT NULL DEFAULT 0,
                    registered_at REAL, ack_generation INTEGER, ack_status TEXT, ack_revision TEXT,
                    ack_config_revision TEXT, ack_at REAL, selected_node_id TEXT,
                    lease_generation INTEGER, lease_config_revision TEXT, lease_not_after REAL,
                    updated REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS controller_grants(
                    device_id TEXT NOT NULL REFERENCES customer_devices(id) ON DELETE CASCADE,
                    node_id TEXT NOT NULL REFERENCES controller_nodes(id), active INTEGER NOT NULL DEFAULT 1, order_index INTEGER NOT NULL DEFAULT 0,
                    updated REAL NOT NULL, PRIMARY KEY(device_id,node_id));
                CREATE TABLE IF NOT EXISTS controller_publish_requests(
                    device_id TEXT NOT NULL REFERENCES customer_devices(id) ON DELETE CASCADE,
                    request_id TEXT NOT NULL, request_hash TEXT NOT NULL, result_json TEXT NOT NULL,
                    created REAL NOT NULL, PRIMARY KEY(device_id,request_id));
                CREATE TABLE IF NOT EXISTS controller_lease_nonces(
                    device_id TEXT NOT NULL REFERENCES customer_devices(id) ON DELETE CASCADE,
                    nonce_hash TEXT NOT NULL, created REAL NOT NULL, PRIMARY KEY(device_id,nonce_hash));
                PRAGMA user_version=6;
                COMMIT;
            ''')
        store.restrict_files()

    @staticmethod
    def _row(c, node_id, include_deleted=False):
        row = c.execute('SELECT * FROM controller_nodes WHERE id=?', (identifier(node_id),)).fetchone()
        if row is None:
            raise ControllerError(404, 'Node not found.', 'node_not_found')
        if row['deleted'] and not include_deleted:
            raise ControllerError(409, 'This node was deleted. Restore it explicitly before using it.', 'node_deleted')
        return row

    @staticmethod
    def _device(c, device_id):
        row = c.execute('SELECT d.*,u.name AS user_name FROM customer_devices d JOIN users u ON u.id=d.user_id WHERE d.id=?', (identifier(device_id),)).fetchone()
        if row is None:
            raise ControllerError(404, 'Device not found.', 'device_not_found')
        return row

    @staticmethod
    def _state(c, device_id, create=False):
        if create:
            c.execute('INSERT OR IGNORE INTO controller_devices(device_id,updated) VALUES(?,?)', (device_id, time.time()))
        row = c.execute('SELECT * FROM controller_devices WHERE device_id=?', (device_id,)).fetchone()
        return dict(row) if row else {'device_id': device_id, 'mode': 'legacy', 'auth_generation': 0,
            'protocol': 0, 'platform': '', 'client_version': '', 'startup_lease': 0, 'runtime_lease': 0,
            'protocols_json': '[]', 'transports_json': '[]', 'owner_publish': 0,
            'registered_at': None, 'ack_generation': None, 'ack_status': None, 'ack_at': None,
            'lease_generation': None, 'lease_config_revision': None, 'lease_not_after': None}

    @staticmethod
    def _controller(state):
        return {'format': PROTOCOL, 'deviceId': state['device_id'], 'mode': state['mode'],
                'required': state['mode'] != 'legacy', 'authGeneration': state['auth_generation'],
                'ttlSeconds': TTL_SECONDS, 'renewAfterSeconds': RENEW_SECONDS,
                'ownerPublish': bool(state['owner_publish'])}

    def _deny(self, state, status, message, code):
        raise ControllerError(status, message, code, self._controller(state))

    @staticmethod
    def _roots(c, device_id):
        return [r[0] for r in c.execute('SELECT node_id FROM controller_grants WHERE device_id=? AND active=1 ORDER BY order_index,node_id', (device_id,))]

    def _closure(self, c, roots, allow_deleted=False):
        order, seen, visiting = [], set(), set()
        def visit(node_id):
            if node_id in visiting:
                raise ControllerError(409, 'The node dependency graph contains a cycle.', 'dependency_cycle')
            if node_id in seen:
                return
            if len(visiting) >= 32 or len(seen) >= 200:
                raise ControllerError(400, 'The node dependency bundle is too large.')
            self._row(c, node_id, include_deleted=allow_deleted)
            visiting.add(node_id)
            for row in c.execute('SELECT dependency_id FROM controller_dependencies WHERE node_id=? ORDER BY dependency_id', (node_id,)):
                visit(row[0])
            visiting.remove(node_id)
            seen.add(node_id)
            order.append(node_id)
        for root in roots:
            visit(root)
        return order

    def _summary(self, c, row):
        dependencies = [r[0] for r in c.execute('SELECT dependency_id FROM controller_dependencies WHERE node_id=? ORDER BY dependency_id', (row['id'],))]
        devices = self._impacted(c, row['id'])
        return {'id': row['id'], 'name': row['name'], 'protocol': row['protocol'], 'revision': row['revision'],
                'deleted': bool(row['deleted']), 'dependencyIds': dependencies, 'grantCount': len(devices),
                'ownerUserId': row['owner_scope'][5:] if row['owner_scope'].startswith('user:') else None,
                'originDeviceId': row['origin_device_id'], 'updated': row['updated']}

    def _impacted(self, c, node_id):
        result = []
        for device_id, in c.execute('SELECT DISTINCT device_id FROM controller_grants WHERE active=1'):
            affected = [root for root in self._roots(c, device_id) if node_id in self._closure(c, [root], allow_deleted=True)]
            if affected:
                device = self._device(c, device_id)
                result.append({'id': device_id, 'name': device['name'], 'userName': device['user_name'],
                    'authGeneration': self._state(c, device_id)['auth_generation'], 'affectedRootNodeIds': affected})
        return result

    def impact(self, node_id):
        with self.store.connect() as c:
            self._row(c, node_id, True)
            dependents = [row[0] for row in c.execute('SELECT id FROM controller_nodes WHERE deleted=0')
                          if row[0] != node_id and node_id in self._closure(c, [row[0]], True)]
            devices = self._impacted(c, node_id)
            return {'nodeId': node_id, 'dependentNodeIds': dependents, 'devices': devices,
                    'grantCount': sum(len(d['affectedRootNodeIds']) for d in devices), 'originRevocation': 'not_performed'}

    def listing(self):
        with self.store.connect() as c:
            c.execute('BEGIN')
            nodes = [self._summary(c, row) for row in c.execute('SELECT * FROM controller_nodes ORDER BY created,id')]
            devices = []
            for device in c.execute('SELECT d.*,u.name AS user_name FROM customer_devices d JOIN users u ON u.id=d.user_id ORDER BY d.created,d.id'):
                state = self._state(c, device['id'])
                roots = self._roots(c, device['id'])
                expanded = self._closure(c, roots, True)
                capable = self._capable(state)
                lease_valid = bool(state['lease_not_after'] and state['lease_not_after'] > time.time() and state['lease_generation'] == state['auth_generation'])
                status = 'revoked' if device['revoked'] or state['mode'] == 'revoked' else 'legacy' if state['mode'] == 'legacy' else 'unsupported' if not capable else 'applied' if state['ack_generation'] == state['auth_generation'] and state['ack_status'] == 'running' and lease_valid else 'pending'
                devices.append({'id': device['id'], 'userId': device['user_id'], 'userName': device['user_name'],
                    'name': device['name'], 'platform': state['platform'], 'clientVersion': state['client_version'],
                    'registered': state['registered_at'] is not None, 'protocol': state['protocol'],
                    'startupLease': bool(state['startup_lease']), 'runtimeLease': bool(state['runtime_lease']),
                    'capabilitiesReported': capable, 'capabilitiesVerified': False,
                    'controllerMode': state['mode'], 'controllerEnabled': state['mode'] == 'enabled',
                    'ownerPublish': bool(state['owner_publish']), 'authGeneration': state['auth_generation'],
                    'ackGeneration': state['ack_generation'], 'ackStatus': state['ack_status'], 'lastAckAt': state['ack_at'],
                    'leaseExpired': not lease_valid, 'lastLeaseAt': state['lease_not_after'] - TTL_SECONDS if state['lease_not_after'] else None, 'leaseNotAfter': state['lease_not_after'],
                    'status': status, 'grantedNodeIds': roots, 'expandedNodeIds': expanded, 'lastSeen': device['last_seen']})
            return {'format': PROTOCOL, 'serverTime': time.time(), 'nodes': nodes, 'devices': devices}

    def detail(self, node_id):
        with self.store.connect() as c:
            row = self._row(c, node_id, True)
            result = self._summary(c, row)
            outbound = json.loads(row['outbound_json'])
            outbound['tag'] = row['name']
            result['config'] = {'outbounds': [outbound]}
            return result

    def _parse(self, payload):
        try:
            config, warnings, _ = parse_import_text(payload.get('configText'), self.json_parser)
            config = portable_config(config)
        except ConfigImportError as error:
            raise ControllerError(400, str(error), 'invalid_configuration') from None
        nodes = [dict(n) for n in config['outbounds'] if n['type'] in NODE_TYPES]
        if not nodes or len(nodes) > 200:
            raise ControllerError(400, 'Import between 1 and 200 proxy nodes.')
        by_tag = {n['tag']: n for n in nodes}
        if len(by_tag) != len(nodes):
            raise ControllerError(400, 'Node names must be unique within an import.')
        for node in nodes:
            name(node['tag'])
            if node.get('detour') and node['detour'] not in by_tag:
                raise ControllerError(400, 'A node dependency must reference another imported proxy node.', 'missing_dependency')
        for node in nodes:
            chain, cursor = set(), node
            while cursor.get('detour'):
                if cursor['tag'] in chain or len(chain) >= 32:
                    raise ControllerError(400, 'The node dependency bundle is too large.')
                chain.add(cursor['tag'])
                cursor = by_tag[cursor['detour']]
        return nodes, warnings

    def preview(self, payload):
        nodes, warnings = self._parse(payload)
        return {'ok': True, 'nodeCount': len(nodes), 'nodes': [{'name': n['tag'], 'protocol': n['type'],
                'dependencyTags': [n['detour']] if n.get('detour') else []} for n in nodes], 'warnings': warnings}

    @staticmethod
    def _connection(outbound, dependencies):
        return digest(canonical({'outbound': outbound, 'dependencyIds': dependencies}))

    def _import(self, c, payload, scope='admin', origin=None):
        nodes, warnings = self._parse(payload)
        by_tag = {node['tag']: node for node in nodes}
        done, created = {}, []
        def add(tag):
            if tag in done:
                return done[tag]
            node = dict(by_tag[tag])
            dependency = node.pop('detour', None)
            dependencies = [add(dependency)] if dependency else []
            label = name(payload.get('name') if len(nodes) == 1 and payload.get('name') else node.pop('tag'))
            node.pop('tag', None)
            fingerprint = self._connection(node, dependencies)
            row = c.execute('SELECT * FROM controller_nodes WHERE owner_scope=? AND connection_hash=?', (scope, fingerprint)).fetchone()
            if row:
                if row['deleted']:
                    raise ControllerError(409, 'This node was deleted. Restore it explicitly before using it.', 'node_deleted')
                done[tag] = row['id']
                return row['id']
            node_id, now = str(uuid.uuid4()), time.time()
            c.execute('INSERT INTO controller_nodes VALUES(?,?,?,?,?,?,1,0,?,?,?)', (node_id, scope, label, node['type'], canonical(node), fingerprint, origin, now, now))
            for dependency_id in dependencies:
                c.execute('INSERT INTO controller_dependencies VALUES(?,?)', (node_id, dependency_id))
            done[tag] = node_id
            created.append(node_id)
            return node_id
        for tag in by_tag:
            add(tag)
        return {'ok': True, 'imported': len(created), 'nodes': [self._summary(c, self._row(c, done[tag])) for tag in by_tag], 'warnings': warnings}

    def import_nodes(self, payload):
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            scope = 'admin'
            if payload.get('ownerUserId') is not None:
                owner = identifier(payload['ownerUserId'])
                if not c.execute('SELECT 1 FROM users WHERE id=?', (owner,)).fetchone():
                    raise ControllerError(404, 'Owner not found.')
                scope = 'user:' + owner
            result = self._import(c, payload, scope)
        self.store.restrict_files()
        return result

    @staticmethod
    def _bump(c, device_id):
        c.execute('UPDATE controller_devices SET auth_generation=auth_generation+1,updated=? WHERE device_id=?', (time.time(), device_id))

    def _compatible(self, c, device, state, roots):
        protocols, transports = set(json.loads(state['protocols_json'])), set(json.loads(state['transports_json']))
        for node_id in self._closure(c, roots):
            node = self._row(c, node_id)
            if node['owner_scope'].startswith('user:') and node['owner_scope'] != 'user:' + device['user_id']:
                raise ControllerError(403, 'A private node cannot be granted to another customer.', 'tenant_mismatch')
            outbound = json.loads(node['outbound_json'])
            transport = 'quic' if node['protocol'] in {'hysteria2', 'tuic'} else outbound.get('transport', {}).get('type', 'tcp')
            if node['protocol'] not in protocols or transport not in transports:
                raise ControllerError(409, 'The device has not reported support for this node protocol or transport.', 'unsupported_node')

    @staticmethod
    def _capable(state):
        return state['protocol'] == PROTOCOL and bool(state['startup_lease']) and bool(state['runtime_lease']) and bool(json.loads(state['protocols_json'])) and bool(json.loads(state['transports_json']))

    def configure(self, device_id, payload):
        if type(payload.get('enabled')) is not bool:
            raise ControllerError(400, 'Choose whether node control is enabled.')
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            device = self._device(c, device_id)
            state = self._state(c, device_id, True)
            expected(payload.get('expectedGeneration'), state['auth_generation'], 'generation')
            if payload['enabled'] and (device['revoked'] or not self._capable(state)):
                raise ControllerError(409, 'This device must report startup and runtime lease support before enrollment.', 'client_upgrade_required')
            mode = 'enabled' if payload['enabled'] else 'revoked'
            if mode != state['mode']:
                c.execute('UPDATE controller_devices SET mode=? WHERE device_id=?', (mode, device_id))
                if mode == 'revoked':
                    c.execute('UPDATE controller_devices SET owner_publish=0 WHERE device_id=?', (device_id,))
                    c.execute('UPDATE controller_grants SET active=0,updated=? WHERE device_id=?', (time.time(), device_id))
                self._bump(c, device_id)
        return {'ok': True}

    def grant(self, device_id, payload):
        roots = ids(payload.get('nodeIds'))
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            device = self._device(c, device_id)
            state = self._state(c, device_id, True)
            expected(payload.get('expectedGeneration'), state['auth_generation'], 'generation')
            if device['revoked'] or state['mode'] != 'enabled' or not self._capable(state):
                raise ControllerError(409, 'Enroll a compatible device before assigning nodes.', 'client_upgrade_required')
            self._compatible(c, device, state, roots)
            if self._roots(c, device_id) != roots:
                c.execute('UPDATE controller_grants SET active=0,updated=? WHERE device_id=?', (time.time(), device_id))
                for position, node_id in enumerate(roots):
                    c.execute('INSERT INTO controller_grants VALUES(?,?,1,?,?) ON CONFLICT(device_id,node_id) DO UPDATE SET active=1,order_index=excluded.order_index,updated=excluded.updated', (device_id, node_id, position, time.time()))
                self._bump(c, device_id)
        return {'ok': True, 'originRevocation': 'not_performed'}

    def revoke(self, device_id, payload):
        return self.configure(device_id, {**payload, 'enabled': False})

    def publishing(self, device_id, payload):
        if type(payload.get('allowed')) is not bool:
            raise ControllerError(400, 'Choose whether owner publishing is allowed.')
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            device = self._device(c, device_id)
            state = self._state(c, device_id, True)
            expected(payload.get('expectedGeneration'), state['auth_generation'], 'generation')
            if payload['allowed'] and (device['revoked'] or state['mode'] == 'revoked'):
                raise ControllerError(409, 'Restore device authorization before enabling owner publishing.', 'authorization_revoked')
            if bool(state['owner_publish']) != payload['allowed']:
                c.execute('UPDATE controller_devices SET owner_publish=? WHERE device_id=?', (int(payload['allowed']), device_id))
                self._bump(c, device_id)
        return {'ok': True}

    def update(self, node_id, payload):
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            row = self._row(c, node_id)
            expected(payload.get('expectedRevision'), row['revision'])
            outbound = json.loads(row['outbound_json'])
            label = name(payload['name']) if 'name' in payload else row['name']
            if 'configText' in payload:
                nodes, _ = self._parse(payload)
                if len(nodes) != 1 or nodes[0].get('detour'):
                    raise ControllerError(400, 'Edit one node at a time and use dependency IDs for its proxy chain.')
                outbound = {key: value for key, value in nodes[0].items() if key != 'tag'}
            dependencies = [r[0] for r in c.execute('SELECT dependency_id FROM controller_dependencies WHERE node_id=?', (node_id,))]
            if 'dependencyIds' in payload:
                dependencies = ids(payload['dependencyIds'])
            if len(dependencies) > 1:
                raise ControllerError(400, 'A proxy node can have only one direct detour dependency.')
            for dependency_id in dependencies:
                dependency = self._row(c, dependency_id)
                if dependency['owner_scope'] not in {'admin', row['owner_scope']}:
                    raise ControllerError(403, 'A node dependency belongs to another customer.', 'tenant_mismatch')
            current_dependencies = [r[0] for r in c.execute('SELECT dependency_id FROM controller_dependencies WHERE node_id=? ORDER BY dependency_id', (node_id,))]
            if label == row['name'] and canonical(outbound) == row['outbound_json'] and sorted(dependencies) == current_dependencies:
                return {'ok': True, 'node': self._summary(c, row)}
            impacted = self._impacted(c, node_id)
            fingerprint = self._connection(outbound, dependencies)
            other = c.execute('SELECT id FROM controller_nodes WHERE owner_scope=? AND connection_hash=? AND id<>?', (row['owner_scope'], fingerprint, node_id)).fetchone()
            if other:
                raise ControllerError(409, 'The same node connection already exists in this library.', 'duplicate_node')
            c.execute('DELETE FROM controller_dependencies WHERE node_id=?', (node_id,))
            for dependency_id in dependencies:
                c.execute('INSERT INTO controller_dependencies VALUES(?,?)', (node_id, dependency_id))
            self._closure(c, [node_id])
            c.execute('UPDATE controller_nodes SET name=?,protocol=?,outbound_json=?,connection_hash=?,revision=revision+1,updated=? WHERE id=?', (label, outbound['type'], canonical(outbound), fingerprint, time.time(), node_id))
            for device in impacted:
                record = self._device(c, device['id'])
                state = self._state(c, device['id'])
                self._compatible(c, record, state, self._roots(c, device['id']))
                self._bump(c, device['id'])
            result = self._summary(c, self._row(c, node_id))
        return {'ok': True, 'node': result}

    def delete(self, node_id, payload):
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            row = self._row(c, node_id, True)
            expected(payload.get('expectedRevision'), row['revision'])
            impacted = self._impacted(c, node_id)
            if impacted and payload.get('confirmImpact') is not True:
                raise ControllerError(409, 'Confirm the affected devices before deleting this node.', 'impact_confirmation_required')
            if not row['deleted']:
                for device in impacted:
                    for root in device['affectedRootNodeIds']:
                        c.execute('UPDATE controller_grants SET active=0,updated=? WHERE device_id=? AND node_id=?', (time.time(), device['id'], root))
                    self._bump(c, device['id'])
                c.execute('UPDATE controller_nodes SET deleted=1,revision=revision+1,updated=? WHERE id=?', (time.time(), node_id))
            result = self._summary(c, self._row(c, node_id, True))
        return {'ok': True, 'node': result, 'affectedDevices': impacted, 'originRevocation': 'not_performed'}

    def restore(self, node_id, payload):
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            row = self._row(c, node_id, True)
            expected(payload.get('expectedRevision'), row['revision'])
            if row['deleted']:
                self._closure(c, [r[0] for r in c.execute('SELECT dependency_id FROM controller_dependencies WHERE node_id=?', (node_id,))])
                c.execute('UPDATE controller_nodes SET deleted=0,revision=revision+1,updated=? WHERE id=?', (time.time(), node_id))
            result = self._summary(c, self._row(c, node_id))
        return {'ok': True, 'node': result, 'grantsRestored': False}

    def _mark_recovery(self, c, device_id):
        if c.execute('SELECT 1 FROM controller_devices WHERE device_id=?', (device_id,)).fetchone():
            return
        setting = c.execute("SELECT value FROM settings WHERE key='controller_recovery_epoch'").fetchone()
        if not setting:
            return
        try:
            recovery = json.loads(setting[0])
            members = recovery['deviceIds']
            if recovery.get('format') != 1 or not isinstance(members, list):
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise ControllerError(503, 'The node controller recovery state is invalid.', 'recovery_required') from None
        if device_id in members:
            c.execute("INSERT INTO controller_devices(device_id,mode,auth_generation,updated) VALUES(?,'revoked',1,?)", (device_id, time.time()))

    def recover_device(self, device_id):
        # Commit the recovery tombstone independently of a later denied request.
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            self._device(c, device_id)
            self._mark_recovery(c, device_id)

    def register(self, device_id, payload):
        allowed = {'protocol', 'platform', 'clientVersion', 'startupLease', 'runtimeLease', 'protocols', 'transports'}
        if set(payload) - allowed or payload.get('protocol') != PROTOCOL or type(payload.get('protocol')) is not int:
            raise ControllerError(400, 'Report only the supported client capability fields.')
        self.recover_device(device_id)
        if payload.get('platform') not in {'windows', 'android', 'router'}:
            raise ControllerError(400, 'Choose a supported device platform.')
        if type(payload.get('startupLease')) is not bool or type(payload.get('runtimeLease')) is not bool:
            raise ControllerError(400, 'Report startup and runtime lease support explicitly.')
        protocols, transports = payload.get('protocols'), payload.get('transports')
        if (not isinstance(protocols, list) or not protocols or len(protocols) > len(NODE_TYPES)
                or any(not isinstance(p, str) or p not in NODE_TYPES for p in protocols)
                or not isinstance(transports, list) or not transports or len(transports) > len(TRANSPORTS)
                or any(not isinstance(p, str) or p not in TRANSPORTS for p in transports)):
            raise ControllerError(400, 'Report a bounded list of supported node protocols and transports.')
        version = payload.get('clientVersion', '')
        if not isinstance(version, str) or not re.fullmatch(r'[A-Za-z0-9._+\-]{0,48}', version):
            raise ControllerError(400, 'The reported client version is invalid.')
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            device, context, state = self._policy(c, device_id)
            self._state(c, device_id, True)
            values = (PROTOCOL, payload['platform'], version, int(payload['startupLease']), int(payload['runtimeLease']),
                      canonical(sorted(set(protocols))), canonical(sorted(set(transports))))
            previous = (state['protocol'], state['platform'], state['client_version'], state['startup_lease'], state['runtime_lease'], state['protocols_json'], state['transports_json'])
            if values != previous and state['mode'] != 'legacy':
                self._bump(c, device_id)
            c.execute('UPDATE controller_devices SET protocol=?,platform=?,client_version=?,startup_lease=?,runtime_lease=?,protocols_json=?,transports_json=?,registered_at=?,updated=? WHERE device_id=?', (*values, time.time(), time.time(), device_id))
            result = self._controller(self._state(c, device_id))
        return {'ok': True, 'controller': result}

    def _policy(self, c, device_id, check_access=True):
        device = self._device(c, device_id)
        state = self._state(c, device_id)
        if device['revoked']:
            self._deny(state, 401, 'Device authentication has been revoked.', 'device_revoked')
        context = self.customers.context(c, device['user_id'])
        if check_access:
            try:
                self.customers.check_access(context)
            except CustomerError:
                self._deny(state, 403, 'Account policy currently denies node access.', 'account_denied')
            active = [r[0] for r in c.execute('SELECT id FROM customer_devices WHERE user_id=? AND revoked=0 ORDER BY created,id', (device['user_id'],))]
            if device_id not in active[:context[3]['deviceLimit']]:
                self._deny(state, 403, 'This device exceeds the account device limit.', 'account_denied')
        return device, context, state

    def _view(self, c, device, context, state):
        if state['mode'] == 'legacy':
            try:
                return self.customers.envelope(context)
            except CustomerError:
                self._deny(state, 403, 'No managed configuration is available for this device.', 'no_grants')
        if state['mode'] != 'enabled':
            self._deny(state, 403, 'Node authorization has been revoked for this device.', 'authorization_revoked')
        if not self._capable(state):
            self._deny(state, 426, 'Upgrade the client to enforce startup and runtime authorization leases.', 'client_upgrade_required')
        roots = self._roots(c, device['id'])
        if not roots:
            self._deny(state, 403, 'No nodes are authorized for this device.', 'no_grants')
        try:
            self._compatible(c, device, state, roots)
            node_ids = self._closure(c, roots)
        except ControllerError as error:
            self._deny(state, error.status, error.message, error.code)
        rows = [self._row(c, node_id) for node_id in node_ids]
        if sum(len(row['outbound_json'].encode('utf-8')) for row in rows) > 512 * 1024:
            self._deny(state, 413, 'The authorized node configuration is too large.', 'configuration_too_large')
        tags = {row['id']: row['name'] + ' [' + row['id'][:8] + ']' for row in rows}
        outbounds, metadata = [], []
        for row in rows:
            dependencies = [r[0] for r in c.execute('SELECT dependency_id FROM controller_dependencies WHERE node_id=? ORDER BY dependency_id', (row['id'],))]
            outbound = json.loads(row['outbound_json'])
            outbound['tag'] = tags[row['id']]
            if dependencies:
                outbound['detour'] = tags[dependencies[0]]
            outbounds.append(outbound)
            metadata.append({'id': row['id'], 'name': row['name'], 'protocol': row['protocol'],
                             'revision': row['revision'], 'tag': outbound['tag'], 'dependencyIds': dependencies})
        selected = [tags[node_id] for node_id in roots]
        final = selected[0]
        if len(selected) > 1:
            final = 'controller-proxy'
            outbounds.insert(0, {'type': 'selector', 'tag': final, 'outbounds': selected, 'default': selected[0]})
        config = {'outbounds': outbounds, 'route': {'final': final}}
        if context[1]['block_claude']:
            config = refuse_claude(config)
        value = {'format': 1, 'configName': 'KOne', 'config': config,
                 'policy': {k: v for k, v in context[3].items() if k != 'quotaMeter'},
                 'configKind': 'sing-box-source', 'groupName': context[2]['name'] if context[2] else None,
                 'controller': {**self._controller(state), 'rootNodeIds': roots, 'nodeIds': node_ids, 'nodes': metadata}}
        value['configRevision'] = digest(canonical(config))
        value['revision'] = digest(canonical(value))
        return value

    def configuration(self, c, device, context, protocol):
        if protocol == PROTOCOL:
            self._mark_recovery(c, device['id'])
        state = self._state(c, device['id'])
        if state['mode'] == 'legacy':
            value = self.customers.envelope(context)
            if protocol == PROTOCOL:
                value['controller'] = {**self._controller(state), 'nodeIds': [], 'rootNodeIds': [], 'nodes': []}
            return value
        if protocol != PROTOCOL:
            self._deny(state, 426, 'Upgrade the client to enforce startup and runtime authorization leases.', 'client_upgrade_required')
        self._policy(c, device['id'])
        return self._view(c, device, context, state)

    def _protocol(self, payload):
        if type(payload.get('protocol')) is not int or payload['protocol'] != PROTOCOL:
            raise ControllerError(426, 'Upgrade the client to enforce startup and runtime authorization leases.', 'client_upgrade_required')

    def lease(self, device_id, payload):
        self._protocol(payload)
        self.recover_device(device_id)
        if set(payload) - {'protocol', 'nonce', 'configRevision', 'authGeneration'}:
            raise ControllerError(400, 'The lease request contains unsupported fields.')
        nonce = payload.get('nonce')
        if not isinstance(nonce, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', nonce):
            raise ControllerError(400, 'Use a fresh random nonce for each authorization request.', 'invalid_nonce')
        revision = payload.get('configRevision')
        if not isinstance(revision, str) or not re.fullmatch(r'[0-9a-f]{64}', revision):
            raise ControllerError(400, 'The requested configuration revision is invalid.')
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            device, context, state = self._policy(c, device_id)
            if type(payload.get('authGeneration')) is not int or payload['authGeneration'] != state['auth_generation']:
                self._deny(state, 409, 'Authorization changed. Refresh the device configuration.', 'stale_generation')
            view = self._view(c, device, context, state)
            if revision != view['configRevision']:
                self._deny(state, 409, 'The configuration changed. Fetch the current revision before connecting.', 'stale_config')
            now = time.time()
            nonce_hash = hashlib.sha256(nonce.encode('ascii')).hexdigest()
            c.execute('DELETE FROM controller_lease_nonces WHERE device_id=? AND created<?', (device_id, now - 300))
            if c.execute('SELECT 1 FROM controller_lease_nonces WHERE device_id=? AND nonce_hash=?', (device_id, nonce_hash)).fetchone():
                self._deny(state, 409, 'Use a new nonce for a new authorization request.', 'nonce_reused')
            self._state(c, device_id, True)
            c.execute('INSERT INTO controller_lease_nonces VALUES(?,?,?)', (device_id, nonce_hash, now))
            c.execute('DELETE FROM controller_lease_nonces WHERE device_id=? AND rowid NOT IN (SELECT rowid FROM controller_lease_nonces WHERE device_id=? ORDER BY created DESC,rowid DESC LIMIT 64)', (device_id, device_id))
            c.execute('UPDATE controller_devices SET lease_generation=?,lease_config_revision=?,lease_not_after=? WHERE device_id=?', (state['auth_generation'], revision, now + TTL_SECONDS, device_id))
            c.execute('UPDATE customer_devices SET last_seen=? WHERE id=?', (now, device_id))
            controller = view.get('controller', {})
            result = {'format': PROTOCOL, 'nonce': nonce, 'deviceId': device_id,
                      'mode': state['mode'], 'required': state['mode'] != 'legacy',
                      'serverTime': int(now), 'notAfter': int(now) + TTL_SECONDS, 'ttlSeconds': TTL_SECONDS,
                      'authGeneration': state['auth_generation'], 'configRevision': revision,
                      'nodeIds': controller.get('nodeIds', []), 'rootNodeIds': controller.get('rootNodeIds', [])}
        return result

    def ack(self, device_id, payload, legacy=False):
        if not legacy:
            self._protocol(payload)
            self.recover_device(device_id)
        status = payload.get('status')
        if legacy:
            status = {'received': 'received', 'applied': 'installed', 'failed': 'failed'}.get(status)
        if status not in ACK_STATUSES:
            raise ControllerError(400, 'Choose a valid node authorization acknowledgment status.')
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            device, context, state = self._policy(c, device_id, check_access=status != 'stopped')
            generation = state['auth_generation'] if legacy else payload.get('authGeneration')
            if type(generation) is not int or generation != state['auth_generation']:
                self._deny(state, 409, 'Authorization changed. Report only the current generation.', 'stale_generation')
            view = None
            if status != 'stopped':
                view = self._view(c, device, context, state)
                if (payload.get('revision') != view['revision'] or device['issued_revision'] != view['revision']
                        or not legacy and payload.get('configRevision') != view['configRevision']):
                    self._deny(state, 409, 'The acknowledged configuration is no longer current.', 'stale_config')
            if status == 'running' and (state['lease_generation'] != generation
                    or state['lease_config_revision'] != view['configRevision']
                    or not state['lease_not_after'] or state['lease_not_after'] <= time.time()):
                self._deny(state, 409, 'A fresh online lease is required before reporting a running configuration.', 'lease_required')
            selected = payload.get('selectedNodeId')
            if selected is not None:
                identifier(selected)
                if view is None or selected not in view.get('controller', {}).get('rootNodeIds', []):
                    raise ControllerError(400, 'The selected node is not an authorized root node.')
            self._state(c, device_id, True)
            # Late receipt/installation reports cannot replace an observed running or stopped state.
            if state['ack_generation'] == generation and state['ack_status'] in {'running', 'stopped'} and status in {'received', 'installed'}:
                return {'ok': True}
            c.execute('UPDATE controller_devices SET ack_generation=?,ack_status=?,ack_revision=?,ack_config_revision=?,ack_at=?,selected_node_id=? WHERE device_id=?',
                      (generation, status, view['revision'] if view else None, view['configRevision'] if view else None, time.time(), selected, device_id))
            c.execute('UPDATE customer_devices SET last_seen=? WHERE id=?', (time.time(), device_id))
        return {'ok': True}

    def publish(self, device_id, payload):
        self._protocol(payload)
        self.recover_device(device_id)
        if set(payload) - {'protocol', 'requestId', 'configText', 'selectedTags', 'name'}:
            raise ControllerError(400, 'The publication request contains unsupported fields.')
        request_id = identifier(payload.get('requestId'))
        with self.store.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            device, _, state = self._policy(c, device_id)
            if not state['owner_publish'] or state['mode'] == 'revoked':
                self._deny(state, 403, 'Owner publishing has not been authorized for this device.', 'publishing_forbidden')
            selected = payload.get('selectedTags')
            if not isinstance(selected, list) or not selected or len(selected) > 200 or any(not isinstance(tag, str) or not tag for tag in selected):
                raise ControllerError(400, 'Explicitly select the local nodes to publish.')
            nodes, _ = self._parse(payload)
            by_tag = {node['tag']: node for node in nodes}
            if any(tag not in by_tag for tag in selected):
                raise ControllerError(400, 'A selected local node is missing from the publication.')
            closure = set()
            def include(tag):
                if tag in closure:
                    return
                closure.add(tag)
                if by_tag[tag].get('detour'):
                    include(by_tag[tag]['detour'])
            for tag in selected:
                include(tag)
            if set(by_tag) != closure:
                raise ControllerError(400, 'Publish only the selected local nodes and their required dependencies.')
            request_hash = digest(canonical({'configText': payload['configText'], 'selectedTags': selected, 'name': payload.get('name')}))
            old = c.execute('SELECT * FROM controller_publish_requests WHERE device_id=? AND request_id=?', (device_id, request_id)).fetchone()
            if old:
                if old['request_hash'] != request_hash:
                    raise ControllerError(409, 'This publication identifier was used for different nodes.', 'request_conflict')
                return json.loads(old['result_json'])
            result = self._import(c, payload, 'user:' + device['user_id'], device_id)
            result['automaticallyGranted'] = False
            c.execute('INSERT INTO controller_publish_requests VALUES(?,?,?,?,?)', (device_id, request_id, request_hash, canonical(result), time.time()))
            c.execute('DELETE FROM controller_publish_requests WHERE device_id=? AND rowid NOT IN (SELECT rowid FROM controller_publish_requests WHERE device_id=? ORDER BY created DESC,rowid DESC LIMIT 100)', (device_id, device_id))
        self.store.restrict_files()
        return result
