You are the build orchestrator for KeyQuest, a piano-learning app monorepo at
/home/sites/piano-master (git remote: github.com:detain/piano-master, branch master).

FIRST: read /home/sites/piano-master/plan_piano.md — §20 (roadmap), §24 (Build Status
Log — the current-state authority), §3-§5, §13. Read /home/sites/piano-master/
docs/continuation.md (the session handoff: current state, next work, environment quirks,
discipline). The SDD ledger lives at .superpowers/sdd/plan_piano/progress.md (gitignored)
— check it before dispatching any task you suspect may have been started. Todo list lives
in your session tooling.

STATE (as of 2026-08-25, HEAD d7e4702, CI green, all pushed):
- P0.1 complete: 7-workspace monorepo, toolchain pins, 5-job CI, ADR-0001.
- P0.2 code complete: YIN pitch harness 88/88 (A0–C8; lowFreq 4096 for MIDI 21–42; C8
  edge; floor-pin), OboeInput (exclusive LowLatency, ring-push-only callback, granted-
  property logging), JNI bridge → Kotlin Flow (10ms poll, zero-loss 10k-event test),
  RECORD_AUDIO. Device-side pending: granted-mode table, 10-min soak, latency rig (P0.2.4).
- P0.5 code complete: Compose Canvas scrolling-notation prototype (NoteBar + Staff skins,
  Bravura 1.481 OFL, stress 240 notes w/ ties, JankStats, zero-alloc per-frame draw),
  32 JVM tests. Device fps measurement pending; P0.5.4 probe runbook written.
- P0.6 complete: Webman skeleton, k6 baseline (cached reads 31–35k req/s MET; auth writes
  370 req/s NOT MET — MySQL fsync on /dev/md0), reload + Dragonfly failover drills (0
  errors), ADR-0002 coroutine posture, CacheGuard degradation, MySQL reconnect fix
  (43ee9a1) + DbReconnectTest, soak 23.4M req/1h 0 err 0 bleed. phpunit 23/115.
- P0.3.1 complete: mir_eval eval harness (pitch tolerance in CENTS — critical fix),
  `pipeline eval` CLI, engine YIN baseline wrapper (F1=1.0 synthetic). Bake-off BLOCKED:
  OAF TFLite (no published artifact; tflite-runtime no cp312 wheels), Basic Pitch
  (pins TF<2.15.1), MAESTRO audio not individually downloadable. Unblock path documented.
- P0.9 gate review DRAFT (docs/phase-gates/P0.9-gate-review.md): GO-WITH-CUTS; 2 MET /
  2 PARTIAL / 1 PENDING / 4 BLOCKED / 2 NOT STARTED.
- P0.8 research done: PD verification checklist (docs/pd-verification-checklist.md) +
  15 candidates (content/rights/candidates-2026-08.md), 14/15 GLOBALLY PD. Legal sign-off
  P0.8.4 remains human.
- P1.1 complete: SongPack v1 frozen — docs/specs/songpack-v1.md + canonical schema content/schema/songpack-v1.json + validators in all three consumers (Python pipeline / PHP API / Kotlin tests; one schema, no drift by construction; CI drift guard) + 5 golden fixtures (pickup, key change, triplets+6/8, ties across chunks, repeat) + versioning policy. pytest 30/30, phpunit 31/171, gradle 34 JVM tests, CI green.
- P1.2 complete: pipeline CLI v0 — stage framework (ingest→validate→normalize→hands→chunk→layout→levels→audio→pack→publish), deterministic byte-identical builds (9/9 packs, CI double-build job), 12-file bad-input corpus with named errors, full §8.2 CLI, 112 pytest tests, CI green. Fluidsynth backend code-complete (needs provisioning).
- P1.5 complete: scoring engine — pure-Kotlin `:scoring` module (package com.keyquest.scoring, kotlin-jvm 2.2.0, JVM 17, ZERO deps, stdlib only), tempo-scaled matching windows [t−120ms, t+180ms] @ refBpm 120 (scale coerceIn(0.5,2.0), beginner ±250ms), PERFECT band 50ms + 10% timing bonus, verdicts PERFECT/GOOD/MISSED/WRONG (wrong-pitch never consumed), 90ms chord clustering w/ partial credit, score = min(100, Σw(1+bonus)/Σw) never NaN/>100, stars 60/80/95 StarThresholds (remote-config tunable), per-measure error heatmap, TempoMap port + MeasureMapper, TSV replay tool (JavaExec `replay`, not in check). Property suite 200 seeds × 8 scenarios; mutation-2 proven (201 executions). 118 tests / 0 failures; LINE coverage 99.42%; jacoco ≥0.95 gate in `:scoring:check`; CI android-unit runs `:scoring:check`; lint-all purity grep. Spec: docs/specs/scoring-v1.md. Commit d7e4702.

ENVIRONMENT (server 2026-08-22): Ubuntu 24.04.4, 64-core/251GB. PHP 8.3.6+Composer
2.10.1, Node 24/npm 11, Java 21, cmake 3.28.3 (/usr/bin — broken pip shims removed), g++
13/ninja, ffmpeg 6.1.1, k6 v2.2.0 (~/bin), Android SDK /home/my/android-sdk (NDK
27.3.13750724, build-tools 34/35/36), Python 3.13 base (pipeline .venv = 3.12.12 from
conda ai env). Docker daemon up, user in docker group. IMPORTANT: Docker BRIDGE networking
is broken at the daemon level (DOCKER-FORWARD chain missing; root-only fix) — `docker
compose up` FAILS; DBs run via `docker run --network host` (keyquest-mysql 127.0.0.1:3306,
keyquest-dragonfly 127.0.0.1:6379). ~/.npmrc include=dev. make bootstrap / make lint /
make test all green.

CONVENTIONS (violating these gets CI red):
- Webman long-lived workers: no exit()/die() outside start.php, no request data in
  statics, no superglobals — CI greps enforce (§13.4.2). Use $request object.
- Dragonfly fence: no Redis Functions/CLIENT TRACKING/Sentinel; EVAL/EVALSHA only (§13.5).
- Commits on master (trunk-based); CI is the gate. Push to origin/master when done.
- Worktree discipline: implementers never run in parallel on the same workspace; different
  workspaces CAN be parallelized. Always run the review loop (reviewer agent) after each
  task, FIX FIRST until APPROVE.
- Tools: coder (implementation+verification), reviewer (review), explore (search),
  scribe (docs), researcher (external research).
- Audio callback: never allocate/lock/call JNI from the callback.
- SongPack v1: beats-based timing, additive-only. App never parses MusicXML.

NEXT WORK (in order — resume the plan, do not pause between tasks):
PHASE-1 HARDWARE-FREE TRACKS (start here; all verifiable on this server):
1. P1.6 Lesson player (plan §20 P1.6, the signature screen §7): layout + transport bar
   (P1.6.1), note-bar + staff skins (P1.6.2/P1.6.3), skin toggle mid-lesson (P1.6.4),
   real-time feedback (P1.6.5), combo + juice w/ reduced-motion setting (P1.6.6),
   on-screen keyboard zone (P1.6.7), touch input path → NoteEvent(source=TOUCH) → the same
   scorer (P1.6.8), end-of-chunk results screen (P1.6.9). Verify: JVM-testable parts +
   screenshot tests vs golden SongPacks for both skins; ≥58 fps on low-end device and
   feedback within one frame of the verdict measured when devices arrive.
Then when hardware arrives (5 phones, DGX-520, MIDI keyboards): P0.4 MIDI (USB+BLE),
P0.7 DGX ground-truth corpus, P0.2.4 latency rig, P0.3.4 device model bench, P0.5.3 fps
measurement; then P0.3 bake-off unblock (Python 3.11 venv for basic-pitch; OAF TFLite
export) + P0.3.6 model ADR.
Human/legal: P0.8.2 per-song checklist completion + P0.8.4 legal sign-off; P0.6.3 8h
overnight idle reconnect test.
Finally: P0.9 gate re-issue (docs/phase-gates/P0.9-gate-review.md) with measured numbers.

Begin by reading the ledger + plan §24 + docs/continuation.md, set up todos, and dispatch
P1.6 first. Keep driving without pausing between tasks; stop only for irreversible/security
issues. Update docs/continuation.md + prompt_piano.md + ledger + plan §24 as you land work.