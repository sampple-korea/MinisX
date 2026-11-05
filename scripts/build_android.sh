#!/usr/bin/env bash
#
# One-shot local build of the Android app: native dependencies (only the ones that are
# missing), then Gradle. Same steps as .github/workflows/android.yml.
#
#   ./scripts/build_android.sh              signed release APK  (default)
#   ./scripts/build_android.sh debug        signed debug APK
#   ./scripts/build_android.sh release --clean-native    rebuild proot / rclone too
#   any other argument goes to Gradle, e.g. -Pminis.versionCode=2 -Pminis.versionName=0.2
#
# Needs: JDK 17+, Android SDK (ANDROID_HOME), NDK r28+ (ANDROID_NDK_HOME), Go 1.25+,
# gomobile, gawk, curl. See BUILDING.md.
#
# Every APK is signed with src/android/keystore/minis.jks, so a new build installs over
# the previous one. The script prints the certificate fingerprint so you can check that.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VARIANT="release"
CLEAN_NATIVE=0
GRADLE_ARGS=()
for arg in "$@"; do
  case "$arg" in
    release|debug) VARIANT="$arg" ;;
    --clean-native) CLEAN_NATIVE=1 ;;
    *) GRADLE_ARGS+=("$arg") ;; # e.g. -Pminis.versionCode=2
  esac
done

case "$VARIANT" in
  release) TASK=":app:assembleGithubRelease"; OUT="release" ;;
  debug)   TASK=":app:assembleGithubDebug";   OUT="debug" ;;
esac

ASSETS="$ROOT/src/android/app/src/main/assets"
LIBS="$ROOT/src/android/app/libs"

command -v gawk >/dev/null || { echo "error: gawk is required (proot's build needs strtonum)" >&2; exit 1; }
[ -d "$ROOT/deps/proot/src" ] || git -C "$ROOT" submodule update --init deps/proot

if [ "$CLEAN_NATIVE" = 1 ]; then
  rm -f "$ASSETS/proot-aarch64" "$ASSETS/alpine-minirootfs.tar.gz" "$LIBS/rclone.aar"
fi

if [ ! -f "$ASSETS/proot-aarch64" ]; then "$ROOT/deps/build_proot.sh"; fi
"$ROOT/scripts/prepare_android_sandbox.sh" >/dev/null
if [ ! -f "$LIBS/rclone.aar" ]; then
  "$ROOT/deps/build_rclone_android.sh"
  mkdir -p "$LIBS" && cp "$ROOT/deps/build/rclone/rclone.aar" "$LIBS/"
fi

cd "$ROOT/src/android"
./gradlew "$TASK" ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"}

APK="$(ls "$ROOT"/src/android/app/build/outputs/apk/github/$OUT/*.apk | head -1)"
echo
echo "APK: $APK ($(du -h "$APK" | cut -f1))"
APKSIGNER="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$APKSIGNER" ] && "$APKSIGNER" verify --print-certs "$APK" | grep -m1 "certificate SHA-256"
