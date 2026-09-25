# Speech component tooling

Scripts that build Sipher's downloadable speech-recognition components. The mod
runs these with sherpa-onnx 1.13.8 through `SpeechRecognizer.java`.

## Prebuilt sherpa-onnx exports (`prebuilt.py`, `prebuilt.json`)

Some models are already published in sherpa-onnx format by the
[k2-fsa sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) project, so no
conversion is needed. `prebuilt.py` downloads them, checks them, repackages
them as Sipher components and validates them. `prebuilt.json` lists each
component's upstream source, pinned revision, files (with sha256 and size),
licence and attribution.

| id                | asrKind           | languages                                  | upstream model                               | licence            |
|-------------------|-------------------|--------------------------------------------|----------------------------------------------|--------------------|
| `stt-es`          | `nemo_transducer` | es                                         | NVIDIA stt_es_fastconformer_hybrid_large_pc  | CC-BY-4.0          |
| `stt-de`          | `nemo_transducer` | de                                         | NVIDIA stt_de_fastconformer_hybrid_large_pc  | CC-BY-4.0          |
| `stt-ru`          | `nemo_transducer` | ru                                         | GigaAM-v3 e2e RNN-T (with punctuation)       | MIT                |
| `stt-sensevoice`  | `sense_voice`     | zh, ja, ko (the model also supports en, yue) | SenseVoiceSmall, 2024-07-17 export           | FunASR Model License 1.1 |
| `stt-parakeet-v3` | `nemo_transducer` | pt, fi (the model supports 25 European languages) | NVIDIA parakeet-tdt-0.6b-v3             | CC-BY-4.0          |

The NVIDIA FastConformer hybrid models ship with both a CTC and a transducer
head. Sipher uses the **transducer** export (`encoder`/`decoder`/`joiner`,
`asrKind: nemo_transducer`). On FLEURS the WER of the two heads is about the
same, but the CTC exports often leave out the final full stop, and the German
one also puts a space before commas.

### Usage

```sh
python -m venv .venv && .venv/bin/pip install sherpa-onnx==1.13.8 numpy soundfile
.venv/bin/python tools/packs/speech/prebuilt.py            # fetch + build + validate everything
.venv/bin/python tools/packs/speech/prebuilt.py fetch      # download and verify the sha256 of every file
.venv/bin/python tools/packs/speech/prebuilt.py build --only stt-ru
.venv/bin/python tools/packs/speech/prebuilt.py validate --threads 2 --clips 10
.venv/bin/python tools/packs/speech/prebuilt.py list
```

Default locations, all under the git-ignored `.cache/packs/`:

- `work/speech-prebuilt/`: downloads (`upstream/<id>/`, `archives/`,
  `licenses/`), FLEURS clips (`fleurs/`) and full validation reports
  (`validation/<id>.json`, with every clip, the WER/CER and the test-wav
  transcripts).
- `out/<id>/`: the finished component. It contains only the files the
  recogniser needs (`*.onnx`, `tokens.txt`), `LICENSE` and `manifest.json`.

You can change these with `--work` and `--out`. Nothing is uploaded.

### What each step does

- **fetch** downloads every file from Hugging Face at the pinned commit. For a
  model that is only published as a sherpa-onnx GitHub release tarball
  (`stt-es`), it downloads the tarball, checks it against its pinned sha256 and
  extracts only the files it needs. Any file whose sha256 or size differs from
  `prebuilt.json` fails the run.
- **build** copies the runtime files and writes `LICENSE`. That file has an
  attribution line, the model, its authors and source, the licence name and URL,
  the changes made (the ONNX export and int8 quantisation by sherpa-onnx, and
  the repackaging by Sipher), and the full licence text where the licence
  requires it (the MIT text for GigaAM, the FunASR Model License for
  SenseVoice). It also writes `manifest.json`: `id`, `type`, `asrKind`,
  `roles`, `languages`, the optional `supportedLanguages`, `languageHint`,
  `license`, `attribution`, `upstream`, `files` (the sha256 and size of every
  file except the manifest), `validation` and `rtf`.
- **validate** streams clips of 5–8 s from the FLEURS test split
  (`google/fleurs` at a pinned revision, CC-BY-4.0). The download stops once it
  has enough clips. It then transcribes each clip with sherpa-onnx configured
  like `SpeechRecognizer.create()`: greedy search, the default 80-dim feature
  config (sherpa takes the real feature size from the model metadata), 2
  threads, and ITN on for SenseVoice. It records the WER (CER for zh, ja and
  ko) and the real-time factor (decode time / audio time, after one warm-up
  decode). It writes 3 reference/hypothesis pairs per language into the
  manifest and reports any RTF above 0.3.

### Updating a model

To update a model, change the `revision` (or the tarball `sha256`) and the file
hashes in `prebuilt.json`, then run `prebuilt.py`. The sizes and hashes in
`prebuilt.json` are the upstream ones. The manifest hashes are recomputed from
the output directory.

## Self-converted models (`convert_nemo.py`, `convert_whisper.py`, `convert.json`)

Some models have no sherpa-onnx export, so Sipher converts them. The converters
use sherpa-onnx's own export scripts, fetched from the pinned sherpa-onnx commit
(`v1.13.8`, `11afbd00`) and checked against the sha256 in `convert.json`. They
then validate the result the same way as `prebuilt.py`. `convert.json` lists
every component: the upstream repo, the pinned revision, the checkpoint sha256,
the licence, the authors and the FLEURS language.

| id       | asrKind           | language (hint) | upstream model                                   | licence                   |
|----------|-------------------|-----------------|--------------------------------------------------|---------------------------|
| `stt-fr` | `nemo_transducer` | fr              | nvidia/stt_fr_fastconformer_hybrid_large_pc      | CC-BY-4.0                 |
| `stt-it` | `nemo_transducer` | it              | nvidia/stt_it_fastconformer_hybrid_large_pc      | CC-BY-4.0                 |
| `stt-nl` | `nemo_transducer` | nl              | nvidia/stt_nl_fastconformer_hybrid_large_pc      | CC-BY-4.0                 |
| `stt-pl` | `nemo_transducer` | pl              | nvidia/stt_pl_fastconformer_hybrid_large_pc      | CC-BY-4.0                 |
| `stt-da` | `nemo_transducer` | da              | nvidia/parakeet-rnnt-110m-da-dk                  | NVIDIA Open Model License |
| `stt-nb` | `whisper`         | nb (`no`)       | NbAiLab/nb-whisper-base                          | Apache-2.0                |
| `stt-sv` | `whisper`         | sv (`sv`)       | KBLab/kb-whisper-base                            | Apache-2.0                |

`stt-nb-tiny` and `stt-sv-tiny` (NbAiLab/nb-whisper-tiny and KBLab/kb-whisper-tiny,
Apache-2.0) are optional alternatives. They are built only when you name them,
and they go to `work/speech-convert/alternatives/`, not `out/`, so they can never
reach the catalogue by accident. Their manifests use the id of the component they
would replace (`packageAs`).

### Setup and usage

The converters need NeMo, PyTorch (CPU-only) and openai-whisper.
`requirements-convert.txt` pins every package. Use Python 3.12:

```sh
W=.cache/packs/work/speech-convert
uv venv --python 3.12 $W/venv
VIRTUAL_ENV=$W/venv uv pip install --index-strategy unsafe-best-match -r tools/packs/speech/requirements-convert.txt
export HF_HOME=.cache/packs/hf
$W/venv/bin/python tools/packs/speech/convert_nemo.py              # stt-fr stt-it stt-nl stt-pl stt-da
$W/venv/bin/python tools/packs/speech/convert_whisper.py           # stt-nb stt-sv
$W/venv/bin/python tools/packs/speech/convert_whisper.py stt-nb-tiny stt-sv-tiny
$W/venv/bin/python tools/packs/speech/convert_nemo.py stt-da       # a single component
```

- Options: `--work`, `--out`, `--export-threads` (default 6) and `--reference-clips` (default 3; 0 skips the PyTorch
  comparison). Validation always uses 2 threads, like the game.
- Exports are cached in `work/speech-convert/{nemo,whisper}/<id>/` (with the fp32 ONNX files). A re-run only
  validates and packages again.
- Temporary files, including NeMo's checkpoint extraction, go to `work/speech-convert/tmp/` rather than `/tmp`.
- Full reports go to `work/speech-convert/reports/<id>.json`. They hold every clip, the WER, the RTF, the 5 s latency
  and the PyTorch-vs-ONNX comparison.
- The finished components go to `out/<id>/`. Each holds the recogniser files, `LICENSE`, `NOTICE` (NVIDIA Open Model
  License only) and `manifest.json`.

The output is reproducible. Two runs produce byte-identical files; `convert_whisper.py` re-executes itself with
`PYTHONHASHSEED=0` because openai-whisper lists language tokens in hash order.

### NeMo FastConformer models (`convert_nemo.py`)

sherpa-onnx published its es and de exports of this NVIDIA family as
transducers. Sipher uses the same kind (`nemo_transducer`: `encoder`, `decoder`,
`joiner`, `tokens`), so every language behaves the same. `convert_nemo.py` runs
`scripts/nemo/fast-conformer-hybrid-transducer-ctc/export-onnx-transducer-non-streaming.py`
unmodified. That is the recipe behind sherpa-onnx's de and pt exports. The export
script writes the int8 files with `quantize_dynamic`, QUInt8, for all three
graphs. The converter injects only two things:

1. `ASRModel.from_pretrained()` restores the pinned, sha256-checked `.nemo` file instead of fetching by name.
2. `parakeet-rnnt-110m-da-dk` is a pure RNN-T model (`EncDecRNNTBPEModel`), but the script is written for hybrid
   models. The script's hybrid-only `change_decoding_strategy(decoder_type="rnnt")` call is accepted as a no-op, since
   RNN-T is the model's only decoder. The encoder's `model_type` metadata is then corrected; sherpa-onnx does not read
   that field. The Danish model uses the same 80-bin `per_feature` front end and 8x subsampling as the hybrids, so no
   extra metadata is needed.

### Whisper fine-tunes (`convert_whisper.py`)

NB-Whisper and KB-Whisper are published as Hugging Face `transformers`
checkpoints. sherpa-onnx's `scripts/whisper/export-onnx.py` loads only
OpenAI-format checkpoints (`whisper.load_model`). The converter bridges the gap:

1. It downloads `model.safetensors` (sha256-pinned), `config.json` and `generation_config.json` at the pinned revision.
2. It rewrites the state dict into an OpenAI checkpoint, `{"dims": ..., "model_state_dict": ...}`, saved as
   `<id>-openai-whisper.pt`:
   - Drops the `model.` prefix.
   - Renames `encoder|decoder.layers.N.` to `...blocks.N.`.
   - Renames the self-attention projections: `self_attn.{q,k,v,out}_proj` becomes `attn.{query,key,value,out}`.
   - Renames the cross-attention projections: `encoder_attn.{q,k,v,out}_proj` becomes
     `cross_attn.{query,key,value,out}`.
   - Renames the layer norms: `self_attn_layer_norm` becomes `attn_ln`, `encoder_attn_layer_norm` becomes
     `cross_attn_ln` and `final_layer_norm` becomes `mlp_ln`.
   - Renames `fc1`/`fc2` to `mlp.0`/`mlp.2`.
   - Renames `encoder.embed_positions.weight` to `encoder.positional_embedding`, `decoder.embed_positions.weight` to
     `decoder.positional_embedding` and `decoder.embed_tokens` to `decoder.token_embedding`.
   - Renames `encoder.layer_norm` to `encoder.ln_post` and `decoder.layer_norm` to `decoder.ln`.
   - Leaves `conv1`/`conv2` unchanged.
   - Drops `proj_out.weight`, after checking that it equals the token embedding.
   - Widens fp16 weights (KB-Whisper) to fp32.
   - Takes the dims from `config.json`.
   - Loads the result with `strict=True`, so every parameter must be mapped and none may be left over.
3. It refuses any checkpoint whose vocabulary or special tokens differ from stock multilingual Whisper. The exporter
   writes `tokens.txt` and the language and task ids from openai-whisper's own tokenizer. The check covers
   `decoder_start_token_id`, `eos_token_id`, `no_timestamps_token_id`, the id of the `<|no|>` or `<|sv|>` token, 80
   mel bins and a 51865-token vocabulary.
4. It runs `export-onnx.py --model base` (or `tiny`) unmodified, with `whisper.load_model()` redirected to the
   converted file. The results are renamed to `encoder.int8.onnx`, `decoder.int8.onnx` and `tokens.txt`.

### Validation and faithfulness checks

Both converters use the same 10 FLEURS test clips per language. The clips are
5–8 s long, from `google/fleurs` at a pinned revision, streamed and resampled to
16 kHz mono. Each run records:

- **Game settings**: WER against the FLEURS transcript (lower-cased, punctuation removed; a rough figure), RTF over
  all 10 clips, and the median latency of a 5 s utterance. The recogniser uses sherpa-onnx 1.13.8 configured like
  `SpeechRecognizer.create()`: 2 threads, greedy search, `nemo_transducer` or whisper with the language hint and the
  default tail padding. The first 3 clips go into `manifest.json`.
- **Same input, PyTorch vs ONNX**: the original model and the fp32 and int8 ONNX graphs get identical features. For
  NeMo, those are NeMo's own features, run through the PyTorch encoder and NeMo greedy decoding. For Whisper, the
  30 s log-mel goes through the converted openai-whisper model with an argmax loop. The comparison covers the
  encoder output difference and whether the tokens are identical.
- **Weights (Whisper)**: the original `transformers` checkpoint (`generate`, greedy) against the converted
  openai-whisper checkpoint (`whisper.decode`) on all 10 clips.
- **End to end**: WER of the original PyTorch model against sherpa-onnx fp32 and int8. sherpa-onnx computes its own
  fbank or log-mel features, so small differences from the PyTorch front end are expected.

### Results

Measured 2026-09-25 on a Ryzen 7 9800X3D with sherpa-onnx 1.13.8 at 2 threads,
on the same 10 FLEURS test clips per language:

| id       | MB    | RTF   | 5 s latency | WER int8 | WER PyTorch original | same-input tokens identical (fp32 / int8) |
|----------|-------|-------|-------------|----------|----------------------|-------------------------------------------|
| `stt-fr` | 136.5 | 0.009 | 0.05 s      | 6.4 %    | 6.9 %                | 3/3 / 2/3                                 |
| `stt-it` | 135.8 | 0.009 | 0.05 s      | 11.3 %   | 11.3 %               | 3/3 / 2/3                                 |
| `stt-nl` | 136.5 | 0.009 | 0.05 s      | 34.5 %   | 30.4 %               | 3/3 / 1/3                                 |
| `stt-pl` | 136.5 | 0.009 | 0.05 s      | 19.1 %   | 20.6 %               | 3/3 / 1/3                                 |
| `stt-da` | 135.3 | 0.009 | 0.05 s      | 7.3 %    | 5.6 %                | 3/3 / 3/3                                 |
| `stt-nb` | 160.6 | 0.045 | 0.20 s      | 20.2 %   | 21.7 %               | 3/3 / 0/3                                 |
| `stt-sv` | 160.6 | 0.048 | 0.30 s      | 13.7 %   | 9.6 %                | 3/3 / 1/3                                 |

The fp32 ONNX graphs are exact. On identical input every model produced the
same tokens as PyTorch; the largest encoder or cross-attention difference was
3e-5 of the tensor's largest value. The
int8 files differ only by quantisation noise, the same `quantize_dynamic`
settings as sherpa-onnx's published exports. The Dutch model is simply weak on
FLEURS, in PyTorch as well.

### Whisper padding and the tiny models

sherpa-onnx 1.13.8 does not pad Whisper input to 30 s. It appends 1000 frames
(10 s) of zero features, the Java `OfflineWhisperModelConfig` default
(`tailPaddings`). Padding to the full 30 s window (`tailPaddings` = 3000) does not
help the base models: nb goes from 20.2 % to 24.0 %, and sv stays at 13.7 %.
Padding to 30 s also raises the 5 s latency to about 0.31 s for nb and 0.42 s for
sv. The tiny models are different: with the default padding NB-Whisper tiny
degenerates to repeated or garbled output (46.5 % WER), and it recovers to 24 %
only with 30 s padding.

| option              | MB    | 5 s latency | WER, default padding | WER, 30 s padding |
|---------------------|-------|-------------|----------------------|-------------------|
| `stt-nb` base       | 160.6 | 0.20 s      | 20.2 %               | 24.0 %            |
| `stt-nb-tiny`       | 103.6 | 0.13 s      | 46.5 %               | 24.0 %            |
| `stt-sv` base       | 160.6 | 0.30 s      | 13.7 %               | 13.7 %            |
| `stt-sv-tiny`       | 103.6 | 0.17 s      | 19.2 %               | 17.1 %            |

Sipher ships the base models. They are well under the latency budget and
need no change to the Java factory. A tiny model would also need
`OfflineWhisperModelConfig.Builder.setTailPaddings(3000)`, which
`SpeechRecognizer` does not set today.

### Licences

- `licenses/NVIDIA-Open-Model-License.txt` is the text of the October 24, 2025
  version from nvidia.com.
- The Apache-2.0 text comes from the shared `../licenses/Apache-2.0.txt`.

Both are included in full in the component `LICENSE`. For `stt-da`, `LICENSE`
also starts with the notice that the NVIDIA licence requires, and a separate
`NOTICE` file repeats it, because the licence asks for the notice to be in a
"Notice" text file. The CC-BY-4.0 components carry the licence name and URL plus
the attribution. Every `LICENSE` says the model was "converted to ONNX/int8 by
the Sipher project".
