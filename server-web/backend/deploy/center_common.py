"""Portable settings and a per-data-directory process lock. Standard library only."""
import json
import os
from pathlib import Path

VERSION = "0.3.9"


def load_settings(path):
    path = Path(path).expanduser().resolve()
    settings = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(settings, dict) or type(settings.get("format")) is not int or settings["format"] != 1:
        raise ValueError("管理中心设置格式无效")
    port = settings.get("port", 8768)
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise ValueError("管理中心端口须为 1–65535")
    data = settings.get("data_dir", "data")
    if not isinstance(data, str) or not data or "\\" in data:
        raise ValueError("data_dir 须使用相对目录，例如 data")
    relative = Path(data)
    if relative.is_absolute() or ".." in relative.parts or ":" in data:
        raise ValueError("data_dir 须位于管理中心目录内")
    if relative.parts and relative.parts[0] == "app":
        raise ValueError("数据目录不能放在程序 app 目录内")
    directory = (path.parent / relative).resolve()
    try:
        directory.relative_to(path.parent)
    except ValueError:
        raise ValueError("数据目录不能链接到管理中心目录外")
    if directory == path.parent:
        raise ValueError("数据与程序须使用不同目录")
    origins = settings.get("origins", [])
    if not isinstance(origins, list) or any(not isinstance(item, str) for item in origins):
        raise ValueError("origins 须为地址列表")
    node_origin = settings.get("node_origin")
    if node_origin is not None and not isinstance(node_origin,str):
        raise ValueError("node_origin 须为 HTTPS 地址")
    api_origin = settings.get("api_origin")
    if api_origin is not None and not isinstance(api_origin, str):
        raise ValueError("api_origin 须为 HTTPS 地址")
    trusted_proxy = settings.get("trusted_proxy")
    if trusted_proxy is not None and (trusted_proxy != "cloudflare-tunnel" or not api_origin):
        raise ValueError("trusted_proxy 须为 cloudflare-tunnel 且配置 api_origin")
    return {"port": port, "data_dir": directory, "origins": origins, "node_origin": node_origin,
            "api_origin": api_origin, "trusted_proxy": trusted_proxy}


class DataLock:
    """The lock file stays in place; lock ownership disappears after process exit."""
    def __init__(self, directory):
        self.handle = None
        directory = Path(directory).expanduser().resolve()
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        lock = directory / ".center.lock"
        if lock.is_symlink():
            raise ValueError("数据锁不能是符号链接")
        handle = open(lock, "a+b")
        try:
            if os.name == "nt":
                import msvcrt
                handle.seek(0, os.SEEK_END)
                if handle.tell() == 0:
                    handle.write(b"\0")
                    handle.flush()
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                os.chmod(str(lock), 0o600)
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            handle.close()
            raise ValueError("无法锁定数据目录，请检查是否有后台在运行及目录权限")
        self.handle = handle

    def close(self):
        if self.handle is not None:
            # Closing releases either OS's lock. Never unlink a held lock file.
            self.handle.close()
            self.handle = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
