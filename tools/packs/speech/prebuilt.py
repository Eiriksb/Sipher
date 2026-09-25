#!/usr/bin/env python3
"""Package ready-made sherpa-onnx speech recognisers as Sipher speech components.

Every component in ``prebuilt.json`` is an existing sherpa-onnx export published
upstream (Hugging Face repository at a pinned commit, or a GitHub release
tarball pinned by its sha256).  This script

  fetch     downloads the upstream files into the work directory and checks
            every sha256 / size against ``prebuilt.json``;
  build     copies the files the recogniser needs into ``<out>/<id>/``, writes
            LICENSE and manifest.json;
  validate  transcribes FLEURS test clips (google/fleurs, CC-BY-4.0) with
            sherpa-onnx, configured exactly like the in-game factory
            (SpeechRecognizer.java), and records reference/hypothesis pairs,
            a rough WER/CER and the real-time factor in the manifest;
  all       fetch + build + validate (the default).

Nothing is ever uploaded.  Requirements: Python >= 3.10 with
``sherpa-onnx==1.13.8 numpy soundfile`` (only ``validate`` needs them).

Examples:
  python prebuilt.py                       # everything
  python prebuilt.py build --only stt-es   # one component, no validation
  python prebuilt.py validate --threads 2 --clips 10
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import tarfile
import time
import unicodedata
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]
DEFAULT_CONFIG = HERE / "prebuilt.json"
DEFAULT_WORK = REPO_ROOT / ".cache" / "packs" / "work" / "speech-prebuilt"
DEFAULT_OUT = REPO_ROOT / ".cache" / "packs" / "out"

USER_AGENT = "sipher-pack-tools/1 (+https://github.com/eiriksb/sipher)"
CHUNK = 1 << 20


# --------------------------------------------------------------------------- utils

def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def verify(path: Path, sha256: str | None, size: int | None) -> bool:
    if not path.is_file():
        return False
    if size is not None and path.stat().st_size != size:
        return False
    return sha256 is None or sha256_file(path) == sha256


def open_url(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    return urllib.request.urlopen(req, timeout=120)


def download(url: str, dest: Path, sha256: str | None = None, size: int | None = None,
             retries: int = 3) -> Path:
    """Downloads ``url`` to ``dest`` unless a verified copy is already there."""
    if verify(dest, sha256, size):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    for attempt in range(1, retries + 1):
        try:
            log(f"  download {url}")
            h = hashlib.sha256()
            with open_url(url) as resp, open(part, "wb") as out:
                for block in iter(lambda: resp.read(CHUNK), b""):
                    out.write(block)
                    h.update(block)
            got = h.hexdigest()
            if sha256 is not None and got != sha256:
                raise ValueError(f"sha256 mismatch for {url}: expected {sha256}, got {got}")
            if size is not None and part.stat().st_size != size:
                raise ValueError(f"size mismatch for {url}: expected {size}, got {part.stat().st_size}")
            part.replace(dest)
            return dest
        except Exception as e:  # noqa: BLE001 - retry any network/IO failure
            if attempt == retries or isinstance(e, ValueError):
                part.unlink(missing_ok=True)
                raise
            log(f"  retry {attempt}/{retries} after: {e}")
            time.sleep(2 * attempt)
    raise AssertionError("unreachable")


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


# --------------------------------------------------------------------------- fetch

def upstream_dir(work: Path, comp: dict) -> Path:
    return work / "upstream" / comp["id"]


def upstream_url(comp: dict) -> str:
    up = comp["upstream"]
    if up["type"] == "huggingface":
        return f"https://huggingface.co/{up['repo']}"
    return up["archive"]["url"]


def upstream_revision(comp: dict) -> str:
    up = comp["upstream"]
    if up["type"] == "huggingface":
        return up["revision"]
    return "sha256:" + up["archive"]["sha256"]


def all_sources(comp: dict) -> list[dict]:
    """Every upstream file to fetch: runtime files, example wavs and an upstream licence file."""
    items = list(comp["files"]) + list(comp.get("testWavs", []))
    text = comp["license"].get("text")
    if text and "src" in text:
        items.append({"src": text["src"], "sha256": text["sha256"], "size": text.get("size")})
    return items


def fetch(comp: dict, work: Path) -> None:
    log(f"[{comp['id']}] fetch")
    up = comp["upstream"]
    dest = upstream_dir(work, comp)
    sources = all_sources(comp)

    if up["type"] == "huggingface":
        for f in sources:
            url = f"https://huggingface.co/{up['repo']}/resolve/{up['revision']}/{f['src']}"
            download(url, dest / f["src"], f["sha256"], f.get("size"))
    elif up["type"] == "github-release":
        missing = [f for f in sources if not verify(dest / f["src"], f["sha256"], f.get("size"))]
        if missing:
            arc = up["archive"]
            archive = download(arc["url"], work / "archives" / Path(arc["url"]).name,
                               arc["sha256"], arc.get("size"))
            wanted = {f"{arc['root']}/{f['src']}": f for f in missing}
            with tarfile.open(archive, "r:*") as tar:
                for member in tar:
                    f = wanted.pop(os.path.normpath(member.name), None)  # names may start with "./"
                    if f is None:
                        continue
                    target = dest / f["src"]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as src, open(target, "wb") as out:
                        shutil.copyfileobj(src, out, CHUNK)
            if wanted:
                raise FileNotFoundError(f"not in {archive.name}: {sorted(wanted)}")
    else:
        raise ValueError(f"unknown upstream type {up['type']}")

    # Licence texts that live outside the model repository (e.g. FunASR's MODEL_LICENSE).
    text = comp["license"].get("text")
    if text and "url" in text:
        download(text["url"], work / "licenses" / text["file"], text["sha256"], text.get("size"))

    for f in sources:
        if not verify(dest / f["src"], f["sha256"], f.get("size")):
            raise ValueError(f"[{comp['id']}] {f['src']} does not match prebuilt.json")


# --------------------------------------------------------------------------- build

def license_text(comp: dict, work: Path) -> str:
    lic = comp["license"]
    up = comp["upstream"]
    lines = [
        f"Sipher speech component: {comp['id']}",
        "",
        f"Attribution: {comp['attribution']}",
        "",
        f"Model:    {comp['model']['name']}",
        f"Authors:  {comp['model']['authors']}",
        f"Source:   {comp['model']['url']}",
        f"Export:   {upstream_url(comp)} (revision {upstream_revision(comp)})",
        f"Licence:  {lic['name']} ({lic['spdx']})",
        f"          {lic['url']}",
        "",
        "Changes: " + comp.get("changes",
                               "none to the model weights; the upstream sherpa-onnx ONNX export was "
                               "repackaged by the Sipher project (file selection only)."),
    ]
    if up["type"] == "github-release":
        lines.append(f"Release page: {up['page']}")
    text = lic.get("text")
    if text:
        body_path = (upstream_dir(work, comp) / text["src"]) if "src" in text else (work / "licenses" / text["file"])
        lines += ["", "-" * 78, "", body_path.read_text(encoding="utf-8").rstrip()]
    return "\n".join(lines) + "\n"


def manifest_path(out: Path, comp: dict) -> Path:
    return out / comp["id"] / "manifest.json"


def build(comp: dict, work: Path, out: Path) -> None:
    log(f"[{comp['id']}] build")
    target = out / comp["id"]
    previous = {}
    if manifest_path(out, comp).is_file():
        previous = json.loads(manifest_path(out, comp).read_text(encoding="utf-8"))
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)

    src_dir = upstream_dir(work, comp)
    for f in comp["files"]:
        shutil.copyfile(src_dir / f["src"], target / f["path"])
    (target / "LICENSE").write_text(license_text(comp, work), encoding="utf-8")

    files = []
    for name in sorted(p.name for p in target.iterdir() if p.name != "manifest.json"):
        p = target / name
        files.append({"path": name, "sha256": sha256_file(p), "size": p.stat().st_size})
    for f in comp["files"]:  # copied files must still match the pinned upstream hash
        entry = next(x for x in files if x["path"] == f["path"])
        if entry["sha256"] != f["sha256"]:
            raise ValueError(f"[{comp['id']}] {f['path']} changed while copying")

    manifest = {
        "id": comp["id"],
        "type": "speech",
        "asrKind": comp["asrKind"],
        "roles": {f["role"]: f["path"] for f in comp["files"]},
        "languages": comp["languages"],
        **({"supportedLanguages": comp["supportedLanguages"]} if "supportedLanguages" in comp else {}),
        "languageHint": comp.get("languageHint", ""),
        "license": comp["license"]["spdx"],
        "attribution": comp["attribution"],
        "upstream": {"url": upstream_url(comp), "revision": upstream_revision(comp)},
        "files": files,
        "validation": [],
        "rtf": None,
    }
    # Keep earlier validation results when the model files did not change.
    report = work / "validation" / f"{comp['id']}.json"
    if report.is_file():
        rep = json.loads(report.read_text(encoding="utf-8"))
        if rep.get("modelSha256") == model_fingerprint(comp):
            manifest["validation"] = rep["manifestValidation"]
            manifest["rtf"] = rep["rtf"]
    elif previous.get("validation") and previous.get("files") == files:
        manifest["validation"] = previous["validation"]
        manifest["rtf"] = previous.get("rtf")
    write_json(manifest_path(out, comp), manifest)


def model_fingerprint(comp: dict) -> str:
    h = hashlib.sha256()
    for f in sorted(comp["files"], key=lambda x: x["path"]):
        h.update(f"{f['role']}={f['sha256']};".encode())
    h.update(comp["asrKind"].encode())
    return h.hexdigest()


# --------------------------------------------------------------------------- FLEURS

def fleurs_clips(cfg: dict, lang_cfg: str, work: Path, count: int,
                 min_s: float, max_s: float) -> list[dict]:
    """Returns ``count`` FLEURS test clips of min_s..max_s seconds (distinct sentences).

    The test archive is streamed and the download stops as soon as enough clips
    were found, so only a prefix of the ~500 MB archive is read.  Selection is
    deterministic for a pinned dataset revision.
    """
    root = work / "fleurs" / lang_cfg
    index = root / f"clips-{count}-{min_s:g}-{max_s:g}.json"
    if index.is_file():
        clips = json.loads(index.read_text(encoding="utf-8"))
        if all((root / c["file"]).is_file() for c in clips):
            return clips

    base = f"https://huggingface.co/datasets/{cfg['dataset']}/resolve/{cfg['revision']}/data/{lang_cfg}"
    tsv = download(f"{base}/{cfg['split']}.tsv", root / f"{cfg['split']}.tsv")
    rows = {}
    for line in tsv.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 7:
            continue
        sid, fname, raw, norm, _, samples, gender = parts[:7]
        rows[fname] = {"sentenceId": sid, "file": fname, "reference": raw, "normalized": norm,
                       "seconds": int(samples) / 16000.0, "gender": gender}

    clips, seen = [], set()
    log(f"  stream FLEURS {lang_cfg}/{cfg['split']} for {count} clips of {min_s:g}-{max_s:g} s")
    with open_url(f"{base}/audio/{cfg['split']}.tar.gz") as resp, tarfile.open(fileobj=resp, mode="r|gz") as tar:
        for member in tar:
            row = rows.get(os.path.basename(member.name))
            if (row is None or not member.isfile() or row["sentenceId"] in seen
                    or not (min_s <= row["seconds"] <= max_s)):
                continue
            data = tar.extractfile(member).read()
            root.mkdir(parents=True, exist_ok=True)
            (root / row["file"]).write_bytes(data)
            seen.add(row["sentenceId"])
            clips.append(row)
            if len(clips) >= count:
                break
    if len(clips) < count:
        log(f"  warning: only {len(clips)} clips found for {lang_cfg}")
    write_json(index, clips)
    return clips


def load_audio(path: Path):
    import numpy as np
    import soundfile as sf

    samples, rate = sf.read(str(path), dtype="float32", always_2d=True)
    samples = samples.mean(axis=1)
    if rate != 16000:  # linear resampling is plenty for a smoke test; FLEURS is already 16 kHz
        n = int(round(len(samples) * 16000 / rate))
        samples = np.interp(np.linspace(0, len(samples) - 1, n), np.arange(len(samples)), samples)
    return np.ascontiguousarray(samples, dtype=np.float32)


# --------------------------------------------------------------------------- scoring

def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    text = "".join(" " if unicodedata.category(ch)[0] in "PS" else ch for ch in text)
    return " ".join(text.split())


def edit_distance(a: list, b: list) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def units(text: str, metric: str) -> list[str]:
    text = normalize(text)
    return list(text.replace(" ", "")) if metric == "cer" else text.split()


# --------------------------------------------------------------------------- validate

def make_recognizer(kind: str, model_dir: Path, roles: dict, language: str, threads: int):
    """Mirror of SpeechRecognizer.create() in the mod (same sherpa-onnx settings)."""
    import sherpa_onnx

    def f(role: str) -> str:
        return str(model_dir / roles[role])

    common = dict(tokens=f("tokens"), num_threads=threads, debug=False, decoding_method="greedy_search")
    if kind == "sense_voice":
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(model=f("model"), language=language,
                                                              use_itn=True, **common)
    if kind == "nemo_ctc":
        return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f("model"), **common)
    if kind in ("nemo_transducer", "transducer"):
        # Java leaves modelType empty for plain "transducer"; Python's default is "transducer".
        model_type = "nemo_transducer" if kind == "nemo_transducer" else ""
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=f("encoder"), decoder=f("decoder"), joiner=f("joiner"), model_type=model_type, **common)
    raise ValueError(f"validation does not support asrKind {kind}")


def transcribe(recognizer, samples) -> str:
    stream = recognizer.create_stream()
    stream.accept_waveform(16000, samples)
    recognizer.decode_stream(stream)
    return stream.result.text.strip()


def validate(comp: dict, cfg: dict, work: Path, out: Path, threads: int, count: int, keep: int) -> dict:
    import sherpa_onnx

    log(f"[{comp['id']}] validate with sherpa-onnx {sherpa_onnx.__version__}, {threads} threads")
    model_dir = out / comp["id"]
    roles = {f["role"]: f["path"] for f in comp["files"]}
    recognizers = {}

    def recognizer_for(hint: str):
        if hint not in recognizers:
            t0 = time.perf_counter()
            recognizers[hint] = make_recognizer(comp["asrKind"], model_dir, roles, hint, threads)
            log(f"  loaded (hint={hint!r}) in {time.perf_counter() - t0:.1f} s")
        return recognizers[hint]

    per_lang, manifest_rows = {}, []
    audio_s = decode_s = 0.0
    for lang in comp["languages"]:
        metric = cfg["metric"].get(lang, "wer")
        clips = fleurs_clips(cfg, cfg["configs"][lang], work, count, cfg["minSeconds"], cfg["maxSeconds"])
        rec = recognizer_for(comp.get("languageHint", ""))
        root = work / "fleurs" / cfg["configs"][lang]
        transcribe(rec, load_audio(root / clips[0]["file"]))  # warm-up, not timed
        errors = total = 0
        rows = []
        for clip in clips:
            samples = load_audio(root / clip["file"])
            t0 = time.perf_counter()
            hyp = transcribe(rec, samples)
            dt = time.perf_counter() - t0
            ref_u, hyp_u = units(clip["reference"], metric), units(hyp, metric)
            err = edit_distance(ref_u, hyp_u)
            errors += err
            total += len(ref_u)
            audio_s += len(samples) / 16000.0
            decode_s += dt
            rows.append({"file": clip["file"], "seconds": round(len(samples) / 16000.0, 2),
                         "decodeSeconds": round(dt, 3), "reference": clip["reference"],
                         "hypothesis": hyp, "errors": err, "refUnits": len(ref_u)})
        entry = {"metric": metric, "errorRate": round(errors / max(total, 1), 4), "clips": rows,
                 "fleursConfig": cfg["configs"][lang]}
        # Explicit language hints as a comparison for multilingual models.
        if comp["asrKind"] == "sense_voice":
            hinted = recognizer_for(lang)
            e2 = sum(edit_distance(units(r["reference"], metric),
                                   units(transcribe(hinted, load_audio(root / r["file"])), metric)) for r in rows)
            entry["errorRateWithHint"] = round(e2 / max(total, 1), 4)
        per_lang[lang] = entry
        log(f"  {lang}: {metric.upper()} {entry['errorRate']:.3f} over {len(rows)} clips")
        for r in rows[:keep]:
            manifest_rows.append({"language": lang, "reference": r["reference"], "hypothesis": r["hypothesis"]})

    # The upstream example wavs (no references) as an extra smoke test.
    smoke = []
    for wav in comp.get("testWavs", []):
        path = upstream_dir(work, comp) / wav["src"]
        if path.is_file():
            smoke.append({"file": wav["src"], "hypothesis": transcribe(recognizer_for(comp.get("languageHint", "")),
                                                                       load_audio(path))})

    rtf = round(decode_s / audio_s, 4) if audio_s else None
    report = {
        "id": comp["id"], "sherpaOnnx": sherpa_onnx.__version__, "threads": threads,
        "modelSha256": model_fingerprint(comp), "rtf": rtf,
        "audioSeconds": round(audio_s, 2), "decodeSeconds": round(decode_s, 3),
        "languages": per_lang, "testWavs": smoke, "manifestValidation": manifest_rows,
        "dataset": {k: cfg[k] for k in ("dataset", "revision", "split", "license")},
    }
    write_json(work / "validation" / f"{comp['id']}.json", report)

    mpath = manifest_path(out, comp)
    manifest = json.loads(mpath.read_text(encoding="utf-8"))
    manifest["validation"] = manifest_rows
    manifest["rtf"] = rtf
    write_json(mpath, manifest)
    flag = "  <-- above 0.3, too slow for in-game use on this CPU" if rtf and rtf > 0.3 else ""
    log(f"  RTF {rtf} ({threads} threads, {audio_s:.0f} s audio){flag}")
    return report


# --------------------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", default="all", choices=["fetch", "build", "validate", "all", "list"])
    ap.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    ap.add_argument("--work", type=Path, default=DEFAULT_WORK, help="downloads and FLEURS clips")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT, help="component output root")
    ap.add_argument("--only", action="append", help="component id (repeatable)")
    ap.add_argument("--threads", type=int, default=2, help="sherpa-onnx threads for validation (game uses 2)")
    ap.add_argument("--clips", type=int, default=10, help="FLEURS clips per language for WER/RTF")
    ap.add_argument("--keep", type=int, default=3, help="reference/hypothesis pairs stored per language")
    args = ap.parse_args()

    cfg = json.loads(args.config.read_text(encoding="utf-8"))
    comps = [c for c in cfg["components"] if not args.only or c["id"] in args.only]
    if args.only and len(comps) != len(set(args.only)):
        ap.error(f"unknown component in {args.only}")

    if args.command == "list":
        for c in comps:
            print(f"{c['id']:18} {c['asrKind']:16} {','.join(c['languages']):12} {c['license']['spdx']}")
        return 0
    for c in comps:
        if args.command in ("fetch", "all"):
            fetch(c, args.work)
        if args.command in ("build", "all"):
            build(c, args.work, args.out)
        if args.command in ("validate", "all"):
            validate(c, cfg["validation"], args.work, args.out, args.threads, args.clips, args.keep)
    return 0


if __name__ == "__main__":
    sys.exit(main())
