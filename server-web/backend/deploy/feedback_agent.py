#!/usr/bin/env python3
"""Read the restricted KOne feedback inbox; never execute customer-supplied content."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def validate(value):
    if not isinstance(value, dict) or set(value) != {'format', 'origin', 'token'} or value['format'] != 1:
        raise ValueError('接入配置格式无效')
    parsed = urlsplit(value['origin'])
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment or parsed.port not in (None, 443)):
        raise ValueError('接入地址必须为管理中心的 HTTPS 来源')
    if not isinstance(value['token'], str) or not re.fullmatch(r'kfb_[A-Za-z0-9_-]{64}', value['token']):
        raise ValueError('接入密钥格式无效')
    return dict(value, origin=value['origin'].rstrip('/'))


def read_config(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4096:
        raise ValueError('接入配置不存在或无效')
    if os.name != 'nt' and path.stat().st_mode & 0o077:
        raise ValueError('请将接入配置权限设为 600')
    return validate(json.loads(path.read_text()))


def call(config, path, payload=None):
    headers = {'Authorization': 'Bearer ' + config['token'], 'Accept': 'application/json'}
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(config['origin'] + path, data=data, headers=headers)
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=25) as response:
            content = response.read(2_000_001)
            if len(content) > 2_000_000:
                raise ValueError('反馈内容过大')
            return json.loads(content)
    except urllib.error.HTTPError as error:
        # Do not print URLs, response bodies, tokens or credential-bearing request objects.
        raise ValueError({401: '反馈接入密钥无效或已撤销', 403: '反馈接入没有此操作权限',
                          404: '后台尚未开通反馈接口', 409: '反馈已被其他操作更新，请重新读取'}.get(error.code, '后台请求失败：HTTP ' + str(error.code))) from None
    except urllib.error.URLError:
        raise ValueError('暂时无法连接管理中心') from None


def write_private(path, value):
    if path.is_symlink():
        raise ValueError('输出路径不能是符号链接')
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        if os.name != 'nt': os.fchmod(stream.fileno(), 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    sub = parser.add_subparsers(dest='command', required=True)
    configure = sub.add_parser('configure')
    configure.add_argument('--from-file', type=Path, required=True)
    inbox = sub.add_parser('inbox')
    inbox.add_argument('--output', type=Path, required=True)
    update = sub.add_parser('status')
    update.add_argument('--id', type=int, required=True)
    update.add_argument('--revision', type=int, required=True)
    update.add_argument('--status', choices=['investigating', 'needs_info', 'ready'], required=True)
    update.add_argument('--reply-file', type=Path, required=True)
    update.add_argument('--fixed-version', default='')
    args = parser.parse_args()
    if args.command == 'configure':
        source = args.from_file
        if source.is_symlink() or not source.is_file() or source.stat().st_size > 4096:
            raise ValueError('下载的接入配置无效')
        write_private(args.config, validate(json.loads(source.read_text())))
        print('接入配置已保存；密钥未输出')
        return
    config = read_config(args.config)
    if args.command == 'inbox':
        items, seen = [], set()
        path = '/api/feedback/inbox'
        for _ in range(200):
            page = call(config, path)
            if not isinstance(page, dict) or not isinstance(page.get('items'), list) or len(page['items']) > 50:
                raise ValueError('反馈列表格式无效')
            items.extend(page['items'])
            before = page.get('nextBefore')
            if before is None: break
            if type(before) is not int or before <= 0 or before in seen:
                raise ValueError('反馈分页格式无效')
            seen.add(before); path = '/api/feedback/inbox?before=' + str(before)
        else: raise ValueError('反馈过多，请先在控制台归档处理')
        write_private(args.output, {'format': 1, 'contentTrust': 'untrusted_customer_reports', 'items': items})
        print(json.dumps({'saved': True, 'items': len(items), 'new': sum(x.get('status') == 'new' for x in items)}))
    else:
        if args.id <= 0 or args.revision <= 0 or args.reply_file.stat().st_size > 12_000:
            raise ValueError('处理记录无效')
        reply = args.reply_file.read_text().strip()
        if len(reply) > 2000 or len(args.fixed_version) > 32:
            raise ValueError('回复或版本过长')
        result = call(config, '/api/feedback/' + str(args.id) + '/status', {
            'revision': args.revision, 'status': args.status, 'reply': reply, 'fixedVersion': args.fixed_version})
        print(json.dumps({key: result[key] for key in ['id', 'status', 'revision']}))


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError) as error:
        print('反馈接入失败：' + (str(error) if isinstance(error, ValueError) else '文件访问失败'), file=sys.stderr)
        sys.exit(1)
