# Sipher — live captions for Simple Voice Chat

**See what people say.** Sipher turns voice chat into live captions — above players' heads and in a transcript box — and can translate them, so friends who speak different languages can play together.

Everything runs **on your own computer**. No audio ever leaves your PC, and there are no accounts, API keys or cloud services.

---

## Features

- 🎙️ **Live captions** for [Simple Voice Chat](https://www.curseforge.com/minecraft/mc-mods/simple-voice-chat). Captions appear while you are still talking and settle into a final sentence when you pause or release push-to-talk.
- 💬 **Caption bubbles** above players' heads, plus a **transcript box** you can drag anywhere (open chat and drag it).
- 🌍 **Translation between 16 languages.** Everyone reads captions in their own language, whatever language the speaker uses.
- 🇬🇧 **English works out of the box.** Other languages are optional packs you add from the in-game *Languages* screen.
- 🔒 **Private by design.** Speech recognition and translation run locally on your CPU. The server only ever sees caption *text*, and only if you choose to share it.
- 👥 **Respects voice chat.** Your captions reach exactly the players who can hear you — your group, or players within voice or whisper range — never the whole server.
- 🧩 **Works client-only.** Join any server: without Sipher on the server, your captions simply stay on your screen.
- ⚡ **Light.** English recognition runs about a hundred times faster than real time on a normal gaming CPU, on a low-priority background thread.

## Languages

| Language | Captions | Translation |
|---|---|---|
| English | built in | built in (pivot language) |
| Español · Deutsch · Français · Italiano · Nederlands · Português · Polski · Русский | language pack | language pack |
| Norsk bokmål · Svenska · Dansk · Suomi | language pack | language pack |
| 中文 · 日本語 · 한국어 | language pack | language pack |

A language pack contains everything you need to *speak* and *read* that language (speech recognition plus translation to and from English). You only need the pack for your own language — Sipher translates everyone else's captions through English. Packs are roughly 100–700 MB depending on the language; the exact size is shown before you download.

## How to use

1. Install **Sipher** and **Simple Voice Chat** on your client. Optionally install Sipher on the server too, so players can see each other's captions.
2. Join a world and talk on voice chat. Captions appear above your head (in third person) and in the transcript box.
3. Press **O** (or *Mods → Sipher → Config*) for settings: turn captions on/off, choose whether to share them, bubble size and colours, transcript box.
4. Speak another language, or want captions translated? Open **Languages…** in the settings, pick your language and click **Download**.

## Privacy and network use

Please read this — it is exactly what Sipher does.

- **Your voice stays on your computer.** Audio is transcribed locally and never sent anywhere by Sipher.
- **Caption text** is sent to the server you are playing on — only when the server has Sipher and *Share my captions* is on — and the server forwards it only to players who can hear you on voice chat.
- **Nothing is downloaded automatically.** The mod file contains everything needed for English. Language packs are downloaded **only when you click *Download*** on a specific pack. Before downloading, Sipher shows the pack's size, where it comes from and its licences.
- **Language packs contain data only** — machine-learning model weights, vocabularies and licence texts. They never contain code or programs: all code, including native libraries, is inside the mod file you install from CurseForge. The downloader refuses any other kind of file.
- Packs are downloaded over HTTPS from this project's GitHub releases and checked against SHA-256 checksums built into the mod before they are used. Files are stored in `.minecraft/sipher/models/`.
- **No telemetry, no analytics, no accounts.** Downloads can be switched off completely with `allow_downloads = false` in `config/sipher-client.toml`.

Sipher uses on-device machine-learning models (speech recognition and translation). They only transcribe and translate what was said; nothing is generated beyond that.

## Requirements

- Minecraft **1.21.1** with **NeoForge**
- **Simple Voice Chat** 2.5 or newer
- 64-bit **Windows 10/11**, **macOS 11+** (Intel or Apple Silicon) or **Linux** (x64 or ARM64)

The same file works on clients and servers. Dedicated servers never load the speech models.

## FAQ

**Do other players need Sipher?** To *see* captions, yes. Players without Sipher can still join and play normally.

**Does the server need Sipher?** Only if players should see each other's captions. Without it, everyone sees just their own.

**Does it work offline?** Yes. After a language pack is installed, no internet connection is needed.

**How accurate is it?** Very good for clear speech; like any speech recognition it can mishear names, slang and noisy microphones. Captions are shown as live (dimmed) while they may still change.

**Does it use a lot of performance?** Speech recognition is fast and runs on a background thread at low priority. Language packs for some languages use larger models and more CPU.

## Credits

Sipher is open source (MIT). It builds on the work of:

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) and [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT)
- [Moonshine](https://github.com/moonshine-ai/moonshine) English speech recognition (MIT) and [Silero VAD](https://github.com/snakers4/silero-vad) (MIT)
- [OPUS-MT](https://github.com/Helsinki-NLP/Opus-MT) translation models by the University of Helsinki (Apache-2.0 / CC-BY-4.0)
- NVIDIA NeMo speech models (CC-BY-4.0; NVIDIA Open Model License for Danish), [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) (FunASR Model License), [NB-Whisper](https://huggingface.co/NbAiLab) by the National Library of Norway (Apache-2.0), [KB-Whisper](https://huggingface.co/KBLab) by the National Library of Sweden (Apache-2.0), GigaAM by SaluteDevices (MIT) and [ElanMT](https://huggingface.co/Mitsua) by Mitsua (CC-BY-SA-4.0)

Full licences and attributions are included with the mod and with each language pack.

**Source code and issues:** [github.com/Eiriksb/Sipher](https://github.com/Eiriksb/Sipher)
