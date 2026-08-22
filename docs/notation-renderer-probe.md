# Notation renderer fallback probe (P0.5.4) — runbook

Status: **Documented, NOT built.** This runbook defines the decision gate for
the fallback probe. The probe itself (an `AndroidView` + custom `View`
renderer) is only built if the Compose `Canvas` prototype fails its acceptance
bar on a low-end device. Per plan §20 P0.5.4, the expected outcome is an ADR
that says which renderer Phase 1 uses and why, **with numbers** — the numbers
below get filled in when device hardware arrives.

## Why this probe exists

The P0.5.3 acceptance bar (plan §20 P0.5.3) is:

- ≥ 58 fps average
- ≤ 1% dropped frames
- no frame over 24 ms
- stable memory

…measured on a **2018 mid-range Android phone** during a 5-minute run of the
stress score (≥ 200 simultaneous-ish notes, both skins, Bravura loaded). If the
Compose Canvas renderer clears that bar with comfortable headroom, Phase 1
(which stacks hit/miss feedback animation, keyboard zone, and combo FX on top)
can keep the single-`Canvas` architecture. If it only just makes it — or misses
it — the headroom is gone before Phase 1 starts, and the custom-`View` path is
the exit.

## Trigger criteria (exactly when the probe starts)

The probe is triggered if ANY of these hold after the P0.5.3 measurement run:

1. **Average fps < 58**, or **dropped frames > 1%**, or **any frame > 24 ms**
   during the 5-minute stress run on the low-end device.
2. **Average fps ≥ 58 but headroom is thin:** mean frame time within 2 ms of
   the 16.7 ms budget **and** the max frame over the run exceeds 20 ms. Phase 1
   adds feedback animation, the keyboard zone, and combo FX on top of this
   budget; thin headroom at P0.5 means the real player never will hold.
3. **Memory is not stable:** observed heap growth > 25 MB over the 5-minute run
   (per-frame allocation showing up as GC pressure), or repeated allocation
   spikes that coincide with frames > 24 ms.

## The 30-minute experiment protocol

The probe is deliberately time-boxed: it is a yes/no measurement, not a
product. Start a stopwatch at the first edit.

| Step | Action | Time budget |
|---|---|---|
| 1 | Fork `ScrollingNotationPlayer` as `LegacyNotationView` — an `AndroidView` hosting a custom `View` that draws the SAME pre-laid-out [NoteLayoutSet] (reuse `NoteLayoutBuilder` unchanged; only the draw surface changes from `DrawScope` to `android.graphics.Canvas`). | 15 min |
| 2 | Reuse the P0.5.3 JankStats hook (same tag, same >24 ms rule) and re-run the 5-minute stress measurement on the same device, same score, both skins. | 10 min |
| 3 | Record the numbers into the ADR (below) and stop. | 5 min |

Constraints: do NOT optimize the custom View during the probe (no sprite
batching, no display lists) — the question is whether the plain
`View.onDraw` path beats Compose on the same geometry, not how far custom
rendering can be pushed. If the custom View fails too, the ADR records that and
the decision falls to the pre-computed-sprite approach (pipeline bakes glyph
atlases), which is a content-format change and gets its own ADR.

## What to record (the ADR input)

One table, filled from the P0.5.3 run (Compose) and the probe run (custom View):

| Metric | Compose Canvas | Custom View | Phase-1 budget note |
|---|---|---|---|
| avg fps (5 min, stress score) | — | — | ≥ 58 |
| dropped frame % | — | — | ≤ 1% |
| max frame time (ms) | — | — | < 24 |
| mean frame time (ms) | — | — | < 16.7 − Phase-1 headroom |
| heap growth (MB) | — | — | < 25 |
| Perfetto trace path | — | — | attach to ADR |

Record how the run was made reproducible: device model + OS, build variant,
perfetto capture command (`perfetto -o /data/misc/perfetto-traces/notation.pb
-t 300s sched freq idle cpu` or the Studio profiler equivalent), and the
JankStats session summary from logcat tag `KeyQuestJank`.

## Decision rule (maps to an ADR)

- **Compose Canvas passes with headroom (criteria 1–3 all clear):** ADR-0003
  says "Phase 1 renderer = single Compose Canvas, translate + draw pre-laid-out
  geometry", cites the numbers, done. The custom-View code from the probe (if
  any was written) is deleted.
- **Custom View wins (or Compose fails):** ADR-0003 says "Phase 1 renderer =
  AndroidView + custom View", cites both runs' numbers and the delta.
- **Both fail:** ADR-0003 records both failures; the recommendation is the
  pre-computed-sprite path (glyph atlases baked by the pipeline into the
  SongPack), which requires a content-format change and a follow-up spike.

The ADR is written by the P0.5.4 task and reviewed in the P0.9 gate review
(plan §20 P0.9); hardware-dependent numbers are filled as soon as the low-end
test device is available.

## Current status (2026-08-22)

- Prototype (Compose Canvas) built: P0.5.1–P0.5.3 instrumentation in place.
- Fallback probe: **not built** (no device to measure against; building it now
  would be speculative code with no acceptance signal).
- Next action: run P0.5.3 on the low-end device, then either close P0.5.4 as
  "not triggered" (Compose passes with headroom) or execute this runbook.