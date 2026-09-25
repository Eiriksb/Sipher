#!/usr/bin/env python3
"""Converts NVIDIA NeMo FastConformer transducer checkpoints to sherpa-onnx int8 speech components.

For every "converter": "nemo" component in convert.json this script

  1. downloads the pinned .nemo checkpoint from Hugging Face and checks its sha256;
  2. runs sherpa-onnx's own export script, unmodified, fetched from the pinned sherpa-onnx commit
     (scripts/nemo/fast-conformer-hybrid-transducer-ctc/export-onnx-transducer-non-streaming.py, the recipe
     sherpa-onnx used for its published es/de FastConformer exports). Two things are injected around it:
       - ASRModel.from_pretrained() is redirected to the pinned local checkpoint, so nothing is fetched by name;
       - for a pure RNN-T model (parakeet-rnnt-110m-da-dk) the script's hybrid-only
         change_decoding_strategy(decoder_type="rnnt") call is accepted as a no-op, since RNN-T is the only
         decoder, and the model_type metadata is corrected afterwards;
  3. checks the ONNX export against the original PyTorch model (NeMo greedy RNN-T decoding) on FLEURS clips;
  4. validates encoder/decoder/joiner.int8.onnx with sherpa-onnx (2 threads, the in-game setting) on FLEURS test
     clips and writes .cache/packs/out/<id>/ with the recogniser files, LICENSE (+ NOTICE) and manifest.json.

Usage (from the repository root, inside the venv from requirements-convert.txt):
  HF_HOME=.cache/packs/hf python tools/packs/speech/convert_nemo.py            # all nemo components
  HF_HOME=.cache/packs/hf python tools/packs/speech/convert_nemo.py stt-fr     # just one
"""
from __future__ import annotations

import argparse
import contextlib
import os
import runpy
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_common as cc  # noqa: E402

INT8 = ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx")


@contextlib.contextmanager
def export_environment(nemo_path: Path, workdir: Path, argv: list[str]):
    """Runs the sherpa-onnx script in `workdir`, with from_pretrained() bound to the pinned local checkpoint."""
    import nemo.collections.asr as nemo_asr
    from nemo.collections.asr.models import EncDecHybridRNNTCTCModel

    asr_model_cls = nemo_asr.models.ASRModel
    original_from_pretrained = asr_model_cls.__dict__.get("from_pretrained")  # usually inherited from ModelPT
    info = {}

    def from_pretrained(cls, model_name=None, **_):
        model = asr_model_cls.restore_from(str(nemo_path), map_location="cpu")
        info["class"] = type(model).__name__
        info["hybrid"] = isinstance(model, EncDecHybridRNNTCTCModel)
        if not info["hybrid"]:
            change = model.change_decoding_strategy  # RNN-T only: there is no CTC branch to switch away from

            def change_decoding_strategy(decoding_cfg=None, decoder_type=None, verbose=True):
                if decoder_type not in (None, "rnnt"):
                    raise ValueError(f"{type(model).__name__} has no {decoder_type} decoder")
                return change(decoding_cfg, verbose=verbose)

            model.change_decoding_strategy = change_decoding_strategy
        return model

    old_cwd, old_argv = os.getcwd(), sys.argv
    asr_model_cls.from_pretrained = classmethod(from_pretrained)
    workdir.mkdir(parents=True, exist_ok=True)
    os.chdir(workdir)
    sys.argv = argv
    try:
        yield info
    finally:
        sys.argv = old_argv
        os.chdir(old_cwd)
        if original_from_pretrained is None:
            del asr_model_cls.from_pretrained
        else:
            asr_model_cls.from_pretrained = original_from_pretrained


def set_metadata(path: Path, updates: dict[str, str]) -> None:
    import onnx

    model = onnx.load(str(path))
    props = {p.key: p.value for p in model.metadata_props}
    props.update(updates)
    del model.metadata_props[:]
    for key, value in props.items():
        entry = model.metadata_props.add()
        entry.key, entry.value = key, str(value)
    onnx.save(model, str(path))


def export(cfg: dict, comp: dict, work: Path) -> tuple[Path, Path]:
    up = comp["upstream"]
    nemo_path = cc.hf_file(up["repo"], up["file"], up["revision"], up["sha256"])
    script = cc.sherpa_script(cfg, "nemoTransducer", work)
    outdir = work / "nemo" / comp["id"]
    stamp = outdir / "export.done"
    key = f"{up['sha256']} {cfg['sherpaOnnx']['scripts']['nemoTransducer']['sha256']}\n"
    if stamp.is_file() and stamp.read_text() == key and all((outdir / f).is_file() for f in INT8 + ("tokens.txt",)):
        cc.log(f"[{comp['id']}] export up to date ({outdir})")
        return outdir, nemo_path

    cc.log(f"[{comp['id']}] exporting {up['repo']}@{up['revision'][:10]} with {script.name}")
    doc = f"{comp['title']} (https://huggingface.co/{up['repo']}, revision {up['revision']})"
    with export_environment(nemo_path, outdir, [str(script), "--model", up["repo"], "--doc", doc]) as info:
        runpy.run_path(str(script), run_name="__main__")
    if not info["hybrid"]:  # the script labels every export EncDecHybridRNNTCTCBPEModel
        set_metadata(outdir / "encoder.int8.onnx", {"model_type": info["class"]})
    stamp.write_text(key)
    return outdir, nemo_path


class OnnxTransducer:
    """Plain onnxruntime greedy RNN-T decoding of the exported encoder/decoder/joiner (NeMo's max 10 symbols/frame)."""

    def __init__(self, exported: Path, suffix: str, threads: int):
        import onnxruntime as ort

        opts = ort.SessionOptions()
        opts.intra_op_num_threads, opts.inter_op_num_threads = threads, 1
        self.sessions = [ort.InferenceSession(str(exported / f"{m}{suffix}.onnx"), opts,
                                              providers=["CPUExecutionProvider"])
                         for m in ("encoder", "decoder", "joiner")]
        meta = self.sessions[0].get_modelmeta().custom_metadata_map
        self.layers, self.hidden = int(meta["pred_rnn_layers"]), int(meta["pred_hidden"])
        self.blank = int(meta["vocab_size"])

    def encode(self, feats, feat_len):
        enc = self.sessions[0]
        return enc.run(None, {enc.get_inputs()[0].name: feats, enc.get_inputs()[1].name: feat_len})

    def greedy(self, encoder_out, encoder_len, max_symbols: int = 10) -> list[int]:
        import numpy as np

        dec, join = self.sessions[1], self.sessions[2]
        names = [i.name for i in dec.get_inputs()]

        def step(token, state):
            out = dec.run(None, {names[0]: np.array([[token]], dtype=np.int32),
                                 names[1]: np.array([1], dtype=np.int32), names[2]: state[0], names[3]: state[1]})
            return out[0], (out[2], out[3])

        state = tuple(np.zeros((self.layers, 1, self.hidden), dtype=np.float32) for _ in range(2))
        dec_out, next_state = step(self.blank, state)
        tokens = []
        for t in range(int(encoder_len[0])):
            frame = encoder_out[:, :, t: t + 1]
            for _ in range(max_symbols):
                logits = join.run(None, {join.get_inputs()[0].name: frame, join.get_inputs()[1].name: dec_out})[0]
                y = int(logits.reshape(-1).argmax())
                if y == self.blank:
                    break
                tokens.append(y)
                state = next_state
                dec_out, next_state = step(y, state)
        return tokens


def reference_check(nemo_path: Path, exported: Path, clips: list[dict], graph_clips: int, threads: int) -> dict:
    """Checks the ONNX export against the original NeMo model (PyTorch).

    graph (first graph_clips clips): NeMo computes the features once; the PyTorch encoder + NeMo greedy RNN-T
           decoding is compared token by token with onnxruntime encoder/decoder/joiner (fp32 and int8) run on
           exactly those features.
    end-to-end (all clips): NeMo transcribe() vs sherpa-onnx, which computes its own fbank features as in the game.
    """
    import nemo.collections.asr as nemo_asr
    import sherpa_onnx
    import torch
    from nemo.collections.asr.models import EncDecHybridRNNTCTCModel

    torch.set_num_threads(threads)
    model = nemo_asr.models.ASRModel.restore_from(str(nemo_path), map_location="cpu")
    model.eval()
    if isinstance(model, EncDecHybridRNNTCTCModel):
        model.change_decoding_strategy(decoder_type="rnnt")
    onnx = {"fp32": OnnxTransducer(exported, "", threads), "int8": OnnxTransducer(exported, ".int8", threads)}

    def first(hyps):
        hyps = hyps[0] if isinstance(hyps, tuple) else hyps
        return hyps[0]

    graph = []
    for clip in clips[:graph_clips]:
        audio = torch.from_numpy(cc.read_wav(clip["path"]))[None]
        with torch.no_grad():
            feats, feat_len = model.preprocessor(input_signal=audio, length=torch.tensor([audio.shape[1]]))
            enc, enc_len = model.encoder(audio_signal=feats, length=feat_len)
            hyp = first(model.decoding.rnnt_decoder_predictions_tensor(encoder_output=enc, encoded_lengths=enc_len))
        pt_tokens = [int(t) for t in hyp.y_sequence]
        row = {"file": clip["file"], "pytorch": hyp.text.strip()}
        for name, m in onnx.items():
            onnx_enc, onnx_len = m.encode(feats.numpy(), feat_len.numpy())
            diff = float(abs(onnx_enc - enc.numpy()).max())
            tokens = m.greedy(onnx_enc, onnx_len)
            row[name] = {"encoderMaxAbsDiff": round(diff, 6),
                         "encoderMaxAbsDiffRelative": round(diff / float(abs(enc.numpy()).max()), 6),
                         "tokensIdentical": tokens == pt_tokens, "text": model.tokenizer.ids_to_text(tokens)}
        graph.append(row)

    with torch.no_grad():
        out = model.transcribe([c["path"] for c in clips], batch_size=1, verbose=False)
    out = out[0] if isinstance(out, tuple) else out
    rows = [{"file": c["file"], "nemoTranscribe": (h.text if hasattr(h, "text") else str(h)).strip()}
            for c, h in zip(clips, out)]
    for suffix, key in (("", "sherpaFp32"), (".int8", "sherpaInt8")):
        rec = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=str(exported / f"encoder{suffix}.onnx"), decoder=str(exported / f"decoder{suffix}.onnx"),
            joiner=str(exported / f"joiner{suffix}.onnx"), tokens=str(exported / "tokens.txt"),
            num_threads=threads, model_type="nemo_transducer", decoding_method="greedy_search")
        for row, clip in zip(rows, clips):
            row[key] = cc.sherpa_decode(rec, cc.read_wav(clip["path"]))
    n = len(rows)

    def exact(key):
        return f"{sum(r['nemoTranscribe'] == r[key] for r in rows)}/{n}"

    return {
        "graph": graph,
        "graphTokensIdentical": {name: f"{sum(r[name]['tokensIdentical'] for r in graph)}/{len(graph)}"
                                 for name in onnx},
        "endToEnd": rows,
        "sherpaVsTranscribeExact": {"fp32": exact("sherpaFp32"), "int8": exact("sherpaInt8")},
        "werVsReference": {k: cc.wer([(c["reference"], r[k]) for c, r in zip(clips, rows)])
                           for k in ("nemoTranscribe", "sherpaFp32", "sherpaInt8")},
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ids", nargs="*", help="component ids (default: all non-optional nemo components)")
    ap.add_argument("--work", type=Path, default=cc.DEFAULT_WORK)
    ap.add_argument("--out", type=Path, default=cc.DEFAULT_OUT)
    ap.add_argument("--export-threads", type=int, default=6)
    ap.add_argument("--reference-clips", type=int, default=3, help="clips compared against PyTorch (0 = skip)")
    args = ap.parse_args()

    cc.limit_threads(args.export_threads)
    cc.use_work_tmp(args.work)
    import torch

    torch.set_num_threads(args.export_threads)
    cfg = cc.load_config()
    val = cfg["validation"]
    lines = []
    for comp in cc.select(cfg, "nemo", args.ids):
        exported, nemo_path = export(cfg, comp, args.work)
        clips = cc.fleurs_clips(cfg, comp["fleurs"], args.work)
        report = {"id": comp["id"], "upstream": comp["upstream"]}
        if args.reference_clips:
            cc.log(f"[{comp['id']}] comparing ONNX with the PyTorch model")
            ref = reference_check(nemo_path, exported, clips, args.reference_clips, val["threads"])
            report["reference"] = ref
            cc.log(f"[{comp['id']}] same features, PyTorch vs ONNX tokens identical: {ref['graphTokensIdentical']}; "
                   f"NeMo transcribe vs sherpa-onnx exact: {ref['sherpaVsTranscribeExact']}; "
                   f"WER {ref['werVsReference']}")

        import sherpa_onnx

        recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=str(exported / INT8[0]), decoder=str(exported / INT8[1]), joiner=str(exported / INT8[2]),
            tokens=str(exported / "tokens.txt"), num_threads=val["threads"], model_type="nemo_transducer",
            decoding_method="greedy_search")
        evaluation = cc.evaluate(recognizer, clips, comp["language"], val["manifestClips"])
        report["evaluation"] = evaluation
        roles = {role: (exported / name, name) for role, name in zip(("encoder", "decoder", "joiner", "tokens"),
                                                                      INT8 + ("tokens.txt",))}
        manifest = cc.package(cfg, comp, cc.output_dir(comp, args.out, args.work), roles, evaluation)
        cc.write_json(args.work / "reports" / f"{comp['id']}.json", report)
        lines.append(cc.report_line(comp, manifest, evaluation))
        cc.log(lines[-1])
    print("\n".join(lines))


if __name__ == "__main__":
    main()
