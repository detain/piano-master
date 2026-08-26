package com.keyquest.app.lesson

import com.keyquest.scoring.Verdict

/**
 * Tracks the player's combo streak from per-note verdicts (plan §20 P1.6.6).
 *
 * Combo counts PER-NOTE in freeze order: a 3-tone chord that lands as three
 * hits adds +3, one per frozen verdict. This implements open question (a)
 * from the plan §24 evening entry — per-note rather than per-chord counting
 * — and should be revisited with dogfooding.
 *
 * A hit is PERFECT or GOOD; MISSED and WRONG both break the streak (a wrong
 * pitch is still a failure to play the expected note, so it resets just like
 * a miss).
 *
 * @property combo current run of consecutive hits; 0 after a MISSED/WRONG
 *   verdict or [reset].
 * @property bestCombo maximum [combo] reached since construction or the
 *   last [reset].
 */
class ComboTracker {

    /** Current run of consecutive hits (PERFECT or GOOD verdicts). */
    var combo: Int = 0
        private set

    /** Maximum [combo] reached since construction or the last [reset]. */
    var bestCombo: Int = 0
        private set

    /** Advances the streak on a hit; breaks it to 0 on a miss or wrong note. */
    fun onVerdict(verdict: Verdict) {
        when (verdict) {
            Verdict.PERFECT, Verdict.GOOD -> combo++
            Verdict.MISSED, Verdict.WRONG -> combo = 0
        }
        if (combo > bestCombo) bestCombo = combo
    }

    /** Resets both [combo] and [bestCombo] to 0. */
    fun reset() {
        combo = 0
        bestCombo = 0
    }
}