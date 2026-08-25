package com.keyquest.scoring

/**
 * A time-signature change point (SongPack v1 `timeSignatures[]` entry,
 * docs/specs/songpack-v1.md §6.2).
 *
 * @property atBeat beat at which this signature takes effect (>= 0). The
 *   pipeline emits these at measure starts, so mid-song changes land exactly
 *   on bar lines.
 * @property numerator beats per measure, 1..32 (schema-enforced).
 * @property denominator beat unit, one of 1,2,4,8,16,32 (schema-enforced).
 */
data class TimeSignature(
    val atBeat: Double,
    val numerator: Int,
    val denominator: Int,
) {
    init {
        require(atBeat >= 0.0) { "atBeat must be >= 0, was $atBeat" }
        require(numerator in 1..32) { "numerator must be in 1..32, was $numerator" }
        require(denominator in listOf(1, 2, 4, 8, 16, 32)) {
            "denominator must be one of 1,2,4,8,16,32, was $denominator"
        }
    }
}

/**
 * Maps beats to 0-based measure indexes for the per-measure error heatmap
 * (plan §6, §20 P1.5.4 — the heatmap feeds the 5-Min Workout generator in
 * Phase 2).
 *
 * Measure structure (derivation, matching spec §2 — "beat 0 is the first
 * pickup note; a 1-beat pickup + 8 measures of 4/4 has durationBeats 33"):
 *
 *   measure 0 = the pickup measure [0, pickupBeats)   — empty when pickupBeats == 0
 *   measure m = [pickupBeats + (m-1)*B, pickupBeats + m*B)   for m >= 1,
 *   where B = beatsPerMeasure(numerator * 4 / denominator) of the time
 *   signature active at that measure's start beat.
 *
 * This mirrors the pipeline's linearized measure table
 * (pipeline/pipeline/build/stage_normalize.py `_linearize`): position 0 is
 * the pickup measure of length pickupBeats (or a full measure when there is
 * no pickup) and every following position is a full measure whose length is
 * its own time signature's numerator*4/denominator, accumulated with a
 * cursor. The two agree for every song with a pickup; for no-pickup songs
 * this mapper keeps measure 0 as an empty slot so measure indexes are stable
 * whether or not a pickup exists (adding a pickup never renumbers bars).
 *
 * Beats at or after the end of the last measure clamp to the last measure
 * index — "last measure" is defined by [durationBeats] (the SongPack manifest
 * `durationBeats`): the last measure whose start beat is < durationBeats.
 * Pass Double.POSITIVE_INFINITY (the default) when the duration is unknown;
 * then no clamping happens and measures extend structurally forever.
 *
 * @property timeSignatures sorted by atBeat; the first entry must be at beat
 *   0.0 (it defines the opening measure, songpack-v1.md §6.2).
 * @property pickupBeats anacrusis length >= 0, at most one full measure at
 *   the opening signature (songpack-v1.md §7.1).
 * @property durationBeats total song length in beats (manifest
 *   `durationBeats`, includes pickup), >= 0.
 */
class MeasureMapper(
    signatures: List<TimeSignature>,
    pickupBeats: Double,
    durationBeats: Double = Double.POSITIVE_INFINITY,
) {

    val timeSignatures: List<TimeSignature> = signatures.sortedBy { it.atBeat }
    val pickupBeats: Double = pickupBeats
    val durationBeats: Double = durationBeats

    init {
        require(pickupBeats >= 0.0) { "pickupBeats must be >= 0, was $pickupBeats" }
        require(durationBeats >= 0.0) { "durationBeats must be >= 0, was $durationBeats" }
        require(timeSignatures.isNotEmpty()) { "time signatures must not be empty" }
        require(timeSignatures.first().atBeat == 0.0) {
            "first time signature must be at beat 0.0, was ${timeSignatures.first().atBeat}"
        }
        require(timeSignatures.zipWithNext().all { (a, b) -> b.atBeat > a.atBeat }) {
            "time signatures must have strictly increasing atBeat"
        }
        require(pickupBeats <= beatsPerMeasure(timeSignatures.first())) {
            "pickupBeats ($pickupBeats) must be at most one full measure " +
                "(${beatsPerMeasure(timeSignatures.first())} beats) at the opening signature (songpack-v1.md §7.1)"
        }
    }

    private fun beatsPerMeasure(signature: TimeSignature): Double =
        signature.numerator * 4.0 / signature.denominator

    /** The active time signature's beats-per-measure at [beat]. */
    private fun beatsPerMeasureAtMeasureStart(beat: Double): Double {
        var index = 0
        while (index + 1 < timeSignatures.size && timeSignatures[index + 1].atBeat <= beat) index++
        return beatsPerMeasure(timeSignatures[index])
    }

    // Precomputed measure starts when the duration is known (the normal app
    // path): [0, pickup, pickup+B1, ...] plus a sentinel — the first measure
    // start at/after duration — so binary search stays exact. With an unknown
    // duration the mapping falls back to a cursor walk (see measureIndexWalk).
    private val measureStarts: List<Double> = if (durationBeats.isFinite()) {
        buildList {
            add(0.0) // measure 0: the pickup measure (empty when pickupBeats == 0)
            var cursor = pickupBeats
            while (cursor < durationBeats) {
                add(cursor)
                cursor += beatsPerMeasureAtMeasureStart(cursor)
            }
            add(cursor) // sentinel
        }
    } else {
        emptyList()
    }

    /**
     * The 0-based measure containing [beat] (0 = the pickup measure).
     *
     * Half-open semantics: a beat exactly on a bar line belongs to the
     * measure that STARTS there. Beats at/after the end of the last measure
     * (when [durationBeats] is finite) clamp to the last measure's index.
     *
     * @throws IllegalArgumentException if [beat] < 0.
     */
    fun measureIndex(beat: Double): Int {
        require(beat >= 0.0) { "beat must be >= 0, was $beat" }
        if (beat < pickupBeats) return 0
        if (durationBeats.isFinite()) {
            if (beat >= durationBeats) return measureStarts.size - 2
            // Rightmost start <= beat (sentinel keeps this well-defined).
            var lo = 0
            var hi = measureStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (measureStarts[mid] <= beat) lo = mid else hi = mid - 1
            }
            return lo
        }
        return measureIndexWalk(beat)
    }

    /** Cursor walk used when the duration is unknown (measures extend forever). */
    private fun measureIndexWalk(beat: Double): Int {
        var measure = 1
        var start = pickupBeats
        while (true) {
            val length = beatsPerMeasureAtMeasureStart(start)
            if (beat < start + length) return measure
            start += length
            measure++
        }
    }

    /**
     * The start beat of measure [measureIndex] (0 = the pickup measure's
     * start, always 0.0). With a finite [durationBeats], [measureIndex] may
     * range over 0..lastMeasureIndex+1 (the +1 is the sentinel measure that
     * starts exactly at the duration, useful for chunk-boundary math).
     */
    fun measureStartBeat(measureIndex: Int): Double {
        require(measureIndex >= 0) { "measureIndex must be >= 0, was $measureIndex" }
        if (durationBeats.isFinite()) {
            require(measureIndex < measureStarts.size) {
                "measureIndex $measureIndex out of range; last startable measure is ${measureStarts.size - 1}"
            }
            return measureStarts[measureIndex]
        }
        var measure = 1
        var start = pickupBeats
        while (measure < measureIndex) {
            start += beatsPerMeasureAtMeasureStart(start)
            measure++
        }
        return start
    }

    /**
     * Beats per measure at [beat]: the active time signature at that beat.
     * SongPack places time-signature entries on measure starts, so this
     * equals the length of the measure containing [beat].
     */
    fun beatsPerMeasureAt(beat: Double): Double {
        require(beat >= 0.0) { "beat must be >= 0, was $beat" }
        return beatsPerMeasureAtMeasureStart(beat)
    }
}