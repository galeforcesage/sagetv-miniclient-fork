#!/usr/bin/env bash

set -euo pipefail

GDX_COMMIT="7bfcd983a25b1943935fbc0d2aed052bf5c49cd3"
NDK_VERSION="28.2.13676358"
SCRIPT_DIR="${SAGETV_GDX_SCRIPT_DIR:-$(cd "$(dirname "$0")" && pwd)}"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SOURCE_DIR="${GDX_SOURCE_DIR:-$HOME/sagetv-native-build/libgdx-1.13.1}"

if [ "${ANDROID_NDK:-}" = "" ]; then
    echo "Set ANDROID_NDK to NDK r28c ($NDK_VERSION)." >&2
    exit 1
fi
grep -q "Pkg.Revision = $NDK_VERSION" "$ANDROID_NDK/source.properties"
export NDK_HOME="$ANDROID_NDK"
export ANDROID_NDK_HOME="$ANDROID_NDK"
command -v ant >/dev/null

if [ ! -d "$SOURCE_DIR/.git" ]; then
    git clone https://github.com/libgdx/libgdx.git "$SOURCE_DIR"
fi
git -C "$SOURCE_DIR" fetch --tags origin
git -C "$SOURCE_DIR" checkout --detach "$GDX_COMMIT"
test "$(git -C "$SOURCE_DIR" rev-parse HEAD)" = "$GDX_COMMIT"
git -C "$SOURCE_DIR" show "$GDX_COMMIT:gdx/build.gradle" > "$SOURCE_DIR/gdx/build.gradle"

patch_file="$SCRIPT_DIR/patches/0001-support-16k-page-alignment.patch"
normalized_patch="$(mktemp)"
trap 'rm -f "$normalized_patch"' EXIT
tr -d '\r' < "$patch_file" > "$normalized_patch"
if git -C "$SOURCE_DIR" apply --check "$normalized_patch"; then
    git -C "$SOURCE_DIR" apply "$normalized_patch"
else
    echo "GDX patch no longer applies cleanly." >&2
    exit 1
fi

cd "$SOURCE_DIR"
./gradlew :gdx:jnigenBuildAndroid --no-daemon

armv7_so="$(find "$SOURCE_DIR" -path '*armeabi-v7a*' -name libgdx.so -print -quit)"
if [ "$armv7_so" = "" ]; then
    echo "GDX build did not produce armeabi-v7a/libgdx.so" >&2
    exit 1
fi

readelf="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
python3 "$REPO_ROOT/tools/verify-native-alignment.py" \
    --readelf "$readelf" \
    "$armv7_so"

override_dir="$REPO_ROOT/android-shared/native-overrides/armeabi-v7a"
mkdir -p "$override_dir"
cp "$armv7_so" "$override_dir/libgdx.so"
python3 "$REPO_ROOT/tools/verify-native-alignment.py" \
    --readelf "$readelf" \
    "$override_dir/libgdx.so"
echo "Installed $armv7_so as $override_dir/libgdx.so"
