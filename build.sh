#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
OUT="$ROOT/.local"
mkdir -p "$OUT/tmp"
export TMPDIR="$OUT/tmp/"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-"$OUT/gradle-home"}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-"$OUT/android-home"}"

if [[ "$(uname -s)" == Darwin ]]; then
    export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}"
    if [[ -z "${JAVA_HOME:-}" ]]; then
        export JAVA_HOME
        JAVA_HOME="$(/usr/libexec/java_home -v '17+')"
    fi
fi

gradle_args=(--no-daemon "-Djava.io.tmpdir=$TMPDIR")
if [[ -n "${TTS_DEBUG_KEYSTORE:-}" ]]; then
    gradle_args+=("-PttsDebugKeystore=$TTS_DEBUG_KEYSTORE")
elif [[ -f "$HOME/.android/debug.keystore" ]]; then
    gradle_args+=("-PttsDebugKeystore=$HOME/.android/debug.keystore")
fi

git submodule update --init --recursive -- vendor/gpt-sovits-onnx-rs
./scripts/build-native.sh
./gradlew "${gradle_args[@]}" :app:assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
unzip -Z1 "$APK" | awk '
    tolower($0) ~ /\.(onnx|ort|gguf|pth|ckpt|safetensors|wav)([._]|$)/ {
        print "Unexpected model or reference audio in APK: " $0 > "/dev/stderr"
        invalid = 1
    }
    END { exit invalid }
'
printf 'APK: %s\n' "$APK"
