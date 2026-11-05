#!/usr/bin/env bash
# ANCALL build: only arm64-v8a is needed for the target Android phone.
# Set ANDROID_NDK before running this script.

set -euo pipefail

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

SHERPA_ONNX_VERSION=$(grep "SHERPA_ONNX_VERSION" ./CMakeLists.txt | cut -d " " -f 2 | cut -d '"' -f 2)
MODEL_DIR="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
ASSET_DIR="./android/SherpaOnnxKws/app/src/main/assets/$MODEL_DIR"
JNI_DIR="./android/SherpaOnnxKws/app/src/main/jniLibs/arm64-v8a"

export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF

log "Building Sherpa-ONNX native libraries for arm64-v8a"
./build-android-arm64-v8a.sh

mkdir -p apks "$JNI_DIR"

if [ ! -d "$ASSET_DIR" ]; then
  MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$MODEL_DIR.tar.bz2"
  log "Downloading English GigaSpeech keyword model"
  curl -fL "$MODEL_URL" -o "$MODEL_DIR.tar.bz2"
  tar jxf "$MODEL_DIR.tar.bz2"
  rm "$MODEL_DIR.tar.bz2"

  pushd "$MODEL_DIR"
  printf 'HEY ANCALL :1.5 #0.4\n' > keywords_raw.txt
  python3 ../scripts/text2token.py \
    --text keywords_raw.txt \
    --tokens tokens.txt \
    --tokens-type bpe \
    --bpe-model bpe.model \
    --output keywords.txt
  log "Generated ANCALL keyword tokens:"
  cat keywords.txt

  rm bpe.model
  rm keywords_raw.txt
  rm -f *.int8.onnx README.md
  rm -rf test_wavs
  popd

  mv "$MODEL_DIR" "$ASSET_DIR"
fi

log "Copying arm64 native libraries into the Android app"
cp -v ./build-android-arm64-v8a/install/lib/*.so "$JNI_DIR/"

pushd android/SherpaOnnxKws
sed -i.bak 's/2048/9012/g' ./gradle.properties
./gradlew assembleDebug
popd

APK="./android/SherpaOnnxKws/app/build/outputs/apk/debug/app-debug.apk"
test -f "$APK"
OUTPUT="./apks/ancall-${SHERPA_ONNX_VERSION}-arm64-v8a-hey-ancall.apk"
mv "$APK" "$OUTPUT"
rm -f "$JNI_DIR"/*.so

log "Build complete:"
ls -lh "$OUTPUT"
