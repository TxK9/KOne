"""Read PassWall UCI node exports without running UCI or contacting nodes."""
import ipaddress
import re
import shlex
import uuid

from config_import import ConfigImportError


def fail(message):
    raise ConfigImportError(message)


def _value(value, maximum=1024):
    if not isinstance(value, str) or len(value) > maximum or any(ord(c) < 32 or ord(c) == 127 for c in value):
        fail('A PassWall node has an invalid text field.')
    return value


def _flag(options, key, default=False):
    value = options.get(key, '1' if default else '0')
    if value not in ('0', '1'):
        fail('A PassWall node has an invalid boolean option.')
    return value == '1'


def _host(value):
    value = _value(value, 253)
    if not value:
        fail('A PassWall node has no server address.')
    try:
        ipaddress.ip_address(value)
    except ValueError:
        try:
            value = value.encode('idna').decode('ascii')
        except UnicodeError:
            fail('A PassWall node has an invalid server address.')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', value):
            fail('A PassWall node has an invalid server address.')
    return value


def _sections(source):
    if not isinstance(source, str) or len(source.encode('utf-8')) > 512 * 1024:
        fail('The PassWall export is too large.')
    result, current, pending, directives = [], None, '', 0
    for line in source.splitlines():
        pending += line + '\n'
        if len(pending) > 8192:
            fail('A PassWall setting is too long.')
        try:
            parts = shlex.split(pending, comments=True, posix=True)
        except ValueError:
            continue
        pending = ''
        if not parts:
            continue
        directives += 1
        if directives > 6000:
            fail('The PassWall export contains too many settings.')
        if parts[0] == 'config' and len(parts) in (2, 3):
            current = {'kind': parts[1], 'id': parts[2] if len(parts) == 3 else 'node-' + str(len(result)), 'options': {}}
            result.append(current)
        elif current is not None and parts[0] in ('option', 'list') and len(parts) == 3:
            key, value = parts[1:]
            if parts[0] == 'list':
                previous = current['options'].setdefault(key, [])
                if not isinstance(previous, list):
                    fail('The PassWall export contains conflicting options.')
                previous.append(value)
            elif key in current['options']:
                fail('The PassWall export contains duplicate options.')
            else:
                current['options'][key] = value
        else:
            fail('The PassWall export contains an invalid UCI directive.')
    if pending.strip():
        fail('The PassWall export contains an unfinished quoted value.')
    return result


_COMMON = {
    'type', 'protocol', 'remarks', 'group', 'add_mode', 'address', 'port',
    'transport', 'tls', 'tls_serverName', 'tls_allowInsecure', 'fingerprint', 'utls',
    'chain_proxy', 'preproxy_node', 'tcp_guise', 'tcpMptcp', 'tcp_fast_open',
    'happy_eyeballs', 'timeout',
}
_FIELDS = {
    'vless': {'uuid', 'flow', 'encryption', 'reality', 'reality_publicKey', 'reality_shortId', 'reality_spiderX'},
    'socks': {'username', 'password'},
    'hysteria2': {'hysteria2_auth_password', 'hysteria2_obfs_type', 'hysteria2_obfs_password', 'hysteria2_hop_interval'},
}


def parse_passwall(source):
    sections = _sections(source)
    candidates = [row for row in sections if row['kind'] == 'nodes']
    if len(candidates) > 256:
        fail('The PassWall export contains too many nodes.')
    nodes, identifiers, skipped, insecure = [], {}, 0, 0
    for row in candidates:
        options = row['options']
        protocol = options.get('protocol', '')
        if not protocol or protocol.startswith('_'):
            skipped += 1
            continue
        if protocol not in _FIELDS:
            fail('A PassWall protocol is not supported. Export that node as sing-box JSON.')
        if set(options) - (_COMMON | _FIELDS[protocol]):
            fail('A PassWall node uses unsupported connection settings. Export complete sing-box JSON instead.')
        if options.get('type', 'Xray') not in ('Xray', 'Sing-Box', 'sing-box'):
            fail('This PassWall node implementation is not supported.')
        if row['id'] in identifiers:
            fail('The PassWall export contains duplicate node identifiers.')
        server_port = options.get('port', '')
        if not isinstance(server_port, str) or not server_port.isdecimal() or not 1 <= int(server_port) <= 65535:
            fail('A PassWall node has an invalid port. Export port-hopping nodes as sing-box JSON.')
        tag = _value(options.get('remarks') or row['id'], 100)
        if not tag:
            fail('A PassWall node has no name.')
        if tag in identifiers.values():
            tag = tag[:78] + ' [' + str(len(nodes) + 1) + ']'
        identifiers[row['id']] = tag
        node = {'type': protocol, 'tag': tag, 'server': _host(options.get('address', '')), 'server_port': int(server_port)}
        if options.get('transport', 'raw') not in ('raw', 'tcp') or options.get('tcp_guise', 'none') not in ('none', ''):
            fail('This PassWall transport is not supported. Export complete sing-box JSON instead.')
        if _flag(options, 'tcpMptcp'):
            fail('PassWall multipath TCP cannot be imported losslessly.')
        if _flag(options, 'tcp_fast_open'):
            node['tcp_fast_open'] = True
        if protocol == 'vless':
            try:
                node['uuid'] = str(uuid.UUID(options.get('uuid', '')))
            except (ValueError, TypeError, AttributeError):
                fail('A PassWall VLESS node has an invalid UUID.')
            if options.get('encryption', 'none') != 'none':
                fail('This PassWall VLESS encryption setting is not supported.')
            if options.get('flow'):
                if options['flow'] != 'xtls-rprx-vision':
                    fail('This PassWall VLESS flow is not supported.')
                node['flow'] = options['flow']
        elif protocol == 'socks':
            if _flag(options, 'tls'):
                fail('TLS-wrapped SOCKS must be exported as complete sing-box JSON.')
            node['version'] = '5'
            for key in ('username', 'password'):
                if options.get(key):
                    node[key] = _value(options[key])
        else:
            password = _value(options.get('hysteria2_auth_password', ''))
            if not password:
                fail('A PassWall Hysteria2 node has no authentication value.')
            node['password'] = password
            obfs = options.get('hysteria2_obfs_type', '')
            if obfs not in ('', 'none', 'salamander'):
                fail('This PassWall Hysteria2 obfuscation is not supported.')
            if obfs == 'salamander':
                secret = _value(options.get('hysteria2_obfs_password', ''))
                if not secret:
                    fail('A PassWall Hysteria2 node has incomplete obfuscation settings.')
                node['obfs'] = {'type': obfs, 'password': secret}
        if protocol == 'hysteria2' or _flag(options, 'tls'):
            tls = {'enabled': True}
            if options.get('tls_serverName'):
                tls['server_name'] = _host(options['tls_serverName'])
            if _flag(options, 'tls_allowInsecure'):
                tls['insecure'] = True
                insecure += 1
            if options.get('fingerprint'):
                tls['utls'] = {'enabled': True, 'fingerprint': _value(options['fingerprint'], 40)}
            if protocol == 'vless' and _flag(options, 'reality'):
                public_key = _value(options.get('reality_publicKey', ''), 128)
                short_id = _value(options.get('reality_shortId', ''), 16)
                if not public_key or not re.fullmatch(r'[0-9a-fA-F]{0,16}', short_id) or len(short_id) % 2:
                    fail('A PassWall Reality node has incomplete security settings.')
                tls['reality'] = {'enabled': True, 'public_key': public_key, 'short_id': short_id}
            node['tls'] = tls
        elif protocol == 'vless' and _flag(options, 'reality'):
            fail('A PassWall Reality node requires TLS.')
        nodes.append((row, node))
    if not nodes:
        fail('The PassWall export contains no supported proxy nodes.')
    mapping = {node['tag']: node for _, node in nodes}
    for row, node in nodes:
        options = row['options']
        chain = options.get('chain_proxy', '0')
        if chain not in ('0', '1', ''):
            fail('This PassWall proxy chain mode is not supported.')
        if chain == '1':
            dependency = identifiers.get(options.get('preproxy_node', ''))
            if not dependency:
                fail('A PassWall proxy chain references a missing node. Export all chain nodes together.')
            node['detour'] = dependency
        visited, cursor = set(), node
        while cursor.get('detour'):
            if cursor['tag'] in visited:
                fail('The PassWall export contains a proxy-chain cycle.')
            visited.add(cursor['tag'])
            cursor = mapping[cursor['detour']]
    final = nodes[0][1]['tag']
    by_id = {row['id']: row for row in candidates}
    globals_ = [row for row in sections if row['kind'] == 'global']
    selected = globals_[0]['options'].get('node') if globals_ else None
    visited = set()
    while selected in by_id and selected not in identifiers:
        if selected in visited:
            fail('The PassWall export contains a rule-group cycle.')
        visited.add(selected)
        selected = by_id[selected]['options'].get('default_node')
    if selected in identifiers:
        final = identifiers[selected]
    warnings = ['Only proxy nodes were imported; router listeners, DNS and routing rules were not copied.']
    if skipped:
        warnings.append('Router rule groups and empty node entries were skipped.')
    if insecure:
        warnings.append('Some nodes already disable TLS certificate verification; those existing settings were preserved.')
    return {'outbounds': [node for _, node in nodes], 'route': {'final': final}}, warnings, 'PassWall UCI'
