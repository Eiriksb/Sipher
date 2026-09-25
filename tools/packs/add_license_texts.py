#!/usr/bin/env python3
"""Appends the full licence text to components whose licence requires shipping it (Apache-2.0 §4(a)).

The component build scripts write a LICENSE with the licence name, URL and attribution. Apache-2.0 additionally
requires giving recipients a copy of the licence itself, so this step appends tools/packs/licenses/Apache-2.0.txt and
updates the LICENSE entry in the component's manifest.json. It is idempotent; run it after building components and
before make_catalog.py.

Usage: tools/packs/add_license_texts.py [--out-dir .cache/packs/out]
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FULL_TEXTS = {"Apache-2.0": Path(__file__).parent / "licenses" / "Apache-2.0.txt"}
MARKER = "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=Path, default=ROOT / ".cache/packs/out")
    args = parser.parse_args()

    for manifest_path in sorted(args.out_dir.glob("*/manifest.json")):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        full_text = FULL_TEXTS.get(manifest["license"])
        if full_text is None:
            continue
        license_path = manifest_path.parent / "LICENSE"
        text = license_path.read_text(encoding="utf-8")
        if MARKER not in text:
            text = text.rstrip() + "\n\n" + "-" * 78 + "\n\n" + full_text.read_text(encoding="utf-8")
            license_path.write_text(text, encoding="utf-8")
            print(f"added full {manifest['license']} text to {manifest_path.parent.name}")
        data = license_path.read_bytes()
        for file in manifest["files"]:
            if file["path"] == "LICENSE":
                file["sha256"] = hashlib.sha256(data).hexdigest()
                file["size"] = len(data)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
