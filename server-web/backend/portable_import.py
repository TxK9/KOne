"""Offline node interchange. Never fetch a subscription or contact a proxy."""
import base64
import binascii
import copy
import ipaddress
import json
import math
import re
import uuid
from urllib.parse import parse_qsl, unquote, urlsplit

from config_import import ConfigImportError, parse_node_links, decoded
from vendor import yaml
from passwall_import import parse_passwall

MAX_TEXT_BYTES = 512 * 1024
NODE_TYPES = {'vless', 'vmess', 'trojan', 'shadowsocks', 'hysteria2', 'tuic', 'anytls', 'socks', 'http'}
ROUTER_NOTE = 'Only proxy nodes were imported; router listeners, DNS and routing rules were not copied.'
DEPENDENCY_ERROR = 'A proxy chain references a missing node. Export every node in the chain together.'


def fail(message):
    raise ConfigImportError(message)


def text(value, maximum=1024):
    if not isinstance(value, str) or not value or len(value) > maximum or any(ord(c) < 32 or ord(c) == 127 for c in value):
        fail('A node contains an invalid text field.')
    return value


def host(value):
    value = text(value, 253)
    try:
        ipaddress.ip_address(value)
        return value
    except ValueError:
        try:
            value = value.encode('idna').decode('ascii')
        except UnicodeError:
            fail('A node has an invalid server address.')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', value):
            fail('A node has an invalid server address.')
        return value


def port(value):
    if isinstance(value, str) and value.isdecimal():
        value = int(value)
    if type(value) is not int or not 1 <= value <= 65535:
        fail('A node has an invalid port.')
    return value


def flag(value):
    if type(value) is not bool:
        fail('A node has an invalid boolean option.')
    return value


def identity(value):
    try:
        return str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError):
        fail('A node has an invalid UUID.')


def unknown(value, allowed):
    if not isinstance(value, dict):
        fail('A node has invalid connection options.')
    if set(value) - set(allowed):
        fail('A node uses unsupported connection options. Export complete sing-box JSON instead.')


def strict_yaml(value):
    class Loader(yaml.SafeLoader):
        depth = 0
        total = 0

        def compose_node(self, parent, index):
            if self.check_event(yaml.AliasEvent):
                fail('YAML aliases are not supported. Export nodes without anchors or aliases.')
            self.depth += 1
            self.total += 1
            if self.depth > 32 or self.total > 12000:
                fail('The YAML document is too complex.')
            try:
                return super().compose_node(parent, index)
            finally:
                self.depth -= 1

        def construct_mapping(self, node, deep=False):
            result = {}
            for key_node, value_node in node.value:
                key = self.construct_object(key_node, deep=deep)
                if not isinstance(key, str) or key in result:
                    fail('The configuration contains duplicate or invalid keys.')
                result[key] = self.construct_object(value_node, deep=deep)
            return result
    try:
        result = yaml.load(value, Loader=Loader)
        json.dumps(result, allow_nan=False)
        return result
    except ConfigImportError:
        raise
    except (yaml.YAMLError, ValueError, TypeError, RecursionError):
        fail('The YAML document is invalid or uses unsupported tags.')


def decode64(value):
    try:
        value = value.strip()
        return base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True).decode('utf-8')
    except (ValueError, binascii.Error, UnicodeError):
        fail('The encoded node data is invalid.')


def assemble(nodes):
    if not nodes or len(nodes) > 200:
        fail('Import between 1 and 200 proxy nodes.')
    tags = set()
    for index, node in enumerate(nodes, 1):
        tag = node.get('tag') or 'node-' + str(index)
        node['tag'] = text(tag, 80)
        if tag in tags or tag in {'proxy', 'direct'}:
            fail('Node names must be unique and cannot be proxy or direct.')
        tags.add(tag)
    for node in nodes:
        if 'detour' in node and node['detour'] not in tags:
            fail(DEPENDENCY_ERROR)
        seen, current = set(), node
        while current.get('detour'):
            if current['tag'] in seen:
                fail('A proxy chain contains a cycle.')
            seen.add(current['tag'])
            current = next(n for n in nodes if n['tag'] == current['detour'])
    final = nodes[0]['tag']
    outbounds = list(nodes)
    if len(nodes) > 1:
        final = 'proxy'
        outbounds.insert(0, {'type': 'selector', 'tag': final, 'outbounds': [n['tag'] for n in nodes], 'default': nodes[0]['tag']})
    outbounds.append({'type': 'direct', 'tag': 'direct'})
    return {'outbounds': outbounds, 'route': {'final': final}}


def tls_options(node, enabled=False):
    if 'tls' in node:
        flag(node['tls'])
    enabled = bool(node.get('tls', enabled) or node.get('reality-opts'))
    if not enabled:
        if any(k in node for k in ('sni', 'servername', 'alpn', 'client-fingerprint', 'skip-cert-verify')):
            fail('TLS options require TLS to be enabled.')
        return None
    result = {'enabled': True}
    sni = node.get('sni') or node.get('servername')
    if sni:
        result['server_name'] = host(sni)
    if 'skip-cert-verify' in node:
        result['insecure'] = flag(node['skip-cert-verify'])
    if 'alpn' in node:
        if not isinstance(node['alpn'], list) or not node['alpn']:
            fail('A node has invalid ALPN values.')
        result['alpn'] = [text(v, 128) for v in node['alpn']]
    if node.get('client-fingerprint'):
        fingerprint = node['client-fingerprint']
        if fingerprint not in {'chrome', 'firefox', 'edge', 'safari', '360', 'qq', 'ios', 'android', 'random', 'randomized'}:
            fail('The TLS fingerprint is not supported.')
        result['utls'] = {'enabled': True, 'fingerprint': fingerprint}
    if node.get('reality-opts'):
        options = node['reality-opts']
        if not isinstance(options, dict):
            fail('The REALITY options are invalid.')
        unknown(options, {'public-key', 'short-id'})
        key, short_id = options.get('public-key', ''), options.get('short-id', '')
        try:
            valid = re.fullmatch(r'[A-Za-z0-9_-]{43}=?', key) and len(base64.b64decode(key.rstrip('=') + '=', altchars=b'-_', validate=True)) == 32
        except (ValueError, binascii.Error):
            valid = False
        if not valid or not isinstance(short_id, str) or not re.fullmatch(r'(?:[0-9a-fA-F]{2}){0,8}', short_id):
            fail('The REALITY public key or short ID is invalid.')
        result['reality'] = {'enabled': True, 'public_key': key.rstrip('='), 'short_id': short_id.lower()}
        result.setdefault('utls', {'enabled': True, 'fingerprint': 'chrome'})
    return result


def clash_node(value):
    if not isinstance(value, dict):
        fail('Every proxy node must be an object.')
    common = {'name', 'type', 'server', 'port', 'udp', 'tfo', 'tls', 'sni', 'servername', 'skip-cert-verify', 'alpn', 'client-fingerprint', 'network', 'ws-opts', 'grpc-opts', 'reality-opts', 'dialer-proxy'}
    fields = {'vless': {'uuid', 'flow', 'packet-encoding'}, 'vmess': {'uuid', 'alterId', 'cipher'}, 'trojan': {'password'}, 'ss': {'cipher', 'password'}, 'hysteria2': {'password', 'obfs', 'obfs-password'}}
    kind = value.get('type')
    if kind not in fields:
        fail('This proxy protocol is not supported by the converter. Export sing-box JSON instead.')
    unknown(value, common | fields[kind])
    node = {'type': 'shadowsocks' if kind == 'ss' else kind, 'tag': text(value.get('name'), 80), 'server': host(value.get('server')), 'server_port': port(value.get('port'))}
    if 'udp' in value:
        if not flag(value['udp']):
            node['network'] = 'tcp'
    if 'tfo' in value:
        node['tcp_fast_open'] = flag(value['tfo'])
    if value.get('dialer-proxy'):
        node['detour'] = text(value['dialer-proxy'], 80)
    if kind in {'vless', 'vmess'}:
        node['uuid'] = identity(value.get('uuid'))
    if kind == 'vless':
        if value.get('flow'):
            if value['flow'] != 'xtls-rprx-vision':
                fail('The VLESS flow is not supported.')
            node['flow'] = value['flow']
        if value.get('packet-encoding'):
            if value['packet-encoding'] not in {'xudp', 'packetaddr'}:
                fail('The VLESS packet encoding is not supported.')
            node['packet_encoding'] = value['packet-encoding']
    if kind == 'vmess':
        node['security'] = value.get('cipher', 'auto')
        if node['security'] not in {'auto', 'none', 'zero', 'aes-128-gcm', 'chacha20-poly1305'}:
            fail('The VMess cipher is not supported.')
        aid = value.get('alterId', 0)
        if type(aid) is not int or not 0 <= aid <= 65535:
            fail('The VMess alter ID is invalid.')
        if aid:
            node['alter_id'] = aid
    if kind in {'ss', 'trojan', 'hysteria2'}:
        node['password'] = text(value.get('password'))
    if kind == 'ss':
        node['method'] = text(value.get('cipher'), 80)
        if node['method'] not in {'aes-128-gcm', 'aes-256-gcm', 'chacha20-ietf-poly1305', 'xchacha20-ietf-poly1305', '2022-blake3-aes-128-gcm', '2022-blake3-aes-256-gcm', '2022-blake3-chacha20-poly1305', 'none'}:
            fail('The Shadowsocks cipher is not supported.')
        if any(k in value for k in ('tls', 'sni', 'servername', 'network', 'ws-opts', 'grpc-opts', 'reality-opts')):
            fail('Shadowsocks transport plugins require complete sing-box JSON.')
    else:
        tls = tls_options(value, enabled=kind in {'trojan', 'hysteria2'})
        if tls:
            node['tls'] = tls
    if kind == 'hysteria2':
        if value.get('network', 'tcp') != 'tcp' or value.get('reality-opts') or value.get('ws-opts') or value.get('grpc-opts'):
            fail('This proxy transport is not supported. Export complete sing-box JSON instead.')
        if value.get('obfs'):
            if value['obfs'] != 'salamander' or not value.get('obfs-password'):
                fail('The Hysteria 2 obfuscation options are not supported.')
            node['obfs'] = {'type': 'salamander', 'password': text(value['obfs-password'])}
        elif value.get('obfs-password'):
            fail('The Hysteria 2 obfuscation options are incomplete.')
    network = value.get('network', 'tcp')
    if network not in {'tcp', 'ws', 'grpc'}:
        fail('This proxy transport is not supported. Export complete sing-box JSON instead.')
    if network == 'ws':
        opts = value.get('ws-opts', {})
        if not isinstance(opts, dict):
            fail('The WebSocket options are invalid.')
        unknown(opts, {'path', 'headers', 'max-early-data', 'early-data-header-name'})
        transport = {'type': 'ws', 'path': opts.get('path', '/')}
        if not isinstance(transport['path'], str):
            fail('The WebSocket options are invalid.')
        if opts.get('headers'):
            if not isinstance(opts['headers'], dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in opts['headers'].items()):
                fail('The WebSocket options are invalid.')
            transport['headers'] = dict(opts['headers'])
        if 'max-early-data' in opts:
            if type(opts['max-early-data']) is not int or not 0 <= opts['max-early-data'] <= 65535:
                fail('The WebSocket options are invalid.')
            transport['max_early_data'] = opts['max-early-data']
        if opts.get('early-data-header-name'):
            transport['early_data_header_name'] = text(opts['early-data-header-name'], 128)
        node['transport'] = transport
    elif network == 'grpc':
        opts = value.get('grpc-opts', {})
        if not isinstance(opts, dict):
            fail('The gRPC options are invalid.')
        unknown(opts, {'grpc-service-name'})
        node['transport'] = {'type': 'grpc', 'service_name': opts.get('grpc-service-name', '')}
    if network != 'ws' and value.get('ws-opts') or network != 'grpc' and value.get('grpc-opts'):
        fail('Transport options do not match the selected transport.')
    if node.get('flow') and (network != 'tcp' or not node.get('tls')):
        fail('The VLESS flow is incompatible with this transport or TLS mode.')
    return node


def additional_link(line, index, json_parser):
    parsed = urlsplit(line)
    kind = parsed.scheme.lower()
    if kind == 'vmess':
        try:
            source = json_parser(decode64(line[8:]))
        except (ValueError, TypeError):
            fail('The VMess share link is invalid.')
        if not isinstance(source, dict):
            fail('The VMess share link is invalid.')
        unknown(source, {'v', 'ps', 'add', 'port', 'id', 'aid', 'scy', 'net', 'type', 'host', 'path', 'tls', 'sni', 'alpn', 'fp'})
        if source.get('type', 'none') not in {'none', ''}:
            fail('VMess header obfuscation requires complete sing-box JSON.')
        value = {'type': 'vmess', 'name': source.get('ps') or 'node-' + str(index), 'server': source.get('add'), 'port': source.get('port'), 'uuid': source.get('id'), 'alterId': int(source.get('aid', 0)), 'cipher': source.get('scy', 'auto'), 'network': source.get('net', 'tcp'), 'tls': source.get('tls') in {'tls', True}}
        if source.get('sni'):
            value['sni'] = source['sni']
        if value['network'] == 'ws':
            value['ws-opts'] = {'path': source.get('path') or '/', 'headers': {'Host': source['host']} if source.get('host') else {}}
        elif value['network'] == 'grpc':
            value['grpc-opts'] = {'grpc-service-name': source.get('path', '')}
        elif source.get('host') or source.get('path'):
            fail('VMess transport options do not match the selected transport.')
        if source.get('alpn'):
            value['alpn'] = source['alpn'].split(',')
        if source.get('fp'):
            value['client-fingerprint'] = source['fp']
        return clash_node(value)
    if kind == 'ss':
        authority = line[len('ss://'):].split('#', 1)[0]
        if '?' in authority:
            fail('Shadowsocks plugins require complete sing-box JSON.')
        if '@' not in authority:
            authority = decode64(authority)
        elif parsed.path not in {'', '/'}:
            fail('Shadowsocks plugins require complete sing-box JSON.')
        if '@' not in authority:
            fail('The Shadowsocks share link is invalid.')
        auth, address = authority.rsplit('@', 1)
        auth = decoded(auth) if ':' in auth else decode64(auth)
        if ':' not in auth:
            fail('The Shadowsocks share link is invalid.')
        method, password = auth.split(':', 1)
        try:
            target = urlsplit('ss://' + address)
            value = {'type': 'ss', 'name': decoded(parsed.fragment) or 'node-' + str(index), 'server': target.hostname, 'port': target.port, 'cipher': method, 'password': password}
        except ValueError:
            fail('The Shadowsocks share link is invalid.')
        return clash_node(value)
    if kind == 'trojan':
        try:
            if re.search(r'%(?![0-9a-fA-F]{2})', parsed.query):
                fail('The Trojan share link is invalid.')
            pairs = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, errors='strict', max_num_fields=50)
            params = dict(pairs)
            if len(params) != len(pairs):
                fail('The share link contains duplicate parameters.')
            unknown(params, {'security', 'type', 'sni', 'peer', 'allowInsecure', 'insecure', 'alpn', 'fp', 'path', 'host', 'serviceName'})
            if params.get('security', 'tls') != 'tls' or parsed.path not in {'', '/'}:
                fail('The Trojan share link uses unsupported options.')
            value = {'type': 'trojan', 'name': decoded(parsed.fragment) or 'node-' + str(index), 'server': parsed.hostname, 'port': parsed.port if parsed.port is not None else 443, 'password': decoded(parsed.netloc.rsplit('@', 1)[0]), 'network': params.get('type', 'tcp')}
            if '@' not in parsed.netloc:
                fail('The share link is missing credentials.')
            if params.get('sni') and params.get('peer') and params['sni'] != params['peer']:
                fail('The share link contains conflicting parameters.')
            if params.get('sni') or params.get('peer'):
                value['sni'] = params.get('sni') or params['peer']
            for key in ('insecure', 'allowInsecure'):
                if key in params:
                    if params[key] not in {'0', '1', 'true', 'false'}:
                        fail('A node has an invalid boolean option.')
                    option = params[key] in {'1', 'true'}
                    if 'skip-cert-verify' in value and value['skip-cert-verify'] != option:
                        fail('The share link contains conflicting parameters.')
                    value['skip-cert-verify'] = option
            if params.get('alpn'):
                value['alpn'] = params['alpn'].split(',')
            if params.get('fp'):
                value['client-fingerprint'] = params['fp']
            if value['network'] == 'ws':
                if params.get('serviceName'):
                    fail('Transport options do not match the selected transport.')
                value['ws-opts'] = {'path': params.get('path', '/'), 'headers': {'Host': params['host']} if params.get('host') else {}}
            elif value['network'] == 'grpc':
                if params.get('path') or params.get('host'):
                    fail('Transport options do not match the selected transport.')
                value['grpc-opts'] = {'grpc-service-name': params.get('serviceName', '')}
            elif any(params.get(k) for k in ('path', 'host', 'serviceName')):
                fail('Transport options do not match the selected transport.')
            return clash_node(value)
        except (ValueError, KeyError):
            fail('The Trojan share link is invalid.')
    fail('This share-link protocol is not supported. Export sing-box JSON instead.')


def xray_nodes(config):
    raw = config.get('outbounds')
    if not isinstance(raw, list) or not raw:
        fail('The Xray export has no outbound nodes.')
    nodes = []
    for index, outbound in enumerate(raw, 1):
        if not isinstance(outbound, dict):
            fail('Every proxy node must be an object.')
        kind = outbound.get('protocol')
        if kind in {'freedom', 'blackhole', 'dns'}:
            continue
        if kind in {'hysteria', 'socks'} and isinstance(outbound.get('streamSettings'), dict) and (outbound['streamSettings'].get('finalmask') or outbound['streamSettings'].get('hysteriaSettings') or outbound['streamSettings'].get('sockopt', {}).get('dialerProxy')):
            fail('This router export uses certificate pinning or transport masks without a safe portable equivalent. Export native node share links instead.')
        if kind not in {'vless', 'vmess', 'trojan', 'shadowsocks'}:
            fail('This Xray protocol is not supported. Export a node share link instead.')
        unknown(outbound, {'protocol', 'tag', 'settings', 'streamSettings', 'mux', 'sendThrough', 'proxySettings'})
        if outbound.get('sendThrough') or outbound.get('mux', {}).get('enabled'):
            fail('Router-bound dial options or multiplexing require complete sing-box JSON.')
        settings = outbound.get('settings', {})
        key = 'vnext' if kind in {'vless', 'vmess'} else 'servers'
        unknown(settings, {key})
        servers = settings.get(key)
        if not isinstance(servers, list) or len(servers) != 1 or not isinstance(servers[0], dict):
            fail('Export one server and one identity per Xray outbound.')
        server = servers[0]
        value = {'name': outbound.get('tag') or 'node-' + str(index), 'type': 'ss' if kind == 'shadowsocks' else kind, 'server': server.get('address'), 'port': server.get('port')}
        if kind in {'vless', 'vmess'}:
            unknown(server, {'address', 'port', 'users'})
            users = server.get('users')
            if not isinstance(users, list) or len(users) != 1 or not isinstance(users[0], dict):
                fail('Export one server and one identity per Xray outbound.')
            user = users[0]
            unknown(user, {'id', 'encryption', 'security', 'alterId', 'flow', 'level', 'email'})
            value['uuid'] = user.get('id')
            if kind == 'vless':
                if user.get('encryption', 'none') != 'none':
                    fail('The VLESS encryption mode is not supported.')
                if user.get('flow'):
                    value['flow'] = user['flow']
            else:
                value['cipher'] = user.get('security', 'auto')
                value['alterId'] = user.get('alterId', 0)
        else:
            unknown(server, {'address', 'port', 'password', 'method', 'level', 'email'})
            value['password'] = server.get('password')
            if kind == 'shadowsocks':
                value['cipher'] = server.get('method')
        stream = outbound.get('streamSettings', {})
        if not isinstance(stream, dict):
            fail('The Xray transport settings are invalid.')
        unknown(stream, {'network', 'security', 'tlsSettings', 'realitySettings', 'wsSettings', 'grpcSettings', 'tcpSettings', 'sockopt'})
        if stream.get('sockopt'):
            fail('Router-bound dial options or multiplexing require complete sing-box JSON.')
        network = stream.get('network', 'tcp')
        value['network'] = 'tcp' if network == 'raw' else network
        security = stream.get('security', 'none')
        if security not in {'none', 'tls', 'reality'}:
            fail('The Xray TLS mode is not supported.')
        value['tls'] = security != 'none'
        options = stream.get('realitySettings' if security == 'reality' else 'tlsSettings', {})
        unknown(options, {'serverName', 'allowInsecure', 'alpn', 'fingerprint', 'publicKey', 'shortId', 'spiderX', 'show'})
        if options.get('serverName'):
            value['sni'] = options['serverName']
        if 'allowInsecure' in options:
            value['skip-cert-verify'] = options['allowInsecure']
        if options.get('alpn'):
            value['alpn'] = options['alpn']
        if options.get('fingerprint'):
            value['client-fingerprint'] = options['fingerprint']
        if security == 'reality':
            value['reality-opts'] = {'public-key': options.get('publicKey'), 'short-id': options.get('shortId', '')}
        if network == 'ws':
            ws = stream.get('wsSettings', {})
            unknown(ws, {'path', 'headers', 'maxEarlyData', 'earlyDataHeaderName'})
            value['ws-opts'] = {'path': ws.get('path', '/')}
            for source, target in [('headers', 'headers'), ('maxEarlyData', 'max-early-data'), ('earlyDataHeaderName', 'early-data-header-name')]:
                if source in ws:
                    value['ws-opts'][target] = ws[source]
        elif network == 'grpc':
            grpc = stream.get('grpcSettings', {})
            unknown(grpc, {'serviceName', 'multiMode'})
            if grpc.get('multiMode'):
                fail('Xray gRPC multi-mode requires complete sing-box JSON.')
            value['grpc-opts'] = {'grpc-service-name': grpc.get('serviceName', '')}
        if stream.get('tcpSettings') not in (None, {}, {'header': {'type': 'none'}}):
            fail('Xray TCP header obfuscation requires complete sing-box JSON.')
        proxy = outbound.get('proxySettings')
        if proxy:
            unknown(proxy, {'tag', 'transportLayer'})
            if proxy.get('transportLayer') is False:
                fail('This Xray proxy-chain mode is not supported.')
            value['dialer-proxy'] = proxy.get('tag')
        nodes.append(clash_node(value))
    return assemble(nodes)


def _parse_import_text(value, json_parser):
    if not isinstance(value, str):
        fail('Configuration content must be text.')
    try:
        size = len(value.encode('utf-8'))
    except UnicodeError:
        fail('Configuration content contains invalid characters.')
    if not value.strip() or size > MAX_TEXT_BYTES:
        fail('Choose a non-empty configuration smaller than 512 KB.')
    value = value.strip().lstrip('\ufeff')
    if re.match(r'(?:#[^\n]*\n\s*)*config\s+', value):
        return parse_passwall(value)
    if re.match(r'[A-Za-z][A-Za-z0-9+.-]*://', value):
        if value.lower().startswith(('http://', 'https://')):
            fail('Download the subscription file in your browser, then import that file. The server does not fetch subscription URLs.')
        nodes, warnings = [], []
        for index, line in enumerate(value.splitlines(), 1):
            line = line.strip()
            if not line:
                continue
            if len(nodes) >= 200:
                fail('Import between 1 and 200 proxy nodes.')
            if line.lower().startswith(('vless://', 'hy2://', 'hysteria2://')):
                config, notes = parse_node_links(line)
                nodes.extend(n for n in config['outbounds'] if n.get('type') not in {'direct', 'selector'})
                warnings.extend(notes)
            else:
                try:
                    nodes.append(additional_link(line, index, json_parser))
                except (ValueError, TypeError, KeyError, OverflowError):
                    fail('A node share link is invalid or unsupported.')
        # Share-link duplicates receive stable unique names, matching the existing importer.
        used = {'proxy', 'direct'}
        for node in nodes:
            original, number = node['tag'], 1
            while node['tag'] in used:
                number += 1
                node['tag'] = original[:70] + '-' + str(number)
            used.add(node['tag'])
        return assemble(nodes), list(dict.fromkeys(warnings)), 'links'
    if re.fullmatch(r'[A-Za-z0-9_+/=\s-]+', value):
        try:
            expanded = decode64(re.sub(r'\s+', '', value))
        except ConfigImportError:
            expanded = ''
        if re.match(r'(?:vless|hy2|hysteria2|trojan|ss|vmess)://', expanded.strip(), re.I):
            config, warnings, _ = _parse_import_text(expanded, json_parser)
            return config, warnings, 'base64-links'
    try:
        config = json_parser(value)
        format_ = 'json'
    except (ValueError, RecursionError):
        if value.startswith(('{', '[')):
            fail('The JSON document is invalid or contains duplicate keys.')
        config = strict_yaml(value)
        format_ = 'yaml'
    if not isinstance(config, dict):
        fail('The configuration must contain an object.')
    if 'proxies' in config:
        proxies = config['proxies']
        if not isinstance(proxies, list):
            fail('The Clash export has no proxy list.')
        return assemble([clash_node(node) for node in proxies]), [ROUTER_NOTE], 'clash-' + format_
    outbounds = config.get('outbounds')
    if isinstance(outbounds, list) and any(isinstance(n, dict) and 'protocol' in n for n in outbounds):
        return xray_nodes(config), [ROUTER_NOTE], 'xray-json'
    if format_ == 'yaml':
        fail('YAML imports require a Clash proxies list.')
    return config, [], 'sing-box'



def parse_import_text(value, json_parser):
    try:
        return _parse_import_text(value, json_parser)
    except ConfigImportError:
        raise
    except (ValueError, TypeError, KeyError, AttributeError, IndexError, OverflowError, RecursionError):
        fail('A node contains invalid or unsupported connection options.')


def portable_config(config):
    """Export connection nodes, preserving complete chains and selector references."""
    source = config.get('outbounds', [])
    if not isinstance(source, list):
        fail('The configuration has no portable proxy nodes.')
    outbounds = copy.deepcopy(source)
    if not any(isinstance(n, dict) and n.get('type') in NODE_TYPES for n in outbounds):
        fail('The configuration has no portable proxy nodes.')
    allowed = NODE_TYPES | {'selector', 'urltest', 'direct'}
    tags = set()
    for node in outbounds:
        if not isinstance(node, dict) or node.get('type') not in allowed:
            fail('This configuration contains outbounds that cannot be exported as portable nodes.')
        tag = node.get('tag')
        if not isinstance(tag, str) or not tag or tag in tags:
            fail('Portable nodes require unique names.')
        tags.add(tag)
        if any(k in node for k in ('bind_interface', 'routing_mark', 'bind_addr', 'inet4_bind_address', 'inet6_bind_address', 'netns')):
            fail('Router-bound dial options require a platform-specific configuration.')
    references = {}
    for node in outbounds:
        deps = list(node.get('outbounds', [])) if node['type'] in {'selector', 'urltest'} else []
        if node.get('detour'):
            deps.append(node['detour'])
        if node.get('default'):
            deps.append(node['default'])
        if any(not isinstance(dep, str) or dep not in tags for dep in deps):
            fail(DEPENDENCY_ERROR)
        references[node['tag']] = deps
    done = set()
    def visit(tag, stack):
        if tag in done:
            return
        if tag in stack:
            fail('A proxy chain contains a cycle.')
        if len(stack) > 64:
            fail('The proxy chain is too deep.')
        for dependency in references[tag]:
            visit(dependency, stack | {tag})
        done.add(tag)
    for tag in tags:
        visit(tag, set())
    final = config.get('route', {}).get('final')
    if final not in tags:
        final = next(n['tag'] for n in outbounds if n['type'] in NODE_TYPES)
    selected = next(node for node in outbounds if node['tag'] == final)
    proxy_tags = [node['tag'] for node in outbounds if node['type'] in NODE_TYPES]
    if len(proxy_tags) > 1 and selected['type'] != 'selector':
        wrapper, suffix = 'proxy', 1
        while wrapper in tags:
            suffix += 1
            wrapper = 'proxy-' + str(suffix)
        choices = list(dict.fromkeys([final, *proxy_tags]))
        outbounds.insert(0, {'type': 'selector', 'tag': wrapper, 'outbounds': choices, 'default': final})
        final = wrapper
    return {'outbounds': outbounds, 'route': {'final': final}}


def merge_config_nodes(existing, incoming):
    incoming = portable_config(incoming)
    result = copy.deepcopy(existing)
    original = result.get('outbounds')
    if not isinstance(original, list):
        fail('The existing configuration has no outbound list.')
    by_tag = {node.get('tag'): node for node in original if isinstance(node, dict)}
    if None in by_tag or len(by_tag) != len(original):
        fail('The existing configuration needs unique node names before merging.')
    source = {node['tag']: node for node in incoming['outbounds']}
    mapped = {}
    added = 0
    added_tags = []

    def fingerprint(node):
        return json.dumps({key: value for key, value in node.items() if key != 'tag'}, sort_keys=True, ensure_ascii=False, allow_nan=False)

    def add(tag):
        nonlocal added
        if tag in mapped:
            return mapped[tag]
        node = copy.deepcopy(source[tag])
        if node.get('detour'):
            node['detour'] = add(node['detour'])
        if node['type'] in {'selector', 'urltest'}:
            node['outbounds'] = [add(dep) for dep in node['outbounds']]
            if node.get('default'):
                node['default'] = add(node['default'])
        key = fingerprint(node)
        same = next((old['tag'] for old in original if fingerprint(old) == key), None)
        if same is not None:
            mapped[tag] = same
            return same
        candidate, suffix = tag, 1
        while candidate in by_tag:
            suffix += 1
            candidate = tag[:70] + '-' + str(suffix)
        node['tag'] = candidate
        original.append(node)
        by_tag[candidate] = node
        mapped[tag] = candidate
        if node['type'] in NODE_TYPES:
            added += 1
            added_tags.append(candidate)
        return candidate

    for tag in source:
        add(tag)
    if len(original) > 1000:
        fail('The shared configuration contains too many outbounds.')
    current_final = result.get('route', {}).get('final')
    if not current_final and existing.get('outbounds'):
        current_final = existing['outbounds'][0]['tag']
    if not current_final:
        result.setdefault('route', {})['final'] = mapped[incoming['route']['final']]
    elif added_tags:
        current = by_tag.get(current_final)
        if current and current['type'] == 'selector':
            current['outbounds'] = list(dict.fromkeys([*current.get('outbounds', []), *added_tags]))
        else:
            wrapper, suffix = 'shared-nodes', 1
            while wrapper in by_tag:
                suffix += 1
                wrapper = 'shared-nodes-' + str(suffix)
            original.insert(0, {'type': 'selector', 'tag': wrapper, 'outbounds': list(dict.fromkeys([current_final, *added_tags])), 'default': current_final})
            result.setdefault('route', {})['final'] = wrapper
    return result, added


def export_node_links(config):
    from urllib.parse import quote, urlencode
    config = portable_config(config)
    nodes = [node for node in config['outbounds'] if node['type'] in NODE_TYPES]
    if any(node.get('detour') for node in nodes):
        fail('Share-link subscriptions cannot preserve proxy chains. Use the sing-box subscription instead.')
    links = []
    for node in nodes:
        kind = node['type']
        if kind not in {'vless', 'vmess', 'trojan', 'shadowsocks', 'hysteria2'}:
            fail('This configuration needs the sing-box subscription to preserve all node protocols.')
        unknown(node, {'type', 'tag', 'server', 'server_port', 'uuid', 'password', 'method', 'flow', 'tls', 'transport', 'security', 'alter_id', 'packet_encoding', 'obfs'})
        address = host(node.get('server'))
        address = '[' + address + ']' if ':' in address else address
        address += ':' + str(port(node.get('server_port')))
        fragment = '#' + quote(node['tag'], safe='')
        params = {}
        tls = node.get('tls', {})
        unknown(tls, {'enabled', 'server_name', 'insecure', 'alpn', 'utls', 'reality'})
        if tls.get('server_name'):
            params['sni'] = tls['server_name']
        if 'insecure' in tls:
            params['insecure'] = '1' if flag(tls['insecure']) else '0'
        if tls.get('alpn'):
            params['alpn'] = ','.join(tls['alpn'])
        if tls.get('utls'):
            unknown(tls['utls'], {'enabled', 'fingerprint'})
            if tls['utls'].get('enabled'):
                params['fp'] = tls['utls']['fingerprint']
        if kind == 'shadowsocks':
            if tls or node.get('transport'):
                fail('This configuration needs the sing-box subscription to preserve connection options.')
            credentials = base64.urlsafe_b64encode((node['method'] + ':' + node['password']).encode()).decode().rstrip('=')
            links.append('ss://' + credentials + '@' + address + fragment)
            continue
        transport = node.get('transport', {})
        network = transport.get('type', 'tcp')
        unknown(transport, {'type', 'path', 'headers', 'host', 'service_name'})
        if network not in {'tcp', 'ws', 'grpc', 'httpupgrade'}:
            fail('This configuration needs the sing-box subscription to preserve connection options.')
        if kind == 'vmess':
            if tls.get('reality') or network == 'httpupgrade' or 'packet_encoding' in node:
                fail('This configuration needs the sing-box subscription to preserve connection options.')
            value = {'v': '2', 'ps': node['tag'], 'add': node['server'], 'port': str(node['server_port']), 'id': node['uuid'], 'aid': str(node.get('alter_id', 0)), 'scy': node.get('security', 'auto'), 'net': network, 'type': 'none', 'tls': 'tls' if tls.get('enabled') else ''}
            for key in ('sni', 'alpn', 'fp'):
                if key in params:
                    value[key] = params[key]
            if 'insecure' in params:
                fail('This configuration needs the sing-box subscription to preserve certificate verification options.')
            if network == 'ws':
                unknown(transport.get('headers', {}), {'Host'})
                value['path'] = transport.get('path', '/')
                value['host'] = transport.get('headers', {}).get('Host', '')
            elif network == 'grpc':
                value['path'] = transport.get('service_name', '')
            links.append('vmess://' + base64.b64encode(json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()).decode())
            continue
        if kind in {'vless', 'trojan'}:
            params['type'] = network
            params['security'] = 'tls' if tls.get('enabled') else 'none'
            if kind == 'trojan' and not tls.get('enabled'):
                fail('This configuration needs the sing-box subscription to preserve connection options.')
            if network in {'ws', 'httpupgrade'}:
                params['path'] = transport.get('path', '/')
                unknown(transport.get('headers', {}), {'Host'})
                if transport.get('headers', {}).get('Host'):
                    params['host'] = transport['headers']['Host']
                elif transport.get('host'):
                    params['host'] = transport['host']
            elif network == 'grpc':
                params['serviceName'] = transport.get('service_name', '')
            if kind == 'vless':
                params['encryption'] = 'none'
                if node.get('flow'):
                    params['flow'] = node['flow']
                if node.get('packet_encoding'):
                    params['packetEncoding'] = node['packet_encoding']
                if tls.get('reality'):
                    unknown(tls['reality'], {'enabled', 'public_key', 'short_id'})
                    params.update(security='reality', pbk=tls['reality']['public_key'], sid=tls['reality'].get('short_id', ''))
                auth = node['uuid']
            else:
                if tls.get('reality'):
                    fail('This configuration needs the sing-box subscription to preserve connection options.')
                auth = node['password']
        else:
            if transport or tls.get('utls') or tls.get('reality'):
                fail('This configuration needs the sing-box subscription to preserve connection options.')
            auth = node['password']
            if node.get('obfs'):
                unknown(node['obfs'], {'type', 'password'})
                params['obfs'] = node['obfs']['type']
                params['obfs-password'] = node['obfs']['password']
        links.append(kind + '://' + quote(auth, safe='') + '@' + address + '/?' + urlencode(params) + fragment)
    return '\n'.join(links) + '\n'
