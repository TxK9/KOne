#!/usr/bin/env python3
"""Import the approved city artwork into a self-contained Android wallpaper library."""
import argparse
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import shutil
from PIL import Image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = root / "android/app/src/main/assets/wallpaper"
    inventory = json.loads((args.source / "dynamic-v13/distribution-inventory.json").read_text())
    scenes = json.loads((root / "desktop/src/main/resources/scenes/catalog.json").read_text())
    originals = json.loads((args.source / "scenes-v9.json").read_text())
    original_by_id = {scene["id"]: scene for scene in originals}
    approved = {entry["path"]: entry["sha256"] for entry in inventory["images"]}
    assert len(scenes) == 302 and len(approved) == 604
    output.mkdir(parents=True, exist_ok=True)
    (output / "images").mkdir(exist_ok=True)
    records = []
    for scene in scenes:
        original = original_by_id[scene["id"]]
        scene["assets"] = {}
        for theme in ("day", "night"):
            source = original["assets"][theme]
            assert source in approved
            target = "images/" + scene["id"] + "-" + theme + ".webp"
            scene["assets"][theme] = target
            records.append((source, target))

    def convert(record):
        source, target = record
        incoming = args.source / source
        assert hashlib.sha256(incoming.read_bytes()).hexdigest() == approved[source]
        destination = output / target
        with Image.open(incoming) as image:
            image = image.convert("RGB")
            image.thumbnail((960, 1920), Image.Resampling.LANCZOS)
            image.save(destination, "WEBP", quality=84, method=4)
        raw = destination.read_bytes()
        return {"path": target, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}

    with ThreadPoolExecutor(max_workers=4) as pool:
        files = list(pool.map(convert, records))
    for name in ("sky-v5.js", "environment-v10.js", "environment-v13.js", "weather-model-v11.js",
                 "motion-v13.js", "lighting-v13.js", "marine-v13.js", "weather-v13.js", "city-life-v13.js"):
        content = (args.source / name).read_text()
        # Limit the mobile surface and the one legacy fixed-rate life layer.
        content = content.replace('MAX_CANVAS_WIDTH = 720', 'MAX_CANVAS_WIDTH = 540').replace('720 / Math.max', '540 / Math.max').replace('720/Math.max', '540/Math.max')
        content = content.replace('lastPaint>=1000/30', 'lastPaint>=1000/18')
        (output / name).write_text("\n".join(line.rstrip() for line in content.splitlines()) + "\n")
    (output / "vendor").mkdir(exist_ok=True)
    shutil.copyfile(args.source / "vendor/suncalc-2.1.1.js", output / "vendor/suncalc-2.1.1.js")
    motion = json.loads((args.source / "motion-scenes-v9.json").read_text())
    life = json.loads((args.source / "city-life-scenes-v12.json").read_text())
    (output / "motion.json").write_text(json.dumps(motion, ensure_ascii=False, separators=(",", ":")))
    (output / "life.json").write_text(json.dumps(life, ensure_ascii=False, separators=(",", ":")))
    (output / "catalog.json").write_text(json.dumps(scenes, ensure_ascii=False, separators=(",", ":")))
    manifest = {"format": 1, "scenes": len(scenes), "images": len(files), "bytes": sum(f["bytes"] for f in files), "files": files}
    (output / "images.json").write_text(json.dumps(manifest, separators=(",", ":")))
    print(json.dumps({key: value for key, value in manifest.items() if key != "files"}))


if __name__ == "__main__":
    main()
