#!/usr/bin/env python3
"""KOne node agent. Fixed apply operations only; no remote shell interface."""
import argparse
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import platform
import re
import signal
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

VERSION = "0.2.3"
CORE_VERSION = "1.14.3"
PROBE_URL = "https://api64.ipify.org"
CORE_HASHES = {
    "linux-amd64": "e2bdf179c15a3652955dc44867e33ca0965c9c9b91b34267dcc5a5d639a5feee",
    "linux-arm64": "29dae24d76bea3bc62d07dd3e60cfc12003de7debbd6b768df9be9c68360174e",
    "darwin-arm64": "0360ce634a04c26a6b4fd957517987bb4f6c8950f63435ce3c5af2fcae5860c5",
}


class HostSampler:
    """Linux host counters only. No process lists, environment or user traffic logs."""
    def __init__(self, proc="/proc", root="/", clock=time.monotonic):
        self.proc, self.root, self.clock = Path(proc), root, clock
        self.cpu_previous = None
        self.network_previous = None

    def read(self, name):
        return (self.proc / name).read_text(encoding="utf-8")[:256 * 1024]

    def sample(self):
        values = {"agentVersion": VERSION, "hostname": socket.gethostname()[:100],
                  "cpuCores": os.cpu_count(), "kernel": platform.release()[:100]}
        try:
            os_info = Path("/etc/os-release").read_text(encoding="utf-8")
            match = re.search(r'^PRETTY_NAME=(.*)$', os_info, re.M)
            if match:
                values["osName"] = match[1].strip('"')[:100]
        except OSError:
            values["osName"] = platform.system()
        try:
            cpuinfo = self.read("cpuinfo")
            match = re.search(r'^(?:model name|Hardware)\s*:\s*(.+)$', cpuinfo, re.M)
            if match:
                values["cpuModel"] = match[1][:160]
            ticks = [int(item) for item in self.read("stat").splitlines()[0].split()[1:9]]
            if len(ticks) >= 4:
                current = (sum(ticks), ticks[3] + (ticks[4] if len(ticks) > 4 else 0))
                if self.cpu_previous:
                    elapsed, idle = (current[i] - self.cpu_previous[i] for i in (0, 1))
                    if elapsed > 0 and 0 <= idle <= elapsed:
                        values["cpuPercent"] = round(100 * (elapsed - idle) / elapsed, 1)
                self.cpu_previous = current
        except (OSError, ValueError, IndexError):
            self.cpu_previous = None
        try:
            memory = {}
            for line in self.read("meminfo").splitlines():
                key, value = line.split(":", 1)
                memory[key] = int(value.strip().split()[0]) * 1024
            total, available = memory["MemTotal"], memory["MemAvailable"]
            if total > 0 and 0 <= available <= total:
                values.update(memoryTotal=total, memoryUsed=total-available,
                              memoryPercent=round(100*(total-available)/total, 1))
        except (OSError, ValueError, KeyError, IndexError):
            pass
        try:
            total, used, _ = shutil.disk_usage(self.root)
            if total > 0:
                values.update(diskTotal=total, diskUsed=used, diskPercent=round(100*used/total, 1))
        except OSError:
            pass
        try:
            values["uptime"] = int(float(self.read("uptime").split()[0]))
            loads = self.read("loadavg").split()[:3]
            if len(loads) == 3:
                values["load"] = [float(item) for item in loads]
        except (OSError, ValueError, IndexError):
            pass
        try:
            counters = {}
            for line in self.read("net/dev").splitlines():
                if ":" not in line:
                    continue
                name, raw = line.split(":", 1)
                name = name.strip()
                if name == "lo":
                    continue
                fields = raw.split()
                if len(fields) >= 9:
                    counters[name] = (int(fields[0]), int(fields[8]))
            now = self.clock()
            if counters:
                # Host interfaces include tunnel/virtual interfaces. This is not billing usage.
                values["networkReceived"] = sum(item[0] for item in counters.values())
                values["networkSent"] = sum(item[1] for item in counters.values())
                if self.network_previous:
                    previous_time, previous = self.network_previous
                    elapsed = now-previous_time
                    if elapsed > 0 and previous.keys() == counters.keys():
                        differences = [(counters[name][0]-previous[name][0], counters[name][1]-previous[name][1]) for name in counters]
                        if all(rx >= 0 and tx >= 0 for rx, tx in differences):
                            values["downloadRate"] = round(sum(rx for rx, _ in differences)/elapsed)
                            values["uploadRate"] = round(sum(tx for _, tx in differences)/elapsed)
                self.network_previous = now, counters
        except (OSError, ValueError, IndexError):
            self.network_previous = None
        return values


def write_private(path, data):
    path = Path(path)
    temp = path.with_suffix(path.suffix + ".tmp")
    descriptor = os.open(str(temp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(str(temp), str(path))
    os.chmod(str(path), 0o600)


def valid_console(url):
    if not isinstance(url, str) or any(ord(char) < 32 for char in url):
        raise ValueError("管理接口地址无效")
    p = urlsplit(url)
    try:
        p.port
    except ValueError:
        raise ValueError("管理接口端口无效")
    if p.username or p.password or p.query or p.fragment or p.path not in ("", "/") or not p.hostname:
        raise ValueError("管理接口地址无效")
    if p.scheme != "https" and not (p.scheme == "http" and p.hostname in ("127.0.0.1", "::1", "localhost")):
        raise ValueError("远程管理必须使用 HTTPS")
    return url.rstrip("/")


def call(config, endpoint, data):
    headers = {"Content-Type": "application/json"}
    if config.get("credential"):
        headers["Authorization"] = "Bearer " + config["credential"]
    request = urllib.request.Request(config["console"]+endpoint, json.dumps(data).encode(), headers, method="POST")
    # Do not forward credentials through environment-configured proxies.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(request, timeout=20) as response:
        return json.loads(response.read(2*1024*1024))


def change_console(directory, url):
    """Local migration operation. Does not enroll again or touch proxy settings."""
    directory = Path(directory)
    settings = directory / "node.json"
    if settings.is_symlink() or not settings.is_file():
        raise ValueError("没有可更新的已注册节点")
    config = json.loads(settings.read_text())
    if not isinstance(config, dict) or not config.get("serverId") or not config.get("credential"):
        raise ValueError("节点注册资料不完整")
    console = valid_console(url)
    if config["console"] == console:
        return
    write_private(directory / "node.previous-console.json", json.dumps(config))
    config["console"] = console
    write_private(settings, json.dumps(config))


def certificate(directory, address):
    try:
        address = str(ipaddress.ip_address(address.strip("[]")))
        san = "IP:" + address
    except ValueError:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{0,251}[A-Za-z0-9]", address) or ".." in address:
            raise ValueError("请填写有效的节点 IP 或域名")
        san = "DNS:" + address
    if (directory/"tls.key").exists() or (directory/"tls.crt").exists():
        saved_address = directory/"certificate-address"
        if (directory/"tls.key").is_file() and (directory/"tls.crt").is_file() and saved_address.is_file() and saved_address.read_text()==address:
            return (directory/"tls.crt").read_text()
        raise ValueError("节点证书与地址不一致，请核对旧安装")
    result = subprocess.run(["openssl","req","-x509","-newkey","ec","-pkeyopt","ec_paramgen_curve:prime256v1","-pkeyopt","ec_param_enc:named_curve",
                             "-nodes","-days","365","-keyout",str(directory/"tls.key"),
                             "-out",str(directory/"tls.crt"),"-subj","/CN="+address,
                             "-addext","subjectAltName="+san], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
    if result.returncode:
        raise ValueError("生成节点证书失败，请检查 OpenSSL")
    os.chmod(str(directory/"tls.key"), 0o600)
    os.chmod(str(directory/"tls.crt"), 0o600)
    write_private(directory/"certificate-address", address)
    return (directory/"tls.crt").read_text()


def install_engine(path):
    system = platform.system().lower()
    arch = {"aarch64":"arm64","arm64":"arm64","x86_64":"amd64"}.get(platform.machine(), "")
    key = system+"-"+arch
    if key not in CORE_HASHES:
        raise ValueError("仅支持 Linux amd64/arm64；本地测试支持 macOS arm64")
    name = "sing-box-"+CORE_VERSION+"-"+key
    url = "https://github.com/SagerNet/sing-box/releases/download/v"+CORE_VERSION+"/"+name+".tar.gz"
    destination = Path(path).resolve()
    if destination.exists():
        raise ValueError("核心路径已存在，请使用现有核心")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kone-core-") as temp:
        archive = Path(temp)/"core.tar.gz"
        with urllib.request.urlopen(url, timeout=90) as response, archive.open("wb") as out:
            size = 0
            while True:
                block = response.read(1024*1024)
                if not block:
                    break
                size += len(block)
                if size > 150*1024*1024:
                    raise ValueError("核心包超过大小上限")
                out.write(block)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != CORE_HASHES[key]:
            raise ValueError("核心校验和不匹配")
        with tarfile.open(str(archive), "r:gz") as tar:
            member = tar.getmember(name+"/sing-box")
            if not member.isfile() or member.size > 150*1024*1024:
                raise ValueError("核心包结构无效")
            with tar.extractfile(member) as source:
                binary = source.read()
        descriptor, temp_path = tempfile.mkstemp(prefix=".core-", dir=str(destination.parent))
        try:
            with os.fdopen(descriptor, "wb") as out:
                out.write(binary)
                out.flush()
                os.fsync(out.fileno())
            os.chmod(temp_path, 0o755)
            os.link(temp_path, str(destination))  # Fail rather than overwrite another installation.
        finally:
            os.unlink(temp_path)
    print("sing-box "+CORE_VERSION+" 已校验安装", flush=True)


class Runner:
    def __init__(self, directory, mode, engine, listen=None):
        self.directory, self.mode, self.engine, self.listen = Path(directory), mode, str(Path(engine).resolve()), listen
        self.child = None

    def command(self, *args):
        return subprocess.run([self.engine]+list(args), cwd=str(self.directory),
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30).returncode

    def start(self):
        if self.mode == "systemd":
            result = subprocess.run(["systemctl","restart","kone-proxy.service"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            if result.returncode:
                return False
            time.sleep(1)
            return subprocess.run(["systemctl","is-active","--quiet","kone-proxy.service"]).returncode == 0
        if self.child and self.child.poll() is None:
            self.child.terminate()
            try:
                self.child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.child.kill()
                self.child.wait()
        self.child = subprocess.Popen([self.engine,"run","-c","active.json"], cwd=str(self.directory),
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(.6)
        return self.child.poll() is None

    def health(self):
        revision = 0
        try:
            revision = int((self.directory/"applied-revision").read_text())
        except (OSError, ValueError):
            pass
        if self.mode == "stage":
            return {"runtime":"staged", "revision":revision}
        if self.mode == "process":
            return {"runtime":"running" if self.child and self.child.poll() is None else "stopped", "revision":revision}
        try:
            running = subprocess.run(["systemctl","is-active","--quiet","kone-proxy.service"],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode == 0
        except (OSError, subprocess.SubprocessError):
            running = False
        return {"runtime":"running" if running else "stopped", "revision":revision}

    def probe(self, job, target=PROBE_URL):
        """Route the approved check through the entry service; never fetch directly."""
        if self.mode == "stage":
            return {"status":"failed","code":"probe_failed"}
        outbound = job.get("outbound")
        if not isinstance(outbound, dict) or outbound.get("type") not in ("vless","hysteria2") or outbound.get("server") != "127.0.0.1":
            return {"status":"failed","code":"probe_failed"}
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            number = sock.getsockname()[1]
        child = None
        with tempfile.TemporaryDirectory(prefix="probe-", dir=str(self.directory)) as temp:
            cfg = Path(temp)/"client.json"
            config = {"log":{"level":"error"}, "inbounds":[{"type":"mixed","tag":"probe-local","listen":"127.0.0.1","listen_port":number}],
                      "outbounds":[outbound], "route":{"final":outbound["tag"]}}
            write_private(cfg, json.dumps(config))
            try:
                if self.command("check","-c",str(cfg)):
                    return {"status":"failed","code":"probe_failed"}
                child = subprocess.Popen([self.engine,"run","-c",str(cfg)], cwd=str(self.directory),
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                time.sleep(.5)
                if child.poll() is not None:
                    return {"status":"failed","code":"probe_failed"}
                result = subprocess.run(["curl","--silent","--show-error","--fail","--max-time","15",
                                         "--max-filesize","1024","--noproxy","","--proxy",
                                         "socks5h://127.0.0.1:"+str(number), target],
                                        capture_output=True, timeout=20)
                if result.returncode or len(result.stdout)>128:
                    return {"status":"failed","code":"probe_failed"}
                address = ipaddress.ip_address(result.stdout.decode("ascii").strip())
                if not address.is_global:
                    return {"status":"failed","code":"probe_failed"}
                return {"status":"probed","code":"probed","ip":str(address)}
            except (OSError, ValueError, UnicodeError, subprocess.SubprocessError):
                return {"status":"failed","code":"probe_failed"}
            finally:
                if child and child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait()

    def validate(self, config):
        if set(config) - {"log","inbounds","outbounds","route"}:
            return False
        inbounds = config.get("inbounds")
        outbounds = config.get("outbounds")
        rules = config.get("route", {}).get("rules")
        if not isinstance(inbounds, list) or not isinstance(outbounds,list) or not isinstance(rules,list):
            return False
        for i in inbounds:
            if not isinstance(i,dict) or set(i) - {"type","tag","listen","listen_port","users","tls"}:
                return False
            if i.get("type") not in ("vless","hysteria2") or not isinstance(i.get("listen_port"),int) or not 1<=i["listen_port"]<=65535:
                return False
            if i.get("tls") != {"enabled":True,"certificate_path":"tls.crt","key_path":"tls.key"}:
                return False
        allowed = {"type","tag","server","server_port","uuid","password","username","version","tls"}
        for o in outbounds:
            if not isinstance(o,dict) or set(o)-allowed or o.get("type") not in ("direct","vless","hysteria2","socks","http"):
                return False
            tls=o.get("tls",{})
            if not isinstance(tls,dict) or set(tls)-{"enabled","server_name","certificate"} or tls.get("enabled") is False:
                return False
        return all(isinstance(r,dict) and not set(r)-{"inbound","auth_user","action","outbound"}
                   and r.get("action") in ("route","reject") for r in rules)

    def restore(self, previous, active):
        if previous.exists():
            write_private(active, previous.read_text())
            try:
                self.start()
            except (OSError, subprocess.SubprocessError):
                pass
        else:
            active.unlink(missing_ok=True)
            if self.mode == "systemd":
                subprocess.run(["systemctl","stop","kone-proxy.service"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)

    def execute(self, job):
        if job.get("kind") == "probe":
            return self.probe(job)
        if job.get("kind") != "apply" or not isinstance(job.get("config"), dict):
            return {"status":"failed","code":"config_invalid"}
        config = job["config"]
        # Only server-produced structured sing-box configs, never remote executable commands.
        if not self.validate(config):
            return {"status":"failed","code":"config_invalid"}
        for inbound in config.get("inbounds", []):
            if inbound.get("type") not in ("vless","hysteria2") or inbound.get("tls", {}).get("certificate_path") != "tls.crt" or inbound.get("tls", {}).get("key_path") != "tls.key":
                return {"status":"failed","code":"config_invalid"}
            if self.listen:
                inbound["listen"] = self.listen
        path = self.directory/"candidate.json"
        write_private(path, json.dumps(config, ensure_ascii=False, indent=2))
        if self.mode == "stage":
            return {"status":"staged","code":"staged"}
        if not Path(self.engine).is_file():
            return {"status":"failed","code":"engine_missing"}
        if self.command("check","-c","candidate.json"):
            return {"status":"failed","code":"config_invalid"}
        old_path, active = self.directory/"previous.json", self.directory/"active.json"
        if active.exists():
            write_private(old_path, active.read_text())
        else:
            old_path.unlink(missing_ok=True)
        # Bind-check newly requested ports; ports in the previous managed config are owned by us.
        old_ports = set()
        if old_path.exists():
            old_ports = {(i["type"],i["listen_port"]) for i in json.loads(old_path.read_text()).get("inbounds", [])}
        for inbound in config.get("inbounds", []):
            key = (inbound["type"], inbound["listen_port"])
            if key in old_ports:
                continue
            listen = inbound["listen"]
            family = socket.AF_INET6 if ":" in listen else socket.AF_INET
            sock = socket.socket(family, socket.SOCK_DGRAM if key[0]=="hysteria2" else socket.SOCK_STREAM)
            try:
                sock.bind((listen, key[1]))
            except OSError:
                return {"status":"failed","code":"port_busy"}
            finally:
                sock.close()
        write_private(active, path.read_text())
        try:
            started = self.start()
        except (OSError, subprocess.SubprocessError):
            started = False
        if not started:
            self.restore(old_path, active)
            return {"status":"failed","code":"start_failed"}
        write_private(self.directory/"applied-revision", str(job.get("revision",0)))
        return {"status":"running","code":"running"}

    def close(self):
        if self.child and self.child.poll() is None:
            self.child.terminate()
            try:
                self.child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.child.kill()
                self.child.wait()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["enroll","run","install-engine","set-console"])
    parser.add_argument("--console")
    parser.add_argument("--address")
    parser.add_argument("--data-dir", type=Path, default=Path("/var/lib/kone-node"))
    parser.add_argument("--engine", default="/opt/kone-node/bin/sing-box")
    parser.add_argument("--mode", choices=["stage","process","systemd"], default="stage")
    parser.add_argument("--token-stdin", action="store_true")
    parser.add_argument("--listen", choices=["127.0.0.1"], help="仅用于本机联调")
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    if args.action == "install-engine":
        install_engine(args.engine)
        return
    directory = args.data_dir.expanduser().resolve()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(str(directory), 0o700)
    settings = directory/"node.json"
    if args.action == "set-console":
        change_console(directory, args.console or "")
        print("管理接口地址已更新；请重启节点管理服务，代理配置与接入凭据保留。", flush=True)
        return
    if args.action == "enroll":
        if settings.exists():
            raise ValueError("节点已注册，现有凭据不会覆盖")
        console = valid_console(args.console or "")
        if not args.address:
            raise ValueError("需要 --address 节点 IP 或域名")
        token = sys.stdin.readline().strip() if args.token_stdin else getpass.getpass("一次性接入凭据：")
        cert = certificate(directory, args.address)
        result = call({"console":console}, "/api/node/register", {"token":token,"certificate":cert,"mode":args.mode,
                       "os":platform.system()+" "+platform.release(),"arch":platform.machine(),"address":args.address})
        write_private(settings, json.dumps({"console":console,"serverId":result["serverId"],
                      "credential":result["credential"],"mode":args.mode,"engine":str(Path(args.engine).resolve()),"listen":args.listen}))
        print("节点已接入", flush=True)
        return
    config = json.loads(settings.read_text())
    valid_console(config["console"])
    runner = Runner(directory, config["mode"], config["engine"], config.get("listen"))
    if runner.mode == "process" and (directory/"active.json").exists():
        runner.start()
    receipt = directory/"receipt.json"
    stopped = [False]
    sampler = HostSampler()
    signal.signal(signal.SIGTERM, lambda *_: stopped.__setitem__(0, True))
    try:
        while not stopped[0]:
            try:
                # An unacknowledged result is retried without applying a config twice.
                if receipt.exists():
                    result = json.loads(receipt.read_text())
                    call(config,"/api/node/result",result)
                    receipt.unlink()
                health = runner.health()
                health["metrics"] = sampler.sample()
                job = call(config,"/api/node/poll",health)["job"]
                if job:
                    try:
                        result = runner.execute(job)
                    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
                        result = {"status":"failed","code":"internal_error"}
                    result.update({"jobId":job["id"],"lease":job["lease"]})
                    write_private(receipt, json.dumps(result))
                    call(config,"/api/node/result",result)
                    receipt.unlink()
                    print("节点任务："+result["status"], flush=True)
            except urllib.error.HTTPError as error:
                print("管理接口返回 HTTP "+str(error.code), flush=True)
                if error.code == 401:
                    break
                if error.code in (403,409) and receipt.exists():
                    receipt.unlink()  # A renewed lease or superseded job must not block the queue.
            except (OSError, ValueError, subprocess.SubprocessError):
                print("节点暂时无法同步，稍后重试", flush=True)
            if args.once:
                break
            for _ in range(5):
                if stopped[0]:
                    break
                time.sleep(1)
    finally:
        runner.close()


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, urllib.error.URLError) as error:
        # Never print response payloads, configs, or node credentials.
        print("节点操作未完成："+str(error) if isinstance(error, ValueError) else "节点操作未完成，请检查环境与连接", file=sys.stderr)
        sys.exit(1)
