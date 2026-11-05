# Third-Party Licenses

MinisX (a fork of [OpenMinis](https://github.com/OpenMinis/OpenMinis), GPL-3.0) bundles, links, or depends on the following third-party components. Versions reflect the current source tree; license types were verified against each project's repository (GitHub license metadata / LICENSE files).

## Native C/C++ dependencies (`deps/`)

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [proot](https://github.com/OpenMinis/proot) (fork) | git submodule `deps/proot` | **GPL-2.0** | Linux sandbox on Android (`libproot.so`, `proot-aarch64`) |
| proot ELF loaders (Termux build) | Termux package `proot` 5.1.107-70, vendored as `src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so` and `libproot-loader32.so` (sha256-pinned in `deps/build_proot.sh`) | **GPL-2.0** | Corresponding source: [termux/proot@`4dba3afb`](https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f), as pinned by [termux-packages@`0ff4d49d`](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/packages/proot/build.sh). Shipped as binaries because they carry Termux's Android loader patches |
| [rclone](https://github.com/rclone/rclone) | v1.75.0 (`deps/rclone-mobile/go.mod`), built by `deps/build_rclone_android.sh` | **MIT**. Its Go module dependencies are mostly MIT / BSD / Apache-2.0 / ISC, plus **MPL-2.0** (`hashicorp/go-retryablehttp`, `go-cleanhttp`, `go-multierror`, `errwrap`, `go-uuid`, through the S3 backend) and **LGPL-3.0** (`cloudsoda/sddl`, through the SMB backend); versions pinned in `go.sum` | Backup remote destinations. Go links everything statically into one library: `Rclone.xcframework` (iOS), `rclone.aar` → `libgojni.so` (Android); the source of every module is fetched from `go.sum` when building |
| [Tailscale](https://github.com/tailscale/tailscale) | `tailscale.com` v1.94.2 (`deps/rclone-mobile/go.mod`), with [wireguard-go](https://github.com/tailscale/wireguard-go) and the [gVisor](https://github.com/google/gvisor) network stack | **BSD-3-Clause** (wireguard-go **MIT**, gVisor **Apache-2.0**) | Embedded Tailscale node (`deps/rclone-mobile/tsnet`) for Settings > Network, statically linked into the same Go library as rclone; built with `ts_omit_logtail,ts_omit_portmapper` (no log upload, no UPnP / NAT-PMP). Signs in to the user's own Tailscale account; no control server is bundled |
| [talloc](https://talloc.samba.org) (Samba) | vendored at `deps/talloc` | **LGPL-3.0-or-later** | Memory allocator required by proot |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | vendored (Android `jieba_jni`) | **MIT** | Chinese word segmentation (header-only + dictionaries) |
| Alpine Linux minirootfs | downloaded at build time by `scripts/prepare_android_sandbox.sh` | Aggregate of package licenses (musl **MIT**, BusyBox **GPL-2.0**, etc.) | Not stored in this repo; bundled into app builds as the default rootfs |

## Android — Gradle dependencies

| Library | Version | License |
|---|---|---|
| AndroidX / Jetpack (Compose BOM 2025.09.00, core-ktx, lifecycle, activity, navigation, Room, DataStore, security-crypto, browser, webkit, exifinterface, profileinstaller, Material 3 adaptive) | see `app/build.gradle.kts` | **Apache-2.0** (Google / AOSP) |
| OkHttp + okhttp-sse | 4.12.0 | **Apache-2.0** |
| kotlinx-serialization-json | 1.7.3 | **Apache-2.0** |
| kotlinx-coroutines-android | 1.9.0 | **Apache-2.0** |
| Coil (coil-compose) | 2.7.0 | **Apache-2.0** |
| multiplatform-markdown-renderer (+ m3) — mikepenz | 0.33.0 | **Apache-2.0** |
| Reorderable (sh.calvin.reorderable) | 2.4.0 | **Apache-2.0** |
| ACRA (acra-core) | 5.12.0 | **Apache-2.0** |
| Shizuku API + provider (dev.rikka.shizuku) | 13.1.5 | **MIT** |
| Health Connect client (androidx.health.connect:connect-client) | 1.1.0-beta01 | **Apache-2.0** |
| AndroidX Biometric | 1.1.0 | **Apache-2.0** |
| AndroidX Glance (glance-appwidget, glance-material3) | 1.1.1 | **Apache-2.0** |
| ML Kit (text-recognition + Chinese/Japanese/Korean, barcode-scanning, image-labeling, object-detection, face-detection, language-id, entity-extraction) | 16.0.1 / 17.3.0 / 17.0.9 / 17.0.2 / 16.1.7 / 17.0.6 / 16.0.0-beta6 | Client libraries under the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) (not an open-source license); the bundled on-device models ship inside the APK. Entity extraction downloads a language model on first use. |
| RealTimeCutVADLibraryForAndroid (helloooideeeeea) | 1.0.5 | **MIT** — bundles ONNX Runtime (**MIT**) and WebRTC audio processing (**BSD-3-Clause**) |

Test-only dependencies: JUnit 4.13.2 (**EPL-1.0**), MockWebServer 4.12.0 (**Apache-2.0**), kotlinx-coroutines-test 1.9.0 (**Apache-2.0**), org.json 20231013 (**Public Domain / JSON License**), sqlite-jdbc 3.41.2.2 (**Apache-2.0**), Robolectric 4.17 (**MIT**).

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android `app/src/main/assets/katex/` | **MIT** |
| jieba dictionaries | Android `assets/jieba/` | **MIT** (cppjieba distribution) |
| [models.dev](https://github.com/sst/models.dev) catalogue snapshot | Android `assets/models-dev-api.json` (offline copy of `https://models.dev/api.json`, refreshed at run time) | **MIT** |
| [tiktoken](https://github.com/openai/tiktoken) `cl100k_base` BPE ranks | Not bundled. `data/BPETokenizer.kt` can load a `cl100k_base.tiktoken` asset if one is added; it is a port of tiktoken's algorithm | **MIT** |

## Removed / historical

- **swift-markdown-ui** (MIT) — formerly vendored under `deps/swift-markdown-ui`; no longer referenced by the Xcode project or imported by any source file, and is not part of the open-source tree.
