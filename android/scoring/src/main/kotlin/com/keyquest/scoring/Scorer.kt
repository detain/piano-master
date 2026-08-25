package com.keyquest.scoring

/**
 * The scorer orchestrator (plan §6, §20 P1.5.3): cluster -> match -> aggregate.
 *
 * Pure and stateless: all inputs are constructor values (immutable), all
 * outputs live in the returned [ScoreReport]. Same inputs, same report.
 *
 * Score math (plan §6):
 *   hitWeight   = sum over matched notes of scoringWeight * (1 + perfectBonus
 *                 when PERFECT, else 1)      — PERFECT carries the timing
 *                 bonus; GOOD is full credit.
 *   totalWeight = sum of scoringWeight over ALL expected notes.
 *   score       = totalWeight == 0 ? 0.0 : min(100.0, 100.0 * hitWeight /
 *                 totalWeight)               — explicit branch: never NaN,
 *                 never > 100.
 *   stars       = number of StarThresholds met (0..3; remote-config tunable,
 *                 passed per call).
 *
 * Telemetry (plan §20 P1.5.4 — emitted now, consumed by the workout
 * generator in Phase 2):
 *   - per-measure error heatmap from MISSED/WRONG notes, keyed by
 *     [MeasureMapper.measureIndex], sorted by measure;
 *   - chord outcomes per cluster (FULL / PARTIAL / MISSED);
 *   - extra events (everything never consumed by a match).
 *
 * Empty input is valid: no expected notes -> score 0.0, 0 stars, no NaN,
 * every played event reported as an unconsumed extra. No played events ->
 * every note MISSED (no events means no wrong-pitch events).
 *
 * @property config scoring configuration (windows, beginner, bonus, ...).
 * @property tempoMap beat -> seconds conversion (step/linear curves).
 * @property measureMapper beat -> measure index for the heatmap.
 */
class Scorer(
    private val config: ScoreConfig,
    private val tempoMap: TempoMap,
    private val measureMapper: MeasureMapper,
) {

    /**
     * Scores one pass over a chunk.
     *
     * @param expected the chunk's expected notes (SongPack canonical order);
     *   may be empty.
     * @param events the played event stream; may be empty or in any order.
     * @param thresholds star thresholds (remote-config tunable).
     */
    fun score(
        expected: List<ExpectedNote>,
        events: List<PlayedNote>,
        thresholds: StarThresholds = StarThresholds(),
    ): ScoreReport {
        if (expected.isEmpty()) {
            return ScoreReport(
                score = 0.0,
                stars = 0,
                outcomes = emptyList(),
                chordOutcomes = emptyList(),
                measureHeatmap = emptyMap(),
                extraEvents = events.sortedWith(Matcher.EVENT_COMPARATOR),
                totalWeight = 0.0,
                hitWeight = 0.0,
                perfectCount = 0,
                goodCount = 0,
                missedCount = 0,
                wrongCount = 0,
                matchedCount = 0,
            )
        }

        // Canonical order once, shared by the clusterer and the matcher, so
        // the two agree on note order and outcomes line up with clusters.
        val canonical = expected.sortedWith(ChordClusterer.COMPARATOR)

        val clusters = ChordClusterer(config, tempoMap).cluster(canonical)
        val matchResult = Matcher(config, tempoMap).match(canonical, events)
        val outcomes = matchResult.outcomes

        // --- weight aggregation --------------------------------------------
        var hitWeight = 0.0
        var totalWeight = 0.0
        var perfectCount = 0
        var goodCount = 0
        var missedCount = 0
        var wrongCount = 0
        for ((index, outcome) in outcomes.withIndex()) {
            val weight = canonical[index].scoringWeight
            totalWeight += weight
            when (outcome.verdict) {
                Verdict.PERFECT -> {
                    hitWeight += weight * (1.0 + config.perfectBonus)
                    perfectCount++
                }
                Verdict.GOOD -> {
                    hitWeight += weight
                    goodCount++
                }
                Verdict.MISSED -> missedCount++
                Verdict.WRONG -> wrongCount++
            }
        }
        val matchedCount = perfectCount + goodCount
        val score = if (totalWeight == 0.0) {
            0.0
        } else {
            minOf(100.0, 100.0 * hitWeight / totalWeight)
        }

        // --- chord outcomes -------------------------------------------------
        // Clusters and outcomes are both in canonical note order, so each
        // cluster's outcomes are a contiguous run of `notes.size` entries.
        val chordOutcomes = mutableListOf<ChordOutcome>()
        var outcomeCursor = 0
        for (cluster in clusters) {
            val tonesHit = (0 until cluster.notes.size).count { offset ->
                val outcome = outcomes[outcomeCursor + offset]
                outcome.verdict == Verdict.PERFECT || outcome.verdict == Verdict.GOOD
            }
            outcomeCursor += cluster.notes.size
            val status = when {
                tonesHit == cluster.notes.size -> ChordStatus.FULL
                tonesHit > 0 -> ChordStatus.PARTIAL
                else -> ChordStatus.MISSED
            }
            chordOutcomes.add(ChordOutcome(cluster.notes, tonesHit, cluster.notes.size, status))
        }

        // --- per-measure error heatmap (sorted by measure index) -----------
        val heatmap = sortedMapOf<Int, MeasureErrorSummary>()
        for ((index, outcome) in outcomes.withIndex()) {
            if (outcome.verdict != Verdict.MISSED && outcome.verdict != Verdict.WRONG) continue
            val measure = measureMapper.measureIndex(canonical[index].startBeat)
            val summary = heatmap.getOrPut(measure) { MeasureErrorSummary() }
            // Only MISSED and WRONG reach here (the continue above filters the rest).
            if (outcome.verdict == Verdict.MISSED) {
                heatmap[measure] = summary.copy(missed = summary.missed + 1)
            } else {
                heatmap[measure] = summary.copy(wrong = summary.wrong + 1)
            }
        }

        // --- stars -----------------------------------------------------------
        val stars = when {
            score >= thresholds.threeStar -> 3
            score >= thresholds.twoStar -> 2
            score >= thresholds.oneStar -> 1
            else -> 0
        }

        return ScoreReport(
            score = score,
            stars = stars,
            outcomes = outcomes,
            chordOutcomes = chordOutcomes,
            measureHeatmap = heatmap,
            extraEvents = matchResult.extraEvents,
            totalWeight = totalWeight,
            hitWeight = hitWeight,
            perfectCount = perfectCount,
            goodCount = goodCount,
            missedCount = missedCount,
            wrongCount = wrongCount,
            matchedCount = matchedCount,
        )
    }
}