#!/usr/bin/env python3
"""Service entry point; reads non-secret settings and binds loopback only."""
import argparse
from pathlib import Path
import signal
import sys
import threading

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
from server import create_server
from deploy.center_common import load_settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--settings", type=Path, default=BASE.parent / "settings.json")
    args = parser.parse_args()
    settings = load_settings(args.settings)
    server = create_server("127.0.0.1", int(settings["port"]), data_dir=settings["data_dir"],
                           web_dir=BASE/"web", public_origin=settings.get("origins", []),
                           node_origin=settings.get("node_origin"), api_origin=settings.get("api_origin"),
                           trusted_proxy=settings.get("trusted_proxy"))
    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print("KOne management center started (loopback only)", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        detail = str(error)
        if any("\u3400" <= char <= "\u9fff" for char in detail):
            detail = "Invalid service configuration (" + type(error).__name__ + ")"
        print("Management center could not start: " + detail, file=sys.stderr)
        sys.exit(1)
