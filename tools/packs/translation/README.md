# Translation language-pack components

Rebuilds Sipher's optional translation components (OPUS-MT / Marian models as int8 ONNX) from their
upstream Hugging Face checkpoints. Each component is one translation direction, such as `mt-en-es`.

| File | Purpose |
|---|---|
| `components.json` | Every component: upstream repo, pinned revision (commit sha), target-language token, licence, authors, and which en→X component supplies the inputs for an X→en check. Also holds the English sample sentences. |
| `build.py` | Downloads, checks, exports, verifies and writes each component directory with its `manifest.json`. |
| `export.py` | Hugging Face checkpoint → `encoder_model.onnx` + `decoder_model_merged.onnx` (int8). |
| `reference_translate.py` | Python mirror of `MarianTranslator.java` / `SentencePiece.java` (pure-Python unigram tokenizer, greedy KV-cache decoding, same limits and sentence splitting). |
| `requirements.txt` | Pinned Python dependencies (Python 3.12, CPU-only PyTorch). |

## Setup

```sh
uv venv -p 3.12 .venv
uv pip install -p .venv/bin/python --index-strategy unsafe-best-match -r tools/packs/translation/requirements.txt
# or: python3.12 -m venv .venv && .venv/bin/pip install -r tools/packs/translation/requirements.txt
```

## Build

```sh
.venv/bin/python tools/packs/translation/build.py                        # everything
.venv/bin/python tools/packs/translation/build.py mt-en-es mt-es-en      # selected components
.venv/bin/python tools/packs/translation/build.py --jobs 3 --threads 2   # parallel exports
.venv/bin/python tools/packs/translation/build.py --verify-only mt-en-pt # re-check a built component
```

Output goes to `.cache/packs/out/<id>/`; downloads, fp32 intermediates, logs and a full
`verify.json` per component go to `.cache/packs/work/translation/<id>/`; `HF_HOME` defaults to
`.cache/packs/hf` (all git-ignored). An export needs a few GB of RAM (about 6 GB for the tc-big models).

Each component directory contains exactly `encoder_model.onnx`, `decoder_model_merged.onnx`, `source.spm`,
`vocab.json` (`target_vocab.json` only for separate-vocabulary models), `config.json`,
`generation_config.json`, `LICENSE` (licence, attribution, modifications), `README.md` (upstream model
card) and `manifest.json` (id, languages, target token, SPDX licence, attribution, upstream repo and
revision, sha256 and size of every other file, five sample translations, ms per sentence).

## What the build does

1. **Download** the upstream repo at the pinned revision (weights, tokenizer, configs, model card).
2. **Licence check**: the licence in the model card metadata must be redistributable (Apache-2.0,
   MIT, CC-BY-4.0, CC-BY-SA-4.0) and match `components.json`; otherwise the component is not built.
3. **Tokenizer check**: `source.spm` must be a SentencePiece *unigram* model without byte fallback
   (the Java tokenizer supports nothing else), the target token must exist in `vocab.json`, and
   `vocab.json` must cover the source pieces. Some separate-vocabulary conversions ship only the target
   vocabulary (the tc-big en-ko/ko-en models); for those, `"sourceVocab": "source.spm"` rebuilds
   `vocab.json` from source.spm's own ids and ships the upstream file as `target_vocab.json`.
4. **Export** (`export.py`): `optimum-cli export onnx --task text2text-generation-with-past --opset 18`,
   then
   - the tied output projection, which the merged decoder computes as `Transpose(embedding)` on every
     step inside its `If` branches, is replaced by a pre-transposed constant;
   - `quantize_dynamic` (QInt8, per-tensor) with `EnableSubgraph` (the decoder's MatMuls sit inside the
     `If` branches) and `MatMulConstBOnly`; the sinusoidal position embeddings stay fp32;
   - the decoder's token-embedding lookup is rewired to read the quantised output-projection matrix so
     the vocabulary matrix is stored once.
5. **Verify** with `reference_translate.py`:
   - the pure-Python tokenizer must produce exactly the pieces of the `sentencepiece` library (about 35
     varied sentences: whitespace, NFKC cases, emoji, several scripts, the sample inputs) and the ids of
     `transformers.MarianTokenizer`;
   - the ten English samples (en→X), or their translation by the matching en→X component (X→en), are
     translated sentence by sentence; outputs must be non-empty, must not hit the generation limit and
     must not loop (an n-gram repeated four times); an X→en component's round trip must keep at least
     25 % of the English words (unigram F1), which catches models that produce fluent nonsense;
   - average ms per input on one thread (C++ `sentencepiece` tokenisation, same ids, so the figure
     measures the model);
   - components over 350 MB are flagged.

Timings depend on the machine; they are meant for comparing components.

## Adding a component

Add an entry to `components.json` with the upstream `repo`, its current commit sha as `revision`
(`huggingface_hub.HfApi().model_info(repo).sha`), `targetToken` (for multilingual-target models: a
`>>xxx<<` key from `vocab.json`, otherwise `null`), the SPDX `license` from the model card and the
`authors`. For an X→en component set `sampleInputsFrom` to its en→X counterpart; record choices such as
the target token in `notes`. Then run `build.py <id>`.

## Notes

- Tiny models (`opus-mt_tiny_*`) have a 256-position table; standard models 512, tc-big 1024. Sipher
  translates sentence by sentence and caps generation at `3 × input + 10` tokens.
- Multilingual-target models need a target token: `mt-en-pl` `>>pol<<`, `mt-en-nb` `>>nob<<`,
  `mt-en-zh` `>>cmn_Hans<<`, and `mt-en-pt` `>>pt_BR<<` (Brazilian Portuguese; `>>pt<<` gives European
  Portuguese but drops clauses, and `>>pt_PT<<` produces a mix of other Romance languages).
- The Python sentence splitter approximates `java.text.BreakIterator` (breaks after `! ?` and CJK
  terminators, and after `.` followed by whitespace and a non-lowercase character).
