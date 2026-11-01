#!/usr/bin/env python3
"""Isolated KOne visual channel. Only the named local AVD is controlled."""
import argparse
import json
import hashlib
import os
from pathlib import Path
import plistlib
import subprocess
import time
import re
import xml.etree.ElementTree as ET
import zipfile

SOURCE = Path(__file__).resolve().parents[1]
WORK = SOURCE.parent
RUNTIME = WORK / "runtime"
SDK = RUNTIME / "sdk"
PROFILE_PATH = Path(os.environ.get("KONE_VISUAL_HOST_PROFILE", str(RUNTIME / "host-local.json")))
if not PROFILE_PATH.is_file():
    raise RuntimeError("Create a host-local visual profile outside the repository; see ANDROID_VISUAL.md")
PROFILE = json.loads(PROFILE_PATH.read_text())
SHARED_SDK = Path(PROFILE["shared_sdk"])
JAVA = Path(PROFILE["java_home"])
ADB = SHARED_SDK / "platform-tools/adb"
AVD = "kone_visual_api35"
TARGET = "emulator-5580"
PACKAGE = "cc.txwd.kone.visual"
ACTIVITY = PACKAGE + "/io.nekohasekai.sfa.preview.VisualPreviewActivity"


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def adb(*args, capture=False):
    return run([ADB, "-s", TARGET, *args], capture_output=capture)


def owned_device():
    result = adb("emu", "avd", "name", capture=True).stdout.decode()
    if result.splitlines()[0].strip() != AVD:
        raise RuntimeError("Refusing to control a device outside this KOne AVD")


def output_dir(label):
    if not re.fullmatch(r"[A-Za-z0-9._-]+", label):
        raise ValueError("Evidence label must be a single directory name")
    volume = Path(PROFILE["volume_mount"])
    if not volume.is_mount():
        raise RuntimeError("Delivery volume is not mounted; no alternate location")
    probe = PROFILE["volume_probe"]
    command = [part.replace("{mountpoint}", str(volume)) for part in probe["command"]]
    raw = run(command, capture_output=True).stdout
    info = plistlib.loads(raw) if probe["format"] == "plist" else json.loads(raw)
    if info.get(probe["uuid_key"]) != PROFILE["volume_uuid"] or not info.get(probe["writable_key"]):
        raise RuntimeError("Delivery volume identity or writeability check failed")
    delivery = Path(PROFILE["delivery_root"])
    delivery.resolve().relative_to(volume.resolve())
    path = delivery / label
    path.mkdir(parents=True, exist_ok=True)
    return path


def start(visible=False):
    RUNTIME.mkdir(parents=True, exist_ok=True)
    try:
        owned_device()
        return
    except subprocess.CalledProcessError:
        pass
    emulator = SDK / "emulator/emulator"
    if not emulator.exists():
        raise RuntimeError("Install the isolated emulator runtime first; see ANDROID_VISUAL.md")
    env = dict(os.environ, ANDROID_HOME=str(SDK), ANDROID_AVD_HOME=str(RUNTIME / "avd"), ANDROID_USER_HOME=str(RUNTIME / "android-user"), ANDROID_EMULATOR_HOME=str(RUNTIME / "android-user"))
    args = [emulator, "-avd", AVD, "-port", "5580", "-no-snapshot", "-no-boot-anim", "-gpu", "auto", "-memory", "1536", "-cores", "2", "-no-audio"]
    if not visible:
        args += ["-no-window"]
    with (RUNTIME / "emulator.log").open("ab") as log:
        proc = subprocess.Popen([str(a) for a in args], env=env, stdout=log, stderr=log, start_new_session=True)
    (RUNTIME / "emulator.pid").write_text(str(proc.pid))
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("Emulator stopped; see runtime/emulator.log")
        try:
            if adb("shell", "getprop", "sys.boot_completed", capture=True).stdout.strip() == b"1":
                owned_device()
                print("KOne isolated emulator ready")
                return
        except subprocess.CalledProcessError:
            pass
        time.sleep(2)
    raise RuntimeError("Emulator boot exceeded 180 seconds")


def record_build():
    records = []
    for scope in ["android/app/src/main", "android/app/src/debug", "android/app/src/testPreview"]:
        for path in sorted((SOURCE / scope).rglob("*")):
            if path.is_file():
                records.append((str(path.relative_to(SOURCE)), hashlib.sha256(path.read_bytes()).hexdigest()))
    for name in ["android/app/build.gradle.kts", "android/kone-version.properties"]:
        records.append((name, hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()))
    manifest = "\n".join(f"{digest}  {name}" for name, digest in records)
    diff = run(["git", "-C", SOURCE, "diff", "--binary"], capture_output=True).stdout
    apks = SOURCE / "android/app/build/outputs/apk/other/debug"
    record = {
        "baseline_commit": (json.loads((WORK / "BASELINE.json").read_text())["development_baseline"]["commit"] if (WORK / "BASELINE.json").is_file() else None),
        "source_head_commit": run(["git", "-C", SOURCE, "rev-parse", "HEAD"], capture_output=True).stdout.decode().strip(),
        "source_tree_sha256": hashlib.sha256(manifest.encode()).hexdigest(),
        "source_file_count": len(records),
        "worktree_diff_sha256": hashlib.sha256(diff).hexdigest(),
        "candidate": "Android UI working tree with isolated synthetic preview; not a pure baseline commit",
        "apk_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in apks.glob("*.apk")},
    }
    (RUNTIME / "preview-build-evidence.json").write_text(json.dumps(record, indent=2))
    (RUNTIME / "preview-source-sha256.txt").write_text(manifest)


def build():
    RUNTIME.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(JAVA), ANDROID_HOME=str(SHARED_SDK), ANDROID_NDK_HOME=str(SHARED_SDK / "ndk" / PROFILE["ndk_version"]))
    run([SOURCE / "android/gradlew", ":app:assembleOtherDebug", "-PkoneEdition=share", "-PkoneVisualPreview=true", "--offline", "--no-daemon", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8"], cwd=SOURCE / "android", env=env)
    record_build()


def install():
    owned_device()
    apks = sorted((SOURCE / "android/app/build/outputs/apk/other/debug").glob("*arm64-v8a*.apk"))
    if not apks:
        raise RuntimeError("No preview APK; run the build command first")
    apk = apks[-1]
    aapt = SHARED_SDK / "build-tools" / PROFILE["build_tools_version"] / "aapt"
    badging = run([aapt, "dump", "badging", apk], capture_output=True).stdout.decode()
    if "name='" + PACKAGE + "'" not in badging or "launchable-activity: name='io.nekohasekai.sfa.preview.VisualPreviewActivity'" not in badging:
        raise RuntimeError("Refusing to install an APK without the isolated preview identity")
    if "uses-permission: name='android.permission.INTERNET'" in badging:
        raise RuntimeError("Preview APK must not request Internet permission")
    manifest = run([aapt, "dump", "xmltree", apk, "AndroidManifest.xml"], capture_output=True).stdout.decode()
    if any("E: " + tag + " " in manifest for tag in ["service", "receiver", "provider"]):
        raise RuntimeError("Preview APK must not contain background components")
    if '="android.app.Application"' not in manifest:
        raise RuntimeError("Preview APK must use the platform Application")
    photos = ["portrait_backdrop", "sunset_backdrop"] + [f"owner_wallpaper_{i}" for i in range(1, 7)]
    with zipfile.ZipFile(apk) as archive:
        for name in photos:
            paths = [p for p in archive.namelist() if Path(p).stem == name]
            if not paths or not all(p.endswith(".xml") for p in paths):
                raise RuntimeError("Preview contains a private wallpaper raster or missing placeholder")
    adb("install", "-r", apk)


def show(locale, dark, page, state, font):
    owned_device()
    adb("shell", "am", "start", "-S", "-n", ACTIVITY, "--es", "locale", locale,
        "--ez", "dark", str(dark).lower(), "--es", "page", page, "--es", "state", state,
        "--ef", "fontScale", str(font))
    (RUNTIME / "preview-requested-state.json").write_text(json.dumps({"locale": locale, "dark": dark, "page": page, "state": state, "font_scale": font}))


def foreground():
    owned_device()
    current = adb("shell", "dumpsys", "activity", "activities", capture=True).stdout.decode()
    resumed = [line for line in current.splitlines() if "mResumedActivity" in line or "topResumedActivity" in line]
    if not any(PACKAGE + "/" in line for line in resumed):
        raise RuntimeError("KOne Visual is not the foreground app")


def wait_ready():
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            foreground()
        except RuntimeError:
            time.sleep(1)
            continue
        adb("shell", "uiautomator", "dump", "/data/local/tmp/kone-visual.xml", capture=True)
        xml = adb("exec-out", "cat", "/data/local/tmp/kone-visual.xml", capture=True).stdout
        nodes = list(ET.fromstring(xml).iter("node"))
        if any(n.get("package") == PACKAGE and (n.get("text") or n.get("class") == "android.webkit.WebView") for n in nodes):
            return xml
        time.sleep(1)
    raise RuntimeError("Preview content is not ready; no screenshot was saved")


def tap_text(text):
    xml = wait_ready()
    nodes = [n for n in ET.fromstring(xml).iter("node") if n.get("text") == text]
    if len(nodes) != 1:
        raise RuntimeError("Expected one visible node with that text")
    bounds = [int(n) for n in re.findall(r"\d+", nodes[0].get("bounds", ""))]
    if len(bounds) != 4:
        raise RuntimeError("Invalid node bounds")
    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))


def capture(label, name):
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
        raise ValueError("Screenshot name must be a single filename stem")
    xml = wait_ready()
    dest = output_dir(label)
    png = adb("exec-out", "screencap", "-p", capture=True).stdout
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("Invalid screenshot output")
    (dest / (name + ".png")).write_bytes(png)
    adb("shell", "uiautomator", "dump", "/data/local/tmp/kone-visual.xml", capture=True)
    xml = adb("exec-out", "cat", "/data/local/tmp/kone-visual.xml", capture=True).stdout
    (dest / (name + ".xml")).write_bytes(xml)
    requested = json.loads((RUNTIME / "preview-requested-state.json").read_text()) if (RUNTIME / "preview-requested-state.json").exists() else {}
    (dest / (name + ".json")).write_text(json.dumps({"requested_state": requested, "evidence": "Android 15 emulator screenshot; synthetic data", "baseline": (json.loads((WORK / "BASELINE.json").read_text())["development_baseline"]["commit"] if (WORK / "BASELINE.json").is_file() else None), "package": PACKAGE, "font_scale": requested.get("font_scale"), "physical_phone_acceptance": False, "build_evidence": json.loads((RUNTIME / "preview-build-evidence.json").read_text()) if (RUNTIME / "preview-build-evidence.json").exists() else None}, indent=2))
    print(dest / (name + ".png"))


def record(label, name, seconds):
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name) or not 1 <= seconds <= 30:
        raise ValueError("Use a filename stem and a recording duration of 1–30 seconds")
    wait_ready()
    dest = output_dir(label)
    adb("shell", "screenrecord", "--bit-rate", "2000000", "--time-limit", str(seconds), "/data/local/tmp/kone-visual-motion.mp4")
    foreground()
    adb("pull", "/data/local/tmp/kone-visual-motion.mp4", dest / (name + ".mp4"))
    print(dest / (name + ".mp4"))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=["start", "build", "install", "show", "capture", "matrix", "review", "tap", "record", "stop"])
    p.add_argument("--seconds", type=int, default=12)
    p.add_argument("--text", default="")
    p.add_argument("--visible", action="store_true")
    p.add_argument("--locale", choices=["en", "zh-CN"], default="en")
    p.add_argument("--dark", action="store_true")
    p.add_argument("--page", choices=["account", "dashboard", "settings", "tools", "city", "motion"], default="account")
    p.add_argument("--state", choices=["synced", "expired", "failed", "external", "reduced", "connected"], default="synced")
    p.add_argument("--font", type=float, default=1.15)
    p.add_argument("--label", default="candidate-device")
    p.add_argument("--name", default="account")
    a = p.parse_args()
    if a.command == "start": start(a.visible)
    elif a.command == "build": build()
    elif a.command == "install": install()
    elif a.command == "show": show(a.locale, a.dark, a.page, a.state, a.font)
    elif a.command == "record": record(a.label, a.name, a.seconds)
    elif a.command == "tap": tap_text(a.text)
    elif a.command == "capture": capture(a.label, a.name)
    elif a.command == "stop":
        owned_device()
        adb("emu", "kill")
    elif a.command == "review":
        build()
        start(a.visible)
        install()
        show(a.locale, a.dark, a.page, a.state, a.font)
        time.sleep(2)
        capture(a.label, a.name)
    elif a.command == "matrix":
        for locale in ["en", "zh-CN"]:
            for dark in [False, True]:
                name = f"account-{locale}-{'dark' if dark else 'light'}-320-font115"
                show(locale, dark, "account", "synced", 1.15)
                time.sleep(2)
                capture(a.label, name)


if __name__ == "__main__":
    main()
