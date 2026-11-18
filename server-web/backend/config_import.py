"""Translate node share links without fetching subscriptions or contacting nodes."""
import base64
import binascii
import ipaddress
import re
from urllib.parse import parse_qsl, unquote, urlsplit
import uuid


class ConfigImportError(ValueError):
    pass


def decoded(value):
    if re.search(r"%(?![0-9a-fA-F]{2})", value):
        raise ConfigImportError("节点链接的转义字符无效")
    try:
        result = unquote(value, errors="strict")
        result.encode("utf-8")
    except (UnicodeError, ValueError):
        raise ConfigImportError("节点链接含无效字符") from None
    if any(ord(c) < 32 or ord(c) == 127 for c in result):
        raise ConfigImportError("节点链接含无效控制字符")
    return result


def boolean(value):
    if value not in {"0", "1", "false", "true"}:
        raise ConfigImportError("节点链接的布尔参数无效")
    return value in {"1", "true"}


def alias(params, *names):
    values = [params[key] for key in names if key in params]
    if len(set(values)) > 1:
        raise ConfigImportError("节点链接包含冲突参数")
    return values[0] if values else None


def parse_node_links(text):
    lines = [line.strip() for line in text.strip().lstrip("\ufeff").splitlines() if line.strip()]
    if not lines or len(lines) > 200:
        raise ConfigImportError("请粘贴 1 至 200 条节点链接，每行一条")
    nodes, tags, warnings = [], {"proxy", "direct"}, []
    for number, line in enumerate(lines, 1):
        if any(ord(c) < 32 or ord(c) == 127 for c in line):
            raise ConfigImportError("节点链接含无效控制字符")
        try:
            parsed = urlsplit(line)
            kind = parsed.scheme.lower()
            host = parsed.hostname
            port = parsed.port
        except ValueError:
            raise ConfigImportError("节点地址或端口无效") from None
        if kind not in {"vless", "hysteria2", "hy2"}:
            raise ConfigImportError("暂不支持这种链接，请使用 VLESS、HY2 链接或 sing-box JSON")
        if not host or any(c.isspace() for c in host) or "%" in host or "\\" in host:
            raise ConfigImportError("节点服务器地址无效")
        try:
            ipaddress.ip_address(host)
        except ValueError:
            try:
                host = host.encode("idna").decode("ascii")
            except UnicodeError:
                raise ConfigImportError("节点服务器地址无效") from None
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", host) or len(host) > 253:
                raise ConfigImportError("节点服务器地址无效")
        if port is None:
            if kind == "vless":
                raise ConfigImportError("VLESS 链接缺少端口")
            port = 443
        if not 1 <= port <= 65535 or parsed.path not in {"", "/"}:
            raise ConfigImportError("节点端口或链接路径无效")
        if "@" not in parsed.netloc:
            raise ConfigImportError("节点链接缺少身份凭据")
        auth = decoded(parsed.netloc.rsplit("@", 1)[0])
        if not auth or len(auth) > 1024:
            raise ConfigImportError("节点身份凭据无效")
        if re.search(r"%(?![0-9a-fA-F]{2})", parsed.query):
            raise ConfigImportError("节点链接的转义字符无效")
        try:
            pairs = parse_qsl(parsed.query, keep_blank_values=True, errors="strict", max_num_fields=50)
        except (ValueError, UnicodeError):
            raise ConfigImportError("节点链接参数无效") from None
        params = {}
        for key, value in pairs:
            if key in params:
                raise ConfigImportError("节点链接包含重复参数")
            if not re.fullmatch(r"[A-Za-z0-9_-]+", key) or any(ord(c) < 32 or ord(c) == 127 for c in value):
                raise ConfigImportError("节点链接参数无效")
            params[key] = value
        tag = decoded(parsed.fragment).strip()[:80] or "node-" + str(number)
        original_tag, suffix = tag, 1
        while tag in tags:
            suffix += 1
            tag = original_tag[:70] + "-" + str(suffix)
        tags.add(tag)
        outbound = {"type": "vless" if kind == "vless" else "hysteria2", "tag": tag,
                    "server": host, "server_port": port}
        tls = {"enabled": True}
        sni = alias(params, "sni", "peer", "serverName")
        if sni:
            if any(c.isspace() for c in sni) or len(sni) > 253:
                raise ConfigImportError("节点 SNI 无效")
            tls["server_name"] = sni
        insecure = alias(params, "insecure", "allowInsecure")
        if insecure is not None:
            tls["insecure"] = boolean(insecure)
        if params.get("alpn"):
            alpn = params["alpn"].split(",")
            if any(not item or len(item) > 128 or any(c.isspace() for c in item) for item in alpn):
                raise ConfigImportError("节点 ALPN 无效")
            tls["alpn"] = alpn
        if kind == "vless":
            allowed = {"security", "encryption", "flow", "type", "headerType", "sni", "peer", "serverName",
                       "insecure", "allowInsecure", "alpn", "fp", "pbk", "sid", "spx", "path", "host",
                       "serviceName", "packetEncoding"}
            if params.get("encryption", "none") != "none":
                raise ConfigImportError("此 VLESS 加密方式暂不支持，请使用完整 JSON")
            try:
                outbound["uuid"] = str(uuid.UUID(auth))
            except ValueError:
                raise ConfigImportError("VLESS 身份 UUID 无效") from None
            security = params.get("security", "none")
            if security not in {"none", "tls", "reality"}:
                raise ConfigImportError("VLESS 安全模式暂不支持")
            transport = params.get("type", "tcp")
            if transport not in {"tcp", "raw", "ws", "grpc", "httpupgrade"}:
                raise ConfigImportError("此 VLESS 传输方式暂不支持，请使用完整 JSON")
            if params.get("headerType", "none") != "none":
                raise ConfigImportError("此 VLESS 伪装方式暂不支持，请使用完整 JSON")
            if params.get("flow"):
                if params["flow"] != "xtls-rprx-vision" or transport not in {"tcp", "raw"} or security == "none":
                    raise ConfigImportError("VLESS flow 与传输或安全模式不兼容")
                outbound["flow"] = params["flow"]
            if params.get("fp") or security == "reality":
                fingerprint = params.get("fp") or "chrome"
                if fingerprint not in {"chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized"}:
                    raise ConfigImportError("TLS 指纹暂不支持")
                tls["utls"] = {"enabled": True, "fingerprint": fingerprint}
            if security == "reality":
                key, short_id = params.get("pbk", ""), params.get("sid", "")
                try:
                    valid_key = re.fullmatch(r"[A-Za-z0-9_-]{43}=?", key) and len(base64.b64decode(key.rstrip("=") + "=", altchars=b"-_", validate=True)) == 32
                except (ValueError, binascii.Error):
                    valid_key = False
                if not valid_key or not re.fullmatch(r"(?:[0-9a-fA-F]{2}){0,8}", short_id):
                    raise ConfigImportError("REALITY 公钥或 short ID 无效")
                tls["reality"] = {"enabled": True, "public_key": key.rstrip("="), "short_id": short_id.lower()}
                if params.get("spx"):
                    warnings.append("spx 是 Xray 专用参数，sing-box 不使用此字段")
            elif any(params.get(key) for key in ("pbk", "sid", "spx")):
                raise ConfigImportError("REALITY 参数与安全模式不一致")
            if security != "none":
                outbound["tls"] = tls
            elif sni or insecure is not None or params.get("fp") or params.get("alpn"):
                raise ConfigImportError("TLS 参数与安全模式不一致")
            if params.get("packetEncoding"):
                if params["packetEncoding"] not in {"xudp", "packetaddr"}:
                    raise ConfigImportError("VLESS UDP 编码暂不支持")
                outbound["packet_encoding"] = params["packetEncoding"]
            if transport in {"ws", "httpupgrade"}:
                if params.get("serviceName"):
                    raise ConfigImportError("传输参数与 VLESS 传输方式不一致")
                value = {"type": transport, "path": params.get("path", "/")}
                if params.get("host"):
                    if transport == "ws":
                        value["headers"] = {"Host": params["host"]}
                    else:
                        value["host"] = params["host"]
                outbound["transport"] = value
            elif transport == "grpc":
                if params.get("path") or params.get("host"):
                    raise ConfigImportError("传输参数与 VLESS 传输方式不一致")
                outbound["transport"] = {"type": "grpc", "service_name": params.get("serviceName", "")}
            elif any(params.get(key) for key in ("path", "host", "serviceName")):
                raise ConfigImportError("传输参数与 VLESS 传输方式不一致")
        else:
            allowed = {"sni", "peer", "serverName", "insecure", "allowInsecure", "alpn", "obfs", "obfs-password"}
            outbound["password"] = auth
            outbound["tls"] = tls
            if params.get("obfs"):
                if params["obfs"] not in {"salamander", "gecko"} or not params.get("obfs-password"):
                    raise ConfigImportError("HY2 混淆类型或密码无效")
                outbound["obfs"] = {"type": params["obfs"], "password": params["obfs-password"]}
            elif params.get("obfs-password"):
                raise ConfigImportError("HY2 混淆参数不完整")
        if set(params) - allowed:
            raise ConfigImportError("链接含暂不支持的参数，请使用完整 sing-box JSON，避免丢失配置")
        nodes.append(outbound)
    outbounds = list(nodes)
    final = nodes[0]["tag"]
    if len(nodes) > 1:
        outbounds.insert(0, {"type": "selector", "tag": "proxy", "outbounds": [n["tag"] for n in nodes], "default": final})
        final = "proxy"
    outbounds.append({"type": "direct", "tag": "direct"})
    return {"outbounds": outbounds, "route": {"final": final}}, list(dict.fromkeys(warnings))
