package com.keyquest.scoring

import kotlin.math.abs

/**
 * The core matching algorithm (plan §20 P1.5.1). Pure and deterministic:
 * same inputs, same outputs, no I/O, no shared state.
 *
 * Algorithm, per expected note in canonical order (startBeat asc, R before L,
 * pitch asc — songpack-v1.md §3.1):
 *
 *  1. Window = [t - earlyMs/1000, t + lateMs/1000] in seconds, where
 *     t = tempoMap.beatToSeconds(startBeat) and early/late come from
 *     ScoreConfig (beginner-aware, tempo-scaled, clamped).
 *  2. Best unconsumed played event with EQUAL pitch inside the window:
 *     min |deviation| (event time - t); ties broken by earliest onTimeNs.
 *     The event is consumed. Verdict PERFECT when |deviation| <=
 *     perfectBandMs/1000, else GOOD.
 *  3. No equal-pitch event: WRONG when ANY unconsumed wrong-pitch event lies
 *     inside the window, else MISSED. Wrong-pitch events are NEVER consumed:
 *     a wrong-pitch event can never match a note (pitch must be equal), so it
 *     stays available and is reported in the extra events afterwards.
 *
 * Extras: every played event that was never consumed is an "extra" — this
 * includes wrong-pitch events that triggered a WRONG verdict AND extra notes
 * played inside the window of an already-matched note (documented decision:
 * extra notes while a note was hit are telemetry, not a verdict change; see
 * docs/specs/scoring-v1.md).
 */
class Matcher(
    private val config: ScoreConfig,
    private val tempoMap: TempoMap,
) {

    /**
     * Matches [events] against [expected]. Both lists may arrive in any
     * order — copies are sorted internally (events by onTimeNs, then pitch,
     * then velocity; expected by the SongPack canonical order), so the
     * result is deterministic.
     *
     * @return per-note outcomes (in canonical expected order) plus the list
     *   of unconsumed events (sorted by onTimeNs, then pitch, then velocity).
     */
    fun match(expected: List<ExpectedNote>, events: List<PlayedNote>): MatchResult {
        val sortedEvents = events.sortedWith(EVENT_COMPARATOR)
        val consumed = BooleanArray(sortedEvents.size)
        val expectedOrder = expected.sortedWith(ChordClusterer.COMPARATOR)
        val outcomes = mutableListOf<NoteOutcome>()

        for ((expectedIndex, note) in expectedOrder.withIndex()) {
            val t = tempoMap.beatToSeconds(note.startBeat)
            val window = config.windowMs(note.startBeat, tempoMap)
            val windowStart = t - window.earlyMs / 1000.0
            val windowEnd = t + window.lateMs / 1000.0

            val best = bestEqualPitchEvent(sortedEvents, consumed, note.pitch, t, windowStart, windowEnd)
            if (best != null) {
                consumed[best] = true
                val event = sortedEvents[best]
                val deviationMs = (event.onTimeNs / 1e9 - t) * 1000.0
                val verdict = if (abs(deviationMs) <= config.perfectBandMs) Verdict.PERFECT else Verdict.GOOD
                outcomes.add(
                    NoteOutcome(
                        expectedIndex = expectedIndex,
                        verdict = verdict,
                        matchedEventIndex = best,
                        deviationMs = deviationMs,
                        matchedPitch = event.pitch,
                    )
                )
            } else {
                val wrongInWindow = sortedEvents.indices.any { index ->
                    !consumed[index] &&
                        sortedEvents[index].pitch != note.pitch &&
                        sortedEvents[index].onTimeNs / 1e9 >= windowStart &&
                        sortedEvents[index].onTimeNs / 1e9 <= windowEnd
                }
                outcomes.add(
                    NoteOutcome(
                        expectedIndex = expectedIndex,
                        verdict = if (wrongInWindow) Verdict.WRONG else Verdict.MISSED,
                    )
                )
            }
        }

        val extras = sortedEvents.filterIndexed { index, _ -> !consumed[index] }
        return MatchResult(outcomes, extras)
    }

    /**
     * The unconsumed event of [pitch] inside [windowStart, windowEnd] with
     * the smallest |deviation| from the expected time [t]; ties go to the
     * earliest onTimeNs. [events] must be pre-sorted by (onTimeNs, pitch,
     * velocity), which makes the tie-break fall out of the iteration order.
     * Returns the event index, or null when there is no such event.
     */
    private fun bestEqualPitchEvent(
        events: List<PlayedNote>,
        consumed: BooleanArray,
        pitch: Int,
        t: Double,
        windowStart: Double,
        windowEnd: Double,
    ): Int? {
        var best: Int? = null
        var bestAbsDeviation = Double.MAX_VALUE
        for (index in events.indices) {
            if (consumed[index]) continue
            val event = events[index]
            if (event.pitch != pitch) continue
            val timeSeconds = event.onTimeNs / 1e9
            if (timeSeconds < windowStart || timeSeconds > windowEnd) continue
            val absDeviation = abs(timeSeconds - t)
            // Strict '<' + pre-sorted events => min |deviation|, earliest
            // onTimeNs on ties (the first best is kept).
            if (absDeviation < bestAbsDeviation) {
                best = index
                bestAbsDeviation = absDeviation
            }
        }
        return best
    }

    companion object {
        /** Deterministic event order: onTimeNs asc, then pitch, then velocity. */
        val EVENT_COMPARATOR: Comparator<PlayedNote> =
            compareBy<PlayedNote>({ it.onTimeNs }, { it.pitch }, { it.velocity })
    }
}

/**
 * The Matcher's full result: per-note [outcomes] in canonical expected order,
 * plus [extraEvents] — every played event that was never consumed by an
 * equal-pitch match (sorted by onTimeNs, then pitch, then velocity).
 */
data class MatchResult(
    val outcomes: List<NoteOutcome>,
    val extraEvents: List<PlayedNote>,
)