package com.keyquest.scoring.replay

import com.keyquest.scoring.ChordStatus
import com.keyquest.scoring.ScoreConfig
import com.keyquest.scoring.ScoreReport
import com.keyquest.scoring.StarThresholds
import com.keyquest.scoring.TempoMap
import com.keyquest.scoring.TempoPoint
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * The replay tool entry point (plan §20 P1.5.6): record a session's event
 * stream, replay it offline against the scorer. Scoring changes are argued
 * with recorded real sessions instead of opinions.
 *
 * Usage:
 *   --events <file>       played events TSV (SessionFormat)
 *   --expected <file>     expected notes TSV
 *   --tempo-map <file>    tempo map TSV        (xor --bpm)
 *   --bpm <n>             single constant tempo (xor --tempo-map)
 *   --beginner            beginner window widths
 *   --stars one,two,three star thresholds (default 60,80,95)
 *
 * This object is a thin shell: [run] is fully testable through the injected
 * [readLines]/[write] seams (defaults are file/stdout I/O); all parsing and
 * scoring logic lives in [SessionFormat] / [ReplayRunner] / the scorer, which
 * are the covered code. Exits 0 on success, 1 on bad args/files.
 */
object ReplayMain {

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    /**
     * Runs the replay. [readLines] reads a file's lines (default: real file
     * I/O); [write] emits output lines (default: stdout). Returns the process
     * exit code: 0 success, 1 bad args or unreadable/parse-failing input.
     */
    fun run(
        args: Array<String>,
        readLines: (String) -> List<String> = { path -> File(path).readLines() },
        write: (String) -> Unit = { line -> println(line) },
    ): Int {
        val eventsPath = flagValue(args, "--events")
        val expectedPath = flagValue(args, "--expected")
        val tempoMapPath = flagValue(args, "--tempo-map")
        val bpmArg = flagValue(args, "--bpm")
        val beginner = args.contains("--beginner")
        val starsArg = flagValue(args, "--stars")

        // Exactly one of --tempo-map / --bpm must be present.
        if (eventsPath == null || expectedPath == null || (tempoMapPath == null) == (bpmArg == null)) {
            writeUsage(write)
            return 1
        }

        // --stars must carry a value when present (fail fast, like --bpm).
        if (args.contains("--stars") && starsArg == null) {
            write("error: --stars requires a value, e.g. '60,80,95'")
            return 1
        }

        return try {
            val events = SessionFormat.readEvents(readLines(eventsPath))
            val expected = SessionFormat.readExpected(readLines(expectedPath))
            val tempoMap = buildTempoMap(tempoMapPath, bpmArg, readLines)
            val config = ScoreConfig(beginner = beginner)
            val thresholds = parseStars(starsArg)
            val report = ReplayRunner.replay(events, expected, tempoMap, config, thresholds)
            writeReport(write, report)
            0
        } catch (e: IllegalArgumentException) {
            write("error: ${e.message}")
            1
        } catch (e: IOException) {
            write("error: ${e.message}")
            1
        }
    }

    private fun buildTempoMap(
        tempoMapPath: String?,
        bpmArg: String?,
        readLines: (String) -> List<String>,
    ): TempoMap {
        if (tempoMapPath != null) return SessionFormat.readTempoMap(readLines(tempoMapPath))
        val bpm = bpmArg!!.toDoubleOrNull()
            ?: throw IllegalArgumentException("--bpm must be a number, was '$bpmArg'")
        return TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = bpm)))
    }

    private fun flagValue(args: Array<String>, flag: String): String? {
        val index = args.indexOf(flag)
        if (index == -1 || index + 1 >= args.size) return null
        return args[index + 1]
    }

    private fun parseStars(starsArg: String?): StarThresholds {
        if (starsArg == null) return StarThresholds()
        val parts = starsArg.split(',')
        require(parts.size == 3) { "--stars must be 'one,two,three', was '$starsArg'" }
        val values = parts.map {
            it.toDoubleOrNull() ?: throw IllegalArgumentException("--stars value '$it' is not a number")
        }
        return StarThresholds(oneStar = values[0], twoStar = values[1], threeStar = values[2])
    }

    private fun writeUsage(write: (String) -> Unit) {
        write("usage: replay --events <file> --expected <file> [--tempo-map <file> | --bpm <n>] [--beginner] [--stars one,two,three]")
        write("       session files are the TSV format of docs/specs/scoring-v1.md (see SessionFormat)")
    }

    private fun writeReport(write: (String) -> Unit, report: ScoreReport) {
        write("score: ${"%.2f".format(report.score)}  stars: ${report.stars}/3")
        write("verdicts: PERFECT ${report.perfectCount}  GOOD ${report.goodCount}  MISSED ${report.missedCount}  WRONG ${report.wrongCount}  (matched ${report.matchedCount}/${report.outcomes.size})")
        write("weights: hit ${"%.2f".format(report.hitWeight)} of ${"%.2f".format(report.totalWeight)}")
        val full = report.chordOutcomes.count { it.outcome == ChordStatus.FULL }
        val partial = report.chordOutcomes.count { it.outcome == ChordStatus.PARTIAL }
        val missedChords = report.chordOutcomes.count { it.outcome == ChordStatus.MISSED }
        write("chords: ${report.chordOutcomes.size} (FULL $full, PARTIAL $partial, MISSED $missedChords)")
        write("measure heatmap (missed/wrong per measure):")
        if (report.measureHeatmap.isEmpty()) {
            write("  (none)")
        } else {
            for ((measure, summary) in report.measureHeatmap) {
                write("  measure $measure: missed ${summary.missed}, wrong ${summary.wrong}")
            }
        }
        write("extra events (unconsumed): ${report.extraEvents.size}")
        for (event in report.extraEvents) {
            write("  pitch ${event.pitch} at ${event.onTimeNs}ns")
        }
    }
}