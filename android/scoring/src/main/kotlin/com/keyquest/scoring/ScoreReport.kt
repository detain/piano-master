package com.keyquest.scoring

/**
 * Per-note matching outcome (plan §6 verdicts, §20 P1.5.3).
 *
 * @property expectedIndex index of the note in the CANONICAL expected order
 *   (startBeat asc, R before L, pitch asc). When the caller passes notes
 *   already in SongPack canonical order — which the app guarantees by
 *   construction — this equals the SongPack `notes.json` array index that
 *   `tieToIndex` references.
 * @property verdict PERFECT / GOOD / MISSED / WRONG (see [Verdict]).
 * @property matchedEventIndex index into the Matcher's internally sorted
 *   event list, or null when nothing matched. Distinct outcomes never share
 *   an index (an event is consumed at most once).
 * @property deviationMs event time minus expected time, in milliseconds; the
 *   sign is negative for early, positive for late. Null when unmatched.
 * @property matchedPitch pitch of the matched event (equals the note's
 *   pitch); null when unmatched.
 */
data class NoteOutcome(
    val expectedIndex: Int,
    val verdict: Verdict,
    val matchedEventIndex: Int? = null,
    val deviationMs: Double? = null,
    val matchedPitch: Int? = null,
)

/**
 * Aggregated errors for one measure of the per-measure error heatmap
 * (plan §6, §20 P1.5.4 — feeds the 5-Min Workout generator, §9.3).
 */
data class MeasureErrorSummary(
    val missed: Int = 0,
    val wrong: Int = 0,
)

/** Chord outcome status (plan §6: "partial chords score partial credit"). */
enum class ChordStatus { FULL, PARTIAL, MISSED }

/**
 * What happened to one chord cluster (plan §6, §20 P1.5.2).
 *
 * @property notes the cluster's expected notes, in canonical order.
 * @property tonesHit how many of the cluster's notes were matched
 *   (PERFECT or GOOD).
 * @property tonesTotal the cluster's note count.
 * @property outcome FULL when all tones were matched, PARTIAL when 1..n-1,
 *   MISSED when none.
 */
data class ChordOutcome(
    val notes: List<ExpectedNote>,
    val tonesHit: Int,
    val tonesTotal: Int,
    val outcome: ChordStatus,
)

/**
 * The full result of scoring one pass over a chunk (plan §6, §20 P1.5.3).
 *
 * Feeds analytics AND the 5-Minute Workout generator (plan §9.3) in Phase 2 —
 * the telemetry fields ([measureHeatmap], [extraEvents], chord outcomes) are
 * emitted now so there is data later (plan §20 P1.5.4).
 *
 * All lists and maps are in deterministic (sorted) order: [outcomes] in
 * canonical expected order, [chordOutcomes] in cluster-start order,
 * [measureHeatmap] sorted by measure index, [extraEvents] sorted by
 * onTimeNs then pitch then velocity.
 *
 * @property score 0..100 (never NaN, never > 100; 0 when nothing is
 *   expected or nothing is hit).
 * @property stars 0..3 from [StarThresholds].
 * @property outcomes per-note verdicts.
 * @property chordOutcomes per-cluster chord verdicts.
 * @property measureHeatmap per-measure missed/wrong counts for non-matched
 *   notes, sorted by measure index.
 * @property extraEvents every played event that was never consumed by a
 *   match (includes wrong-pitch events that caused WRONG verdicts).
 * @property totalWeight sum of all expected scoringWeights.
 * @property hitWeight sum over matched notes of scoringWeight * (1 +
 *   perfectBonus when PERFECT, else 1).
 */
data class ScoreReport(
    val score: Double,
    val stars: Int,
    val outcomes: List<NoteOutcome>,
    val chordOutcomes: List<ChordOutcome>,
    val measureHeatmap: Map<Int, MeasureErrorSummary>,
    val extraEvents: List<PlayedNote>,
    val totalWeight: Double,
    val hitWeight: Double,
    val perfectCount: Int,
    val goodCount: Int,
    val missedCount: Int,
    val wrongCount: Int,
    val matchedCount: Int,
) {
    init {
        require(score >= 0.0 && score <= 100.0 && !score.isNaN()) {
            "score must be in [0, 100] and never NaN, was $score"
        }
        require(stars in 0..3) { "stars must be in 0..3, was $stars" }
        require(totalWeight >= 0.0) { "totalWeight must be >= 0, was $totalWeight" }
        require(hitWeight >= 0.0) { "hitWeight must be >= 0, was $hitWeight" }
    }
}