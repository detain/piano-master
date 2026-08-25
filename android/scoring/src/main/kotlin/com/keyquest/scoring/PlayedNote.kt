package com.keyquest.scoring

/**
 * A note the player actually played, as captured by the input path.
 *
 * Mirrors the app's `NoteEvent` (com.keyquest.app.audio) but is deliberately
 * source-agnostic: the app adapts its `NoteEvent` stream into `PlayedNote`s
 * before scoring (plan §6, §20 P1.5). Pure data — no behavior.
 *
 * @property pitch MIDI pitch, 0..127 (app range is 21..108, but the scorer
 *   accepts the full MIDI range so any input source can be adapted).
 * @property velocity key velocity, 0..127 (currently unused by scoring;
 *   carried for telemetry).
 * @property onTimeNs when the key went down, in nanoseconds since the session
 *   start. Negative times are rejected: a session clock starts at 0.
 * @property offTimeNs when the key went up, or -1 for a still-held note.
 */
data class PlayedNote(
    val pitch: Int,
    val velocity: Int,
    val onTimeNs: Long,
    val offTimeNs: Long,
) {
    init {
        require(pitch in 0..127) { "pitch must be in 0..127, was $pitch" }
        require(velocity in 0..127) { "velocity must be in 0..127, was $velocity" }
        require(onTimeNs >= 0L) { "onTimeNs must be >= 0, was $onTimeNs" }
        require(offTimeNs == -1L || offTimeNs >= onTimeNs) {
            "offTimeNs must be -1 or >= onTimeNs, was offTimeNs=$offTimeNs onTimeNs=$onTimeNs"
        }
    }
}