# Sipher

Live, private captions for [Simple Voice Chat](https://github.com/henkelmax/simple-voice-chat) on NeoForge 1.21.1.

Sipher turns what players say on voice chat into captions: bubbles above their heads and a transcript box. Everything
runs on your own computer. English works out of the box; other languages are optional packs you download from inside
the game.

> **Status:** early development (0.1). English captions work end to end. Translation and language packs are next —
> see [docs/PLAN.md](docs/PLAN.md).

## Features

- Offline speech recognition of your own voice chat microphone (Moonshine Tiny, runs ~100× faster than real time).
- Live captions that grow while you talk, finalised when you pause or release push-to-talk.
- Caption bubbles above players and a draggable transcript box (drag it while chat is open).
- With Sipher on the server, your captions reach exactly the players who can hear you: your voice chat group, or
  players within voice (or whisper) range. They are never broadcast to the whole server.
- Works client-only too: without Sipher on the server, captions simply stay on your screen.
- Settings: press **O** in game, or use the Mods menu → Sipher → Config.

## Privacy and network use

- **No audio ever leaves your computer.** Speech recognition runs locally; the server only ever receives caption text,
  and only when *Share my captions* is on.
- **Nothing is downloaded automatically.** The mod jar contains everything needed for English. Optional language packs
  (speech and translation models) are downloaded only when you click *Download* on a specific pack in the in-game
  *Languages* screen, which first shows the size, the source and the licence. Packs are data files only (model weights
  and vocabularies), fetched from this project's GitHub releases, and verified against SHA-256 checksums built into the
  mod. No code or native library is ever downloaded — all of it is inside the jar.
- No telemetry, no accounts, no API keys, no third-party services.
- Downloads can be switched off completely with `allow_downloads = false` in `config/sipher-client.toml`.

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1+
- Simple Voice Chat 2.5+ (NeoForge)
- 64-bit Windows, macOS 11+ or Linux (x64 or ARM64)

Install the same jar on clients and, optionally, on the server. The server never loads the speech models or natives.

## How it works

```
your microphone (Simple Voice Chat, 48 kHz)
  → anti-aliased resampling to 16 kHz → Silero voice activity detection
  → Moonshine speech recognition (sherpa-onnx)          ── live + final captions on your screen
  → caption text to the server → players who can hear you
```

Speech recognition uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Translation (coming next) uses ONNX
Runtime Java on the *same* ONNX Runtime library that ships with sherpa-onnx, through a small JNI glue library built from
ONNX Runtime's sources ([natives/onnxruntime4j_jni](natives/onnxruntime4j_jni)). That keeps the jar at ~90 MB for six
platforms.

## Building

```bash
./gradlew build
```

Gradle downloads JDK 21 if needed. The build fetches sherpa-onnx's release jars and the built-in models, and verifies
all of them against pinned SHA-256 checksums (`gradle/third-party-checksums.txt` and `build.gradle`). The jar is
written to `build/libs/`. `./gradlew runClient` starts a development client with Simple Voice Chat.

The ONNX Runtime JNI glue binaries are committed; rebuild them with `natives/onnxruntime4j_jni/build.sh` (needs a JDK
and [zig](https://ziglang.org) 0.16). CI checks that the committed binaries are reproducible.

## Licence

Sipher is MIT licensed. Bundled third-party components and models keep their own licences; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
