# ADR-0003: Model bake-off for the transcription baseline

Status: Accepted
Date: 2026-08-26

## Context

Plan §5.2 (P0.3.3) requires a validated transcription baseline and a
device-side pitch floor for scoring calibration. Three candidates compete:
(a) Magenta Onsets-and-Frames (OAF) as a TFLite mobile model, (b) Spotify
Basic Pitch as the published MAESTRO baseline, (c) the engine's C++ YIN as
the device-side floor. The harness is `pipeline/eval` (mir_eval metrics,
calibration verified 10/10 fixtures to 1e-9, synthetic F1=1.0 oracle tests);
reference data: MAESTRO v3.0.0 (108 GB zip), test split 416 pieces.

## Options

- **O1 — OAF TFLite (mobile candidate) — BLOCKED, conclusively
  (2026-08-26).** No published TFLite/SavedModel exists (tfhub.dev 404, GCS
  404s); the 2019 `maestro_checkpoint.zip` is GPU-trained with CudnnRNN
  opaque-kernel ops that have **no CPU kernels in any TF build** (verified on
  TF 1.15/2.15 CPU: "No OpKernel was registered"); no NVIDIA GPU on the
  build server; a magenta 2.1.4 estimator rebuild mismatches the checkpoint
  architecture (fc_end 1920 vs 5472, conv0/BN placement); training-era code
  absent from pip. Unlock paths: GPU machine + TF 1.15 metagraph conversion
  (scrub script restores all 162 vars cleanly), or CudnnRNN→LSTM surgery.
- **O2 — Spotify Basic Pitch — RUNNING, VALIDATED.** Fno **0.639** on the
  8-piece duration-stratified v3 test subset vs the published **0.709**
  (Bittner et al. 2022, MAESTRO v2 full test); ±0.15 gate PASSED 2026-08-26
  (`validate_maestro` exit 0). Offset-based F (0.076) informational —
  dense/staccato subset; the paper's headline metric is Fno ("offsets are
  less objective than onsets"). Correction: the earlier README target
  0.8226 was Onsets-and-Frames' number, misattributed to Basic Pitch.
- **O3 — Engine C++ YIN floor — RUNNING.** F1 1.0 on the synthetic clean
  melody (10 notes), onset median 0.0107 s; two documented segmentation
  choices (one-hop offset extension, one-hop onset debounce).

## Decision

**Basic Pitch is the calibration baseline** (validated against its published
Fno with documented subset/version caveats); **the engine YIN floor is the
device-side reference**; **OAF stays BLOCKED** as an external blocker (no
GPU). The harness's published-number comparison becomes the P0.3.3
acceptance gate: Fno within ±0.15 of 0.709, F and onset error informational.
Target correction recorded: 0.8226 is OAF's number, not Basic Pitch's.

## Consequences

- **Positive:** P0.3.3 is unblocked and GREEN (commit ce17e68); the gate is a
  published-number comparison, not a self-referential one.
- **Negative:** device-side real-scoring (DGX captures, phones) still awaits
  the hardware; OAF re-attempt requires a GPU machine (hardware milestone).
- **Bias documented:** the 8-piece subset vs full-split comparison carries
  known bias; future runs may raise the piece limit (zip on disk) but must
  keep the duration stratification.
- **Reproducible environment:** the py311 conda env (`tensorflow==2.15.0`,
  `basic-pitch`, `setuptools<81`) is the canonical eval environment.