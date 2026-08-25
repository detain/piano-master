package com.keyquest.scoring

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property tests (plan §20 P1.5.5): generated event streams (early, late,
 * extra, missing, wrong-octave, rolled chords, duplicates) with invariants —
 * score is never NaN, never > 100, identical input always yields identical
 * output, score is monotone in accuracy, stars are consistent with
 * thresholds, heatmap sums agree, and matched events are consumed at most
 * once. Deterministic: java.util.Random with a fixed seed (0xC0FFEE) — no
 * kotlin.random, no wall clock.
 */
class ScorerPropertyTest {

    private val thresholds = StarThresholds()

    private val tempo120 = TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = 120.0)))

    private val scenarios = listOf("base", "early", "late", "extra", "missing", "wrongOctave", "rolled", "duplicates")

    // A generated session: expected notes + events, plus which expected note
    // each event was generated for (null = deliberate extra). The source map
    // is what lets the monotone-in-accuracy mutations target a real event.
    private data class Session(
        val expected: List<ExpectedNote>,
        val events: List<PlayedNote>,
        val eventSources: List<Int?>,
    )

    @Test
    fun scorerInvariantsHoldAcrossSeededScenarios() {
        val seeds = 200
        for (seed in 0 until seeds) {
            val rng = Random(0xC0FFEE.toLong() * 31 + seed)
            val base = generateSession(rng)
            val mapper = measureMapperFor(base)

            for (scenario in scenarios) {
                val session = when (scenario) {
                    "base" -> base
                    else -> generateVariant(base, scenario, rng)
                }
                assertSessionInvariants(session, mapper, scenario, seed)
            }

            assertMonotoneInAccuracy(base, mapper, rng)
            assertExtraEventNeverIncreasesScore(base, mapper, rng)
        }
    }

    // ------------------------------------------------------------------
    // invariant assertions
    // ------------------------------------------------------------------

    private fun assertSessionInvariants(session: Session, mapper: MeasureMapper, scenario: String, seed: Int) {
        val scorer = Scorer(ScoreConfig(), tempo120, mapper)
        val first = scorer.score(session.expected, session.events, thresholds)
        val second = scorer.score(session.expected, session.events, thresholds)

        val label = "seed=$seed scenario=$scenario"

        // Determinism: identical input -> identical report.
        assertEquals("$label: deterministic reports", first, second)
        assertEquals("$label: deterministic hashes", first.hashCode(), second.hashCode())

        // Score sanity: never NaN, always in [0, 100].
        assertFalse("$label: score not NaN", first.score.isNaN())
        assertTrue("$label: score >= 0 (was ${first.score})", first.score >= 0.0)
        assertTrue("$label: score <= 100 (was ${first.score})", first.score <= 100.0)

        // Stars consistent with thresholds.
        assertTrue("$label: stars in 0..3", first.stars in 0..3)
        val expectedStars = when {
            first.score >= thresholds.threeStar -> 3
            first.score >= thresholds.twoStar -> 2
            first.score >= thresholds.oneStar -> 1
            else -> 0
        }
        assertEquals("$label: stars match score", expectedStars, first.stars)

        // Heatmap sums agree with the verdict counts and stay in range.
        val heatmapTotal = first.measureHeatmap.values.sumOf { it.missed + it.wrong }
        assertEquals("$label: heatmap totals", first.missedCount + first.wrongCount, heatmapTotal)
        val maxBeat = session.expected.maxOfOrNull { it.startBeat } ?: 0.0
        val maxMeasure = mapper.measureIndex(maxBeat)
        for (measure in first.measureHeatmap.keys) {
            assertTrue("$label: measure $measure within [0, $maxMeasure]", measure in 0..maxMeasure)
        }

        // Each played event is consumed at most once.
        val matchedIndexes = first.outcomes.mapNotNull { it.matchedEventIndex }
        assertEquals("$label: unique matched events", matchedIndexes.size, matchedIndexes.toSet().size)

        // Verdict counts add up to the note count.
        assertEquals(
            "$label: verdict counts sum",
            first.outcomes.size,
            first.perfectCount + first.goodCount + first.missedCount + first.wrongCount,
        )
    }

    private fun assertMonotoneInAccuracy(base: Session, mapper: MeasureMapper, rng: Random) {
        val scorer = Scorer(ScoreConfig(), tempo120, mapper)
        val original = scorer.score(base.expected, base.events, thresholds)
        val sortedEvents = base.events.sortedWith(Matcher.EVENT_COMPARATOR)

        // Events consumed by some match, as indexes into the Matcher's sorted
        // event list (matches the report's matchedEventIndex).
        val consumedSortedIndexes = original.outcomes.mapNotNull { it.matchedEventIndex }.toSet()

        // Mutations target MATCHED events (the task wording: "moving one
        // event's onTimeNs CLOSER to its matched expected note's time").
        val matched = original.outcomes.mapNotNull { outcome ->
            val sortedIndex = outcome.matchedEventIndex ?: return@mapNotNull null
            val rawIndex = base.events.indexOfFirst { it == sortedEvents[sortedIndex] }
            if (rawIndex < 0) return@mapNotNull null
            Triple(rawIndex, outcome.expectedIndex, sortedIndex)
        }
        if (matched.isEmpty()) return

        repeat(3) {
            val (rawIndex, expectedIndex, _) = matched[rng.nextInt(matched.size)]
            val note = base.expected[expectedIndex]
            val event = base.events[rawIndex]

            // --- mutation 1: move the event onto its matched note's time ---
            val exactNs = (tempo120.beatToSeconds(note.startBeat) * 1e9).toLong()
            if (isUnambiguous(note, exactNs, base.expected)) {
                val closerEvents = base.events.toMutableList()
                closerEvents[rawIndex] = event.copy(onTimeNs = exactNs)
                val closer = scorer.score(base.expected, closerEvents, thresholds)
                assertTrue(
                    "moving an event onto its matched note's time must not lower the score " +
                        "(was ${original.score}, now ${closer.score})",
                    closer.score >= original.score - 1e-9,
                )
            }

        }

        // --- mutation 2: fix an unconsumed wrong-pitch event to its source pitch ---
        val wrongPitchUnconsumed = base.events.indices.filter { i ->
            val src = base.eventSources[i]
            src != null &&
                base.events[i].pitch != base.expected[src].pitch &&
                sortedEvents.indexOfFirst { it === base.events[i] } !in consumedSortedIndexes
        }
        repeat(3) {
            if (wrongPitchUnconsumed.isEmpty()) return@repeat
            val rawIndex = wrongPitchUnconsumed[rng.nextInt(wrongPitchUnconsumed.size)]
            val event = base.events[rawIndex]
            val sourceIndex = base.eventSources[rawIndex]!!
            val rightPitch = base.expected[sourceIndex].pitch
            if (isUnambiguous(base.expected[sourceIndex], event.onTimeNs, base.expected)) {
                val fixedEvents = base.events.toMutableList()
                fixedEvents[rawIndex] = event.copy(pitch = rightPitch)
                val fixed = scorer.score(base.expected, fixedEvents, thresholds)
                assertTrue(
                    "fixing a wrong pitch must not lower the score " +
                        "(was ${original.score}, now ${fixed.score})",
                    fixed.score >= original.score - 1e-9,
                )
            }
        }
    }

    /**
     * Soundness of the monotone-in-accuracy mutations (guards below):
     *
     *  - mutation 1 (move an event onto its matched note's time): targets
     *    MATCHED events; the guard below is checked on the note at the target
     *    time. Sound when AT MOST ONE same-pitch window contains that time —
     *    no other note can consume the event, so the greedy assignment can
     *    only improve that note's match. Overlapping same-pitch windows (fast
     *    repeated notes or unisons) are skipped: the greedy matcher could
     *    reshuffle which note gets the PERFECT bonus, and the property is not
     *    asserted (documented corner in docs/specs/scoring-v1.md; the score
     *    swing is bounded by the bonus).
     *
     *  - mutation 2 (fix a wrong-pitch event to its source pitch): targets
     *    UNCONSUMED wrong-pitch events only (pool filter: source-bearing,
     *    pitch differs from the source note, not in consumedSortedIndexes).
     *    Consumed events are excluded because a wrong-pitch event can only
     *    have been consumed by a DIFFERENT note (its pitch differs from the
     *    source's, so the source can never consume it), and fixing it would
     *    steal that note's match and could lower the score. The guard below
     *    is applied to the SOURCE note at the event's time: this prevents
     *    PERFECT-bonus reshuffling — the event becomes a candidate for only
     *    one same-pitch note, so every note's greedy match can only improve
     *    or stay the same (removing a wrong-pitch candidate can only turn
     *    WRONG into a match or leave it; WRONG and MISSED both contribute 0
     *    weight).
     */
    private fun isUnambiguous(note: ExpectedNote, timeNs: Long, expected: List<ExpectedNote>): Boolean {
        val config = ScoreConfig()
        val timeSeconds = timeNs / 1e9
        var windowsContaining = 0
        for (candidate in expected) {
            if (candidate.pitch != note.pitch) continue
            val t = tempo120.beatToSeconds(candidate.startBeat)
            val window = config.windowMs(candidate.startBeat, tempo120)
            val inside = timeSeconds >= t - window.earlyMs / 1000.0 && timeSeconds <= t + window.lateMs / 1000.0
            if (inside) windowsContaining++
        }
        return windowsContaining <= 1
    }

    private fun assertExtraEventNeverIncreasesScore(base: Session, mapper: MeasureMapper, rng: Random) {
        val scorer = Scorer(ScoreConfig(), tempo120, mapper)
        val original = scorer.score(base.expected, base.events, thresholds)
        val extraEvent = PlayedNote(
            pitch = 0, // outside every expected pitch (60..84): can never match
            velocity = 100,
            onTimeNs = (rng.nextDouble() * 40.0 * 1e9).toLong(),
            offTimeNs = -1,
        )
        val withExtra = scorer.score(base.expected, base.events + extraEvent, thresholds)
        assertTrue(
            "an event that matches no note must never increase the score " +
                "(was ${original.score}, now ${withExtra.score})",
            withExtra.score <= original.score + 1e-9,
        )
        assertEquals(original.extraEvents.size + 1, withExtra.extraEvents.size)
    }

    // ------------------------------------------------------------------
    // generator
    // ------------------------------------------------------------------

    private fun generateSession(rng: Random): Session {
        val noteCount = 1 + rng.nextInt(24) // 1..24 notes
        val notes = mutableListOf<ExpectedNote>()
        for (i in 0 until noteCount) {
            val pitch = 60 + rng.nextInt(25) // 60..84
            val startBeat = rng.nextDouble() * 32.0 // 8 measures of 4/4 at 120 bpm
            val hand = if (rng.nextBoolean()) Hand.R else Hand.L
            notes.add(ExpectedNote(pitch = pitch, startBeat = startBeat, durBeats = 1.0, hand = hand))
        }
        // Some chords: 2-3 notes within 0.15 beats (75 ms at 120 bpm < 90 ms).
        val chordCount = rng.nextInt(4)
        for (c in 0 until chordCount) {
            val anchor = rng.nextInt(notes.size)
            val extraTones = 1 + rng.nextInt(2)
            for (t in 0 until extraTones) {
                val pitch = 60 + rng.nextInt(25)
                val startBeat = notes[anchor].startBeat + rng.nextDouble() * 0.15
                notes.add(ExpectedNote(pitch = pitch, startBeat = startBeat, durBeats = 1.0, hand = Hand.R))
            }
        }
        notes.sortWith(ChordClusterer.COMPARATOR)

        val events = mutableListOf<PlayedNote>()
        val sources = mutableListOf<Int?>()
        for ((index, note) in notes.withIndex()) {
            if (rng.nextDouble() < 0.15) continue // 15% of notes are never played (missing)
            val baseNs = (tempo120.beatToSeconds(note.startBeat) * 1e9).toLong()
            val jitterNs = ((rng.nextDouble() * 2.0 - 1.0) * 0.12 * 1e9).toLong() // +/- 120 ms
            val pitch = if (rng.nextDouble() < 0.05) note.pitch + 12 else note.pitch // 5% wrong octave
            val velocity = 1 + rng.nextInt(127)
            // onTimeNs must be >= 0 (session clock); clamp beat-0 jitter.
            events.add(PlayedNote(pitch, velocity, maxOf(0L, baseNs + jitterNs), -1L))
            sources.add(index)
            if (rng.nextDouble() < 0.10) {
                // duplicate: same source, nearly same time
                events.add(PlayedNote(pitch, velocity, baseNs + jitterNs, -1L))
                sources.add(index)
            }
        }
        // Deliberate extras: wrong-pitch or far-away events.
        val extraCount = rng.nextInt(3)
        for (e in 0 until extraCount) {
            events.add(
                PlayedNote(
                    pitch = rng.nextInt(128),
                    velocity = 1 + rng.nextInt(127),
                    onTimeNs = (rng.nextDouble() * 40.0 * 1e9).toLong(),
                    offTimeNs = -1,
                )
            )
            sources.add(null)
        }
        return Session(notes, events, sources)
    }

    /** Applies one perturbation scenario to a base session. */
    private fun generateVariant(base: Session, scenario: String, rng: Random): Session {
        val shiftNs = (rng.nextDouble() * 0.12 * 1e9).toLong()
        val events = base.events.toMutableList()
        val sources = base.eventSources.toMutableList()

        when (scenario) {
            "early" -> shiftAll(events, -shiftNs)
            "late" -> shiftAll(events, shiftNs)
            "extra" -> {
                val extraCount = 1 + rng.nextInt(3)
                repeat(extraCount) {
                    events.add(
                        PlayedNote(
                            pitch = rng.nextInt(128),
                            velocity = 1 + rng.nextInt(127),
                            onTimeNs = (rng.nextDouble() * 40.0 * 1e9).toLong(),
                            offTimeNs = -1,
                        )
                    )
                    sources.add(null)
                }
            }
            "missing" -> {
                // Drop random SOURCE-bearing events only (the tail holds the
                // deliberate extras; dropping those would not exercise
                // missing notes). Distinct random indexes, removed highest
                // first so earlier indexes stay valid; skip when no source
                // events exist. Sources stay in sync.
                val sourceIndexes = sources.indices.filter { sources[it] != null }
                val dropCount = minOf(1 + rng.nextInt(3), sourceIndexes.size)
                val drops = buildSet {
                    while (size < dropCount) add(sourceIndexes[rng.nextInt(sourceIndexes.size)])
                }.sortedDescending()
                for (index in drops) {
                    events.removeAt(index)
                    sources.removeAt(index)
                }
            }
            "wrongOctave" -> {
                val flips = minOf(1 + rng.nextInt(3), events.size)
                repeat(flips) {
                    val i = rng.nextInt(events.size)
                    // Shift a full octave up, or down when that would exceed
                    // the MIDI range (pitch must stay in 0..127).
                    val shifted = if (events[i].pitch + 12 <= 127) events[i].pitch + 12 else events[i].pitch - 12
                    events[i] = events[i].copy(pitch = shifted)
                }
            }
            "rolled" -> {
                // Roll chord tones: shift events whose source note is close to
                // another note's onset by up to +/- 90 ms.
                for ((i, source) in sources.withIndex()) {
                    if (source == null) continue
                    val beat = base.expected[source].startBeat
                    val inChord = base.expected.any { it !== base.expected[source] && kotlin.math.abs(it.startBeat - beat) <= 0.16 }
                    if (inChord && rng.nextDouble() < 0.5) {
                        val rollNs = ((rng.nextDouble() * 2.0 - 1.0) * 0.09 * 1e9).toLong()
                        events[i] = events[i].copy(onTimeNs = maxOf(0L, events[i].onTimeNs + rollNs))
                    }
                }
            }
            "duplicates" -> {
                val dups = minOf(1 + rng.nextInt(3), events.size)
                repeat(dups) {
                    val i = rng.nextInt(events.size)
                    events.add(events[i])
                    sources.add(sources[i])
                }
            }
        }
        return Session(base.expected, events, sources)
    }

    private fun shiftAll(events: MutableList<PlayedNote>, deltaNs: Long) {
        for (i in events.indices) {
            // Clamp to >= 0: the session clock starts at 0 (PlayedNote guard).
            events[i] = events[i].copy(onTimeNs = maxOf(0L, events[i].onTimeNs + deltaNs))
        }
    }

    private fun measureMapperFor(session: Session): MeasureMapper {
        val duration = session.expected.maxOfOrNull { it.startBeat + it.durBeats } ?: 0.0
        return MeasureMapper(
            signatures = listOf(TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4)),
            pickupBeats = 0.0,
            durationBeats = duration,
        )
    }

    // ------------------------------------------------------------------
    // TempoMap property: beatToSeconds is monotone, deterministic
    // ------------------------------------------------------------------

    @Test
    fun tempoMapBeatToSecondsIsMonotoneNonDecreasing() {
        val rng = Random(0xC0FFEE)
        for (trial in 0 until 200) {
            val entryCount = 1 + rng.nextInt(5)
            val atBeats = mutableListOf(0.0)
            repeat(entryCount - 1) { atBeats.add(1.0 + rng.nextDouble() * 39.0) }
            atBeats.sort()
            val points = atBeats.mapIndexed { i, atBeat ->
                TempoPoint(
                    atBeat = atBeat,
                    bpm = 40.0 + rng.nextDouble() * 160.0,
                    curve = if (rng.nextBoolean()) TempoCurve.STEP else TempoCurve.LINEAR,
                )
            }
            val map = TempoMap(points)

            assertEquals(0.0, map.beatToSeconds(0.0), 0.0)
            var previous = 0.0
            var beat = 0.0
            while (beat <= 40.0) {
                val seconds = map.beatToSeconds(beat)
                assertTrue(
                    "trial $trial: beatToSeconds must be non-decreasing (beat $beat: $seconds < $previous)",
                    seconds >= previous - 1e-9,
                )
                if (beat > 0.0) assertTrue("trial $trial: positive for beat > 0", seconds > 0.0)
                assertTrue("trial $trial: bpmAt positive", map.bpmAt(beat) > 0.0)
                previous = seconds
                beat += 0.25
            }
            // Determinism.
            for (b in 0..40) {
                assertEquals(map.beatToSeconds(b.toDouble()), map.beatToSeconds(b.toDouble()), 0.0)
            }
        }
    }
}