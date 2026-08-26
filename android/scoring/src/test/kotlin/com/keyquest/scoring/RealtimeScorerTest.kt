package com.keyquest.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RealtimeScorer tests (plan §24 P1.6.5/8/9): the incremental freeze
 * semantics — running-max close deadlines, tentative vs. frozen verdicts,
 * batch-exact [finalize], non-monotone frozen-score math, canonical-order
 * snapshot alignment, input guards, and empty-expected behavior.
 *
 * Unless noted, sessions use the constant 120 bpm map and the 4/4 no-pickup
 * measure mapper; notes sit at beats 1/3/5 -> expected times 0.5/1.5/2.5 s
 * with closes 0.68/1.68/2.68 s (close = t + 0.18 s at 120 bpm).
 */
class RealtimeScorerTest {

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

    private fun realtime(
        expected: List<ExpectedNote>,
        config: ScoreConfig = ScoreConfig(),
        tempoMap: TempoMap = tempo120,
        measureMapper: MeasureMapper = mapper(),
    ): RealtimeScorer = RealtimeScorer(expected, config, tempoMap, measureMapper)

    // ------------------------------------------------------------------
    // freeze semantics
    // ------------------------------------------------------------------

    @Test
    fun perfectHitsFreezeInOrder() {
        val rt = realtime(listOf(note(60, 1.0), note(64, 3.0), note(67, 5.0)))
        rt.onEvent(event(60, 0.5))
        rt.onEvent(event(64, 1.5))
        val snap = rt.onEvent(event(67, 2.5))
        // Watermark 2.5 s < close(2) = 2.68 s keeps note 2 open, but the
        // running max has already frozen the prefix notes 0-1 (2.5 s >
        // 0.68/1.68): the frozen set is always a prefix.
        assertEquals(2, snap.frozenCount)
        assertEquals(listOf(Verdict.PERFECT, Verdict.PERFECT, null), snap.frozenVerdicts)
        assertEquals(listOf(Verdict.PERFECT, Verdict.PERFECT, Verdict.PERFECT), snap.tentativeVerdicts)
        assertEquals(100.0, snap.tentativeScore, 1e-9)
        assertEquals(3, snap.tentativeStars)
        assertEquals(100.0, snap.frozenScore, 1e-9)
        assertEquals(listOf<Int?>(60, 64, 67), snap.matchedPitches)
        assertEquals(listOf<Double?>(0.0, 0.0, 0.0), snap.deviationMs)
        val after = rt.tick(2.7)
        assertEquals(3, after.frozenCount)
        assertEquals(listOf(Verdict.PERFECT, Verdict.PERFECT, Verdict.PERFECT), after.frozenVerdicts)
        assertEquals(100.0, after.frozenScore, 1e-9)
    }

    @Test
    fun tickFreezesWithoutChangingTentative() {
        val rt = realtime(listOf(note(60, 1.0), note(64, 3.0), note(67, 5.0)))
        rt.onEvent(event(60, 0.5))
        rt.onEvent(event(64, 1.5))
        val snapshot1 = rt.onEvent(event(67, 2.5))
        assertEquals(100.0, snapshot1.tentativeScore, 1e-9)
        // The events' own watermark already froze notes 0-1; note 2 stays
        // open until a tick passes its running-max close 2.68 s.
        assertEquals(2, snapshot1.frozenCount)
        // A tick below the event watermark is a no-op for the frozen set.
        val snapshot2 = rt.tick(0.6)
        assertEquals(2, snapshot2.frozenCount)
        // Ticks never recompute the tentative: verdicts change only on events.
        assertEquals(snapshot1.tentativeVerdicts, snapshot2.tentativeVerdicts)
        val snapshot3 = rt.tick(3.0)
        assertEquals(3, snapshot3.frozenCount)
        assertEquals(snapshot3.tentativeVerdicts, snapshot3.frozenVerdicts)
        assertEquals(snapshot3.tentativeScore, snapshot3.frozenScore, 0.0)
    }

    @Test
    fun onEventAdvancesWatermark() {
        // A single event's timestamp advances the watermark on its own: no
        // tick is needed to freeze. Note 0 (beat 1 -> 0.5 s) closes at
        // 0.68 s; the event at 1.0 s is past the close and outside the
        // window [0.38, 0.68], so it freezes as MISSED.
        val rt = realtime(listOf(note(60, 1.0), note(64, 3.0), note(67, 5.0)))
        val snap = rt.onEvent(event(60, 1.0))
        assertEquals(1, snap.frozenCount) // 1.0 > close(0) = 0.68; 1.68/2.68 open
        assertEquals(Verdict.MISSED, snap.frozenVerdicts[0])
    }

    @Test
    fun frozenNeverFlipsAfterClose() {
        val rt = realtime(listOf(note(60, 0.0)))
        val frozen = rt.tick(0.7) // past close(0) = 0.18 s, no events -> MISSED
        assertEquals(1, frozen.frozenCount)
        assertEquals(Verdict.MISSED, frozen.frozenVerdicts[0])
        // Late event (0.9 s > close, outside the window [-0.12, 0.18]) arrives
        // after the note froze: the frozen verdict never flips.
        val snap = rt.onEvent(event(60, 0.9))
        assertEquals(Verdict.MISSED, snap.frozenVerdicts[0])
        assertEquals(Verdict.MISSED, snap.tentativeVerdicts[0])
        assertEquals(Verdict.MISSED, rt.finalize().outcomes[0].verdict)
    }

    @Test
    fun tempoInversionRunningMaxFreeze() {
        // plan §24 regression: a step 60 -> 240 bpm at beat 8 inverts the
        // closes — N (beat 8.01, close 8.0925 s) closes BEFORE M (beat 7.9,
        // close 8.26 s). The wrong-pitch event at 8.10 s is OUTSIDE N's
        // window [7.9425, 8.0925], so N is MISSED; a naive per-note close
        // would freeze N at watermark > 8.0925 while M is still open,
        // leaving the NON-PREFIX frozen set {N} with a hole. The running
        // max keeps N's deadline at 8.26 s, so the frozen set is always a
        // prefix [0..frozenCount) — frozenCount == 0 at watermark 8.10 is
        // exactly that invariant.
        val tempoMap = TempoMap(
            listOf(
                TempoPoint(atBeat = 0.0, bpm = 60.0),
                TempoPoint(atBeat = 8.0, bpm = 240.0),
            )
        )
        val expected = listOf(note(60, 7.9), note(64, 8.01))
        val events = listOf(event(65, 8.10))
        val rt = realtime(expected, tempoMap = tempoMap)
        val snap = rt.onEvent(events[0])
        assertEquals(0, snap.frozenCount) // 8.10 < running max 8.26
        assertEquals(listOf(Verdict.WRONG, Verdict.MISSED), snap.tentativeVerdicts)
        val frozen = rt.tick(8.30)
        assertEquals(2, frozen.frozenCount)
        assertEquals(listOf(Verdict.WRONG, Verdict.MISSED), frozen.frozenVerdicts)
        // finalize is exactly the batch result over the same inputs.
        assertEquals(
            Scorer(ScoreConfig(), tempoMap, mapper()).score(expected, events, StarThresholds()),
            rt.finalize(),
        )
    }

    @Test
    fun eventAtNominalCloseRoundsOutsideAndFreezesConsistently() {
        // Boundary: an event at the NOMINAL close rounds OUTSIDE the window.
        // close(beat 1.0) = 0.5 + 0.18 = 0.6799999999999999 (double, rounds
        // down), while the event (0.68 s -> 680000000 ns -> 680000000/1e9 =
        // 0.68) rounds UP — last-ulp rounding. The Matcher's inclusive
        // `<= windowEnd` (Matcher.kt:76) therefore excludes the event
        // (MISSED), and RealtimeScorer's strict `watermark > close` freezes
        // it: the two are complementary on the SAME doubles, so the frozen
        // verdict equals the batch verdict exactly at the boundary.
        val rt = realtime(listOf(note(60, 1.0))) // beat 1 -> 0.5 s, close 0.68 s
        val snap = rt.onEvent(event(60, 0.68))
        assertEquals(1, snap.frozenCount) // watermark 0.68 > close 0.6799999999999999
        assertEquals(listOf(Verdict.MISSED), snap.tentativeVerdicts)
        assertEquals(listOf(Verdict.MISSED), snap.frozenVerdicts)
        assertEquals(Verdict.MISSED, rt.finalize().outcomes[0].verdict)
    }

    @Test
    fun eventJustInsideCloseStaysOpenUntilTick() {
        // Boundary: 0.679 < 0.6799999999999999, so the event is INSIDE the
        // window (deviation 179 ms > perfectBand 50 ms -> GOOD), and the
        // strict freeze rule (watermark > close) keeps the note open at
        // 0.679 s — only the next tick past the close freezes it.
        val rt = realtime(listOf(note(60, 1.0))) // beat 1 -> 0.5 s, close 0.68 s
        val snap = rt.onEvent(event(60, 0.679))
        assertEquals(0, snap.frozenCount) // 0.679 not > close
        assertEquals(listOf(Verdict.GOOD), snap.tentativeVerdicts)
        val frozen = rt.tick(0.69)
        assertEquals(1, frozen.frozenCount)
        assertEquals(listOf(Verdict.GOOD), frozen.frozenVerdicts)
        assertEquals(100.0, frozen.frozenScore, 1e-9)
        // The boundary event was consumed by the match, not an extra.
        assertEquals(Verdict.GOOD, rt.finalize().outcomes[0].verdict)
    }

    @Test
    fun finalizeEqualsBatchExactly() {
        val expected = listOf(note(60, 0.0), note(64, 1.0), note(67, 2.0))
        val events = listOf(event(60, 0.0), event(65, 0.5), event(67, 1.1))
        val rt = realtime(expected)
        rt.onEvent(events[0])
        rt.tick(0.2)
        rt.onEvent(events[1])
        rt.tick(0.8)
        rt.onEvent(events[2])
        rt.tick(1.5)
        // PERFECT (bonus), wrong pitch, +100 ms late GOOD -> 100 * 2.1 / 3.
        val report = rt.finalize()
        assertEquals(listOf(Verdict.PERFECT, Verdict.WRONG, Verdict.GOOD), report.outcomes.map { it.verdict })
        assertEquals(100.0 * 2.1 / 3.0, report.score, 0.001)
        assertEquals(1, report.stars)
        assertEquals(
            Scorer(ScoreConfig(), tempo120, mapper()).score(expected, events, StarThresholds()),
            report,
        )
    }

    // ------------------------------------------------------------------
    // frozen-score math
    // ------------------------------------------------------------------

    @Test
    fun frozenScoreMathAndNonMonotonicity() {
        // Weights [1.0, 0.2, 1.0] at beats 1/3/5 (times 0.5/1.5/2.5 s). The
        // 64 event arrives FIRST at 0.3 s: it is outside note 1's window
        // [1.38, 1.68] (close 1.68) so note 1 is MISSED, while the watermark
        // stays at 0.5 s — below note 0's close 0.68 — until the first tick.
        val rt = realtime(listOf(note(60, 1.0), note(64, 3.0, weight = 0.2), note(67, 5.0)))
        rt.onEvent(event(64, 0.3))
        rt.onEvent(event(60, 0.5)) // PERFECT, deviation 0
        var snap = rt.tick(0.7)
        // Note 0 freezes PERFECT: 100 * 1.1 / 1.0 capped at 100.
        assertEquals(100.0, snap.frozenScore, 1e-9)
        snap = rt.tick(1.8)
        // Note 1 freezes MISSED: the denominator grows while the numerator
        // stays, so the frozen score DROPS — frozenScore is NOT monotone.
        assertEquals(2, snap.frozenCount)
        assertEquals(100.0 * 1.1 / 1.2, snap.frozenScore, 0.001)
        snap = rt.onEvent(event(67, 2.6)) // +100 ms -> GOOD, no bonus
        snap = rt.tick(2.7)
        assertEquals(100.0 * (1.1 + 1.0) / 2.2, snap.frozenScore, 0.001)
        assertEquals(listOf(Verdict.PERFECT, Verdict.MISSED, Verdict.GOOD), snap.frozenVerdicts)
    }

    // ------------------------------------------------------------------
    // empty input, alignment, and guards
    // ------------------------------------------------------------------

    @Test
    fun emptyExpectedValid() {
        val rt = realtime(emptyList())
        rt.onEvent(event(60, 0.5))
        val snap = rt.tick(5.0)
        assertEquals(0, snap.frozenCount)
        assertTrue(snap.frozenVerdicts.isEmpty())
        assertTrue(snap.tentativeVerdicts.isEmpty())
        assertTrue(snap.matchedPitches.isEmpty())
        assertTrue(snap.deviationMs.isEmpty())
        assertEquals(0.0, snap.tentativeScore, 0.0)
        assertEquals(0.0, snap.frozenScore, 0.0)
        assertEquals(0, snap.tentativeStars)
        val report = rt.finalize()
        assertEquals(0.0, report.score, 0.0)
        assertEquals(1, report.extraEvents.size)
    }

    @Test
    fun snapshotAlignsToCanonicalOrder() {
        // Input order is REVERSED; the snapshot aligns to canonical order
        // (startBeat asc), so index 0 is the beat-0 note.
        val rt = realtime(listOf(note(64, 1.0), note(60, 0.0)))
        val snap = rt.onEvent(event(60, 0.0))
        assertEquals(Verdict.PERFECT, snap.tentativeVerdicts[0])
        assertEquals(60, snap.matchedPitches[0])
        assertEquals(Verdict.MISSED, snap.tentativeVerdicts[1])
        assertEquals(null, snap.matchedPitches[1])
    }

    @Test
    fun outOfOrderEventRejected() {
        val rt = realtime(listOf(note(60, 0.0), note(64, 1.0)))
        assertThrows(IllegalArgumentException::class.java) {
            rt.onEvent(event(60, 1.0))
            rt.onEvent(event(64, 0.5))
        }
    }

    @Test
    fun backwardsTickRejected() {
        val rt = realtime(listOf(note(60, 0.0)))
        assertThrows(IllegalArgumentException::class.java) {
            rt.tick(1.0)
            rt.tick(0.5)
        }
    }

    @Test
    fun invalidSnapshotRejected() {
        val valid = Snapshot(
            frozenVerdicts = listOf(Verdict.PERFECT),
            tentativeVerdicts = listOf(Verdict.PERFECT),
            matchedPitches = listOf(60),
            deviationMs = listOf(0.0),
            frozenCount = 0,
            tentativeScore = 100.0,
            frozenScore = 100.0,
            tentativeStars = 3,
        )
        // frozen and tentative verdict lists must have equal size.
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(tentativeVerdicts = listOf(Verdict.PERFECT, Verdict.PERFECT))
        }
        // Telemetry lists must have equal size too.
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(matchedPitches = listOf(60, 60))
        }
        // frozenCount must be in 0..noteCount.
        assertThrows(IllegalArgumentException::class.java) { valid.copy(frozenCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(frozenCount = 2) }
        // Scores must be finite and in [0, 100].
        assertThrows(IllegalArgumentException::class.java) { valid.copy(tentativeScore = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(frozenScore = 101.0) }
        // Stars must be in 0..3.
        assertThrows(IllegalArgumentException::class.java) { valid.copy(tentativeStars = 4) }
    }
}