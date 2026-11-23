#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/.local"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-"$SDK/ndk/29.0.14206865"}}"
TARGET=aarch64-linux-android
API=28

case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64 ;;
  Linux) HOST=linux-x86_64 ;;
  *) echo "Build on macOS or x86-64 Linux." >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST"
LINKER="$TOOLCHAIN/bin/$TARGET$API-clang"
if [[ ! -x "$LINKER" ]]; then
  echo "Set ANDROID_NDK_ROOT to a working Android NDK installation ($LINKER)." >&2
  exit 1
fi

RUNTIME="$OUT/runtime"
mkdir -p "$RUNTIME" "$OUT/tmp"
export TMPDIR="$OUT/tmp/"
"$ROOT/vendor/gpt-sovits-onnx-rs/scripts/prepare_android_runtime.sh" \
  --output-dir "$RUNTIME" --archive-dir "${ORT_ARCHIVE_DIR:-"$OUT/archives"}"

export CARGO_HOME="${CARGO_HOME:-"$OUT/cargo-home"}"
export CARGO_TARGET_DIR="$OUT/cargo-target"
export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_HOME="$NDK" ANDROID_NDK="$NDK"
export ANDROID_PLATFORM="android-$API"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER"
export CC_aarch64_linux_android="$LINKER"
export CXX_aarch64_linux_android="$TOOLCHAIN/bin/$TARGET$API-clang++"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
cat > "$OUT/android-toolchain.cmake" <<CMAKE
set(ANDROID_ABI arm64-v8a CACHE STRING "" FORCE)
set(ANDROID_PLATFORM android-$API CACHE STRING "" FORCE)
include("$NDK/build/cmake/android.toolchain.cmake")
CMAKE
export CMAKE_TOOLCHAIN_FILE_aarch64_linux_android="$OUT/android-toolchain.cmake"
export ORT_LIB_LOCATION="$RUNTIME" ORT_PREFER_DYNAMIC_LINK=1
export CARGO_ENCODED_RUSTFLAGS=$'-C\x1ftarget-cpu=generic\x1f-C\x1flink-arg=-Wl,-z,max-page-size=16384\x1f-C\x1flink-arg=-Wl,-z,common-page-size=16384'

cargo build --locked --manifest-path native/Cargo.toml --target "$TARGET" --release

JNI="$OUT/jni/arm64-v8a"
mkdir -p "$JNI"
cp "$CARGO_TARGET_DIR/$TARGET/release/libgpt_sovits_tts.so" \
  "$RUNTIME/libonnxruntime.so" "$TOOLCHAIN/sysroot/usr/lib/$TARGET/libc++_shared.so" "$JNI/"
printf 'Android libraries: %s\n' "$JNI"
