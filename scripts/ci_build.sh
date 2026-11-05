#!/usr/bin/env bash
# CI build of the signed release APK, written for a runner that logs publicly (the "shuttle"
# repository, see docs/ci-shuttle/README.md): every command's output goes to $CI_LOG and only
# one line per step is printed, so the public log shows no source paths, warnings or errors.
#
# Needs on the runner: JDK 17, Go 1.25, the Android SDK in ANDROID_HOME (ubuntu-24.04 has it),
# sudo apt. Environment:
#   VERSION_NAME  e.g. 0.2           (required)
#   VERSION_CODE  e.g. 2             (required, must be higher than the last release)
#   CI_LOG        log file           (default: ./build.log)
#   OUT_APK       where to put the APK (default: ./minis.apk)
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION_NAME:?}" "${VERSION_CODE:?}"
LOG="${CI_LOG:-$PWD/build.log}"
OUT="${OUT_APK:-$PWD/minis.apk}"
: > "$LOG"

BUILD_TOOLS=36.0.0
NDK_VERSION=28.0.12433566
CMAKE_VERSION=3.22.1
# SHA-256 of the certificate in src/android/keystore/minis.jks
EXPECTED_CERT_SHA256=d0f126e7524dbafeb636e7dcb9e16a697b0ff9108133d79f01580e98c919a747

step() { echo "== $*"; }
quiet() { "$@" >> "$LOG" 2>&1; }

step "1/6 Android SDK"
SDKM="$(ls -d "$ANDROID_HOME"/cmdline-tools/*/bin | sort -V | tail -1)/sdkmanager"
{ yes | "$SDKM" --licenses || true; } >> "$LOG" 2>&1
quiet "$SDKM" --install "platforms;android-36" "build-tools;${BUILD_TOOLS}" "ndk;${NDK_VERSION}" "cmake;${CMAKE_VERSION}"
export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/${NDK_VERSION}"

step "2/6 Tools"
# proot's build runs loader-info.awk, which needs gawk's strtonum (the default mawk lacks it).
quiet sudo apt-get update
quiet sudo apt-get install -y gawk
quiet go install golang.org/x/mobile/cmd/gomobile@latest
export PATH="$(go env GOPATH)/bin:$PATH"
quiet gomobile init

step "3/6 Native dependencies"
quiet ./deps/build_proot.sh
quiet ./scripts/prepare_android_sandbox.sh
quiet ./deps/build_rclone_android.sh
mkdir -p src/android/app/libs
cp deps/build/rclone/rclone.aar src/android/app/libs/

step "4/6 Build"
( cd src/android && quiet ./gradlew --no-daemon --quiet --console=plain :app:assembleGithubRelease \
    -Pminis.versionCode="$VERSION_CODE" -Pminis.versionName="$VERSION_NAME" )

step "5/6 Signature"
apk="$(ls src/android/app/build/outputs/apk/github/release/*.apk | head -1)"
actual="$("$ANDROID_HOME/build-tools/${BUILD_TOOLS}/apksigner" verify --print-certs "$apk" 2>>"$LOG" \
  | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')"
if [ "$actual" != "$EXPECTED_CERT_SHA256" ]; then
  echo "APK is signed with $actual, expected $EXPECTED_CERT_SHA256" | tee -a "$LOG"
  exit 1
fi
cp "$apk" "$OUT"

step "6/6 Done ($(du -h "$OUT" | cut -f1))"
