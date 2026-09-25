#!/usr/bin/env python3
"""
Reference OPUS-MT / Marian inference that mirrors Sipher's Java implementation
(src/main/java/io/github/eiriksb/sipher/mt/MarianTranslator.java and SentencePiece.java).
It only needs numpy and onnxruntime:

  1. SentencePiece *unigram* tokenizer written from scratch: the .spm protobuf is decoded by
     hand, text is normalised with the precompiled nmt_nfkc charsmap and segmented with Viterbi.
  2. Marian piece -> id mapping through vocab.json (SentencePiece ids are not the model ids in
     general), optional sentence-initial target-language token such as >>nob<<, then </s>.
  3. The encoder runs once, then greedy decoding with decoder_model_merged.onnx and its KV cache.
  4. Output ids -> pieces (inverse vocab.json / target_vocab.json) -> joined, '▁' -> ' ', stripped.

Text is split into sentences first (like java.text.BreakIterator) because OPUS-MT tends to drop
sentences when given several at once.

Usage:
    python reference_translate.py <component-dir> [--target-token '>>nob<<'] "Hello there!" ...
"""
import argparse
import json
import struct
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

SPACE = "▁"  # '▁'


# --------------------------------------------------------------------------------------------
# SentencePiece model (.spm) = protobuf ModelProto, read by hand so the Java port needs no
# protobuf library either.
#   ModelProto: 1 = pieces (repeated SentencePiece{1 = piece, 2 = score (float), 3 = type (enum)})
#               2 = trainer_spec (3 = model_type: 1 UNIGRAM, 2 BPE, 3 WORD, 4 CHAR;
#                                 35 = byte_fallback)
#               3 = normalizer_spec (1 = name, 2 = precompiled_charsmap, 3 = add_dummy_prefix,
#                                    4 = remove_extra_whitespaces, 5 = escape_whitespaces)
# --------------------------------------------------------------------------------------------
def _f32(x):
    """Rounds to IEEE float32: sentencepiece (C++) and the Java port keep Viterbi scores in float, and on
    exact ties (e.g. long runs of one letter) double precision would pick a different segmentation."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


def _varint(buf, i):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def _fields(buf):
    """Yields (field_number, wire_type, value) for a protobuf message."""
    i = 0
    while i < len(buf):
        key, i = _varint(buf, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = _varint(buf, i)
        elif wire == 1:
            value, i = buf[i:i + 8], i + 8
        elif wire == 2:
            n, i = _varint(buf, i)
            value, i = buf[i:i + n], i + n
        elif wire == 5:
            value, i = buf[i:i + 4], i + 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")
        yield number, wire, value


class SpmModel:
    NORMAL, UNKNOWN, CONTROL, USER_DEFINED, UNUSED, BYTE = 1, 2, 3, 4, 5, 6
    MODEL_TYPES = {1: "UNIGRAM", 2: "BPE", 3: "WORD", 4: "CHAR"}

    def __init__(self, path):
        buf = Path(path).read_bytes()
        self.pieces = []  # (piece, score, type)
        self.model_type = 1
        self.byte_fallback = False
        self.normalizer_name = ""
        self.charsmap = b""
        self.add_dummy_prefix = True
        self.remove_extra_whitespaces = True
        self.escape_whitespaces = True
        for number, _, value in _fields(buf):
            if number == 1:
                piece, score, kind = "", 0.0, self.NORMAL
                for n2, _, v2 in _fields(value):
                    if n2 == 1:
                        piece = bytes(v2).decode("utf-8")
                    elif n2 == 2:
                        score = struct.unpack("<f", v2)[0]
                    elif n2 == 3:
                        kind = v2
                self.pieces.append((piece, score, kind))
            elif number == 2:
                for n2, _, v2 in _fields(value):
                    if n2 == 3:
                        self.model_type = v2
                    elif n2 == 35:
                        self.byte_fallback = bool(v2)
            elif number == 3:
                for n2, _, v2 in _fields(value):
                    if n2 == 1:
                        self.normalizer_name = bytes(v2).decode("utf-8")
                    elif n2 == 2:
                        self.charsmap = bytes(v2)
                    elif n2 == 3:
                        self.add_dummy_prefix = bool(v2)
                    elif n2 == 4:
                        self.remove_extra_whitespaces = bool(v2)
                    elif n2 == 5:
                        self.escape_whitespaces = bool(v2)
        if self.model_type != 1:
            raise NotImplementedError(
                f"only unigram SentencePiece models are supported, got {self.MODEL_TYPES.get(self.model_type, self.model_type)}")
        # Pieces usable by Viterbi: NORMAL and USER_DEFINED (CONTROL/UNKNOWN/UNUSED never match).
        self.piece_to_id = {}
        self.unk_id = 0
        scores = []
        for i, (piece, score, kind) in enumerate(self.pieces):
            if kind in (self.NORMAL, self.USER_DEFINED):
                self.piece_to_id[piece] = i
            if kind == self.NORMAL:
                scores.append(score)
            if kind == self.UNKNOWN:
                self.unk_id = i
        self.min_score, self.max_score = min(scores), max(scores)
        self.max_piece_len = max(len(p) for p in self.piece_to_id)
        self.normalizer = PrecompiledCharsmap(self.charsmap) if self.charsmap else None

    # normalizer.cc: Normalizer::Normalize
    def normalize(self, text):
        chunks = list(text) if self.normalizer is None else self.normalizer.split(text)
        i = 0
        if self.remove_extra_whitespaces:  # drop leading whitespace
            while i < len(chunks) and chunks[i] == " ":
                i += 1
        if i == len(chunks):
            return ""
        ws = SPACE if self.escape_whitespaces else " "
        out = [ws] if self.add_dummy_prefix else []
        previous_space = self.remove_extra_whitespaces
        for chunk in chunks[i:]:
            if previous_space:
                chunk = chunk.lstrip(" ")
            if chunk:
                out.append(chunk.replace(" ", ws) if self.escape_whitespaces else chunk)
                previous_space = chunk.endswith(" ")
            if not self.remove_extra_whitespaces:
                previous_space = False
        s = "".join(out)
        if self.remove_extra_whitespaces:
            while s.endswith(ws):
                s = s[:-len(ws)]
        return s

    # unigram_model.cc: Model::EncodeOptimized (Viterbi over code points)
    def encode_pieces(self, text):
        s = self.normalize(text)
        n = len(s)
        unk_score = _f32(self.min_score - 10.0)  # kUnkPenalty
        best_score = [0.0] * (n + 1)
        best_start = [-1] * (n + 1)
        best_id = [-1] * (n + 1)
        for start in range(n):
            if start > 0 and best_start[start] == -1:
                continue
            base = best_score[start]
            has_single = False
            for end in range(start + 1, min(n, start + self.max_piece_len) + 1):
                pid = self.piece_to_id.get(s[start:end])
                if pid is None:
                    continue
                _, score, kind = self.pieces[pid]
                if kind == self.USER_DEFINED:
                    score = _f32(_f32((end - start) * self.max_score) - _f32(0.1))
                candidate = _f32(base + score)
                if best_start[end] == -1 or candidate > best_score[end]:
                    best_score[end], best_start[end], best_id[end] = candidate, start, pid
                if end - start == 1:
                    has_single = True
            if not has_single:
                end = start + 1
                candidate = _f32(base + unk_score)
                if best_start[end] == -1 or candidate > best_score[end]:
                    best_score[end], best_start[end], best_id[end] = candidate, start, self.unk_id
        out = []
        end = n
        while end > 0:
            start = best_start[end]
            out.append((s[start:end], best_id[end]))
            end = start
        out.reverse()
        merged = []  # SentencePieceProcessor merges consecutive unknown pieces into one
        for piece, pid in out:
            if pid == self.unk_id and merged and merged[-1][1] == self.unk_id:
                merged[-1] = (merged[-1][0] + piece, pid)
            else:
                merged.append((piece, pid))
        return [p for p, _ in merged], [i for _, i in merged]


class PrecompiledCharsmap:
    """SentencePiece 'precompiled_charsmap' (nmt_nfkc): uint32 LE trie size, a darts-clone
    double-array trie (uint32 units) over UTF-8 bytes, then NUL-terminated replacement strings."""

    def __init__(self, blob):
        (trie_size,) = struct.unpack_from("<I", blob, 0)
        self.units = np.frombuffer(blob, dtype="<u4", count=trie_size // 4, offset=4).tolist()
        self.normalized = blob[4 + trie_size:]

    def _longest_prefix(self, key: bytes):
        units = self.units
        best_len, best_value = 0, -1
        pos = 0
        unit = units[pos]
        pos ^= (unit >> 10) << ((unit & (1 << 9)) >> 6)  # offset()
        for i, c in enumerate(key):
            pos ^= c
            if pos >= len(units):
                break
            unit = units[pos]
            if (unit & ((1 << 31) | 0xFF)) != c:  # label()
                break
            pos ^= (unit >> 10) << ((unit & (1 << 9)) >> 6)
            if (unit >> 8) & 1:  # has_leaf()
                best_len, best_value = i + 1, units[pos] & 0x7FFFFFFF  # value()
        return best_len, best_value

    def split(self, text):
        """Returns the normalised text as a list of replacement chunks (one per match)."""
        data = text.encode("utf-8")
        out = []
        i = 0
        while i < len(data):
            n, value = self._longest_prefix(data[i:i + 64])
            if n:
                end = self.normalized.index(b"\0", value)
                out.append(self.normalized[value:end].decode("utf-8"))
                i += n
            else:  # copy one UTF-8 character unchanged
                b = data[i]
                length = 1 if b < 0x80 else 2 if b < 0xE0 else 3 if b < 0xF0 else 4
                out.append(data[i:i + length].decode("utf-8", errors="replace"))
                i += length
        return out


# --------------------------------------------------------------------------------------------
# Sentence splitting: an approximation of java.text.BreakIterator.getSentenceInstance(ROOT).
# Breaks after ! ? and CJK/fullwidth terminators (plus closing punctuation and spaces), and after
# '.' when it is followed by whitespace and a character that is not a lowercase letter.
# --------------------------------------------------------------------------------------------
_TERMINATORS = set("!?։؟۔‼‽⁇⁈⁉。﹒﹗！？｡")
_PERIODS = set(".．")
_CLOSING = set("\"'”’)]}»」』）】")


def split_sentences(text):
    sentences = []
    start = i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in _TERMINATORS or c in _PERIODS:
            j = i + 1
            while j < n and (text[j] in _TERMINATORS or text[j] in _PERIODS or text[j] in _CLOSING):
                j += 1
            k = j
            while k < n and text[k].isspace():
                k += 1
            if c in _TERMINATORS or k == n or (k > j and not text[k].islower()):
                sentences.append(text[start:k])
                start = i = k
                continue
            i = j
            continue
        i += 1
    if start < n:
        sentences.append(text[start:])
    return [s.strip() for s in sentences if s.strip()]


# --------------------------------------------------------------------------------------------
# Marian model: same algorithm, limits and special-token handling as MarianTranslator.java.
# --------------------------------------------------------------------------------------------
class MarianOnnx:
    def __init__(self, model_dir, threads=1, use_spm_lib=False):
        d = Path(model_dir)
        cfg = json.loads((d / "config.json").read_text())
        gen_path = d / "generation_config.json"
        gen = json.loads(gen_path.read_text()) if gen_path.exists() else {}

        def setting(key):
            value = gen.get(key)
            return value if value is not None else cfg.get(key)

        self.decoder_start = setting("decoder_start_token_id")
        self.eos = setting("eos_token_id")
        pad = setting("pad_token_id")
        # never emit single-token bad words (bad_words_ids = [[pad]]) or <pad>
        self.suppressed = {ids[0] for ids in (setting("bad_words_ids") or []) if len(ids) == 1} | {pad}
        self.max_positions = cfg["max_position_embeddings"]
        self.n_heads = cfg["decoder_attention_heads"]
        self.head_dim = cfg["d_model"] // self.n_heads

        self.vocab = json.loads((d / "vocab.json").read_text())  # piece -> model id
        target_vocab_path = d / "target_vocab.json"  # only for separate-vocabulary models
        target_vocab = json.loads(target_vocab_path.read_text()) if target_vocab_path.exists() else self.vocab
        self.id_to_piece = {i: p for p, i in target_vocab.items()}
        self.unk = self.vocab.get("<unk>", 1)

        if use_spm_lib:  # the C++ library: identical pieces (checked by build.py), faster in Python
            import sentencepiece as spm
            processor = spm.SentencePieceProcessor(model_file=str(d / "source.spm"))
            self.source_pieces = lambda text: processor.encode(text, out_type=str)
        else:
            model = SpmModel(d / "source.spm")
            self.source_pieces = lambda text: model.encode_pieces(text)[0]

        options = ort.SessionOptions()
        options.intra_op_num_threads = max(1, threads)
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        providers = ["CPUExecutionProvider"]
        self.encoder = ort.InferenceSession(str(d / "encoder_model.onnx"), options, providers=providers)
        self.decoder = ort.InferenceSession(str(d / "decoder_model_merged.onnx"), options, providers=providers)
        self.past_names = [i.name for i in self.decoder.get_inputs() if i.name.startswith("past_key_values.")]
        self.output_names = [o.name for o in self.decoder.get_outputs()]

    def encode(self, sentence, target_token=None):
        """Text -> model ids: [target token] + pieces via vocab.json (unknown -> <unk>) + </s>."""
        ids = []
        if target_token:
            if target_token not in self.vocab:
                raise ValueError(f"model has no target token {target_token}")
            ids.append(self.vocab[target_token])
        ids += [self.vocab.get(p, self.unk) for p in self.source_pieces(sentence)]
        return ids[: self.max_positions - 1] + [self.eos]

    def translate(self, text, target_token=None):
        """Sentence by sentence, like MarianTranslator.translate."""
        results = [self.translate_sentence(s, target_token)[0] for s in split_sentences(text)]
        return " ".join(r for r in results if r)

    def translate_sentence(self, sentence, target_token=None):
        """Returns (text, generated ids, hit_limit). hit_limit means EOS was forced."""
        source = self.encode(sentence, target_token)
        input_ids = np.array([source], dtype=np.int64)
        mask = np.ones_like(input_ids)
        (encoded,) = self.encoder.run(None, {"input_ids": input_ids, "attention_mask": mask})
        # Captions are short: stop runaway generation well before the position table runs out.
        limit = min(self.max_positions - 1, len(source) * 3 + 10)

        # Step 0 takes use_cache_branch=False; past inputs are ignored but must be fed.
        empty = np.zeros((1, self.n_heads, 0, self.head_dim), dtype=np.float32)
        feed = {name: empty for name in self.past_names}
        feed.update({
            "encoder_attention_mask": mask,
            "encoder_hidden_states": encoded,
            "input_ids": np.array([[self.decoder_start]], dtype=np.int64),
            "use_cache_branch": np.array([False]),
        })
        generated = []
        hit_limit = False
        for step in range(limit):
            outputs = dict(zip(self.output_names, self.decoder.run(None, feed)))
            if step == limit - 1:
                token = self.eos
                hit_limit = True
            else:
                logits = outputs["logits"][0, -1].copy()
                logits[list(self.suppressed)] = -np.inf
                token = int(np.argmax(logits))
            if token == self.eos:
                break
            generated.append(token)
            # Cross-attention K/V only come out of step 0: in the use_cache_branch=True subgraph the
            # merged decoder returns placeholders for present.*.encoder.*, so keep the step-0 ones.
            for name in self.past_names:
                if step == 0 or ".decoder." in name:
                    feed[name] = outputs["present." + name[len("past_key_values."):]]
            feed["input_ids"] = np.array([[token]], dtype=np.int64)
            feed["use_cache_branch"] = np.array([True])
        pieces = [self.id_to_piece[i] for i in generated if i != self.unk and i in self.id_to_piece]
        return "".join(pieces).replace(SPACE, " ").strip(), generated, hit_limit


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("model_dir")
    parser.add_argument("texts", nargs="+")
    parser.add_argument("--target-token", default=None, help="e.g. '>>nob<<' for multilingual models")
    parser.add_argument("--threads", type=int, default=1)
    args = parser.parse_args()
    model = MarianOnnx(args.model_dir, threads=args.threads)
    for text in args.texts:
        started = time.perf_counter()
        out = model.translate(text, args.target_token)
        print(f"{text}\n  -> {out}   [{(time.perf_counter() - started) * 1000:.1f} ms]")


if __name__ == "__main__":
    main()
