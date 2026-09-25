# Draft: CurseForge pre-release question

Send via https://support.curseforge.com/support/tickets/new before the first public upload.

---

**Subject:** Pre-submission question: opt-in, in-game download of ML model data files (Minecraft mod "Sipher")

Hello,

Before submitting a new Minecraft mod I'd like to confirm that its optional download feature is acceptable.

**The mod:** Sipher (NeoForge 1.21.1, MIT, source: https://github.com/Eiriksb/Sipher) adds offline live captions and
translation to Simple Voice Chat. Speech recognition and translation run locally with ONNX models; no audio or text is
sent to any online service.

**What ships in the jar (~90 MB):** all code and every native library (sherpa-onnx and ONNX Runtime for Windows, macOS
and Linux), plus the English speech model. English captions work with no downloads at all.

**The optional download:** other languages need speech/translation models of roughly 30–400 MB each. Players can add a
language from an in-game "Languages" screen:

- Nothing is downloaded unless the player clicks *Download* on a specific language pack. The confirmation shows the
  size, the source host and the model licences first. Downloads can also be disabled entirely in the config.
- Packs contain **data files only** (ONNX model weights, vocabulary/tokenizer files, licence texts). The downloader
  refuses any other file type; no code, executables or native libraries are ever downloaded.
- Files come from the project's own GitHub releases (Hugging Face as a mirror), are fetched over HTTPS with a host
  allowlist, and are verified against SHA-256 checksums compiled into the mod before use. There is no remote manifest.
- Files are stored under `<game>/sipher/models/`. No telemetry, no accounts, no uploads.
- All models are under licences that allow redistribution (MIT, Apache-2.0, CC-BY-4.0); each pack includes its licence
  and attribution. Non-commercial models are not used.

The project page will describe all of this in a "Privacy and network use" section.

Is this acceptable under CurseForge's policies? If you'd prefer changes (for example hosting the packs differently,
or wording on the project page), I'm happy to adjust before submitting.

Thank you,
Eirik
