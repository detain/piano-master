# KeyQuest — Android App (`/android`)

Kotlin/Compose Android client for KeyQuest, the piano learning app. See the root
[`plan_piano.md`](../plan_piano.md) — §3.2 (Android app stack) and §20 P0.1 (toolchain
pinning) — for the product context this scaffold implements.

## Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.2 (app), C++20 via NDK (audio engine, later) |
| UI | Jetpack Compose (Material 3); custom `Canvas` rendering for the lesson player |
| Min SDK | 26 (Android 8.0 — AAudio baseline) |
| Target SDK | 36 |
| AGP / Gradle | 8.9.1 / 8.11.1 (versions pinned in `gradle/libs.versions.toml`) |
| Toolchain | JDK 17 bytecode (`jvmTarget = 17`) |

Version catalog rationale lives as a comment in `gradle/libs.versions.toml` — the
pairing is chosen so CI and laptops build byte-identical artifacts (plan §20 P0.1.2).

## Build

> **Wrapper note:** `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are
> committed (generated with `gradle wrapper --gradle-version 8.11.1`); the
> wrapper pins the Gradle distribution via `gradle/wrapper/gradle-wrapper.properties`.
> A JDK 21 + Android SDK are required to build (see `/toolchain.md`).

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests (device/emulator)
```

## Layout

```
android/
  settings.gradle.kts            # plugin repos, dependency repos, :app module
  build.gradle.kts               # plugin aliases (apply false)
  gradle/libs.versions.toml      # single source of truth for all versions
  gradle/wrapper/gradle-wrapper.properties  # pinned Gradle distribution
  app/                           # the only module today
    src/main/                    # manifest, MainActivity, Compose placeholder
    src/test/                    # JVM unit tests
    src/androidTest/             # instrumented tests
```

## What lands next (Phase 0/1)

- **P0.2 — audio spike:** microphone → screen loop with monophonic pitch detection.
  Brings the NDK/Oboe engine module (C++), `RECORD_AUDIO` permission, and the
  `NoteEvent` JNI bridge (`/engine` workspace starts here too).
- **P1.6 — lesson player (plan §7):** the signature screen — Compose `Canvas`
  rendering of SongPack note-bars/staff, wait-for-me mode, on-screen keyboard,
  real-time hit feedback. First real navigation + ViewModel.

## Conventions

- Package root: `com.keyquest.app`.
- Launcher icons are deferred to the design pass — the manifest omits `android:icon`
  on purpose to stay lint-clean without binary assets.
- Future workspaces (Room, DataStore, Retrofit, Hilt, Media3, Play Billing) extend
  the version catalog and add modules; no module declares its own repositories.