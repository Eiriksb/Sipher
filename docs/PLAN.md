# Sipher plan

Sipher replaces the earlier `en_translator` prototype: one NeoForge 1.21.1 jar, English speech recognition built in,
every other language an opt-in in-game download.

## Distribution rules we follow (CurseForge and Modrinth, checked 2026-09)

Neither platform forbids a mod from downloading data at runtime. CurseForge's moderation policy only bans external
download links on project pages; its author terms require accurate disclosure (§3.1) and let moderators remove mods with
security impact (§3.7). Precedents on CurseForge include MCEF (downloads Chromium natives automatically), WhisperLib
(in-game model manager) and VoiceLib (downloads a Vosk model on first launch). Modrinth requires clear disclosure of
network use; live translation falls under its "AI functionality" content disclosure. Size limits: CurseForge 2 GB per
file, Modrinth 500 MiB.

Sipher's download contract:

1. Nothing is downloaded until the player clicks *Download* on a specific pack; the dialog shows size, host and licence.
2. Packs contain data only (ONNX weights, vocabularies, licences). All code and natives ship inside the reviewed jar.
3. The pack catalogue (URLs, SHA-256, sizes) is compiled into the jar — no remote manifest.
4. Files are written to `<game>/sipher/models/` via temp file → hash check → atomic rename; redirects only to allowlisted
   hosts (GitHub, Hugging Face).
5. No telemetry, no uploads; downloads can be disabled in config; packs can also be installed by hand (same checks).
6. Models licensed non-commercially (NLLB, Moonshine non-English, NVIDIA's Portuguese STT) are never used.

Before the first public release: open a CurseForge support ticket describing the downloader and get written approval.

## Architecture

- **Capture:** Simple Voice Chat `ClientSoundEvent` — the local player's own microphone, 48 kHz PCM, 20 ms frames.
- **Speech:** 48→16 kHz windowed-sinc decimation → Silero VAD (segments capped at 8 s) → sherpa-onnx offline
  recogniser. Live partials re-transcribe the current utterance every ~600 ms; the final caption is produced when the
  VAD closes the segment or push-to-talk is released.
- **Wire protocol (English pivot):** a caption carries `{language, text, english}`. The speaker translates their own
  speech to English; each listener translates English into their reading language. Every player therefore needs only
  their own language pack, however many languages are spoken on the server.
- **Server:** relays text only, to players who can hear the speaker (voice chat group, voice range, or whisper range —
  whisper state comes from the server's own microphone packets), with a per-player rate limit and text sanitising.
  The network channel is optional, so either side can be vanilla.
- **Natives:** one libonnxruntime (sherpa-onnx's) serves sherpa-onnx and ONNX Runtime Java. ONNX Runtime Java is
  embedded without its natives (Gradle artifact transform); its JNI glue is cross-built with zig for six platforms
  against sherpa's runtime and committed, with a CI job that reproduces it bit for bit.

## Status

| Phase | Scope | State |
|---|---|---|
| 0 | Spikes: shared ONNX Runtime, Moonshine latency, OPUS-MT tiny export, model catalogue | done |
| 1 | English base mod: natives loader, VAD + Moonshine pipeline, bubbles, transcript, settings | done |
| 2 | Server relay: optional channel, voice-chat-aware routing, rate limits, server config | done |
| 3 | Language packs: catalogue, downloader + Languages screen, SentencePiece + Marian decoder in Java, English pivot | done (15 languages, `models-v1`) |
| 4 | Captions for players without Sipher (listener-side ASR), English HQ pack, CurseForge/Modrinth pages | later |

## Phase 3 findings

**Translation.** Helsinki-NLP's OPUS-MT *tiny* models export to int8 ONNX at ~28 MB per direction (standard OPUS-MT:
~117 MB), translate a chat sentence in ~3 ms, and match standard quality for en→X chat (weaker for X→en). Tiny exists
for en↔{es, de, fr, it, nl, ru} and {zh, ko}→en. Other directions use standard or multilingual OPUS-MT (`>>xxx<<`
target tokens). Export notes: `text2text-generation-with-past`, opset 18, dynamic int8 with `EnableSubgraph`; the
output projection must be pre-transposed before quantising or int8 decoding is 3–18× slower; tiny models cap at 256
positions; the merged decoder returns empty encoder K/V after step 0 (keep step-0 encoder K/V). Tokenizers are
SentencePiece unigram (nmt_nfkc) — a pure-Java port has been validated against sentencepiece in Python.

**Speech.** Per-language models of ~137–240 MB: NeMo FastConformer (es, de; fr, it, nl, pl need conversion),
GigaAM-v3 (ru, MIT), NB-Whisper base (nb, Apache-2.0), KB-Whisper base (sv), SenseVoice-Small (zh/ja/ko shared, 240 MB).
Parakeet-TDT-0.6B-v3 (670 MB, CC-BY-4.0, 25 European languages) as an optional "European all-in-one" pack and the only
redistributable option for pt and fi.

**Decisions taken (2026-09-25):**

- Japanese translation: ElanMT-BT (CC-BY-SA-4.0; applies to the model files only, not the mod).
- Danish speech: NVIDIA parakeet-rnnt-110m-da-dk (NVIDIA Open Model License, notice shipped).
- Korean translation: opus-mt-tc-big (CC-BY-4.0). Its upstream vocab.json is the target vocabulary only; the build
  rebuilds the source vocabulary from source.spm.
- Italian, Dutch and Polish use the shared Parakeet-TDT-0.6B-v3 model (measured on the same FLEURS clips: it 5.3% vs
  11.3%, nl 9.9% vs 34.5%, pl 14.3% vs 19.1% WER); French and Danish keep their dedicated models, which beat it.
- Norwegian and Swedish use Whisper base fine-tunes (NB-Whisper, KB-Whisper); tiny was less accurate.
- Packs are hosted on this repository's `models-v1` GitHub release; the tooling is in `tools/packs/`.

## Known limitations

- Moonshine in sherpa-onnx 1.13.8 returns empty text for >9.2 s of audio; segments are capped at 8 s until the fix ships.
- Only the speaker's own microphone is transcribed; players without Sipher are not captioned yet (phase 4).
- The shared European speech model uses about 1 GB of native memory while in use.
- Swedish loses some accuracy in int8 (13.7% vs 8.9% WER fp32 on 10 clips); an fp32 Swedish pack is an option.
- The SenseVoice (zh/ja/ko) FunASR model licence has a no-denigration clause and auto-applying revisions (§6); review
  before a commercial use of the packs.
