package com.keyquest.scoring.replay

import com.keyquest.scoring.ExpectedNote
import com.keyquest.scoring.Hand
import com.keyquest.scoring.PlayedNote
import com.keyquest.scoring.ScoreConfig
import com.keyquest.scoring.StarThresholds
import com.keyquest.scoring.Verdict
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReplayRunner + ReplayMain tests (plan §20 P1.5.6): the full pure pipeline
 * TSV -> parse -> replay -> report, plus the ReplayMain shell through its
 * injected readLines/write seams (no real file I/O, so every branch is
 * covered).
 */
class ReplayRunnerTest {

    private val eventLines = listOf(
        "# keyquest scoring session v1",
        "60\t100\t0\t-1",
        "64\t100\t500000000\t-1",
        "67\t90\t1000000000\t-1",
    )

    private val expectedLines = listOf(
        "# keyquest scoring session v1",
        "60\t0.0\t1.0\tR\t1.0",
        "64\t1.0\t1.0\tR\t1.0",
        "67\t2.0\t1.0\tL\t0.5",
    )

    private val tempoLines = listOf(
        "# keyquest scoring session v1",
        "0.0\t120.0\tstep",
    )

    private fun fakeReader(linesByPath: Map<String, List<String>>): (String) -> List<String> =
        { path -> linesByPath[path] ?: throw IOException("no such file: $path") }

    private fun runReplay(args: Array<String>, reader: (String) -> List<String>): Pair<Int, List<String>> {
        val output = mutableListOf<String>()
        val exit = ReplayMain.run(args, reader, output::add)
        return exit to output
    }

    // ------------------------------------------------------------------
    // ReplayRunner (pure pipeline)
    // ------------------------------------------------------------------

    @Test
    fun fullPipelineParsesAndScores() {
        val events = SessionFormat.readEvents(eventLines)
        val expected = SessionFormat.readExpected(expectedLines)
        val tempoMap = SessionFormat.readTempoMap(tempoLines)
        val report = ReplayRunner.replay(events, expected, tempoMap, ScoreConfig(), StarThresholds())
        assertEquals(100.0, report.score, 1e-9) // 3 PERFECT hits capped
        assertEquals(3, report.stars)
        assertEquals(3, report.perfectCount)
        assertEquals(0, report.missedCount)
        assertTrue(report.extraEvents.isEmpty())
    }

    @Test
    fun replayWithNoEventsScoresZero() {
        val expected = SessionFormat.readExpected(expectedLines)
        val tempoMap = SessionFormat.readTempoMap(tempoLines)
        val report = ReplayRunner.replay(emptyList(), expected, tempoMap, ScoreConfig(), StarThresholds())
        assertEquals(0.0, report.score, 0.0)
        assertEquals(3, report.missedCount)
        // The default 4/4 mapper places all three notes in measure 1.
        assertEquals(mapOf(1 to com.keyquest.scoring.MeasureErrorSummary(missed = 3)), report.measureHeatmap)
    }

    @Test
    fun replayWithEmptyExpectedReturnsAnEmptyReport() {
        val tempoMap = SessionFormat.readTempoMap(tempoLines)
        val report = ReplayRunner.replay(SessionFormat.readEvents(eventLines), emptyList(), tempoMap, ScoreConfig(), StarThresholds())
        assertEquals(0.0, report.score, 0.0)
        assertTrue(report.outcomes.isEmpty())
    }

    @Test
    fun replayDerivesMeasureMappingFromTheExpectedNotes() {
        // Notes spread over two 4/4 measures: misses land in measures 1 and 2.
        val expected = listOf(
            ExpectedNote(60, 0.0, 1.0, Hand.R),
            ExpectedNote(64, 5.0, 1.0, Hand.R),
        )
        val report = ReplayRunner.replay(emptyList(), expected, SessionFormat.readTempoMap(tempoLines), ScoreConfig(), StarThresholds())
        assertEquals(
            mapOf(1 to com.keyquest.scoring.MeasureErrorSummary(missed = 1), 2 to com.keyquest.scoring.MeasureErrorSummary(missed = 1)),
            report.measureHeatmap,
        )
    }

    // ------------------------------------------------------------------
    // ReplayMain: happy paths
    // ------------------------------------------------------------------

    @Test
    fun mainRunsWithBpmAndWritesAReport() {
        val files = mapOf("events.tsv" to eventLines, "expected.tsv" to expectedLines)
        val (exit, output) = runReplay(arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "120"), fakeReader(files))
        assertEquals(0, exit)
        assertTrue(output.any { it.startsWith("score: 100.00") })
        assertTrue(output.any { it.contains("stars: 3/3") })
        assertTrue(output.any { it.contains("PERFECT 3") })
    }

    @Test
    fun mainRunsWithTempoMapFile() {
        val files = mapOf("events.tsv" to eventLines, "expected.tsv" to expectedLines, "tempo.tsv" to tempoLines)
        val (exit, output) = runReplay(
            arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--tempo-map", "tempo.tsv"),
            fakeReader(files),
        )
        assertEquals(0, exit)
        assertTrue(output.any { it.startsWith("score:") })
    }

    @Test
    fun mainAcceptsBeginnerAndCustomStarsFlags() {
        val files = mapOf("events.tsv" to eventLines, "expected.tsv" to expectedLines)
        val (exit, output) = runReplay(
            arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "120", "--beginner", "--stars", "50,70,90"),
            fakeReader(files),
        )
        assertEquals(0, exit)
        assertTrue(output.any { it.contains("stars: 3/3") })
    }

    @Test
    fun mainReportsExtrasAndHeatmapWhenThereAreMisses() {
        val files = mapOf(
            "events.tsv" to listOf("# header", "72\t100\t10000000000\t-1"),
            "expected.tsv" to expectedLines,
        )
        val (exit, output) = runReplay(
            arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "120"),
            fakeReader(files),
        )
        assertEquals(0, exit)
        assertTrue(output.any { it.startsWith("score: 0.00") })
        assertTrue(output.any { it.contains("extra events (unconsumed): 1") })
        assertTrue(output.any { it.contains("measure heatmap") })
    }

    // ------------------------------------------------------------------
    // ReplayMain: error paths
    // ------------------------------------------------------------------

    @Test
    fun mainRejectsMissingRequiredFlags() {
        val (exit, output) = runReplay(arrayOf("--bpm", "120"), fakeReader(emptyMap()))
        assertEquals(1, exit)
        assertTrue(output.any { it.startsWith("usage:") })
    }

    @Test
    fun mainRejectsBothTempoMapAndBpm() {
        val (exit, _) = runReplay(
            arrayOf("--events", "e", "--expected", "x", "--tempo-map", "t", "--bpm", "120"),
            fakeReader(emptyMap()),
        )
        assertEquals(1, exit)
    }

    @Test
    fun mainRejectsBpmWithoutValue() {
        val files = mapOf("events.tsv" to eventLines, "expected.tsv" to expectedLines)
        val (exit, _) = runReplay(arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm"), fakeReader(files))
        assertEquals(1, exit)
    }

    @Test
    fun mainRejectsStarsWithoutValue() {
        val files = mapOf("events.tsv" to eventLines, "expected.tsv" to expectedLines)
        val (exit, output) = runReplay(
            arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "120", "--stars"),
            fakeReader(files),
        )
        assertEquals(1, exit)
        assertTrue(output.any { it.startsWith("error:") })
    }

    @Test
    fun mainRejectsNonNumericBpm() {
        val files = mapOf("events.tsv" to eventLines, "expected.tsv" to expectedLines)
        val (exit, output) = runReplay(
            arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "fast"),
            fakeReader(files),
        )
        assertEquals(1, exit)
        assertTrue(output.any { it.startsWith("error:") })
    }

    @Test
    fun mainRejectsMalformedStars() {
        val files = mapOf("events.tsv" to eventLines, "expected.tsv" to expectedLines)
        val (exit, output) = runReplay(
            arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "120", "--stars", "60,80"),
            fakeReader(files),
        )
        assertEquals(1, exit)
        assertTrue(output.any { it.startsWith("error:") })
    }

    @Test
    fun mainSurfacesParseErrorsFromSessionFiles() {
        val files = mapOf("events.tsv" to listOf("# header", "60\t100"), "expected.tsv" to expectedLines)
        val (exit, output) = runReplay(
            arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "120"),
            fakeReader(files),
        )
        assertEquals(1, exit)
        assertTrue(output.any { it.startsWith("error:") && it.contains("line 2") })
    }

    @Test
    fun mainSurfacesMissingFiles() {
        val (exit, output) = runReplay(
            arrayOf("--events", "missing.tsv", "--expected", "expected.tsv", "--bpm", "120"),
            fakeReader(emptyMap()),
        )
        assertEquals(1, exit)
        assertTrue(output.any { it.startsWith("error:") })
    }

    @Test
    fun mainScoresWithVerdictLevelDetail() {
        val files = mapOf(
            "events.tsv" to listOf("# header", "60\t100\t0\t-1"),
            "expected.tsv" to expectedLines,
        )
        val (exit, output) = runReplay(
            arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "120"),
            fakeReader(files),
        )
        assertEquals(0, exit)
        // Only the C5 event is played: 1 PERFECT, 2 MISSED, weight 1 + 1 + 0.5.
        assertTrue(output.any { it.contains("PERFECT 1") })
        assertTrue(output.any { it.contains("MISSED 2") })
        assertTrue(output.any { it.contains("weights: hit 1.10 of 2.50") })
        assertTrue(output.any { it.contains("chords: 3") })
    }

    @Test
    fun mainUsesStdoutWriterByDefault() {
        // Covers the default-argument bridge for run()'s write parameter.
        val files = mapOf("events.tsv" to eventLines, "expected.tsv" to expectedLines)
        val original = System.out
        val captured = java.io.ByteArrayOutputStream()
        try {
            System.setOut(java.io.PrintStream(captured))
            val exit = ReplayMain.run(
                arrayOf("--events", "events.tsv", "--expected", "expected.tsv", "--bpm", "120"),
                fakeReader(files),
            )
            assertEquals(0, exit)
            assertTrue(captured.toString().contains("score: 100.00"))
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun mainWithNoReaderWriterFailsFastOnBadArgs() {
        // Covers the default-argument bridge for BOTH of run()'s function
        // parameters: bad args fail before any file I/O, exit 1.
        val exit = ReplayMain.run(arrayOf("--bpm", "120"))
        assertEquals(1, exit)
    }

    @Test
    fun verdictEnumsAreStable() {
        // Verdict is used by the report; a smoke assertion keeps the enum wired.
        assertEquals(Verdict.PERFECT, com.keyquest.scoring.Verdict.valueOf("PERFECT"))
    }
}