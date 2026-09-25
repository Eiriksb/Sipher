#!/usr/bin/env bash
# Builds ONNX Runtime's Java JNI glue (libonnxruntime4j_jni) for every platform Sipher supports, linked against the
# libonnxruntime that ships inside sherpa-onnx's native-lib jars, so one ONNX Runtime serves both libraries.
#
# Uses `zig cc` as a cross compiler: Linux builds target glibc 2.17 (like sherpa-onnx's own libraries), macOS builds
# target macOS 11, and Windows builds use the MinGW ABI (the glue only calls ONNX Runtime's plain C API).
#
# Requirements: bash, curl, unzip, git, a JDK (JAVA_HOME) and zig (ZIG, default: zig on PATH).
# Usage: natives/onnxruntime4j_jni/build.sh [platform...]   (default: all platforms)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ZIG="${ZIG:-zig}"
: "${JAVA_HOME:?JAVA_HOME must point to a JDK}"

prop() { grep "^$1=" "$ROOT/gradle.properties" | cut -d= -f2; }
ORT_VERSION="$(prop onnxruntime_java_version)"
SHERPA_VERSION="$(prop sherpa_onnx_version)"
PLATFORMS=("$@")
if [ ${#PLATFORMS[@]} -eq 0 ]; then
  PLATFORMS=(linux-x64 linux-aarch64 osx-x64 osx-aarch64 win-x64 win-arm64)
fi

WORK="$ROOT/build/ort-jni"
SRC="$WORK/onnxruntime-$ORT_VERSION"
mkdir -p "$WORK"

# 1. ONNX Runtime sources at the exact version of the Java classes we embed.
if [ ! -d "$SRC/java/src/main/native" ]; then
  rm -rf "$SRC"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "v$ORT_VERSION" --filter=blob:none --sparse \
    https://github.com/microsoft/onnxruntime "$SRC"
fi
git -C "$SRC" sparse-checkout set java/src/main include/onnxruntime/core/session include/onnxruntime/core/providers \
  orttraining/orttraining/training_api/include

# 2. JNI headers generated from the Java sources, plus the one CMake-generated header the C code includes.
HEADERS="$WORK/headers-$ORT_VERSION"
if [ ! -f "$HEADERS/ai_onnxruntime_OnnxRuntime.h" ]; then
  rm -rf "$HEADERS" "$WORK/classes"
  (cd "$SRC/java" && "$JAVA_HOME/bin/javac" -nowarn -h "$HEADERS" -d "$WORK/classes" \
    $(find src/main/java src/main/jvm -name '*.java') 2>&1 | grep -v '^Note:' || true)
fi
printf '#pragma once\n#define ORT_VERSION "%s"\n' "$ORT_VERSION" > "$HEADERS/onnxruntime_config.h"

build() {
  local platform="$1" target jni_md out ort flags=()
  case "$platform" in
    linux-x64)     target=x86_64-linux-gnu.2.17;  jni_md="$JAVA_HOME/include/linux"; out=libonnxruntime4j_jni.so;  ort=libonnxruntime.so ;;
    linux-aarch64) target=aarch64-linux-gnu.2.17; jni_md="$JAVA_HOME/include/linux"; out=libonnxruntime4j_jni.so;  ort=libonnxruntime.so ;;
    osx-x64)       target=x86_64-macos.11.0;      jni_md="$JAVA_HOME/include/linux"; out=libonnxruntime4j_jni.dylib; ort=libonnxruntime.dylib ;;
    osx-aarch64)   target=aarch64-macos.11.0;     jni_md="$JAVA_HOME/include/linux"; out=libonnxruntime4j_jni.dylib; ort=libonnxruntime.dylib ;;
    win-x64)       target=x86_64-windows-gnu;     jni_md="$HERE/include/win32";      out=onnxruntime4j_jni.dll;     ort=onnxruntime.dll ;;
    win-arm64)     target=aarch64-windows-gnu;    jni_md="$HERE/include/win32";      out=onnxruntime4j_jni.dll;     ort=onnxruntime.dll ;;
    *) echo "unknown platform $platform" >&2; exit 1 ;;
  esac

  local dir="$WORK/$platform"
  mkdir -p "$dir"
  local jar="$dir/sherpa-onnx-native-lib-$platform-$SHERPA_VERSION.jar"
  if [ ! -f "$jar" ]; then
    curl -sSfL -o "$jar" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-native-lib-$platform-$SHERPA_VERSION.jar"
  fi
  unzip -oqj "$jar" "sherpa-onnx/native/$platform/$ort" -d "$dir"

  case "$platform" in
    linux-*) flags=(-fPIC "$dir/$ort" "-Wl,-rpath,\$ORIGIN" "-Wl,-soname,$out") ;;
    osx-*)   flags=("$dir/$ort" "-Wl,-rpath,@loader_path" "-Wl,-install_name,@rpath/$out") ;;
    win-*)
      local machine; [ "$platform" = win-x64 ] && machine=i386:x86-64 || machine=arm64
      printf 'LIBRARY onnxruntime.dll\nEXPORTS\n  OrtGetApiBase\n  OrtSessionOptionsAppendExecutionProvider_CPU\n' > "$dir/onnxruntime.def"
      "$ZIG" dlltool -d "$dir/onnxruntime.def" -l "$dir/libonnxruntime.dll.a" -m "$machine"
      flags=("$dir/libonnxruntime.dll.a") ;;
  esac

  "$ZIG" cc -target "$target" -O2 -s -shared -std=c11 -D_GNU_SOURCE -Wno-everything \
    -I"$JAVA_HOME/include" -I"$jni_md" -I"$HEADERS" -I"$SRC/include" -I"$SRC/include/onnxruntime/core/session" \
    -I"$SRC/orttraining/orttraining/training_api/include" \
    "$SRC"/java/src/main/native/*.c "${flags[@]}" -o "$dir/$out"

  mkdir -p "$HERE/$platform"
  cp "$dir/$out" "$HERE/$platform/$out"
  echo "built $platform/$out ($(wc -c < "$dir/$out") bytes)"
}

for platform in "${PLATFORMS[@]}"; do
  build "$platform"
done

# Provenance for reviewers: what each binary was built from.
{
  echo "# ONNX Runtime JNI glue built by natives/onnxruntime4j_jni/build.sh"
  echo "# onnxruntime source: v$ORT_VERSION (MIT), linked against sherpa-onnx v$SHERPA_VERSION's libonnxruntime; zig $("$ZIG" version)"
  (cd "$HERE" && find . -mindepth 2 -type f \( -name '*.so' -o -name '*.dylib' -o -name '*.dll' \) | sort | sed 's|^\./||' | xargs sha256sum)
} > "$HERE/checksums.txt"
