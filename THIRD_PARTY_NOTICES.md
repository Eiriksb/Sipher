# Third-party notices

Sipher bundles the following components. Each keeps its own licence.

| Component | Version | Licence | Source |
|---|---|---|---|
| sherpa-onnx (Java API and native libraries) | 1.13.8 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (native library, shipped inside sherpa-onnx) | 1.28.2 | MIT | https://github.com/microsoft/onnxruntime |
| ONNX Runtime Java API and JNI glue (built from source) | 1.28.0 | MIT | https://github.com/microsoft/onnxruntime/tree/v1.28.0/java |
| Moonshine Tiny (English speech recognition model, sherpa-onnx export) | 2026-02-27 | MIT | https://github.com/moonshine-ai/moonshine, https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27 |
| Silero VAD (voice activity detection model) | v4 | MIT | https://github.com/snakers4/silero-vad |

Optional language packs downloaded from the in-game Languages screen list their models, sources and licences in the
game before download and in `<game>/sipher/models/<pack>/LICENSE` afterwards.

The test suite uses a short excerpt of John F. Kennedy's 1961 inaugural address (public domain); it is not part of the
mod jar.

Simple Voice Chat is a separate mod and is not bundled.
