#!/usr/bin/env python3
"""
Builds Sipher translation components from components.json:

    <out>/<id>/encoder_model.onnx, decoder_model_merged.onnx   int8 ONNX (see export.py)
               source.spm, vocab.json [, target_vocab.json]     upstream tokenizer files
               config.json [, generation_config.json]           upstream configs
               README.md                                        upstream model card
               LICENSE                                          licence + attribution
               manifest.json                                    id, licence, upstream, files, samples, speed

For each component it
  1. downloads the upstream repo at the pinned revision and checks the licence in the model card
     metadata against components.json (missing or non-commercial licences stop that component);
  2. checks the tokenizer is something Sipher's Java SentencePiece port supports (unigram, no
     byte fallback), that vocab.json covers the source pieces (see "sourceVocab") and that the
     target-language token exists;
  3. exports and quantises to int8 ONNX (export.py);
  4. verifies with reference_translate.py (the Python mirror of the Java decoder): the pure-Python
     tokenizer must match the sentencepiece library (and MarianTokenizer ids) exactly, the sample
     sentences must translate to non-empty, non-looping output, and it times ms/sentence on one
     thread. X->en components translate the English samples with their en->X counterpart first and
     must keep a minimum word overlap with the English originals.

Usage:
    python build.py                       # every component
    python build.py mt-en-es mt-es-en     # selected components (en->X counterparts are added if missing)
    python build.py --jobs 3 --threads 2  # export several components in parallel
    python build.py --verify-only mt-en-es
Paths default to <repo>/.cache/packs/{out,work/translation,hf}; HF_HOME is set to the latter.
"""
import argparse
import collections
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
CACHE = REPO / ".cache" / "packs"
os.environ.setdefault("HF_HOME", str(CACHE / "hf"))
os.environ["DISABLE_SAFETENSORS_CONVERSION"] = "true"  # never ask the Hub to convert .bin weights
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")

sys.dont_write_bytecode = True  # keep the tools directory free of __pycache__
sys.path.insert(0, str(HERE))
from reference_translate import MarianOnnx, SpmModel, split_sentences  # noqa: E402

UPSTREAM_FILES = ["config.json", "generation_config.json", "tokenizer_config.json", "special_tokens_map.json",
                  "source.spm", "target.spm", "vocab.json", "target_vocab.json", "README.md"]
# Everything a component directory ships (manifest.json aside), in manifest order.
COMPONENT_FILES = ["encoder_model.onnx", "decoder_model_merged.onnx", "source.spm", "vocab.json",
                   "target_vocab.json", "config.json", "generation_config.json", "LICENSE", "README.md"]
# Hugging Face model-card licence ids that may be redistributed in the mod's downloads.
ALLOWED_LICENSES = {"apache-2.0": "Apache-2.0", "mit": "MIT", "cc-by-4.0": "CC-BY-4.0", "cc-by-sa-4.0": "CC-BY-SA-4.0"}
SIZE_WARNING_MB = 350
TIMING_REPEATS = 5  # fastest run per input: robust against other load on the machine
MIN_ROUND_TRIP_OVERLAP = 0.25  # a broken model scores near 0; working pairs score roughly 0.5-0.9

# Varied text for the tokenizer comparison (the component's sample inputs are added to it).
TOKENIZER_SENTENCES = [
    "", "   ", "a", "  leading and   inner   spaces  ", "tab\tand\nnewline\r\nCRLF",
    "Ｆｕｌｌｗｉｄｔｈ ＡＢＣ １２３", "ﬁ ﬂ ligatures ½ ² ™ ℃ Ⅻ", "café naïve Ångström — “quotes” … «guillemets»",
    "emoji 🎮⛏️🧱 and ❤️", "zero​width‍ joiner ﻿bom", "nbsp here ideographic　space",
    "日本語のテキストです。", "中文文本测试，你好！", "한국어 텍스트입니다.", "Русский текст, ёжик в тумане.",
    "Ελληνικά γράμματα", "Español: ¿Dónde está el cofre? ¡Cuidado!", "Deutsch: Größe, Straße, ÄÖÜ",
    "Polski: zażółć gęślą jaźń", "Norsk: blåbærsyltetøy, Æøå", "Suomi: hääyöaie, äö", "Português: não, coração",
    "URLs https://example.com/a_b?c=d&e=f #tag @user", "numbers 3.14159 1,000,000 42%", "x" * 300,
    # runs of one letter produce exact score ties in Viterbi (float vs double accumulation matters)
    "hahahahahahahahahahaha lol", "Nooooooooooooooooo!!!!!!!!", "gggggggggggggggggggg wp",
]


class ComponentError(Exception):
    pass


def load_components():
    return json.loads((HERE / "components.json").read_text(encoding="utf-8"))


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8")) if Path(path).exists() else {}


# ---------------------------------------------------------------------------------------------
# 1-2. download + licence + tokenizer checks
# ---------------------------------------------------------------------------------------------
def download(component, hf_dir):
    from huggingface_hub import HfApi, snapshot_download
    info = HfApi().model_info(component["repo"], revision=component["revision"])
    available = {s.rfilename for s in info.siblings}
    weights = "model.safetensors" if "model.safetensors" in available else "pytorch_model.bin"
    if weights not in available:
        raise ComponentError(f"{component['repo']} has no PyTorch weights")
    snapshot_download(component["repo"], revision=component["revision"], local_dir=hf_dir,
                      allow_patterns=[f for f in UPSTREAM_FILES if f in available] + [weights])


def check_license(component, hf_dir):
    from huggingface_hub import ModelCard
    card = ModelCard.load(hf_dir / "README.md")
    license_id = (card.data.license or "") if card.data else ""
    if isinstance(license_id, list):
        license_id = ",".join(license_id)
    spdx = ALLOWED_LICENSES.get(license_id.lower())
    if spdx is None:
        raise ComponentError(f"model card licence is {license_id or 'missing'!r}: not redistributable, not building")
    if spdx != component["license"]:
        raise ComponentError(f"model card licence {spdx} differs from components.json ({component['license']})")


def check_tokenizer(component, hf_dir):
    """Sipher's Java SentencePiece port handles unigram models without byte fallback."""
    try:
        model = SpmModel(hf_dir / "source.spm")
    except NotImplementedError as e:
        raise ComponentError(f"source.spm: {e}")
    if model.byte_fallback:
        raise ComponentError("source.spm uses byte_fallback, which the Java tokenizer does not implement")
    tokenizer_config = read_json(hf_dir / "tokenizer_config.json")
    if tokenizer_config.get("separate_vocabs") and not (hf_dir / "target_vocab.json").exists():
        raise ComponentError("separate_vocabs model without target_vocab.json")
    vocab = read_json(hf_dir / "vocab.json")
    # Some separate-vocabulary checkpoints (the tc-big en-ko/ko-en conversions) ship only the target
    # vocabulary as vocab.json; source pieces then map to the wrong ids and the model outputs nonsense.
    normal = [p for p, _, kind in model.pieces if kind == SpmModel.NORMAL]
    coverage = sum(p in vocab for p in normal) / len(normal)
    if coverage < 0.9 and component.get("sourceVocab") != "source.spm":
        raise ComponentError(f"vocab.json covers only {coverage:.1%} of the source.spm pieces (target-only vocabulary?); "
                             "set \"sourceVocab\": \"source.spm\" if the encoder uses SentencePiece ids")
    token = component.get("targetToken")
    if token and token not in vocab:
        raise ComponentError(f"target token {token} is not in vocab.json")
    return {"modelType": "UNIGRAM", "normalizer": model.normalizer_name, "byteFallback": model.byte_fallback,
            "sourcePiecesInVocab": round(coverage, 4)}


def source_spm_vocab(hf_dir):
    """vocab.json for models whose encoder ids are the SentencePiece ids of source.spm (sourceVocab option).
    The upstream vocab.json becomes target_vocab.json and must itself be target.spm's id mapping."""
    source = SpmModel(hf_dir / "source.spm")
    target = SpmModel(hf_dir / "target.spm")
    upstream = read_json(hf_dir / "vocab.json")
    mismatched = [p for i, (p, _, _) in enumerate(target.pieces) if upstream.get(p) != i]
    if mismatched:
        raise ComponentError(f"sourceVocab: upstream vocab.json is not target.spm's id mapping ({len(mismatched)} pieces differ)")
    vocab = {p: i for i, (p, _, _) in enumerate(source.pieces)}
    for piece, i in upstream.items():  # ids beyond the SentencePiece model, such as <pad>
        if i >= len(source.pieces):
            vocab[piece] = i
    return vocab


# ---------------------------------------------------------------------------------------------
# 3. export + assemble
# ---------------------------------------------------------------------------------------------
def license_text(component, licenses):
    lic = licenses[component["license"]]
    repo = component["repo"]
    lines = [
        f"{lic['name']} ({component['license']})",
        lic["url"],
        "",
        attribution(component),
        "",
        f"Upstream model: https://huggingface.co/{repo} (revision {component['revision']})",
        f"Authors: {component['authors']}",
        f"Licence: {component['license']} ({lic['url']})",
        "Modifications: the PyTorch weights were exported to ONNX (encoder and merged KV-cache decoder) and",
        "quantised to int8 by the Sipher project (https://github.com/Eiriksb/Sipher).",
    ]
    if component.get("sourceVocab") == "source.spm":
        lines += ["vocab.json was rebuilt from the ids of source.spm (the upstream vocab.json, which only covers the",
                  "target vocabulary, is target_vocab.json). The other tokenizer and configuration files are unmodified",
                  "upstream copies; README.md is the upstream model card."]
    else:
        lines += ["The tokenizer, vocabulary and configuration files are unmodified upstream copies; README.md is the",
                  "upstream model card."]
    if component["license"] == "CC-BY-SA-4.0":
        lines += ["", "The ONNX files in this directory are an adaptation of the upstream model and are distributed under",
                  f"CC-BY-SA-4.0 ({lic['url']})."]
    if component["license"].startswith("CC-"):
        lines += ["", "The material is provided as-is, without warranties of any kind."]
    return "\n".join(lines) + "\n"


def attribution(component):
    return (f"{component['repo']} by {component['authors']}, licensed under {component['license']}; "
            f"converted to ONNX/int8 by the Sipher project.")


def build_export(component, dirs, licenses, log=print):
    from export import export
    work = dirs["work"] / component["id"]
    hf_dir = work / "hf"
    out = dirs["out"] / component["id"]
    work.mkdir(parents=True, exist_ok=True)
    log(f"[{component['id']}] download {component['repo']}@{component['revision'][:10]}")
    download(component, hf_dir)
    check_license(component, hf_dir)
    tokenizer = check_tokenizer(component, hf_dir)
    log(f"[{component['id']}] export + int8 quantisation")
    started = time.time()
    stats = export(hf_dir, work / "onnx", work / "fp32", log=work / "optimum-export.log")
    log(f"[{component['id']}] exported in {time.time() - started:.0f} s {stats}")

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    for name in ("encoder_model.onnx", "decoder_model_merged.onnx"):
        shutil.move(work / "onnx" / name, out / name)
    for name in ("source.spm", "vocab.json", "target_vocab.json", "config.json", "generation_config.json", "README.md"):
        if (hf_dir / name).exists():
            shutil.copy2(hf_dir / name, out / name)
    if component.get("sourceVocab") == "source.spm":
        vocab = source_spm_vocab(hf_dir)
        shutil.copy2(hf_dir / "vocab.json", out / "target_vocab.json")
        (out / "vocab.json").write_text(json.dumps(vocab, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out / "LICENSE").write_text(license_text(component, licenses), encoding="utf-8")
    (work / "tokenizer.json").write_text(json.dumps(tokenizer, indent=1))


# ---------------------------------------------------------------------------------------------
# 4. verification + manifest
# ---------------------------------------------------------------------------------------------
def repetition_loop(ids, min_repeats=4, max_unit=6):
    """Describes the first n-gram (n <= max_unit) repeated min_repeats times in a row, if any."""
    for size in range(1, max_unit + 1):
        for start in range(0, len(ids) - size * min_repeats + 1):
            unit = ids[start:start + size]
            if all(ids[start + k * size:start + (k + 1) * size] == unit for k in range(1, min_repeats)):
                return f"{size}-token unit repeated {min_repeats}x at {start}"
    return None


def word_overlap(reference, candidate):
    """Unigram F1 over lowercased words: a coarse check that en->X->en round trips keep their meaning."""
    ref, cand = re.findall(r"\w+", reference.lower()), re.findall(r"\w+", candidate.lower())
    common = sum((collections.Counter(ref) & collections.Counter(cand)).values())
    return 0.0 if not common else 2 * common / (len(ref) + len(cand))


def check_tokenizer_equivalence(model_dir, hf_dir, sentences):
    import sentencepiece as spm
    ours = SpmModel(model_dir / "source.spm")
    library = spm.SentencePieceProcessor(model_file=str(model_dir / "source.spm"))
    mismatches = []
    for s in sentences:
        a, b = ours.encode_pieces(s)[0], library.encode(s, out_type=str)
        if a != b:
            mismatches.append({"text": s, "ours": a[:20], "sentencepiece": b[:20]})
    result = {"sentences": len(sentences), "pieceMismatches": mismatches}
    if hf_dir.exists():  # end to end: vocab.json mapping, <unk>, </s> against transformers' MarianTokenizer
        from transformers import MarianTokenizer
        if (model_dir / "target_vocab.json").exists():  # our vocabularies, not the upstream tokenizer config
            tokenizer = MarianTokenizer(str(hf_dir / "source.spm"), str(hf_dir / "target.spm"), str(model_dir / "vocab.json"),
                                        target_vocab_file=str(model_dir / "target_vocab.json"), separate_vocabs=True)
        else:
            tokenizer = MarianTokenizer.from_pretrained(hf_dir)
        model = MarianOnnx(model_dir, threads=1)

        def reference_ids(text):  # Sipher truncates to the position table, keeping </s>
            ids = tokenizer(text).input_ids
            return ids if len(ids) <= model.max_positions else ids[:model.max_positions - 1] + ids[-1:]

        bad = [s for s in sentences if model.encode(s) != reference_ids(s)]
        result["idMismatchesVsMarianTokenizer"] = [s[:80] for s in bad]
    return result


def sample_inputs(component, by_id, config, dirs):
    english = config["englishSamples"]
    source_id = component.get("sampleInputsFrom")
    if not source_id:
        return english
    source = by_id[source_id]
    source_dir = dirs["out"] / source_id
    if not (source_dir / "manifest.json").exists():
        raise ComponentError(f"sample inputs come from {source_id}, which has not been built")
    forward = MarianOnnx(source_dir, threads=1)
    return [forward.translate(s, source.get("targetToken")) for s in english]


def verify(component, by_id, config, dirs, log=print):
    cid = component["id"]
    out = dirs["out"] / cid
    work = dirs["work"] / cid
    token = component.get("targetToken")
    inputs = sample_inputs(component, by_id, config, dirs)

    tokenizer = check_tokenizer_equivalence(out, work / "hf", TOKENIZER_SENTENCES + inputs)
    problems = []
    if tokenizer["pieceMismatches"]:
        problems.append(f"{len(tokenizer['pieceMismatches'])} tokenizer piece mismatches vs sentencepiece")
    if tokenizer.get("idMismatchesVsMarianTokenizer"):
        problems.append(f"{len(tokenizer['idMismatchesVsMarianTokenizer'])} id mismatches vs MarianTokenizer")

    model = MarianOnnx(out, threads=1)  # pure-Python tokenizer, like the Java code
    translations = []
    for text in inputs:
        parts = [model.translate_sentence(s, token) for s in split_sentences(text)]
        output = " ".join(p[0] for p in parts if p[0])
        issues = []
        if not output:
            issues.append("empty output")
        for sentence, (_, ids, hit_limit) in zip(split_sentences(text), parts):
            if hit_limit:
                issues.append(f"hit the generation limit on {sentence!r}")
            loop = repetition_loop(ids)
            if loop:
                issues.append(f"repetition loop ({loop}) on {sentence!r}")
        translations.append({"input": text, "output": output, "sentences": len(parts), "issues": issues})
        problems += [f"{text!r}: {i}" for i in issues]
    round_trip = None
    if component.get("sampleInputsFrom"):  # outputs should resemble the original English samples
        round_trip = round(statistics.mean(
            word_overlap(e, t["output"]) for e, t in zip(config["englishSamples"], translations)), 3)
        if round_trip < MIN_ROUND_TRIP_OVERLAP:
            problems.append(f"en->{component['source']}->en round trip keeps only {round_trip:.0%} of the words")

    # Speed: same decoder, C++ sentencepiece for tokenisation (identical pieces, checked above) so the
    # figure reflects the model rather than Python's Viterbi loop.
    fast = MarianOnnx(out, threads=1, use_spm_lib=True)
    fast.translate(inputs[0], token)
    per_input = []
    for text in inputs:
        runs = []
        for _ in range(TIMING_REPEATS):
            started = time.perf_counter()
            fast.translate(text, token)
            runs.append((time.perf_counter() - started) * 1000)
        per_input.append(min(runs))
    ms = round(statistics.mean(per_input), 1)

    files = [name for name in COMPONENT_FILES if (out / name).exists()]
    unexpected = sorted({p.name for p in out.iterdir()} - set(files) - {"manifest.json"})
    if unexpected:
        problems.append(f"unexpected files {unexpected}")
    size = sum((out / f).stat().st_size for f in files)
    warnings = [f"total size {size / 1e6:.1f} MB exceeds {SIZE_WARNING_MB} MB"] if size > SIZE_WARNING_MB * 1e6 else []

    manifest = {
        "id": cid,
        "type": "translation",
        "source": component["source"],
        "target": component["target"],
        "targetToken": token,
        "license": component["license"],
        "attribution": attribution(component),
        "upstream": {"repo": component["repo"], "revision": component["revision"]},
        "files": [{"path": f, "sha256": sha256(out / f), "size": (out / f).stat().st_size} for f in files],
        "samples": [{"input": translations[i]["input"], "output": translations[i]["output"]}
                    for i in config["manifestSamples"]],
        "msPerSentence": ms,
    }
    report = {"id": cid, "sizeBytes": size, "msPerSentence": ms, "msPerInput": per_input, "warnings": warnings,
              "problems": problems, "roundTripWordOverlap": round_trip, "tokenizer": tokenizer,
              "translations": translations}
    (work / "verify.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    (out / "manifest.json").unlink(missing_ok=True)
    if problems:
        raise ComponentError("; ".join(problems) + f" (details: {work / 'verify.json'})")
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"[{cid}] ok: {size / 1e6:.1f} MB, {ms} ms/sentence, tokenizer {tokenizer['sentences']} sentences identical"
        + (f"  WARNING: {'; '.join(warnings)}" if warnings else ""))
    for t in translations:
        log(f"    {t['input']}\n      -> {t['output']}")
    return report


# ---------------------------------------------------------------------------------------------
# driver
# ---------------------------------------------------------------------------------------------
def export_in_subprocess(cid, args):
    """Runs one export in its own process so --jobs can overlap them with bounded threads."""
    log = args.work / cid / "build.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    threads = str(args.threads)
    env = dict(os.environ, OMP_NUM_THREADS=threads, MKL_NUM_THREADS=threads, OPENBLAS_NUM_THREADS=threads)
    cmd = [sys.executable, __file__, "--export-only", "--threads", threads,
           "--out", str(args.out), "--work", str(args.work), cid]
    with open(log, "w") as f:
        return cid, subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, env=env).returncode, log


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ids", nargs="*", help="component ids (default: all)")
    parser.add_argument("--out", type=Path, default=CACHE / "out")
    parser.add_argument("--work", type=Path, default=CACHE / "work" / "translation")
    parser.add_argument("--jobs", type=int, default=1, help="parallel exports")
    parser.add_argument("--threads", type=int, default=2, help="threads per export")
    parser.add_argument("--export-only", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()

    config = load_components()
    by_id = {c["id"]: c for c in config["components"]}
    ids = args.ids or list(by_id)
    unknown = [i for i in ids if i not in by_id]
    if unknown:
        parser.error(f"unknown component ids {unknown}")
    # X->en verification translates with the en->X component: build it too if it is missing.
    for cid in list(ids):
        dep = by_id[cid].get("sampleInputsFrom")
        if dep and dep not in ids and not (args.out / dep / "manifest.json").exists():
            ids.insert(0, dep)
    ids.sort(key=lambda i: bool(by_id[i].get("sampleInputsFrom")))  # en->X first
    dirs = {"out": args.out, "work": args.work}

    try:
        import torch
        torch.set_num_threads(args.threads)
    except ImportError:
        pass

    failed = {}
    if not args.verify_only:
        if args.export_only or args.jobs <= 1:
            for cid in ids:
                try:
                    build_export(by_id[cid], dirs, config["licenses"])
                except ComponentError as e:
                    failed[cid] = str(e)
                    print(f"[{cid}] NOT BUILT: {e}")
        else:
            with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
                for cid, code, log in pool.map(lambda i: export_in_subprocess(i, args), ids):
                    tail = log.read_text().strip().splitlines()[-1:] if log.exists() else []
                    print(f"[{cid}] export {'ok' if code == 0 else 'FAILED'} ({log}) {' '.join(tail)}")
                    if code != 0:
                        failed[cid] = f"export failed, see {log}"
    if args.export_only:
        sys.exit(1 if failed else 0)

    reports = {}
    for cid in ids:
        if cid in failed:
            continue
        try:
            reports[cid] = verify(by_id[cid], by_id, config, dirs)
        except ComponentError as e:
            failed[cid] = str(e)
            print(f"[{cid}] VERIFY FAILED: {e}")

    print("\nid            size MB  ms/sent  token          sample")
    for cid in ids:
        if cid in reports:
            r = reports[cid]
            flag = " !" if r["warnings"] else ""
            print(f"{cid:12s} {r['sizeBytes'] / 1e6:8.1f} {r['msPerSentence']:8.1f}  {str(by_id[cid].get('targetToken')):14s} "
                  f"{r['translations'][1]['output']}{flag}")
        else:
            print(f"{cid:12s} FAILED: {failed[cid]}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
