# KeyQuest — Session Continuation / Handoff

Pick-up point for a fresh build-orchestrator session. Read this, plan_piano.md §20/§24,
prompt_piano.md, and the SDD ledger (.superpowers/sdd/plan_piano/progress.md) before
dispatching any work.

## Current state (2026-08-25 evening, HEAD 323383a — NO commits landed this session)
P1.6 lesson player: ARCHITECTURE + TOOLCHAIN RESEARCH COMPLETE (full detail in plan §24
evening entry); implementation NOT started. Working tree = HEAD + ONE untracked file:
`android/scoring/src/main/kotlin/com/keyquest/scoring/Touchstone.kt` (agent sanity file —
DELETE it as the first action of the next session; it compiles harmlessly but must not ship).

- Design (in plan §24): `RealtimeScorer` in :scoring — incremental batch-consistent scorer
  (freeze rule: sessionSeconds > max(closeSeconds[0..k]); frozen verdicts never flip;
  finalize() == batch Scorer EXACTLY; full API spec + regression tests incl. the
  tempo-change inversion case + property suite 200 seeds × 8 scenarios).
  LessonSession (pure Kotlin, app module, frame-driven clock) + SongPack model/loader
  (org.json + testImplementation org.json:json) + LayoutHintDeriver (fixtures carry no
  hints) + ProtoScore adapter (rebased beats for renderer) + renderer per-frame feedback
  state + OnScreenKeyboard (multi-touch → NoteEvent(source=TOUCH) → RealtimeScorer) +
  NoteVoice seam (SilentVoice stub) + results overlay (stars/score/heatmap/retry/next) +
  transport (tempo display/pause/loop/progress/skin toggle) + reduced-motion setting.
  App gains implementation(project(":scoring")); bundle pickup_anacrusis fixture as asset.
- P1.6 cuts: tempo-change control (P1.8.4), Wait-for-Me (P1.7), count-in (P1.8.2),
  soundfont voice (P1.8 provisioning), finger badges only when schema `finger` present,
  no in-lesson score readout.
- Screenshot tests: **Paparazzi 2.0.0-alpha05** (only line supporting AGP 8.9.1/Gradle
  8.11.1/Kotlin 2.2.0+compose plugin/Java 21; headless ubuntu-latest; tests in normal
  `test` source set; goldens src/test/snapshots/; recordPaparazziDebug/verifyPaparazziDebug;
  default maxPercentDifference 0.01). Gotchas: issue #2342 HandlerThread NoSuchMethodError
  (our static UI unaffected); record goldens on Linux/JDK21 (cross-OS AA diffs #311).
  Fallback: Robolectric 4.16.1 + Roborazzi 1.73.0.

## Agent-dispatch lesson (CRITICAL — read before dispatching ANYTHING)
- Write-capable agents (coder, general) return EMPTY results on long one-shot specs
  (~5–6 KB prompts): task reports "completed", zero files created.
- A ~600-char sanity task worked instantly (Touchstone.kt).
- Long task() calls can also drop required JSON keys (description/prompt/subagent_type).
- RULE: one small step per dispatch (one file, prompt ≤ ~1500 chars); verify via ls/mtime;
  resume an empty-result task via task_id once, then re-dispatch smaller. Never one-shot
  a multi-file spec. (This rule cost a full session to learn — follow it.)

## Next work — P1.6 lesson player (in order; small dispatches)
1. Delete Touchstone.kt.
2. D1 RealtimeScorer: (a) class file android/scoring/src/main/kotlin/com/keyquest/scoring/RealtimeScorer.kt;
   (b) unit tests RealtimeScorerTest.kt; (c) property tests RealtimeScorerPropertyTest.kt;
   (d) `cd android && ./gradlew :scoring:check --no-daemon --stacktrace` — must pass incl.
   jacoco ≥0.95 LINE gate. Spec: plan §24 evening entry. Review → commit.
3. D2 SongPack model/loader (com.keyquest.app.songpack; org.json; load golden fixtures via
   existing generated test resources build/generated/songpack) + LayoutHintDeriver +
   ProtoScore adapter + LessonSession + ComboTracker + JVM tests. Review → commit.
4. D3 LessonPlayerScreen UI: transport (tempo display/pause/loop/progress/skin toggle),
   notation feedback (per-frame verdict state + hit pop, zero-alloc; reduced-motion
   color-only), OnScreenKeyboard (Canvas multi-touch; glow lead ~1 beat; wrong-key flash;
   expected-key pulse), ResultsOverlay, NoteVoice seam, bundle pickup_anacrusis asset,
   MainActivity → LessonPlayerScreen. Review → commit.
5. D4 Paparazzi 2.0.0-alpha05 screenshots vs golden fixtures (both skins + keyboard +
   results) + CI android-unit += :app:verifyPaparazziDebug. RECORD goldens on this Linux
   server (same JDK 21 as CI). Review → commit.
6. Docs: ledger + plan §24 + continuation + prompt update; push to origin/master.

## Hardware-gated work (when the 5 phones / DGX-520 / MIDI keyboards arrive)
- P0.4 MIDI (USB host + BLE): MidiManager enumeration, running status, NoteOn-vel-0-as-off,
  source arbitration (MIDI → mic auto-disable), silent-controller → soundfont synth.
- P0.7 DGX ground-truth capture rig: aligned (audio,MIDI) pairs, sync click, automated
  alignment verification (≤5ms), batch playback, through-air captures on all 5 phones.
- P0.2.4 latency rig: loopback + high-speed camera + internal instrumentation → per-device
  budget table (<80ms mid-tier gate).
- P0.3.4 device benchmark app: each model artifact × CPU/GPU/NNAPI per device →
  latency/RTF/memory/thermal matrix.
- P0.5.3 measurement: ≥58fps avg, ≤1% dropped, no frame >24ms on the low-end device
  (JankTracker logcat tag KeyQuestJank + Perfetto); then P0.5.4 decision → ADR-0003.
- P0.3 bake-off unblock: Python 3.11 venv (basic-pitch needs TF<2.15.1; no cp312 wheels),
  obtain OAF TFLite (export from the TF checkpoint or a published artifact), MAESTRO audio
  (GCS full zips or an alternative source) → run validate_maestro.py → P0.3.6 model ADR.

## Human/legal work
- P0.8.2: fill per-song checklist records (docs/pd-verification-checklist.md) for the 15
  candidates (content/rights/candidates-2026-08.md) — classical ≈15–20 min each, folk
  ≈25–35 min.
- P0.8.4: legal sign-off on the royalty-free-only position + arrangement/recording stance.
- P0.6.3: 8h overnight idle → hit again (MySQL reconnect; use the soak driver's
  --reconnect-check or a manual burst).

## Open items / known gaps
- P1.6 design open questions (decide during implementation, document in §24):
  (a) combo counts per-note in freeze order (a 3-tone chord adds +3) — revisit with
  dogfooding; (b) hit feedback colors at window close (≤180ms late at ref tempo) — meets
  "within one frame of the verdict"; (c) loop = reset-and-replay pass (events cleared);
  (d) session clock = frame clock for ALL sources (mic/MIDI clock alignment is P1.8).
- P1.1 SongPack v1 is frozen — format must not change without an ADR + golden-fixture
  migration (plan §20 P1.1 expectation).
- Pipeline v0 scope cuts (docs/specs/pipeline-v0.md): calibrated difficulty, L2/L3
  generation, fingering, D.S./D.C. rejection, DGX renderer, CDN publish — all deferred;
  fluidsynth backend awaits provisioning (apt needs sudo).
- Docker bridge networking broken at daemon level (DOCKER-FORWARD missing; root-only fix).
  Workaround: `docker run --network host` for keyquest-mysql + keyquest-dragonfly.
  `docker compose up` keeps failing until a root user fixes dockerd.
- Auth-writes load target (≥800 req/s) NOT MET: MySQL commit fsync ~3.8ms on /dev/md0.
  Evaluate in P1: innodb_flush_log_at_trx_commit tuning, batching, separate log volume
  (async-fsync toggle proved 8.8k req/s).
- yin_cli transition-window finding: engine argmin-YIN confidently reports pitches in mixed
  transition windows — suppressed for scoring by the 1-hop debounce + min-ms; the raw stream
  needs confidence gating before a real-time UI consumes it (P0.2.2 follow-up).
- Engine G1 ragged edge (standard 2048 reads G1 a semitone flat in ~half of windows);
  production routes MIDI 21–42 through lowFreq (4096). Boundary-honesty tightening is a
  tracked follow-up (needs boundary-epsilon care so lowFreq A0 at period 1745.45 vs maxTau
  1745 is preserved).
- make bootstrap uses `python3` (3.13) for the pipeline venv; the correct .venv is 3.12.12
  (from conda ai env). Don't let bootstrap rebuild it with 3.13.
- OAF TFLite + Basic Pitch + MAESTRO audio blockers (see pipeline/README.md bake-off section).
- Scoring calibration open questions (docs/specs/scoring-v1.md): perfectBand / matching-window
  values to be tuned against P0.2.4 device latency data when it lands; the :scoring `replay`
  tool exists so scoring changes can be argued with recorded sessions.

## Environment (server 2026-08-22)
Ubuntu 24.04.4, 64-core/251GB, 3.3TB disk. PHP 8.3.6+Composer 2.10.1, Node 24/npm 11,
Java 21, cmake 3.28.3 (/usr/bin; broken pip shims removed), g++ 13/ninja, ffmpeg 6.1.1,
k6 v2.2.0 (~/bin). Python 3.13 base; pipeline .venv = Python 3.12.12 (from conda ai env
/home/my/miniconda3/envs/ai). Android SDK /home/my/android-sdk (platform 36, build-tools
34/35/36, NDK 27.3.13750724). gh authed as detain. Remote git@github.com:detain/piano-master.
NOTE: record Paparazzi goldens on THIS server (Linux, JDK 21 — matches CI ubuntu-latest).

## Discipline (non-negotiable)
- Review loop after EVERY task: reviewer agent → fix until APPROVE.
- Trunk-based; commits on master; CI (5 jobs: engine-host-tests, android-unit, api-tests,
  cms-build, lint-all) is the gate; push to origin/master.
- Never run two implementers on the same workspace in parallel.
- Dispatch rule: ONE small step per agent call (prompt ≤ ~1500 chars); verify via ls/mtime.
- Maintain the ledger + plan §24 + docs/continuation.md + prompt_piano.md as work lands.