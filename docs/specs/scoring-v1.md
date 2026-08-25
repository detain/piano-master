# Scoring Engine v1 — Specification

Status: **Done** (P1.5, plan §6 + §20 P1.5)
Implementation: `android/scoring` — pure Kotlin, zero dependencies (JUnit 4
for tests only), zero Android imports (CI lint-all grep enforces), zero I/O
in scorer code (file I/O lives only in the replay tool), deterministic.
Coverage gate: ≥ 95% line (CI runs `:scoring:check`).

Normative sources: plan §6 (matching window, chord cluster, verdicts,
score/stars math, telemetry) and §20 P1.5 (expectations). This document is
the human companion; the Kotlin code is the executable specification.

---

## 1. Inputs

| Type | Fields | Notes |
|---|---|---|
| `PlayedNote` | `pitch` 0..127, `velocity` 0..127, `onTimeNs >= 0`, `offTimeNs == -1 \|\| >= onTimeNs` | Mirrors the app's `NoteEvent` (com.keyquest.app.audio) but source-agnostic; the app adapts its NoteEvent stream in P1.6. |
| `ExpectedNote` | `pitch` 0..127, `startBeat >= 0`, `durBeats > 0`, `hand` R\|L, `scoringWeight >= 0` (default 1.0) | SongPack v1 note record's scoring fields (songpack-v1.md §3.2); ornament expansions 0.2, grace 0 come from the pipeline verbatim. |
| `TempoMap` | entries `(atBeat, bpm > 0, curve step\|linear)`, first `atBeat == 0`, strictly increasing | Exact port of `pipeline/pipeline/build/audio.py` `_interval_seconds` / `_linear_bpm_at` / `_bpm_seconds` — the two cannot drift silently (doc-comment cites the reference). |
| `MeasureMapper` | time signatures `(atBeat, numerator 1..32, denominator ∈ {1,2,4,8,16,32})`, `pickupBeats`, `durationBeats` | Beat → 0-based measure index for the heatmap. Measure 0 = pickup measure `[0, pickupBeats)` (empty when no pickup); measure m ≥ 1 = full measures of `numerator*4/denominator` at the signature active at the measure's start. Mirrors `stage_normalize.py` `_linearize`; beats at/after the end of the last measure clamp. |
| `ScoreConfig` | §3 | |
| `StarThresholds` | `oneStar`/`twoStar`/`threeStar` (defaults 60/80/95), `0 <= one <= two <= three <= 100` | Remote-config-tunable; the app feeds live values in P1.6+. |

All invariants are `require()`-guarded with descriptive messages (fail fast,
parse-don't-validate).

## 2. Matching (P1.5.1)

For each expected note in canonical order (startBeat asc, R before L, pitch
asc — songpack-v1.md §3.1):

1. `t = tempoMap.beatToSeconds(startBeat)`.
2. Window = `[t − earlyMs/1000, t + lateMs/1000]` seconds (formula §3).
3. Best **unconsumed** played event of **equal pitch** inside the window:
   min `|deviation|` (`eventTime − t`), ties broken by earliest `onTimeNs`.
   Consume it. `PERFECT` when `|deviation| <= perfectBandMs/1000`, else
   `GOOD`.
4. No equal-pitch event: `WRONG` when **any** unconsumed wrong-pitch event
   lies inside the window, else `MISSED`.

Documented decisions:

- **Wrong-pitch events are never consumed.** A wrong-pitch event can never
  match a note (pitch must be equal), so it stays in the pool and is
  reported in the extra events afterwards. It may flag several notes'
  windows as WRONG; that is the "miss with hint" signal (plan §6).
- **Extra-note policy.** Every event never consumed by an equal-pitch match
  is an "extra" — including extra notes played inside the window of an
  already-matched note. Extras are telemetry, never a verdict change.
- **Determinism.** Inputs may arrive in any order — copies are sorted
  internally (events by onTimeNs, then pitch, then velocity; expected by the
  canonical order). Same inputs → same outputs; HashMap is used only for
  aggregation into sorted outputs.

## 3. Window formula and knobs (ScoreConfig)

At expected beat t: `bpm = tempoMap.bpmAt(t)`,
`scale = (refBpm / bpm).coerceIn(windowScaleClampMin, windowScaleClampMax)`,
`earlyMs = baseEarlyMs * scale` (`beginnerEarlyMs * scale` when beginner),
`lateMs = baseLateMs * scale` (`beginnerLateMs * scale` when beginner).

Defaults: `refBpm 120`, `baseEarlyMs 120`, `baseLateMs 180` — the
`[t − 120 ms, t + 180 ms]` window at reference tempo (plan §6). Beginner
mode widens to ±250 ms at reference tempo (plan §6).

Documented decisions:

- **Tempo scaling** — half the bpm doubles the window, so a slow rendition
  is not punished for human timing error at half speed.
- **Clamp 0.5..2.0** — extreme tempos must not produce degenerate
  (near-zero or absurdly wide) windows.
- **PERFECT band 50 ms** at reference tempo — `|deviation| <= 50 ms` is
  PERFECT, else GOOD. The band is deliberately smaller than the window; the
  two are independent knobs.
- **Timing bonus 0.10** — a PERFECT hit weighs `scoringWeight * 1.10`;
  GOOD is full credit (1.0). This is plan §6's "timing bonus for PERFECT".
- **Chord cluster 90 ms** (absolute, NOT tempo-scaled, NOT beginner-widened)
  — a chord is a physical gesture (§4).

## 4. Chord clustering (P1.5.2)

Sort expected notes canonically; walk in order; a note joins the current
cluster while its onset, converted to **seconds** at the tempo of the gap's
midpoint (`bpmAt`), is within `chordClusterMs / 1000` of the cluster's
**first** note; otherwise it starts a new cluster. Rolled chords (tones
spread up to the tolerance) still cluster. Cluster membership is therefore
tempo-dependent (beats → seconds) but level-independent.

## 5. Verdicts (P1.5.3)

| Verdict | Meaning | Feedback (plan §7.3) |
|---|---|---|
| PERFECT | hit, \|deviation\| ≤ perfectBand | green fill + pop |
| GOOD | hit, beyond the band | green fill |
| MISSED | nothing played in the window | red outline as the playhead passes |
| WRONG | wrong-pitch event(s) in the window | "miss with hint" — red key flash |

## 6. Score and stars

- `totalWeight = Σ scoringWeight` over all expected notes.
- `hitWeight = Σ` over matched notes of `scoringWeight * (1 + perfectBonus
  if PERFECT else 1)`.
- `score = totalWeight == 0 ? 0.0 : min(100.0, 100.0 * hitWeight /
  totalWeight)` — explicit branch: **never NaN, never > 100**.
- `stars` = number of StarThresholds met (0..3): ≥ oneStar → 1, ≥ twoStar →
  2, ≥ threeStar → 3. Thresholds are a parameter (remote config).
- Empty expected → 0.0 / 0 stars / empty report. Empty events → 0.0 with
  everything MISSED. Grace notes (weight 0) count toward verdicts and the
  heatmap but never move the score.

## 7. Telemetry (P1.5.4 — emitted now, consumed in Phase 2)

`ScoreReport` carries, all in deterministic (sorted) order:

- per-note `NoteOutcome(expectedIndex, verdict, matchedEventIndex?,
  deviationMs?, matchedPitch?)` — `expectedIndex` is the canonical-order
  index (equals the SongPack `notes.json` array index when the app passes
  canonical notes);
- `measureHeatmap: Map<Int, MeasureErrorSummary(missed, wrong)>` — per
  measure index, from MISSED/WRONG notes only, sorted by measure; **feeds
  the 5-Min Workout generator (plan §9.3)**;
- `chordOutcomes` — FULL / PARTIAL / MISSED per cluster with `tonesHit` /
  `tonesTotal` (partial chords score partial credit, plan §6);
- `extraEvents` (everything never consumed) and the totals
  (totalWeight, hitWeight, perfect/good/missed/wrong/matched counts).

## 8. Replay tool (P1.5.6)

`./gradlew :scoring:replay --args="--events <file> --expected <file>
[--tempo-map <file> | --bpm <n>] [--beginner] [--stars one,two,three]"`

TSV session format (`com.keyquest.scoring.replay.SessionFormat`, stdlib-only;
`#` comments and blank lines ignored; parse errors report the line number):

| Section | Line |
|---|---|
| events | `pitch<TAB>velocity<TAB>onTimeNs<TAB>offTimeNs` |
| expected | `pitch<TAB>startBeat<TAB>durBeats<TAB>hand(R\|L)<TAB>scoringWeight` |
| tempo map | `atBeat<TAB>bpm<TAB>curve(step\|linear)` |

All writers emit a `# keyquest scoring session v1` header. Replay scores the
session through the same pure pipeline as the live app. **Known limitation:**
the session format carries no time signatures, so the replay heatmap assumes
4/4 with no pickup (measure boundaries only); scores, verdicts, and chord
outcomes are unaffected (they never consult the measure mapper). The live
app scores with the manifest's real MeasureMapper. The tool exists so
scoring changes are argued with recorded real sessions (plan §20 P1.5.6);
exit code 0 on success, 1 on bad args/files.

## 9. Determinism guarantees

- No `kotlin.random`, no wall clock, no `Random` in main code (the property
  test's generator uses `java.util.Random` with a fixed seed).
- All list/map outputs are in sorted, stable order (TimSort-stable
  comparators; `sortedMapOf` for the heatmap).
- Matching consumes events in a deterministic order; equal-pitch ties break
  by earliest `onTimeNs`.
- Property tests (200 seeds × 8 scenarios: early, late, extra, missing,
  wrong-octave, rolled, duplicates) assert: score ∈ [0, 100] and never NaN,
  determinism (identical input → identical `ScoreReport`), stars consistent
  with thresholds, heatmap sums agree with verdict counts, matched events
  consumed at most once, extra events never increase the score, and
  `beatToSeconds` monotone non-decreasing.

## 10. Open questions for calibration

Values to tune once real device data exists (all are knobs, none require
code changes):

1. **`perfectBandMs` (50 ms)** — plan §20 P0.2.4 device latency numbers
   (mic latency, Oboe input latency, granted-mode tables) should inform this
   directly: the band must comfortably exceed the input path's measured
   jitter or perfect hits become impossible; too wide and GOOD is meaningless.
2. **`baseEarlyMs`/`baseLateMs` (120/180)** — the asymmetry assumes players
   rush more than they drag; verify against recorded sessions via the replay
   tool before widening.
3. **`windowScaleClampMin/Max` (0.5/2.0)** — extreme-tempo behavior is
   untested on device; the clamps may need tightening.
4. **`perfectBonus` (0.10)** — tune from score distributions of real
   sessions: the bonus should reward timing without making GOOD feel
   punitive.
5. **`chordClusterMs` (90 ms)** — rolled-chord acceptance: too tight breaks
   slow rolled chords at low tempo (the tolerance is absolute, per plan §6);
   validate with recorded rolls.
6. **Monotonicity corner (known, bounded)** — the greedy matcher + PERFECT
   bonus can reshuffle which of two same-pitch notes (windows overlapping,
   e.g. unisons or fast repeated notes) receives the bonus: moving an event
   closer to one note can let an earlier note take it, shifting up to one
   bonus (0.1 per note) between them. Property tests assert monotonicity for
   unambiguous events (the event inside exactly one same-pitch window) and
   skip the ambiguous corner; the swing is bounded by the bonus amount.
7. **Star thresholds** — 60/80/95 are plan §6 defaults; remote config will
   tune them from session data.