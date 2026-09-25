#!/usr/bin/env python3
"""
Exports a Marian / OPUS-MT checkpoint (a local Hugging Face model directory) to the two int8
ONNX files Sipher's MarianTranslator loads:

    encoder_model.onnx          int8 (dynamic quantisation, per-tensor, QInt8 weights)
    decoder_model_merged.onnx   int8, one decoder with KV cache (If node on `use_cache_branch`)

Steps:
  1. `optimum-cli export onnx --task text2text-generation-with-past` (fp32, opset 18) produces
     encoder_model.onnx and decoder_model_merged.onnx.
  2. untie_lm_head: the merged decoder computes the tied output projection as
     Transpose(shared embedding) inside each If branch, i.e. on every decoding step. Replace it
     with a pre-transposed constant so the MatMul gets a constant B and can be quantised.
  3. quantize: quantize_dynamic(QInt8, per_channel=False) with EnableSubgraph (the decoder's
     MatMuls live inside the If branches) and MatMulConstBOnly; sinusoidal position embeddings
     stay fp32.
  4. share_quantized_embedding: the decoder now holds the vocabulary matrix twice ([V,d] for the
     token-embedding Gather and [d,V] for the output projection); make the Gather read the [d,V]
     copy and drop the other one.

Usage:
    python export.py <hf-model-dir> <out-dir> [--fp32-dir DIR] [--opset 18]
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

import onnx
from onnx import helper, numpy_helper
from onnxruntime.quantization import QuantType, quantize_dynamic

ONNX_FILES = ("encoder_model.onnx", "decoder_model_merged.onnx")


def _all_graphs(graph):
    yield graph
    for node in graph.node:
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.GRAPH:
                yield from _all_graphs(attribute.g)


def optimum_export(hf_dir, fp32_dir, opset=18, log=None):
    """fp32 encoder + merged decoder via optimum-cli (runs in a subprocess)."""
    if fp32_dir.exists():
        shutil.rmtree(fp32_dir)
    cli = Path(sys.executable).with_name("optimum-cli")
    cmd = [str(cli), "export", "onnx", "-m", str(hf_dir), "--task", "text2text-generation-with-past",
           "--opset", str(opset), str(fp32_dir)]
    env = dict(os.environ, DISABLE_SAFETENSORS_CONVERSION="true")
    with open(log or os.devnull, "w") as out:
        result = subprocess.run(cmd, stdout=out, stderr=subprocess.STDOUT, env=env)
    if result.returncode != 0:
        tail = Path(log).read_text()[-4000:] if log else ""
        raise RuntimeError(f"optimum-cli export failed ({result.returncode})\n{tail}")
    if not (fp32_dir / "decoder_model_merged.onnx").exists():
        raise RuntimeError("optimum-cli did not produce decoder_model_merged.onnx")
    for name in ("decoder_model.onnx", "decoder_with_past_model.onnx"):  # superseded by the merged decoder
        (fp32_dir / name).unlink(missing_ok=True)


def untie_lm_head(path):
    """Replaces runtime Transpose(vocab matrix) nodes with a pre-transposed initializer."""
    model = onnx.load(str(path))
    initializers = {i.name: i for i in model.graph.initializer}
    changed = 0
    for graph in _all_graphs(model.graph):
        for node in list(graph.node):
            if node.op_type != "Transpose" or node.input[0] not in initializers:
                continue
            source = initializers[node.input[0]]
            if len(source.dims) != 2 or source.dims[0] * source.dims[1] < 1_000_000:
                continue
            name = source.name + "_transposed_const"
            if name not in initializers:
                model.graph.initializer.append(numpy_helper.from_array(numpy_helper.to_array(source).T.copy(), name))
                initializers[name] = model.graph.initializer[-1]
            old_output = node.output[0]
            graph.node.remove(node)
            for inner in _all_graphs(graph):
                for consumer in inner.node:
                    for k, value in enumerate(consumer.input):
                        if value == old_output:
                            consumer.input[k] = name
            changed += 1
    if changed:
        onnx.save(model, str(path))
    return changed


def quantize(source, target):
    model = onnx.load(str(source), load_external_data=False)
    # sinusoidal position tables stay fp32 (small, and int8 would perturb every position)
    exclude = [n.name for g in _all_graphs(model.graph) for n in g.node
               if n.op_type == "Gather" and "embed_positions" in n.name]
    quantize_dynamic(
        str(source), str(target),
        per_channel=False, reduce_range=False, weight_type=QuantType.QInt8,
        nodes_to_exclude=exclude,
        extra_options={"EnableSubgraph": True, "MatMulConstBOnly": True},
    )


def share_quantized_embedding(path):
    """Gather(W_q[V,d], ids) -> Gather(W_T_q[d,V], ids, axis=1) -> DequantizeLinear -> Transpose."""
    model = onnx.load(str(path))
    initializers = {i.name: i for i in model.graph.initializer}
    changed = 0
    for graph in _all_graphs(model.graph):
        consumers = {}
        for node in graph.node:
            for value in node.input:
                consumers.setdefault(value, []).append(node)
        for gather in list(graph.node):
            if gather.op_type != "Gather" or not gather.input[0].endswith("_quantized"):
                continue
            base = gather.input[0][: -len("_quantized")]
            transposed = base + "_transposed_const_quantized"
            if transposed not in initializers or any(a.name == "axis" and a.i != 0 for a in gather.attribute):
                continue
            users = consumers.get(gather.output[0], [])
            if len(users) != 1 or users[0].op_type != "DequantizeLinear":
                continue
            dequantize = users[0]
            output = dequantize.output[0]
            index = list(graph.node).index(gather)
            graph.node.remove(gather)
            graph.node.remove(dequantize)
            replacement = [
                helper.make_node("Gather", [transposed, gather.input[1]], [gather.output[0]], name=gather.name, axis=1),
                helper.make_node("DequantizeLinear",
                                 [gather.output[0], base + "_transposed_const_scale", base + "_transposed_const_zero_point"],
                                 [output + "_dBS"], name=dequantize.name),
                helper.make_node("Transpose", [output + "_dBS"], [output], name=dequantize.name + "_T", perm=[1, 2, 0]),
            ]
            for k, node in enumerate(replacement):
                graph.node.insert(index + k, node)
            changed += 1
    if changed:
        used = {value for g in _all_graphs(model.graph) for n in g.node for value in n.input}
        for initializer in list(model.graph.initializer):
            if initializer.name not in used:
                model.graph.initializer.remove(initializer)
        onnx.save(model, str(path))
    return changed


def export(hf_dir, out_dir, fp32_dir, opset=18, log=None):
    """Writes the two int8 ONNX files into out_dir; fp32 intermediates stay in fp32_dir."""
    hf_dir, out_dir, fp32_dir = Path(hf_dir), Path(out_dir), Path(fp32_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    optimum_export(hf_dir, fp32_dir, opset, log)
    untied = untie_lm_head(fp32_dir / "decoder_model_merged.onnx")
    if untied == 0:
        raise RuntimeError("no runtime Transpose of the vocabulary matrix found in the merged decoder")
    for name in ONNX_FILES:
        quantize(fp32_dir / name, out_dir / name)
    shared = share_quantized_embedding(out_dir / "decoder_model_merged.onnx")
    return {"untied": untied, "shared": shared}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("hf_dir", type=Path)
    parser.add_argument("out_dir", type=Path)
    parser.add_argument("--fp32-dir", type=Path, default=None, help="default: <out_dir>/../fp32")
    parser.add_argument("--opset", type=int, default=18)
    args = parser.parse_args()
    fp32 = args.fp32_dir or args.out_dir.parent / "fp32"
    print(export(args.hf_dir, args.out_dir, fp32, args.opset))


if __name__ == "__main__":
    main()
