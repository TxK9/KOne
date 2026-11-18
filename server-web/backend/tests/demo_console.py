#!/usr/bin/env python3
"""A throwaway console full of made-up servers, routes and customers, for looking at the UI.

Runs on 127.0.0.1 with a fresh temporary database and never touches var/. Pretend agents keep
most servers online with drifting numbers; one is left offline, one is still waiting to be
installed. The data goes away with the temporary folder.

    python3 -B tests/demo_console.py [--port 8791] [--web DIR]

Sign in with the demo password below (local throwaway console only).
"""
import argparse
import json
import random
import shutil
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.dont_write_bytecode = True
CONSOLE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE))
sys.path.insert(0, str(CONSOLE / "node"))

import agent  # noqa: E402  (node/agent.py: the real certificate maker)
import server as console  # noqa: E402

DEMO_PASSWORD = "ink-demo-2026"
GIB = 1024 ** 3

CONFIG = {
    "outbounds": [
        {"type": "selector", "tag": "proxy", "outbounds": ["us-1", "jp-1", "sg-1"]},
        {"type": "vless", "tag": "us-1", "server": "us.example.invalid", "server_port": 443, "uuid": "00000000-0000-4000-8000-00000000demo"},
        {"type": "hysteria2", "tag": "jp-1", "server": "jp.example.invalid", "server_port": 443, "password": "demo"},
        {"type": "trojan", "tag": "sg-1", "server": "sg.example.invalid", "server_port": 443, "password": "demo"},
        {"type": "direct", "tag": "direct"},
    ],
    "dns": {"servers": [{"type": "https", "server": "1.1.1.1"}, {"type": "local"}]},
    "route": {"rules": [{"domain_suffix": [".cn"], "outbound": "direct"}, {"ip_is_private": True, "outbound": "direct"}], "final": "proxy"},
}

SERVERS = [
    # name, address, os, cores, memory GiB, disk GiB, cpu, state
    ("Los Angeles 1", "198.51.100.21", "Ubuntu 24.04 LTS", 4, 8, 80, 18, "live"),
    ("Tokyo", "198.51.100.34", "Debian 12", 2, 4, 40, 42, "live"),
    ("Singapore", "203.0.113.9", "Ubuntu 22.04 LTS", 2, 2, 30, 94, "live"),
    ("Frankfurt", "203.0.113.57", "Debian 12", 1, 1, 25, 8, "offline"),
    ("New York", "198.51.100.77", "Ubuntu 24.04 LTS", 2, 4, 50, 0, "pending"),
]


def metrics(name, cores, mem, disk, cpu, rng, uptime):
    cpu = max(1.0, min(99.0, cpu + rng.uniform(-6, 6)))
    memory_used = int(mem * GIB * rng.uniform(0.34, 0.62))
    disk_used = int(disk * GIB * 0.41)
    return {
        "hostname": name.replace(" ", "-").lower() + ".demo", "osName": None, "kernel": "6.8.0-45-generic",
        "cpuModel": "Synthetic CPU", "agentVersion": "0.2.9", "cpuCores": cores, "uptime": int(uptime),
        "memoryTotal": mem * GIB, "memoryUsed": memory_used, "diskTotal": disk * GIB, "diskUsed": disk_used,
        "networkReceived": int(uptime * 380_000), "networkSent": int(uptime * 120_000),
        "downloadRate": int(rng.uniform(0.2, 6.5) * 1024 * 1024 * cpu / 50), "uploadRate": int(rng.uniform(0.05, 1.2) * 1024 * 1024 * cpu / 50),
        "cpuPercent": round(cpu, 1), "memoryPercent": round(memory_used / (mem * GIB) * 100, 1), "diskPercent": 41.0,
        "load": [round(cpu / 100 * cores * f, 2) for f in (1.0, 0.86, 0.74)],
    }


class Agents:
    """Stands in for the node agents: polls, runs every job as if it worked, reports numbers."""

    def __init__(self, app, servers):
        self.app, self.servers = app, servers
        self.rng = random.Random(7)
        self.started = time.time() - 86400 * 9

    def beat(self, live_only=True):
        for item in self.servers:
            if item["state"] != "live" and live_only:
                continue
            uptime = time.time() - self.started + item["cores"] * 7000
            data = metrics(item["name"], item["cores"], item["mem"], item["disk"], item["cpu"], self.rng, uptime)
            data["osName"] = item["os"]
            row = self.row(item["id"])
            health = {"metrics": data, "runtime": "running", "revision": row["applied"]}
            reply = self.app.fleet.poll(item["id"], health)
            job = reply.get("job")
            while job:
                if job["kind"] == "apply":
                    self.app.fleet.complete(item["id"], {"jobId": job["id"], "lease": job["lease"], "status": "running", "code": "running"})
                else:
                    self.app.fleet.complete(item["id"], {"jobId": job["id"], "lease": job["lease"], "status": "failed", "code": "internal_error"})
                job = self.app.fleet.poll(item["id"], {"runtime": "running", "revision": self.row(item["id"])["applied"]}).get("job")

    def row(self, identifier):
        with self.app.store.connect() as c:
            return dict(c.execute("SELECT * FROM fleet_servers WHERE id=?", (identifier,)).fetchone())

    def run(self):
        while True:
            time.sleep(15)
            try:
                self.beat()
            except Exception as error:  # keep the demo alive; it is only a picture
                print("agent:", error, flush=True)


def seed(app, workdir):
    store, fleet, customers = app.store, app.fleet, app.customers
    store.setup(DEMO_PASSWORD)
    text = json.dumps(CONFIG, ensure_ascii=False)
    base = store.import_profile({"name": "US JP SG · split", "configText": text, "source": "file", "userIds": []})["id"]
    store.import_profile({"name": "Global · US", "configText": json.dumps({"outbounds": [CONFIG["outbounds"][1], CONFIG["outbounds"][4]]}), "source": "paste", "userIds": []})

    family = customers.save_group({"name": "Family", "profileId": base, "quotaBytes": 0, "deviceLimit": 3, "validDays": 0})["id"]
    friends = customers.save_group({"name": "Friends", "profileId": base, "quotaBytes": 100 * GIB, "deviceLimit": 2, "validDays": 365})["id"]
    trial = customers.save_group({"name": "Trial", "profileId": None, "quotaBytes": 10 * GIB, "deviceLimit": 1, "validDays": 30})["id"]

    servers = []
    for name, address, os_name, cores, mem, disk, cpu, state in SERVERS:
        ticket = fleet.enrollment({"name": name, "address": address})
        item = {"id": ticket["id"], "name": name, "os": os_name, "cores": cores, "mem": mem, "disk": disk, "cpu": cpu, "state": state}
        if state != "pending":
            folder = Path(workdir) / ("node-" + ticket["id"][:8])
            folder.mkdir()
            cert = agent.certificate(folder, address)
            fleet.register({"token": ticket["token"], "certificate": cert, "os": "linux", "arch": "amd64", "mode": "systemd"})
        servers.append(item)
    agents = Agents(app, servers)
    agents.beat(live_only=False)

    def add_node(server, kind, port):
        return fleet.add_node({"name": server["name"] + (" · VLESS" if kind == "vless" else " · HY2"), "serverId": server["id"], "kind": kind, "port": port})["id"]

    la, tokyo, singapore, frankfurt = servers[:4]
    la_v = add_node(la, "vless", 443)
    add_node(la, "hysteria2", 8443)
    tokyo_v = add_node(tokyo, "vless", 443)
    sg_h = add_node(singapore, "hysteria2", 443)
    fr_v = add_node(frankfurt, "vless", 443)
    agents.beat(live_only=False)

    exit_id = fleet.add_exit({"name": "Home broadband", "kind": "socks", "address": "198.51.100.200", "port": 1080, "username": "", "password": ""})["id"]
    route_us = fleet.add_route({"name": "US · direct", "nodeIds": [la_v], "exitId": None})["id"]
    route_jp = fleet.add_route({"name": "Tokyo → Los Angeles", "nodeIds": [tokyo_v, la_v], "exitId": None})["id"]
    route_home = fleet.add_route({"name": "Singapore · home", "nodeIds": [sg_h], "exitId": exit_id})["id"]
    fleet.add_route({"name": "Europe · Frankfurt", "nodeIds": [fr_v], "exitId": None})
    agents.beat(live_only=False)

    people = {}
    for name, group in [("Morgan", family), ("Parker", family), ("Lin", friends), ("Alex", friends), ("Zhou", friends),
                        ("Wang", friends), ("Mia", trial), ("Jay", trial), ("Kevin", trial)]:
        people[name] = store.add_user({"name": name, "groupId": group})["id"]

    fleet.assign({"routeId": route_us, "userIds": [people["Lin"], people["Alex"]]})
    fleet.assign({"routeId": route_jp, "userIds": [people["Zhou"]]})
    fleet.assign({"routeId": route_home, "userIds": [people["Morgan"]]})
    agents.beat()
    agents.beat()

    def device(name, model, status="applied", rx=None):
        code = customers.invite(people[name])["inviteCode"]
        claimed = customers.claim({"inviteCode": code, "deviceName": model})
        envelope = customers.configuration(claimed["deviceId"])
        if status:
            customers.ack(claimed["deviceId"], {"revision": envelope["revision"], "status": "received"})
            if status != "received":
                customers.ack(claimed["deviceId"], {"revision": envelope["revision"], "status": status, "code": "ok" if status == "applied" else "start_failed"})
        customers.diagnostics(claimed["deviceId"], {"appVersion": "1.0.0", "network": random.choice(["wifi", "cellular"]), "events": ["app_started", "connection_ok"],
                                                    "downloadBytes": rx, "uploadBytes": rx // 9 if rx else None})
        return claimed["deviceId"]

    device("Morgan", "iPhone 15", rx=3 * GIB)
    device("Parker", "Pixel 8", rx=GIB)
    device("Lin", "PHK110", rx=12 * GIB)
    device("Lin", "Test Laptop")
    device("Alex", "Galaxy S24", status="received")
    device("Zhou", "iPhone 13", rx=2 * GIB)
    customers.set_member(people["Mia"], "configure", {"groupId": trial, "profileId": base})
    device("Mia", "Redmi Note 13", status=None)
    customers.set_member(people["Jay"], "configure", {"groupId": trial, "profileId": base})
    customers.invite(people["Jay"])  # a key handed out, not yet used
    customers.set_member(people["Kevin"], "configure", {"groupId": trial, "profileId": base})
    customers.set_member(people["Kevin"], "pause", {"paused": True})
    customers.service(people["Wang"])  # service taken back
    # Claude held back for two people: one on a route of the console's servers, one on an imported config.
    customers.claude(people["Alex"], {"blocked": True})
    customers.claude(people["Mia"], {"blocked": True})
    # Two people who only get the App opened, no nodes: one has bound a phone, one has not yet.
    for name, bound in [("Jo", False), ("Leo", True)]:
        people[name] = store.add_user({"name": name, "groupId": trial, "appOnly": True})["id"]
        if bound:
            customers.claim({"inviteCode": customers.invite(people[name])["inviteCode"], "deviceName": "iPhone 16"})

    month = time.strftime("%Y-%m", time.gmtime())
    now = time.time()
    with store.connect() as c:
        for name, used in [("Lin", 86 * GIB), ("Alex", 23 * GIB), ("Zhou", 101 * GIB), ("Morgan", 41 * GIB), ("Parker", 7 * GIB), ("Mia", 3 * GIB)]:
            c.execute("INSERT OR REPLACE INTO customer_usage VALUES(?,?,?,?,?,?)", (people[name], month, int(used * 0.88), int(used * 0.12), now - 30, now - 86400 * 12))
        c.execute("INSERT OR REPLACE INTO fleet_probes(route_id,ip,checked,status) VALUES(?,?,?,?)", (route_us, "198.51.100.21", console.timestamp(), "verified"))
        c.execute("INSERT OR REPLACE INTO fleet_probes(route_id,ip,checked,status) VALUES(?,?,?,?)", (route_jp, "198.51.100.21", console.timestamp(), "verified"))
        # Frankfurt went quiet ten minutes ago.
        c.execute("UPDATE fleet_servers SET last_seen=? WHERE id=?", (now - 600, frankfurt["id"]))
        c.execute("UPDATE fleet_host_metrics SET received=? WHERE server_id=?", (now - 600, frankfurt["id"]))
    return agents


def main():
    parser = argparse.ArgumentParser(description="Disposable KOne UI preview with synthetic data")
    parser.add_argument("--port", type=int, default=8791)
    parser.add_argument("--web", type=Path, default=CONSOLE / "web")
    parser.add_argument("--work-dir", type=Path)
    args = parser.parse_args()
    workdir = str(args.work_dir) if args.work_dir else tempfile.mkdtemp(prefix="kone-demo-")
    Path(workdir).mkdir(parents=True, exist_ok=True)
    # A made-up public address, so keys come with a kone://claim link and its QR code.
    server = console.create_server("127.0.0.1", args.port, Path(workdir) / "data", args.web.resolve(),
                                   api_origin="https://kone-api.example.invalid")
    agents = seed(server.app, workdir)
    threading.Thread(target=agents.run, daemon=True).start()
    print("Disposable KOne UI preview: http://127.0.0.1:%d (synthetic state: %s)" % (server.server_address[1], workdir), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if args.work_dir is None:
            shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
