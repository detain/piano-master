package com.keyquest.scoring.replay

import com.keyquest.scoring.ExpectedNote
import com.keyquest.scoring.Hand
import com.keyquest.scoring.PlayedNote
import com.keyquest.scoring.TempoCurve
import com.keyquest.scoring.TempoMap
import com.keyquest.scoring.TempoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionFormat tests (plan §20 P1.5.6): the TSV session format round-trips,
 * skips comments/blank lines, and reports parse errors with line numbers.
 */
class SessionFormatTest {

    private fun collect(emit: ((String) -> Unit) -> Unit): MutableList<String> {
        val lines = mutableListOf<String>()
        emit { lines.add(it) }
        return lines
    }

    // ------------------------------------------------------------------
    // writers
    // ------------------------------------------------------------------

    @Test
    fun writeEventsEmitsHeaderAndOneLinePerEvent() {
        val lines = collect { writer -> SessionFormat.writeEvents(listOf(PlayedNote(60, 100, 0L, -1L)), writer) }
        assertEquals(SessionFormat.HEADER, lines[0])
        assertEquals("60\t100\t0\t-1", lines[1])
        assertEquals(2, lines.size)
    }

    @Test
    fun writeExpectedEmitsHeaderAndOneLinePerNote() {
        val lines = collect { writer ->
            SessionFormat.writeExpected(
                listOf(ExpectedNote(60, 0.0, 1.0, Hand.R, 1.0), ExpectedNote(67, 1.0, 0.5, Hand.L, 0.2)),
                writer,
            )
        }
        assertEquals(SessionFormat.HEADER, lines[0])
        assertEquals("60\t0.0\t1.0\tR\t1.0", lines[1])
        assertEquals("67\t1.0\t0.5\tL\t0.2", lines[2])
    }

    @Test
    fun writeTempoMapEmitsStepAndLinearCurveNames() {
        val lines = collect { writer ->
            SessionFormat.writeTempoMap(
                listOf(
                    TempoPoint(atBeat = 0.0, bpm = 120.0, curve = TempoCurve.STEP),
                    TempoPoint(atBeat = 4.0, bpm = 90.0, curve = TempoCurve.LINEAR),
                ),
                writer,
            )
        }
        assertEquals(SessionFormat.HEADER, lines[0])
        assertEquals("0.0\t120.0\tstep", lines[1])
        assertEquals("4.0\t90.0\tlinear", lines[2])
    }

    // ------------------------------------------------------------------
    // round trips
    // ------------------------------------------------------------------

    @Test
    fun eventsRoundTripThroughWriteAndRead() {
        val events = listOf(
            PlayedNote(60, 100, 0L, -1L),
            PlayedNote(72, 88, 1_500_000_000L, 2_000_000_000L),
            PlayedNote(21, 1, 99L, 99L),
        )
        val lines = collect { writer -> SessionFormat.writeEvents(events, writer) }
        assertEquals(events, SessionFormat.readEvents(lines))
    }

    @Test
    fun expectedNotesRoundTripThroughWriteAndRead() {
        val notes = listOf(
            ExpectedNote(60, 0.0, 1.0, Hand.R, 1.0),
            ExpectedNote(64, 0.5, 0.5, Hand.L, 0.2),
            ExpectedNote(67, 2.0, 1.5, Hand.L, 0.0),
        )
        val lines = collect { writer -> SessionFormat.writeExpected(notes, writer) }
        assertEquals(notes, SessionFormat.readExpected(lines))
    }

    @Test
    fun tempoMapRoundTripsThroughWriteAndRead() {
        val points = listOf(
            TempoPoint(atBeat = 0.0, bpm = 120.0, curve = TempoCurve.STEP),
            TempoPoint(atBeat = 8.0, bpm = 60.0, curve = TempoCurve.LINEAR),
        )
        val lines = collect { writer -> SessionFormat.writeTempoMap(points, writer) }
        val read = SessionFormat.readTempoMap(lines)
        assertEquals(points, read.points)
        assertEquals(2, read.points.size)
    }

    // ------------------------------------------------------------------
    // comments and blank lines
    // ------------------------------------------------------------------

    @Test
    fun commentsAndBlankLinesAreSkipped() {
        val lines = listOf(
            "# keyquest scoring session v1",
            "",
            "   ",
            "60\t100\t0\t-1",
            "# a comment in the middle",
            "62\t90\t500000000\t-1",
        )
        val events = SessionFormat.readEvents(lines)
        assertEquals(2, events.size)
        assertEquals(60, events[0].pitch)
        assertEquals(62, events[1].pitch)
    }

    // ------------------------------------------------------------------
    // parse errors
    // ------------------------------------------------------------------

    @Test
    fun malformedEventLineThrowsWithLineNumber() {
        val lines = listOf(
            "# header",
            "60\t100\t0", // only 3 fields
        )
        val error = assertThrows(IllegalArgumentException::class.java) { SessionFormat.readEvents(lines) }
        assertTrue(error.message!!.contains("line 2"))
    }

    @Test
    fun nonNumericFieldThrowsWithLineNumber() {
        val lines = listOf("# header", "sixty\t100\t0\t-1")
        val error = assertThrows(IllegalArgumentException::class.java) { SessionFormat.readEvents(lines) }
        assertTrue(error.message!!.contains("line 2"))
        assertTrue(error.message!!.contains("pitch"))
    }

    @Test
    fun badHandIsRejected() {
        val lines = listOf("60\t0.0\t1.0\tX\t1.0")
        val error = assertThrows(IllegalArgumentException::class.java) { SessionFormat.readExpected(lines) }
        assertTrue(error.message!!.contains("line 1"))
    }

    @Test
    fun badCurveNameIsRejected() {
        val lines = listOf("0.0\t120.0\twiggle")
        val error = assertThrows(IllegalArgumentException::class.java) { SessionFormat.readTempoMap(lines) }
        assertTrue(error.message!!.contains("line 1"))
    }

    @Test
    fun wrongFieldCountInTempoLineIsRejected() {
        val lines = listOf("0.0\t120.0")
        val error = assertThrows(IllegalArgumentException::class.java) { SessionFormat.readTempoMap(lines) }
        assertTrue(error.message!!.contains("line 1"))
    }

    @Test
    fun invalidOffTimeIsRejected() {
        val lines = listOf("60\t100\t10\t5") // offTimeNs < onTimeNs
        assertThrows(IllegalArgumentException::class.java) { SessionFormat.readEvents(lines) }
    }

    @Test
    fun emptyInputParsesToEmptyList() {
        assertTrue(SessionFormat.readEvents(emptyList()).isEmpty())
        assertTrue(SessionFormat.readExpected(listOf("# only comments")).isEmpty())
    }
}