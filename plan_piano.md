# Piano Learning App — Full Build Plan

A native Android app for learning piano, modeled feature-for-feature on Simply Piano:
song-based lessons, real-time note detection (microphone or MIDI), scrolling notation
with instant color-coded feedback, gamified progression, family profiles, and a
subscription business model. Working codename: **KeyQuest** (rename freely).

---

## 1. Product Vision

**One-liner:** Learn piano with songs you love — 5 minutes a day, real-time feedback
on a real piano or keyboard, no prior experience needed.

**Core loop:** open app → pick up where you left off → play a bite-sized lesson chunk
on your real instrument → app hears every note and shows instant feedback → earn
stars/XP → unlock the next chunk → daily streak grows.

**Target users:**
- Absolute beginners (age 6+) with access to any piano or keyboard
- Returning players brushing up technique
- Families: up to 5 profiles under one subscription

**What makes-or-breaks this product (in priority order):**
1. Note-detection accuracy + latency (mic and MIDI) — if this feels wrong, nothing else matters
2. The scrolling-notation lesson player feel (smooth 60fps, correct sync)
3. Curriculum quality and pacing
4. Song library appeal on a royalty-free-only catalog (§8.5 — our own arrangements and
   originals must carry the "songs you love" promise without purchasing pop licenses)
5. Retention mechanics (streaks, workouts, progress)

---

## 2. Feature Parity Checklist (mapped from Simply Piano)

| # | Feature | Section |
|---|---------|---------|
| 1 | Acoustic note detection via microphone (polyphonic — chords, not just melody) | §5 |
| 2 | MIDI input: USB and Bluetooth LE keyboards | §5.4 |
| 3 | Latency calibration (auto + manual) | §5.5 |
| 4 | Scrolling sheet-music timeline (right → left) | §7 |
| 5 | Real-time color-coded note feedback (hit/miss/idle) | §7.3 |
| 6 | Wait-for-Me mode (music pauses until correct note played) | §7.4 |
| 7 | On-screen virtual keyboard with target-note highlighting | §7.5 |
| 8 | Touch Courses (no piano? play on-screen keyboard) | §7.6 |
| 9 | Structured step-by-step courses (reading → both hands → technique) | §9 |
| 10 | Song library, 100s–1000s of songs, searchable by genre/difficulty | §8, §9.4 |
| 11 | Songs broken into small chunks; hands separated then combined | §8.2, §9.2 |
| 12 | 5-Minute Workouts (personalized daily practice) | §9.3 |
| 13 | Streaks, XP/levels, 3-star scoring, unlockable content | §10 |
| 14 | Metronome, hand isolation (mute L/R), section looping, tempo control | §11 |
| 15 | Sheet-music toggle (traditional notation ↔ beginner note-bars) | §7.7 |
| 16 | Onboarding questionnaire (age/experience/goals → starting point) | §12.2 |
| 17 | Multi-profile accounts (up to 5 per subscription) | §12.3 |
| 18 | Progress analytics (minutes practiced, accuracy trends, real-time progress) | §17 |
| 19 | Free tier + Premium subscription, 7-day free trial, Google Play Billing | §15 |
| 20 | Kids-safe: no ads, no external links, COPPA/Families compliant | §16 |
| 21 | Video lessons / piano tutorials | §8.4 |
| 22 | In-app feedback channel (Menu > Settings > Have a Question) | §12.5 |
| 23 | Works on phones and tablets, portrait + landscape | §7.8 |
| 24 | CMS: import MusicXML/MIDI → app-native lesson format, curriculum management | §14 |

---

## 3. Platform Decision & Tech Stack

### 3.1 Decision: Native Android (Kotlin)

This must be a **native Android app**, not a web app. Reasons:

- **Audio latency.** Real-time pitch detection needs the microphone → DSP → screen loop
  under ~50 ms total. Browsers can't guarantee this on Android; native code using
  AAudio/Oboe with `PerformanceMode::LowLatency` and exclusive streams can.
- **MIDI.** Android's `android.media.midi` API (USB + BLE MIDI) is only fully available natively.
  Web MIDI on Android Chrome exists but is flaky and unavailable in WebView.
- **ML inference.** On-device polyphonic transcription (TFLite/LiteRT with NNAPI/GPU
  delegates) is a native-first story.
- **Play Billing, Families program, offline content** — all first-class native.

(The Vue/Vite preference is not wasted: the **admin CMS and content-authoring tools in
§14 are a Vue 3 + Vite web app**, which is where a web stack genuinely belongs here.)

**Why not Flutter/React Native/KMP for v1:** the hard 30% of this app (audio engine,
notation renderer) is platform/NDK code either way; a cross-platform UI shell adds a
bridge layer exactly where we can least afford jitter. If iOS comes later, port the
C++ audio engine (it's shared-ready by design, §5.1) and rebuild UI in SwiftUI, or
revisit KMP then.

### 3.2 Android app stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.x (app), C++20 (audio/DSP engine via NDK) |
| UI | Jetpack Compose; custom `Canvas`/`graphicsLayer` rendering for the lesson player; Compose for everything else |
| Min SDK | 26 (Android 8.0 — AAudio baseline); target latest |
| Audio I/O | [Oboe](https://github.com/google/oboe) (C++ wrapper over AAudio, low-latency exclusive streams) |
| DSP / pitch | Custom C++ pipeline: onset detection + chroma/FFT prefilter → ML transcription model |
| ML runtime | LiteRT (TensorFlow Lite) with GPU/NNAPI delegate; model: Onsets-and-Frames-lite or Basic-Pitch-class model (§5.3) |
| MIDI | `android.media.midi` (USB host + Bluetooth LE MIDI via `MidiManager`) |
| Notation data | MusicXML ingested offline by CMS → app-native JSON ("SongPack", §8.1); app never parses MusicXML at runtime |
| Notation rendering | Custom Compose/Canvas renderer for the scrolling player (only needs a subset of engraving); Verovio (C++/JNI) optional later for full-page score view |
| Backing tracks | Media3 ExoPlayer for mixed stems (OGG/Opus); `Soundpool`/custom sampler for metronome + on-screen keyboard sounds (SF2/SFZ piano soundfont via TinySoundFont in the C++ engine) |
| Local storage | Room (progress, profiles cache, downloads registry); DataStore (settings); files dir for downloaded SongPacks |
| Networking | Retrofit + OkHttp + kotlinx.serialization; certificate pinning |
| DI | Hilt |
| Background | WorkManager (progress sync, content downloads) |
| Video lessons | Media3 ExoPlayer, HLS from CDN |
| Billing | Google Play Billing Library 7+, subscriptions + offers (7-day free trial) |
| Crash/analytics | Crashlytics + self-hosted analytics events endpoint (kids-safe config, §16) |
| CI | GitHub Actions: unit tests, instrumentation on Firebase Test Lab / emulator matrix, Play internal-track deploy via Gradle Play Publisher |

### 3.3 Backend stack

| Concern | Choice |
|---|---|
| API | PHP 8.3 (team strength) — **Webman 2.1** (HTTP framework on **Workerman 5.1**), REST JSON (OpenAPI-documented). Long-lived worker processes, not PHP-FPM: ~10–40× the req/s of a framework on FPM, and it can hold the RTDN consumer, the workout generator and the pipeline dispatcher as in-process daemons instead of cron |
| Runtime model | `php start.php start -d`, N HTTP workers (start at 4 × vCPU) behind nginx; long-running processes ⇒ strict state hygiene (§13.4). Coroutines (Fiber/Swoole/Swow drivers via `Workerman\Coroutine`) available but **off by default** — see §13.4.3 for when they help and when they are a footgun |
| DB | MySQL 8 (InnoDB) via `webman/database` (illuminate/database + Eloquent) |
| Cache / queue / rate-limit / ephemeral state | **Dragonfly** (Redis-wire-compatible, multi-threaded). Single vertically-scaled node + replica, spoken to with the ordinary phpredis/`webman/redis` client. Usage rules and the compatibility fence in §13.5 |
| Queue | `webman/redis-queue` (list + delayed-zset over the Dragonfly connection) for ordinary jobs; a MySQL `job_outbox` table in front of it for anything money- or entitlement-shaped (§13.4.5) |
| Auth | First-party app only, so **no full OAuth2 authorization server**: short-lived JWT access tokens (`lcobucci/jwt`, 15 min, RS256) + opaque rotating refresh tokens in MySQL with reuse detection; Google Sign-In verified server-side against Google's JWKS. `league/oauth2-server` is the drop-in if third-party API clients ever become a product |
| Content storage/CDN | S3-compatible object store + CDN for SongPacks, audio stems, video (HLS) |
| Server-side receipt validation | Google Play Developer API (pub/sub Real-Time Developer Notifications for subscription state) |
| Admin CMS | **Vue 3 + Vite + TypeScript + Pinia**, served as SPA against the same API (admin-scoped) |
| Music tooling (offline pipeline) | Python 3 workers: music21 / Verovio for MusicXML parsing+validation, mido for MIDI, ffmpeg for audio stem processing; dispatched from a Webman custom process over the job queue |

---

## 4. System Architecture

```
┌─────────────────────────── Android App ───────────────────────────┐
│  Compose UI (courses, library, profile, paywall, settings)        │
│  Lesson Player (Compose Canvas: staff, note bars, keyboard)       │
│        ▲ NoteEvents / score state (Kotlin Flow)                   │
│  ┌─────┴──────────── Kotlin domain layer ─────────────────┐       │
│  │ LessonSession · Scorer · WaitModeController · Gamify   │       │
│  └─────▲──────────────────────────▲──────────────────────┘        │
│   JNI  │ NoteOn/NoteOff(pitch,vel,t)  │ MidiManager (USB/BLE)     │
│  ┌─────┴─────────────── C++ AudioEngine (Oboe) ───────────┐       │
│  │ mic stream → ring buffer → onset det → LiteRT model    │       │
│  │ output stream ← mixer ← (metronome, stems, soundfont)  │       │
│  └────────────────────────────────────────────────────────┘       │
│  Room / DataStore  ·  Download manager (SongPacks)  ·  Billing    │
└───────────────▲───────────────────────────▲───────────────────────┘
                │ REST (Retrofit)            │ CDN (SongPacks, HLS video)
┌───────────────┴─────────────┐  ┌──────────┴───────────┐
│  API — Webman on Workerman  │  │  Object store + CDN  │
│  http workers (stateless)   │  └──────────▲───────────┘
│  + custom processes:        │             │ publish
│    RTDN consumer · workout  │  ┌──────────┴───────────┐
│    gen · pipeline dispatch  │  │ Content pipeline     │
│    · analytics flush        │  │ (Python workers:     │
│  MySQL 8  ·  Dragonfly      │  │ MusicXML→SongPack,   │
└───────────────▲─────────────┘  │ stems, validation)   │
                │ admin API      └──────────▲───────────┘
┌───────────────┴────────────┐              │ enqueue
│  Admin CMS (Vue3+Vite SPA) │──────────────┘
│  song editor · curriculum  │
└────────────────────────────┘
```

**Key architectural rules**
- The C++ `AudioEngine` is UI-agnostic and platform-portable (only Oboe + LiteRT bindings
  are platform code) — future iOS port reuses it.
- All detection results cross JNI as a single unified event type:
  `NoteEvent{pitch: Int(0-127), velocity: Int, onTimeNs, offTimeNs?, source: MIC|MIDI|TOUCH}`.
  Everything above the engine (scoring, feedback, wait mode) is input-source-agnostic —
  this is what makes mic, MIDI, and touch courses share one lesson player.
- The app never interprets MusicXML. The CMS compiles everything to the versioned
  SongPack format (§8.1); the app renders SongPacks only. This keeps the client renderer
  small and lets content evolve server-side.
- **The API is stateless per request even though the process is not.** Webman workers
  live for days; nothing derived from a request may survive it (§13.4). The only
  cross-request state lives in MySQL (durable) or Dragonfly (reconstructible).
- **Dragonfly holds nothing that cannot be rebuilt from MySQL or the CDN.** Cache,
  sessions, rate-limit counters, queues, short-lived locks — all disposable. A cold
  Dragonfly means a slow minute, never a lost purchase or a lost star (§13.5).

---

## 5. Core Audio Engine (the hard part — build first)

### 5.1 C++ engine layout (`/engine`)

```
engine/
  src/
    io/OboeInput.cpp          # exclusive low-latency mic stream, 16kHz mono resample tap
    io/OboeOutput.cpp         # output mixer stream (metronome, stems, keyboard synth)
    dsp/RingBuffer.h
    dsp/OnsetDetector.cpp     # spectral-flux onset detection (cheap, always-on)
    dsp/Features.cpp          # STFT / mel / CQT frames for the model
    ml/Transcriber.cpp        # LiteRT session: frames → {pitch, onset, offset, velocity}
    midi/MidiDecoder.cpp      # raw MIDI bytes → NoteEvent (also handles running status)
    synth/SoundFontSynth.cpp  # TinySoundFont piano for touch keyboard + note previews
    metronome/Metronome.cpp   # sample-accurate click scheduled on output callback
    Engine.cpp                # lifecycle, config, event queue to JNI
  include/engine/NoteEvent.h
  test/                       # offline harness: WAV in → events out (runs on host, CI)
```

### 5.2 Microphone pipeline

1. Oboe input stream: exclusive, `PerformanceMode::LowLatency`, native rate (48 kHz),
   mono, callback-driven; frames pushed to lock-free ring buffer.
2. Downsample to 16 kHz for the model (its training rate); keep 48 kHz path for onset
   detector (better temporal resolution).
3. **Onset detector** (spectral flux + adaptive threshold) runs every hop (~10 ms).
   Cheap, battery-friendly; gates the ML model so it only runs hard when something
   was actually played, and provides precise onset timestamps.
4. **Transcription model** runs on a dedicated thread on ~64–96 ms hops over a sliding
   window, emitting per-pitch onset/frame probabilities for the 88 piano keys.
5. **Decoder**: hysteresis thresholding (onset prob > 0.5 starts a note, frame prob
   < 0.3 ends it), minimum note length 60 ms, octave-error suppression using onset-
   detector energy envelope. Emits `NoteEvent`s with timestamps from the *audio clock*
   (stream position → `AudioTimestamp` mapping), not wall clock.

### 5.3 Polyphonic transcription model

- **Primary candidate:** Magenta **Onsets and Frames** — piano-specific, dual-objective
  (onset head + frame head), and Magenta shipped a TFLite build taking ~1s raw-audio
  windows that runs fast on Android. Start from this; retrain/shrink as needed.
- **Second candidate:** Spotify **Basic Pitch** architecture (NMP) — very small
  (~20 MB → quantizable to ~5 MB), instrument-agnostic, ONNX/TFLite exportable.
- **Plan:** evaluate both against our own test set (§19.2) for (a) chord accuracy,
  (b) latency at small hop sizes, (c) robustness to phone-speaker-quality pianos,
  out-of-tune uprights, room noise. Pick one, then fine-tune on MAESTRO + our own
  recorded corpus (many devices × many pianos). Quantize to int8/float16; verify with
  GPU and NNAPI delegates on the device matrix.
- **Fallback mode:** monophonic YIN/pYIN detector (tiny, instant) used for the earliest
  beginner lessons (single notes) and as a sanity cross-check; lets lessons 1–10 work
  even on low-end devices while the model warms up.

### 5.4 MIDI input

- `MidiManager` device enumeration; support **USB MIDI** (host mode, OTG) and
  **BLE MIDI** (`BluetoothMidiDevice`), with a device-picker sheet + auto-reconnect
  to last device.
- Decode NoteOn/NoteOff/velocity (treat NoteOn vel=0 as off, handle running status).
- MIDI events bypass all DSP → near-zero latency, 100% accuracy. When a MIDI device is
  connected, mic detection auto-disables (with a settings override).
- Detect "keyboard makes no sound" (MIDI-only controllers): offer local synth
  (SoundFontSynth) so the user hears their own playing through the phone/tablet.

### 5.5 Latency calibration

Total loop = mic hardware + buffering + model hop + render. Must align the *scoring
window* to the *visual timeline*.

- **Auto-calibration on first run (and per audio route change):** play 4 metronome
  clicks from the device speaker, capture via mic, measure emission→detection delta
  (cross-correlation). This yields round-trip latency; store per audio route
  (speaker/wired/BT — Bluetooth audio output adds 150–300 ms and must shift the
  *backing track schedule*, never the scoring window).
- **Manual calibration screen:** "tap along with the click" slider, ±5 ms steps,
  live preview.
- Scoring compares note timestamps in **audio-clock time** against expected beat times;
  visual layer renders with its own vsync clock mapped through the same offset.

### 5.6 Audio output & mixing

- Single Oboe output stream mixing: backing-track stems (decoded by ExoPlayer into
  the engine, or ExoPlayer standalone with clock sync — decide in spike M1), metronome
  clicks (sample-accurate, scheduled by frame position), soundfont synth, UI sounds.
- **Echo problem:** device plays backing track from speaker while mic listens.
  Mitigations: (a) spectral subtraction of the known backing signal, (b) duck backing
  track during mic mode, (c) prefer "listen-only" arrangements where backing = drums/pad
  without piano frequencies in mic mode. Simply Piano does mostly (b)+(c). Ship (b)+(c),
  research (a) later.

---

## 6. Note Matching & Scoring Engine (Kotlin, deterministic, unit-tested)

Input: stream of `NoteEvent`s + the chunk's expected note list (`pitch, beatTime,
duration, hand`). Pure function core → trivially testable.

- **Matching window:** expected note at beat time *t* matches an event of the same
  pitch class+octave within `[t−120ms, t+180ms]` (window scales with tempo and level —
  beginners get ±250 ms). Wrong-pitch events inside the window = "miss with hint"
  (show the played key in red on the keyboard).
- **Chord matching:** all chord tones must arrive within a 90 ms cluster; partial
  chords score partial credit.
- **Per-note verdicts:** `PERFECT | GOOD | MISSED | WRONG` → drive the color feedback
  (§7.3) and the score.
- **Scoring:** `score = 100 × (Σ note weights hit) / (Σ weights)`, timing bonus for
  PERFECT. Stars: ★ ≥ 60%, ★★ ≥ 80%, ★★★ ≥ 95% (tunable via remote config).
- **Pass/fail gates:** course steps require ★ minimum to advance; "Play it again to
  polish" prompt below ★★.
- Emits granular events to analytics: per-note accuracy, per-measure error heatmap
  (feeds the 5-Min Workout generator, §9.3).

---

## 7. Lesson Player UI (the signature screen)

Landscape-first, works portrait. Three stacked zones:

```
┌──────────────────────────────────────────────────────┐
│  ♩=80  [pause] [♻ loop] [🎚 tempo]      progress ▓▓░  │  ← transport bar
│  ─────────────────╂──────────────────────────────────│
│   staff / note-bar lane scrolling ◀── right to left   │  ← notation zone
│   with playhead line fixed at ~30% from left          │
│  ─────────────────╂──────────────────────────────────│
│  🎹 on-screen keyboard, target keys glowing           │  ← keyboard zone
└──────────────────────────────────────────────────────┘
```

### 7.1 Rendering approach
- One Compose `Canvas` (or `AndroidView` + custom `View` if profiling demands) drawing
  the visible window of notes each frame; positions computed from
  `songTime = f(frameClock, tempo, waitModeState)`. Pre-layout each chunk's glyph
  geometry once (off main thread) → per-frame work is translate + draw only. 60 fps
  on a 2018 mid-ranger is the acceptance bar.
- Glyphs from **Bravura** (SMuFL, OFL-licensed) for real engraving symbols.

### 7.2 Two notation skins (same data, §2 #15)
- **Note-bar mode (beginner):** colored rounded bars on a 5-lane-per-hand grid, note
  letter inside the bar (C-D-E…), length = duration. This is the "color-coded visual
  bars" toggle.
- **Staff mode:** real grand staff, clefs, key/time signatures, accidentals, beams,
  ties — the subset needed for scrolling display (no full engraving: CMS pre-computes
  beam groups and spacing hints into the SongPack).

### 7.3 Real-time feedback
- Upcoming notes: neutral (theme gray/blue).
- On verdict: PERFECT/GOOD → fill green with a small pop animation; MISSED → red
  outline as the playhead passes; WRONG pitch → played key flashes red on keyboard
  + expected key pulses.
- Combo streak counter (consecutive hits) with subtle escalation FX — key
  gamification juice, keep it toggleable ("reduced motion" setting).

### 7.4 Wait-for-Me mode
State machine per expected note/chord when enabled:
```
SCROLLING → (playhead reaches note, note unplayed) → HOLD (freeze songTime,
duck backing) → (correct NoteEvent arrives) → RESUME (ease songTime back to speed)
```
- Used automatically in "Learn" phases; regular timed mode in "Play" phases.
- Chord holds wait for all tones; a 5 s idle in HOLD triggers a hint (key glow +
  optional note-name voiceover).

### 7.5 On-screen keyboard zone
- Scrollable/zoomable 88-key strip auto-centered on the chunk's range; target notes
  glow ahead of time (glow leads by ~1 beat); shows played keys (any source) in real time.
- Finger-number badges (1–5) when the lesson teaches fingering.

### 7.6 Touch Courses (no instrument mode)
- Same player, keyboard zone becomes the input: multi-touch → `NoteEvent(source=TOUCH)`
  → same scorer. Soundfont synth voices the touches. Marketed as "no piano yet? start
  anyway" and as the on-the-go practice mode.

### 7.7 Sheet music toggle — user-level setting + per-course default (beginner courses
open in note-bar mode; staff introduced in Course 2 as a lesson topic).

### 7.8 Devices: phones (landscape lesson player), tablets (both orientations), fold-
ables; min screen width gate for the player ~ 4.7". Chromebooks later.

---

## 8. Content Model & Pipeline

The app is a renderer; **this section is the factory that feeds it.** Everything the user
experiences as "the product" — every lesson, every song, every difficulty level — arrives
as data produced here. Two consequences drive every decision below:

1. **The pipeline's throughput is the product's growth rate.** §20 P2.D5 sets ≥ 8 songs/week
   sustained, and §20.6 shows the launch library shrinking from 150 to 80 if that number
   isn't hit. Every manual step in this section is a permanent tax on that rate.
2. **The pipeline's correctness is invisible until it isn't.** A wrong hand assignment or a
   badly placed chunk boundary doesn't crash — it just makes a beginner feel stupid. The
   validation gates here exist because there is no runtime error to catch these.

### 8.1 SongPack — the app-native format (`songpack/v1`)

#### 8.1.1 Design constraints (why this format exists at all)

The app never parses MusicXML (§4). Everything is compiled ahead of time into a format
built around one question: *what does the renderer need in the next 16 milliseconds?*

- **Pre-computed, not derived.** Beam groups, note-bar lane assignments, and horizontal
  spacing are computed by the pipeline, never by the client. The client does translate +
  draw (§7.1); anything requiring layout logic on device is a format bug.
- **Beats are the unit of musical time; seconds appear nowhere in note data.** A single
  `tempoMap` converts. This is what lets tempo control (§11) work without rewriting notes,
  and it is the #1 source of bugs when violated — so the schema forbids a `seconds` field
  on any note record, and the validator enforces it.
- **Self-contained and offline-complete.** A downloaded pack plus the app is a complete
  experience. No runtime lookups, no CDN dependency mid-lesson.
- **Additive-only evolution.** Unknown keys are ignored by clients; new required behavior
  gates on `minAppVersion`. See §8.1.9.

#### 8.1.2 Pack layout

```
song_<id>_v<n>.pack/            # zip, deterministic ordering, no timestamps
  manifest.json                 # identity, metadata, tempo map, chunk index, rights ref
  notes.json                    # the note data + layout hints, per arrangement level
  chunks.json                   # chunk definitions, teaching modes, prerequisites
  skills.json                   # skill requirements/teachings, per arrangement level
  audio/
    l<N>_backing_full.opus      # full mix — demo/listen mode
    l<N>_backing_norh.opus      # minus right hand → LH practice
    l<N>_backing_nolh.opus      # minus left hand  → RH practice
    l<N>_backing_micsafe.opus   # drums/pad only, no piano-range content (§5.6)
    l<N>_reference.opus         # the piece played correctly, for "listen first"
    count_in.opus               # click bars, tempo-agnostic (pitched click, resampled)
  cover.webp                    # 1:1, 512 px and 1024 px variants
  LICENSE.txt                   # attribution text if CC-BY sourced (§8.5.5)
  checksums.json                # sha256 per file, signed at publish
```

*Expectation:* a 3-minute song at three arrangement levels lands under **8 MB** packed.
Audio dominates; notes.json for a dense 3-minute piece should stay under 200 KB.
If a pack exceeds budget, the build warns; over 2× budget it fails.

#### 8.1.3 `manifest.json` — field by field

| Field | Type | Notes |
|---|---|---|
| `format` | `"songpack/v1"` | Refuse to load anything else. |
| `songId`, `packVersion` | string, int | `packVersion` increments on every publish, never reused. |
| `minAppVersion` | int | Client below this refuses the pack and prompts to update (§8.1.9). |
| `title`, `subtitle`, `composer`, `arranger` | string | `arranger` is us, always, for PD works. |
| `genre`, `era`, `mood[]` | enum, enum, enum[] | Drives library browse facets (§9.4). |
| `difficulty` | 1–10 | Computed (§8.2.6), human-overridable, override recorded with a reason. |
| `durationBeats`, `durationSecondsAtDefaultTempo` | int, float | The second is display-only. |
| `defaultTempoBpm`, `minPracticeTempoPct` | int, int | Floor for the tempo slider, per song. |
| `tempoMap[]` | `{atBeat, bpm, curve}` | `curve: step\|linear` for rits and accels. |
| `timeSignatures[]`, `keySignatures[]` | `{atBeat, …}` | Mid-song changes are normal, not exotic. |
| `pickupBeats` | float | Non-zero for anacrusis. Getting this wrong offsets the entire song by a beat — the most common single content bug, so it gets its own validator check. |
| `arrangementLevels[]` | `{level, name, difficulty, tier}` | `tier: free\|premium`. |
| `chunkCount`, `chunkIndexRef` | int, string | Index lives in `chunks.json`. |
| `rightsRef` | string | FK into the rights record (§8.5.6). **Publish is blocked without it.** |
| `audioProfile` | object | Loudness target, codec, bitrate — recorded so a re-encode can be verified. |
| `buildInfo` | object | Pipeline version, source file hash, build timestamp, git SHA of `/pipeline`. Non-hashed for determinism (§8.2.10), but present for support. |

#### 8.1.4 `notes.json` — the note record

```jsonc
{ "pitch": 60,            // MIDI 21–108; the app's only pitch representation
  "startBeat": 4.0,       // absolute beats from song start, pickup-adjusted
  "durBeats": 0.5,
  "hand": "L" | "R",      // required; never null — see §8.2.4
  "staff": 1 | 2,         // visual staff, which is NOT always the hand
  "voice": 1,
  "finger": 3,            // optional; present only where the lesson teaches fingering
  "tieToIndex": 412,      // index into this array, not a beat reference
  "accidental": "sharp" | "flat" | "natural" | null,  // display only; pitch is absolute
  "beamGroup": 87,        // precomputed
  "lane": 2,              // note-bar skin lane assignment (§7.2)
  "xHint": 1024.5,        // precomputed horizontal spacing unit
  "isOrnamentExpansion": false,  // true for notes we generated from a trill/mordent
  "scoringWeight": 1.0 }  // lets ornament notes count less, or not at all
```

Three details that matter more than they look:

- **`staff` ≠ `hand`.** Cross-staff passages are common in real piano writing. The renderer
  uses `staff`; the scorer and hand-isolation use `hand`. Conflating them breaks LH/RH
  practice on exactly the pieces where it's most needed.
- **`accidental` is display-only.** `pitch` is already absolute. A client must never derive
  pitch from key signature + accidental, and the schema comment says so.
- **`scoringWeight`** lets a trill count as one gesture rather than eleven missed notes.
  Default 1.0; ornament expansions default 0.2; grace notes 0.

#### 8.1.5 Chunks — the pedagogy, encoded as data

```jsonc
{ "chunkId": "c07", "ord": 7,
  "startBeat": 32.0, "endBeat": 48.0,
  "teachingModes": ["RH", "LH", "BOTH"],   // ordered; the sequence the learner walks
  "waitModeDefault": { "RH": true, "LH": true, "BOTH": false },
  "prerequisiteChunks": ["c05", "c06"],
  "loopSafe": true,                         // can this loop musically without a lead-in?
  "countInBeats": 4,
  "label": "Chorus, first half",            // shown to the user
  "difficulty": 4,
  "newSkills": ["chord_Fmaj", "eighth_notes_rh"] }
```

- **2–8 bars**, chosen at phrase boundaries, not bar counts (§8.2.5).
- **`teachingModes` is the whole hands-separate-then-together pedagogy** expressed in
  content rather than code. Changing pedagogy = changing data, not shipping an app release.
- **`loopSafe`** exists because a chunk starting mid-phrase loops horribly; the CMS shows
  a warning and the auto-loop suggestion (§11) skips unsafe chunks.

#### 8.1.6 Arrangement levels

Level 1 (Essentials, melody + simple LH), Level 2 (Intermediate), Level 3 (Pro / close to
the original). Each is a **separate full note set** in `notes.json`, not a diff — diffs
were considered and rejected: the storage saving is trivial and the bug surface is not.

*Expectation:* stars, progress, and skill credit are tracked per `(song, level)`, so
replaying at a harder level is real progression rather than a repeat. Level 1 must be
playable by someone who has completed Course 1 — the pipeline checks this against the
skill graph and fails the build if L1 requires an unteachable skill (§8.2.6).

#### 8.1.7 Audio stem contract

- **48 kHz, Opus.** Stereo 96 kbps for full mixes, mono 64 kbps for the mic-safe stem.
- **Loudness normalized to −16 LUFS integrated, −1 dBTP ceiling**, uniformly across the
  whole catalog. Users play songs back to back; a volume jump between songs reads as
  broken. This is a per-catalog invariant, verified per build.
- **Every stem is sample-aligned to beat 0** including the pickup, with silence padded
  rather than trimmed. A trimmed stem desyncs the whole song and looks like a player bug.
- **The mic-safe stem must contain no energy in the piano fundamental range** (roughly
  80 Hz–2 kHz for the played register). The build measures this and fails if violated —
  it is the difference between mic mode working in a real room and not (§5.6, §20 P1.8.6).
- **`reference.opus`** is the piece played correctly at default tempo, for "listen first."

#### 8.1.8 Integrity and packaging

`checksums.json` holds sha256 per file; the pack itself is signed at publish. The client
verifies on download and on load, and a failed verification **deletes and re-downloads
rather than attempting partial use** (§20 P2.C5). Corrupt content that half-loads produces
support tickets no one can diagnose.

#### 8.1.9 Versioning and compatibility

- **Additive changes** (new optional field): no version bump, old clients ignore it.
- **New required behavior**: bump `minAppVersion`; old clients hide the song from the
  catalog rather than downloading and failing. The catalog API filters by client version.
- **`songpack/v2`** would be a parallel format with the pipeline emitting both during a
  transition window, never a flag day.
- **Republishing** an existing song increments `packVersion`; clients holding the old pack
  are told at catalog refresh and re-download in the background, preserving progress
  (progress keys on `songId` + level + chunk ordinal, never on pack version).

*Expectation, stated as a test:* content authored in Phase 1 week 4 loads unmodified on
the launch client. There is a CI job that builds the Phase-1 golden fixtures against the
current schema on every pipeline change.

#### 8.1.10 One schema, three consumers

The JSON Schema in `/content/schema/songpack-v1.json` is consumed by the Python pipeline
(build-time validation), the PHP API (publish-time validation — never trust the pipeline),
and Kotlin tests (fixture validation). *Expectation:* the schema is generated from one
source and drift is impossible by construction; a CI job fails if any consumer's copy
diverges from the canonical file.

### 8.2 The build pipeline (Python workers)

```
  source score ──▶ [1] ingest ──▶ [2] validate ──▶ [3] normalize ──▶ [4] hands+fingering
                                        │                                    │
                                        ▼ reject with actionable report      ▼
       publish ◀── [11] ◀── [10] pack ◀── [9] audio ◀── [8] levels ◀── [7] layout ◀── [6] difficulty ◀── [5] chunking
```

Each stage is a pure function `(input artifact, config) → (output artifact, report)`,
runnable standalone from the CLI. *Expectation:* any stage can be re-run on a stored
intermediate without re-running the whole pipeline — a 6-minute audio render should never
be repeated because someone fixed a fingering.

**CLI surface** (also the CMS's backend):
```bash
pipeline ingest   score.musicxml --song-id fur-elise --source imslp:12345
pipeline build    fur-elise [--stage N] [--from-stage N] [--level 1,2,3]
pipeline audio    fur-elise --renderer dgx|fluidsynth [--stems all]
pipeline validate fur-elise --strict
pipeline diff     fur-elise --against published
pipeline publish  fur-elise --env staging|prod
pipeline batch    --manifest weekly-batch.yaml --parallel 4
```

#### 8.2.1 Stage 1 — Ingest and source provenance

**What it should do:** take a source file and permanently record where it came from, before
anything mutates it.

- Accept MusicXML (`.xml`/`.mxl`), MIDI, and MuseScore export; store the original **byte-for-byte**
  in the content store, hashed.
- Require `--source` provenance at ingest time: IMSLP work + edition ID, Mutopia ID, or
  commission contract reference. **Ingest fails without it** — retrofitting provenance
  onto 200 songs later is the kind of task that never gets done (§8.5.2).
- Record the source's own licensing claim verbatim, including the edition's editor and
  publication year, which is a different question from the composition's PD status (§8.5.3).

*Expectation:* for any published song, one command returns the exact source file, its
origin, and its rights record. This is the artifact you need if a takedown ever arrives.

#### 8.2.2 Stage 2 — Validation

**What it should do:** reject bad input loudly, with an error a musician can act on, rather
than producing subtly wrong content.

Checks, each producing `error` (blocks) or `warning` (proceeds, shown in CMS):

- **Structural:** parseable; exactly 1–2 staves; a piano part identified; divisions/ticks
  consistent; no zero-duration or negative-duration notes; measures sum correctly to their
  time signature (a classic MusicXML export defect).
- **Range:** all pitches within A0–C8; warn on anything outside a 61-key range, since a
  large share of learners have small keyboards and a song using C8 is unplayable for them.
- **Musical sanity:** no simultaneous notes on the same pitch in the same voice; no chord
  requiring a span greater than a 10th in one hand (warning — it may be a hand-assignment
  error rather than genuine); tie targets exist and match pitch.
- **Unsupported constructs, explicitly enumerated and explicitly handled:** repeats,
  voltas, D.C./D.S./Coda, tuplets, grace notes, cue notes, trills/mordents/turns, glissandi,
  pedal marks, cross-staff beams, multi-voice staves, chord symbols, lyrics.
  *Every one either has a defined normalization (§8.2.3) or a clear rejection message.*
  "Unsupported feature" with no name is a bug in this stage.
- **Tempo:** at least one tempo mark or an explicit `--tempo` override; text-only tempo
  ("Andante") is a warning requiring a human BPM.

*Expectation:* a deliberately mangled MusicXML corpus (kept in `/pipeline/tests/bad/`, one
file per defect class) produces a specific, actionable message for every case, and no stack
traces. Growing that corpus is how this stage matures — every content bug found downstream
gets a new bad-input fixture.

#### 8.2.3 Stage 3 — Normalization

**What it should do:** collapse the enormous expressive variety of MusicXML into the small,
regular structure the app renders.

- **Expand repeats and jumps** into a linear timeline. This is the single most bug-prone
  transformation in the pipeline: nested repeats with voltas plus a D.S. al Coda is a
  control-flow problem, not a music problem. Implement it as an explicit state machine with
  its own unit-test suite, and emit a `repeatMap` recording which source measures produced
  which output beats — the CMS shows it so a human can eyeball the expansion.
- **Tuplets** → exact fractional beat durations (no rounding to a grid; scoring windows are
  ±120 ms and rounding drifts past that).
- **Grace notes** → real notes with `scoringWeight: 0`, placed just before the beat.
- **Ornaments** (trills, mordents, turns) → expanded to notes with
  `isOrnamentExpansion: true` and low `scoringWeight`, *or* preserved as a single note with
  an ornament marker at low arrangement levels. Which one is a per-level decision.
- **Voice normalization:** MusicXML voice numbering is wildly inconsistent between
  exporters; renumber to a canonical scheme.
- **Rests** made explicit where the renderer needs them; implicit rests removed.
- **Pedal marks** retained as metadata (displayed, never scored).
- **Chord symbols and lyrics** retained in metadata for the Chords track (§9.1) and display.

*Expectation:* normalization is idempotent (running it twice changes nothing), and the
`repeatMap` lets any output beat be traced back to a source measure — which is how a
content editor debugs "the second verse sounds wrong."

#### 8.2.4 Stage 4 — Hand assignment and fingering

**What it should do:** assign every note to a hand — correctly, because this drives hand
isolation, the note-bar lanes, and half the pedagogy.

- **Primary signal:** staff assignment in the source (right ≈ staff 1). Correct maybe 85%
  of the time in real scores.
- **Corrections needed for:** cross-staff passages, LH melody crossing above RH, and
  single-staff sources (common in folk/lead-sheet material).
- **Algorithm:** cost-minimizing assignment over a sliding window — hand-span feasibility,
  continuity (hands don't teleport), crossing penalty, and a preference for keeping voices
  in one hand. Output a **per-note confidence**.
- **Low-confidence notes are surfaced in the CMS editor**, not silently guessed. The editor
  shows them highlighted so the human reviews 20 notes instead of 2,000.
- **Fingering:** generated only where the lesson teaches it (beginner arrangements). Use a
  standard cost model (span, thumb-under, weak-finger avoidance, position stability). This
  is genuinely hard to do well; treat generated fingering as a *draft* the educator accepts
  or overrides, and never present low-confidence fingering to a learner — wrong fingering
  actively teaches bad technique, which is worse than none.

*Expectation:* on the 15-song Phase-0 vetted set (§20 P0.8.2), auto hand-assignment agrees
with the educator's judgment on ≥ 95% of notes, and every disagreement is flagged as
low-confidence rather than confidently wrong. Track that agreement rate as a pipeline
quality metric over time.

#### 8.2.5 Stage 5 — Auto-chunking (suggestions, never decisions)

**What it should do:** propose 2–8-bar chunks at musical phrase boundaries and let a human
confirm in two minutes instead of authoring in twenty.

- **Boundary signals:** cadences, rests, repeated melodic material, phrase-length regularity
  (4/8 bars), harmonic rhythm, dynamic and articulation changes.
- **Constraints:** never split a tie or a slur across a boundary; never start a chunk
  mid-beat; respect `loopSafe` (a chunk should begin somewhere a loop can restart musically).
- **Output:** ordered suggestions with confidence and a one-line rationale ("cadence in bar
  16, repeated material begins bar 17").
- **Teaching-mode defaults** per chunk from density and hand independence: dense two-hand
  passages get the full `RH → LH → BOTH` walk; a simple melody gets `BOTH` directly.

*Expectation:* the educator accepts suggestions unmodified on ≥ 70% of chunks, and total
human chunking time per song stays under 5 minutes. If that number slips, this stage —
not the humans — is what needs work. **Never auto-publish chunk boundaries**; musically
stupid boundaries are the fastest way to make lessons feel wrong.

#### 8.2.6 Stage 6 — Difficulty scoring and skill inference

**What it should do:** place the song correctly in the library's difficulty ordering and
declare what a learner must already know to play it.

- **Features:** note density (notes/second at default tempo), maximum hand span, position
  shifts per minute, rhythmic complexity (distinct durations, syncopation, tuplets),
  accidental count, hands-together ratio, largest leap, chord size distribution, tempo,
  and dynamic/articulation demands.
- **Model:** a transparent weighted score calibrated against ~50 educator-rated songs, not
  a black box. When a musician disagrees with a difficulty rating, you need to be able to
  say *which feature* drove it.
- **Skill requirements** inferred against the §9.2 skill graph (`chord_Fmaj`, `eighth_notes`,
  `hands_together_easy`…), with **`newSkills` per chunk** — what this chunk teaches.
- **The gate that matters:** a song's Level-1 arrangement must not require a skill that no
  course teaches, and must not require a skill taught *after* the song's placement in the
  ladder. The build fails on either — this is what prevents the library's "playable by you
  now" filter (§9.4) from lying to users.

*Expectation:* computed difficulty correlates ≥ 0.8 with educator ratings on the holdout
set; every override is stored with a reason and feeds recalibration.

#### 8.2.7 Stage 7 — Layout precompute

**What it should do:** do all the engraving math now, so the device does none of it.

- **Staff skin:** beam groups (respecting time-signature conventions and beat grouping),
  stem directions, accidental placement including courtesy accidentals, collision-avoided
  note positions, tie/slur control points, and `xHint` spacing derived from note durations.
- **Note-bar skin:** lane assignment per hand (avoiding overlap while keeping voice
  continuity), bar geometry, and label placement.
- **Both skins from the same note data** — the toggle (§7.2) must be instant and lossless.
- **Viewport hints:** for each chunk, the pitch range and the recommended keyboard-zone
  window, so the on-screen keyboard auto-centers without the client computing it.

*Expectation:* the client's per-frame work is translate + draw (§7.1), verified by
profiling the Phase-0 renderer against a real pack; screenshot tests compare rendered
output against golden images for both skins on every pipeline change (§20 P1.6).

#### 8.2.8 Stage 8 — Arrangement level generation

**What it should do:** produce Levels 1–3 with as little hand-arranging as possible, while
being honest that the top level is a musician's job.

- **Level 3 (Pro)** is the source arrangement, essentially as ingested.
- **Level 2 (Intermediate):** simplify LH accompaniment patterns (broken chords → block
  chords, reduced voicings), thin inner voices, keep the melody intact.
- **Level 1 (Essentials):** melody plus a minimal LH (roots or simple triads on strong
  beats), simplified rhythm, reduced range.
- **Automated reductions are drafts.** The educator reviews and fixes; the tooling exists to
  make a 30-minute arranging job a 10-minute editing job, not to eliminate it.
- **Invariant across levels:** the melody is recognizable and chunk boundaries align, so
  progress and star tracking are comparable and a learner moving L1 → L2 recognizes the piece.

*Expectation:* L1 of any published song is playable by a learner who has finished Course 1,
verified by the §8.2.6 skill gate *and* by the educator actually playing it.

#### 8.2.9 Stage 9 — Audio stem rendering

**What it should do:** produce the four stems per level, sounding good enough that the app
doesn't feel cheap, at batch scale.

- **Renderer A — DGX-520 rig (§8.6):** arrangement MIDI played out over USB, audio captured.
  Better timbre and character; real-time (a 3-minute song takes 3 minutes), serialized on
  one physical device.
- **Renderer B — fluidsynth + openly-licensed soundfonts:** faster than real time,
  parallelizable, less character. Default for drafts and for bulk backfill.
- **Choice per song, recorded in `audioProfile`.** Draft with B, upgrade hero songs to A.
- **Stem construction:** mute the RH track for `norh`, LH for `nolh`, and build `micsafe`
  from percussion/pad parts only — then **verify spectrally** that it holds no energy in the
  piano fundamental range (§8.1.7). This is a measurement, not an intention.
- **Post:** EBU R128 loudness normalize to −16 LUFS / −1 dBTP, dither, Opus encode, and
  verify the encoded file's loudness (encoding can shift it).
- **Alignment check:** decoded stem onsets compared against the MIDI; **fail the build if
  drift exceeds 10 ms** at any point. Silent drift is the failure mode that makes users
  think their playing is wrong.

*Expectation:* full stem set for one song in under 10 minutes on renderer B; the DGX rig
runs unattended overnight in batch (§8.6.2); every stem passes loudness, alignment, and
mic-safety checks automatically before it can be packed.

#### 8.2.10 Stage 10 — Pack assembly and determinism

**What it should do:** produce byte-identical output for identical input, so that content
diffs mean something and CDN caching is safe.

Determinism requires all of: sorted JSON keys, fixed float formatting, zip entries in
sorted order with timestamps zeroed, pinned versions of every rendering tool, fixed random
seeds anywhere a heuristic samples, and `buildInfo` (which contains a timestamp) excluded
from the content hash.

*Expectation:* a CI job builds every golden fixture twice on different machines and
byte-compares. When it fails, a real nondeterminism has crept in and it is worth stopping
to fix — otherwise `pipeline diff` starts showing phantom changes and people stop reading it.

#### 8.2.11 Stage 11 — Publish

**What it should do:** move a validated pack to the CDN safely and reversibly.

- **Pre-publish gate (all mandatory):** schema valid · rights record complete and cleared
  (§8.5.6) · skill gate passed · audio checks passed · human content-QA sign-off recorded
  (§8.7.4) · size within budget.
- **Diff against the live version** shown to the publisher — note count delta, chunk changes,
  audio re-render, difficulty change — because "I only fixed a fingering" and a 400-note
  diff should stop someone.
- **Upload → verify checksums from the CDN → flip the catalog pointer → purge caches.**
  In that order; the pointer flips only after the content is verifiably readable.
- **Rollback** is a pointer flip to the previous `packVersion`, available for one click in
  the CMS, and rehearsed.

*Expectation:* publish-to-live in under 2 minutes; rollback in under 30 seconds; no window
in which the catalog references a pack that isn't fully uploaded.

#### 8.2.12 Orchestration

The CMS calls the admin API, which writes a `job_outbox` row and enqueues onto
`webman/redis-queue`; a Webman `queue-consumer` process (§13.4.1) invokes the Python worker
and streams stage-level progress back for the CMS to poll. Pipeline runs are minutes long,
so nothing here is synchronous, and an outbox-backed job survives a Dragonfly restart
mid-build (§13.4.5). Stage artifacts are cached by input hash, so `--from-stage` re-runs
are cheap.

*Expectation:* a killed worker mid-build leaves no half-written pack and the job resumes or
restarts cleanly; the CMS always shows which stage is running and what it's doing.

#### 8.2.13 Pipeline testing

- **Golden fixtures:** the §20 P1.1.3 awkward-case set (pickup bars, mid-song key change,
  triplets, ties across chunk boundaries, 6/8, repeat structures) built on every commit and
  compared byte-for-byte.
- **Bad-input corpus:** one file per defect class, asserting the specific error message.
- **Property tests:** repeat expansion (round-trips the `repeatMap`), tuplet arithmetic
  (durations sum exactly), tie resolution, and hand assignment invariants (no note unassigned,
  no impossible span).
- **Round-trip test:** SongPack → MIDI → compare against the normalized source. Catches
  whole classes of silent data loss.
- **Audio checks** as assertions, not eyeballs: loudness, alignment, mic-safe spectrum.
- **Determinism check** (§8.2.10).

*Expectation:* the pipeline suite runs in under 5 minutes and is a required CI job. A
content bug found in review becomes a fixture in the same PR that fixes it.

#### 8.2.14 Failure taxonomy and operator runbook

| Symptom | Likely stage | First check |
|---|---|---|
| Song sounds a beat off from the start | 2/3 | `pickupBeats` — the most common single defect |
| Second verse plays wrong material | 3 | `repeatMap` expansion; check nested repeats + voltas |
| LH practice mode plays the wrong part | 4 | Hand assignment confidence; look for cross-staff |
| Chunk loops sound broken | 5 | `loopSafe` false or boundary mid-phrase |
| Song listed as playable but learner can't | 6 | Skill inference vs. course ladder placement |
| Notes overlap / collide visually | 7 | Layout precompute; add a golden fixture |
| Backing drifts out of sync late in the song | 9 | Stem alignment check; tempo-map curve handling |
| Mic mode gets false notes with backing on | 9 | Mic-safe stem spectral check |
| `pipeline diff` shows changes nobody made | 10 | Determinism regression |

### 8.3 Course and lesson content (curriculum as data)

#### 8.3.1 Step types

Lessons are ordered sequences of typed steps. The set is deliberately small and closed;
adding a type is an app release, so the types must be general.

| Type | Payload | Notes |
|---|---|---|
| `VIDEO` | video id, optional start/end | §8.4 |
| `THEORY_CARD` | template id + params | §8.3.2 |
| `EXERCISE` | songId, level, chunkId, mode, tempo, wait-mode override | The workhorse |
| `SONG` | songId, level, star requirement | Full-piece performance |
| `QUIZ` | question set | Note-naming, rhythm, interval recognition |
| `CHECKPOINT` | songId, level | End-of-course celebration; unlocks the next course |

Each step carries `passCriteria` (min stars, min accuracy, or completion) and
`skillsTaught[]` / `skillsRequired[]`.

#### 8.3.2 Theory cards are templates, not content

A fixed set of JSON-driven templates rendered by the app: staff diagram, keyboard diagram
with highlighted keys, note-name drill, rhythm clap-along, interval comparison,
text-plus-image explainer. **Adding a theory card must never require an app release** —
that is the entire design constraint. New pedagogy = new template *parameters*; a genuinely
new template is a rare, planned app change.

*Expectation:* the educator authors a theory card in the CMS and previews it as it will
appear on device, without an engineer.

#### 8.3.3 Curriculum source format

Curriculum lives in `/content/curriculum/*.yaml`, human-readable and diffable, authored
from Phase 1 (§20 P1.10.1) before the CMS exists — so the CMS reads the existing shape
rather than inventing one:

```yaml
course: essentials-1
track: soloist
ord: 2
title: Essentials I
lessons:
  - id: e1-l03
    title: Both hands together
    skillsTaught: [hands_together_easy]
    steps:
      - type: THEORY_CARD
        template: keyboard_highlight
        params: { keys: [C4, E4, G4], caption: "C major, right hand" }
      - type: EXERCISE
        song: ode-to-joy
        level: 1
        chunk: c01
        mode: RH
        waitMode: true
        pass: { minStars: 1 }
```

#### 8.3.4 The skill graph

- Skills are slugs with a prerequisite DAG (§9.2). **Cycle detection blocks save** in the
  CMS and blocks the build in CI — a cycle soft-locks real users out of content, and it is
  trivially preventable.
- **Reachability check:** every skill required by any published content must be taught by
  some earlier lesson. The build enumerates unreachable requirements and fails.
- **Orphan check:** skills taught but never required are warnings — usually a tagging typo.
- The graph is what powers unlock gating, "you can now play 12 new songs," and workout
  targeting (§9.3), so an error here propagates into three user-visible systems at once.

#### 8.3.5 Curriculum build and publish

Same shape as SongPack: validate → build → diff → publish, with the DAG checks as gates.
Curriculum publishes independently of songs, but a curriculum referencing an unpublished
song fails the gate. *Expectation:* reordering lessons in a live course does not reset
anyone's progress — progress keys on lesson id, never ordinal, and there is a test for it.

#### 8.3.6 Localization

All user-visible content strings (`title`, `label`, theory-card text, quiz questions) are
keyed and externalized from day one (§12.4), even while shipping English only. Retrofitting
i18n across a few hundred lessons is a multi-week project; doing it upfront costs a naming
convention.

### 8.4 Video lessons

**8.4.1 Scope:** a small, high-value set — posture and bench height, hand shape, thumb-under,
counting and subdivision, reading the staff, pedal basics, practice technique. Target ~20
videos at launch, 2–4 minutes each. This is a **budget line, not an engineering project**;
the engineering is delivery.

**8.4.2 Production spec:** 1080p, two angles (over-the-shoulder keyboard view and hands
close-up), clean audio, consistent framing and set so the library looks like one series.
Script reviewed by the educator; no on-screen text that would need re-shooting to localize.

**8.4.3 Encoding:** HLS with an adaptive ladder (240p/480p/720p/1080p), H.264 for
compatibility, plus a single downloadable 480p MP4 for offline. Loudness-normalized to the
same target as the music stems so switching between a video and a lesson doesn't jolt.

**8.4.4 Delivery:** CDN, ExoPlayer, downloadable for offline with the same entitlement and
checksum discipline as SongPacks. Resume position stored per profile.

**8.4.5 Accessibility and i18n:** burned-in text is forbidden; captions are a required
deliverable in the same PR as the video (WebVTT), which makes later translation a text job.

**8.4.6 Expectation:** adding a video is a CMS upload plus a curriculum reference — no app
release, no engineering ticket.

### 8.5 Music sourcing — royalty-free only (no purchased licenses)

Hard rule: every song ships under rights we own outright or that are free for
commercial use. No sync/mechanical license purchases, ever. Source tiers:

1. **Public-domain works** (the backbone): classical (Bach, Beethoven, Mozart,
   Chopin, Satie, Debussy…), folk/traditional (Greensleeves, Scarborough Fair,
   House of the Rising Sun), hymns, children's songs, ragtime/early jazz (Joplin).
   Beginner favorites — Für Elise, Ode to Joy, Canon in D, Gymnopédie No. 1,
   The Entertainer — are all PD, and they're most of what beginners want anyway.
   **Caveat:** the *composition* being PD isn't enough — modern arrangements and
   recordings carry their own copyright. We always make our own arrangements from
   PD scores (IMSLP / Mutopia sources) and render our own audio.
2. **Commissioned originals** (work-for-hire, owned 100%): modern-sounding pieces
   in pop/cinematic/lo-fi styles to fill the "sounds like today" gap.
3. **CC0 / CC-BY music:** usable commercially with attribution tracked in the CMS
   (§14.4). Avoid CC-BY-NC and CC-BY-SA — incompatible with a paid, closed catalog.
4. **Style-alike covers are OUT:** a "sounds like <current hit>" arrangement of a
   copyrighted song is still a derivative work, not royalty-free. Don't go there.

PD status is territory-dependent (life+70 in most markets): the CMS records composer
death year + first-publication year per song and flags anything not globally clear.

#### 8.5.1 Clearance procedure (the actual steps, per song)

1. **Identify the work**: composer, title, catalogue number, composition year, first
   publication year.
2. **Composer death year** → PD in life+70 territories if death year + 70 < current year.
   Record the source of the death year, not just the number.
3. **First publication year** → covers the US publication-year rule (works first
   published in 1930 or earlier are in the public domain as of 2026) and posthumous
   publication edge cases, which are exactly where PD assumptions go wrong.
4. **Territory matrix**: evaluate against our launch markets plus the obvious next ones.
   Anything not clear in *all* of them is flagged and either excluded or geo-gated.
5. **Edition check** (§8.5.3) — the trap that catches most people.
6. **Record everything** in the rights record (§8.5.6) with links to sources.
7. **Second-person review** for anything flagged, and counsel review for any novel category.

*Expectation:* clearance takes under 20 minutes per straightforward PD song. The Phase-0
exercise of doing 15 by hand (§20 P0.8.2) exists to establish that number honestly.

#### 8.5.2 Provenance is captured at ingest, never retrofitted

Stage 1 refuses to ingest without a source reference (§8.2.1), and publish refuses without
a complete rights record (§8.2.11). Both are enforced in code because a "we'll fill it in
before launch" backlog of 200 songs is, in practice, a decision not to have provenance.

#### 8.5.3 The edition trap

**A PD composition in a modern edition is not PD.** Urtext and critical editions carry a
separate copyright in the editorial work — fingerings, phrasing, dynamics, and engraving.
Practical rules:

- Source from **IMSLP editions explicitly marked public domain**, or Mutopia (which
  publishes in source form with clear licensing), and record the specific edition.
- **Our arrangement is our own**: we re-engrave from the PD source and add our own
  fingering, dynamics, and chunking — which is also what makes the arrangement ours to own.
- **Never** import a modern published arrangement, and never scrape a user-uploaded
  MusicXML from a sharing site — its provenance is unknowable, and unknowable provenance is
  the same as bad provenance.
- **All audio is ours**, rendered from our own arrangements (§8.6). No third-party recordings.

#### 8.5.4 Commissioning workflow

For the modern-sounding originals in tier 2: written work-for-hire agreement assigning
copyright in the composition *and* the delivered audio, with a warranty of originality;
brief specifying style, difficulty, and length; delivery as MusicXML/MIDI **plus** stems;
contract reference stored in the rights record; payment on acceptance after the same content
QA as any other song. *Expectation:* the contract template is reviewed once by counsel and
reused; no commission starts on a handshake.

#### 8.5.5 CC attribution mechanics

CC-BY requires attribution in a form the user can actually find. Attribution text lives in
the rights record, is written into `LICENSE.txt` in the pack, and is surfaced on the song's
info screen and in an app-wide credits page. CC-BY-SA and CC-BY-NC are rejected at intake
by the CMS, not by policy memory. *Expectation:* the credits page is generated from rights
records, so it cannot drift from the catalog.

#### 8.5.6 The rights record

```
rights_records(id, song_id, tier, composer, composer_death_year,
  composition_year, first_publication_year, source_type, source_ref, source_url,
  edition_editor, edition_year, edition_license,
  cc_license?, cc_attribution_text?, commission_contract_ref?,
  territory_flags_json, cleared_globally: bool,
  reviewed_by, reviewed_at, notes)
```
Mandatory before publish (§14.4). `cleared_globally = false` forces either exclusion or an
explicit geo-gating decision recorded in `notes`.

#### 8.5.7 Audit and takedown readiness

Quarterly audit: every published song has a complete, reviewed rights record; spot-check
10% against sources by hand. A documented takedown response path: locate the record,
unpublish in one action (pointer flip, §8.2.11), respond with provenance. *Expectation:*
"unpublish this song everywhere" takes under 5 minutes, and the evidence for why we
believed we were clear is one query away.

### 8.6 DGX-520 production rig (owned hardware)

The Yamaha DGX-520 (76 keys, ~500 XGlite voices, USB-MIDI) becomes a build asset:

- **Backing-stem factory:** the pipeline plays arrangement MIDI out to the DGX over
  USB while capturing its audio output — batch-renders royalty-free backing stems
  (piano, strings, drums, pads) with far more character than raw fluidsynth.
  Recordings of performances made *with* an instrument are standard, safe use.
- **Detection ground truth:** capture MIDI and audio simultaneously → perfectly
  note-aligned recordings for the detection test bench (§19.2), including
  through-air mic recordings of the DGX's speakers on each test device.
- **MIDI input test device:** one of the three lab keyboards (§19.3) for USB-MIDI
  integration testing — already owned.
- **One nuance for the in-app piano sound:** for the *embedded, redistributable*
  soundfont (§3.2 synth), use an openly licensed sample set (e.g. Salamander Grand
  Piano, CC-BY) rather than multisampling DGX voices — recording performances is
  fine, but redistributing a sample library ripped from the hardware invites an
  EULA/copyright question we don't need. Sampling the DGX for internal test audio
  is unrestricted.

#### 8.6.1 Physical setup and signal chain

Line out (not the headphone jack — different output stage, and it mutes the speakers you
need for through-air capture) → audio interface → 48 kHz/24-bit capture. USB-MIDI to the
same host. Fixed gain staging, documented and taped down, because a mid-catalog gain change
means every earlier render is at a different level. Room treated enough for the through-air
captures (§8.6.4) to be representative rather than boxy.

#### 8.6.2 Batch render harness

**What it should do:** unattended overnight rendering of a week's worth of stems.

Queue of (song, level, stem) jobs → for each: set the DGX voice/program, play the MIDI,
record, trim to a sync marker, run the QC gates (§8.6.5), store or fail with a reason.
Include a leading sync click on every take so alignment is measured, not assumed.

*Expectation:* an 8-hour unattended run completes ≥ 40 stems with < 5% requiring a re-take,
and the failures are reported with a cause rather than discovered by ear later.

#### 8.6.3 Ground-truth capture for detection

Simultaneous MIDI + audio, sync-marked, automatically verified to within 5 ms (§20 P0.7).
Feeds the model bake-off (§20 P0.3), the CI detection bench (§19.2, §20 P3.7), and every
later model decision. This corpus is a compounding asset: it is what lets you change the
audio engine in year two without guessing.

#### 8.6.4 Through-air capture

The same takes re-recorded via each test phone's microphone, at three distances/positions,
in three noise conditions. This is what makes the detection bench reflect reality rather
than a studio. *Expectation:* re-capturing the full corpus on a newly added device is a
scripted half-day, not a project — new phones arrive continuously.

#### 8.6.5 QC gates on every render

Loudness within target, no clipping, no dropouts (silence-gap detection), alignment within
10 ms, correct duration, and mic-safety spectrum for the `micsafe` stem. All automated;
a human listens to a spot-check sample, not to everything.

#### 8.6.6 When to outgrow it

The DGX is one physical device rendering in real time — a hard ceiling of roughly 60–80
stems/week with overnight batching. If content throughput approaches that, options in order
of cost: shift bulk work to fluidsynth and reserve the DGX for hero songs (cheapest, already
supported by `audioProfile`); add a second hardware unit; or license a high-quality sample
library for offline rendering (check its redistribution terms carefully — rendered audio is
usually fine, the samples themselves usually are not).

### 8.7 Content operations — the assembly line

#### 8.7.1 Roles and handoffs per song

| Stage | Owner | Typical time |
|---|---|---|
| Song selection, rights clearance | Content ops / PM | 20 min |
| Source acquisition + ingest | Content ops | 10 min |
| Pipeline build + validation triage | Automated + content ops | 5 min |
| Hand/fingering review, chunk confirmation | Educator | 20 min |
| Arrangement level review and fixes | Educator | 30–60 min |
| Audio render | Automated (batch) | 0 min attended |
| Content QA — play every chunk on a real piano | Educator | 20 min |
| Publish | Content ops | 5 min |

≈ **2–2.5 hours of human time per song**, most of it the educator's. That figure is the
whole reason §8.2.4, §8.2.5, and §8.2.8 emphasize *good drafts and flagged low confidence*
over full automation: shaving the educator's review is worth far more than shaving compute.

#### 8.7.2 Throughput math

8 songs/week × ~2.25 h ≈ **18 hours/week of educator time** on catalog work — roughly half
a full-time role, with the rest going to curriculum. That is the actual staffing constraint
behind the §20 P2.D5 target, and it is why the educator is a Phase-0 hire (§21.3).

If throughput is short, the levers in order of effect: (1) improve auto-chunk acceptance
rate, (2) improve hand-assignment confidence so less is flagged, (3) batch by composer/style
so the educator stays in one idiom, (4) ship fewer arrangement levels per song initially and
backfill L2/L3 later, (5) hire a second part-time arranger. **Not** on the list: skipping
content QA.

#### 8.7.3 Batching strategy

Work in themed batches (a Bach batch, a folk batch, a lo-fi commission batch): rights
research shares context, the educator stays in one style, and audio renders share a voice
setup. Weekly batch manifest → `pipeline batch --parallel 4`.

#### 8.7.4 Pre-publish quality checklist (enforced, not aspirational)

- [ ] Rights record complete, reviewed, globally cleared or explicitly geo-gated
- [ ] Schema validation clean, zero errors, warnings triaged
- [ ] Hand assignment reviewed; no unresolved low-confidence notes
- [ ] Chunk boundaries confirmed by a human; all `loopSafe` flags correct
- [ ] Each arrangement level played end-to-end on a real piano by the educator
- [ ] Skill requirements verified against the course ladder (automated gate)
- [ ] All stems pass loudness, alignment, and mic-safety checks
- [ ] Difficulty rating sanity-checked against neighbors in the library
- [ ] Cover art present, correct, and rights-clear
- [ ] Pack size within budget
- [ ] Diff against previous version reviewed if republishing

#### 8.7.5 Content KPIs

Tracked weekly from Phase 2 week 1: songs published/week · median human-minutes per song ·
auto-chunk acceptance rate · hand-assignment agreement rate · build failure rate by stage ·
republish rate (content shipped then fixed — a proxy for QA quality) · per-measure global
error heatmap outliers (§14.5, which finds badly-tuned content after release) · catalog
coverage by difficulty band and genre.

**The coverage metric deserves emphasis:** a catalog of 150 songs that are all difficulty
6–8 is useless to the beginners who are the entire target market. Track the histogram and
commission against the gaps.

#### 8.7.6 Release calendar

Monthly themed drops ("Ragtime in March") plus a weekly trickle, scheduled in the CMS
(§14.4) with publish dates set in advance. Predictable content cadence is a retention
mechanic and a marketing asset; it also forces the pipeline to stay operable by non-engineers.

#### 8.7.7 Content debt and republishing

Content ships, then reality reports back: a measure everyone misses, a chunk boundary that
confuses people, a difficulty rating that's wrong. Treat these as a normal backlog with a
weekly triage from the §14.5 dashboards, republish via §8.2.11, and track the republish rate
so the *upstream* quality gate improves rather than the fixes just getting faster.

---

## 9. Curriculum (needs a music-educator hire/contractor from day 1)

### 9.1 Course ladder (mirrors Simply Piano's two tracks)
- **Soloist track:** melody + reading: *Piano Basics → Essentials I → II → III →
  Intermediate I → II → Pre-Advanced…*
- **Chords track:** pop accompaniment: *Chords I → II → III → Lead Sheets I → II…*
- Each course = 15–25 lessons; each lesson = 5–10 steps; end-of-course "checkpoint song."

### 9.2 Skill graph
Every lesson tags skills (`note_C4`, `rh_5finger`, `chord_Cmaj`, `eighth_notes`,
`hands_together_easy`…). Prerequisites form a DAG → drives unlock gating (§10.4),
song recommendations ("you can now play 12 new songs!"), and workout targeting.

### 9.3 5-Minute Workouts
Daily generated set of 3–4 micro-drills: 1 review of a weak skill (from the per-measure
error heatmap, §6), 1 current-lesson exercise, 1 song chunk near mastery, 1 new
sight-reading snippet. Server-generated nightly per profile, cached offline; a pure
Kotlin fallback generator runs locally when offline.

### 9.4 Song library UX
Browse: search, genre chips, difficulty filter, "playable by you now" smart filter
(skill graph vs. song requirements), favorites, recently played. Each song page:
arrangement-level picker, demo playback, stars earned per level.

---

## 10. Gamification

1. **Streaks:** a day counts when ≥1 workout or lesson step is completed; streak
   calendar UI; 1 "streak freeze" earned per 7-day streak (kindness beats churn).
   Local + server clock reconciliation to survive offline days.
2. **XP & levels:** XP per note hit (1), per chunk ★/★★/★★★ (10/25/50), lesson
   complete (100), workout complete (75). Profile level = f(total XP), cosmetic.
3. **3-star scoring:** §6; stars persist per chunk/song/arrangement-level; library
   shows star badges → natural replay motivation.
4. **Unlockables:** courses gate on predecessor completion; songs gate on skill
   requirements (+ Premium entitlement); "checkpoint" songs celebrate with a
   full-song play + shareable (image only — kids-safe, no social links, §16).
5. **Weekly goal ring:** user picks 3/5/7 days-per-week goal at onboarding; home
   screen ring tracks it.

All tunables (XP values, star thresholds, streak rules) come from remote config.

---

## 11. Practice Utilities

- **Metronome:** free-practice screen + toggleable in any exercise; 40–220 BPM, tap
  tempo, accent patterns (2/4, 3/4, 4/4, 6/8); sample-accurate in engine (§5.1).
- **Hand isolation:** per-chunk mode switch — mute LH stem & score RH only, or reverse
  (uses `backing_norh/nolh` stems + hand-filtered expected notes).
- **Looping:** drag-select measures on the transport bar → loop at 50–100% tempo;
  auto-suggest loop after 2 consecutive fails on the same measure ("Practice this part
  slowly?").
- **Tempo control:** 50/75/100% presets + slider on any exercise; scoring windows scale;
  stars ≥★★ require ≥ 90% tempo (prevents cheesing, matches Simply Piano behavior).
- **Free Play mode:** open mic/MIDI listening with note-name display — doubles as a
  detection-quality demo and a debugging surface.

---

## 12. Accounts, Profiles, Onboarding

### 12.1 Auth
Email+password and Google Sign-In. Anonymous "guest" mode allowed through Course 1
lesson 3 (reduce signup friction), then soft-prompt to save progress.

### 12.2 Onboarding flow (first launch)
1. Who's playing? (age band — drives kids mode + COPPA path §16)
2. Experience? (never / a little / used to play / can read music)
3. Instrument? (acoustic / keyboard w. speakers / MIDI-capable / none yet → Touch)
4. Goal? (play songs / read music / technique / for my kid)
5. Weekly goal pick → 6. mic permission w. explainer OR MIDI setup → 7. latency
   auto-cal (§5.5) → 8. **"play 3 notes" magic moment** — detection demo within 90
   seconds of install → 9. placement: experienced users get a 2-min placement test
   (short sight-reading chunks) → recommended starting course.

### 12.3 Multi-profile (up to 5)
- `account 1—N profile`: name, avatar (built-in art only), age band, own progress/
  streaks/settings. Profile switcher on home. Subscription entitlement is account-level.
- Kid profiles: restricted settings, no account/billing access without account PIN.

### 12.4 Settings
Audio input picker, latency cal, notation skin, left-hand color / colorblind-safe
palette, reduced motion, practice reminders (local notifications), download-over-wifi,
language (i18n from day 1: strings externalized; launch EN, structure for ES/DE/FR/PT).

### 12.5 Support
"Have a question?" → in-app form (attaches app version, device, anonymized detection
stats) → helpdesk email/API. FAQ pages rendered in-app (no external browser — §16).

---

## 13. Backend Services & API (Webman/Workerman + MySQL + Dragonfly)

### 13.1 Core tables (abridged)
```
accounts(id, email, google_id?, pass_hash, status, created_at)
profiles(id, account_id, name, avatar, age_band, settings_json)
subscriptions(id, account_id, play_purchase_token, product_id, state,
              trial_ends_at, renews_at, acknowledged, rtdn_last_event)
songs(id, slug, title, artist, genre, era, tier, status)
song_versions(id, song_id, songpack_ver, cdn_url, checksum, published_at)
arrangements(id, song_id, level, difficulty, skill_reqs_json)
courses(id, track, ord, title, ...) lessons(id, course_id, ord, ...)
lesson_steps(id, lesson_id, ord, type, ref_json)
skills(id, slug) · lesson_skills · arrangement_skills
progress(profile_id, subject_type, subject_id, stars, best_score,
         completed_at, attempts, PRIMARY KEY(profile_id, subject_type, subject_id))
practice_sessions(id, profile_id, started_at, seconds, notes_hit, notes_missed,
                  device_meta_json)
measure_errors(profile_id, arrangement_id, measure, miss_count)   # workout fuel
streaks(profile_id, current, best, freezes, last_counted_date, tz)
workouts(id, profile_id, date, items_json, completed_mask)
events_raw(...)  # analytics firehose, partitioned
```

### 13.2 API surface (v1, all JSON, JWT bearer)
```
POST /auth/register|login|google|refresh        GET  /me
CRUD /me/profiles (max 5)                       GET  /catalog/courses, /catalog/songs?filters
GET  /catalog/songpacks/{id}/download-url       # signed CDN URL, entitlement-checked
GET  /profiles/{id}/progress                    PUT  /profiles/{id}/progress (batch upsert, idempotent)
POST /profiles/{id}/sessions                    # practice session + note stats
GET  /profiles/{id}/workout?date=               POST /billing/play/verify   # purchase token → entitlement
POST /webhooks/play-rtdn                        # Pub/Sub push: renewals, cancels, grace
POST /support/tickets                           GET  /config   # remote config: tunables, feature flags
```
- **Progress sync is offline-first:** client owns truth between syncs; batch upserts
  keyed by `(subject, updated_at)` with last-write-wins + max(stars) merge.
- Rate limiting, request signing for download URLs, per-profile authorization checks
  on every progress route (same discipline as MyAdmin's `custid` ownership checks).

### 13.3 Entitlements
`free`: Course 1 + ~20 songs + Touch basics. `premium`: everything. Evaluated
server-side at download-URL time AND baked into catalog responses; client caches an
entitlement token (short TTL, offline grace 7 days).

### 13.4 Webman runtime model (this is the part that bites teams coming from FPM)

Webman runs the application **once** and then serves requests in a loop inside
long-lived worker processes. That is where the throughput comes from, and it is also
the entire source of new failure modes. Every rule below exists because the opposite
has caused a production incident in somebody's Workerman app.

#### 13.4.1 Process inventory (`config/process.php`)

| Process | Count | Responsibility |
|---|---|---|
| `webman` (HTTP) | 4 × vCPU | The REST API. Stateless per request. |
| `rtdn-consumer` | 1 | Pulls Google Pub/Sub subscription notifications, writes `job_outbox`, drives the entitlement state machine (§15). Single instance — ordering matters. |
| `queue-consumer` | 2–4 | `webman/redis-queue` consumer: pipeline dispatch, CDN purge, email, receipt re-verification. |
| `workout-gen` | 1 | Nightly per-profile 5-Min Workout generation (§9.3), sharded by profile-id modulo so it can scale to N later. |
| `analytics-flush` | 1 | Drains the Dragonfly analytics buffer → batched MySQL/warehouse inserts every 5 s or 1 000 events. |
| `scheduler` | 1 | `Workerman\Timer`-based cron replacement (streak rollover per timezone, entitlement expiry sweep, orphaned-download GC). |
| `monitor` | 1 | `webman/monitor`: file-watch in dev; in prod, restarts any worker exceeding the memory ceiling. |

Rule: **exactly one instance of anything that must not run twice.** A second
`rtdn-consumer` would double-apply subscription transitions. Enforce with a Dragonfly
lock (`SET lock:rtdn <token> NX PX 30000` + refresh timer) so even a botched deploy
that starts two copies degrades to one active.

#### 13.4.2 State-hygiene rules (violating these is the #1 Workerman bug class)

- **Never `exit()`/`die()`** — it kills the worker, not the request. Return a response
  object. Add a CI grep that fails the build on `exit(`/`die(` outside `start.php`.
- **No request data in statics.** No `static $user`, no container singleton holding
  `$request`, no `$GLOBALS`. Request-scoped values travel in the `$request` object or a
  per-request context object created by middleware and discarded at response time.
- **Superglobals are meaningless.** `$_GET`/`$_POST`/`$_SERVER`/`$_SESSION` are not
  populated per request; use `$request->get()/post()/header()/session()`. CI grep for
  superglobal reads in `app/`.
- **`set_error_handler`, `ini_set`, `date_default_timezone_set`, locale changes are
  process-global and permanent** — do them once at bootstrap, never inside a handler.
- **Anything registered in a loop leaks.** Eloquent model event listeners, middleware
  arrays, `spl_autoload_register` — register at bootstrap only. A listener re-registered
  per request means the Nth request fires N callbacks (a real, silent, quadratic bug).
- **Unbounded in-process caches are leaks.** Any per-process memoization must be an LRU
  with a hard entry cap, not a plain array that grows.
- **Memory ceiling + graceful reload:** `monitor` restarts a worker over 256 MB RSS;
  `php start.php reload` drains connections and re-executes bootstrap with zero dropped
  requests. Every deploy is `git pull && composer install --no-dev && php start.php reload`.
- **Long-lived DB connections drop.** MySQL `wait_timeout` will close idle worker
  connections; enable reconnect handling and set `wait_timeout` above the idle window.
  Verify with a "leave it overnight, then hit it" test in staging — this failure only
  shows up after hours of quiet.

#### 13.4.3 Coroutines: default off, and here is exactly when to turn them on

Webman 2.1 on Workerman 5.1 exposes Fiber/Swoole/Swow drivers through
`Workerman\Coroutine` (with `Context`, `Channel`, `Pool`, `WaitGroup`, `Barrier`).

- **Default: blocking mode, one request at a time per worker, many workers.** Eloquent
  runs on PDO, which is a blocking C extension — putting a blocking PDO call inside a
  Fiber blocks the whole worker anyway. Coroutines buy nothing for a DB-bound endpoint
  and cost you connection-safety complexity.
- **Turn them on only for outbound-HTTP fan-out**: verifying purchase tokens against the
  Google Play Developer API, CDN cache-purge calls, JWKS refresh. Do that work in the
  queue/consumer processes with `workerman/http-client`, keeping the HTTP workers plain.
- **If coroutines are ever enabled on the HTTP workers**, every connection (DB, Dragonfly)
  must become coroutine-local via `Workerman\Coroutine\Pool` and any request context must
  move into `Coroutine\Context`. That is a deliberate, benchmarked migration with its own
  load test — never an incidental config flip. Write the decision down in an ADR.

#### 13.4.4 What we lose from Laravel, and the replacement

| Laravel affordance | Webman replacement |
|---|---|
| `artisan make:*`, tinker | `webman/console` (`php webman make:controller`, `make:model`, REPL) |
| Eloquent + migrations | `webman/database` (illuminate/database) + phinx or illuminate migrations via console |
| Validation | `respect/validation` or `illuminate/validation` standalone, invoked in a `ValidateRequest` middleware |
| Passport / Sanctum | Own JWT + refresh-rotation service (§3.3 Auth row); ~400 lines, fully testable |
| Queues | `webman/redis-queue` + `job_outbox` (§13.4.5) |
| Scheduler | `scheduler` custom process with `Workerman\Timer` (or `workbunny/webman-crontab`) |
| Horizon | `/metrics` Prometheus endpoint + Grafana board (queue depth, job latency, failures) |
| `php artisan test` harness | §13.7 |

Net: about a week of scaffolding we would not have written on Laravel, bought back by a
runtime that holds daemons in-process and does not re-bootstrap the framework 3 000
times a second. Accept the trade knowingly — it is the reason for the switch, not a
side effect.

#### 13.4.5 Durability rule for jobs

Queue payloads in Dragonfly are fast but not a system of record. Anything that touches
money, entitlements, or published content is written to a MySQL `job_outbox`
(`id, type, payload_json, state, attempts, available_at, locked_by, created_at`) inside
the same transaction as the state change, then pushed to the queue. The consumer marks
the outbox row done. A Dragonfly restart loses at most speed: the `scheduler` process
re-drives any outbox row still `pending` after 60 s. Ordinary jobs (thumbnail, email,
analytics rollup) skip the outbox and are allowed to vanish.

### 13.5 Dragonfly instead of Redis — usage rules and the compatibility fence

Dragonfly is wire-compatible with Redis and multi-threaded, so one node scales
vertically where Redis would need sharding, and the PHP side is unchanged (phpredis /
`webman/redis` / `webman/redis-queue` all just work against it).

**What we rely on, all verified-supported:** `GET/SET/SETEX/INCR`, hashes, sorted sets
(delayed queue), lists + `BRPOP`/`BLMOVE` (queue consumers), `EXPIRE`/`TTL`,
`MULTI/EXEC/WATCH`, `EVAL`/`EVALSHA` (atomic token-bucket rate limiter), pub/sub,
streams with consumer groups (analytics buffer), `SCAN`, `SELECT` (db index per test
worker), `ACL`, and `REPLICAOF` replication.

**The fence — do not build on these:**

| Feature | Dragonfly status | What we do instead |
|---|---|---|
| Redis Functions (`FUNCTION LOAD`, `FCALL`) | **Unsupported** | `EVAL`/`EVALSHA` Lua only. Lint rule: no `FUNCTION`/`FCALL` in the codebase. |
| `CLIENT TRACKING` client-side caching | **Partial** (no `BCAST`/`PREFIX`/`REDIRECT`) | In-process LRU with a short TTL + explicit invalidation messages on pub/sub. Never RESP3 invalidation push. |
| Redis Sentinel | **Not a thing here** | Replica via `REPLICAOF`; failover = promote replica + flip the VIP/proxy, scripted and drilled in Phase 3 (§20 P3.9.4). Cache loss is survivable by design. |
| Redis Cluster (real sharding) | Emulated cluster commands only | Not needed — one node, vertically scaled. If we ever outgrow it, shard by key prefix in the app, not by client-side cluster support. |
| Keyspace notifications | Supported in current builds (added via dragonflydb PR #3154) — but version-sensitive | Allowed for nice-to-haves only. Nothing on the critical path may depend on an expiry event; use explicit queue messages. |
| Module ecosystem parity (RediSearch/RedisJSON/Bloom/TimeSeries) | Dragonfly ships built-in `FT`, `JSON`, `BF`, `CF`, `CMS`, `TOPK`, `TDIGEST`, `TS` commands, but they are **not** guaranteed feature-parity with the Redis modules | Catalog search stays on MySQL `FULLTEXT` (and Meilisearch later if it needs to be better). `BF`/`TOPK` are permitted for non-critical dedupe/telemetry only, behind an interface with a plain-Redis-command fallback. |

**Enforcement, not good intentions:** a `tests/Compat/DragonflyCommandSurfaceTest.php`
runs the full list of commands the app actually issues against a pinned Dragonfly
container in CI and fails on any error reply. The list is generated by a
`RedisCommandRecorder` decorator enabled in the integration test suite, so the surface
can't silently grow past what we've verified. Pin the Dragonfly image tag; treat a
version bump as a change that must pass that test.

**Escape hatch:** the connection is configured by DSN in one place. If some future
dependency genuinely requires a Redis-only feature, swapping that one workload back to
a Redis 7 instance is a config change, not a refactor. Document any such split.

### 13.6 Deployment & operations

- nginx terminates TLS and reverse-proxies to `127.0.0.1:8787` (`proxy_http_version 1.1`,
  keepalive upstream, WebSocket upgrade headers ready for later live features).
- systemd unit wrapping `php start.php start -d`, `ExecReload=php start.php reload`,
  `Restart=always`, `LimitNOFILE=65535`.
- Deploy = pull, `composer install --no-dev -o`, run migrations, `reload`. Reload drains
  in-flight requests; no 502s. Rollback = checkout previous tag + reload.
- `GET /healthz` (liveness: process up) and `GET /readyz` (readiness: MySQL ping +
  Dragonfly ping + migrations current). LB uses `/readyz`.
- `GET /metrics` — Prometheus text from the monitor process: per-worker RSS, req/s, p50/
  p95/p99 latency by route, queue depth, outbox backlog, Dragonfly hit rate, RTDN lag.
- Log to stdout → journald → shipper; structured JSON lines with a request-id set in
  middleware and echoed in the `X-Request-Id` response header.
- Capacity baseline to establish in Phase 0 (§20 P0.6.5) and re-check in Phase 3:
  a 4-vCPU node should serve ≥ 5 000 req/s of cached catalog reads and ≥ 800 req/s of
  authenticated progress-sync writes at p99 < 120 ms.

### 13.7 Backend testing without a Laravel test harness

- **Unit:** pure services (entitlement state machine, sync merge, workout generator,
  rate-limit math) with plain PHPUnit — no framework boot. These are the majority.
- **Integration:** `tests/bootstrap.php` starts a real Webman instance on an ephemeral
  port in a child process, plus MySQL and Dragonfly containers; tests hit it over HTTP
  with Guzzle. Each test class gets its own Dragonfly db index via `SELECT` and a MySQL
  schema wrapped in a transaction that rolls back.
- **The Workerman-specific suite (do not skip):** a "worker longevity" test that fires
  10 000 requests through one worker and asserts (a) RSS growth < 5 MB, (b) no
  cross-request data bleed (request N never sees request N−1's user), (c) the worker is
  still alive. This is the test that catches the state-hygiene violations in §13.4.2,
  and nothing else will.
- **RTDN simulator:** replays recorded Pub/Sub payloads for every subscription
  transition, including out-of-order and duplicate delivery.
- **Load:** k6 scenarios per route class, run in CI nightly against staging, with the
  §13.6 numbers as the pass threshold.

---

## 14. Admin CMS (Vue 3 + Vite + TS + Pinia)

The internal tool that makes content scale. SPA against admin-scoped API.

1. **Song intake:** upload MusicXML/MIDI + stems → pipeline runs (§8.2) → validation
   report (range, voice/hand sanity, chunking suggestions).
2. **Interactive song editor** (the flagship CMS feature):
   - Piano-roll + staff preview side-by-side (render via Verovio-JS/OSMD in browser —
     web rendering is fine here, it's not latency-critical)
   - Edit hands/fingering/chunk boundaries; set per-chunk teaching mode (RH/LH/both,
     wait-mode default); audition with synthesized playback; set arrangement level +
     skill requirement tags
   - Diff view between SongPack versions; one-click publish → CDN + cache purge
3. **Curriculum builder:** drag-drop lessons/steps, skill tagging, prerequisite DAG
   visualization (cycle detection!), preview-as-device mode.
4. **Catalog ops:** rights-provenance metadata per song (PD verification — composer
   death/publication years; CC attribution text; commission contract ref — §8.5),
   mandatory before publish; tier assignment; scheduling ("new songs every month"
   release calendar).
5. **Analytics dashboards:** funnel (install → magic moment → D1/D7 → trial → paid),
   per-lesson drop-off, per-measure global error heatmaps (find badly-tuned content),
   detection-accuracy telemetry by device model.
6. **Remote config editor** with staged rollout + kill switches (e.g., disable model
   X on device Y).

---

## 15. Monetization (Google Play Billing)

- **Products:** `premium_yearly` (anchor, 7-day free trial), `premium_quarterly`,
  `premium_monthly`. One subscription covers all 5 profiles (family value prop).
- Play Billing Library 7: `BillingClient`, subscription offers/base plans, trial
  eligibility via offer tags; **server-side verification mandatory** — client posts
  purchase token → server verifies via Play Developer API, stores entitlement,
  acknowledges purchase.
- **RTDN (Real-Time Developer Notifications)** via Pub/Sub → webhook: renewals,
  grace period, on-hold, cancel, revoke → entitlement state machine
  (`active → grace → hold → expired`).
- Paywall: after Course 1 free content, at locked songs, and post-onboarding offer
  screen. Transparent copy (price, trial terms, cancel anytime) — required by Play
  policy and by decency; Simply Piano gets review-bombed for exactly this, be better.
- Restore purchases, account-hold recovery deep link, proration between plans.
- No ads ever (Families policy + product stance).

---

## 16. Kids Safety & Compliance (gates store approval — not optional)

- **Play Families / "Designed for Families"**: mixed-audience app. Age band collected
  at onboarding (neutral age screen); child profiles → no behavioral analytics, no
  personalized anything, built-in avatars only, no free-text visible to others.
- **COPPA / GDPR-K:** verifiable parental consent flow for child accounts (account
  creation is adult-gated; kid profiles live under adult account — mirrors Simply
  Piano's model and simplifies consent). Data minimization: audio is processed
  **on-device only and never uploaded** (say this loudly in the privacy policy —
  it's also a marketing point). Only derived note-stats sync.
- No external links in kid-reachable surfaces; support/FAQ rendered in-app; parental
  PIN gate on billing/settings.
- **Privacy policy + ToS** pages (web) before store submission; Play Data Safety form
  matching actual behavior; mic permission rationale strings.
- Accessibility: TalkBack labels everywhere outside the player; colorblind-safe
  feedback palette option (§12.4); font scaling in non-player screens.

---

## 17. Analytics & Progress UX

- **User-facing progress:** profile dashboard — minutes practiced (week/month graphs),
  notes hit, accuracy trend, streak calendar, skills unlocked map, courses %.
  Weekly recap notification ("You practiced 47 min, hit 1,204 notes, +2 skills").
- **Product analytics events:** onboarding funnel steps, magic-moment success rate,
  detection confidence histograms per device model (drives model tuning), lesson
  step outcomes, paywall views/conversions, subscription lifecycle.
- Kids profiles: analytics restricted to anonymous, aggregated, service-required
  events (§16).

---

## 18. Offline Support

- Course content + next N recommended SongPacks auto-downloaded (wifi-only default);
  full player, scoring, streaks, workouts (local generator) work offline.
- Progress queued in Room outbox → WorkManager sync with retry/backoff.
- Entitlement offline grace: 7 days since last verified check, then free-tier degrade
  with friendly messaging.

---

## 19. Testing & QA Strategy

### 19.1 Standard layers
- Kotlin unit tests: scorer, wait-mode state machine, streak/XP logic, sync merge
  (all pure functions by design).
- C++ engine tests on host (Catch2): WAV-file in → NoteEvents out, golden-file diffs.
- Compose UI tests for flows; screenshot tests for notation renderer against
  golden SongPacks.
- Backend: PHPUnit — pure-service unit tests, HTTP integration tests against a real
  booted Webman instance, the worker-longevity/state-bleed suite, the Dragonfly command-
  surface test, RTDN webhook simulator, entitlement state-machine property tests (§13.7).

### 19.2 The special one: **detection accuracy test bench**
- Corpus: MIDI-aligned recordings (DGX-520 simultaneous MIDI+audio capture, §8.6)
  across ≥15 device models ×
  piano types (grand, upright slightly out of tune, cheap keyboard via speakers) ×
  noise conditions (quiet, TV, talking).
- CI job scores model builds: note F1, onset timing error distribution, chord recall.
  **Regression gate:** no model/DSP change ships if F1 drops >0.5% on any device class.
- Field telemetry loop: anonymized detection-confidence stats per device → weekly
  triage → device-specific config via remote config (§14.6).

### 19.3 Device/hardware lab
Physical: ~10 popular phones/tablets (include 2 low-end), 3 MIDI keyboards — the
owned Yamaha DGX-520 (USB, §8.6) plus a BLE one and a cheap class-compliant one —
1 acoustic piano access, OTG adapters. Firebase
Test Lab for the wide matrix on UI tests.

### 19.4 Beta program
Closed track → open beta with in-app detection-feedback button ("did we hear you
right?") capturing opt-in 10 s audio snippets (adults only, explicit consent) to
grow the training corpus.

---

## 20. Roadmap & Milestones

### 20.0 How to read this section

Every step below is written as **what it should do → how it gets built → what
"done" measurably means**. A step with no measurable expectation is a step nobody can
tell you finished, so each one carries either a number, an artifact, or a demonstrable
behavior.

**Standing Definition of Done** (applies to every step, not repeated below):
code merged to `main` behind a feature flag where user-visible · unit tests for pure
logic · CI green · no new lint/static-analysis findings · docs/ADR updated if a decision
was made · demoed in the Friday build review.

**Cadence:** two-week iterations inside each phase; Friday build review with the whole
team where the app/API is actually run, not screenshotted; a written phase-gate review
at each phase boundary with a go / go-with-cuts / stop-and-rethink decision.

**Durations** assume the §21 team. A two-person team should multiply by ~2.5× and take
the cut lines in §20.6 immediately rather than at the end.

**Symbol:** 🤖 marks steps where AI assistance carries most of the weight and a
generalist can own work that would otherwise want a specialist (see §21.2).

---

### PHASE 0 — Feasibility Spikes (4–6 weeks)

**Objective:** answer the four questions that can kill the product, before spending a
single week on anything that assumes the answers are yes. Nothing built in this phase is
expected to survive into production; the *knowledge* is the deliverable, and every spike
ends in a written finding, not a branch nobody reads.

**Entry criteria:** hardware in hand (DGX-520, 5 test phones spanning flagship → sub-$150,
one BLE MIDI keyboard, OTG adapters); Play Console account created; Google Cloud project
for Pub/Sub provisioned.

**Anti-goal:** do not build product. No onboarding, no accounts, no UI polish. Every
spike is a throwaway harness app with a debug-ugly UI.

#### P0.1 — Repo, toolchain, and CI skeleton (week 1)

**P0.1.1 Monorepo layout.** One repository, five top-level workspaces, because the
content format is the contract between all of them and it must version atomically:
```
/engine        C++20 audio engine (portable core + Android bindings)
/android       Kotlin app (Gradle, Compose)
/api           Webman 2.1 backend
/cms           Vue 3 + Vite admin SPA
/pipeline      Python content workers
/content       SongPack sources, curriculum YAML, rights metadata
/docs          ADRs, specs, runbooks, phase-gate reviews
```
*Expectation:* a fresh clone plus one `make bootstrap` produces a working dev env on
Linux and macOS in under 20 minutes, verified by a new person actually doing it and
timing it. If it takes longer, fix the bootstrap — this cost is paid by every future hire
and every CI run.

**P0.1.2 Toolchain pinning.** NDK version, Gradle/AGP, Kotlin, CMake, PHP 8.3, Composer,
Node/Vite, Python 3.12 + `requirements.txt` hashes, Dragonfly image tag, MySQL 8 image
tag — all pinned in files, none in a wiki. *Expectation:* CI and laptops build byte-identical
artifacts; "works on my machine" is a bug report against P0.1.2.

**P0.1.3 CI skeleton (GitHub Actions).** Five jobs from day one, all fast and all
mandatory: `engine-host-tests` (C++ on Linux, no device), `android-unit`, `api-tests`
(PHPUnit + MySQL + Dragonfly service containers), `cms-build`, `lint-all`. Total wall
clock under 10 minutes. *Expectation:* a PR that breaks any workspace is red before
review, and nobody has learned to ignore a flaky job — flakiness gets fixed the day it
appears, not triaged.

**P0.1.4 ADR practice.** `docs/adr/NNNN-title.md`, one page: context, options, decision,
consequences. Phase 0 will produce roughly 8–12 of them (model choice, coroutine posture,
notation renderer, SongPack versioning…). *Expectation:* by the gate review, every
non-obvious choice has a one-page justification a future engineer can argue with.

#### P0.2 — Spike A: microphone → screen loop with monophonic detection 🤖

**What it should do:** play a single note on a real piano, see the note name appear on
the phone in under 80 ms, consistently, on a mid-tier device.

**P0.2.1 Oboe input stream.** Exclusive-mode, `PerformanceMode::LowLatency`, native
sample rate, mono, callback-driven, with the callback doing nothing but writing into a
lock-free ring buffer. Log the actually-granted stream properties (Android silently
downgrades to shared mode on many devices). *Expectation:* a table of
`device → granted mode / frames-per-burst / sample rate` for all 5 test phones. Learning
which devices refuse exclusive mode is a Phase-0 deliverable in itself.

**P0.2.2 YIN/pYIN monophonic detector.** Textbook algorithm, ~200 lines, running on a
worker thread over 2048-sample windows with 50% overlap. This is deliberately the
*easy* detector — it exists to prove the plumbing and to be the low-end fallback (§5.3).
*Expectation:* correct pitch on every white and black key from A0 to C8 played on the
DGX, ≥ 99% of single notes, with octave errors counted separately and reported.

**P0.2.3 JNI event bridge.** `NoteEvent` structs pushed onto a lock-free SPSC queue,
drained by a Kotlin coroutine into a `Flow`. Never allocate, lock, or call JNI from the
audio callback. *Expectation:* a 10-minute soak with continuous playing shows zero
dropped events, zero audio glitches (Oboe `xRunCount` stays flat), and no measurable
main-thread jank.

**P0.2.4 Latency measurement rig — the actual point of this spike.** Three independent
measurements, because each catches something the others miss:
- *Loopback:* device speaker click → mic → detection, cross-correlated. Gives round-trip.
- *High-speed camera (240 fps phone slo-mo is enough):* film the hammer strike and the
  screen simultaneously, count frames. Gives the true human-perceived number, including
  display latency the software cannot see.
- *Internal instrumentation:* timestamps at ring-buffer write, detector output, JNI
  hand-off, and frame present.

*Expectation:* a per-device latency budget breakdown (hardware in, buffer, detect, bridge,
render, display) summing to the measured total, ± 5 ms. **You cannot optimize what you
have not decomposed** — a single "it feels laggy" number is useless, and this table is
what makes the rest of the project's audio work tractable.

**Gate contribution:** < 80 ms note-to-screen on the mid-tier device, < 120 ms worst case
across all five.

#### P0.3 — Spike B: polyphonic transcription bake-off 🤖

**What it should do:** decide, with evidence, which model ships — and prove it runs fast
enough on cheap hardware.

**P0.3.1 Offline evaluation harness first, models second.** Python: takes
(audio file, aligned ground-truth MIDI) pairs and any model wrapper, emits note-level
precision/recall/F1 (`mir_eval` conventions), onset timing error distribution, chord
recall by chord size, and octave-error rate. *Expectation:* the harness scores a known
published result on a MAESTRO subset within a couple of points of the paper's number —
if it doesn't, the harness is wrong and every later measurement would have been a lie.
**This step exists before any model work and must not be skipped or shortened.**

**P0.3.2 Test set assembly (not just MAESTRO).** MAESTRO is clean studio grand recordings
and will flatter every model. Build a second set that reflects reality: DGX-520 aligned
captures (P0.7), a slightly out-of-tune acoustic upright, a cheap 61-key keyboard through
its own speakers, each recorded through-air on all five phones, in three noise conditions
(quiet / TV in background / someone talking). *Expectation:* ≥ 60 minutes of aligned audio
spanning ≥ 24 condition combinations, checked into DVC/LFS with a manifest.

**P0.3.3 Candidate builds.** (a) Magenta Onsets-and-Frames TFLite, (b) Spotify Basic Pitch
exported to TFLite, (c) the YIN baseline from P0.2 as the floor. Quantize each to
float16 and int8. *Expectation:* six model artifacts, each with size, and each scoring
on the P0.3.1 harness.

**P0.3.4 On-device benchmark app.** Runs each artifact against CPU/XNNPACK, GPU delegate,
and NNAPI on every test device, measuring per-inference latency (p50/p99), real-time
factor, peak memory, and 10-minute sustained thermal behavior. *Expectation:* a matrix of
`device × model × delegate → latency, RTF, memory, thermal-throttle-onset`, with the
delegate crashes and silent fallbacks explicitly noted. NNAPI in particular fails or
falls back silently on many OEM builds; discovering that now is worth weeks later.

**P0.3.5 Decoder tuning.** Hysteresis thresholds, minimum note length, octave-error
suppression from the onset envelope (§5.2.5), swept as a parameter grid on the harness.
*Expectation:* chosen parameters plus a sensitivity plot showing the choice sits on a
plateau, not a spike — a threshold that is optimal but fragile will fall apart on the
first unseen piano.

**P0.3.6 Written model decision (ADR).** Model, delegate strategy per device class,
fallback ladder, and the honest list of conditions where it underperforms.

**Gate contribution:** ≥ 90% chord F1 on the mid-tier device in a quiet room with the
acoustic upright; ≥ 80% in the noisy condition; onset timing error p95 < 40 ms.

#### P0.4 — Spike C: MIDI in, both transports

**P0.4.1 USB host MIDI** via `MidiManager`: enumerate, open, decode NoteOn/NoteOff/velocity
including running status and NoteOn-velocity-0-as-off. *Expectation:* DGX-520 over USB-OTG
produces correct `NoteEvent`s for a 5-minute dense passage with zero dropped or stuck notes,
including fast repeated notes and full-hand chords.

**P0.4.2 BLE MIDI:** scan, pair, connect, auto-reconnect after the keyboard sleeps or the
phone backgrounds. *Expectation:* reconnect within 5 s of the keyboard waking, no duplicate
device entries after 10 connect/disconnect cycles, and measured BLE jitter documented
(it is typically 10–30 ms and *will* affect scoring windows — the number goes in the budget).

**P0.4.3 Source arbitration:** MIDI connected ⇒ mic detection auto-disables; unplug ⇒
back to mic within one second, with a visible toast. *Expectation:* no state where both
sources emit and notes double-count — verified by unplugging mid-passage 20 times.

**P0.4.4 Silent-controller detection:** identify MIDI devices with no audio output and
route through the soundfont synth. *Expectation:* pressing a key on a mute controller makes
a piano sound from the phone within the same latency budget as P0.2.

#### P0.5 — Spike D: scrolling notation at 60 fps

**P0.5.1 Renderer prototype.** Compose `Canvas` drawing a right-to-left scrolling window of
notes with a fixed playhead, positions from `songTime = f(frameClock, tempo)`. Pre-layout
all glyph geometry once, off the main thread; per-frame work is translate + draw only.

**P0.5.2 Stress content.** 200 simultaneous-ish notes on screen, dense beaming, both
skins (note-bar and staff), Bravura glyphs loaded.

**P0.5.3 Profiling.** Perfetto/JankStats traces on the lowest-end test device, 5-minute
run. *Expectation:* ≥ 58 fps average, ≤ 1% dropped frames, no frame over 24 ms, stable
memory. Record the headroom — Phase 1 adds feedback animation, keyboard, and combo FX on
top of this budget, and if the prototype only just makes it, the real player never will.

**P0.5.4 Fallback probe.** If Compose Canvas cannot hold it: 30-minute experiment with a
plain `AndroidView` + custom `View`, and note the delta. *Expectation:* an ADR that says
which renderer Phase 1 uses and why, with numbers.

#### P0.6 — Spike E: Webman + Dragonfly runtime spike 🤖

**Why this spike exists:** switching off PHP-FPM to long-lived workers moves a whole class
of bugs from "impossible" to "silent and intermittent." Meet them in week 3 of the project,
in a throwaway app, not in Phase 2 with real users' progress data.

**P0.6.1 Skeleton service.** Webman 2.1 on PHP 8.3, `webman/database` → MySQL 8,
`webman/redis` → Dragonfly, four routes: a static JSON, a MySQL read, a Dragonfly
read-through cache, and an authenticated write. *Expectation:* running via
`php start.php start`, reachable behind nginx, health endpoints live.

**P0.6.2 State-bleed torture test.** Deliberately write the bugs from §13.4.2 — a static
holding the current user, a per-request event listener registration, an `exit()` — then
write the tests from §13.7 that catch each one. *Expectation:* each deliberate bug is
caught by a test that fails loudly; then delete the bugs and keep the tests. The team has
now *seen* the failure mode, which is the only way this discipline survives contact with
a deadline.

**P0.6.3 Longevity soak.** One worker, 100 000 requests over an hour, mixed routes.
*Expectation:* RSS growth < 5 MB, p99 latency at hour-one within 10% of minute-one, worker
never restarted, zero cross-request data bleed. Then leave it idle overnight and hit it
again: *expectation* is the MySQL connection reconnects cleanly rather than throwing
"MySQL server has gone away" on the first morning request.

**P0.6.4 Dragonfly compatibility audit.** Enumerate every Redis command the intended
stack will issue — `webman/redis-queue` internals included — and run all of them against
the pinned Dragonfly container, asserting no error replies. Explicitly probe the fence
items in §13.5 and confirm the failure is graceful. *Expectation:* the
`DragonflyCommandSurfaceTest` from §13.5 exists and is green, and any surprise goes into
an ADR. Confirm the queue plugin's blocking pop, delayed-zset, and retry paths all behave
under a Dragonfly restart mid-consume.

**P0.6.5 Load baseline.** k6 against a 4-vCPU staging box: cached catalog reads,
authenticated writes, and a queue-drain scenario. *Expectation:* the §13.6 numbers
(≥ 5 000 req/s cached reads, ≥ 800 req/s authenticated writes, p99 < 120 ms) either met or
a written explanation of what the bottleneck is. This number is the one Phase 3 regresses
against; without it, later "is it slower?" arguments have no referee.

**P0.6.6 Reload and failover drill.** `php start.php reload` under sustained load
(*expectation:* zero failed requests, zero dropped connections); kill Dragonfly mid-load
(*expectation:* the API degrades to MySQL-direct with elevated latency and recovers
automatically, no 500s to clients, no lost queue jobs that were outbox-backed).

**P0.6.7 Coroutine posture ADR.** Benchmark one outbound-HTTP-heavy endpoint blocking vs.
Fiber-driven. *Expectation:* a written decision (expected outcome: coroutines off for HTTP
workers, on for the consumer processes' outbound fan-out) with the measurements attached.

#### P0.7 — Spike F: DGX-520 ground-truth capture rig 🤖

**What it should do:** produce perfectly aligned (audio, MIDI) pairs on demand, which is
the raw material for P0.3, the Phase-3 CI bench, and every future model decision.

**P0.7.1 Simultaneous capture:** MIDI over USB and line-out audio recorded together, with
a shared clock reference (a sync click at the head of every take). **P0.7.2 Alignment
verification:** automated check that MIDI note onsets land within 5 ms of audio onsets;
takes that fail are re-recorded, not hand-fixed. **P0.7.3 Batch playback:** feed
arrangement MIDI out to the DGX and capture its rendered audio unattended.
**P0.7.4 Through-air capture:** the same takes re-recorded via each test phone's mic at
three distances/room positions.

*Expectation:* one command produces a labeled, verified, manifest-tracked corpus entry;
an unattended overnight run yields ≥ 2 hours of aligned material. If alignment is
hand-managed, the corpus stops growing the week someone gets busy — automate it now.

#### P0.8 — Rights and content groundwork (runs in parallel, low intensity)

**P0.8.1 PD verification workflow:** the checklist and evidence format (composer death
year, first publication, edition provenance, territory flags) that §14.4 will later
enforce in software. **P0.8.2 Vet 15 candidate songs** end to end by hand, including
sourcing a clean PD score from IMSLP/Mutopia. *Expectation:* 15 songs with completed
provenance records and at least 10 cleared globally — and a realistic sense of how long
one song takes, which is the input to the content-throughput plan. **P0.8.3 Educator
engagement:** curriculum lead contracted or hired, with a first draft of the Course-1
skill sequence. **P0.8.4 Legal review** of the §8.5 royalty-free-only policy and the
arrangement/recording position, by an actual lawyer, once.

#### P0.9 — Phase-0 gate review

A written document, reviewed in a meeting where the demos are run live.

| Gate criterion | Threshold | Source |
|---|---|---|
| Note-to-screen latency, mid-tier device | < 80 ms | P0.2.4 |
| Chord detection F1, mid-tier, quiet, acoustic upright | ≥ 90% | P0.3.4 |
| Chord detection F1, noisy condition | ≥ 80% | P0.3.4 |
| Onset timing error p95 | < 40 ms | P0.3.1 |
| Model inference RTF on low-end device | < 0.5 | P0.3.4 |
| MIDI USB + BLE round-trip, no stuck notes | 5 min dense passage | P0.4 |
| Notation prototype | ≥ 58 fps, 200 notes, low-end device | P0.5.3 |
| Backend soak | 100k requests, < 5 MB RSS growth, no bleed | P0.6.3 |
| Dragonfly command surface | 100% green, fence documented | P0.6.4 |
| Ground-truth corpus | ≥ 2 h aligned, automated | P0.7 |
| Rights | ≥ 10 songs globally cleared, legal sign-off | P0.8 |

**If the detection gate fails** — decide between: (a) fine-tune on our corpus (adds
4–6 weeks and is the point where a specialist becomes worth hiring, §21.3);
(b) ship MIDI-first and market to keyboard owners, with mic as beta;
(c) narrow the promise to monophonic-plus-simple-chords for beginner courses and defer
full polyphony. **(d) proceed anyway and hope** is not on the list. Write which one and why.

**If the latency gate fails on specific devices only:** ship a device allowlist with a
documented "reduced accuracy" mode, and put the OEM/chipset pattern into remote config.

**Explicitly not built in Phase 0:** accounts, payments, real UI, curriculum content
beyond one lesson's worth of test material, the CMS, offline support.

---

### PHASE 1 — Vertical Slice (8–10 weeks)

**Objective:** one course, five songs, no backend — but the lesson experience is *real*.
The question this phase answers is not "does it work" (Phase 0 answered that) but **"does
it feel right?"**, which is the thing that actually determines whether anyone keeps using
a piano app.

**Entry criteria:** Phase 0 gate passed or consciously re-scoped; model and renderer ADRs
written.

**Principle for the whole phase:** every piece of the lesson loop is built to the quality
bar it needs at launch, for a deliberately tiny amount of content. Breadth is Phase 2's
job. Resist adding a second course.

#### P1.1 — Freeze SongPack v1

**P1.1.1 Write the spec** (`docs/specs/songpack-v1.md`): every field, every unit (beats vs.
seconds vs. ticks — pick one and say so at every field), every enum, the required/optional
matrix, and the forward-compatibility rule (unknown keys ignored; a `minAppVersion` field
lets content require a newer client). **P1.1.2 JSON Schema + validator** usable from the
Python pipeline, the PHP API, and Kotlin tests — one schema, three consumers, no drift.
**P1.1.3 Golden fixtures:** 5 hand-authored packs covering the awkward cases (pickup bars,
key change mid-song, triplets, ties across chunk boundaries, 6/8, a repeat structure).
**P1.1.4 Versioning and migration policy:** how v1 content behaves on a v2 client and
vice versa, decided now rather than after 300 songs exist.

*Expectation:* the format is frozen for all of Phase 1; changing it requires an ADR and a
migration of the golden fixtures. Content authored in week 4 still loads in week 40.

#### P1.2 — Pipeline CLI v0 (Python) 🤖

**What it should do:** `pipeline build song.musicxml → song_x_v1.pack`, deterministically.

**P1.2.1 MusicXML ingest + validation** (music21): part/voice/hand sanity, range checks,
unsupported-feature detection with clear errors rather than silent wrong output.
**P1.2.2 Normalization:** voices → hands, tie/slur resolution, grace notes, ornaments
expanded or explicitly rejected. **P1.2.3 Auto-chunking:** phrase detection at 2/4/8-bar
boundaries, emitted as *suggestions* with confidence — a human confirms in the CMS later,
and pretending otherwise produces musically stupid chunk boundaries. **P1.2.4 Layout
precompute:** beam groups, spacing hints, note-bar lane assignment. **P1.2.5 Audio stems**
via DGX rendering (§8.6) or fluidsynth, EBU R128 loudness-normalized, Opus-encoded, with
the mic-safe drums/pad variant. **P1.2.6 Pack + checksum + manifest.**

*Expectation:* the same input produces a byte-identical pack on any machine (determinism
matters for caching and for diffing content changes); all 5 Phase-1 songs build from one
command; a deliberately malformed MusicXML produces an actionable error, not a stack trace.

#### P1.3 — Engine productionization

Take the Phase-0 spike code and make it something the app can depend on.

**P1.3.1 Engine API surface:** `configure()`, `start()`, `stop()`, `setInputSource()`,
`setLatencyOffset()`, `pushMidiBytes()`, event queue out. Stable, documented, C-ABI-ish for
future iOS reuse. **P1.3.2 Threading model written down** and enforced: which thread may
allocate, which may block, which may call JNI. **P1.3.3 Onset gating** so the ML model runs
only when something was played (the battery story depends on this).
**P1.3.4 Lifecycle robustness:** audio route changes mid-lesson (headphones in/out, BT
connect, phone call, another app grabbing exclusive audio), app backgrounding, device
rotation. *Expectation:* a scripted 30-event chaos sequence leaves the engine playing
correctly, never crashed, never silently deaf. **P1.3.5 Host test harness in CI:** WAV in →
NoteEvents out, golden-file diffs, running on Linux with no device. *Expectation:* engine
regressions are caught by `engine-host-tests` in under 2 minutes, on every PR.

#### P1.4 — Latency calibration (§5.5)

**P1.4.1 Auto-calibration:** 4 clicks out, cross-correlate the mic capture, derive
round-trip. **P1.4.2 Per-route storage:** speaker / wired / Bluetooth stored separately and
re-run automatically on route change. **P1.4.3 Bluetooth output handling:** BT adds
150–300 ms and must shift the *backing-track schedule*, never the scoring window — getting
this backwards makes the app blame the user for the phone's latency. Test with two BT
devices of different latency. **P1.4.4 Manual calibration screen:** tap-along, ±5 ms,
live preview. **P1.4.5 Sanity clamps:** implausible results (< 0 ms, > 500 ms) are rejected
and fall back to a device-class default rather than being stored.

*Expectation:* on all five devices, after auto-cal, a human playing exactly on the beat
scores PERFECT ≥ 90% of the time. That single sentence is the whole acceptance test, and
it is worth more than any internal metric here.

#### P1.5 — Scoring engine (pure Kotlin, §6)

**P1.5.1 Matching:** windows, tempo scaling, beginner widening, wrong-pitch-in-window
classification. **P1.5.2 Chord clustering:** 90 ms grouping, partial credit.
**P1.5.3 Verdicts and score math**, stars from remote-config thresholds.
**P1.5.4 Error telemetry:** per-measure miss heatmap emitted (this feeds workouts in
Phase 2 — emit it now so there's data later). **P1.5.5 Property tests:** generated event
streams (early, late, extra, missing, wrong-octave, rolled chords) with invariants —
score is monotone in accuracy, never NaN, never > 100, identical input always yields
identical output. **P1.5.6 Replay tool:** record a session's event stream, replay it
offline against a modified scorer. *Expectation:* scoring changes are argued with recorded
real sessions instead of opinions.

*Expectation:* zero I/O, zero Android dependencies, ≥ 95% line coverage, and a scorer a new
engineer can read in one sitting.

#### P1.6 — Lesson player (the signature screen, §7)

**P1.6.1 Layout and transport bar:** tempo, pause, loop, progress; landscape-first,
portrait supported. **P1.6.2 Note-bar skin** (beginner, colored lanes, letters inside).
**P1.6.3 Staff skin** (grand staff, clefs, accidentals, beams, ties, from precomputed
layout). **P1.6.4 Skin toggle** with instant switch mid-lesson, no reload.
**P1.6.5 Real-time feedback:** green fill + pop on hit, red outline on miss as the playhead
passes, wrong-key flash on the keyboard, expected-key pulse. **P1.6.6 Combo counter and
juice**, with a reduced-motion setting that genuinely removes motion.
**P1.6.7 On-screen keyboard zone:** 88-key strip auto-centered on the chunk range, target
glow leading by ~1 beat, live played-key display from any source, finger badges.
**P1.6.8 Touch input path:** multi-touch → `NoteEvent(source=TOUCH)` → the same scorer,
voiced by the soundfont. **P1.6.9 End-of-chunk results screen:** stars, score, per-measure
heatmap, retry/next.

*Expectations:* ≥ 58 fps on the low-end device with feedback and combo FX active (the
Phase-0 headroom now gets spent — measure it again, don't assume); screenshot tests against
golden SongPacks for both skins; visual feedback appears within one frame of the scoring
verdict; and a first-time user can tell what to do without instructions, verified by
watching three people who have never seen it.

*Status (2026-08-25): in progress — architecture + toolchain research done; implementation starts 2026-08-26 (see §24 evening entry).*

#### P1.7 — Wait-for-Me mode (§7.4)

**P1.7.1 State machine:** `SCROLLING → HOLD → RESUME`, with `songTime` frozen and the
backing track ducked in HOLD. **P1.7.2 Chord holds** wait for all tones with the cluster
tolerance. **P1.7.3 Resume easing** — a hard jump back to tempo feels broken; ease over
~200 ms. **P1.7.4 Hint escalation:** 5 s idle → key glow; 10 s → note-name display;
15 s → offer to skip. **P1.7.5 Unit tests** on the state machine independent of UI.

*Expectation:* a beginner who stops for 30 seconds mid-song, then plays the right note,
resumes musically — no jump, no double-trigger, no stuck hold. Tested by deliberately
playing wrong notes 10 times in a row at a hold point.

#### P1.8 — Audio playback, mixing, and practice utilities

**P1.8.1 Backing-track playback** with sample-accurate sync to the visual timeline —
including after pause/resume, seek, loop, and tempo change. **P1.8.2 Metronome** scheduled
by frame position in the engine, 40–220 BPM, accents. *Expectation:* metronome drift under
5 ms over 5 minutes, measured against the audio clock, not eyeballed.
**P1.8.3 Hand isolation** using `backing_norh`/`nolh` stems plus hand-filtered expected
notes. **P1.8.4 Tempo control** 50/75/100% with scoring windows scaling and the ≥ 90%-tempo
rule for ★★+. **P1.8.5 Section looping** with drag-select and the auto-suggest after two
consecutive same-measure failures. **P1.8.6 Echo mitigation:** ducking + mic-safe stems in
mic mode. *Expectation:* with the backing track at normal volume through the phone speaker
and mic detection active, false-positive note events stay under 1 per minute — this is the
number that decides whether mic mode is usable at all in a real room.

#### P1.9 — Local progress and the course runner

**P1.9.1 Room schema** for profiles-lite, progress, sessions, star records — designed now
with the Phase-2 sync fields (`updated_at`, `dirty`, `server_version`) already present, so
Phase 2 is a sync implementation and not a migration. **P1.9.2 Course runner:** lesson →
steps → chunk sequencing, prerequisite gating, ★-minimum advancement, "play again to
polish" prompt. **P1.9.3 Step types:** exercise and song for now; theory card and video as
stubs. **P1.9.4 Resume:** killing the app mid-lesson and reopening returns to the same step
with progress intact.

#### P1.10 — Content: Course 1 and five songs

Educator-authored, ten lessons, hands-separate-then-together, each song chunked, each
chunk mode-tagged. **P1.10.1 Author in the source format** (curriculum YAML in `/content`)
even though the CMS doesn't exist — the CMS in Phase 2 must read what already exists rather
than inventing a new shape. **P1.10.2 Build all packs through the P1.2 CLI**, never by
hand. **P1.10.3 Content review pass** by the educator playing every chunk on a real piano.

*Expectation:* a complete, coherent 10-lesson course that takes a true beginner from
nothing to playing a recognizable piece with two hands — not five disconnected demos.

#### P1.11 — Dogfood protocol

**P1.11.1 Recruit 5 real beginners** (not team members, not musicians; ideally including
one child around 8 and one adult over 50). **P1.11.2 Structured sessions:** observed
first-run, then a week of unsupervised daily use, then an interview.
**P1.11.3 Instrument the app** with local-only session logs. **P1.11.4 Measure:** did they
complete Course 1; time-to-first-successful-note from app open; how often they said the app
was wrong when it wasn't (and vice versa — count both, they have opposite fixes); where they
quit; whether they came back on day 2 and day 7 without being asked.

**Phase-1 gate:**

| Criterion | Threshold |
|---|---|
| Beginners completing Course 1 unaided | ≥ 3 of 5 |
| "The app heard me correctly" agreement | ≥ 4 of 5, and no one strongly disagrees |
| Unprompted return on day 2 | ≥ 3 of 5 |
| Player frame rate, low-end device, full FX | ≥ 58 fps |
| False-positive notes in mic mode with backing | < 1/min |
| Crash-free session rate | ≥ 99% |
| Content builds reproducibly from CLI | 100% |

**If "feels right" fails**, the fix is almost never more features. Ranked suspects: latency
calibration wrong on their device → scoring feels unfair; matching windows too tight →
widen for beginners; wait-mode resume janky; content pacing too steep in lessons 3–5.
Diagnose with the P1.5.6 replay tool against their recorded sessions before changing anything.

**Explicitly not built in Phase 1:** any backend, accounts, payments, the CMS, more than one
course, downloads, gamification beyond stars.

---

### PHASE 2 — Product Foundation (10–12 weeks, five parallel tracks)

**Objective:** turn a good lesson player into a product — accounts, catalog, sync,
progression, content at volume, and the tooling that makes content an ops function rather
than an engineering one.

**Entry criteria:** Phase-1 gate passed; SongPack v1 stable; the team is now working in
parallel tracks, which means the integration contracts below matter more than any single
track's velocity.

**Integration discipline for the phase:** the OpenAPI spec is written *before* the endpoints
and is the contract between Track A, Track C, and the CMS. App and CMS develop against a
mock server generated from it. Spec changes are PRs that the consuming tracks review.
Bi-weekly all-track integration day: real app, real API, real content, end to end.

#### Track A — Backend on Webman (§13)

**P2.A1 Project skeleton and conventions.** Directory layout, middleware pipeline (request
id → CORS → auth → rate limit → validation → handler), error envelope, the request-context
object, structured logging, `webman/console` commands. *Expectation:* an ADR-documented
"how to add an endpoint" doc plus one reference endpoint that a new dev can copy — and the
§13.4.2 CI greps live from day one, before there is code to violate them.

**P2.A2 Auth.** Register/login (argon2id), Google Sign-In verification against Google's
JWKS with cached keys, JWT issuance (RS256, 15 min), refresh-token rotation with reuse
detection and family revocation, logout, and password reset. *Expectation:* the auth
service is pure-testable; a replayed refresh token revokes the whole family and is
covered by a test; keys rotate without invalidating live sessions.

**P2.A3 Profiles.** CRUD with the max-5 rule enforced server-side, kid-profile flags,
per-profile authorization on *every* route that names a profile — not just the obvious
ones. *Expectation:* an authorization test matrix where every profile-scoped route is
attempted with another account's profile id and returns 404 (not 403 — don't leak
existence). Same discipline as MyAdmin's `custid` ownership checks.

**P2.A4 Catalog and downloads.** Courses/lessons/songs/arrangements with filtering and
paging; entitlement baked into responses; signed, expiring, entitlement-checked CDN URLs.
*Expectation:* catalog responses are Dragonfly-cached with explicit invalidation on publish
(p95 < 30 ms warm); a signed URL for premium content issued to a free account is impossible
by construction and covered by a test; URL signatures expire and are single-song-scoped.

**P2.A5 Progress sync (the highest-risk endpoint).** Batch idempotent upsert, last-write-wins
with `max(stars)` merge, per-item conflict resolution, an idempotency key per batch.
*Expectation:* property tests prove convergence — apply the same batches in any order, on
any number of devices, with duplicates, and the final state is identical. Sync must never
*lower* a star rating; a user who earned ★★★ offline on a plane does not land to ★.
Test the "two devices, both offline, both played, both sync" case explicitly.

**P2.A6 Practice sessions and measure-error ingest**, feeding the workout generator.

**P2.A7 Workout generator** (`workout-gen` process): nightly per profile, using the skill
graph and error heatmap; deterministic given inputs so it can be tested; the Kotlin offline
fallback (Track C) must produce a *reasonable* set from local data alone.

**P2.A8 Analytics ingest.** Batched client events → Dragonfly stream buffer → the
`analytics-flush` process → warehouse. *Expectation:* ingest survives a 10× spike by
buffering rather than 500-ing; kid profiles are filtered to service-required events at
ingest, server-side, so a client bug cannot leak them (§16).

**P2.A9 Admin API** (admin-scoped, separate auth path, MFA on admin accounts) for the CMS:
content CRUD, publish, rights metadata, dashboards, remote config.

**P2.A10 Remote config service** with staged rollout percentages, device-class targeting,
and kill switches. *Expectation:* changing a star threshold or disabling a model on one
device family takes effect within 15 minutes with no app release, and every change is
audit-logged with who/when/why.

**P2.A11 Observability and deployment** per §13.6: metrics, dashboards, alerts (queue depth,
outbox backlog, RTDN lag, p99, error rate, worker restarts), systemd units, reload-based
deploys, and a staging environment that matches prod topology.

#### Track B — Admin CMS (Vue 3 + Vite + TS + Pinia, §14)

**P2.B1 Shell:** auth, routing, layout, role-based nav. **P2.B2 Song intake:** upload,
pipeline trigger, live job status (polled; the pipeline is a queue job), validation report
rendering with actionable errors. **P2.B3 Song editor** — the flagship: piano-roll + staff
preview (Verovio-JS/OSMD in browser, latency-uncritical here), hand/fingering/chunk-boundary
editing, per-chunk teaching mode, synthesized audition, arrangement level and skill tagging.
*Expectation:* editing a chunk boundary and re-publishing takes under 2 minutes end to end,
and the editor never lets you save content the validator would reject.
**P2.B4 Curriculum builder:** drag-drop lessons/steps, skill tagging, prerequisite DAG
visualization with **cycle detection that blocks save** (a cycle in the skill graph
soft-locks real users out of content — catch it here, not in support tickets).
**P2.B5 Rights provenance UI**, mandatory before publish, with territory flags.
**P2.B6 Publish flow:** version diff against the live pack, publish → CDN → cache purge,
and one-click rollback to the previous version. **P2.B7 Dashboards:** funnel, per-lesson
drop-off, global per-measure error heatmaps (badly-tuned content shows up as a red measure
across thousands of users — this is how content quality gets fixed at scale), detection
telemetry by device model. **P2.B8 Remote-config editor** with staged rollout and diff-before-apply.

#### Track C — Android app breadth

**P2.C1 Onboarding** (§12.2), all nine steps, with the **magic moment measured**:
time from first launch to the user's first successfully detected note. *Expectation:*
< 90 seconds at p50, instrumented as a funnel, and treated as a headline metric for the
rest of the project. **P2.C2 Placement test** for experienced users → recommended course.
**P2.C3 Accounts and profile switcher**, guest mode through Course 1 lesson 3, then soft
prompt, with guest progress migrating intact on signup (losing it here is a silent
retention killer). **P2.C4 Song library UX** (§9.4) including the "playable by you now"
smart filter driven by the skill graph. **P2.C5 Downloads and offline** (§18): wifi-only
default, next-N prefetch, storage management UI, resumable downloads, checksum verification,
graceful handling of a pack that fails to verify. **P2.C6 Sync client:** Room outbox →
WorkManager with backoff, conflict handling, and a visible sync state. *Expectation:*
airplane mode for a week, play daily, then reconnect — everything lands, nothing duplicates,
nothing regresses. **P2.C7 Gamification** (§10): streaks with timezone-correct rollover and
freezes, XP/levels, unlock gating, weekly goal ring. *Expectation:* streak logic unit-tested
against timezone changes, DST, and travel across the date line — this is where naive
implementations break and users get furious about a lost 90-day streak.
**P2.C8 Workouts v1** with the offline fallback generator. **P2.C9 Touch courses** as a
first-class track, not a debug mode. **P2.C10 Settings** (§12.4) including the
colorblind-safe palette and reduced motion. **P2.C11 Support surface** (§12.5).
**P2.C12 Theory cards and video steps** rendered from JSON templates.

#### Track D — Content at volume

**P2.D1 Courses 1–4 on both tracks** authored and reviewed. **P2.D2 60–100 songs** through
the full intake → edit → rights → publish path, using the CMS as it lands (the content team
is the CMS's first and most demanding user; their complaints in weeks 4–8 are the real
requirements). **P2.D3 Batch stem rendering** on the DGX rig. **P2.D4 Content QA pass:**
every chunk played by a human on a real instrument before publish. **P2.D5 Throughput
metric:** songs published per week, tracked from week 1; *expectation:* ≥ 8/week sustained
by the end of the phase, which is the number that makes a monthly release calendar credible.

#### Track E — Infrastructure

**P2.E1 Environments:** staging mirroring prod, ephemeral PR environments for the API.
**P2.E2 Dragonfly topology:** primary + replica, `--maxmemory` sized against measured
working set, snapshot schedule, and the pinned version from §13.5. **P2.E3 MySQL:**
backups with a **verified restore drill** (an untested backup is a rumor), slow-query log,
sane pool sizing for long-lived workers. **P2.E4 Object store + CDN:** cache headers,
signed URLs, purge automation, cost monitoring. **P2.E5 Secrets management**, no
credentials in the repo. **P2.E6 CI extension:** integration tests, k6 nightly, and the
Dragonfly command-surface test in the required set.

**Phase-2 gate:**

| Criterion | Threshold |
|---|---|
| End-to-end: install → onboard → play → sync → resume on a second device | Works, demoed |
| Magic-moment time (first launch → first detected note), p50 | < 90 s |
| Offline week test: no data loss, no duplicates, no star regression | Pass |
| Songs published/week by content team, sustained | ≥ 8 |
| Published catalog | ≥ 60 songs, 4 courses × 2 tracks |
| API p99 under the §13.6 load profile | < 120 ms |
| Worker longevity suite + Dragonfly surface test | Green |
| Authorization matrix (cross-profile access attempts) | 100% denied |
| Crash-free users on internal track | ≥ 99.5% |
| CMS: intake → publish by a non-engineer, unaided | Demonstrated |

**Explicitly not built in Phase 2:** billing, the paywall, store submission assets.

---

### PHASE 3 — Monetization and Hardening (6–8 weeks)

**Objective:** make it sellable, safe, fast, and operable. This phase is where the product
stops being a demo that works and becomes a service that survives real users, refund
edge cases, and Play policy review.

#### P3.1 — Play Billing client integration

**P3.1.1 `BillingClient` lifecycle**, connection retry, and product/offer querying.
**P3.1.2 Subscription products:** `premium_yearly` (anchor, 7-day trial), quarterly, monthly,
with base plans and offers; trial eligibility read from offer tags, never assumed.
**P3.1.3 Purchase flow** including the pending-purchase state (cash/carrier billing can take
hours or days — a client that assumes purchases are instant will show a paying customer a
paywall). **P3.1.4 Acknowledgement within the 3-day window**, or Play auto-refunds.
**P3.1.5 Restore purchases** and account-hold recovery deep link. **P3.1.6 Proration**
between plans. *Expectation:* every path exercised against Play's license-tester accounts,
including cancel, refund, upgrade, downgrade, resubscribe, and payment decline.

#### P3.2 — Server-side verification and entitlement state machine

**P3.2.1 `POST /billing/play/verify`:** purchase token → Play Developer API → entitlement
row → acknowledge. Never trust a client claim of premium, ever, anywhere.
**P3.2.2 RTDN via Pub/Sub** into the `rtdn-consumer` process (§13.4.1), single-instance
locked. **P3.2.3 State machine** `active → grace → hold → expired` (+ paused, revoked,
refunded) as a pure, exhaustively-tested function of (current state, event).
**P3.2.4 Idempotency and ordering:** RTDN delivers at-least-once and out of order.
*Expectation:* property tests that shuffle and duplicate every notification sequence and
always converge to the correct state — because in production they *will* arrive shuffled
and duplicated. **P3.2.5 Reconciliation job:** nightly sweep comparing local entitlements
against the Play API for anything stale, catching missed notifications.
**P3.2.6 Offline grace** (7 days) enforced consistently client and server.
**P3.2.7 Alerting:** RTDN lag > 5 min, verification failure rate, entitlement mismatches
found by reconciliation.

#### P3.3 — Paywall and pricing surfaces

**P3.3.1 Placements:** post-onboarding, end of free Course 1, locked songs.
**P3.3.2 Transparent copy** — price, trial length, renewal date, cancel path, all visible
before purchase, and the trial end date stated as an actual date. This is both Play policy
and the specific thing Simply Piano gets review-bombed over; being clearly better here is
cheap and compounding. **P3.3.3 Remote-configurable placement and copy** for later
experimentation. **P3.3.4 Manage-subscription deep link** into Play, easy to find, not buried.
*Expectation:* a hostile read-through by someone outside the team finds nothing misleading;
trial-to-paid and cancel flows both tested on real accounts.

#### P3.4 — Kids safety and compliance pass (§16)

**P3.4.1 Neutral age screen** and the mixed-audience declaration. **P3.4.2 Kid-profile
restrictions** enforced server-side as well as client-side: no behavioral analytics, no
personalization, built-in avatars only, no free text. **P3.4.3 Parental PIN gate** on
billing and account settings. **P3.4.4 No external links** on any kid-reachable surface;
FAQ and support rendered in-app. **P3.4.5 Data-minimization audit:** confirm audio never
leaves the device (grep the codebase, then verify with a network capture during a full
lesson — say it in the privacy policy only after you have proven it).
**P3.4.6 Pre-review checklist** against the Play Families policy, item by item, with
evidence per item. *Expectation:* a completed checklist document that a reviewer's
objection can be answered from.

#### P3.5 — Legal, privacy, and store readiness

**P3.5.1 Privacy policy and ToS** published on the web, reviewed by counsel.
**P3.5.2 Play Data Safety form** matching actual observed behavior, not aspiration.
**P3.5.3 Permission rationale strings** for the mic, in-context and honest.
**P3.5.4 Account deletion** (in-app and web, as Play requires), including the data-retention
statement. **P3.5.5 Rights audit:** every published song has complete provenance (§14.4);
*expectation:* zero songs publishable without it, enforced in code, spot-checked by hand.

#### P3.6 — Accessibility pass

**P3.6.1 TalkBack labels** on every screen outside the player; **P3.6.2** a documented,
honest statement about player accessibility limits and what the alternatives are;
**P3.6.3 font scaling** to 200% on non-player screens without clipping;
**P3.6.4 colorblind-safe feedback palette** validated with a simulator for all three common
types; **P3.6.5 touch target sizes** ≥ 48 dp; **P3.6.6 reduced motion** honored app-wide,
including the combo FX. *Expectation:* an accessibility scanner run is clean on non-player
screens, and a TalkBack user can navigate from launch to starting a lesson.

#### P3.7 — Detection test bench in CI (§19.2)

**P3.7.1 Bench job** scoring every model/DSP change against the full corpus by device class.
**P3.7.2 Regression gate:** no change ships if note F1 drops > 0.5% on any device class.
**P3.7.3 Reporting:** per-PR comparison table posted to the PR.
**P3.7.4 Corpus growth loop:** beta opt-in audio snippets (adults only, explicit consent)
flowing into the corpus with a labeling workflow. **P3.7.5 Field telemetry triage:** weekly
review of detection-confidence distributions by device model → remote-config adjustments.
*Expectation:* the bench runs unattended, nightly plus on every engine PR, and the team
trusts it enough to let it block a merge.

#### P3.8 — Performance, battery, thermal

**P3.8.1 Battery budget:** < 15%/hour during an active lesson on the mid-tier device,
measured with Battery Historian over three 30-minute sessions.
**P3.8.2 Thermal:** 30 minutes of continuous play with no throttling-induced frame drops;
if throttling occurs, an automatic quality-reduction path (lower model hop rate, simpler FX)
that degrades gracefully rather than stuttering. **P3.8.3 Cold start** < 2 s to interactive
on the low-end device. **P3.8.4 APK/AAB size** budget with the model quantized and stems
streamed rather than bundled. **P3.8.5 Memory:** no OOM on a 2 GB device with a large
SongPack loaded. **P3.8.6 Perf regression gates in CI** on startup time and player frame
timing, so this doesn't silently rot after launch.

#### P3.9 — Backend hardening and operational drills

**P3.9.1 Rate limiting** per account/IP/route via the Dragonfly token bucket, with
abuse-pattern alerts. **P3.9.2 Load test at 3× projected launch peak**, sustained 30
minutes; *expectation:* the §13.6 thresholds hold, worker RSS is flat, no queue backlog
growth. **P3.9.3 Chaos drills, actually executed, each written up:** kill a worker mid-request;
kill Dragonfly (expect degraded-but-serving, per §13.5); kill MySQL primary; fill the disk;
saturate the queue. **P3.9.4 Dragonfly failover drill:** promote the replica, flip the
endpoint, measure the window and what was lost. *Expectation:* a runbook with real
timings, rehearsed by someone who is not the person who built it.
**P3.9.5 Backup restore drill** for MySQL, timed, into a scratch environment.
**P3.9.6 Zero-downtime deploy verification** under load (`reload`, not restart).
**P3.9.7 Security review:** dependency audit, secrets scan, an authz fuzz pass over every
profile-scoped route, TLS/cert-pinning check, and a review of the signed-URL scheme.

#### P3.10 — Closed beta

**P3.10.1 Play internal track** → **P3.10.2 closed track, 200–500 users** recruited across
device classes and skill levels. **P3.10.3 In-app "did we hear you right?" button** with
opt-in snippet capture. **P3.10.4 Weekly triage** of crashes, detection complaints, and
funnel drop-offs, with a published changelog to beta users (they stay engaged when they see
their reports land). **P3.10.5 Instrument the full funnel:** install → onboard → magic
moment → D1 → D7 → trial start → trial convert.

**Phase-3 gate:**

| Criterion | Threshold |
|---|---|
| Billing: all lifecycle paths verified incl. pending, refund, hold | Pass |
| RTDN convergence property tests (shuffled + duplicated) | Pass |
| Play Families pre-review checklist | 100% with evidence |
| Battery in lesson, mid-tier | < 15%/hr |
| Thermal over 30 min continuous | No throttle-induced drops |
| Detection bench in CI, blocking | Live |
| Load test at 3× peak | Thresholds hold |
| Failover + restore drills | Executed and documented |
| Beta crash-free users | ≥ 99.5% |
| Beta D7 retention | ≥ 25% (set the real bar from beta data; below ~20% do not launch — fix retention first) |
| Trial start rate among eligible | Measured, with a stated launch expectation |

---

### PHASE 4 — Launch (4 weeks)

**Objective:** get to 100% availability with the ability to see problems and undo them.
A launch is an operations exercise, not an event.

#### P4.1 — Open beta (week 1)

Promote to open testing; watch install → magic-moment conversion at real-world device
diversity (the long tail of cheap OEM devices only shows up here, and it is where audio
weirdness lives). *Expectation:* magic-moment success ≥ 85% across all devices with ≥ 20
installs; any device model below 60% gets a remote-config profile or a documented
limitation before P4.3.

#### P4.2 — Store listing and ASO (week 1–2)

**P4.2.1 Listing assets:** screenshots showing the player in action, a 30-second video of
real playing with real feedback, honest feature list. **P4.2.2 Keyword research** and
localized listings for the launch locale set. **P4.2.3 Pre-registration** if the calendar
supports it. **P4.2.4 Review-response templates** and an owner, because the first week's
reviews set the rating that determines the next year's install rate.

#### P4.3 — Staged production rollout (week 2–3)

**P4.3.1 5% → 20% → 50% → 100%**, minimum 48 hours at each stage, with explicit promotion
criteria checked at each: crash-free ≥ 99.5%, ANR under threshold, API error rate < 0.5%,
RTDN lag normal, no rating slide, magic-moment rate holding.
**P4.3.2 Halt criteria written in advance and delegated** — whoever is on call can stop a
rollout without a meeting. **P4.3.3 Kill switches rehearsed** for the mic model, wait mode,
and the paywall placement. **P4.3.4 Backend capacity** provisioned for the 100% number
before the 50% stage, not during it.

#### P4.4 — Support and on-call (week 2)

**P4.4.1 Runbooks:** billing dispute, entitlement mismatch, RTDN backlog, "the app doesn't
hear me" triage tree (device → route → calibration → model config), lost-progress recovery,
lost-streak goodwill policy. **P4.4.2 On-call rotation** with a real escalation path and
paging thresholds. **P4.4.3 Support tooling:** an admin view to look up an account, see
entitlement and sync state, and grant a comp. **P4.4.4 Refund policy** written and staffed.
*Expectation:* every runbook has been walked through once by someone who did not write it.

#### P4.5 — Launch monitoring (continuous)

A single dashboard the whole team watches: installs, magic-moment rate, D1/D7, crash-free,
API p99, error rate, queue and outbox depth, RTDN lag, trial starts, conversions, refunds,
store rating. *Expectation:* one screen answers "is the launch healthy?" without anyone
running a query, and rollback criteria are written on it.

#### P4.6 — Post-launch reviews (week 4 and week 8)

Funnel review against Phase-3 expectations, detection telemetry by device model → the first
post-launch model/config iteration, content performance (which songs get played, which
lessons lose people), and a written retro feeding the next quarter's roadmap.

**Definition of "launched":** 100% rollout, on-call staffed, ≥ 150 songs live, crash-free
≥ 99.5%, billing reconciliation clean for 14 consecutive days, and the content team shipping
new songs weekly without engineering involvement.

---

### 20.5 Cross-phase dependency map (what blocks what)

- **Ground-truth corpus (P0.7)** → model bake-off (P0.3) → detection bench (P3.7). Late
  corpus automation delays two later phases; it is the highest-leverage item in Phase 0.
- **SongPack v1 freeze (P1.1)** → pipeline (P1.2), player (P1.6), CMS editor (P2.B3),
  catalog API (P2.A4), all content (P1.10, P2.D). Freezing it late stalls four tracks.
- **Room schema with sync fields (P1.9.1)** → progress sync (P2.A5, P2.C6). Omitting the
  fields in Phase 1 turns Phase 2 into a data migration.
- **OpenAPI spec (Phase 2, week 1)** → app and CMS parallel development. Written after the
  endpoints, it is documentation; written before, it is the thing that lets three tracks run
  at once.
- **Entitlement model (P2.A4)** → billing (P3.1–P3.2). Retrofitting entitlement checks after
  billing exists means auditing every route twice.
- **Content throughput (P2.D5)** → the ≥ 150-song launch bar. At 8 songs/week it clears; at
  4 it does not, and the fix is CMS investment in Phase 2, not overtime in Phase 4.

### 20.6 Cut lines (take these in order if behind schedule)

1. Staff-notation skin → note-bar only at launch (staff in the first post-launch update).
2. Chords track → Soloist track only.
3. Multi-profile → single profile; keep the account-level schema so it's additive later.
4. Placement test → everyone starts at Course 1 with a skip-ahead button.
5. Touch courses → post-launch.
6. Launch song library 150 → 80, with a published monthly release calendar.
7. Video lessons → post-launch.

**Never cut:** detection accuracy work, latency calibration, the scoring engine's fairness,
kids-safety compliance, server-side purchase verification, or the backup/restore drill.
These are the ones that are either impossible or extremely expensive to add after launch.

**Post-launch backlog:** more courses/songs monthly (the CMS makes this ops, not eng),
iOS port (C++ engine reuse), Chromebook/large-screen, practice reminders with ML-timed
notifications, duet/family challenges, sight-reading infinite mode, acoustic-echo-cancellation
research (§5.6), CQT-based model v2, and a live-features experiment over Workerman's
WebSocket support (the runtime is already there — see §13.4.1).

---

## 21. Team

### 21.1 Full-team shape (the "we have budget" version)

| Role | Count | Notes |
|---|---|---|
| Android engineers | 2 | one owns lesson player/UI, one owns app platform |
| Audio/DSP-ML engineer | 1 | engine, model eval/fine-tune, test bench — see §21.3 before assuming this is a full-time hire |
| Backend/PHP engineer | 1 | Webman API, billing, pipeline glue (existing team strength) |
| Frontend (Vue) engineer | 1 | CMS (can be part-time after Phase 2) |
| Music educator / content lead | 1 | curriculum, song arrangements, review — **the one role AI cannot replace** |
| Designer (UX + motion) | 1 | player feel, gamification juice, kids-safe UI |
| QA (incl. device lab) | 1 | detection bench ownership |
| PM / content ops | 1 | owns catalog growth, PD verification, commissions |

~6–8 months to launch with this team; solo/duo teams should expect 12–18 months and
should cut Phase-2 scope (single profile, one course track, 30 songs) for a first release.

### 21.2 Where AI assistance actually changes the staffing math

Honest per-workstream assessment. "Leverage" is how much of the work a strong generalist
plus heavy AI assistance can absorb — not a claim that AI does it unattended.

| Workstream | AI leverage | What AI does well here | What still needs a human, and why |
|---|---|---|---|
| C++ audio plumbing (Oboe streams, ring buffers, JNI bridge, threading) | **Very high** | This is well-documented, pattern-heavy systems code with public reference implementations. AI writes it accurately and explains the real-time-safety rules (no allocation/locking in the callback) correctly. | Running it on real devices. Reading a Perfetto trace and knowing which 3 ms matters. |
| Classic DSP (YIN/pYIN, spectral-flux onset, STFT/CQT features) | **Very high** | These are textbook algorithms with published pseudocode. AI implements them correctly and tunes parameters against a harness. | Knowing that the harness is lying to you (§P0.3.1 exists for this reason). |
| ML *integration* — TFLite conversion, quantization, delegate selection, decoder tuning | **High** | Conversion scripts, benchmark harnesses, parameter sweeps, per-device matrices — all mechanical and AI-fast. | Interpreting failures: octave errors vs. sustain-pedal smearing vs. mic AGC destroying the signal look identical in an F1 score and have different fixes. |
| ML *research* — new architecture, training from scratch, fine-tuning on a custom corpus | **Low–medium** | Boilerplate training loops, data pipelines, eval code. | Judgment about when the model is the problem vs. the data vs. the eval; GPU budget; knowing when to stop. **This is the real specialist work.** |
| Kotlin/Compose app, custom Canvas renderer | **High** | Large volumes of idiomatic UI code, state machines, tests. | "Does the scroll feel right" — a perception judgment no benchmark captures. |
| Webman/PHP backend | **Very high** | Endpoints, migrations, auth, validation, tests, OpenAPI. Webman's long-running-process pitfalls (§13.4.2) are well-understood and AI applies the rules consistently once told. | Deciding the coroutine posture and reading a load-test result honestly. |
| Vue CMS | **Very high** | Internal tooling with no design constraint is close to ideal AI work. | What the content team actually needs — learned by watching them, not by asking. |
| Content pipeline (Python, music21, ffmpeg) | **Very high** | Format wrangling, validation rules, batch tooling. | Musical judgment on chunk boundaries and arrangement quality. |
| Test infrastructure of every kind | **Very high** | Harnesses, fixtures, property tests, CI config. AI is disproportionately good here, and this project's whole risk model rests on test benches. | Deciding what to measure. |
| Curriculum and arrangements | **Low** | Drafting, formatting, consistency checks across lesson metadata. | Pedagogy and musicianship. A wrong-but-confident lesson sequence teaches bad habits to a child, and no eval catches it. |
| Compliance (COPPA/Families/Play policy), legal | **Medium** | Checklists, policy drafts, gap analysis. | Counsel signs off; a hallucinated policy reading is a launch blocker. |
| Device/hardware measurement, real-room testing | **None** | — | Somebody has to hold the phone next to an out-of-tune upright. |

### 21.3 So: do you need the audio/DSP-ML engineer?

**Short answer: probably not as a full-time hire — if you commit to shipping an
off-the-shelf pre-trained model and never training your own.**

That commitment is what changes the role from research to integration, and integration is
squarely in the "very high AI leverage" band above. Concretely:

- **The plan is already designed around not training a model.** Basic Pitch and
  Onsets-and-Frames are pre-trained, permissively licensed, and export to TFLite. Phase 0
  is a *bake-off*, not a research project. If one of them clears the P0.9 gate, there is no
  research work left in this product — only measurement, integration, and tuning.
- **The genuinely hard parts of the audio work are engineering, not ML:** real-time-safe
  C++, Oboe stream negotiation across OEMs, latency decomposition, echo handling, thermal
  behavior. A strong generalist with AI assistance can do all of it, slower than a
  specialist but not qualitatively worse — because the feedback loop is measurable.
- **What protects you is the test bench, not the résumé.** AI-assisted DSP work is safe
  exactly to the degree that your eval harness is honest. That is why P0.3.1 (build and
  *validate* the harness before touching a model) and P3.7 (the CI bench with a blocking
  regression gate) are load-bearing steps, not nice-to-haves. With them, a generalist
  iterating with AI converges. Without them, a specialist would also be guessing — they'd
  just guess with better intuition.

**What to do instead of the full-time hire:**

1. **Assign one engineer as the audio owner, full time, AI-assisted.** Not a rotating
   responsibility — the device-behavior knowledge compounds in one head.
2. **Buy 20–40 hours of a consulting DSP/ML expert**, spread across three touchpoints:
   (a) review the P0.3.1 eval harness *before* it produces numbers anyone trusts —
   this is the single highest-value hour of the engagement; (b) sit in on the P0.9 gate
   review and challenge the conclusions; (c) be on call for one or two "we're stuck and
   don't know why" sessions in Phase 1. Budget roughly $8–20k. This buys most of the
   specialist's value — pattern recognition on failure modes — at a few percent of a salary.
3. **Set an explicit hiring trigger.** Hire the specialist if and only if one of these
   fires: the P0.9 detection gate fails and the chosen path is (a) fine-tune; or field
   telemetry after beta shows a device class stuck below the accuracy bar that config
   changes cannot fix; or you decide to build the v2 CQT model in §20.6's post-launch list.
   Until one fires, the money is better spent on content.

**The role you should not try to AI your way past is the music educator.** Detection errors
are visible, measurable, and fixable. Bad pedagogy is invisible, produces confidently
plausible lesson plans, and its failure mode is a beginner quietly concluding they have no
talent. Hire or contract that one first, in Phase 0 (P0.8.3), before any curriculum exists.

### 21.4 Realistic small-team shape with heavy AI assistance

| Role | Count | Covers |
|---|---|---|
| Generalist engineer — audio owner | 1 | C++ engine, model integration, detection bench, latency |
| Generalist engineer — app | 1 | Kotlin/Compose, player, offline, billing client |
| Generalist engineer — services | 1 | Webman API, CMS, content pipeline, infra |
| Music educator / content lead | 1 | curriculum, arrangements, content QA (part-time in Phase 0–1, full-time from Phase 2) |
| Designer | 0.5 | contract for the player and onboarding; the rest is a design system AI can apply |
| Consulting DSP/ML expert | ~40 h total | harness review, gate review, escalations |
| Legal/compliance | as needed | rights policy, privacy, Families review |

Three engineers instead of six, on roughly a 9–12 month timeline rather than 6–8 —
with the §20.6 cut lines taken early rather than late. The constraint that does not
compress is content: 150 songs and four courses is human musician-hours, and that is the
real reason the educator is the first hire, not the last.

## 22. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **Catalog appeal without licensed hits** — royalty-free-only rule (§8.5) means no current pop songs, part of Simply Piano's draw | Weaker "songs you love" hook for some users | Lean into PD beginner favorites (Für Elise, Canon in D, Ode to Joy…); commission modern-style originals; market on skills + timeless classics; revisit licensing only if retention data demands it |
| Accidental rights slip (arranging a non-PD work, missed CC-BY attribution) | Takedown / legal exposure | CMS rights-provenance fields mandatory before publish (§14.4); PD territory check; hard "no style-alike covers" rule (§8.5) |
| Polyphonic detection underperforms on cheap devices / out-of-tune pianos | Core UX broken for a user segment | Phase-0 gate; MIDI-first messaging for keyboard owners; monophonic fallback for early lessons; device-class remote config; field telemetry loop |
| Audio latency variance across Android OEMs | Scoring feels unfair | Auto-cal per route (§5.5), manual cal, generous beginner windows, BT-output backing-track shift |
| Echo: speaker backing track pollutes mic | False positives | Duck + piano-free backing stems in mic mode (§5.6) |
| Battery/thermal in long sessions | Churn, bad reviews | Onset-gated model inference, 5-min-session product design, perf budget in CI |
| Play Families policy rejection | Launch blocked | Compliance pass as explicit Phase-3 work with pre-review checklist; no ads/no external links from day 1 |
| Subscription refund/renewal edge cases | Revenue leakage, support load | RTDN state machine + property tests; entitlement grace design |
| Content authoring bottleneck | Library grows too slowly | CMS editor investment (§14.2) + auto-chunking pipeline; measure songs/week as a KPI from Phase 2 week 1 (§20 P2.D5) |
| **Webman long-running-process state bugs** (leaked request data, growing memory, re-registered listeners) | Intermittent wrong-user data — the worst class of bug there is | §13.4.2 rules enforced by CI greps; the worker-longevity + state-bleed suite (§13.7) written in Phase 0 against *deliberately* broken code (P0.6.2) so the team has seen the failure; memory-ceiling auto-restart via `monitor` |
| **Dragonfly diverges from Redis** on a command or semantic we depend on | Broken cache/queue path, possibly only under load | Command-surface test in CI against a pinned image (§13.5); the documented fence (no Redis Functions, no client-side-caching invalidation, no Sentinel); nothing in Dragonfly is a system of record (§13.4.5), so worst case is degraded, not lost; single-DSN escape hatch back to Redis for any one workload |
| No Sentinel / manual Dragonfly failover | Cache outage window | Replica + scripted promotion, drilled in Phase 3 (P3.9.4); the API degrades to MySQL-direct rather than erroring (P0.6.6 proves it) |
| **Over-reliance on AI assistance in the DSP/ML work** | Confident-sounding code that is subtly wrong, discovered late | Eval harness validated against a published benchmark result *before* it is trusted (P0.3.1); blocking CI regression gate (P3.7.2); 20–40 h of consulting specialist review at the harness and gate milestones (§21.3); explicit hiring trigger if the gate fails |
| Educator hired late or curriculum AI-drafted | Bad pedagogy — invisible failure, blames the learner | Educator contracted in Phase 0 (P0.8.3); every chunk played by a human before publish (P2.D4) |

## 23. Key References

- Oboe (low-latency Android audio): https://github.com/google/oboe
- Magenta Onsets and Frames (+ TFLite mobile build): https://magenta.tensorflow.org/onsets-frames
- Spotify Basic Pitch (lightweight polyphonic AMT): https://github.com/spotify/basic-pitch
- MAESTRO dataset (piano training data): https://magenta.tensorflow.org/datasets/maestro
- Verovio (MusicXML/MEI engraving, C++/JS): https://www.verovio.org/
- alphaTab (notation rendering, Android canvas support): https://github.com/coderline/alphaTab
- Bravura SMuFL font: https://github.com/steinbergmedia/bravura
- IMSLP (public-domain scores): https://imslp.org/
- Mutopia Project (PD/CC scores in source form): https://www.mutopiaproject.org/
- Salamander Grand Piano (CC-BY piano sample set): https://sfzinstruments.github.io/pianos/salamander/
- Android MIDI API: https://developer.android.com/reference/android/media/midi/package-summary
- Play Billing subscriptions + RTDN: https://developer.android.com/google/play/billing
- Play Families policy: https://support.google.com/googleplay/android-developer/answer/9893335
- Webman manual (framework, session, processes, plugins): https://webman.workerman.net/doc/en/
- Webman Redis Queue plugin: https://webman.workerman.net/doc/en/queue/redis.html
- Webman coroutines (Fiber/Swoole/Swow drivers): https://webman.workerman.net/doc/en/coroutine/coroutine.html
- Workerman 5 manual: https://manual.workerman.net/doc/en/
- Dragonfly docs: https://www.dragonflydb.io/docs
- Dragonfly Redis command compatibility matrix (the fence in §13.5 comes from this):
  https://www.dragonflydb.io/docs/command-reference/compatibility
- Redis→Dragonfly migration notes: https://oneuptime.com/blog/post/2026-03-31-redis-migrate-redis-to-dragonflydb/view
- Simply Piano feature behavior (reviews used for parity research):
  https://pianistscompass.org/reviews/apps/simply-piano/ ·
  https://www.musicradar.com/reviews/simply-piano-review ·
  https://piano-help.hellosimply.com/en/collections/1512479-simply-piano-note-recognition-and-midi-help

---

## 24. Build Status Log

> Maintained by the build orchestrator as implementation proceeds. Each entry records what shipped, the commits, verification evidence, and environment state. This section is the spec-of-record companion to the working tree.
> **LATEST (2026-08-25, evening):** P1.6 lesson player — architecture designed + screenshot-test toolchain research done (Paparazzi 2.0.0-alpha05 chosen); NO commits landed this session (write-agent dispatch tooling failed on long prompts — see the evening entry below; next session: dispatch in small steps). Tree: HEAD 323383a + one untracked file to delete (android/scoring/src/main/kotlin/com/keyquest/scoring/Touchstone.kt). Next work: P1.6 implementation.

### 2026-08-25 (evening) — P1.6 lesson player: design + toolchain research complete (implementation start; no commits)

**Commits:** none. HEAD 323383a unchanged. Working tree: ONE untracked file — `android/scoring/src/main/kotlin/com/keyquest/scoring/Touchstone.kt` (agent-sanity file; DELETE at session start; compiles harmlessly but must not ship).

**What landed (design + research only):**
- **Real-time scoring design (P1.6.5/8/9):** new `RealtimeScorer` in `:scoring` — an incremental driver over the batch `Scorer` with a provable freeze rule: note k's verdict depends only on notes 0..k and events with onTimeNs <= closeSeconds(k); freeze prefix [0..k] only when sessionSeconds > max(closeSeconds[0..k]) (running max, NOT per-note close — covers the tempo-change inversion: step 60->240 bpm at beat 8 makes note N(beat 8.01, close ~8.09s) close BEFORE M(beat 7.9, close ~8.26s); a wrong-pitch event at 8.05s would freeze N WRONG under naive per-note close, while the running-max rule gives N MISSED exactly like batch). Tentative verdicts = full batch given events so far (display-only, may evolve); frozen verdicts never flip; finalize() == Scorer.score(expected, allEvents) EXACTLY. API: onEvent(PlayedNote): Snapshot / tick(sessionSeconds): Snapshot / finalize(): ScoreReport / snapshot(); Snapshot(frozenVerdicts, tentativeVerdicts, matchedPitches, deviationMs, frozenCount, tentativeScore, frozenScore, tentativeStars), per canonical index (ChordClusterer.COMPARATOR order — align arrays to Scorer outcomes). Regression test spec includes the tempo-inversion case; property suite 200 seeds x 8 scenarios (ScorerPropertyTest style): finalize==batch deep-equal, frozen-never-flip, monotone frozenCount/frozenScore, no NaN, same-seed determinism.
- **Lesson player architecture:** pure-Kotlin `LessonSession` in the app module (frame-driven session clock; transport play/pause/loop; chunk-end -> FINISHED -> results overlay; retry = reset, next = chunk ord+1) + SongPack model/loader (org.json; testImplementation org.json:json for JVM) + `LayoutHintDeriver` (fixtures carry no layout hints: lane = pitch-rank within hand over 5 lanes, beamGroup = runs of <=0.5-beat notes per staff, xHint = startBeat*1000) + ProtoScore adapter (rebased beats for the renderer; absolute beats for scoring). Renderer gains per-frame feedback state (verdict + hit timestamp for pop; zero-alloc; no layout rebuild). `OnScreenKeyboard` (Canvas + multi-touch pointerInput -> `NoteEvent(source=TOUCH)` -> RealtimeScorer; layout math JVM-testable; target glow ~1 beat lead; live pressed keys; wrong-key flash + expected-key pulse). `NoteVoice` seam (SilentVoice stub now; soundfont with P1.8 fluidsynth provisioning). Results overlay: stars/score/per-measure heatmap/retry/next. Transport: tempo display (change control is P1.8.4), pause, loop, progress, skin toggle mid-lesson. Reduced-motion setting (color-only, no scale/translate). App gains `implementation(project(":scoring"))`; bundle `content/fixtures/songpack-v1/pickup_anacrusis` as an Android asset for the on-device demo.
- **P1.6 cuts (documented):** tempo-change control (P1.8.4), Wait-for-Me (P1.7), count-in metronome (P1.8.2), soundfont voice (P1.8 provisioning), finger badges render only when schema `finger` is present (pipeline v0 emits none), in-lesson score readout (results screen only).
- **Screenshot-test toolchain (P1.6 expectation):** **Paparazzi 2.0.0-alpha05** (2026-05-20) — only release line supporting the pinned toolchain (AGP 8.9.1, Gradle 8.11.1, Kotlin 2.2.0 + org.jetbrains.kotlin.plugin.compose, Java 21); headless JVM on ubuntu-latest; tests in the normal `test` source set; goldens in `src/test/snapshots/`; tasks `recordPaparazziDebug`/`verifyPaparazziDebug`; default maxPercentDifference 0.01; plugin auto-adds the paparazzi test dependency. Gotchas: open issue #2342 (NoSuchMethodError Thread.setPosixNicenessInternal when code under test starts a HandlerThread — our static UI does not; workaround documented in the issue), cross-OS golden AA differences -> record goldens on Linux with the SAME JDK 21 as CI, goldens as PNGs. Fallback: Robolectric 4.16.1 + Roborazzi 1.73.0. Sources: Maven Central metadata, paparazzi releases/CHANGELOG, official sample build, issues #2342/#311.

**Agent-dispatch lesson (read before dispatching anything):** write-capable agents (coder, general) return EMPTY results on long single-shot specs (~5-6 KB prompts): task reports "completed", zero files created. A ~600-char sanity task worked instantly. Long task() calls can also drop required JSON keys (description/prompt/subagent_type) mid-generation. **Rule: dispatch one small step per call (one file, prompt <= ~1500 chars), verify each result via ls/mtime, resume an empty-result task via task_id once, then re-dispatch smaller. Never one-shot a multi-file spec.**

**Next (in order):** 1) delete Touchstone.kt; 2) D1 RealtimeScorer — class file, then unit tests file, then property tests file, then `cd android && ./gradlew :scoring:check --no-daemon --stacktrace` (must pass incl. jacoco >= 0.95 LINE gate; small dispatches); 3) reviewer loop -> commit; 4) D2 SongPack model/loader + LayoutHintDeriver + ProtoScore adapter + LessonSession + ComboTracker + tests; 5) review -> commit; 6) D3 lesson player UI (transport, notation feedback, keyboard zone, touch input, results screen, voice seam, assets, MainActivity); 7) review -> commit; 8) D4 Paparazzi screenshots vs golden fixtures (both skins) + CI android-unit += :app:verifyPaparazziDebug; 9) review -> commit; 10) docs/ledger/§24/continuation + memory update; 11) push to origin/master.

### 2026-08-25 — P1.5 Scoring engine (pure-Kotlin :scoring module, zero deps, property suite, replay tool)
**Commits:** `d7e4702` (main; range 027b289..d7e4702). On master, pushed, remote CI green (5 jobs).
**What shipped:**
- New Gradle module `android/scoring/` — pure Kotlin, ZERO dependencies (stdlib only), package `com.keyquest.scoring`, kotlin-jvm 2.2.0, JVM target 17, JUnit 4; jacoco 0.8.12 LINE COVEREDRATIO ≥0.95 gate wired into `:scoring:check`. Spec: `docs/specs/scoring-v1.md` (window formula, verdicts, cluster rule, score/stars, telemetry, replay TSV format, determinism, open calibration questions).
- Matching (P1.5.1): tempo-scaled windows [t−120 ms, t+180 ms] at refBpm 120, scale = (refBpm/bpm).coerceIn(0.5, 2.0), beginner ±250 ms; PERFECT band 50 ms + 10% timing-bonus weight; verdicts PERFECT/GOOD/MISSED/WRONG — wrong-pitch-in-window = WRONG, never consumed; unconsumed events = extras.
- Chord clustering (P1.5.2): 90 ms (absolute ms, seconds-converted) → FULL/PARTIAL/MISSED outcomes with partial credit.
- Score math (P1.5.3): score = min(100, 100·Σw(1+bonus)/Σw), never NaN/>100; stars 60/80/95 as a `StarThresholds` parameter (remote-config tunable).
- Telemetry (P1.5.4): per-measure error heatmap derived from timeSignatures + pickupBeats (notes carry no measure field in SongPack).
- Property tests (P1.5.5): java.util.Random fixed seed, 200 seeds × 8 scenarios (base/early/late/extra/missing/wrongOctave/rolled/duplicates); invariants — no NaN, 0..100, deep-equal determinism, stars consistent, heatmap sums, unique matched indexes, verdict counts sum, extra-event-never-increases, TempoMap monotonicity; monotone-in-accuracy with documented ambiguity corner (overlapping same-pitch windows, bounded by bonus). Mutation-2 (fix wrong pitch) retargeted to UNCONSUMED wrong-pitch events after review — proven to execute (201 executions over 200 seeds).
- Replay tool (P1.5.6): SessionFormat + pure ReplayRunner + thin ReplayMain (injectable seam) + Gradle JavaExec `replay` task (not in `check`) — scoring changes can be argued with recorded sessions.
- Supporting math: TempoMap = faithful Kotlin port of pipeline audio.py beat_to_seconds (step/linear, log-integral, 1e-12 threshold); MeasureMapper mirrors songpack-v1 §2 pickup semantics (1-beat pickup + 8×4/4 → 33 beats; divergence from pipeline `_linearize` documented in MeasureMapper KDoc).
- CI: android-unit job now runs `./gradlew testDebugUnitTest :scoring:check`; lint-all gains a purity grep forbidding `^import (android|androidx)\.` in android/scoring/src (verified to fail on a planted android.util.Log import).
**Verified:** 118 tests / 0 failures (8 classes); LINE coverage 99.42% (2749/2765), INSTRUCTION 96.32%, BRANCH 92.76%; `:scoring:check` + `:scoring:jacocoTestCoverageVerification` green; `:app` testDebugUnitTest unaffected; make lint OK.
**Review:** APPROVED after 2 review passes + fix rounds. Round 1: APPROVE w/ 6 minors (M1 property-test guard hole, M2 empty-expected extras, M3 missing-scenario tail-drop, M4 dead else, M5 --stars fail-fast, M6 KDoc) — all fixed. Re-review: APPROVE w/ 1 residual (mutation-2 vacuous) — fixed + proven (201 executions). No blockers/majors at any point.
**Next:** P1.6 lesson player (plan §20 P1.6 — layout/transport, note-bar + staff skins, skin toggle, real-time feedback, combo/juice, on-screen keyboard, touch input path through the same scorer, results screen; screenshot tests vs golden SongPacks; ≥58 fps expectation measured when devices arrive).

### 2026-08-24 — P1.2 Pipeline CLI v0 (stage framework, deterministic builds, bad-input corpus)
**Commits:** `49265f0` (main) + `d1fa785` (CI: ffmpeg) → review fixes `8583208` (M1 per-note chord ties) → `0e60f14` (M2 nested repeats+voltas) → `d61a0da` (M3 MIDI delta) → `fdc40db` (minors m4–m11) → `0c0c312` (CI: pyyaml) → `106ad8b` (brute-force lock). All on master, pushed, remote CI green (5 jobs).
**What shipped:**
- Stage framework `pipeline/pipeline/build/` — pure `(artifact, config) -> (artifact, report)` stages: ingest (provenance required), validate (music21; every unsupported construct named-rejected), normalize (explicit repeat-expansion state machine — simple repeats + voltas + nested, D.S./D.C. rejected by name; grace→scoringWeight 0; ornaments expanded; ties→tieToIndex; idempotent), hands, chunking suggestions (never split a tie, no mid-beat start, loopSafe), difficulty (v0 = 1; calibrated scoring deferred), layout precompute, levels (single level), audio, pack, publish.
- Full §8.2 CLI surface (`ingest build audio validate diff publish batch` + `eval` preserved) with one-line stderr errors and NO stack traces; `--from-stage` exact-or-error + `--resume-nearest`; `--strict` wired; batch per-item isolation.
- Determinism: sorted JSON keys, fixed float formatting, zeroed zip stamps, Ogg serial canonicalization, `buildTimestamp` = `SOURCE_DATE_EPOCH` else fixed sentinel, `buildInfo` excluded from the content hash. **9/9 golden packs byte-identical** across independent builds (audio included) — CI double-build job added.
- Audio: deterministic sine renderer default (two-pass loudnorm −16 LUFS/−1 dBTP, Opus 48 kHz, mic-safe spectral check <−40 dB in 80 Hz–2 kHz measured on the encoded file, alignment ≤ 10 ms, tempo-map-integrated durations incl. linear curves); fluidsynth backend code-complete behind the same interface (subprocess + sha256-pinned soundfont) — needs provisioning (no sudo on server; CI does not depend on it).
- Bad-input corpus: 12 defect files (`pipeline/tests/bad/`), each → named actionable error; 9 awkward-case MusicXML fixtures; 112 pytest tests.
- v0 scope cuts documented in `docs/specs/pipeline-v0.md` (calibrated difficulty, L2/L3 generation, fingering, D.S./D.C., DGX, CDN publish).
**Verified:** pytest 112/112, make lint OK, determinism double-build 9/9 byte-identical, remote CI green on all commits.
**Review:** APPROVED after 1 pass + fix series (M1 per-note chord tie extraction; M2 volta ownership in nested repeats; M3 absolute-tick MIDI events; minors: tempo-integrated durations, strict wiring, atomic pack write, exact from-stage, eval boundary, shared chord beams, batch isolation; CI pyyaml; brute-force termination test).
**Next:** P1.5 scoring engine (pure Kotlin, zero Android deps, property tests, ≥95% coverage) — plan §6 + §20 P1.5.

### 2026-08-24 — P1.1 SongPack v1 frozen (spec + canonical schema + 3-consumer validators + golden fixtures + CI drift guard)
**Commits:** `9d1ebd5` (P1.1 main) → `7a82caf` (classloader-warning fix) → `cac560b` (review fixes: date-time enforcement, drift-guard exclusions, forward-compat tests) → `d74194d` (RFC3339 gate tightening). All on master, pushed, remote CI green on every commit (5 jobs).
**What shipped:**
- `docs/specs/songpack-v1.md` — the frozen Phase-1 content contract: every field/unit/enum, required/optional matrix, units-in-beats policy (seconds appear nowhere in note data), forward-compat rule (unknown keys ignored; `minAppVersion` gates new required behavior; additive-only; `songpack/v2` parallel; `packVersion` never reused; progress keys on songId+level+chunk), audio stem contract (§8.1.7), one-schema-three-consumers mechanism (§8.1.10).
- `content/schema/songpack-v1.json` — canonical draft-07 schema with `$defs`; root `oneOf` dispatcher over the four pack documents; `seconds` forbidden on note records via `not:{required:[seconds]}` (unknown keys still accepted = forward-compat); RFC 3339 `date-time` on `buildInfo.buildTimestamp`.
- Three consumers, one schema, no drift by construction: Python (`pipeline/pipeline/songpack/validator.py` — jsonschema + semantic checks: pickupBeats, tie index/pitch integrity, chunk bounds/count/prereqs, level cross-refs, NaN/Infinity rejected at parse, custom RFC3339 format checker matching opis/networknt; 28 tests), PHP (`api/tests/SongPack/SongPackSchemaTest.php` via opis/json-schema ^2.6 dev — 8 tests/56 assertions; reads the canonical file directly), Kotlin (`android/.../SongPackSchemaTest.kt` via networknt 1.5.6 test-only — Gradle Copy tasks generate test resources from `content/` into gitignored `build/generated/songpack`, so no committed copy can drift; 3 JVM tests).
- 5 golden fixtures (`content/fixtures/songpack-v1/`) covering all 6 awkward cases: pickup bars, mid-song key change (2 levels), 6/8 + triplets, ties across chunk boundaries, linearized A-B-A repeat + `repeatMap` + forward-compat unknown keys.
- CI: `lint-all` drift guard (sha256 canonical vs any other `songpack-v1.json`; excludes `build/`/`.gradle/`) + `engine-host-tests` runs the songpack pytest suite.
**Design decisions frozen (spec §6):** notes.json per-level shape `{"levels": {"1": [...], ...}}` (full note sets, never diffs); skills.json `{"levels": {"1": {"requiredSkills": [...], "taughtSkills": [...]}}}`; buildInfo required; repeats pre-expanded by the pipeline (format is linear; optional `repeatMap` annotation; `loopSafe` chunks).
**Verified:** pytest 30/30, phpunit 31/171, gradle 34 JVM tests, make lint OK.
**Review:** APPROVED after two passes (fixed: Python date-time enforcement gap [MAJOR], drift-guard build exclusions, ci.yml EOF newline, PHP+Kotlin forward-compat tests; residual RFC3339 leniency closed in `d74194d`).
**Next:** P1.2 pipeline CLI v0 (music21 ingest → deterministic pack, bad-input corpus) → P1.5 scoring engine.

### 2026-08-22 (late) — P0.5 renderer prototype + P0.3.3-partial + soak/drill + MySQL reconnect fix + P0.9 gate draft
**Commits:** `15af47f` + `d23985b` (P0.5) → `b7b44c8` + `e2ad357` + `7ac461b` (P0.3.3-partial) → `49c143b` (soak/drill results) → `840d566` + `47c92a0` (P0.9 gate draft + revision) → `43ee9a1` (MySQL reconnect fix).
**What shipped:**
- P0.5 code complete: Compose Canvas scrolling-notation prototype — NoteBar + Staff skins, Bravura 1.481 (OFL) hybrid glyphs, stress score 240 notes w/ ties, JankStats (metrics-performance 1.0.0), zero-alloc per-frame draw (review-fixed), 32 JVM tests. Device measurement (≥58fps on mid-ranger) pending hardware; P0.5.4 probe runbook written.
- P0.3.3-partial: engine YIN baseline CLI (yin_cli, WavReader promoted to engine_core) + EngineYinWrapper (F1=1.0 synthetic); OAF TFLite BLOCKED (no published artifact; tflite-runtime no cp312 wheels); Basic Pitch BLOCKED (TF pin). Review-fixed: SIGFPE guard, merge threshold, subprocess timeout, CI wrapper test.
- P0.6.3 soak + P0.6.4 drill COMPLETE: 23.4M req/1h @6510 rps, 0 errors/0 bleed/0 kB RSS growth/p99 stable; Dragonfly restart mid-consume no job loss. GATE DEFECT found: MySQL connection drop → 500 until ~50s heartbeat (§13.4.2 expectation NOT MET).
- MySQL reconnect FIXED (43ee9a1): ReconnectingDatabaseManager subclass + app support\Db shadow (PSR-4), DbReconnectTest regression, soak-driver pacing/RSS fixes. phpunit 23/113.
- P0.9 gate review DRAFT (docs/phase-gates/): GO-WITH-CUTS recommendation; 1 MET / 3 PARTIAL at draft; revised to 2 MET / 2 PARTIAL once the reconnect fix landed (43ee9a1); hardware-free Phase-1 tracks identified (SongPack v1, pipeline CLI v0, scoring engine).
**Environment:** unchanged (new server; host-network DB containers; docker compose still broken at daemon level).
**CI:** green on all pushed commits (remote runs verified via gh).
**Next:** device hardware (5 phones, DGX-520, MIDI keyboards) for P0.2.4/P0.3.4/P0.4/P0.7 + P0.5.3; rights vetting (P0.8); then Phase-1 hardware-free tracks (P1.1 SongPack spec, P1.2 pipeline CLI, P1.5 scoring engine) and FINAL P0.9 re-issue once hardware + rights land.

### 2026-08-22 (final) — P0.8 rights groundwork + cleanup
**Commits:** `5a7a834` (review-minor cleanup: reconnect doc note, test determinism, soak pacing, k6 thresholds) → `d415b0b` (P0.8 rights groundwork + stale US-PD wording fix) → `6652776` (final cleanup).
**What shipped:**
- P0.8 groundwork (research stage): `docs/pd-verification-checklist.md` (per-song PD clearance record template) + `content/rights/candidates-2026-08.md` (15 candidates, 14/15 primaries GLOBALLY PD — the ≥10 requirement is met at research level; legal sign-off P0.8.4 remains human). Ruling corrected: USA public domain = first published 1930 or earlier (plan §8.5.1 stale 'pre-1929' wording fixed).
- Final cleanup: soak.php rate<1 chunk-bounds, SoakDrill.md --pid doc, catalog-reads.js comment.
**CI:** green on all pushed commits.
**Status of the six NEXT-WORK items from the build brief:** 1 P0.2-A3 ✅ · 2 P0.2-B ✅ code/device-pending · 3 P0.6-C ✅ · 4 P0.5 ✅ code/device-pending · 5 P0.3 ✅ harness + partial bake-off (OAF/BasicPitch blocked) · 6 P0.9 ✅ DRAFT go-with-cuts + soak recorded + reconnect fixed + rights groundwork. Remaining blockers are EXTERNAL: hardware (5 phones, DGX-520, MIDI keyboards) for P0.2.4/P0.3.4/P0.4/P0.7/P0.5.3; legal sign-off for rights; 8h overnight idle reconnect test; P0.3.2 test-set assembly.

### 2026-08-22 — P0.2 (A3+B) + P0.6-C + P0.3.1: pitch harness green, Oboe/JNI wired, load baseline + ADR-0002, eval harness + critical fix
**Commits:** `00366b9` (P0.2-A3 harness) → `8f862e3` (A3 review fixes) → `84c0f2d` + `2eee95b` (P0.6-C) → `0c49086` (P0.6-C minors) → `4f33c20` (P0.3.1 harness) → `a1a30a2` (pitch-tolerance critical fix) → `8969787` (P0.2-B Oboe/JNI).
**What shipped:**
- P0.2-A3 complete: deterministic fixture generator + full A0–C8 pitch suite (88/88 correct, 0 octave errors, C8 edge check, boundary-honesty negatives, floor-pin MIDI 32–42). Finding: standard 2048 resolves F1/F#1 via extrapolation past the nominal 46.9 Hz boundary; reliable floor ~G#1; YinDetector.h contract corrected; detector logic untouched. Debug+Release ctest green, -Werror.
- P0.2-B code complete: OboeInput (exclusive LowLatency, ring-push-only callback, granted-property logging, drop counter), NoteEventQueue (lock-free SPSC), JNI bridge → Kotlin Flow, RECORD_AUDIO, NDK/prefab wiring (libkeyquest_engine.so, 4 ABIs, oboe 1.9.3). assembleDebug + testDebugUnitTest green; engine host tests green. Device-side verification (granted-mode table, soak, latency rig) pending the 5 test phones.
- P0.6-C complete: k6 load baseline (cached reads 31–35k req/s MET ~6x; auth writes 370 req/s NOT MET — MySQL commit-fsync bottleneck on /dev/md0; async-fsync toggle → 8.8k req/s), reload + Dragonfly failover drills (0 errors across 2M/1.8M requests, degraded-200 + auto-recovery ~5s), ADR-0002 coroutine posture (OFF for HTTP workers / ON for consumer outbound fan-out; WaitGroup+Channel deadlock → Workerman\Coroutine\Parallel). CacheGuard degradation + CacheDegradationTest (+3). phpunit 21/106.
- P0.3.1 complete: mir_eval harness + `pipeline eval` CLI + self-tests; critical pitch-tolerance fix (Hz→cents, 50¢ default) with +30¢ regression test; metric calibration proven vs mir_eval fixtures (1e-9); MAESTRO published-number comparison blocked (basic-pitch TF pin, no cp312; MAESTRO audio not individually downloadable) — validate_maestro.py re-runs when unblocked.
**Environment (new server):** Ubuntu 24.04.4, 64-core/251 GB. Fixed: cmake/cpack/ctest broken pip shims, NDK r27d + build-tools 35/36, k6 v2.2.0, MySQL/Dragonfly images pulled, pipeline venv = Python 3.12.12. **Docker bridge networking broken (root-only fix)** — DBs run via host-network containers; `docker compose up` unusable on this box.
**CI:** all commits pushed to origin/master; local make lint/test green on every commit (remote CI runs on push).
**Next:** P0.2-B review → P0.5 notation renderer → P0.3 bake-off (engine YIN CLI + OAF TFLite attempt; Basic Pitch blocked) → P0.9 gate review (needs soak drill, rights vetting, hardware).

### 2026-08-21 — P0.1 + P0.2 (partial) + P0.6 (partial): monorepo green, CI passing
**Commits:** `612e70f` (initial) → `246b2b2` (P0.1 scaffold) → `deaadbf` + `99538ee` (P0.6.1 Webman skeleton + review fixes) → `3ae0045` (engine core part 1) → `a16ca23` + `e78c657` (YIN core + review fixes) → `71574a3` + `a4b4fde` (P0.6.2-4 test suites + review fixes) → `4fe5a35` (Vite 6→8.2.2) → `d6253fe` → `1544284` → `3d8584a` (CI fixes) → `3c2847a` (provisioning).
**What shipped:**
- P0.1 complete: 7-workspace monorepo, toolchain pinning (toolchain.md + version catalogs), GitHub Actions CI (5 jobs: engine-host-tests, android-unit, api-tests, cms-build, lint-all), ADR practice (docs/adr/0001). License: proprietary (user decision).
- P0.2 partial: engine core — NoteEvent, lock-free SPSC RingBuffer, wav_util, YIN/pYIN monophonic detector in engine::dsp with boundary/DC/null guards. Host tests pass in Release+Debug with -Werror. Full pitch test harness + Oboe input + JNI bridge NOT yet built.
- P0.6 partial: Webman 2.1 skeleton (6 routes incl. healthz/readyz, DB/Redis/Dragonfly wiring, request-id middleware, dev-auth placeholder), docker-compose (MySQL 8.0.43 + Dragonfly v1.29.0 GHCR), PHPUnit integration suite (18 tests/92 assertions) incl. worker-longevity (10k req, 0 bleed, RSS +120kB), Dragonfly command-surface test (89 commands + §13.5 fence probes), RedisCommandRecorder wired into app path, state-bleed guard greps. P0.6.5 load baseline (k6), P0.6.6 drills, P0.6.7 coroutine ADR NOT yet done.
- CMS: Vite 8.2.2 + plugin-vue 6.0.8, build green.
- Ops: scripts/provision-server.sh (Ubuntu 24.04 bootstrap, --with-android, --check), named MySQL volume for dev data persistence.
**CI:** green (all 5 jobs) on master; triggers on push master/main + PR.
**Environment (local):** Android SDK (platform 36, build-tools 36, NDK r27d), Docker daemon up + group membership, pnpm 11.22. MySQL/Dragonfly images pre-pulled.
**Next (in order):** P0.2 pitch test harness + Oboe/JNI (P0.2.1/3/4) → P0.6.5 k6 load baseline + P0.6.6 drills + P0.6.7 ADR → P0.5 notation renderer → P0.3 bake-off → P0.9 gate review.

### Rulings made (orchestrator)
- License: proprietary/all-rights-reserved (user 2026-08-21); MPL-2.0 scaffold text replaced.
- Dragonfly images from GHCR only (Docker Hub stale); pinned v1.29.0.
- NDK pinned 27.3.13750724 (r27d) installed locally.
- YIN default 2048-window/48kHz resolves ≥~47Hz (F#1+); A0–F#1 deferred to a lowFreq mode (windowSize 4096) to be built+validated with the P0.2 pitch harness.
- P0.6.3 full soak (100k/1h) + P0.6.4 restart-mid-consume drill are documented as manual drills (api/tests/Integration/SoakDrill.md) to run before the P0.9 gate review, not automated in CI.
- setup-android@v3 packages input is space-separated; MySQL schema applied in CI via PHP PDO step (volume mount hits EACCES pre-checkout).

### Known gaps / debt (non-blocking)
- pre-existing lint warnings (vue singleline-html) in cms; Node-20 deprecation warnings on checkout@v4/setup-node@v4 (non-gating); esbuild/vue-demi allow-scripts notices; redis.php EOF newline.
- api .env not committed (vars documented in api/README.md).
- gradle wrapper now committed; android CI uses ./gradlew directly.
