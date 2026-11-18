#!/usr/bin/env python3
"""Prepare a portable home without starting services or changing OS settings."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE / "deploy"))
from package import FILES


def default_home():
    if os.name == "nt":
        return Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "KOneConsole"
    return Path.home() / "KOneConsole"


def prepare(home, port=8768):
    home = Path(home).expanduser().resolve()
    if home.exists():
        raise ValueError("目标目录已存在；请选择一个新的目录，避免覆盖程序或数据")
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise ValueError("端口须为 1–65535")
    # Read every whitelisted source before writing the target.
    payloads = {}
    for name in FILES:
        source = BASE / name
        if source.is_symlink() or not source.is_file():
            raise ValueError("代码文件缺失：" + name)
        payloads[name] = source.read_bytes()
    home.mkdir(parents=True, mode=0o700)
    try:
        for name, content in payloads.items():
            destination = home / "app" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
        (home / "data").mkdir(mode=0o700)
        (home / "backups").mkdir(mode=0o700)
        settings = home / "settings.json"
        settings.write_text(json.dumps({"format": 1, "port": port, "data_dir": "data", "origins": []},
                                       indent=2) + "\n", encoding="utf-8")
        os.chmod(str(settings), 0o600)
        windows_python = ('"'+sys.executable.replace('%','%%')+'"') if os.name == 'nt' else 'py -3'
        (home / "start-windows.cmd").write_bytes(
            ('@echo off\r\nsetlocal\r\nchcp 65001 >nul\r\n'+windows_python+' -X utf8 -B "%~dp0app\\deploy\\run-center.py" --settings "%~dp0settings.json"\r\nif errorlevel 1 pause\r\n').encode('utf-8'))
        (home / "start-linux.sh").write_text(
            '#!/bin/sh\nset -eu\ncenter_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\n'
            'exec python3 -B "$center_dir/app/deploy/run-center.py" --settings "$center_dir/settings.json"\n',
            encoding="utf-8")
        os.chmod(str(home / "start-linux.sh"), 0o700)
    except Exception:
        shutil.rmtree(str(home))  # Only the newly created target is ours.
        raise
    return home


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="准备 KOne 跨系统管理中心目录")
    parser.add_argument("--home", type=Path, default=default_home())
    parser.add_argument("--port", type=int, default=8768)
    args = parser.parse_args()
    try:
        location = prepare(args.home, args.port)
        print("已准备管理中心：" + str(location))
        print("尚未启动服务、设置自启动或修改电源与网络。")
    except (OSError, ValueError) as error:
        parser.exit(1, "准备未完成：%s\n" % error)
