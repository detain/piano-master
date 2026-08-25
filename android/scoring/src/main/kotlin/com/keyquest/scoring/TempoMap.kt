package com.keyquest.scoring

import kotlin.math.abs
import kotlin.math.ln

/**
 * How the tempo moves between two tempo-map entries (SongPack v1 `curve`
 * enum, docs/specs/songpack-v1.md §6.1): `step` = instant change,
 * `linear` = rit./accel. ramping to the next entry's bpm.
 */
enum class TempoCurve { STEP, LINEAR }

/**
 * One entry of the tempo map (SongPack v1 `tempoMap[]` entry).
 *
 * @property atBeat beat at which this entry takes effect (>= 0).
 * @property bpm beats per minute at this point (> 0).
 * @property curve how to reach the next entry's bpm; the last entry's curve
 *   is irrelevant (its bpm simply holds).
 */
data class TempoPoint(
    val atBeat: Double,
    val bpm: Double,
    val curve: TempoCurve = TempoCurve.STEP,
) {
    init {
        require(atBeat >= 0.0) { "atBeat must be >= 0, was $atBeat" }
        require(bpm > 0.0) { "bpm must be > 0, was $bpm" }
    }
}

/**
 * The song's tempo map: beats -> seconds, integrating `step` and `linear`
 * curves piecewise. This is a faithful Kotlin port of the pipeline reference
 * implementation so the two cannot drift silently:
 *
 *   pipeline/pipeline/build/audio.py — `_interval_seconds()`,
 *   `_linear_bpm_at()`, `_bpm_seconds()` (P1.2, reviewed M4).
 *
 * Semantics to preserve EXACTLY (any change here must be mirrored there):
 *  - `step` curve holds its bpm until the next entry; the last entry extends
 *    at its own bpm.
 *  - `linear` curve ramps across the segment [atBeat_i, atBeat_{i+1}); the
 *    segment's seconds are the log-integral `(60/slope) * ln(bpm_b/bpm_a)`,
 *    falling back to the constant-bpm formula when `|bpm_b - bpm_a| < 1e-12`.
 *  - a note crossing a tempo change integrates over its whole beat span, so
 *    it renders at the correct length (audio.py review M4).
 *
 * @property points tempo entries, stored sorted by `atBeat`. The first entry
 *   must be at beat 0 (SongPack validator-enforced; tempoMap must cover
 *   beat 0).
 */
class TempoMap(entries: List<TempoPoint>) {

    val points: List<TempoPoint> = entries.sortedBy { it.atBeat }

    init {
        require(points.isNotEmpty()) { "tempo map must not be empty" }
        require(points.first().atBeat == 0.0) {
            "first tempo entry must be at beat 0.0, was ${points.first().atBeat}"
        }
        require(points.zipWithNext().all { (a, b) -> b.atBeat > a.atBeat }) {
            "tempo entries must have strictly increasing atBeat"
        }
    }

    /** The last entry whose atBeat is <= [beat]. */
    private fun lastEntryAtOrBefore(beat: Double): Int {
        var index = 0
        while (index + 1 < points.size && points[index + 1].atBeat <= beat) index++
        return index
    }

    /**
     * The active bpm at [beat].
     *
     * Mirrors audio.py `_linear_bpm_at` for linear segments: `step` holds the
     * current entry's bpm until the next entry's atBeat; `linear`
     * interpolates across the segment; a beat at or beyond the last entry
     * takes that entry's bpm. Beats before the first entry (beat 0, the only
     * legal case since all note beats are >= 0) take the first entry's bpm.
     */
    fun bpmAt(beat: Double): Double {
        if (beat <= points.first().atBeat) return points.first().bpm
        val index = lastEntryAtOrBefore(beat)
        val entry = points[index]
        if (index == points.lastIndex) return entry.bpm
        val next = points[index + 1]
        return when (entry.curve) {
            TempoCurve.STEP -> entry.bpm
            TempoCurve.LINEAR -> linearBpmAt(entry, next, beat)
        }
    }

    /**
     * The interpolated bpm inside a linear segment (audio.py
     * `_linear_bpm_at`): bpm_a + fraction * (bpm_b - bpm_a). At the segment's
     * end this equals the next entry's bpm exactly, so bpmAt is continuous
     * across linear segments.
     */
    private fun linearBpmAt(entry: TempoPoint, next: TempoPoint, beat: Double): Double {
        val span = next.atBeat - entry.atBeat
        if (span <= 0.0) return entry.bpm
        val fraction = (beat - entry.atBeat) / span
        return entry.bpm + fraction * (next.bpm - entry.bpm)
    }

    /**
     * Seconds spanned by a beat segment whose tempo ramps linearly from
     * [bpmA] to [bpmB] (constant when they are equal). Port of audio.py
     * `_bpm_seconds`: the constant case uses the plain formula; otherwise the
     * log-integral `(60/slope) * ln(bpm_b / bpm_a)`. The 1e-12 threshold is
     * the Python reference's, kept verbatim so results match to the last ulp.
     */
    private fun bpmSeconds(segmentStart: Double, segmentEnd: Double, bpmA: Double, bpmB: Double): Double {
        if (abs(bpmB - bpmA) < 1e-12) return (segmentEnd - segmentStart) * 60.0 / bpmA
        val slope = (bpmB - bpmA) / (segmentEnd - segmentStart)
        return (60.0 / slope) * ln(bpmB / bpmA)
    }

    /**
     * Absolute seconds at [beat] under this tempo map — the piecewise
     * integral of [0, beat], port of audio.py `_interval_seconds(0.0, beat,
     * entries)` with every branch mirrored:
     *  - `beat <= 0` -> 0.0;
     *  - entries are integrated segment by segment; a segment ends at the
     *    next entry's atBeat (the last entry extends forever);
     *  - `step` segments: `(segment_end - segment_start) * 60 / bpm`;
     *  - `linear` segments (only when a next entry exists beyond the current
     *    atBeat): bpm_a/bpm_b interpolated at the segment's own endpoints,
     *    then `bpmSeconds`.
     */
    fun beatToSeconds(beat: Double): Double {
        if (beat <= 0.0) return 0.0
        var total = 0.0
        for (index in points.indices) {
            val entry = points[index]
            val nextBeat = if (index + 1 < points.size) points[index + 1].atBeat else Double.POSITIVE_INFINITY
            if (beat <= entry.atBeat) break
            val segmentStart = maxOf(0.0, entry.atBeat)
            val segmentEnd = minOf(beat, nextBeat)
            if (segmentEnd <= segmentStart) continue
            if (entry.curve == TempoCurve.LINEAR && nextBeat.isFinite() && nextBeat > entry.atBeat) {
                val next = points[index + 1]
                val bpmA = linearBpmAt(entry, next, segmentStart)
                val bpmB = linearBpmAt(entry, next, segmentEnd)
                total += bpmSeconds(segmentStart, segmentEnd, bpmA, bpmB)
            } else {
                total += (segmentEnd - segmentStart) * 60.0 / entry.bpm
            }
        }
        return total
    }

    /** Convenience: [beatToSeconds] for a list of beats. */
    fun beatToSeconds(beats: List<Double>): List<Double> = beats.map(::beatToSeconds)
}