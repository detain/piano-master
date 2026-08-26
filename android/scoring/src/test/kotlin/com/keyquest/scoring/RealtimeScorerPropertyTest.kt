package com.keyquest.scoring

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RealtimeScorer property tests (plan §24 P1.6.5/8/9): generated event
 * streams (early, late, extra, missing, wrong-octave, rolled chords,
 * duplicates) fed incrementally with the freeze invariants asserted at
 * EVERY snapshot — score ranges, frozenCount monotonicity, frozen verdicts
 * never flipping, frozen == batch over the events delivered so far,
 * tentative == that batch, and finalize == batch EXACTLY. Deterministic:
 * java.util.Random with a fixed seed (0xC0FFEE) — no kotlin.random, no
 * wall clock.
 */
class RealtimeScorerPropertyTest {

    private val tempo120 = TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = 120.0)))

    private val mapper = MeasureMapper(
        signatures = listOf(TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4)),
        pickupBeats = 0.0,
        durationBeats = 32.0,
    )

    private val scenarios = listOf("base", "early", "late", "extra", "missing", "wrongOctave", "rolled", "duplicates")

    @Test
    fun realtimeInvariantsHoldAcrossSeededScenarios() {
        for (seed in 0 until 200) {
            for (scenario in scenarios) {
                runProtocol(seed, scenario)
            }
        }
    }

    @Test
    fun protocolIsDeterministicForBaseScenario() {
        val first = runProtocol(seed = 0, scenario = "base")
        val second = runProtocol(seed = 0, scenario = "base")
        assertEquals("identical input must yield identical snapshot sequences", first, second)
    }

    // ------------------------------------------------------------------
    // protocol: feed events incrementally, assert invariants at every step
    // ------------------------------------------------------------------

    private fun runProtocol(seed: Int, scenario: String): List<Snapshot> {
        val expected = expectedNotes()
        val events = generateEvents(seed, scenario)
        val realtime = RealtimeScorer(expected, ScoreConfig(), tempo120, mapper)
        val batch = Scorer(ScoreConfig(), tempo120, mapper)

        val snapshots = mutableListOf<Snapshot>()
        val delivered = mutableListOf<PlayedNote>()
        var previousFrozenCount = 0
        var previousFrozenVerdicts = emptyList<Verdict?>()

        for ((step, event) in events.withIndex()) {
            realtime.onEvent(event)
            delivered.add(event)
            val snap = realtime.tick(event.onTimeNs / 1e9 + 0.001)
            val label = "seed=$seed scenario=$scenario step=$step"
            val report = batch.score(expected, delivered.toList(), StarThresholds())

            // 1. Score ranges, stars, and list alignment.
            assertRanges(snap, label)

            // 2. frozenCount is non-decreasing.
            assertTrue(
                "$label: frozenCount non-decreasing (was $previousFrozenCount, now ${snap.frozenCount})",
                snap.frozenCount >= previousFrozenCount,
            )

            // 3. A verdict frozen at a previous step never flips.
            for (index in 0 until previousFrozenCount) {
                assertEquals(
                    "$label: frozen verdict $index never flips " +
                        "(was ${previousFrozenVerdicts[index]}, now ${snap.frozenVerdicts[index]})",
                    previousFrozenVerdicts[index],
                    snap.frozenVerdicts[index],
                )
            }

            // 4. Frozen verdicts equal the batch over the events so far.
            for (index in 0 until snap.frozenCount) {
                assertEquals(
                    "$label: frozen verdict $index == batch-over-so-far",
                    report.outcomes[index].verdict,
                    snap.frozenVerdicts[index],
                )
            }

            // 5. The tentative view IS that batch.
            assertEquals(
                "$label: tentativeVerdicts == batch-over-so-far",
                report.outcomes.map { it.verdict },
                snap.tentativeVerdicts,
            )
            assertEquals("$label: tentativeScore == batch-over-so-far", report.score, snap.tentativeScore, 0.0)
            assertEquals("$label: tentativeStars == batch-over-so-far", report.stars, snap.tentativeStars)

            previousFrozenCount = snap.frozenCount
            previousFrozenVerdicts = snap.frozenVerdicts
            snapshots.add(snap)
        }

        // Final: tick past the last close (5.5 s + 0.18 s), then finalize.
        val finalSnap = realtime.tick(10.0)
        val label = "seed=$seed scenario=$scenario final"
        assertEquals("$label: all notes frozen", 12, finalSnap.frozenCount)
        val report = realtime.finalize()
        assertEquals("$label: frozen == final", report.outcomes.map { it.verdict }, finalSnap.frozenVerdicts)
        assertEquals(
            "$label: finalize == batch exactly",
            Scorer(ScoreConfig(), tempo120, mapper).score(expected, events, StarThresholds()),
            report,
        )
        snapshots.add(finalSnap)
        return snapshots
    }

    private fun assertRanges(snap: Snapshot, label: String) {
        assertFalse("$label: tentativeScore not NaN", snap.tentativeScore.isNaN())
        assertTrue("$label: tentativeScore >= 0 (was ${snap.tentativeScore})", snap.tentativeScore >= 0.0)
        assertTrue("$label: tentativeScore <= 100 (was ${snap.tentativeScore})", snap.tentativeScore <= 100.0)
        assertFalse("$label: frozenScore not NaN", snap.frozenScore.isNaN())
        assertTrue("$label: frozenScore >= 0 (was ${snap.frozenScore})", snap.frozenScore >= 0.0)
        assertTrue("$label: frozenScore <= 100 (was ${snap.frozenScore})", snap.frozenScore <= 100.0)
        assertTrue("$label: tentativeStars in 0..3", snap.tentativeStars in 0..3)
        assertEquals("$label: frozen/tentative same size", snap.frozenVerdicts.size, snap.tentativeVerdicts.size)
        assertEquals("$label: matchedPitches same size", snap.tentativeVerdicts.size, snap.matchedPitches.size)
        assertEquals("$label: deviationMs same size", snap.tentativeVerdicts.size, snap.deviationMs.size)
    }

    // ------------------------------------------------------------------
    // generator: 12 notes at beats 0..11 (t = beat * 0.5 s @ 120 bpm)
    // ------------------------------------------------------------------

    private fun expectedNotes(): List<ExpectedNote> {
        val pitches = listOf(60, 64, 67)
        return List(12) { index ->
            ExpectedNote(
                pitch = pitches[index % 3],
                startBeat = index.toDouble(),
                durBeats = 1.0,
                hand = if (index % 2 == 0) Hand.R else Hand.L,
                scoringWeight = if (index == 3 || index == 9) 0.2 else 1.0,
            )
        }
    }

    /** The base stream: one event per note at t + uniform(-0.02, +0.02). */
    private fun addBaseEvents(rng: Random, notes: List<ExpectedNote>, events: MutableList<PlayedNote>) {
        for (note in notes) {
            val t = tempo120.beatToSeconds(note.startBeat)
            val jitter = -0.02 + rng.nextDouble() * 0.04
            val onTimeNs = maxOf(0L, ((t + jitter) * 1e9).toLong())
            events.add(PlayedNote(pitch = note.pitch, velocity = 100, onTimeNs = onTimeNs, offTimeNs = -1))
        }
    }

    /** Distinct random indexes into the base list (2..3 of them). */
    private fun randomNoteIndexes(rng: Random, count: Int): Set<Int> = buildSet {
        while (size < count) add(rng.nextInt(12))
    }

    private fun generateEvents(seed: Int, scenario: String): List<PlayedNote> {
        val rng = Random(0xC0FFEE.toLong() * 31 + seed)
        val notes = expectedNotes()
        val events = mutableListOf<PlayedNote>()

        when (scenario) {
            "base" -> addBaseEvents(rng, notes, events)
            "early" -> {
                for (note in notes) {
                    val t = tempo120.beatToSeconds(note.startBeat) + (-0.09 + rng.nextDouble() * 0.06)
                    events.add(PlayedNote(note.pitch, 100, maxOf(0L, (t * 1e9).toLong()), -1L))
                }
            }
            "late" -> {
                for (note in notes) {
                    val t = tempo120.beatToSeconds(note.startBeat) + (0.03 + rng.nextDouble() * 0.06)
                    events.add(PlayedNote(note.pitch, 100, maxOf(0L, (t * 1e9).toLong()), -1L))
                }
            }
            "extra" -> {
                addBaseEvents(rng, notes, events)
                // 1-2 random extras: random pitch, random time in the session
                // span [0, 6 s) (the piece ends at 5.5 s).
                val extraCount = 1 + rng.nextInt(2)
                repeat(extraCount) {
                    events.add(
                        PlayedNote(
                            pitch = rng.nextInt(128),
                            velocity = 100,
                            onTimeNs = (rng.nextDouble() * 6.0 * 1e9).toLong(),
                            offTimeNs = -1,
                        )
                    )
                }
            }
            "missing" -> {
                addBaseEvents(rng, notes, events)
                // Omit 2-3 random notes' events (base events are in note order).
                val drops = randomNoteIndexes(rng, 2 + rng.nextInt(2)).sortedDescending()
                for (index in drops) events.removeAt(index)
            }
            "wrongOctave" -> {
                addBaseEvents(rng, notes, events)
                val flips = randomNoteIndexes(rng, 2 + rng.nextInt(2))
                for (index in flips) {
                    val event = events[index]
                    // Shift a full octave up, or down when that would exceed
                    // the MIDI range (pitch must stay in 0..127).
                    val shifted = if (event.pitch + 12 <= 127) event.pitch + 12 else event.pitch - 12
                    events[index] = event.copy(pitch = shifted)
                }
            }
            "rolled" -> {
                // Two events per note: one at t + [0.00, 0.04], one at
                // t + [0.05, 0.08].
                for (note in notes) {
                    val t = tempo120.beatToSeconds(note.startBeat)
                    val first = t + rng.nextDouble() * 0.04
                    val second = t + 0.05 + rng.nextDouble() * 0.03
                    events.add(PlayedNote(note.pitch, 100, (first * 1e9).toLong(), -1L))
                    events.add(PlayedNote(note.pitch, 100, (second * 1e9).toLong(), -1L))
                }
            }
            "duplicates" -> {
                addBaseEvents(rng, notes, events)
                val dupCount = 2 + rng.nextInt(2)
                repeat(dupCount) {
                    events.add(events[rng.nextInt(events.size)])
                }
            }
            else -> error("unknown scenario $scenario")
        }
        return events.sortedBy { it.onTimeNs }
    }
}