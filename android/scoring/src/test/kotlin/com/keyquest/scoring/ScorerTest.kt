package com.keyquest.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scorer tests (plan §6, §20 P1.5.3): score/stars math, weight aggregation,
 * PERFECT bonus cap, chord partial credit, per-measure heatmap, extras, and
 * determinism. All sessions use a constant 120 bpm tempo map and a 4/4
 * no-pickup measure mapper unless noted.
 */
class ScorerTest {

    private val tempo120 = TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = 120.0)))

    private fun mapper(duration: Double = 32.0): MeasureMapper = MeasureMapper(
        signatures = listOf(TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4)),
        pickupBeats = 0.0,
        durationBeats = duration,
    )

    private fun note(pitch: Int, startBeat: Double, weight: Double = 1.0): ExpectedNote =
        ExpectedNote(pitch = pitch, startBeat = startBeat, durBeats = 1.0, hand = Hand.R, scoringWeight = weight)

    private fun event(pitch: Int, seconds: Double): PlayedNote =
        PlayedNote(pitch = pitch, velocity = 100, onTimeNs = (seconds * 1e9).toLong(), offTimeNs = -1)

    private fun scorer(
        config: ScoreConfig = ScoreConfig(),
        tempoMap: TempoMap = tempo120,
        measureMapper: MeasureMapper = mapper(),
    ): Scorer = Scorer(config, tempoMap, measureMapper)

    // ------------------------------------------------------------------
    // score and stars
    // ------------------------------------------------------------------

    @Test
    fun allPerfectScoresOneHundredCapped() {
        val report = scorer().score(
            listOf(note(60, 0.0), note(64, 1.0), note(67, 2.0)),
            listOf(event(60, 0.0), event(64, 0.5), event(67, 1.0)),
        )
        // 3 * (1 + 0.1) / 3 = 1.1 -> capped at 100, never 110.
        assertEquals(100.0, report.score, 1e-9)
        assertEquals(3, report.stars)
        assertEquals(3, report.perfectCount)
        assertEquals(3, report.matchedCount)
        assertEquals(0.0, report.missedCount.toDouble(), 0.0)
    }

    @Test
    fun allGoodScoresOneHundredWithoutBonus() {
        val report = scorer().score(
            listOf(note(60, 0.0), note(64, 1.0)),
            listOf(event(60, 0.06), event(64, 0.56)), // +60 ms: GOOD, no bonus
        )
        assertEquals(100.0, report.score, 1e-9)
        assertEquals(3, report.stars)
        assertEquals(2, report.goodCount)
        assertEquals(2.0, report.hitWeight, 1e-9)
    }

    @Test
    fun mixedHitAndMissWithWeights() {
        // 2 notes weight 1 hit (GOOD, no bonus), 1 note weight 0.2 missed
        // -> 100 * 2 / 2.2 = 90.91.
        val report = scorer().score(
            listOf(note(60, 0.0), note(64, 1.0), note(67, 2.0, weight = 0.2)),
            listOf(event(60, 0.06), event(64, 0.56)),
        )
        assertEquals(100.0 * 2.0 / 2.2, report.score, 0.001)
        assertEquals(2, report.stars)
        assertEquals(2.2, report.totalWeight, 1e-9)
        assertEquals(2.0, report.hitWeight, 1e-9)
        assertEquals(1, report.missedCount)
    }

    @Test
    fun starBoundariesFollowTheThresholds() {
        // Two notes [w, 1-w]; hitting only the first (GOOD) scores 100*w.
        fun scoreFor(weight: Double): ScoreReport =
            scorer().score(
                listOf(note(60, 0.0, weight = weight), note(64, 1.0, weight = 1.0 - weight)),
                listOf(event(60, 0.06)),
            )
        assertEquals(0, scoreFor(0.599).stars) // 59.9
        assertEquals(1, scoreFor(0.6).stars) // 60
        assertEquals(2, scoreFor(0.8).stars) // 80
        assertEquals(3, scoreFor(0.95).stars) // 95
    }

    @Test
    fun customStarThresholdsAreParameterized() {
        // Remote-config-style thresholds from the app (plan §6).
        val thresholds = StarThresholds(oneStar = 50.0, twoStar = 70.0, threeStar = 90.0)
        fun starsFor(weight: Double): Int = scorer().score(
            listOf(note(60, 0.0, weight = weight), note(64, 1.0, weight = 1.0 - weight)),
            listOf(event(60, 0.06)),
            thresholds,
        ).stars
        assertEquals(1, starsFor(0.6)) // 60
        assertEquals(2, starsFor(0.75)) // 75
        assertEquals(3, starsFor(0.92)) // 92
        assertEquals(0, starsFor(0.4)) // 40
    }

    @Test
    fun emptyExpectedYieldsZeroScoreWithAllEventsAsSortedExtras() {
        val report = scorer().score(emptyList(), listOf(event(72, 10.0), event(60, 0.0)))
        assertEquals(0.0, report.score, 0.0)
        assertFalse(report.score.isNaN())
        assertEquals(0, report.stars)
        assertTrue(report.outcomes.isEmpty())
        assertTrue(report.chordOutcomes.isEmpty())
        assertTrue(report.measureHeatmap.isEmpty())
        // With nothing expected, every played event is an unconsumed extra,
        // in the Matcher's deterministic (onTimeNs, pitch, velocity) order.
        assertEquals(listOf(event(60, 0.0), event(72, 10.0)), report.extraEvents)
        assertEquals(0.0, report.totalWeight, 0.0)
        assertEquals(0.0, report.hitWeight, 0.0)
    }

    @Test
    fun emptyEventsScoreZeroWithEverythingMissed() {
        val report = scorer().score(listOf(note(60, 0.0), note(64, 1.0)), emptyList())
        assertEquals(0.0, report.score, 0.0)
        assertEquals(0, report.stars)
        assertEquals(2, report.missedCount)
        assertEquals(0, report.matchedCount)
    }

    @Test
    fun zeroTotalWeightGraceNotesScoreZeroWithoutNaN() {
        // Grace notes carry scoringWeight 0 (songpack-v1.md §3.2).
        val report = scorer().score(listOf(note(60, 0.0, weight = 0.0)), emptyList())
        assertEquals(0.0, report.score, 0.0)
        assertFalse(report.score.isNaN())
        assertEquals(0, report.stars)
        assertEquals(0.0, report.totalWeight, 0.0)
    }

    // ------------------------------------------------------------------
    // chords
    // ------------------------------------------------------------------

    @Test
    fun fullChordGetsFullCredit() {
        val report = scorer().score(
            listOf(note(60, 0.0), note(64, 0.0), note(67, 0.0)),
            listOf(event(60, 0.0), event(64, 0.0), event(67, 0.0)),
        )
        assertEquals(100.0, report.score, 1e-9)
        assertEquals(1, report.chordOutcomes.size)
        assertEquals(ChordStatus.FULL, report.chordOutcomes[0].outcome)
        assertEquals(3, report.chordOutcomes[0].tonesHit)
        assertEquals(3, report.chordOutcomes[0].notes.size)
    }

    @Test
    fun partialChordGetsPartialCredit() {
        // 3-tone chord, 2 GOOD hits -> 100 * 2 / 3, chord PARTIAL.
        val report = scorer().score(
            listOf(note(60, 0.0), note(64, 0.0), note(67, 0.0)),
            listOf(event(60, 0.06), event(67, 0.06)),
        )
        assertEquals(100.0 * 2.0 / 3.0, report.score, 0.001)
        assertEquals(1, report.stars)
        assertEquals(ChordStatus.PARTIAL, report.chordOutcomes[0].outcome)
        assertEquals(2, report.chordOutcomes[0].tonesHit)
        assertEquals(3, report.chordOutcomes[0].tonesTotal)
    }

    @Test
    fun missedChordScoresNothing() {
        val report = scorer().score(
            listOf(note(60, 0.0), note(64, 0.0), note(67, 0.0)),
            emptyList(),
        )
        assertEquals(0.0, report.score, 0.0)
        assertEquals(ChordStatus.MISSED, report.chordOutcomes[0].outcome)
        assertEquals(0, report.chordOutcomes[0].tonesHit)
    }

    @Test
    fun wrongPitchInAChordClusterCountsAsWrongNotMissed() {
        // C4 + E4 + G4; player plays C4, E-flat4 (wrong for E4), G4.
        val report = scorer().score(
            listOf(note(60, 0.0), note(64, 0.0), note(67, 0.0)),
            listOf(event(60, 0.0), event(63, 0.0), event(67, 0.0)),
        )
        assertEquals(Verdict.WRONG, report.outcomes[1].verdict)
        assertEquals(ChordStatus.PARTIAL, report.chordOutcomes[0].outcome)
        assertEquals(2, report.chordOutcomes[0].tonesHit)
        assertEquals(1, report.wrongCount)
        assertEquals(1, report.extraEvents.size)
        assertEquals(63, report.extraEvents[0].pitch)
        // 2 PERFECT hits carry the 0.10 bonus: 100 * 2.2 / 3 = 73.33.
        assertEquals(100.0 * 2.2 / 3.0, report.score, 0.001)
    }

    // ------------------------------------------------------------------
    // telemetry
    // ------------------------------------------------------------------

    @Test
    fun perMeasureHeatmapAggregatesMissedAndWrongSortedByMeasure() {
        // Beat 0-3 -> measure 1, 4-7 -> 2, 8-11 -> 3, 12-15 -> 4.
        val report = scorer().score(
            listOf(note(60, 0.0), note(62, 4.0), note(64, 8.0), note(65, 12.0)),
            listOf(event(65, 6.06), event(63, 4.0)), // F5 wrong for E5@8; F5@12 GOOD
        )
        assertEquals(Verdict.MISSED, report.outcomes[0].verdict)
        assertEquals(Verdict.MISSED, report.outcomes[1].verdict)
        assertEquals(Verdict.WRONG, report.outcomes[2].verdict)
        assertEquals(Verdict.GOOD, report.outcomes[3].verdict)
        assertEquals(
            mapOf(1 to MeasureErrorSummary(missed = 1), 2 to MeasureErrorSummary(missed = 1), 3 to MeasureErrorSummary(wrong = 1)),
            report.measureHeatmap,
        )
        // Heatmap totals agree with the verdict counts.
        val heatmapTotal = report.measureHeatmap.values.sumOf { it.missed + it.wrong }
        assertEquals(report.missedCount + report.wrongCount, heatmapTotal)
        assertEquals(listOf(1, 2, 3), report.measureHeatmap.keys.toList())
    }

    @Test
    fun extraEventsAreReported() {
        val report = scorer().score(
            listOf(note(60, 0.0)),
            listOf(event(60, 0.0), event(72, 10.0), event(61, 0.05)),
        )
        // 72@10 s is far outside any window; 61@0.05 s is a wrong pitch in
        // the window (C5's note) but never consumed.
        assertEquals(Verdict.PERFECT, report.outcomes[0].verdict)
        assertEquals(2, report.extraEvents.size)
        assertEquals(listOf(61, 72), report.extraEvents.map { it.pitch })
    }

    @Test
    fun outcomesAreInCanonicalExpectedOrder() {
        val report = scorer().score(
            listOf(note(67, 1.0), note(60, 0.0), note(64, 1.0)),
            listOf(event(60, 0.0), event(64, 0.5), event(67, 0.5)),
        )
        // Canonical order: 60@0, 64@1, 67@1 (pitch asc within the same beat).
        assertEquals(listOf(60, 64, 67), report.outcomes.map { it.matchedPitch!! })
        assertEquals(listOf(0, 1, 2), report.outcomes.map { it.expectedIndex })
    }

    // ------------------------------------------------------------------
    // determinism and generated methods
    // ------------------------------------------------------------------

    @Test
    fun identicalInputsProduceIdenticalReports() {
        val expected = listOf(note(60, 0.0), note(64, 1.0), note(67, 2.0))
        val events = listOf(event(60, 0.0), event(64, 0.5), event(67, 1.0))
        val first = scorer().score(expected, events)
        val second = scorer().score(expected, events)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first.toString().contains("stars="))
        val (score, stars) = first
        assertEquals(first.score, score, 0.0)
        assertEquals(first.stars, stars)
    }

    // ------------------------------------------------------------------
    // constructor guards
    // ------------------------------------------------------------------

    @Test
    fun scoreConfigRejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { ScoreConfig(refBpm = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ScoreConfig(baseEarlyMs = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ScoreConfig(baseLateMs = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { ScoreConfig(windowScaleClampMin = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ScoreConfig(windowScaleClampMax = 0.4, windowScaleClampMin = 0.5) }
        assertThrows(IllegalArgumentException::class.java) { ScoreConfig(perfectBandMs = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ScoreConfig(perfectBonus = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { ScoreConfig(chordClusterMs = 0.0) }
    }

    @Test
    fun scoreConfigExposesItsTunableDefaults() {
        val config = ScoreConfig()
        assertEquals(120.0, config.refBpm, 0.0)
        assertEquals(120.0, config.baseEarlyMs, 0.0)
        assertEquals(180.0, config.baseLateMs, 0.0)
        assertEquals(false, config.beginner)
        assertEquals(250.0, config.beginnerEarlyMs, 0.0)
        assertEquals(250.0, config.beginnerLateMs, 0.0)
        assertEquals(0.5, config.windowScaleClampMin, 0.0)
        assertEquals(2.0, config.windowScaleClampMax, 0.0)
        assertEquals(50.0, config.perfectBandMs, 0.0)
        assertEquals(0.10, config.perfectBonus, 0.0)
        assertEquals(90.0, config.chordClusterMs, 0.0)
    }

    @Test
    fun starThresholdsRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { StarThresholds(oneStar = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { StarThresholds(oneStar = 101.0) }
        assertThrows(IllegalArgumentException::class.java) { StarThresholds(oneStar = 70.0, twoStar = 60.0) }
        assertThrows(IllegalArgumentException::class.java) { StarThresholds(twoStar = 95.0, threeStar = 80.0) }
    }

    @Test
    fun playedNoteRejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { PlayedNote(pitch = -1, velocity = 100, onTimeNs = 0, offTimeNs = -1) }
        assertThrows(IllegalArgumentException::class.java) { PlayedNote(pitch = 128, velocity = 100, onTimeNs = 0, offTimeNs = -1) }
        assertThrows(IllegalArgumentException::class.java) { PlayedNote(pitch = 60, velocity = 128, onTimeNs = 0, offTimeNs = -1) }
        assertThrows(IllegalArgumentException::class.java) { PlayedNote(pitch = 60, velocity = 100, onTimeNs = -5, offTimeNs = -1) }
        assertThrows(IllegalArgumentException::class.java) { PlayedNote(pitch = 60, velocity = 100, onTimeNs = 10, offTimeNs = 5) }
    }

    @Test
    fun expectedNoteRejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { ExpectedNote(pitch = -1, startBeat = 0.0, durBeats = 1.0, hand = Hand.R) }
        assertThrows(IllegalArgumentException::class.java) { ExpectedNote(pitch = 60, startBeat = -0.5, durBeats = 1.0, hand = Hand.R) }
        assertThrows(IllegalArgumentException::class.java) { ExpectedNote(pitch = 60, startBeat = 0.0, durBeats = 0.0, hand = Hand.R) }
        assertThrows(IllegalArgumentException::class.java) { ExpectedNote(pitch = 60, startBeat = 0.0, durBeats = 1.0, hand = Hand.R, scoringWeight = -1.0) }
    }
}