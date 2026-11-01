#!/usr/bin/env bash
set -euo pipefail
umask 077

# Usage: sudo bash install-node.sh https://console.example ADDRESS
# Enter the one-time token at the hidden prompt, never in shell history.
if [[ "$#" != 2 ]]; then
  echo "用法：sudo bash install-node.sh https://管理接口地址 VPS地址" >&2
  exit 1
fi
CONSOLE="$(printf '%s' "$1" | sed 's:/*$::')"
ADDRESS="$2"
if [[ "$(id -u)" != 0 ]]; then
  echo "请使用 sudo 运行安装程序。" >&2
  exit 1
fi
if [[ "$CONSOLE" != https://* || -z "$ADDRESS" ]]; then
  echo "管理接口必须是 HTTPS。" >&2
  exit 1
fi
if [[ "$(uname -s)" != Linux || ! -d /run/systemd/system ]]; then
  echo "需要使用 systemd 的 Linux VPS。" >&2
  exit 1
fi
if [[ ! -r /etc/os-release ]]; then
  echo "无法核实系统版本。" >&2
  exit 1
fi
. /etc/os-release
case "$ID:$VERSION_ID" in
  ubuntu:22.04|ubuntu:24.04|debian:12|debian:13) ;;
  *) echo "第一版安装器支持 Ubuntu 22.04/24.04、Debian 12/13。" >&2; exit 1 ;;
esac
MISSING=0
for program in python3 openssl curl; do
  command -v "$program" >/dev/null || MISSING=1
done
if [[ "$MISSING" == 1 ]]; then
  apt-get update
  apt-get install -y python3 openssl curl ca-certificates
fi
for program in python3 openssl curl systemctl; do
  command -v "$program" >/dev/null || { echo "缺少依赖：$program" >&2; exit 1; }
done
python3 -c 'import sys;sys.exit(0 if sys.version_info >= (3,9) else 1)' || {
  echo "需要 Python 3.9 或以上版本。" >&2
  exit 1
}
if [[ -e /var/lib/kone-node/node.json ]]; then
  echo "该 VPS 已注册；安装程序不会覆盖凭据和配置。" >&2
  exit 1
fi
if [[ -e /etc/systemd/system/kone-node.service || -e /etc/systemd/system/kone-proxy.service ]]; then
  echo "已有 KOne 服务文件，请先核对旧安装。" >&2
  exit 1
fi
mkdir -p /opt/kone-node/bin /var/lib/kone-node
chmod 700 /opt/kone-node /var/lib/kone-node
TEMP_AGENT="$(mktemp)"
trap 'rm -f "$TEMP_AGENT"' EXIT
curl --fail --silent --show-error --proto '=https' --max-time 60 \
  "$CONSOLE/downloads/kone-node.py" -o "$TEMP_AGENT"
python3 -c 'import ast,sys;ast.parse(open(sys.argv[1]).read())' "$TEMP_AGENT"
install -m 700 "$TEMP_AGENT" /opt/kone-node/agent.py
if [[ ! -x /opt/kone-node/bin/sing-box ]]; then
  python3 /opt/kone-node/agent.py install-engine
fi
python3 /opt/kone-node/agent.py enroll --console "$CONSOLE" --address "$ADDRESS" --mode systemd
cat > /etc/systemd/system/kone-proxy.service <<'UNIT'
[Unit]
Description=KOne managed sing-box
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
WorkingDirectory=/var/lib/kone-node
ExecStart=/opt/kone-node/bin/sing-box run -c /var/lib/kone-node/active.json
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
ProtectHome=true
ProtectSystem=strict
PrivateTmp=true
ReadWritePaths=/var/lib/kone-node
LimitNOFILE=65536
StandardOutput=null
StandardError=null
[Install]
WantedBy=multi-user.target
UNIT
cat > /etc/systemd/system/kone-node.service <<'UNIT'
[Unit]
Description=KOne node management agent
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
WorkingDirectory=/var/lib/kone-node
ExecStart=/usr/bin/python3 /opt/kone-node/agent.py run
NoNewPrivileges=true
Restart=on-failure
RestartSec=10
UMask=0077
ProtectHome=true
ProtectSystem=strict
PrivateTmp=true
ReadWritePaths=/var/lib/kone-node
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable kone-proxy.service
systemctl enable --now kone-node.service
echo "VPS 已接入。回到控制台部署协议。"
echo "系统和供应商防火墙需允许所选 TCP/UDP 端口；本程序不会覆盖已有防火墙。"
