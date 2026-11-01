#!/usr/bin/env python3
"""Upgrade local program files after the center stops. Preserve settings and data."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE))
from deploy.center_common import DataLock,VERSION,load_settings
from deploy.maintenance import backup


def upgrade(home):
    home=Path(home).expanduser().resolve()
    settings=load_settings(home/'settings.json')
    application=home/'app'
    if application.is_symlink() or not (application/'server.py').is_file():
        raise ValueError('找不到现有管理中心程序')
    manifest=json.loads((BASE/'release.json').read_text(encoding='utf-8'))
    if manifest.get('version') != VERSION or not isinstance(manifest.get('files'),dict):
        raise ValueError('请从完整发布包运行更新程序')
    from deploy.package import FILES
    if set(manifest['files']) != set(FILES):
        raise ValueError('发布包白名单不一致')
    payloads={}
    for name in FILES:
        source=BASE/name
        if source.is_symlink() or not source.is_file():
            raise ValueError('发布包文件缺失')
        content=source.read_bytes()
        if hashlib.sha256(content).hexdigest() != manifest['files'][name]:
            raise ValueError('发布包校验未通过')
        payloads[name]=content
    with DataLock(settings['data_dir']):
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        backups=home/'backups';backups.mkdir(exist_ok=True,mode=0o700)
        backup(settings['data_dir'],backups/('before-update-'+stamp+'.zip'))
        previous=backups/('app-before-'+stamp)
        with tempfile.TemporaryDirectory(prefix='.update-',dir=str(home)) as pending:
            staged=Path(pending)/'app'
            for name,content in payloads.items():
                destination=staged/name
                destination.parent.mkdir(parents=True,exist_ok=True)
                destination.write_bytes(content)
            application.rename(previous)
            try:
                staged.rename(application)
            except Exception:
                previous.rename(application)
                raise
        if os.name == 'nt':
            python='"'+sys.executable.replace('%','%%')+'"'
            (home/'start-windows.cmd').write_bytes(('@echo off\r\nsetlocal\r\nchcp 65001 >nul\r\n'+python+
                ' -X utf8 -B "%~dp0app\\deploy\\run-center.py" --settings "%~dp0settings.json"\r\nif errorlevel 1 pause\r\n').encode('utf-8'))
    return home


if __name__=='__main__':
    parser=argparse.ArgumentParser(description='更新管理中心程序，保留数据与设置')
    parser.add_argument('--home',type=Path,required=True)
    args=parser.parse_args()
    try:
        home=upgrade(args.home)
        print('程序已更新到 '+VERSION+'；数据库与设置保留，更新前备份已保存。')
        print('请启动原管理中心入口。')
    except (OSError,ValueError,KeyError) as error:
        parser.exit(1,'更新未完成：%s\n'%error)
