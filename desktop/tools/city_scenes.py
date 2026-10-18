#!/usr/bin/env python3
"""
Brings Codex's world-city scenes (country-preview/, on the codex/country-preview branch) into
KOne for Windows: each day and night painting cut to the handscroll's 3:4 from 8% down (the sky,
the skyline and the water; every scene's content lies between 8% and 70% of its height), kept at
full resolution as WebP, and one catalog.json with what the App draws them with.

    python3 desktop/tools/city_scenes.py --ref 484a556 --out desktop/src/main/resources/scenes

Reads the files straight out of git, so the 1.2 GB of PNGs need not be checked out. Needs Pillow
with WebP. Rerun when Codex changes the scenes; the output is deterministic.
"""
import argparse
import io
import json
import subprocess
from pathlib import Path

from PIL import Image

CROP_TOP = 0.08
RATIO = 0.75  # width / height of a handscroll painting
QUALITY = 86

# Fields of the scene list the App uses; the rest is Codex's review notes.
SCENE_FIELDS = ["id", "title", "description", "cityId", "cityLabel", "countryLabel", "cityZone", "cityLocation",
                "cameraBearing", "horizontalFov", "horizonY", "verticalScale", "skySafe", "skyExclusions",
                "cloudSkyBand", "waterType", "sunDemoDates"]
MOTION_FIELDS = ["waterPolygons", "waterExclusions", "lightRegions", "mistPolygons", "birds", "marine",
                 "cloudSkyBand", "skyExclusions"]
LIFE_DROP = {"reviewed", "reviewedAgainst", "reviewMethod", "reviewNote", "reviewContactSheet",
             "localIdentity", "effectCaption"}


def git_bytes(ref: str, path: str) -> bytes:
    return subprocess.run(["git", "show", f"{ref}:{path}"], check=True, capture_output=True).stdout


def country_code(flag: str) -> str:
    """🇯🇵 → JP: a flag is two regional indicator letters."""
    return "".join(chr(ord(c) - 0x1F1E6 + ord("A")) for c in flag if 0x1F1E6 <= ord(c) <= 0x1F1FF)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref", required=True, help="the git commit or branch holding country-preview/")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    out = Path(args.out)
    (out / "images").mkdir(parents=True, exist_ok=True)

    base = "country-preview/"
    scenes = json.loads(git_bytes(args.ref, base + "scenes-v9.json"))
    motion = json.loads(git_bytes(args.ref, base + "motion-scenes-v9.json"))["scenes"]
    life = json.loads(git_bytes(args.ref, base + "city-life-scenes-v12.json"))["scenes"]

    catalog = []
    for scene in scenes:
        entry = {key: scene[key] for key in SCENE_FIELDS if key in scene}
        entry["country"] = country_code(scene["flag"])
        crop = None
        for kind in ("day", "night"):
            image = Image.open(io.BytesIO(git_bytes(args.ref, base + scene["assets"][kind]))).convert("RGB")
            width, height = image.size
            top = round(CROP_TOP * height)
            tall = min(round(width / RATIO), height - top)
            image.crop((0, top, width, top + tall)).save(out / "images" / f"{scene['id']}-{kind}.webp", "WEBP", quality=QUALITY, method=6)
            # Where the cut lies, as fractions of the original, to place the scene's own coordinates.
            crop = {"top": top / height, "height": tall / height, "aspect": width / height}
        entry["crop"] = crop
        entry["motion"] = {key: value for key, value in motion.get(scene["id"], {}).items() if key in MOTION_FIELDS}
        entry["life"] = {key: value for key, value in life.get(scene["id"], {}).items() if key not in LIFE_DROP}
        catalog.append(entry)
        print(scene["id"], entry["country"])

    (out / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(len(catalog), "scenes")


if __name__ == "__main__":
    main()
