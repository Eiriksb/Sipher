#!/usr/bin/env python3
"""Builds src/main/resources/sipher/catalog.json from the language-pack components in .cache/packs/out.

Each component directory has a manifest.json (written by the translation / speech build scripts). This script checks
every file against its manifest, assigns the GitHub release download URL, and writes the catalogue that is compiled
into the mod. The catalogue is the only thing that decides what the mod can download.

Usage: tools/packs/make_catalog.py [--out-dir .cache/packs/out]
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "Eiriksb/Sipher"
RELEASE = "models-v1"
HOSTS = ["github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"]

# code, native name, English name, speech component, X->en component, en->X component.
# Italian, Dutch and Polish use the shared Parakeet-v3 model: on the same FLEURS clips it made far fewer errors than the
# per-language FastConformer models (it 5.3% vs 11.3%, nl 9.9% vs 34.5%, pl 14.3% vs 19.1% WER), while the dedicated
# French and Danish models beat it (fr 6.4% vs 9.0%, da 7.3% vs 22.9%).
LANGUAGES = [
    ("es", "Español", "Spanish", "stt-es", "mt-es-en", "mt-en-es"),
    ("de", "Deutsch", "German", "stt-de", "mt-de-en", "mt-en-de"),
    ("fr", "Français", "French", "stt-fr", "mt-fr-en", "mt-en-fr"),
    ("it", "Italiano", "Italian", "stt-parakeet-v3", "mt-it-en", "mt-en-it"),
    ("nl", "Nederlands", "Dutch", "stt-parakeet-v3", "mt-nl-en", "mt-en-nl"),
    ("pt", "Português", "Portuguese", "stt-parakeet-v3", "mt-pt-en", "mt-en-pt"),
    ("pl", "Polski", "Polish", "stt-parakeet-v3", "mt-pl-en", "mt-en-pl"),
    ("ru", "Русский", "Russian", "stt-ru", "mt-ru-en", "mt-en-ru"),
    ("nb", "Norsk bokmål", "Norwegian", "stt-nb", "mt-nb-en", "mt-en-nb"),
    ("sv", "Svenska", "Swedish", "stt-sv", "mt-sv-en", "mt-en-sv"),
    ("da", "Dansk", "Danish", "stt-da", "mt-da-en", "mt-en-da"),
    ("fi", "Suomi", "Finnish", "stt-parakeet-v3", "mt-fi-en", "mt-en-fi"),
    ("zh", "中文", "Chinese", "stt-sensevoice", "mt-zh-en", "mt-en-zh"),
    ("ja", "日本語", "Japanese", "stt-sensevoice", "mt-ja-en", "mt-en-ja"),
    ("ko", "한국어", "Korean", "stt-sensevoice", "mt-ko-en", "mt-en-ko"),
]

# Manifest fields the mod reads for each component type (besides files/licence/attribution).
DETAIL_FIELDS = {
    "speech": ["asrKind", "roles", "languages", "languageHint", "languageHints"],
    "translation": ["source", "target", "targetToken"],
}


def asset_name(component: str, path: str) -> str:
    """GitHub release assets are a flat namespace (letters, digits, '.', '-', '_'): <component>__<path, '/' as '--'>."""
    return f"{component}__{path.replace('/', '--')}"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def load_component(directory: Path) -> dict:
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    component = {
        "type": manifest["type"],
        "license": manifest["license"],
        "attribution": manifest["attribution"],
        "files": [],
    }
    for key in DETAIL_FIELDS[manifest["type"]]:
        if key in manifest:
            component[key] = manifest[key]
    for file in manifest["files"]:
        local = directory / file["path"]
        if not local.is_file() or local.stat().st_size != file["size"] or sha256(local) != file["sha256"]:
            raise SystemExit(f"{directory.name}/{file['path']} does not match its manifest")
        component["files"].append({
            "path": file["path"],
            "url": f"https://github.com/{REPOSITORY}/releases/download/{RELEASE}/{asset_name(directory.name, file['path'])}",
            "sha256": file["sha256"],
            "size": file["size"],
        })
    return component


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=Path, default=ROOT / ".cache/packs/out")
    args = parser.parse_args()

    available = {d.name: d for d in sorted(args.out_dir.iterdir()) if (d / "manifest.json").is_file()}
    components, languages = {}, []
    for code, name, english, speech, to_english, from_english in LANGUAGES:
        needed = [speech, to_english, from_english]
        missing = [c for c in needed if c not in available]
        if missing:
            print(f"skipping {code}: missing {', '.join(missing)}", file=sys.stderr)
            continue
        for component in needed:
            if component not in components:
                components[component] = load_component(available[component])
        languages.append({"code": code, "name": name, "englishName": english,
                          "speech": speech, "toEnglish": to_english, "fromEnglish": from_english})

    catalog = {"schema": 1, "release": RELEASE, "hosts": HOSTS, "components": components, "languages": languages}
    target = ROOT / "src/main/resources/sipher/catalog.json"
    target.write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    total = sum(f["size"] for c in components.values() for f in c["files"])
    print(f"wrote {target.relative_to(ROOT)}: {len(languages)} languages, {len(components)} components, {total / 1e9:.2f} GB")
    for language in languages:
        size = sum(f["size"] for c in {language["speech"], language["toEnglish"], language["fromEnglish"]}
                   for f in components[c]["files"])
        print(f"  {language['code']}  {language['englishName']:<11} {size / 1e6:7.0f} MB")


if __name__ == "__main__":
    main()
