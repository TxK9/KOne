#!/usr/bin/env python3
"""Consistent SQLite backups and non-destructive portable restores."""
import argparse
from contextlib import closing, contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import sqlite3
import sys
import tempfile
import time
import zipfile

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
from deploy.center_common import DataLock, VERSION, load_settings

CORE = {"settings", "profiles", "users", "records"}
FLEET = {"fleet_servers", "fleet_enrollments", "fleet_nodes", "fleet_exits", "fleet_routes",
         "fleet_assignments", "fleet_credentials", "fleet_jobs", "fleet_probes"}
CUSTOMERS = {"customer_groups", "customer_accounts", "customer_invites", "customer_devices",
             "customer_deliveries", "customer_events", "customer_usage", "customer_node_counters"}
CONTROLLER = {"controller_nodes", "controller_dependencies", "controller_devices", "controller_grants",
              "controller_publish_requests", "controller_lease_nonces"}
MAX_DATABASE = 4 * 1024 * 1024 * 1024


def readonly(path):
    return sqlite3.connect(Path(path).resolve().as_uri() + "?mode=ro", uri=True, timeout=10)


def digest(path):
    result = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def inspect_database(path):
    with closing(readonly(path)) as connection:
        if connection.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
            raise ValueError("数据库完整性检查失败")
        if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise ValueError("数据库关联检查失败")
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        if version > 6:
            raise ValueError("备份来自较新版本，请先更新程序")
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not CORE.issubset(tables) or (tables & FLEET and not FLEET.issubset(tables)):
            raise ValueError("不是完整的 KOne 管理中心数据库")
        columns = {"settings": {"key", "value"}, "profiles": {"id", "config_json", "version_number"},
                   "users": {"id", "profile_id"}, "records": {"id", "time"}}
        for table, required in columns.items():
            actual = {row[1] for row in connection.execute('PRAGMA table_info("' + table + '")')}
            if not required.issubset(actual):
                raise ValueError("数据库结构不兼容")
        selected = (CORE | FLEET | CUSTOMERS | CONTROLLER | {"profile_revisions", "profile_subscriptions"} | {"fleet_host_metrics","customer_service_actions"}) & tables
        if version >= 2 and "fleet_host_metrics" not in tables:
            raise ValueError("服务器信息表缺失")
        if (version>=3 and not CUSTOMERS.issubset(tables)) or (tables & CUSTOMERS and not CUSTOMERS.issubset(tables)):
            raise ValueError("客户管理表缺失")
        if version>=4:
            account_columns={r[1] for r in connection.execute('PRAGMA table_info(customer_accounts)')}
            if 'customer_service_actions' not in tables or 'revoked' not in account_columns:
                raise ValueError('客户收回权限结构缺失')
            if version>=5 and 'app_only' not in account_columns:
                raise ValueError('App 开通权限结构缺失')
        if version >= 6 and not CONTROLLER.issubset(tables):
            raise ValueError("The node controller security schema is incomplete")
        counts = {table: connection.execute('SELECT COUNT(*) FROM "' + table + '"').fetchone()[0]
                  for table in sorted(selected)}
        return {"user_version": version, "counts": counts}


def backup(data_dir, output):
    data_dir, output = Path(data_dir).resolve(), Path(output).expanduser().resolve()
    database = data_dir / "console.sqlite3"
    if not database.is_file() or database.is_symlink():
        raise ValueError("找不到管理中心数据库，未生成空备份")
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if output.exists() or output.is_symlink():
        raise ValueError("备份目标已存在，不会覆盖")
    with tempfile.TemporaryDirectory(prefix=".kone-backup-", dir=str(output.parent)) as directory:
        snapshot = Path(directory) / "console.sqlite3"
        descriptor = os.open(str(snapshot), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(descriptor)
        deadline = time.monotonic() + 60
        def progress(*_):
            if time.monotonic() > deadline:
                raise TimeoutError("数据库繁忙，备份超时；请稍后重试")
        with closing(readonly(database)) as source, closing(sqlite3.connect(str(snapshot))) as destination:
            source.backup(destination, pages=128, progress=progress, sleep=0.1)
            destination.execute("PRAGMA journal_mode=DELETE")
        metadata = inspect_database(snapshot)
        manifest = {"format": 1, "application": "kone-console", "app_version": VERSION,
                    "created": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                    "database_sha256": digest(snapshot), **metadata}
        descriptor = os.open(str(output), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        try:
            with os.fdopen(descriptor, "wb") as stream, zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.write(snapshot, "console.sqlite3")
                archive.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
            os.chmod(str(output), 0o600)
        except Exception:
            output.unlink(missing_ok=True)
            raise
    return manifest


@contextmanager
def verified_snapshot(path):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) != 2 or {entry.filename for entry in entries} != {"console.sqlite3", "manifest.json"}:
            raise ValueError("备份目录无效，不接受额外文件或路径")
        if archive.getinfo("manifest.json").file_size > 16384 or archive.getinfo("console.sqlite3").file_size > MAX_DATABASE:
            raise ValueError("备份文件超过校验上限")
        manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
        if (not isinstance(manifest, dict) or type(manifest.get("format")) is not int
                or manifest["format"] != 1 or manifest.get("application") != "kone-console"):
            raise ValueError("备份格式无效")
        with tempfile.TemporaryDirectory(prefix="kone-verify-") as directory:
            snapshot = Path(directory) / "console.sqlite3"
            descriptor = os.open(str(snapshot), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, "wb") as output, archive.open("console.sqlite3") as source:
                shutil.copyfileobj(source, output, length=1024 * 1024)
            if digest(snapshot) != manifest.get("database_sha256"):
                raise ValueError("备份校验码不一致，不能恢复")
            metadata = inspect_database(snapshot)
            if any(metadata[key] != manifest.get(key) for key in ("user_version", "counts")):
                raise ValueError("备份记录与数据库不一致")
            yield manifest, snapshot


def verify(path):
    with verified_snapshot(path) as (manifest, _):
        return manifest


def restore(path, data_dir):
    data_dir = Path(data_dir).expanduser()
    if data_dir.is_symlink():
        raise ValueError("恢复目录不能是符号链接")
    data_dir = data_dir.resolve()
    with verified_snapshot(path) as (manifest, snapshot), DataLock(data_dir):
        if any(item.name != ".center.lock" for item in data_dir.iterdir()):
            raise ValueError("恢复只接受空数据目录，不会覆盖已有数据")
        backup_version = manifest["user_version"]
        with tempfile.TemporaryDirectory(prefix=".restore-", dir=data_dir) as staged:
            staged_dir = Path(staged)
            pending = staged_dir / "console.sqlite3"
            descriptor = os.open(str(pending), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, "wb") as output, snapshot.open("rb") as source:
                shutil.copyfileobj(source, output, length=1024 * 1024)
                output.flush()
                os.fsync(output.fileno())
            # Migrate only the verified staged copy. No server or listener is started.
            from server import ConsoleStore, parse_json_text
            from fleet import Fleet
            from customers import Customers
            from node_controller import NodeController
            store = ConsoleStore(staged_dir)
            fleet = Fleet(store)
            customers = Customers(store, fleet)
            NodeController(store, customers, parse_json_text)
            epoch = time.time()
            with store.connect() as connection:
                connection.execute("BEGIN IMMEDIATE")
                devices = [row[0] for row in connection.execute("SELECT id FROM customer_devices ORDER BY id")]
                recovery = json.dumps({"format": 1, "cutoff": epoch, "deviceIds": devices}, separators=(",", ":"))
                connection.execute("INSERT INTO settings VALUES('controller_recovery_epoch',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (recovery,))
                connection.execute("UPDATE customer_invites SET used=1 WHERE used=0")
                connection.execute("UPDATE controller_grants SET active=0,updated=?", (epoch,))
                connection.execute("UPDATE controller_devices SET mode='revoked',owner_publish=0,auth_generation=auth_generation+1,ack_generation=NULL,ack_status=NULL,ack_revision=NULL,ack_config_revision=NULL,ack_at=NULL,selected_node_id=NULL,lease_generation=NULL,lease_config_revision=NULL,lease_not_after=NULL,updated=?", (epoch,))
                connection.execute("DELETE FROM controller_lease_nonces")
            metadata = inspect_database(pending)
            if metadata["user_version"] != 6:
                raise ValueError("The restored security schema was not upgraded")
            restored_hash = digest(pending)
            os.replace(str(pending), str(data_dir / "console.sqlite3"))
            os.chmod(str(data_dir / "console.sqlite3"), 0o600)
        return {**manifest, **metadata, "backupSchemaVersion": backup_version,
                "restoredSchemaVersion": 6, "controllerAuthorizationReset": bool(devices),
                "controllerRecoveryEpoch": epoch, "restoredDatabaseSha256": restored_hash}



def main():
    parser = argparse.ArgumentParser(description="KOne backup, verification and recovery")
    parser.add_argument("action", choices=["backup", "verify", "restore"])
    parser.add_argument("--settings", type=Path, default=BASE.parent / "settings.json")
    parser.add_argument("--data-dir", type=Path, help="Data directory of the existing deployment")
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    if args.action == "verify":
        if not args.archive:
            parser.error("verify requires --archive")
        manifest = verify(args.archive)
    else:
        directory = args.data_dir if args.data_dir is not None else load_settings(args.settings)["data_dir"]
        if args.action == "backup":
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            archive = args.archive or directory.parent / "backups" / ("kone-" + stamp + "-" + secrets.token_hex(3) + ".zip")
            manifest = backup(directory, archive)
            print("Backup: " + str(archive.resolve()))
        else:
            if not args.archive:
                parser.error("restore requires --archive")
            manifest = restore(args.archive, directory)
    print("Completed " + args.action + "; database verified; " + str(manifest["counts"].get("users", 0)) + " users")
    if "controllerAuthorizationReset" in manifest:
        print("controllerAuthorizationReset=" + str(manifest["controllerAuthorizationReset"]))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, sqlite3.Error, zipfile.BadZipFile, RuntimeError) as error:
        # Do not print database contents, credentials, or JSON parser context.
        print("Maintenance failed. Check the archive, destination and service state.", file=sys.stderr)
        sys.exit(1)
