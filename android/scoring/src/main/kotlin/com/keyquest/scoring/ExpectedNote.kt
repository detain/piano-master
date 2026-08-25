package com.keyquest.scoring

/**
 * A note the player was supposed to play (plan §6 "the chunk's expected note
 * list (pitch, beatTime, duration, hand)").
 *
 * Maps 1:1 onto the SongPack v1 note record's scoring-relevant fields
 * (docs/specs/songpack-v1.md §3.2): `pitch`, `startBeat`, `durBeats`, `hand`,
 * `scoringWeight`. `scoringWeight` defaults to 1.0; the pipeline normalizes
 * ornament expansions to 0.2 and grace notes to 0 (pipeline stage 7 +
 * songpack-v1.md §3.2), so those are passed through here verbatim.
 *
 * @property pitch MIDI pitch, 0..127 (SongPack content is 21..108).
 * @property startBeat absolute beat from song start, pickup-adjusted
 *   (beat 0 = first pickup note). Seconds appear nowhere in note data
 *   (songpack-v1.md §2) — time conversion happens only inside TempoMap.
 * @property durBeats duration in beats, > 0 (grace notes still have a
 *   duration in SongPack; their weight is 0, not their length).
 * @property hand which hand should play this note (scorer uses `hand`, not
 *   `staff`).
 * @property scoringWeight contribution to the score denominator, 0..1.
 *   A weight of 0 (grace) can never raise the score but still counts toward
 *   verdicts and the heatmap.
 */
data class ExpectedNote(
    val pitch: Int,
    val startBeat: Double,
    val durBeats: Double,
    val hand: Hand,
    val scoringWeight: Double = 1.0,
) {
    init {
        require(pitch in 0..127) { "pitch must be in 0..127, was $pitch" }
        require(startBeat >= 0.0) { "startBeat must be >= 0, was $startBeat" }
        require(durBeats > 0.0) { "durBeats must be > 0, was $durBeats" }
        require(scoringWeight >= 0.0) { "scoringWeight must be >= 0, was $scoringWeight" }
    }
}