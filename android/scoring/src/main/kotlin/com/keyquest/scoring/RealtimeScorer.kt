package com.keyquest.scoring

/**
 * An incremental, batch-consistent scorer for live lessons (plan §24
 * P1.6.5/8/9): a stateful driver over the pure batch [Scorer] that emits
 * per-note verdicts and a score DURING the lesson, with a provable freeze
 * rule guaranteeing [finalize] equals the batch result EXACTLY.
 *
 * Freeze theorem: for the standard window config (late >= early — true
 * for the defaults 180 >= 120 ms and the beginner 250 == 250 ms), note
 * k's verdict depends only on notes 0..k (canonical order,
 * [ChordClusterer.COMPARATOR]) and on events with
 * onTimeNs <= closeSeconds(k), where closeSeconds(k) is the LATE edge of
 * note k's hit window:
 *
 *   closeSeconds(k) = tempoMap.beatToSeconds(startBeat_k)
 *                     + config.windowMs(startBeat_k, tempoMap).lateMs / 1000
 *
 * Once the delivery watermark passes closeSeconds(k), no future event can
 * change note k's verdict — so the verdict is frozen and never flips.
 *
 * The running-max freeze is sound even without late >= early: any event
 * beyond close(k) that an earlier note j could still consume satisfies
 * onTimeNs <= close(j) <= runningMaxClose[k], so no event after the
 * running-max watermark can affect notes 0..k.
 *
 * Prefix freeze via running max, NOT per-note close: note k freezes only
 * when watermark > runningMaxClose[k] = max(closeSeconds[0..k]), so the
 * frozen set is always a prefix [0..frozenCount). Naive per-note close
 * can leave holes: at a 60->240 bpm step at beat 8, N(beat 8.01, close
 * ~8.09s) freezes while M(beat 7.9, close ~8.26s) is still open. The
 * running max is also robust to delivery jitter: an event with onTimeNs
 * in (8.0925, 8.26] delivered after N's naive close would be invisible
 * to a per-note-frozen N, but is still covered by the running max, which
 * keeps N open until 8.26.
 *
 * Tentative verdicts = the full batch over the events delivered so far
 * (display-only; they may evolve as more events arrive). Frozen verdicts
 * are captured from the tentative at freeze time and never change.
 *
 * [finalize] returns EXACTLY [Scorer.score] over the complete event stream
 * — the same report the batch scorer produces, inputs and all.
 *
 * Delivery contract (caller obligation):
 *  - events arrive via [onEvent] with non-decreasing onTimeNs (enforced);
 *  - [tick] must be called only AFTER every event with onTimeNs <= now has
 *    been delivered, otherwise the watermark can cross a close before the
 *    events that determine it have been seen.
 *
 * frozenScore is NOT monotone: freezing a MISSED or WRONG note grows the
 * denominator while the numerator stays, so the score can DROP. The
 * monotone invariants are frozenCount (non-decreasing) and frozen-verdict
 * stability (a frozen verdict never flips).
 *
 * Empty expected is valid: empty canonical and outcomes, no freezes,
 * score 0.0, no crash; every played event lands in [finalize]'s extras.
 *
 * @property expected the chunk's expected notes (any order; canonicalized
 *   internally for freeze alignment).
 * @property config scoring configuration (windows, beginner, bonus, ...).
 * @property tempoMap beat -> seconds conversion (step/linear curves).
 * @property measureMapper beat -> measure index (delegated to the batch
 *   scorer).
 * @property thresholds star thresholds for the tentative and final stars.
 */
class RealtimeScorer(
    private val expected: List<ExpectedNote>,
    private val config: ScoreConfig,
    private val tempoMap: TempoMap,
    private val measureMapper: MeasureMapper,
    private val thresholds: StarThresholds = StarThresholds(),
) {

    /** Canonical note order (startBeat asc, R before L, pitch asc). */
    private val canonical = expected.sortedWith(ChordClusterer.COMPARATOR)

    /** The late edge of each note's hit window, in seconds — the freeze deadline. */
    private val closeSeconds: DoubleArray = DoubleArray(canonical.size) { index ->
        val note = canonical[index]
        tempoMap.beatToSeconds(note.startBeat) +
            config.windowMs(note.startBeat, tempoMap).lateMs / 1000.0
    }

    /** runningMaxClose[k] = max(closeSeconds[0..k]): note k's freeze deadline. */
    private val runningMaxClose: DoubleArray = run {
        val result = DoubleArray(closeSeconds.size)
        var runningMax = Double.NEGATIVE_INFINITY
        for (index in closeSeconds.indices) {
            runningMax = maxOf(runningMax, closeSeconds[index])
            result[index] = runningMax
        }
        result
    }

    /** The batch reference scorer, reused for every tentative and [finalize]. */
    private val batchScorer = Scorer(config, tempoMap, measureMapper)

    /** Every delivered event, in delivery order (append-only). */
    private val events = mutableListOf<PlayedNote>()

    /** Session clock in seconds; advances only via [tick]. */
    private var sessionSeconds = 0.0

    /** onTimeNs of the most recently delivered event; -1 before the first. */
    private var lastEventNs = -1L

    /**
     * Freeze watermark: max(sessionSeconds, lastEventNs / 1e9). A note is
     * frozen once the watermark exceeds its running-max close.
     */
    private var watermark = 0.0

    /** Frozen verdicts per canonical index; null while the note is open. */
    private val frozenVerdicts = arrayOfNulls<Verdict>(canonical.size)

    /** How many leading notes are frozen (the frozen set is always a prefix). */
    private var frozenCount = 0

    /** Score over the frozen prefix only (may decrease; see class KDoc). */
    private var frozenScore = 0.0

    /** The last full-batch report over the events delivered so far. */
    private var tentative: ScoreReport =
        batchScorer.score(expected, emptyList(), thresholds)

    /**
     * Delivers one played event. Recomputes the tentative (full batch over
     * all events so far), freezes every note whose close the watermark has
     * passed, and returns the new [Snapshot].
     *
     * @param note the played event; onTimeNs must be >= the previous
     *   event's (monotone delivery, see class KDoc).
     * @return the snapshot AFTER this event's effect.
     */
    fun onEvent(note: PlayedNote): Snapshot {
        require(note.onTimeNs >= lastEventNs) {
            "events must be delivered in non-decreasing onTimeNs order, " +
                "was ${note.onTimeNs} after $lastEventNs"
        }
        events.add(note)
        lastEventNs = note.onTimeNs
        watermark = maxOf(watermark, note.onTimeNs / 1e9)
        tentative = batchScorer.score(expected, events.toList(), thresholds)
        fillFrozen()
        return snapshot()
    }

    /**
     * Advances the session clock. Does NOT recompute the tentative
     * (verdicts only change when events arrive); it only advances the
     * watermark, which may freeze additional notes.
     *
     * @param sessionSeconds current session time in seconds, non-decreasing
     *   (and, per the delivery contract, only after every event with
     *   onTimeNs <= it has been delivered).
     * @return the snapshot after this tick.
     */
    fun tick(sessionSeconds: Double): Snapshot {
        require(sessionSeconds >= this.sessionSeconds) {
            "sessionSeconds must be non-decreasing, " +
                "was $sessionSeconds after ${this.sessionSeconds}"
        }
        this.sessionSeconds = sessionSeconds
        watermark = maxOf(watermark, sessionSeconds)
        fillFrozen()
        return snapshot()
    }

    /**
     * The final report: EXACTLY the batch result over the complete event
     * stream — identical inputs to [Scorer.score], identical output.
     */
    fun finalize(): ScoreReport =
        batchScorer.score(expected, events.toList(), thresholds)

    /**
     * The current live view: frozen and tentative verdicts, match
     * telemetry, and scores/stars, all aligned to canonical note order.
     */
    fun snapshot(): Snapshot {
        val outcomes = tentative.outcomes
        return Snapshot(
            frozenVerdicts = frozenVerdicts.toList(),
            tentativeVerdicts = outcomes.map { it.verdict },
            matchedPitches = outcomes.map { it.matchedPitch },
            deviationMs = outcomes.map { it.deviationMs },
            frozenCount = frozenCount,
            tentativeScore = tentative.score,
            frozenScore = frozenScore,
            tentativeStars = tentative.stars,
        )
    }

    /**
     * Freezes every note whose running-max close the watermark has passed,
     * then recomputes [frozenScore] over the frozen prefix [0..frozenCount).
     * Each verdict is captured from the CURRENT tentative exactly once (the
     * null guard makes repeated calls idempotent); the tentative at freeze
     * time is final for that note (see class KDoc freeze theorem).
     */
    private fun fillFrozen() {
        while (frozenCount < canonical.size && watermark > runningMaxClose[frozenCount]) {
            if (frozenVerdicts[frozenCount] == null) {
                frozenVerdicts[frozenCount] = tentative.outcomes[frozenCount].verdict
            }
            frozenCount++
        }

        // --- frozen-score aggregation over the frozen prefix (mirrors
        //     Scorer: PERFECT carries the bonus, GOOD is full credit) ------
        var totalWeight = 0.0
        var hitWeight = 0.0
        for (index in 0 until frozenCount) {
            val weight = canonical[index].scoringWeight
            totalWeight += weight
            when (frozenVerdicts[index]) {
                Verdict.PERFECT -> hitWeight += weight * (1.0 + config.perfectBonus)
                Verdict.GOOD -> hitWeight += weight
                Verdict.MISSED, Verdict.WRONG, null -> Unit
            }
        }
        frozenScore = if (totalWeight == 0.0) {
            0.0
        } else {
            minOf(100.0, 100.0 * hitWeight / totalWeight)
        }
    }
}

/**
 * The live scoring view returned by [RealtimeScorer] after every event and
 * tick (plan §24 P1.6.5/8/9).
 *
 * All lists are aligned to the CANONICAL expected order
 * ([ChordClusterer.COMPARATOR]) and have equal length.
 *
 * @property frozenVerdicts per-note verdicts that can never change; null
 *   until the note's close has passed (index >= [frozenCount]).
 * @property tentativeVerdicts the full-batch verdicts over the events
 *   delivered so far — display-only, may evolve.
 * @property matchedPitches pitch of the matched event per note, or null
 *   when the note is not (yet) matched.
 * @property deviationMs event time minus expected time per note, in
 *   milliseconds (negative early, positive late), or null when not matched.
 * @property frozenCount how many leading notes are frozen (the frozen set
 *   is always a prefix [0..frozenCount)).
 * @property tentativeScore 0..100 from the current tentative batch.
 * @property frozenScore 0..100 over the frozen prefix only; may DECREASE
 *   when a MISSED/WRONG note freezes (see [RealtimeScorer] KDoc).
 * @property tentativeStars 0..3 from the current tentative batch.
 */
data class Snapshot(
    val frozenVerdicts: List<Verdict?>,
    val tentativeVerdicts: List<Verdict>,
    val matchedPitches: List<Int?>,
    val deviationMs: List<Double?>,
    val frozenCount: Int,
    val tentativeScore: Double,
    val frozenScore: Double,
    val tentativeStars: Int,
) {
    init {
        require(frozenVerdicts.size == tentativeVerdicts.size) {
            "frozenVerdicts and tentativeVerdicts must have equal size, " +
                "were ${frozenVerdicts.size} and ${tentativeVerdicts.size}"
        }
        require(
            tentativeVerdicts.size == matchedPitches.size &&
                matchedPitches.size == deviationMs.size
        ) {
            "tentativeVerdicts, matchedPitches and deviationMs must have equal size, " +
                "were ${tentativeVerdicts.size}, ${matchedPitches.size} and ${deviationMs.size}"
        }
        require(frozenCount in 0..tentativeVerdicts.size) {
            "frozenCount must be in 0..${tentativeVerdicts.size}, was $frozenCount"
        }
        require(!tentativeScore.isNaN() && tentativeScore in 0.0..100.0) {
            "tentativeScore must be in [0, 100] and never NaN, was $tentativeScore"
        }
        require(!frozenScore.isNaN() && frozenScore in 0.0..100.0) {
            "frozenScore must be in [0, 100] and never NaN, was $frozenScore"
        }
        require(tentativeStars in 0..3) {
            "tentativeStars must be in 0..3, was $tentativeStars"
        }
    }
}