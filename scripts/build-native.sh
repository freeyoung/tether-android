#!/usr/bin/env bash
# Build libtether.so (go/) for Android and put it in app/src/main/jniLibs/,
# where Gradle packages it. The app's preBuild runs this script, so a Gradle
# build needs no extra step.
#
# Usage: scripts/build-native.sh [ABI...]   (default: arm64-v8a x86_64)
#
# The engine is the module github.com/freeyoung/tether-core-go. Its version
# in go/go.mod is the version that the engine names in hello.
set -euo pipefail

ndk_version="30.0.16248370"
min_sdk=29

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/app/src/main/jniLibs"
abis=("$@")
[[ ${#abis[@]} -gt 0 ]] || abis=(arm64-v8a x86_64)

# The SDK from ANDROID_HOME, or from sdk.dir in local.properties.
sdk="${ANDROID_HOME:-}"
if [[ -z "$sdk" && -f "$root/local.properties" ]]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")"
fi
ndk="${ANDROID_NDK_HOME:-$sdk/ndk/$ndk_version}"
case "$(uname -s)" in
  Darwin) host=darwin-x86_64 ;;
  Linux) host=linux-x86_64 ;;
  *) echo "Unknown build OS: $(uname -s)" >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host"
if [[ ! -d "$toolchain" ]]; then
  echo "NDK $ndk_version is missing. Install it: sdkmanager \"ndk;$ndk_version\"" >&2
  exit 1
fi

# Empty when go.mod replaces the module with a local copy.
version="$(cd "$root/go" && go list -m -f '{{.Version}}' github.com/freeyoung/tether-core-go)"
version="${version#v}"
version="${version:-0.0.0-dev}"

for abi in "${abis[@]}"; do
  case "$abi" in
    arm64-v8a) goarch=arm64; triple=aarch64-linux-android ;;
    x86_64) goarch=amd64; triple=x86_64-linux-android ;;
    *) echo "Unknown ABI: $abi" >&2; exit 1 ;;
  esac
  mkdir -p "$out/$abi"
  echo "Building libtether.so for $abi (engine $version)"
  # -trimpath keeps local paths out of the library. Android 15 and later
  # need 16 KB aligned segments.
  (cd "$root/go" && \
    GOOS=android GOARCH="$goarch" CGO_ENABLED=1 \
    CC="$toolchain/bin/$triple$min_sdk-clang" \
    go build -trimpath -buildmode=c-shared \
      -ldflags "-s -w -X github.com/freeyoung/tether-core-go/core.Version=$version \
        -extldflags=-Wl,-z,max-page-size=16384" \
      -o "$out/$abi/libtether.so" .)
  rm -f "$out/$abi/libtether.h"
done
