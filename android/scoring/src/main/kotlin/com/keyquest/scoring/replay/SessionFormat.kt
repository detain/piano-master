package com.keyquest.scoring.replay

import com.keyquest.scoring.ExpectedNote
import com.keyquest.scoring.Hand
import com.keyquest.scoring.PlayedNote
import com.keyquest.scoring.TempoCurve
import com.keyquest.scoring.TempoMap
import com.keyquest.scoring.TempoPoint

/**
 * The recorded-session TSV format for the replay tool (plan §20 P1.5.6,
 * docs/specs/scoring-v1.md §"Replay TSV format").
 *
 * Stdlib-only (zero-dep purity): plain tab-separated lines, `#` comment
 * lines and blank lines ignored on read. One section per file:
 *
 *  - events:    `pitch<TAB>velocity<TAB>onTimeNs<TAB>offTimeNs`
 *  - expected:  `pitch<TAB>startBeat<TAB>durBeats<TAB>hand(R|L)<TAB>scoringWeight`
 *  - tempo map: `atBeat<TAB>bpm<TAB>curve(step|linear)`
 *
 * All writers emit a `# keyquest scoring session v1` header comment first.
 * Parse errors throw [IllegalArgumentException] naming the offending line
 * (1-based within the given list).
 */
object SessionFormat {

    const val HEADER = "# keyquest scoring session v1"

    /** Writes [events] as TSV lines (header + one line per event). */
    fun writeEvents(events: List<PlayedNote>, writer: (String) -> Unit) {
        writer(HEADER)
        for (event in events) {
            writer("${event.pitch}\t${event.velocity}\t${event.onTimeNs}\t${event.offTimeNs}")
        }
    }

    /** Writes [notes] as TSV lines (header + one line per note). */
    fun writeExpected(notes: List<ExpectedNote>, writer: (String) -> Unit) {
        writer(HEADER)
        for (note in notes) {
            writer("${note.pitch}\t${note.startBeat}\t${note.durBeats}\t${note.hand.name}\t${note.scoringWeight}")
        }
    }

    /** Writes [points] as TSV lines (header + one line per tempo entry). */
    fun writeTempoMap(points: List<TempoPoint>, writer: (String) -> Unit) {
        writer(HEADER)
        for (point in points) {
            writer("${point.atBeat}\t${point.bpm}\t${curveName(point.curve)}")
        }
    }

    /** Parses [lines] into played events. */
    fun readEvents(lines: List<String>): List<PlayedNote> =
        lines.parseNonEmpty { line, lineNumber ->
            val fields = line.split('\t')
            require(fields.size == 4) { "line $lineNumber: expected 4 fields (pitch velocity onTimeNs offTimeNs), got ${fields.size}" }
            PlayedNote(
                pitch = fields[0].toIntOrThrow(lineNumber, "pitch"),
                velocity = fields[1].toIntOrThrow(lineNumber, "velocity"),
                onTimeNs = fields[2].toLongOrThrow(lineNumber, "onTimeNs"),
                offTimeNs = fields[3].toLongOrThrow(lineNumber, "offTimeNs"),
            )
        }

    /** Parses [lines] into expected notes. */
    fun readExpected(lines: List<String>): List<ExpectedNote> =
        lines.parseNonEmpty { line, lineNumber ->
            val fields = line.split('\t')
            require(fields.size == 5) { "line $lineNumber: expected 5 fields (pitch startBeat durBeats hand scoringWeight), got ${fields.size}" }
            ExpectedNote(
                pitch = fields[0].toIntOrThrow(lineNumber, "pitch"),
                startBeat = fields[1].toDoubleOrThrow(lineNumber, "startBeat"),
                durBeats = fields[2].toDoubleOrThrow(lineNumber, "durBeats"),
                hand = when (fields[3]) {
                    "R" -> Hand.R
                    "L" -> Hand.L
                    else -> throw IllegalArgumentException("line $lineNumber: hand must be R or L, was '${fields[3]}'")
                },
                scoringWeight = fields[4].toDoubleOrThrow(lineNumber, "scoringWeight"),
            )
        }

    /** Parses [lines] into a tempo map (curve names `step` | `linear`). */
    fun readTempoMap(lines: List<String>): TempoMap =
        TempoMap(
            lines.parseNonEmpty { line, lineNumber ->
                val fields = line.split('\t')
                require(fields.size == 3) { "line $lineNumber: expected 3 fields (atBeat bpm curve), got ${fields.size}" }
                TempoPoint(
                    atBeat = fields[0].toDoubleOrThrow(lineNumber, "atBeat"),
                    bpm = fields[1].toDoubleOrThrow(lineNumber, "bpm"),
                    curve = when (fields[2]) {
                        "step" -> TempoCurve.STEP
                        "linear" -> TempoCurve.LINEAR
                        else -> throw IllegalArgumentException(
                            "line $lineNumber: curve must be 'step' or 'linear', was '${fields[2]}'"
                        )
                    },
                )
            }
        )

    private fun curveName(curve: TempoCurve): String = when (curve) {
        TempoCurve.STEP -> "step"
        TempoCurve.LINEAR -> "linear"
    }

    /**
     * Walks [lines] skipping `#` comments and blank lines, mapping each
     * remaining line through [parse]. Errors carry the 1-based line number.
     */
    private fun <T> List<String>.parseNonEmpty(parse: (line: String, lineNumber: Int) -> T): List<T> {
        val result = mutableListOf<T>()
        for ((index, line) in this.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            result.add(parse(trimmed, index + 1))
        }
        return result
    }

    private fun String.toIntOrThrow(lineNumber: Int, field: String): Int =
        toIntOrNull() ?: throw IllegalArgumentException("line $lineNumber: '$field' must be an integer, was '$this'")

    private fun String.toLongOrThrow(lineNumber: Int, field: String): Long =
        toLongOrNull() ?: throw IllegalArgumentException("line $lineNumber: '$field' must be an integer, was '$this'")

    private fun String.toDoubleOrThrow(lineNumber: Int, field: String): Double =
        toDoubleOrNull() ?: throw IllegalArgumentException("line $lineNumber: '$field' must be a number, was '$this'")
}