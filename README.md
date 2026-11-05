# OpenMinis

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg)](#building-from-source)

**Your private, on-device AI agent.**

OpenMinis brings leading models — Claude, GPT, Gemini and more — into a native
mobile experience, and gives them a real computer to work with: a full Linux
shell running on your device, browser automation, extensible skills, persistent
memory, and deep system integration.

It is free, and fully open source.

**We believe that in the age of AI, technical design and code are no longer
where a product's advantage lies. The best agent emerges from a tight feedback
loop with the people who use it — their expectations and their reports are
what converge on the product.**

Official website: **[openminis.app](https://openminis.app)**

<a href="https://github.com/OpenMinis/OpenMinis/releases">
  <img alt="Get the APK on GitHub" height="48" src="assets/badge-android.svg" />
</a>

> **This repository is the Android-only line of OpenMinis.** The iOS app and its
> native dependencies (iSH, FFmpeg, LAME) were removed; the last commit that
> contains them is `1a71df0`.

---

## What it does

| | |
|---|---|
| **Bring your own model** | Claude, GPT, Gemini and other providers, via your own API keys or account sign-in. |
| **A real Linux shell** | A sandboxed Alpine Linux environment runs on-device — the agent can install packages, run scripts, and work with real files. |
| **Device integration** | Calendar, Contacts, Health Connect, Bluetooth LE, NFC, Location, Clipboard, Photos, Media sessions, Alarms, Notifications, on-device Vision/NLP and more, exposed to the agent as `android-*` shell tools. |
| **Browser automation** | The agent can browse and interact with the web on your behalf. |
| **Skills & memory** | Extensible skills plus persistent memory across sessions. |
| **Workspaces** | Organise work into separate contexts, addressable via `minis://workspace/`. |
| **Native offloads** | Heavy or platform-specific work is handed to native code instead of the sandbox. |
| **Privacy** | Biometric app lock and per-chat locks; a Screen Saver for long agent runs. |

---

## What you can do with Minis

A few things people actually use it for:

- **Photograph a meal, log the nutrition** — Minis identifies the dishes, estimates
  calories and macros, and writes them to Health Connect.
- **Turn group chatter into tasks** — pull messages from a Telegram group, extract
  bugs and action items, deduplicate them, and put them on your calendar.
- **Mount your Obsidian vault** — research, clean up and write Markdown notes back
  into the vault as a normal workspace.
- **Read a document from a photo** — `android-vision ocr` pulls the text out on
  the device, then the model summarises it.
- **Share anything into a calendar event** — send a page or message to Minis via the
  Android share sheet and it creates the event, time and place included.

**→ [OpenMinis/AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)** — a curated,
community-contributed collection of use cases and workflows across health,
productivity, research, finance and developer tooling.

---

## Skills

A **skill** is a folder with a `SKILL.md` file — instructions, and optionally scripts,
references and assets — that the agent loads on demand when a request matches it.
Metadata stays in context for triggering; the body and bundled resources load only
when the skill is actually used.

Minis has its own tool system, but it does not require skills written specifically
for it: **skills built for Claude, Codex, OpenClaw or Hermes Agent generally run in
Minis as-is.** Skills that have been adapted to Minis' tools simply run better —
they can reach the Linux shell, device integrations and native offloads directly.

**→ [OpenMinis/MinisSkills](https://github.com/OpenMinis/MinisSkills)** — skills
adapted for Minis alongside ones built for it from scratch, covering TTS, search,
media downloads, health analysis, cloud APIs and more.

---

## Releases

The [releases page](https://github.com/OpenMinis/OpenMinis/releases) carries the
upstream APKs. To run this fork, build it yourself (below).

---

## Building from source

Minis ships a Linux sandbox inside the app, so the native dependencies (PRoot,
rclone) and the Alpine rootfs are **built from source** rather than committed as
binaries.

**→ See [BUILDING.md](BUILDING.md) for the full first-build guide.**

The short version:

```sh
git clone --recurse-submodules https://github.com/sampple-korea/MinisX.git
cd MinisX

# needs NDK r28+, Go 1.25+ and gomobile
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
./deps/build_rclone_android.sh
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/
cd src/android && ./gradlew :app:assembleGithubDebug
```

`BUILDING.md` covers the toolchain requirements, the build-time
customization templates, and a troubleshooting section for the failure modes
you are most likely to hit.

---

## Repository layout

```
src/android/      Android app (Kotlin / Compose) + JNI native code
src/shared/       Test vectors and rule tables shared with the Android build
deps/             Native dependency build scripts and vendored sources
docs/specs/       Architecture and interface specifications
scripts/          Rootfs preparation and developer tooling
```

---

## Acknowledgements

OpenMinis stands on a great deal of open-source work. Our thanks to the
maintainers of these projects — the full inventory, with versions and license
terms, is in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

**The sandbox** — the heart of the product:

- **[PRoot](https://github.com/termux/proot)** (GPLv2) — user-space chroot for the
  Android sandbox, via [our fork](https://github.com/OpenMinis/proot);
  **[talloc](https://talloc.samba.org)** (LGPLv3+) underpins it.
- **[Alpine Linux](https://alpinelinux.org)** — the minirootfs the sandbox boots.

**Text** — [cppjieba](https://github.com/yanyiwu/cppjieba) (MIT),
[KaTeX](https://katex.org) (MIT).

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack),
[OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/),
[kotlinx](https://github.com/Kotlin) serialization & coroutines,
[multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer),
[Reorderable](https://github.com/Calvin-LL/Reorderable), [ACRA](https://github.com/ACRA/acra)
(all Apache-2.0), [Shizuku](https://github.com/RikkaApps/Shizuku-API) (MIT),
and Google's [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect),
Biometric, Glance and [ML Kit](https://developers.google.com/ml-kit) libraries.

---

## License

OpenMinis is licensed under the **[GNU General Public License v3.0](LICENSE)**.

The app bundles a GPL-licensed component — [PRoot](https://github.com/OpenMinis/proot)
(GPLv2) — so the combined work is distributed under GPLv3. Bundled third-party licenses are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

## Community

- **Telegram**: [Join the group](https://t.me/+2NzhOJuzRyI1YmM1)
- **Issues**: Bug reports, feature requests and discussion via
  [GitHub Issues](https://github.com/OpenMinis/OpenMinis/issues)

This repository is a mirror of a private development tree, so it **does not
accept pull requests** — there is nowhere for them to land. Issues are the way
to shape the product, and [AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)
and [MinisSkills](https://github.com/OpenMinis/MinisSkills) both do take
contributions. See [CONTRIBUTING.md](CONTRIBUTING.md).
