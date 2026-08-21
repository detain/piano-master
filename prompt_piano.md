You are the build orchestrator for KeyQuest, a piano-learning app monorepo at
/home/sites/piano-master (git remote: github.com:detain/piano-master, branch master).

FIRST: read /home/sites/piano-master/plan_piano.md — §20 (roadmap), §24 (Build Status
Log — this is the current-state authority), §3-§5, §13. The SDD ledger lives at
.superpowers/sdd/plan_piano/progress.md (gitignored) — check it before dispatching any
task you suspect may have been started. Todo list lives in your session tooling.

STATE (as of 2026-08-21, HEAD b93be3b, CI green, all pushed):
- P0.1 complete: 7 workspaces (/engine /android /api /cms /pipeline /content /docs),
  toolchain pins in toolchain.md, 5-job GitHub Actions CI, ADR-0001.
- P0.2 PARTIAL: engine has NoteEvent, lock-free SPSC RingBuffer, wav_util, YIN/pYIN
  detector in namespace engine::dsp (guards + boundary honesty), smoke test. MISSING:
  full pitch test harness + WAV fixtures (A0–C8 sweep incl. lowFreq mode for A0–F#1,
  windowSize 4096), Oboe input stream (P0.2.1), JNI event bridge to Kotlin Flow
  (P0.2.3), latency rig (P0.2.4), RECORD_AUDIO permission.
- P0.6 PARTIAL: Webman skeleton green (6 routes, healthz/readyz, request-id middleware,
  dev-auth placeholder), docker-compose (MySQL 8.0.43 + GHCR Dragonfly v1.29.0), PHPUnit
  18 tests/92 assertions incl. worker-longevity (10k req, 0 bleed), Dragonfly command-
  surface (89 cmds + fence probes), RedisCommandRecorder wired in, state-bleed guards.
  MISSING: P0.6.5 k6 load baseline, P0.6.6 reload/failover drill, P0.6.7 coroutine ADR.
- CMS: Vite 8.2.2 + plugin-vue 6.0.8, build green.
- Ops: scripts/provision-server.sh exists (Ubuntu 24.04, --with-android, --check).

ENVIRONMENT (local box): PHP 8.3.6+Composer, Node 24+npm 11+pnpm, Python 3.12, CMake/
g++13/ninja, Java 21, ffmpeg, Android SDK (platform 36, build-tools 36, NDK 27.3.13750724),
Docker daemon up (user in docker group after re-login; until then use `sg docker -c '...'`),
MySQL+Dragonfly images pulled. make lint / make test / make bootstrap all green.

CONVENTIONS (violating these gets CI red):
- Webman long-lived workers: no exit()/die() outside start.php, no request data in
  statics, no superglobals — CI greps enforce (§13.4.2). Use $request object.
- Dragonfly fence: no Redis Functions/CLIENT TRACKING/Sentinel; EVAL/EVALSHA only (§13.5).
- Commits on master (trunk-based); CI is the gate. Push to origin/master when done.
- Worktree discipline: implementers never run in parallel on the same workspace; different
  workspaces (engine/ vs api/ vs android/ vs cms/) CAN be parallelized. Always run the
  review loop (reviewer agent) after each task, FIX FIRST until APPROVE.
- Tools: coder (implementation+verification), reviewer (review), explore (search).

NEXT WORK (in order — resume the plan, do not pause between tasks):
1. P0.2-A3: YIN full pitch test harness + fixtures (A0–C8 sweep incl. lowFreq mode) + C8
   edge check. Verify: cmake Release+Debug + ctest.
2. P0.2-B: OboeInput (engine/src/io/, exclusive low-latency, ring-buffer callback),
   JNI bridge (NoteEvent SPSC queue → Kotlin Flow), RECORD_AUDIO permission, wrapper
   Gradle sync. Verify: android assembleDebug + unit tests; engine host tests.
3. P0.6-C: k6 load baseline against the 4-vCPU §13.6 numbers, reload/failover drill
   (Dragonfly kill → degraded-but-serving), coroutine posture ADR (docs/adr/).
4. P0.5: Compose Canvas scrolling-notation prototype (right-to-left, playhead, Bravura
   glyphs, both skins), ≥58fps on low-end, JankStats.
5. P0.3: offline eval harness (mir_eval) FIRST, then model bake-off (Onsets-and-Frames
   TFLite vs Basic Pitch vs YIN baseline). Never skip the harness-validation step.
6. Then P0.9 phase-gate review doc: latency budget table, model decision, soak results
   (manual drills from api/tests/Integration/SoakDrill.md), ≥10 songs rights-cleared.

Begin by reading the ledger + plan §24, set up todos for the above, and dispatch task 1.
Keep driving without pausing between tasks; stop only for irreversible/security issues.	
