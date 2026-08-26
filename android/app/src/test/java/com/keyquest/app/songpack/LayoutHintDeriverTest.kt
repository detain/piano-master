package com.keyquest.app.songpack

import com.keyquest.scoring.Hand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LayoutHintDeriverTest — unit tests for [LayoutHintDeriver], the pure,
 * deterministic derivation of note-bar lanes, beam groups and x hints that
 * pipeline v0 does not precompute (same input list, same output list).
 *
 * Expected values are computed by hand from the derivation rules: lanes are
 * distinct-pitch ranks within the hand folded onto 5 lanes per hand; beam
 * groups are per-staff runs of consecutive notes no longer than half a beat;
 * x hints are `startBeat * 1000`.
 *
 * JVM-only (unit test, no device).
 */
class LayoutHintDeriverTest {

    @Test
    fun lanesArePitchRankWithinHand() {
        // Distinct R pitches 60, 64, 67 rank 0, 1, 2 ascending; the repeated
        // 60 keeps rank 0. L pitches rank within L only: 48 is rank 0 even
        // though R already holds three ranks.
        val hints = LayoutHintDeriver.derive(
            listOf(
                note(60, 0.0),
                note(67, 1.0),
                note(64, 0.5),
                note(60, 2.0),
                note(48, 3.0, hand = Hand.L),
            ),
        )
        assertEquals(listOf(0, 2, 1, 0, 0), hints.map { it.lane })
    }

    @Test
    fun lanesFoldPastFiveDistinctPitches() {
        // Six distinct R pitches exceed the note bar's 5 lanes per hand;
        // rank 5 folds back to lane 0 (LANES_PER_HAND = 5).
        val notes = listOf(60, 62, 64, 65, 67, 69)
            .mapIndexed { index, pitch -> note(pitch, index.toDouble()) }
        val hints = LayoutHintDeriver.derive(notes)
        assertEquals(listOf(0, 1, 2, 3, 4, 0), hints.map { it.lane })
    }

    @Test
    fun beamGroupRunsOfShortNotesPerStaff() {
        val short = 0.25
        val staffOne = listOf(
            note(60, 0.0, durBeats = short),
            note(62, 0.5, durBeats = short),
            note(64, 1.0, durBeats = short),
            note(65, 1.5, durBeats = short),
            note(67, 2.0), // long note starts a fresh group
            note(69, 3.0, durBeats = short),
            note(72, 3.5, durBeats = short),
        )
        val hints = LayoutHintDeriver.derive(staffOne)
        assertEquals(
            "consecutive short notes share one group; a long note starts a fresh one",
            listOf(1, 1, 1, 1, 2, 3, 3),
            hints.map { it.beamGroup },
        )

        // A staff-2 voice interleaved in time must not split the staff-1
        // run: groups are computed per staff.
        val withLeftHand = staffOne + note(48, 1.25, durBeats = 0.5, hand = Hand.L, staff = 2)
        val hintsWithLeftHand = LayoutHintDeriver.derive(withLeftHand)
        assertEquals(
            "a staff-2 note in between must not split staff-1 runs",
            listOf(1, 1, 1, 1, 2, 3, 3),
            hintsWithLeftHand.take(7).map { it.beamGroup },
        )
    }

    @Test
    fun xHintIsStartBeatTimesThousand() {
        val hints = LayoutHintDeriver.derive(listOf(note(60, 2.5)))
        assertEquals(2500.0, hints.single().xHint, 0.0)
    }

    @Test
    fun emptyInputGivesEmpty() {
        assertTrue(LayoutHintDeriver.derive(emptyList()).isEmpty())
    }

    @Test
    fun inputOrderAligned() {
        // Mixed hands and staffs: hints must come back in input order, not in
        // pitch or staff order.
        val notes = listOf(
            note(60, 0.0),
            note(48, 0.5, hand = Hand.L, staff = 2),
            note(67, 1.0),
            note(55, 1.5, hand = Hand.L, staff = 2),
        )
        val hints = LayoutHintDeriver.derive(notes)
        assertEquals(listOf(0, 0, 1, 1), hints.map { it.lane })
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0), hints.map { it.xHint })
        assertEquals(listOf(1, 1, 2, 2), hints.map { it.beamGroup })
    }

    @Test
    fun laneOutOfRangeRejected() {
        // The note bar has LANES_PER_HAND lanes per hand; a lane outside
        // 0..4 is an unrepresentable state and must fail fast at construction.
        val e = assertThrows(IllegalArgumentException::class.java) {
            LayoutHintDeriver.NoteHints(lane = 5, xHint = 0.0, beamGroup = null)
        }
        assertTrue(
            "offending lane must be named, was: ${e.message}",
            e.message!!.contains("lane"),
        )
    }

    /** Builds a [SongNote] with the defaults the layout rules rely on. */
    private fun note(
        pitch: Int,
        startBeat: Double,
        durBeats: Double = 1.0,
        hand: Hand = Hand.R,
        staff: Int = 1,
    ): SongNote = SongNote(pitch, startBeat, durBeats, hand, staff)
}