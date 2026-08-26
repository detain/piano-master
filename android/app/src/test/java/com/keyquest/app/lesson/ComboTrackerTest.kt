package com.keyquest.app.lesson

import com.keyquest.scoring.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ComboTrackerTest — unit tests for [ComboTracker] (plan §20 P1.6.6): hits
 * (PERFECT/GOOD) advance the streak, misses and wrong notes reset it, and
 * [ComboTracker.reset] zeroes both counters.
 *
 * JVM-only (unit test, no device).
 */
class ComboTrackerTest {

    @Test
    fun hitsIncrementComboAndBest() {
        val tracker = ComboTracker()
        tracker.onVerdict(Verdict.PERFECT)
        assertEquals(1, tracker.combo)
        assertEquals(1, tracker.bestCombo)
        tracker.onVerdict(Verdict.GOOD)
        assertEquals(2, tracker.combo)
        assertEquals(2, tracker.bestCombo)
    }

    @Test
    fun missResetsStreakButKeepsBest() {
        val tracker = ComboTracker()
        tracker.onVerdict(Verdict.PERFECT)
        tracker.onVerdict(Verdict.PERFECT)
        tracker.onVerdict(Verdict.GOOD)
        assertEquals(3, tracker.combo)
        assertEquals(3, tracker.bestCombo)
        tracker.onVerdict(Verdict.MISSED)
        assertEquals(0, tracker.combo)
        assertEquals(3, tracker.bestCombo)
        // The streak restarts from the next hit; bestCombo is untouched.
        tracker.onVerdict(Verdict.GOOD)
        assertEquals(1, tracker.combo)
        assertEquals(3, tracker.bestCombo)
    }

    @Test
    fun wrongNoteResetsLikeMiss() {
        val tracker = ComboTracker()
        tracker.onVerdict(Verdict.PERFECT)
        tracker.onVerdict(Verdict.WRONG)
        assertEquals(0, tracker.combo)
        assertEquals(1, tracker.bestCombo)
    }

    @Test
    fun resetZeroesBothCounters() {
        val tracker = ComboTracker()
        tracker.onVerdict(Verdict.PERFECT)
        tracker.onVerdict(Verdict.PERFECT)
        tracker.onVerdict(Verdict.PERFECT)
        assertEquals(3, tracker.combo)
        assertEquals(3, tracker.bestCombo)
        tracker.reset()
        assertEquals(0, tracker.combo)
        assertEquals(0, tracker.bestCombo)
    }
}