# Sipher

Live, private captions for [Simple Voice Chat](https://github.com/henkelmax/simple-voice-chat) on Fabric and NeoForge,
Minecraft 1.21.1 to 26.3.

Sipher turns what players say on voice chat into captions: bubbles above their heads and a transcript box. Everything
runs on your own computer. English works out of the box; other languages are optional packs you download from inside
the game.

> **Status:** beta (0.1). English captions, translation and 15 downloadable language packs work end to end in tests;
> real-world testing is ongoing. What is left before 1.0 is tracked in the
> [1.0 milestone](https://github.com/Eiriksb/Sipher/milestone/1).

## Features

- Offline speech recognition of your own voice chat microphone (Moonshine Tiny, runs ~100× faster than real time).
- Live captions that grow while you talk, finalised when you pause or release push-to-talk.
- Caption bubbles above players and a draggable transcript box (drag it while chat is open).
- With Sipher on the server, your captions reach exactly the players who can hear you: your voice chat group, or
  players within voice (or whisper) range. They are never broadcast to the whole server.
- Works client-only too: without Sipher on the server, captions simply stay on your screen.
- Settings: press **O** in game, or use the Mods menu → Sipher → Config (on Fabric with
  [Mod Menu](https://modrinth.com/mod/modmenu)).

## Privacy and network use

- **No audio ever leaves your computer.** Speech recognition runs locally; the server only ever receives caption text,
  and only when *Share my captions* is on. Server mods can also receive the captions you share, for example to let
  villagers hear you (see [For mod developers](#for-mod-developers)).
- **Nothing is downloaded automatically.** The mod jar contains everything needed for English. Optional language packs
  (speech and translation models) are downloaded only when you click *Download* on a specific pack in the in-game
  *Languages* screen, which first shows the size, the source and the licence. Packs are data files only (model weights
  and vocabularies), fetched from this project's GitHub releases, and verified against SHA-256 checksums built into the
  mod. No code or native library is ever downloaded — all of it is inside the jar.
- No telemetry, no accounts, no API keys, no third-party services.
- Downloads can be switched off completely with `allow_downloads = false` in `config/sipher-client.toml`.

## Requirements

- Minecraft 1.21.1, 1.21.4, 1.21.5, 1.21.6–1.21.8, 1.21.9–1.21.10, 1.21.11, 26.1–26.1.2, 26.2 or 26.3
- Fabric with Fabric API, or NeoForge
- Simple Voice Chat 2.5+ for the same loader
- 64-bit Windows, macOS 11+ or Linux (x64 or ARM64)

There is one jar per loader and Minecraft version, named like `sipher-fabric-<version>+1.21.1.jar`. Install the one for
your loader and Minecraft version on clients and, optionally, on the server. The server never loads the speech models
or natives.

## How it works

```
your microphone (Simple Voice Chat, 48 kHz)
  → anti-aliased resampling to 16 kHz → Silero voice activity detection
  → Moonshine speech recognition (sherpa-onnx)          ── live + final captions on your screen
  → caption text to the server → players who can hear you
```

Speech recognition uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Translation uses OPUS-MT/Marian models
on ONNX Runtime Java, running on the *same* ONNX Runtime library that ships with sherpa-onnx, through a small JNI glue library built from
ONNX Runtime's sources ([natives/onnxruntime4j_jni](natives/onnxruntime4j_jni)). That keeps the jar at ~90 MB for six
platforms.

## For mod developers

On the server, Sipher fires `io.github.eiriksb.sipher.api.PlayerCaptionEvent` (server thread) for every caption a
player shares, live and final: on NeoForge it is posted on `NeoForge.EVENT_BUS`, on Fabric you register a listener
with `PlayerCaptionEvent.EVENT.register(event -> ...)`. It carries the spoken language, the transcript and its English
translation. [They Will Talk](https://github.com/Eiriksb/they-will-talk) uses it to let villagers hear players.

`io.github.eiriksb.sipher.api.SipherCaptions.show(...)` goes the other way: it shows a caption for any entity (a
talking NPC) to the players you choose, as a bubble above the entity and in their transcript, translated into each
player's reading language like a player's caption. They Will Talk uses it for what villagers say.

Both stay compatible within a major version; see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Building

The newest jar from `main` is always on the [Development build](https://github.com/Eiriksb/Sipher/releases/tag/dev)
pre-release: CI replaces it after every merge that passes on all six platforms. To build it yourself:

```bash
./gradlew :1.21.1-fabric:build
```

Every Minecraft version and loader is its own Gradle project, `<minecraft>-<loader>` (listed in `settings.gradle.kts`,
with their dependencies in `stonecutter.properties.toml`), and writes its jar to `versions/<minecraft>-<loader>/build/libs/`.
`./gradlew dist` builds all of them into `build/dist/`, which takes a while: each Minecraft version is set up once.
`./gradlew :1.21.1-neoforge:runClient` starts a development client with Simple Voice Chat (also `runServer`, and
`runClient2` for a second player). For testing without a microphone, add `-PdebugCaption="Hello there"`: every few
seconds you say it (shared with the server like real speech), alternating with the nearest mob.
`-PdebugAudio=core/src/test/resources/audio/jfk.wav` plays a recording as your microphone instead, over and over, through
real speech recognition, and logs what is recognised and which captions arrive from other players. `-PquickPlay=<world>`
or `-PquickPlayServer=localhost` joins a world or server right away, `-Pusername=<name>` picks the player's name.
`runServer -PdebugRelay` logs every caption the server relays and to whom.

`tools/runtime-test/run.sh 1.21.1-fabric` plays one Minecraft version for real without a screen (Linux with `xvfb-run`
or `gamescope`): a Fabric dedicated server (`1.21.1-neoforge` for NeoForge) must start with Sipher's voice chat plugin,
then a Fabric and a NeoForge player join it, each with that recording as its microphone. Each must recognise it, the
server must relay each one's captions to the other, and each must receive and draw the other's. Logs and screenshots
go to `build/runtime-test/report/`. CI runs it with both servers for every Minecraft version.

The code that doesn't touch Minecraft (speech, translation, language packs, natives, settings) lives in `core/` and is
tested once with `./gradlew :core:test`. The Minecraft code in `src/` is shared by every version through
[Stonecutter](https://stonecutter.kikugie.dev/): where Minecraft or a loader changed, the code has `//? if >=1.21.9`
or `//? if fabric` comments, and `src/` is always written for one active version (`stonecutter.gradle.kts`). Switch it
with `./gradlew "Set active project to 26.3-fabric"` to work on another version, and back with
`./gradlew "Reset active project"` before committing.

Gradle downloads JDK 21 and 25 if needed. The build fetches sherpa-onnx's release jars and the built-in models, and
verifies all of them against pinned SHA-256 checksums (`gradle/third-party-checksums.txt` and `core/build.gradle.kts`).

Releases are published by pushing a version tag; see [docs/RELEASING.md](docs/RELEASING.md).

The ONNX Runtime JNI glue binaries are committed; rebuild them with `natives/onnxruntime4j_jni/build.sh` (needs a JDK
and [zig](https://ziglang.org) 0.16). CI checks that the committed binaries are reproducible.

## Licence

Sipher is MIT licensed. Bundled third-party components and models keep their own licences; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
