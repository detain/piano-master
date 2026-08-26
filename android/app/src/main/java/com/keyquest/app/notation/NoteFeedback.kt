package com.keyquest.app.notation

/**
 * Per-frame feedback state for the scrolling-notation renderer (plan §24
 * P1.6.5/6): a verdict and a hit timestamp per note, read by the player each
 * frame to color the noteheads and drive the hit pop.
 *
 * ## Array contract (zero per-frame allocation)
 * The screen owns the two arrays and reuses this same instance across frames:
 * it fills them in place from the scorer snapshot and hands the instance back
 * to the player. Neither array is ever reallocated per frame, and changing
 * feedback never triggers a layout rebuild — the player only reads the arrays.
 *
 * ## Index alignment
 * Both arrays are index-parallel to [ProtoScore.notes], which is the canonical
 * scorer order, so `verdicts[i]` and `hitBeats[i]` describe exactly the note
 * at `ProtoScore.notes[i]`.
 *
 * ## Verdicts
 * [OPEN] = not yet judged; [PERFECT] / [GOOD] = hit; [MISSED] = window closed
 * with nothing played; [WRONG] = wrong-pitch event(s) inside the window
 * (plan §7.3 colors: green fill on hit, red outline on miss, red key flash on
 * wrong).
 *
 * ## Hit pop
 * For a hit note, [hitBeats] holds the song-time beat (in beats, not seconds)
 * when the note was matched; the pop's decay is `f(nowBeats - hitBeats)`.
 * Unmatched notes carry `-1f`.
 */
class NoteFeedback(
    /** Per-note verdict, one of [OPEN], [PERFECT], [GOOD], [MISSED], [WRONG]. */
    val verdicts: IntArray,
    /** Song-time beat of the hit, or `-1f` when unmatched (drives the pop decay). */
    val hitBeats: FloatArray,
) {
    init {
        require(verdicts.size == hitBeats.size) {
            "NoteFeedback arrays must be index-parallel: verdicts has ${verdicts.size} " +
                "entries but hitBeats has ${hitBeats.size}"
        }
    }

    companion object {
        /** Note has not been judged yet. */
        const val OPEN = 0

        /** Hit within the PERFECT band: green fill + pop. */
        const val PERFECT = 1

        /** Hit beyond the PERFECT band but inside the window: dimmer green fill. */
        const val GOOD = 2

        /** Window closed with nothing played: red outline as the playhead passes. */
        const val MISSED = 3

        /** Wrong-pitch event(s) inside the window: red flash on the played key. */
        const val WRONG = 4

        /**
         * A reusable all-open feedback of [count] notes: every verdict is
         * [OPEN] and every hit beat is `-1f`. Create one instance up front,
         * then refill the arrays in place each frame — see the class contract.
         */
        fun open(count: Int): NoteFeedback {
            require(count >= 0) { "NoteFeedback.open needs a non-negative note count, was $count" }
            return NoteFeedback(
                verdicts = IntArray(count) { OPEN },
                hitBeats = FloatArray(count) { -1f },
            )
        }
    }
}