#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-"$HOME/Android/Sdk"}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-"$ANDROID_HOME/ndk/29.0.14033849"}

cd "$ROOT/native"
cargo ndk \
    --platform 26 \
    --target arm64-v8a \
    --target x86_64 \
    --output-dir "$ROOT/app/src/main/jniLibs" \
    build --release --locked
