# KeyQuest — Session Continuation / Handoff

Pick-up point for a fresh build-orchestrator session. Read this, plan_piano.md §20/§24,
prompt_piano.md, and the SDD ledger (.superpowers/sdd/plan_piano/progress.md) before
dispatching any work.

## Current state (2026-08-26 evening, HEAD 2af30e8 — LOCAL, push pending)
Four commits on master, **ahead 4 of origin/master** (NOT pushed):
- **2af30e8 — ADR-0003 model bake-off decision** (Basic Pitch = calibration baseline, Fno
  0.639 vs 0.709; engine YIN = device-side floor; OAF conclusively blocked). Satisfies the
  plan's P0.3.6 written model decision.
- **7ea04d2 — OAF TFLite export CONCLUSIVELY BLOCKED** (CudnnRNN GPU-only ops — no CPU
  kernels in any TF build, verified 1.15/2.15; tfhub URL dead; magenta 2.1.4 arch mismatch;
  unlock = GPU machine + TF 1.15 metagraph scrub or LSTM surgery). Evidence in
  pipeline/README.md.
- **ce17e68 — P0.3.3 validation DONE (GREEN).** validate_maestro unblocked + published-number
  comparison passes: Basic Pitch **Fno 0.639 vs published 0.709** (Bittner et al. 2022, MAESTRO
  v2 test), ±0.15 gate, exit 0 on 2026-08-26, on the 8-piece duration-stratified v3 test subset.
  note-F-with-offsets 0.076 informational (short subset pieces punish the offset criterion).
  Target corrected: 0.8226 was Onsets-and-Frames' number (Hawthorne et al. 2018), not Basic
  Pitch's — the paper's headline is Fno ("offsets are less objective than onsets"). 11 harness
  tests green. Detail: pipeline/README.md "Model bake-off status (P0.3.3)" + plan §24 entry.
- **c26a6f5 — P0.8.2 records DONE:** 18 per-song PD clearance records (15 primary + 3 alternates)
  in `content/rights/records/`; all GLOBALLY PD or CLEARED-WITH-NOTE; editions pinned; §8
  sign-off still pending P0.8.4 legal gate.

P0.6.3 8h idle soak **PASS** (2026-08-26 20:06Z, docs/runbooks/soak-results-2026-08-26.md,
ledger Task 19) — runbook + ledger + plan §24 edits sit uncommitted and land with the push.

P1.6 lesson player (0b28af0/5226130/1ca619b/1c52e85 + docs cd90dd5) is already pushed with CI
5/5 green; 218 tests.

## py311 env recipe (bake-off / basic-pitch; verified on this server)
```bash
conda create -n py311 python=3.11   # basic-pitch pins tensorflow>=2.4.1,<2.15.1 — no cp312 wheels
conda activate py311
pip install "setuptools<81"         # REQUIRED: 81+ removed pkg_resources; resampy + tensorflow-hub import it
pip install -e ".[dev]"             # harness deps (mir_eval, numpy, ...)
pip install basic-pitch==0.4.0 tensorflow==2.15.0
python -m pipeline.eval.validate_maestro --workdir /tmp/opencode/maestro --require-comparison
```
Data: 108 GB `maestro-v3.0.0.zip` at `/home/sites/maestro/` (individual-wav URLs 404); subset
wavs pre-extracted into `/tmp/opencode/maestro/audio/` (harness accepts >1024-byte files).
OAF env: conda `py311-oaf` (magenta 2.1.4 `--no-deps` + note-seq + TF 2.15.0); checkpoint at
`/home/sites/maestro/oaf/checkpoint/maestro_checkpoint.zip`.

## Remaining software work (in order; small dispatches)
1. **8h idle soak — DONE (PASS, 2026-08-26 20:06Z):** 8 h zero-traffic idle (12:03:57Z →
   20:03:57Z, PID 2235980 alive, RSS flat 40372 kB) + documented 5000/120 s soak (0 err,
   0 bleed, p99 995.03 → 994.89 ms) + reconnect check PASS. Runbook
   `docs/runbooks/soak-results-2026-08-26.md`; closes the plan §24 P0.6.3 idle requirement.
2. **Commit + push** (HEAD 2af30e8 + soak runbook + ledger Task 19 + plan §24 + this file) and
   confirm CI 5/5 green.
3. **P0.3.6 model ADR — DONE via ADR-0003** (commit 2af30e8): plan §5 P0.3.6 is the written
   model decision (model, delegate strategy per device class, fallback ladder, underperformance
   conditions); ADR-0003 records model = Basic Pitch calibration baseline, device floor = engine
   YIN, OAF blocked, known bias/conditions documented. Per-device-class delegate matrix still
   awaits hardware benches (P0.3.4).
4. **OAF TFLite export: CONCLUSIVELY BLOCKED (2026-08-26)** — CudnnRNN GPU-only kernels, no
   GPU on server; unlock = NVIDIA GPU + TF 1.15 metagraph scrub (`/tmp/opencode/oaf_tf115_scrub.py`)
   or CudnnRNN→LSTM surgery. Evidence: pipeline/README.md + ADR-0003; `OafTfliteWrapper` stays
   a skeleton.
5. **Hardware-gated (5 phones / DGX-520 / MIDI keyboards):** P0.4 MIDI, P0.7 DGX corpus,
   P0.2.4 latency rig, P0.3.4 device bench, P0.5.3 fps (≥58fps, tag KeyQuestJank).
6. **Human/legal:** P0.8.4 sign-off on the P0.8.2 records; P0.9 gate re-issue with measured numbers.

P1.6 open items: P1.8.4 tempo control; P1.8 mic/MIDI clock alignment (TOUCH-only today);
loop-replay via frozenCount regression; in-lesson score readout (cut).

## Build workarounds (MUST stay documented — do not "clean up")
- Root buildscript: force sdk-common 31.13.2 → 31.9.1 (31.13.2 REMOVED `GradleVersion.parse`).
- App module: force kotlin-stdlib 2.2.0 (paparazzi transitives resolve 2.3.0).
- Renderer testability seams: `layoutDispatcher` (Unconfined in tests) + `fixedViewportSize`
  (Paparazzi never fires `onSizeChanged`; `produceState` keys include it).
- Kotlin 2.2 REMOVED `InputStream.readText()` — use `bufferedReader().use { it.readText() }`.
- Record Paparazzi goldens on THIS server (Linux, JDK 21 — matches CI ubuntu-latest).

## Agent-dispatch lesson (CRITICAL — read before dispatching ANYTHING)
Write-capable agents (coder, general) return EMPTY results on long one-shot specs (~5–6 KB
prompts). RULE: one small step per dispatch (one file, prompt ≤ ~1500 chars); verify via
ls/mtime; resume an empty-result task via task_id once, then re-dispatch smaller. Never
one-shot a multi-file spec.

## Open items / known gaps
- P1.1 SongPack v1 frozen — format changes need an ADR + golden-fixture migration.
- Pipeline v0 scope cuts (docs/specs/pipeline-v0.md); fluidsynth awaits provisioning (no sudo).
- Docker bridge networking broken (DOCKER-FORWARD missing) → DBs via `docker run --network host`.
- Auth-writes load target NOT MET (MySQL commit fsync ~3.8ms on /dev/md0); async-fsync toggle
  proved 8.8k req/s — tune in P1.
- yin_cli transition-window confidence gating (P0.2.2 follow-up); engine G1 ragged edge
  (production routes MIDI 21–42 through lowFreq 4096).
- make bootstrap uses `python3` (3.13) for the pipeline venv — correct .venv is 3.12.12.
- Scoring calibration (docs/specs/scoring-v1.md) awaits P0.2.4 device latency data; ADR-0003
  pending P0.5.3 fps numbers.

## Environment (server 2026-08-26)
Ubuntu 24.04.4, 64-core/251GB. PHP 8.3.6+Composer 2.10.1, Node 24, Java 21, cmake 3.28.3,
g++ 13/ninja, ffmpeg 6.1.1, k6 v2.2.0. Python 3.13 base; pipeline .venv = 3.12.12 (conda `ai`);
conda envs: `py311` (basic-pitch eval), `py311-oaf` (magenta 2.1.4 experiments), `py311-oaf2`
(magenta 1.1.2 experiments), `tf115` (TF 1.15 CPU) — `setuptools<81` / `protobuf==3.20.3`
pinned as needed in each. Android SDK /home/my/android-sdk (NDK 27.3.13750724). gh authed
(detain). Remote git@github.com:detain/piano-master.
MAESTRO data: /home/sites/maestro/ (108 GB zip + oaf/checkpoint).

## Discipline (non-negotiable)
- Review loop after EVERY task: reviewer agent → fix until APPROVE.
- Trunk-based; commits on master; CI (5 jobs) is the gate; push to origin/master.
- Never run two implementers on the same workspace in parallel.
- Dispatch rule: ONE small step per agent call (prompt ≤ ~1500 chars); verify via ls/mtime.
- Maintain the ledger + plan §24 + docs/continuation.md + prompt_piano.md as work lands.