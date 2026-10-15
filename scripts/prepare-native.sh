#!/usr/bin/env bash
#
# Build sherpa-onnx (with Qualcomm QNN support) for arm64-v8a and put the native libraries the
# recognizer needs into app/src/main/jniLibs/arm64-v8a/.
#
# Requirements: git, curl, cmake (>= 3.22), make, an Android NDK (r27+).
#   export ANDROID_NDK=/path/to/ndk
#   ./scripts/prepare-native.sh
#
# Environment:
#   SHERPA_ONNX_VERSION  sherpa-onnx git tag (default v1.13.8; must match the Kotlin API in
#                        app/src/main/java/com/k2fsa/sherpa/onnx, which JNI accesses by name)
#   QNN_HTP_ARCHS        Hexagon versions to bundle (default "73 75" = Snapdragon 8 Gen 2 and
#                        8 Gen 3; one per NPU model in SpeechModel.kt)
#   WORK_DIR             build directory (default build/voice-native)
#
set -euo pipefail

SHERPA_ONNX_VERSION=${SHERPA_ONNX_VERSION:-v1.13.8}
# must match the QNN version the model's context binary was built with
QNN_VERSION=2.40.0.251030
QNN_HTP_ARCHS=${QNN_HTP_ARCHS:-73 75}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK_DIR=${WORK_DIR:-$ROOT/build/voice-native}
OUT=$ROOT/app/src/main/jniLibs/arm64-v8a

if [ -z "${ANDROID_NDK:-}" ]; then
  ANDROID_NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}
fi
if [ -z "${ANDROID_NDK:-}" ] || [ ! -d "$ANDROID_NDK" ]; then
  echo "Please set ANDROID_NDK" >&2
  exit 1
fi
export ANDROID_NDK
echo "Using NDK: $ANDROID_NDK"
echo "Using cmake: $(command -v cmake) ($(cmake --version | head -1))"

RELEASE=https://github.com/k2-fsa/sherpa-onnx/releases/download

download() {
  # download <url> <output>, with retries
  for i in 1 2 3 4 5; do
    if curl -fSL --retry 3 -o "$2" "$1"; then return 0; fi
    echo "download failed ($i), retrying..." >&2
    sleep 5
  done
  return 1
}

mkdir -p "$WORK_DIR" "$OUT"
cd "$WORK_DIR"

# ---------------------------------------------------------------------------------------------
# 1. sherpa-onnx + onnxruntime (JNI)
SHERPA_LIBS=sherpa-libs-$SHERPA_ONNX_VERSION
if [ ! -f "$SHERPA_LIBS/libsherpa-onnx-jni.so" ] || [ ! -f "$SHERPA_LIBS/libonnxruntime.so" ]; then
  if [ ! -d "sherpa-onnx-$SHERPA_ONNX_VERSION" ]; then
    git clone --depth 1 -b "$SHERPA_ONNX_VERSION" https://github.com/k2-fsa/sherpa-onnx.git \
      "sherpa-onnx-$SHERPA_ONNX_VERSION"
  fi

  if [ ! -f "qnn-include-$QNN_VERSION/include/QNN/QnnInterface.h" ]; then
    download "$RELEASE/asr-models-qnn/qnn-include-$QNN_VERSION.tar.bz2" qnn-include.tar.bz2
    tar xf qnn-include.tar.bz2
    rm qnn-include.tar.bz2
  fi
  export QNN_SDK_ROOT=$WORK_DIR/qnn-include-$QNN_VERSION

  pushd "sherpa-onnx-$SHERPA_ONNX_VERSION"
  export SHERPA_ONNX_ENABLE_TTS=OFF
  export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
  export SHERPA_ONNX_ENABLE_BINARY=OFF
  export SHERPA_ONNX_ENABLE_C_API=OFF
  export SHERPA_ONNX_ENABLE_JNI=ON
  export SHERPA_ONNX_ENABLE_QNN=ON
  export BUILD_SHARED_LIBS=ON
  ./build-android-arm64-v8a.sh
  popd

  mkdir -p "$SHERPA_LIBS"
  cp -v "sherpa-onnx-$SHERPA_ONNX_VERSION"/build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so \
    "sherpa-onnx-$SHERPA_ONNX_VERSION"/build-android-arm64-v8a/install/lib/libonnxruntime.so \
    "$SHERPA_LIBS/"
fi
cp -v "$SHERPA_LIBS"/*.so "$OUT/"

# ---------------------------------------------------------------------------------------------
# 2. QNN runtime (HTP backend). Must match the QNN version used to build the context binary.
QNN_LIBS=qnn-libs-$QNN_VERSION
if [ ! -f "$QNN_LIBS/libQnnHtp.so" ]; then
  download "$RELEASE/asr-models-qnn/qnn-libs-$QNN_VERSION.tar.bz2" qnn-libs.tar.bz2
  tar xf qnn-libs.tar.bz2
  rm qnn-libs.tar.bz2
fi
cp -v "$QNN_LIBS/libQnnHtp.so" "$QNN_LIBS/libQnnSystem.so" "$OUT/"
for v in $QNN_HTP_ARCHS; do
  cp -v "$QNN_LIBS/libQnnHtpV${v}Stub.so" "$QNN_LIBS/libQnnHtpV${v}Skel.so" "$OUT/"
done

# ---------------------------------------------------------------------------------------------
# 3. sanity checks: every library is an ELF for the right machine
#    (e_machine: 183 = AArch64 for the app side, 164 = Qualcomm Hexagon for the DSP skel)
machine() { od -An -tu2 -j18 -N2 "$1" | tr -d ' '; }
for f in "$OUT"/*.so; do
  head -c4 "$f" | grep -q $'\x7fELF' || { echo "$f is not an ELF file" >&2; exit 1; }
  m=$(machine "$f")
  case "$(basename "$f")" in
    libQnnHtpV*Skel.so) want=164 ;;
    *) want=183 ;;
  esac
  if [ "$m" != "$want" ]; then
    echo "$f: unexpected ELF machine $m (want $want)" >&2
    exit 1
  fi
done

echo
echo "Native libraries for the recognizer are ready:"
ls -lh "$OUT"
