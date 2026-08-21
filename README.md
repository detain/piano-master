# KeyQuest

**Learn piano with songs you love — 5 minutes a day, real-time feedback on a real
piano or keyboard, no prior experience needed.** (plan §1)

KeyQuest is a native Android piano-learning app: song-based lessons, real-time note
detection (microphone or MIDI), scrolling notation with instant color-coded feedback,
gamified progression, family profiles, and a subscription business model.

## Architecture

The app is a renderer; the content pipeline is the factory that feeds it. The C++
audio engine is UI-agnostic and platform-portable — only the Oboe and LiteRT bindings
are platform code (plan §4).

```
┌─────────────────────────── Android App ───────────────────────────┐
│  Compose UI · Lesson Player (Canvas) · Kotlin domain layer        │
│        ▲ NoteEvents (JNI)            ▲ MidiManager (USB/BLE)      │
│  ┌─────┴────────── C++ AudioEngine (Oboe) ────────────────┐       │
│  │ mic → ring buffer → onset detect → LiteRT model        │       │
│  │ output ← mixer ← (metronome, stems, soundfont synth)   │       │
│  └────────────────────────────────────────────────────────┘       │
└───────────────▲───────────────────────────▲───────────────────────┘
                │ REST (Retrofit)            │ CDN (SongPacks, HLS)
┌───────────────┴─────────────┐  ┌──────────┴───────────┐
│  API — Webman on Workerman  │  │  Object store + CDN  │
│  MySQL 8 · Dragonfly        │  └──────────▲───────────┘
└───────────────▲─────────────┘             │ publish
                │ admin API      ┌──────────┴───────────┐
┌───────────────┴────────────┐   │ Content pipeline     │
│  Admin CMS (Vue 3 + Vite)  │   │ (Python workers:     │
└────────────────────────────┘   │  MusicXML → SongPack)│
                                 └──────────────────────┘
```

Key rules: the app never parses MusicXML (everything arrives as versioned SongPacks);
all detection crosses JNI as one `NoteEvent` type so mic, MIDI, and touch share one
lesson player; the API is stateless per request; Dragonfly holds nothing that cannot
be rebuilt from MySQL or the CDN (plan §4).

## Workspaces

| Workspace | Purpose | Toolchain |
|---|---|---|
| `engine/` | Platform-portable C++20 audio engine: Oboe mic/output I/O, DSP (onset, features), ML transcription, MIDI decode, soundfont synth, metronome | C++20 · CMake 3.28+ · NDK r27 · Oboe 1.9 |
| `android/` | Native Android app: Compose UI, lesson player, scoring/gamification, Room/DataStore, billing | Kotlin 2.x · AGP 8.x · Gradle · JDK 21 |
| `api/` | Backend: REST API, JWT auth, content serving, in-process daemons (RTDN, workout gen, pipeline dispatch) | PHP 8.3 · Webman 2.1 · Workerman 5.1 · MySQL 8 · Dragonfly |
| `cms/` | Admin CMS: song editor, curriculum management, pipeline orchestration | Vue 3 · Vite · TypeScript · Pinia |
| `pipeline/` | Content pipeline: MusicXML/MIDI → validated SongPacks, stems, layout precompute | Python 3.12 · music21 · mido · ffmpeg |
| `content/` | Source of truth: SongPack sources, curriculum YAML, rights metadata, canonical schemas | Data only (YAML/JSON/audio) |
| `docs/` | ADRs, specs, runbooks, phase-gate reviews | Markdown |

## Get started

1. **Read the plan** — `plan_piano.md` is the full product plan; start with §1–§4.
2. **Browse the docs index** — `docs/README.md` (ADRs, specs, runbooks).
3. **Install dependencies** — `make bootstrap` (idempotent; prints per-workspace status).
4. **Verify** — `make test` and `make lint` (both skip workspaces not yet present).

Pinned toolchain versions: [`toolchain.md`](toolchain.md). Workspace-independent
conventions live in `.editorconfig` and `.gitignore`.

## Provisioning a new machine / server

Fresh Ubuntu 24.04 (or Debian 12+) server? Bootstrap the whole dev environment
from the repo root with one command (idempotent — safe to re-run):

```bash
./scripts/provision-server.sh
```

That installs the pinned toolchain (see [`toolchain.md`](toolchain.md)):
system packages (git, curl, build-essential, cmake, ninja, ffmpeg, python3,
OpenJDK 21, PHP 8.3 + Composer, Docker + compose plugin), Node 24 via
NodeSource; enables/starts Docker and adds your user to the `docker` group —
then runs `make bootstrap` (api composer deps, cms npm deps, pipeline venv,
engine CMake configure).

Flags:

- `--check` — read-only report of installed versions vs `toolchain.md` pins.
- `--skip-system` — skip OS-level installs; only run `make bootstrap`.
- `--with-android` — also install the Android SDK (cmdline-tools, platform 36,
  build-tools 36.0.0, NDK r27d) and accept its licenses.
- `--skip-android` — override `--with-android` (Android is **off** by default).

What it does **not** install by default: the Android SDK — SDK licenses require
your acceptance, so pass `--with-android` explicitly. Docker group membership
takes effect after re-login or `newgrp docker`.