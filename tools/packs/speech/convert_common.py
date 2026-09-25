"""Shared helpers for convert_nemo.py and convert_whisper.py.

Everything here is deterministic given convert.json: upstream checkpoints, the sherpa-onnx export scripts and the
FLEURS validation clips are all fetched by pinned revision and checked by sha256 where upstream publishes one.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import statistics
import tarfile
import time
import unicodedata
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE = ROOT / ".cache" / "packs"
DEFAULT_WORK = CACHE / "work" / "speech-convert"
DEFAULT_OUT = CACHE / "out"
SAMPLE_RATE = 16_000


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def load_config() -> dict:
    return json.loads((HERE / "convert.json").read_text(encoding="utf-8"))


def select(cfg: dict, converter: str, ids: list[str] | None) -> list[dict]:
    comps = [c for c in cfg["components"] if c["converter"] == converter]
    if not ids:
        return [c for c in comps if not c.get("optional")]
    known = {c["id"]: c for c in comps}
    unknown = [i for i in ids if i not in known]
    if unknown:
        raise SystemExit(f"unknown {converter} component(s): {', '.join(unknown)}; known: {', '.join(known)}")
    return [known[i] for i in ids]


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def check_sha256(path: Path, expected: str | None) -> Path:
    if expected:
        actual = sha256_file(path)
        if actual != expected:
            raise SystemExit(f"{path}: sha256 {actual} != pinned {expected}")
    return Path(path)


def hf_file(repo: str, filename: str, revision: str, sha256: str | None = None, repo_type: str = "model") -> Path:
    """Downloads one file from the Hugging Face Hub at a pinned revision (cached under $HF_HOME)."""
    from huggingface_hub import hf_hub_download

    path = hf_hub_download(repo_id=repo, filename=filename, revision=revision, repo_type=repo_type)
    return check_sha256(Path(path), sha256)


def sherpa_script(cfg: dict, key: str, work: Path) -> Path:
    """Fetches an export script from k2-fsa/sherpa-onnx at the pinned commit and checks its sha256."""
    sherpa = cfg["sherpaOnnx"]
    script = sherpa["scripts"][key]
    target = work / "sherpa-onnx-scripts" / sherpa["commit"] / script["path"]
    if not target.is_file() or sha256_file(target) != script["sha256"]:
        target.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/{sherpa['commit']}/{script['path']}"
        log(f"fetching {url}")
        with urllib.request.urlopen(url) as response:
            target.write_bytes(response.read())
    return check_sha256(target, script["sha256"])


# --------------------------------------------------------------------------- licence / manifest


def license_text(cfg: dict, comp: dict, attribution: str) -> str:
    lic = cfg["licenses"][comp["license"]]
    up = comp["upstream"]
    lines = [
        f"Sipher speech component: {component_id(comp)}",
        "",
        f"Attribution: {attribution}",
        "",
    ]
    if lic.get("notice"):
        lines += [lic["notice"], ""]
    lines += [
        f"Model:    {comp['title']}",
        f"Authors:  {comp['authors']}",
        f"Source:   https://huggingface.co/{up['repo']} (revision {up['revision']})",
        f"Licence:  {lic['name']}",
        f"          {lic['url']}",
        "",
        "Changes: converted to ONNX/int8 by the Sipher project with the sherpa-onnx "
        f"v{cfg['sherpaOnnx']['version']} export scripts (graph export plus dynamic int8 weight quantisation); "
        "no retraining or other changes to the weights.",
    ]
    if lic.get("textFile"):
        lines += ["", "-" * 78, "", (HERE / lic["textFile"]).read_text(encoding="utf-8").rstrip()]
    return "\n".join(lines) + "\n"


def attribution(comp: dict, cfg: dict) -> str:
    lic = cfg["licenses"][comp["license"]]
    return (f"{comp['title']} by {comp['authors']} (https://huggingface.co/{comp['upstream']['repo']}), "
            f"{lic['name']}; converted to ONNX/int8 by the Sipher project.")


def spdx(license_key: str) -> str:
    return license_key if license_key in ("CC-BY-4.0", "Apache-2.0") else f"LicenseRef-{license_key}"


def component_id(comp: dict) -> str:
    """The id a build is published as (an optional alternative build names the component it can replace)."""
    return comp.get("packageAs", comp["id"])


def output_dir(comp: dict, out: Path, work: Path) -> Path:
    """Optional alternatives go to <work>/alternatives/ so they never end up in the catalogue by accident."""
    return (work / "alternatives" if comp.get("optional") else out) / comp["id"]


def package(cfg: dict, comp: dict, target: Path, roles: dict[str, tuple[Path, str]], evaluation: dict) -> dict:
    """Writes target/: the recogniser files (roles: role -> (source file, published name)), LICENSE (+ NOTICE when
    the licence requires one) and manifest.json."""
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    role_names = {}
    for role, (src, name) in roles.items():
        shutil.copyfile(src, target / name)
        role_names[role] = name
    attr = attribution(comp, cfg)
    (target / "LICENSE").write_text(license_text(cfg, comp, attr), encoding="utf-8")
    notice = cfg["licenses"][comp["license"]].get("notice")
    if notice:  # the NVIDIA Open Model License asks for the notice in a separate "Notice" text file
        (target / "NOTICE").write_text(notice + "\n", encoding="utf-8")

    files = []
    for p in sorted(target.iterdir()):
        files.append({"path": p.name, "sha256": sha256_file(p), "size": p.stat().st_size})
    manifest = {
        "id": component_id(comp),
        "type": "speech",
        "asrKind": comp["asrKind"],
        "roles": role_names,
        "languages": [comp["language"]],
        "languageHint": comp.get("languageHint", ""),
        "license": spdx(comp["license"]),
        "attribution": attr,
        "upstream": {"repo": comp["upstream"]["repo"], "revision": comp["upstream"]["revision"]},
        "files": files,
        "validation": evaluation["validation"],
        "rtf": evaluation["rtf"],
    }
    write_json(target / "manifest.json", manifest)
    return manifest


# --------------------------------------------------------------------------- FLEURS validation clips


def fleurs_clips(cfg: dict, lang: str, work: Path) -> list[dict]:
    """Returns cfg.validation.clips FLEURS test clips of 5-8 s for `lang` (e.g. "fr_fr"), as 16 kHz mono wav files.

    The test tarball is streamed and only the selected clips are kept (one recording per sentence).
    """
    import numpy as np
    import soundfile as sf

    v = cfg["validation"]
    folder = work / "fleurs" / v["revision"][:12] / lang
    index = folder / "clips.json"
    if index.is_file():
        clips = json.loads(index.read_text(encoding="utf-8"))
        if len(clips) >= v["clips"] and all((folder / c["file"]).is_file() for c in clips):
            return [dict(c, path=str(folder / c["file"])) for c in clips[: v["clips"]]]

    tsv = hf_file(v["dataset"], f"data/{lang}/{v['split']}.tsv", v["revision"], repo_type="dataset")
    rows = {}
    for line in tsv.read_text(encoding="utf-8").splitlines():
        cols = line.split("\t")
        if len(cols) < 6:
            continue
        seconds = int(cols[5]) / SAMPLE_RATE
        if v["minSeconds"] <= seconds <= v["maxSeconds"]:
            rows[cols[1]] = {"sentence": cols[0], "reference": cols[2], "seconds": round(seconds, 2)}

    from huggingface_hub import hf_hub_url

    url = hf_hub_url(v["dataset"], f"data/{lang}/audio/{v['split']}.tar.gz", repo_type="dataset",
                     revision=v["revision"])
    log(f"streaming {url} for {v['clips']} clips")
    folder.mkdir(parents=True, exist_ok=True)
    clips, sentences = [], set()
    with urllib.request.urlopen(url) as response, tarfile.open(fileobj=response, mode="r|gz") as tar:
        for member in tar:
            name = os.path.basename(member.name)
            row = rows.get(name)
            if not member.isfile() or row is None or row["sentence"] in sentences:
                continue
            audio, sr = sf.read(io.BytesIO(tar.extractfile(member).read()), dtype="float32", always_2d=True)
            audio = audio.mean(axis=1)
            if sr != SAMPLE_RATE:
                from scipy.signal import resample_poly
                g = np.gcd(sr, SAMPLE_RATE)
                audio = resample_poly(audio, SAMPLE_RATE // g, sr // g).astype("float32")
            sf.write(folder / name, audio, SAMPLE_RATE, subtype="PCM_16")
            sentences.add(row["sentence"])
            clips.append({"file": name, **row})
            if len(clips) >= v["clips"]:
                break
    write_json(index, clips)
    return [dict(c, path=str(folder / c["file"])) for c in clips]


def read_wav(path: str):
    import soundfile as sf

    audio, sr = sf.read(path, dtype="float32")
    assert sr == SAMPLE_RATE and audio.ndim == 1, (path, sr, audio.shape)
    return audio


# --------------------------------------------------------------------------- scoring


def normalize(text: str) -> list[str]:
    """Lower-cases and drops punctuation/symbols (rough WER normalisation, same for reference and hypothesis)."""
    text = unicodedata.normalize("NFKC", text).lower()
    text = "".join(" " if unicodedata.category(ch)[0] in "PS" else ch for ch in text)
    return text.split()


def edit_distance(ref: list[str], hyp: list[str]) -> int:
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1]


def wer(pairs: list[tuple[str, str]]) -> float:
    errors = words = 0
    for ref, hyp in pairs:
        r, h = normalize(ref), normalize(hyp)
        errors += edit_distance(r, h)
        words += len(r)
    return round(errors / max(words, 1), 4)


def sherpa_decode(recognizer, samples) -> str:
    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, samples)
    recognizer.decode_stream(stream)
    return stream.result.text.strip()


def evaluate(recognizer, clips: list[dict], language: str, manifest_clips: int) -> dict:
    """Transcribes the clips with a sherpa-onnx recogniser: hypotheses, rough WER, RTF and 5 s-utterance latency."""
    audio = [read_wav(c["path"]) for c in clips]
    sherpa_decode(recognizer, audio[0])  # warm-up (first run allocates)
    results, decode_s, audio_s = [], 0.0, 0.0
    for clip, samples in zip(clips, audio):
        t0 = time.perf_counter()
        hyp = sherpa_decode(recognizer, samples)
        dt = time.perf_counter() - t0
        decode_s += dt
        audio_s += len(samples) / SAMPLE_RATE
        results.append({"file": clip["file"], "reference": clip["reference"], "hypothesis": hyp,
                        "seconds": round(len(samples) / SAMPLE_RATE, 2), "decodeSeconds": round(dt, 3),
                        "wer": wer([(clip["reference"], hyp)])})
    five = audio[0][: 5 * SAMPLE_RATE]
    latencies = []
    for _ in range(5):
        t0 = time.perf_counter()
        sherpa_decode(recognizer, five)
        latencies.append(time.perf_counter() - t0)
    return {
        "validation": [{"language": language, "reference": r["reference"], "hypothesis": r["hypothesis"]}
                       for r in results[:manifest_clips]],
        "rtf": round(decode_s / audio_s, 4),
        "wer": wer([(r["reference"], r["hypothesis"]) for r in results]),
        "werManifestClips": wer([(r["reference"], r["hypothesis"]) for r in results[:manifest_clips]]),
        "latency5s": round(statistics.median(latencies), 3),
        "latency5sMin": round(min(latencies), 3),
        "clips": results,
    }


def report_line(comp: dict, manifest: dict, evaluation: dict) -> str:
    size = sum(f["size"] for f in manifest["files"]) / 1e6
    return (f"{comp['id']:12} {comp['asrKind']:16} {size:7.1f} MB  RTF {evaluation['rtf']:.3f}  "
            f"WER {evaluation['wer'] * 100:5.1f}% ({len(evaluation['clips'])} clips)  "
            f"5 s latency {evaluation['latency5s']:.2f} s")


def limit_threads(n: int) -> None:
    for var in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "NUMEXPR_MAX_THREADS"):
        os.environ.setdefault(var, str(n))


def use_work_tmp(work: Path) -> None:
    """Keeps NeMo's checkpoint extraction and ONNX temporaries in the work dir (often /tmp is RAM-backed)."""
    import tempfile

    tmp = work / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    os.environ["TMPDIR"] = str(tmp)
    tempfile.tempdir = str(tmp)
