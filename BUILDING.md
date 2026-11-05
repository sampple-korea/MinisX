# Building Minis

Minis ships a full Linux sandbox inside the app, so a first build is not just
"open the project and press Run": the native dependencies (PRoot, rclone) and
the Alpine rootfs are **built from source by the scripts in `deps/` and
`scripts/`**, not committed as binaries. Budget ~30–60 minutes for the first
build; afterwards the artifacts are cached on disk and normal builds are fast.

Read this end to end before starting — the steps are ordered by dependency, and
skipping one produces confusing errors later.

This repository is **Android only**. The iOS app lived in `src/ios` up to commit
`1a71df0`; it is still in git history if you need to read it.

---

## Common setup

Clone with submodules — the PRoot fork is a submodule, and a clone without it
will fail at the native build step:

```sh
git clone --recurse-submodules https://github.com/sampple-korea/MinisX.git
cd MinisX

# Already cloned without --recurse-submodules?
git submodule update --init --recursive
```

| Submodule | Repository | Used by |
|---|---|---|
| `deps/proot` | [OpenMinis/proot](https://github.com/OpenMinis/proot) | Android sandbox |

### Build-time customization

Some values are injected at build time and are **not** in this repository.
Copy the templates before building:

```sh
cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

Leaving the values empty is fine — **the app compiles and runs**. A value is
only required by the feature that uses it, and that feature fails loudly at
runtime when it is missing. API-key based sign-in works without any
customization.

### `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`

Only relevant if you want to **sign in with Claude OAuth credentials** rather
than an Anthropic API key.

When a request is authenticated with OAuth, Anthropic's endpoint expects the
system prompt to begin with the identifying line that Claude Code itself
sends; without it the request is rejected. The build injects that line from
this value, so OAuth sign-in fails at runtime while it is empty.

We do not ship a value. Supply your own if you need this path — other
open-source projects that talk to the same endpoint declare the same
identifier, for example
[claude-relay-service](https://github.com/Wei-Shaw/claude-relay-service),
which you can consult for the exact wording.

Everything else — Anthropic API keys, and every other provider — works
without setting this.

---

## Build

### Requirements

| Tool | Version / notes |
|---|---|
| JDK | **17** (`sourceCompatibility`/`targetCompatibility` are 17) |
| Android SDK | **compileSdk 36**, targetSdk 35, **minSdk 26**; point Gradle at it with `ANDROID_HOME` or `sdk.dir` in `src/android/local.properties` |
| Android NDK | **r28+** — set `$ANDROID_NDK_HOME`, or install via Android Studio |
| CMake | 3.22.1 (install through the SDK Manager) |
| Go + gomobile | Go **1.25+**, then `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init` — for rclone |
| Shell tools | `curl`, `tar`, `make`, `awk`, `sed` |

Gradle itself comes from the wrapper (Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0) —
do not install it separately.

Only `arm64-v8a` is built (`abiFilters`), so use an arm64 device or emulator
image.

### 1. Build the native dependencies

```sh
./deps/build_proot.sh              # → assets/proot-aarch64, jniLibs/arm64-v8a/*.so
./scripts/prepare_android_sandbox.sh   # → assets/alpine-minirootfs.tar.gz
./deps/build_rclone_android.sh     # → deps/build/rclone/rclone.aar
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/
```

- **`build_proot.sh`** cross-compiles a static `libtalloc` and the
  `deps/proot` fork with the NDK, then installs into the app's `assets/` and
  `jniLibs/arm64-v8a/`: the proot binary itself plus **`libproot-loader.so`
  and `libproot-loader32.so`**. The script verifies all of them at the end and
  fails the build if any is missing.

  The loaders are required, not optional. Android 10+ enforces W^X for
  `untrusted_app`: nothing labelled `app_data_file` — which includes the whole
  extracted Alpine rootfs under the app's `files/` dir — can ever be executed.
  Only `nativeLibraryDir`, populated from `lib/**/*.so` in the APK, is
  executable, so the loader lives there and maps guest binaries itself instead
  of relying on the kernel's `execve`. (proot can normally extract an embedded
  loader at runtime, but on Android it writes it into the app's temp dir and
  hits the same W^X wall.) That is also why they carry a `.so` suffix despite
  being executables rather than shared objects — only `*.so` gets extracted.

  Artifacts are **not** byte-identical across NDK releases; the loader's code
  differs between toolchain generations. Functionally equivalent — don't expect
  checksums to match someone else's build.
- **`prepare_android_sandbox.sh`** downloads the Alpine aarch64 minirootfs into
  `assets/`.
- **`build_rclone_android.sh`** binds rclone with gomobile into an `.aar` for
  the backup feature's remote destinations. It needs `ANDROID_NDK_HOME` (or
  `ANDROID_HOME`) set, and it writes to `deps/build/rclone/`; Gradle reads the
  `.aar` from `src/android/app/libs/`, so copy it there as shown above.

All of these outputs are gitignored — they are build artifacts, so rerun the
scripts rather than committing them.

The small JNI libraries in `src/main/cpp/` (`pty_bridge`, the crash handler,
`jieba_jni`) are built by CMake as part of the normal Gradle build; no separate
step is needed.

### 2. Build the app

```sh
cd src/android
./gradlew :app:assembleGithubDebug    # → app/build/outputs/apk/github/debug/
./gradlew :app:installGithubDebug     # install onto a connected device
```

There are two distribution flavors, `github` (the default; self-update on) and
`store`. `assembleDebug` builds both; `compileGithubDebugKotlin` is the quick
"does it compile" check.

Release builds are configured with the debug signing config, so no keystore is
required to produce one locally.

### One command

`scripts/build_android.sh` runs the whole chain — builds proot, downloads the Alpine
rootfs, builds rclone (each only when missing), then Gradle — and prints the signing
certificate at the end:

```sh
./scripts/build_android.sh              # signed release APK
./scripts/build_android.sh debug        # signed debug APK
./scripts/build_android.sh release --clean-native   # rebuild proot / rclone too
```

proot's build needs **gawk** (`sudo apt install gawk`); Ubuntu's default `mawk` has no
`strtonum` and the build stops in `loader-info.awk`.

### Signing

Release builds are signed with one fixed key so that every new build installs over the previous
one. The key is **not in this repository** (it is public). It lives in GitHub Actions secrets:

| Secret | Value |
|---|---|
| `MINISX_KEYSTORE_B64` | the keystore file, base64 (`base64 -w0 minisx-release.jks`) |
| `MINISX_KEYSTORE_PASSWORD` | its password (alias `minisx`; the key password is the same) |

CI decodes it, builds, and then fails unless the APK's certificate matches the SHA-256 below.
**Never regenerate or replace the key**: an app signed with a different key cannot be updated,
only uninstalled and reinstalled (which wipes its data). Keep a backup of the keystore somewhere
private; if it is lost, the next release has to be a fresh install.

Certificate SHA-256: `CB:95:20:BC:64:85:1A:2A:E3:E9:2E:61:AC:99:A5:44:9F:12:CB:5E:46:8C:78:62:2E:E2:59:3C:43:C7:D2:DC`

Local builds without the key (the normal case) are signed with your Android debug key
(`~/.android/debug.keystore`). They install fine but cannot update, or be updated by, a release
APK. To build with the release key locally:
`MINISX_KEYSTORE=/path/minisx-release.jks MINISX_KEYSTORE_PASSWORD=... ./scripts/build_android.sh`.

Version numbers come from `build.gradle.kts` unless you pass `-Pminis.versionCode=N
-Pminis.versionName=X`; CI does that so each build is newer than the one before.

### GitHub Actions

`.github/workflows/android.yml`:

| Trigger | What happens |
|---|---|
| manual run (Actions tab) | builds the signed release APK and uploads it as the `minis-apk` artifact (90 days); there is no push-to-`main` trigger |
| tag `v*` (e.g. `git tag v0.2 && git push --tags`) | the same, and attaches the APK to a GitHub release; `versionName` is the tag |
| manual run | the same as a push; with `release_tag` (e.g. `v0.1`) it also creates that tag + release on the built commit, and `version_code` can force the versionCode |

The job checks the APK's certificate against the fingerprint above and fails if it differs.
`versionCode` is the run number minus 5 (the app restarted at 0.1 / versionCode 1 as `com.minisx.app`), or the `version_code` input.

The in-app updater looks for new releases of `sampple-korea/MinisX` (the repository is public, so the unauthenticated GitHub API sees them).

### Tests

Some unit tests use Robolectric, which downloads Android jars from Maven the
first time; they fail in a sandbox with no outbound access. A few tests also
need the build-time customization file (see above) and fail without it. Filter
with `--tests "com.minisx.app.config.*"` to run a package.

```sh
./gradlew :app:testGithubDebugUnitTest      # JVM unit tests
./gradlew :app:connectedGithubDebugAndroidTest   # instrumented; needs a device/emulator
```

---

## Troubleshooting

**`deps/proot` is empty** — the submodule was not initialised:
`git submodule update --init --recursive`.

**`Could not find :rclone:`** — `rclone.aar` is not in
`src/android/app/libs/`. Run `./deps/build_rclone_android.sh` and copy
`deps/build/rclone/rclone.aar` there.

**`Android NDK not found`** — set `ANDROID_NDK_HOME` to your NDK r28+
installation, e.g.
`export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/28.0.12433566`.

**app starts but the shell does not** — the sandbox assets are
missing. Rerun `./deps/build_proot.sh` and
`./scripts/prepare_android_sandbox.sh`, then rebuild.

**every command returns `[Shell not running] (exit code: -1)`** —
the proot ELF loaders are missing from the APK. Check that
`src/android/app/src/main/jniLibs/arm64-v8a/` contains `libproot-loader.so`
(and `libproot-loader32.so`); if not, rerun `./deps/build_proot.sh`, which
now verifies them, and rebuild. Confirm with
`unzip -l app-debug.apk | grep libproot` — all three `.so` files must be
present.

This failure is deliberately hard to spot from the outside: proot still
launches and logs its `native_offload` initialisation, so the sandbox looks
healthy and any "does it start?" check passes. Only the first
`execve("/bin/sh")` fails, with `Permission denied` in logcat under the
`PRootStderr` tag. When touching this area, verify by **running a command and
asserting exit code 0** — starting the sandbox is not enough.

**A feature throws about a missing configuration value** — that value comes
from the customization file; see [Build-time customization](#build-time-customization).

---

## Licensing note

Minis is **GPLv3**, and bundles PRoot (GPLv2). If you change how the native
dependencies are built, preserve the vendored `LICENSE` files. See
[LICENSE](LICENSE) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
