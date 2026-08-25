package com.keyquest.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matcher tests (plan §6, §20 P1.5.1): window semantics, tempo scaling,
 * beginner widening, clamp, PERFECT/GOOD/MISSED/WRONG verdicts, consumption,
 * and extras. All timings are at 120 bpm (one beat = 0.5 s) unless noted.
 */
class MatcherTest {

    private fun tempoMap(bpm: Double): TempoMap = TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = bpm)))

    private fun note(pitch: Int, startBeat: Double): ExpectedNote =
        ExpectedNote(pitch = pitch, startBeat = startBeat, durBeats = 1.0, hand = Hand.R)

    private fun event(pitch: Int, onTimeNs: Long): PlayedNote =
        PlayedNote(pitch = pitch, velocity = 100, onTimeNs = onTimeNs, offTimeNs = -1)

    private fun seconds(seconds: Double): Long = (seconds * 1e9).toLong()

    private fun match(
        expected: List<ExpectedNote>,
        events: List<PlayedNote>,
        bpm: Double = 120.0,
        config: ScoreConfig = ScoreConfig(),
    ): MatchResult = Matcher(config, tempoMap(bpm)).match(expected, events)

    // ------------------------------------------------------------------
    // basic verdicts
    // ------------------------------------------------------------------

    @Test
    fun exactHitIsPerfect() {
        val result = match(listOf(note(60, 0.0)), listOf(event(60, 0L)))
        assertEquals(Verdict.PERFECT, result.outcomes[0].verdict)
        assertEquals(0.0, result.outcomes[0].deviationMs!!, 1e-6)
        assertEquals(60, result.outcomes[0].matchedPitch!!)
        assertEquals(0, result.outcomes[0].matchedEventIndex!!)
        assertEquals(0, result.outcomes[0].expectedIndex)
        assertTrue(result.extraEvents.isEmpty())
    }

    @Test
    fun lateWithinWindowIsGood() {
        val result = match(listOf(note(60, 0.0)), listOf(event(60, seconds(0.1))))
        assertEquals(Verdict.GOOD, result.outcomes[0].verdict)
        assertEquals(100.0, result.outcomes[0].deviationMs!!, 1e-6)
    }

    @Test
    fun earlyWithinWindowIsGood() {
        // Note at beat 1 (t = 0.5 s); an event 80 ms early is still in the
        // window (onTimeNs must be >= 0, so "early" is relative to a later note).
        val result = match(listOf(note(60, 1.0)), listOf(event(60, seconds(0.42))))
        assertEquals(Verdict.GOOD, result.outcomes[0].verdict)
        assertEquals(-80.0, result.outcomes[0].deviationMs!!, 1e-6)
    }

    @Test
    fun deviationInsideThePerfectBandIsPerfect() {
        val result = match(listOf(note(60, 0.0)), listOf(event(60, seconds(0.04))))
        assertEquals(Verdict.PERFECT, result.outcomes[0].verdict)
    }

    @Test
    fun deviationBeyondThePerfectBandIsGood() {
        val result = match(listOf(note(60, 0.0)), listOf(event(60, seconds(0.06))))
        assertEquals(Verdict.GOOD, result.outcomes[0].verdict)
    }

    @Test
    fun wrongPitchInWindowIsWrongAndNotConsumed() {
        val result = match(listOf(note(60, 0.0)), listOf(event(61, seconds(0.05))))
        assertEquals(Verdict.WRONG, result.outcomes[0].verdict)
        assertNull(result.outcomes[0].matchedEventIndex)
        // The wrong-pitch event is never consumed: it shows up as an extra.
        assertEquals(1, result.extraEvents.size)
        assertEquals(61, result.extraEvents[0].pitch)
    }

    @Test
    fun nothingInWindowIsMissed() {
        val result = match(listOf(note(60, 0.0)), listOf(event(60, seconds(5.0))))
        assertEquals(Verdict.MISSED, result.outcomes[0].verdict)
        assertEquals(1, result.extraEvents.size) // the far event is extra
    }

    @Test
    fun emptyEventsAreAllMissed() {
        val result = match(listOf(note(60, 0.0), note(64, 1.0)), emptyList())
        assertEquals(Verdict.MISSED, result.outcomes[0].verdict)
        assertEquals(Verdict.MISSED, result.outcomes[1].verdict)
    }

    // ------------------------------------------------------------------
    // consumption
    // ------------------------------------------------------------------

    @Test
    fun anEventIsConsumedAtMostOnce() {
        // One event, two same-pitch notes: only the first can take it.
        val result = match(listOf(note(60, 0.0), note(60, 1.0)), listOf(event(60, 0L)))
        assertEquals(Verdict.PERFECT, result.outcomes[0].verdict)
        assertEquals(Verdict.MISSED, result.outcomes[1].verdict)
        assertTrue(result.extraEvents.isEmpty())
    }

    @Test
    fun anEventMatchesTheLaterNoteWhenTheEarlierWindowMissesIt() {
        val result = match(listOf(note(60, 0.0), note(60, 1.0)), listOf(event(60, seconds(0.5))))
        assertEquals(Verdict.MISSED, result.outcomes[0].verdict)
        assertEquals(Verdict.PERFECT, result.outcomes[1].verdict)
    }

    @Test
    fun wrongPitchEventDoesNotConsumeAndCanFlagLaterNotes() {
        // E5 at 0.05 s is wrong for C5@0; it stays unconsumed and is not in
        // D5@1's window either — D5 is simply missed.
        val result = match(
            listOf(note(60, 0.0), note(62, 1.0)),
            listOf(event(64, seconds(0.05)), event(60, seconds(0.04))),
        )
        assertEquals(Verdict.PERFECT, result.outcomes[0].verdict)
        assertEquals(Verdict.MISSED, result.outcomes[1].verdict)
        assertEquals(1, result.extraEvents.size)
        assertEquals(64, result.extraEvents[0].pitch)
    }

    @Test
    fun duplicateEventsAtTheSameTimeAreStable() {
        val events = listOf(event(60, 0L), event(60, 0L))
        val result = match(listOf(note(60, 0.0)), events)
        assertEquals(Verdict.PERFECT, result.outcomes[0].verdict)
        assertEquals(1, result.extraEvents.size)
        // Deterministic: identical inputs give the same consumption order.
        val again = match(listOf(note(60, 0.0)), events)
        assertEquals(result, again)
    }

    @Test
    fun inputOrderDoesNotAffectResults() {
        val expected = listOf(note(60, 0.0), note(64, 1.0), note(67, 2.0))
        val events = listOf(event(67, seconds(1.0)), event(60, 0L), event(64, seconds(0.5)))
        val shuffled = match(expected, events)
        val reversed = match(expected, events.reversed())
        assertEquals(shuffled, reversed)
        assertEquals(Verdict.PERFECT, shuffled.outcomes[0].verdict)
        assertEquals(Verdict.PERFECT, shuffled.outcomes[1].verdict)
        assertEquals(Verdict.PERFECT, shuffled.outcomes[2].verdict)
    }

    // ------------------------------------------------------------------
    // window scaling
    // ------------------------------------------------------------------

    @Test
    fun halfTempoDoublesTheWindow() {
        // 60 bpm -> scale = 120/60 = 2.0 -> late window = 180*2 = 360 ms.
        val result = match(listOf(note(60, 0.0)), listOf(event(60, seconds(0.2))), bpm = 60.0)
        assertEquals(Verdict.GOOD, result.outcomes[0].verdict)
        assertEquals(200.0, result.outcomes[0].deviationMs!!, 1e-6)
    }

    @Test
    fun twoHundredMsLateMissesAtReferenceTempo() {
        val result = match(listOf(note(60, 0.0)), listOf(event(60, seconds(0.2))))
        assertEquals(Verdict.MISSED, result.outcomes[0].verdict)
    }

    @Test
    fun beginnerWideningAcceptsTwoHundredTwentyMsLate() {
        val events = listOf(event(60, seconds(0.22)))
        val base = match(listOf(note(60, 0.0)), events)
        assertEquals(Verdict.MISSED, base.outcomes[0].verdict)
        val beginner = match(listOf(note(60, 0.0)), events, config = ScoreConfig(beginner = true))
        assertEquals(Verdict.GOOD, beginner.outcomes[0].verdict)
    }

    @Test
    fun tempoScaleIsClampedAtExtremeTempo() {
        // 30 bpm -> scale would be 4.0 but clamps to 2.0: late window = 360 ms.
        val events = listOf(event(60, seconds(0.3)), event(60, seconds(0.4)))
        val result = match(listOf(note(60, 0.0)), events, bpm = 30.0)
        assertEquals(Verdict.GOOD, result.outcomes[0].verdict) // 300 ms in window
        assertEquals(1, result.extraEvents.size) // 400 ms beyond the clamped window
    }

    // ------------------------------------------------------------------
    // wrong octave
    // ------------------------------------------------------------------

    @Test
    fun wrongOctaveInWindowIsWrong() {
        // C5 expected; an octave-misread C6 played in the window.
        val result = match(listOf(note(60, 0.0)), listOf(event(72, seconds(0.05))))
        assertEquals(Verdict.WRONG, result.outcomes[0].verdict)
        assertEquals(1, result.extraEvents.size)
    }

    @Test
    fun bestEventWinsAmongSeveralCandidates() {
        // Note at beat 1 (t = 0.5 s): two C5 events in the window, the closer
        // one (-10 ms) wins.
        val result = match(
            listOf(note(60, 1.0)),
            listOf(event(60, seconds(0.6)), event(60, seconds(0.49))),
        )
        assertEquals(Verdict.PERFECT, result.outcomes[0].verdict)
        assertEquals(-10.0, result.outcomes[0].deviationMs!!, 1e-6)
        assertEquals(1, result.extraEvents.size)
    }
}