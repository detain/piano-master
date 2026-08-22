package com.keyquest.app.notation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math tests for the scrolling-notation prototype (plan §7.1, P0.5.1):
 * songTime mapping, note-x, viewport culling, note-bar lane placement, and
 * grand-staff line placement. Everything under test is a pure function — no
 * Android framework, so these run on the JVM in milliseconds.
 */
class LayoutMathTest {

    // ------------------------------------------------------------------
    // songTime = f(frameClock, tempo)
    // ------------------------------------------------------------------

    @Test
    fun songTimeIsZeroAtStart() {
        assertEquals(0.0, LayoutMath.songTime(1_000_000_000L, 1_000_000_000L, 120.0), 1e-9)
    }

    @Test
    fun songTimeIsMonotonicInFrameTime() {
        val start = 0L
        var previous = LayoutMath.songTime(start, start, 120.0)
        for (i in 1..1_000) {
            val frameTime = start + i * 16_666_667L // one 60fps frame step
            val current = LayoutMath.songTime(frameTime, start, 120.0)
            assertTrue("songTime must increase: $current <= $previous", current > previous)
            previous = current
        }
    }

    @Test
    fun songTimeConvertsTempoToBeatsPerSecond() {
        // 120 bpm = 2 beats/sec; 60 bpm = 1 beat/sec.
        assertEquals(2.0, LayoutMath.songTime(1_000_000_000L, 0L, 120.0), 1e-9)
        assertEquals(1.0, LayoutMath.songTime(1_000_000_000L, 0L, 60.0), 1e-9)
    }

    // ------------------------------------------------------------------
    // note-x (right-to-left scroll, fixed playhead)
    // ------------------------------------------------------------------

    @Test
    fun noteXIsOnThePlayheadWhenBeatEqualsSongTime() {
        val width = 1_000f
        val playheadX = width * LayoutMath.DEFAULT_PLAYHEAD_FRACTION
        assertEquals(playheadX, LayoutMath.noteX(5.0, 5.0, 90f, width = width), 0.001f)
    }

    @Test
    fun noteXIncreasesAsBeatApproachesThePlayhead() {
        val songTime = 4.0
        val width = 1_000f
        var previous = LayoutMath.noteX(0.0, songTime, 90f, width = width)
        for (beat in 1..20) {
            val x = LayoutMath.noteX(beat.toDouble(), songTime, 90f, width = width)
            assertTrue("noteX must increase toward the playhead: $x <= $previous", x > previous)
            previous = x
        }
    }

    @Test
    fun futureNotesSitRightOfThePlayheadAndPastNotesLeft() {
        val width = 1_000f
        val playheadX = width * LayoutMath.DEFAULT_PLAYHEAD_FRACTION
        assertTrue(LayoutMath.noteX(10.0, 5.0, 90f, width = width) > playheadX) // future
        assertTrue(LayoutMath.noteX(0.0, 5.0, 90f, width = width) < playheadX) // past
    }

    // ------------------------------------------------------------------
    // viewport culling
    // ------------------------------------------------------------------

    private fun note(startBeat: Double, durBeats: Double = 0.5) = ProtoNote(
        pitch = 60,
        startBeat = startBeat,
        durBeats = durBeats,
        hand = 'R',
        staff = 1,
        lane = 0,
        xHint = startBeat * 1000.0,
    )

    @Test
    fun visibleNotesExcludesNotesBeforeTheWindowAndAfterTheLookahead() {
        val notes = listOf(
            note(startBeat = 5.0), // entirely before the visible window
            note(startBeat = 8.0), // straddles songTime -> visible
            note(startBeat = 25.0), // beyond the lookahead -> excluded
        )
        val visible = LayoutMath.visibleNotes(
            notes = notes,
            songTimeBeats = 10.0,
            lookaheadBeats = 10.0,
            pxPerBeat = 90f,
            width = 1_000f,
        )
        assertEquals(listOf(notes[1]), visible)
    }

    @Test
    fun visibleNotesIncludesANoteStraddlingThePlayhead() {
        val straddler = note(startBeat = 1.0, durBeats = 2.0) // spans songTime 2.0
        val visible = LayoutMath.visibleNotes(
            notes = listOf(straddler),
            songTimeBeats = 2.0,
            lookaheadBeats = 10.0,
            pxPerBeat = 90f,
            width = 1_000f,
        )
        assertEquals(listOf(straddler), visible)
    }

    @Test
    fun visibleNotesReturnsEmptyForEmptyInputAndZeroWidth() {
        assertTrue(LayoutMath.visibleNotes(emptyList(), 2.0, 10.0, 90f, 1_000f).isEmpty())
        assertTrue(LayoutMath.visibleNotes(listOf(note(2.0)), 2.0, 10.0, 90f, 0f).isEmpty())
    }

    @Test
    fun visibleNotesCullsANoteEndingExactlyAtTheLeftWindowEdge() {
        // The window is half-open [firstVisibleBeat, lastVisibleBeat): a note
        // whose right edge lands exactly on firstVisibleBeat is entirely behind.
        val width = 1_000f
        val pxPerBeat = 90f
        val songTime = 10.0
        val firstVisibleBeat = songTime - (width * LayoutMath.DEFAULT_PLAYHEAD_FRACTION) / pxPerBeat
        val endingAtEdge = note(startBeat = firstVisibleBeat - 1.0, durBeats = 1.0)
        assertTrue(
            LayoutMath.visibleNotes(
                notes = listOf(endingAtEdge),
                songTimeBeats = songTime,
                lookaheadBeats = 10.0,
                pxPerBeat = pxPerBeat,
                width = width,
            ).isEmpty(),
        )
    }

    @Test
    fun visibleNotesKeepsANoteStartingExactlyAtTheRightWindowEdge() {
        val width = 1_000f
        val pxPerBeat = 90f
        val songTime = 10.0
        val lastVisibleBeat = songTime + 10.0
        val startingAtEdge = note(startBeat = lastVisibleBeat)
        assertEquals(
            listOf(startingAtEdge),
            LayoutMath.visibleNotes(
                notes = listOf(startingAtEdge),
                songTimeBeats = songTime,
                lookaheadBeats = 10.0,
                pxPerBeat = pxPerBeat,
                width = width,
            ),
        )
    }

    // ------------------------------------------------------------------
    // pre-laid-out geometry (translate + draw contract, plan §7.1)
    // ------------------------------------------------------------------

    @Test
    fun noteLayoutScreenXTranslatesBaseXBySongTime() {
        val layout = NoteLayout(
            note = note(startBeat = 4.0),
            baseX = 300f,
            y = 0f,
            width = 10f,
            height = 8f,
            glyph = NoteGlyph.QUARTER,
            label = "C",
        )
        // screenX = baseX - songTime * pxPerBeat (the score scrolls left).
        assertEquals(300f, layout.screenX(0.0, 90f), 0.001f)
        assertEquals(300f - 180f, layout.screenX(2.0, 90f), 0.001f)
        assertEquals(300f - 450f, layout.screenX(5.0, 90f), 0.001f)
    }

    @Test
    fun sharpsAndFlatsShiftStaffPlacementByHalfASpace() {
        val spacePx = 10f
        val trebleTop = 30f
        val c = LayoutMath.staffLineY(60, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop)
        val cSharp = LayoutMath.staffLineY(61, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop)
        val a = LayoutMath.staffLineY(69, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop)
        val bFlat = LayoutMath.staffLineY(70, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop)
        // C# is 35.5 units (half a space above C at 35); Bb is 40.5 (above A at 40).
        assertEquals(130f, c, 0.001f)
        assertEquals(125f, cSharp, 0.001f)
        assertEquals(0.5f * spacePx, c - cSharp, 0.001f)
        assertEquals(0.5f * spacePx, a - bFlat, 0.001f)
    }

    // ------------------------------------------------------------------
    // note-bar lanes: 5 per hand, deterministic wrap
    // ------------------------------------------------------------------

    @Test
    fun noteBarLaneIndexMapsHandsToDisjointRanges() {
        assertEquals(0, LayoutMath.noteBarLaneIndex('L', 0))
        assertEquals(4, LayoutMath.noteBarLaneIndex('L', 4))
        assertEquals(5, LayoutMath.noteBarLaneIndex('R', 0))
        assertEquals(9, LayoutMath.noteBarLaneIndex('R', 4))
    }

    @Test
    fun noteBarLaneAssignmentWrapsAcrossTenNotesDeterministically() {
        fun laneOf(i: Int) = LayoutMath.noteBarLaneIndex(hand = if (i % 2 == 0) 'L' else 'R', lane = i % 5)
        // Even i (L): lanes 0,2,4 then wrap 1,3 -> indices 0,2,4,1,3.
        // Odd i (R): lanes 1,3,0,2,4 -> indices 6,8,5,7,9.
        val expected = listOf(0, 6, 2, 8, 4, 5, 1, 7, 3, 9)
        assertEquals(expected, (0 until 10).map { laneOf(it) })
    }

    @Test
    fun noteBarYGrowsWithLaneAndInsertsTheSplitGapAfterTheLeftHand() {
        val laneHeight = 20f
        val gap = 16f
        val top = 8f
        assertEquals(8f, LayoutMath.noteBarY(0, laneHeight, gap, top, lanesPerHand = 5), 0.001f)
        assertEquals(8f + 4 * 20f, LayoutMath.noteBarY(4, laneHeight, gap, top, lanesPerHand = 5), 0.001f)
        // Lane 5 (right hand's first) sits below lane 4 PLUS the split gap.
        assertEquals(8f + 5 * 20f + 16f, LayoutMath.noteBarY(5, laneHeight, gap, top, lanesPerHand = 5), 0.001f)
        assertTrue(LayoutMath.noteBarY(5, laneHeight, gap, top, lanesPerHand = 5) > LayoutMath.noteBarY(4, laneHeight, gap, top, lanesPerHand = 5))
    }

    @Test
    fun noteBarYRespectsLanesPerHand() {
        // With 3 lanes per hand the split gap starts at lane 3, not 5.
        assertEquals(3 * 20f + 16f, LayoutMath.noteBarY(3, 20f, 16f, lanesPerHand = 3), 0.001f)
        assertEquals(4 * 20f + 16f, LayoutMath.noteBarY(4, 20f, 16f, lanesPerHand = 3), 0.001f)
        // Lane indices outside 0..2*lanesPerHand-1 are rejected.
        assertThrows(IllegalArgumentException::class.java) {
            LayoutMath.noteBarY(6, 20f, 16f, lanesPerHand = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutMath.noteBarY(-1, 20f, 16f, lanesPerHand = 3)
        }
    }

    // ------------------------------------------------------------------
    // grand staff placement
    // ------------------------------------------------------------------

    @Test
    fun middleCIsOnTheExpectedLedgerLineInBothStaves() {
        val spacePx = 10f
        val middleCY = 130f
        val trebleTop = LayoutMath.staffTopLineY(middleCY, LayoutMath.StaffZone.TREBLE, spacePx)
        val bassTop = LayoutMath.staffTopLineY(middleCY, LayoutMath.StaffZone.BASS, spacePx)
        // Middle C resolves to the same y (the shared ledger line) on both staves.
        assertEquals(middleCY, LayoutMath.staffLineY(60, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop), 0.001f)
        assertEquals(middleCY, LayoutMath.staffLineY(60, LayoutMath.StaffZone.BASS, spacePx, bassTop), 0.001f)
    }

    @Test
    fun trebleStaffLinesLandExactlyOnTheFiveLines() {
        val spacePx = 10f
        val trebleTop = 30f
        // Bottom->top: E4 G4 B4 D5 F5 = 64 67 71 74 77.
        assertEquals(110f, LayoutMath.staffLineY(64, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop), 0.001f)
        assertEquals(90f, LayoutMath.staffLineY(67, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop), 0.001f)
        assertEquals(70f, LayoutMath.staffLineY(71, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop), 0.001f)
        assertEquals(50f, LayoutMath.staffLineY(74, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop), 0.001f)
        assertEquals(30f, LayoutMath.staffLineY(77, LayoutMath.StaffZone.TREBLE, spacePx, trebleTop), 0.001f)
    }

    @Test
    fun bassStaffLinesLandExactlyOnTheFiveLines() {
        val spacePx = 10f
        val bassTop = 30f
        // Bottom->top: G2 B2 D3 F3 A3 = 43 47 50 53 57.
        assertEquals(110f, LayoutMath.staffLineY(43, LayoutMath.StaffZone.BASS, spacePx, bassTop), 0.001f)
        assertEquals(70f, LayoutMath.staffLineY(50, LayoutMath.StaffZone.BASS, spacePx, bassTop), 0.001f)
        assertEquals(30f, LayoutMath.staffLineY(57, LayoutMath.StaffZone.BASS, spacePx, bassTop), 0.001f)
    }

    // ------------------------------------------------------------------
    // misc pure helpers
    // ------------------------------------------------------------------

    @Test
    fun accidentalSitsLeftOfTheNotehead() {
        assertTrue(LayoutMath.accidentalX(100f) < 100f)
        assertEquals(78f, LayoutMath.accidentalX(100f), 0.001f) // 100 - 8 gap - 14 width
    }

    @Test
    fun noteLetterUsesTheRightNamesAcrossTheHalfSteps() {
        assertEquals("C", LayoutMath.noteLetter(60))
        assertEquals("C", LayoutMath.noteLetter(61))
        assertEquals("D", LayoutMath.noteLetter(62))
        assertEquals("E", LayoutMath.noteLetter(64))
        assertEquals("F", LayoutMath.noteLetter(65))
        assertEquals("F", LayoutMath.noteLetter(66))
        assertEquals("G", LayoutMath.noteLetter(67))
        assertEquals("A", LayoutMath.noteLetter(69))
        assertEquals("B", LayoutMath.noteLetter(71))
        assertEquals("C", LayoutMath.noteLetter(72))
    }

    @Test
    fun noteLetterWithAccidentalSuffixesDisplayOnly() {
        assertEquals("C#", LayoutMath.noteLetterWithAccidental(61, Accidental.SHARP))
        assertEquals("Ab", LayoutMath.noteLetterWithAccidental(70, Accidental.FLAT))
        assertEquals("C", LayoutMath.noteLetterWithAccidental(60, null))
    }

    // ------------------------------------------------------------------
    // stress score factory (P0.5.2) + layout builder determinism
    // ------------------------------------------------------------------

    @Test
    fun stressScoreIsDeterministicAndMeetsTheStressBar() {
        val a = ProtoScoreFactory.stressScore()
        val b = ProtoScoreFactory.stressScore()
        assertEquals(a, b)
        assertTrue("stress score must have >= 200 notes", a.notes.size >= 200)
        assertTrue(a.notes.any { it.hand == 'L' })
        assertTrue(a.notes.any { it.hand == 'R' })
        assertTrue(a.notes.any { it.staff == 1 })
        assertTrue(a.notes.any { it.staff == 2 })
        assertTrue("dense beaming: beam groups must exist", a.notes.any { it.beamGroup != null })
    }

    @Test
    fun stressScoreDiffersForDifferentSeeds() {
        assertNotEquals(ProtoScoreFactory.stressScore(seed = 1L), ProtoScoreFactory.stressScore(seed = 2L))
    }

    @Test
    fun stressScoreContainsTiesWithValidTargets() {
        val score = ProtoScoreFactory.stressScore()
        val tied = score.notes.filter { it.tieToIndex != null }
        assertTrue("stress score must produce ties for the staff skin to draw", tied.isNotEmpty())
        for (note in tied) {
            val target = note.tieToIndex!!
            assertTrue("tieToIndex $target must be a valid note index", target in score.notes.indices)
            assertEquals("tied notes must share a pitch", note.pitch, score.notes[target].pitch)
        }
    }

    @Test
    fun stressScoreWithZeroSeedStillEmitsVariedVoices() {
        val score = ProtoScoreFactory.stressScore(seed = 0L, noteCount = 240)
        val chordSizes = score.notes.groupBy { it.startBeat }.values.map { it.size }
        assertTrue("seed 0 must still produce chords", chordSizes.any { it >= 2 })
        assertTrue("seed 0 must still produce single-voice slots", chordSizes.any { it == 1 })
    }

    @Test
    fun staffBeamsNeverConnectSimultaneousNotes() {
        val score = ProtoScoreFactory.stressScore()
        val staff = NoteLayoutBuilder.build(score, NotationSkin.Staff, 90f, 1_200f, 360f).staff!!
        for (beam in staff.beams) {
            val a = score.notes[beam.noteAIndex]
            val b = score.notes[beam.noteBIndex]
            assertTrue(
                "beam must connect successive notes, not chord voices (both at ${a.startBeat})",
                a.startBeat != b.startBeat,
            )
        }
    }

    @Test
    fun layoutBuilderProducesCompleteLayoutsForBothSkins() {
        val score = ProtoScoreFactory.stressScore()
        val noteBar = NoteLayoutBuilder.build(score, NotationSkin.NoteBar, 90f, 1_200f, 360f)
        assertEquals(score.notes.size, noteBar.notes.size)
        assertNotNull(noteBar.noteBar)
        assertEquals(score.notes.size, noteBar.notes.map { it.note }.toSet().size)

        val staff = NoteLayoutBuilder.build(score, NotationSkin.Staff, 90f, 1_200f, 360f)
        assertEquals(score.notes.size, staff.notes.size)
        assertNotNull(staff.staff)
        assertTrue("staff skin must pre-layout beam geometry", staff.staff!!.beams.isNotEmpty())
    }
}