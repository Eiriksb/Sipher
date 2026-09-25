#!/usr/bin/env python3
"""Converts Hugging Face Whisper fine-tunes (NB-Whisper, KB-Whisper) to sherpa-onnx int8 speech components.

sherpa-onnx's Whisper exporter (scripts/whisper/export-onnx.py) only loads OpenAI-format checkpoints through
whisper.load_model(), while NB-Whisper and KB-Whisper are published as Hugging Face transformers checkpoints. For
every "converter": "whisper" component in convert.json this script therefore

  1. downloads model.safetensors (sha256-pinned), config.json and generation_config.json at the pinned revision;
  2. rewrites the transformers state dict into an OpenAI-whisper checkpoint ({"dims", "model_state_dict"}):
     parameter names are mapped (model.encoder.layers.N.self_attn.q_proj -> encoder.blocks.N.attn.query, fc1/fc2 ->
     mlp.0/mlp.2, embed_positions -> positional_embedding, ...), fp16 weights are widened to fp32, the dims come from
     config.json, and the checkpoint is refused unless the output projection is tied to the token embedding and the
     vocabulary/special tokens are the stock multilingual Whisper ones (the exporter writes tokens.txt from
     openai-whisper's own tokenizer);
  3. runs sherpa-onnx's export-onnx.py unmodified (fetched from the pinned sherpa-onnx commit) with
     --model <size>, with whisper.load_model(<size>) redirected to the converted checkpoint;
  4. proves the conversion is faithful: the original transformers model, the converted openai-whisper model and the
     fp32/int8 ONNX files (sherpa-onnx, padded to 30 s like the originals) transcribe the same FLEURS clips;
  5. validates the int8 files with sherpa-onnx as Sipher runs them (2 threads, default 1000-frame tail padding),
     measures the latency of a 5 s utterance and writes <out>/<id>/ (encoder.int8.onnx, decoder.int8.onnx,
     tokens.txt, LICENSE, manifest.json). "optional" alternatives (the tiny models) go to <work>/alternatives/.

Usage (from the repository root, inside the venv from requirements-convert.txt):
  HF_HOME=.cache/packs/hf python tools/packs/speech/convert_whisper.py                          # stt-nb, stt-sv
  HF_HOME=.cache/packs/hf python tools/packs/speech/convert_whisper.py stt-nb-tiny stt-sv-tiny  # alternatives
"""
from __future__ import annotations

import argparse
import json
import os
import re
import runpy
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_common as cc  # noqa: E402

# transformers parameter name -> openai-whisper parameter name ("model." prefix already removed)
TOP_LEVEL = {
    "encoder.embed_positions.weight": "encoder.positional_embedding",
    "encoder.layer_norm.weight": "encoder.ln_post.weight",
    "encoder.layer_norm.bias": "encoder.ln_post.bias",
    "decoder.embed_tokens.weight": "decoder.token_embedding.weight",
    "decoder.embed_positions.weight": "decoder.positional_embedding",
    "decoder.layer_norm.weight": "decoder.ln.weight",
    "decoder.layer_norm.bias": "decoder.ln.bias",
}
IN_LAYER = {
    "self_attn.q_proj": "attn.query", "self_attn.k_proj": "attn.key", "self_attn.v_proj": "attn.value",
    "self_attn.out_proj": "attn.out", "self_attn_layer_norm": "attn_ln",
    "encoder_attn.q_proj": "cross_attn.query", "encoder_attn.k_proj": "cross_attn.key",
    "encoder_attn.v_proj": "cross_attn.value", "encoder_attn.out_proj": "cross_attn.out",
    "encoder_attn_layer_norm": "cross_attn_ln",
    "fc1": "mlp.0", "fc2": "mlp.2", "final_layer_norm": "mlp_ln",
}
LAYER = re.compile(r"^(encoder|decoder)\.layers\.(\d+)\.(.+)\.(weight|bias)$")


def openai_name(hf_name: str) -> str:
    name = hf_name.removeprefix("model.")
    if name in TOP_LEVEL:
        return TOP_LEVEL[name]
    if name.startswith(("encoder.conv1.", "encoder.conv2.")):
        return name
    m = LAYER.match(name)
    if m and m.group(3) in IN_LAYER:
        return f"{m.group(1)}.blocks.{m.group(2)}.{IN_LAYER[m.group(3)]}.{m.group(4)}"
    raise ValueError(f"unexpected transformers Whisper parameter: {hf_name}")


def convert_checkpoint(comp: dict, target: Path) -> dict:
    """HF transformers Whisper -> OpenAI whisper checkpoint file. Returns the dims."""
    import torch
    import whisper
    from safetensors.torch import load_file

    up = comp["upstream"]
    weights = cc.hf_file(up["repo"], up["file"], up["revision"], up["sha256"])
    config = json.loads(cc.hf_file(up["repo"], "config.json", up["revision"]).read_text(encoding="utf-8"))
    generation = json.loads(cc.hf_file(up["repo"], "generation_config.json", up["revision"]).read_text("utf-8"))

    hf = load_file(str(weights))
    if "proj_out.weight" in hf and not torch.equal(hf["proj_out.weight"], hf["model.decoder.embed_tokens.weight"]):
        raise SystemExit(f"{up['repo']}: proj_out is not tied to embed_tokens; OpenAI Whisper cannot represent it")
    state = {openai_name(k): v.float().contiguous() for k, v in hf.items() if k != "proj_out.weight"}
    dims = {
        "n_mels": config["num_mel_bins"], "n_vocab": config["vocab_size"],
        "n_audio_ctx": config["max_source_positions"], "n_audio_state": config["d_model"],
        "n_audio_head": config["encoder_attention_heads"], "n_audio_layer": config["encoder_layers"],
        "n_text_ctx": config["max_target_positions"], "n_text_state": config["d_model"],
        "n_text_head": config["decoder_attention_heads"], "n_text_layer": config["decoder_layers"],
    }
    model = whisper.model.Whisper(whisper.model.ModelDimensions(**dims))
    model.load_state_dict(state, strict=True)  # every parameter mapped, none left over, shapes match

    # tokens.txt and the language/task ids in the ONNX metadata come from openai-whisper's stock tokenizer, so the
    # fine-tune must use exactly that vocabulary and those special-token ids.
    tok = whisper.tokenizer.get_tokenizer(model.is_multilingual, num_languages=model.num_languages)
    expected = {"decoder_start_token_id": tok.sot, "eos_token_id": tok.eot, "no_timestamps_token_id": tok.no_timestamps}
    for key, value in expected.items():
        if key in generation and generation[key] != value:
            raise SystemExit(f"{up['repo']}: {key} {generation[key]} != stock Whisper {value}")
    hint = comp["languageHint"]
    lang_token = generation.get("lang_to_id", {}).get(f"<|{hint}|>")
    if lang_token != tok.to_language_token(hint):
        raise SystemExit(f"{up['repo']}: <|{hint}|> is {lang_token}, stock Whisper uses {tok.to_language_token(hint)}")
    if not model.is_multilingual or dims["n_mels"] != 80:
        raise SystemExit(f"{up['repo']}: expected a multilingual 80-mel Whisper model, got {dims}")

    target.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"dims": dims, "model_state_dict": model.state_dict()}, target)
    return dims


def export(cfg: dict, comp: dict, work: Path) -> Path:
    import whisper

    up, size = comp["upstream"], comp["whisperSize"]
    outdir = work / "whisper" / comp["id"]
    names = [f"{size}-encoder.int8.onnx", f"{size}-decoder.int8.onnx", f"{size}-encoder.onnx",
             f"{size}-decoder.onnx", f"{size}-tokens.txt"]
    script = cc.sherpa_script(cfg, "whisper", work)
    stamp = outdir / "export.done"
    key = f"{up['sha256']} {cfg['sherpaOnnx']['scripts']['whisper']['sha256']}\n"
    if stamp.is_file() and stamp.read_text() == key and all((outdir / n).is_file() for n in names):
        cc.log(f"[{comp['id']}] export up to date ({outdir})")
        return outdir

    checkpoint = outdir / f"{comp['id']}-openai-whisper.pt"
    cc.log(f"[{comp['id']}] converting {up['repo']}@{up['revision'][:10]} to an OpenAI-whisper checkpoint")
    dims = convert_checkpoint(comp, checkpoint)
    cc.log(f"[{comp['id']}] dims {dims}; exporting with {script.name} --model {size}")

    import torch

    original, set_interop = whisper.load_model, torch.set_num_interop_threads

    def load_model(name, *args, **kwargs):
        return original(str(checkpoint), device="cpu") if name == size else original(name, *args, **kwargs)

    def set_num_interop_threads(n):  # the script sets 1; torch refuses a second call in the same process
        try:
            set_interop(n)
        except RuntimeError:
            pass

    old_cwd, old_argv = os.getcwd(), sys.argv
    whisper.load_model, torch.set_num_interop_threads = load_model, set_num_interop_threads
    os.chdir(outdir)
    sys.argv = [str(script), "--model", size]
    try:
        runpy.run_path(str(script), run_name="__main__")
    finally:
        sys.argv = old_argv
        os.chdir(old_cwd)
        whisper.load_model, torch.set_num_interop_threads = original, set_interop
    stamp.write_text(key)
    return outdir


def sherpa_whisper(exported: Path, size: str, suffix: str, language: str, threads: int, tail_paddings: int = -1):
    import sherpa_onnx

    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(exported / f"{size}-encoder{suffix}.onnx"), decoder=str(exported / f"{size}-decoder{suffix}.onnx"),
        tokens=str(exported / f"{size}-tokens.txt"), language=language, task="transcribe", num_threads=threads,
        decoding_method="greedy_search", tail_paddings=tail_paddings)


def onnx_greedy(exported: Path, size: str, suffix: str, mel, sot_sequence: list[int], eot: int, dims: dict,
                threads: int, max_tokens: int = 120):
    """Plain argmax decoding of the exported encoder/decoder with onnxruntime, using the self-attention KV cache the
    way sherpa-onnx does. Returns (cross_k, tokens)."""
    import numpy as np
    import onnxruntime as ort

    opts = ort.SessionOptions()
    opts.intra_op_num_threads, opts.inter_op_num_threads = threads, 1
    enc, dec = (ort.InferenceSession(str(exported / f"{size}-{m}{suffix}.onnx"), opts,
                                     providers=["CPUExecutionProvider"]) for m in ("encoder", "decoder"))
    cross_k, cross_v = enc.run(None, {"mel": mel})
    cache = np.zeros((dims["n_text_layer"], 1, dims["n_text_ctx"], dims["n_text_state"]), dtype=np.float32)
    self_k, self_v, offset, feed, out = cache, cache.copy(), 0, list(sot_sequence), []
    while len(out) < max_tokens:
        logits, self_k, self_v = dec.run(None, {
            "tokens": np.array([feed], dtype=np.int64), "in_n_layer_self_k_cache": self_k,
            "in_n_layer_self_v_cache": self_v, "n_layer_cross_k": cross_k, "n_layer_cross_v": cross_v,
            "offset": np.array([offset], dtype=np.int64)})
        offset += len(feed)
        y = int(logits[0, -1].argmax())
        if y == eot:
            break
        out.append(y)
        feed = [y]
    return cross_k, out


def reference_check(comp: dict, exported: Path, clips: list[dict], graph_clips: int, threads: int) -> dict:
    """Checks the ONNX export against the original models (PyTorch).

    graph (first graph_clips clips): the converted openai-whisper model and the fp32/int8 ONNX files get the same
        30 s log-mel input and are decoded with the same plain argmax loop (sot, language, transcribe,
        no_timestamps); cross-attention K and output tokens are compared.
    weights (all clips): the original transformers checkpoint (generate, greedy) vs the converted openai-whisper
        checkpoint (whisper.decode, greedy) proves the parameter renaming.
    end-to-end (all clips): WER of transformers vs sherpa-onnx fp32/int8 as the game runs it (sherpa-onnx computes
        its own features and pads only 10 s of zero features instead of 30 s of silence).
    """
    import torch
    import whisper
    from safetensors.torch import load_file
    from transformers import GenerationConfig, WhisperConfig, WhisperForConditionalGeneration

    torch.set_num_threads(threads)
    up, size, hint = comp["upstream"], comp["whisperSize"], comp["languageHint"]
    config_dir = cc.hf_file(up["repo"], "config.json", up["revision"]).parent
    cc.hf_file(up["repo"], "generation_config.json", up["revision"])
    hf_model = WhisperForConditionalGeneration(WhisperConfig.from_pretrained(config_dir))
    state = {k: v.float() for k, v in load_file(str(cc.hf_file(up["repo"], up["file"], up["revision"]))).items()}
    missing, unexpected = hf_model.load_state_dict(state, strict=False)
    assert not unexpected and set(missing) <= {"proj_out.weight"}, (missing, unexpected)
    hf_model.tie_weights()
    hf_model.eval()
    hf_model.generation_config = GenerationConfig.from_pretrained(config_dir)

    oa_model = whisper.load_model(str(exported / f"{comp['id']}-openai-whisper.pt"), device="cpu").eval()
    dims = vars(oa_model.dims)
    tok = whisper.tokenizer.get_tokenizer(True, num_languages=oa_model.num_languages, language=hint,
                                          task="transcribe")
    sot_sequence = list(tok.sot_sequence) + [tok.no_timestamps]
    options = whisper.DecodingOptions(language=hint, task="transcribe", without_timestamps=True, fp16=False,
                                      temperature=0.0)
    graph, rows = [], []
    for i, clip in enumerate(clips):
        audio = cc.read_wav(clip["path"])
        mel = whisper.log_mel_spectrogram(whisper.pad_or_trim(torch.from_numpy(audio)), dims["n_mels"])
        with torch.no_grad():
            ids = hf_model.generate(mel.unsqueeze(0), language=hint, task="transcribe", return_timestamps=False,
                                    num_beams=1, do_sample=False, max_new_tokens=200)[0].tolist()
            hf_text = tok.decode([t for t in ids if t < tok.eot]).strip()
            oa_text = whisper.decode(oa_model, mel, options).text.strip()
            rows.append({"file": clip["file"], "transformers": hf_text, "openaiWhisper": oa_text})
            if i >= graph_clips:
                continue
            features = oa_model.encoder(mel.unsqueeze(0))
            pt_cross_k = torch.stack([b.cross_attn.key(features) for b in oa_model.decoder.blocks]).numpy()
            tokens = list(sot_sequence)
            while len(tokens) - len(sot_sequence) < 120:
                y = int(oa_model.decoder(torch.tensor([tokens]), features)[0, -1].argmax())
                if y == tok.eot:
                    break
                tokens.append(y)
            pt_tokens = tokens[len(sot_sequence):]
        row = {"file": clip["file"], "pytorch": tok.decode(pt_tokens).strip()}
        for name, suffix in (("fp32", ""), ("int8", ".int8")):
            cross_k, onnx_tokens = onnx_greedy(exported, size, suffix, mel.unsqueeze(0).numpy(), sot_sequence,
                                               tok.eot, dims, threads)
            diff = float(abs(cross_k - pt_cross_k).max())
            row[name] = {"crossKMaxAbsDiff": round(diff, 6),
                         "crossKMaxAbsDiffRelative": round(diff / float(abs(pt_cross_k).max()), 6),
                         "tokensIdentical": onnx_tokens == pt_tokens, "text": tok.decode(onnx_tokens).strip()}
        graph.append(row)
    for suffix, key in (("", "sherpaFp32"), (".int8", "sherpaInt8")):
        rec = sherpa_whisper(exported, size, suffix, hint, threads)
        for row, clip in zip(rows, clips):
            row[key] = cc.sherpa_decode(rec, cc.read_wav(clip["path"]))
    n = len(rows)
    return {
        "graph": graph,
        "graphTokensIdentical": {k: f"{sum(r[k]['tokensIdentical'] for r in graph)}/{len(graph)}"
                                 for k in ("fp32", "int8")},
        "weights": f"{sum(r['transformers'] == r['openaiWhisper'] for r in rows)}/{n}",
        "endToEnd": rows,
        "werVsReference": {k: cc.wer([(c["reference"], r[k]) for c, r in zip(clips, rows)])
                           for k in ("transformers", "openaiWhisper", "sherpaFp32", "sherpaInt8")},
    }


def padding_comparison(exported: Path, comp: dict, clips: list[dict], threads: int) -> dict:
    """WER/latency of the int8 model with 30 s padding (as trained) vs sherpa-onnx's default tail padding."""
    rec = sherpa_whisper(exported, comp["whisperSize"], ".int8", comp["languageHint"], threads, tail_paddings=3000)
    audio = [cc.read_wav(c["path"]) for c in clips]
    hyps = [cc.sherpa_decode(rec, a) for a in audio]
    five = audio[0][: 5 * cc.SAMPLE_RATE]
    times = []
    for _ in range(3):
        t0 = time.perf_counter()
        cc.sherpa_decode(rec, five)
        times.append(time.perf_counter() - t0)
    return {"wer": cc.wer([(c["reference"], h) for c, h in zip(clips, hyps)]),
            "latency5s": round(statistics.median(times), 3), "hypotheses": hyps}


def main() -> None:
    if os.environ.get("PYTHONHASHSEED") != "0":
        # openai-whisper's tokenizer yields the language tokens in string-hash order and the exporter writes that
        # order into the encoder metadata; a fixed hash seed makes the export byte-for-byte reproducible.
        os.environ["PYTHONHASHSEED"] = "0"
        os.execv(sys.executable, [sys.executable, *sys.argv])
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ids", nargs="*", help="component ids (default: all non-optional whisper components)")
    ap.add_argument("--work", type=Path, default=cc.DEFAULT_WORK)
    ap.add_argument("--out", type=Path, default=cc.DEFAULT_OUT)
    ap.add_argument("--export-threads", type=int, default=6)
    ap.add_argument("--reference-clips", type=int, default=3, help="clips compared against PyTorch (0 = skip)")
    args = ap.parse_args()

    cc.limit_threads(args.export_threads)
    cc.use_work_tmp(args.work)
    cfg = cc.load_config()
    val = cfg["validation"]
    lines = []
    for comp in cc.select(cfg, "whisper", args.ids):
        exported = export(cfg, comp, args.work)
        clips = cc.fleurs_clips(cfg, comp["fleurs"], args.work)
        report = {"id": comp["id"], "upstream": comp["upstream"]}
        if args.reference_clips:
            cc.log(f"[{comp['id']}] comparing ONNX with the PyTorch models")
            ref = reference_check(comp, exported, clips, args.reference_clips, val["threads"])
            report["reference"] = ref
            cc.log(f"[{comp['id']}] same mel, PyTorch vs ONNX tokens identical: {ref['graphTokensIdentical']}; "
                   f"transformers vs converted openai-whisper exact: {ref['weights']}; WER {ref['werVsReference']}")

        size = comp["whisperSize"]
        recognizer = sherpa_whisper(exported, size, ".int8", comp["languageHint"], val["threads"])
        evaluation = cc.evaluate(recognizer, clips, comp["language"], val["manifestClips"])
        report["evaluation"] = evaluation
        report["padded30s"] = padding_comparison(exported, comp, clips, val["threads"])
        cc.log(f"[{comp['id']}] 30 s padding: WER {report['padded30s']['wer']}, "
               f"5 s latency {report['padded30s']['latency5s']} s")
        roles = {"encoder": (exported / f"{size}-encoder.int8.onnx", "encoder.int8.onnx"),
                 "decoder": (exported / f"{size}-decoder.int8.onnx", "decoder.int8.onnx"),
                 "tokens": (exported / f"{size}-tokens.txt", "tokens.txt")}
        manifest = cc.package(cfg, comp, cc.output_dir(comp, args.out, args.work), roles, evaluation)
        cc.write_json(args.work / "reports" / f"{comp['id']}.json", report)
        lines.append(cc.report_line(comp, manifest, evaluation))
        cc.log(lines[-1])
    print("\n".join(lines))


if __name__ == "__main__":
    main()
