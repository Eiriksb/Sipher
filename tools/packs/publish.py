#!/usr/bin/env python3
"""Uploads every file referenced by src/main/resources/sipher/catalog.json to the GitHub release it points at.

Files are taken from .cache/packs/out/<component>/, re-verified against the catalogue's SHA-256, staged under their
flat asset names and uploaded with the GitHub CLI (`gh`). Already-uploaded assets with the same size are skipped, so
the script can be re-run after an interrupted upload.

Usage: tools/packs/publish.py [--dry-run]
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
from make_catalog import REPOSITORY, asset_name  # noqa: E402


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def gh(*args: str, capture: bool = False) -> str:
    result = subprocess.run(["gh", *args], check=True, text=True, capture_output=capture)
    return result.stdout if capture else ""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    catalog = json.loads((ROOT / "src/main/resources/sipher/catalog.json").read_text(encoding="utf-8"))
    release = catalog["release"]
    out = ROOT / ".cache/packs/out"
    staging = ROOT / ".cache/packs/release" / release
    staging.mkdir(parents=True, exist_ok=True)

    wanted = {}
    for component, spec in catalog["components"].items():
        for file in spec["files"]:
            source = out / component / file["path"]
            if source.stat().st_size != file["size"] or sha256(source) != file["sha256"]:
                raise SystemExit(f"{component}/{file['path']} does not match the catalogue")
            name = asset_name(component, file["path"])
            assert file["url"].endswith("/" + name), file["url"]
            staged = staging / name
            if not staged.exists():
                os.link(source, staged)
            wanted[name] = file["size"]

    existing = {}
    try:
        listing = gh("release", "view", release, "--repo", REPOSITORY, "--json", "assets", capture=True)
        existing = {asset["name"]: asset["size"] for asset in json.loads(listing)["assets"]}
    except subprocess.CalledProcessError:
        if not args.dry_run:
            gh("release", "create", release, "--repo", REPOSITORY, "--title", "Language packs (models-v1)",
               "--notes-file", str(Path(__file__).parent / "RELEASE_NOTES.md"), "--latest=false")

    todo = [name for name, size in wanted.items() if existing.get(name) != size]
    total = sum(wanted[name] for name in todo)
    print(f"{len(wanted)} assets in catalogue, {len(todo)} to upload ({total / 1e9:.2f} GB)")
    if args.dry_run:
        return
    for name in todo:
        print(f"uploading {name} ({wanted[name] / 1e6:.0f} MB)")
        gh("release", "upload", release, str(staging / name), "--repo", REPOSITORY, "--clobber")


if __name__ == "__main__":
    main()
