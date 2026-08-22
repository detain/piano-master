package com.keyquest.app.notation

/**
 * A tempo point in [ProtoScore.tempoMap]. Mirrors songpack/v1 `tempoMap`
 * entries; the prototype always uses a single `{atBeat: 0, bpm}` entry.
 */
data class TempoPoint(
    val atBeat: Double = 0.0,
    val bpm: Double,
)

/** Time signature, e.g. 4/4. Mirrors songpack/v1 `timeSignatures[]`. */
data class TimeSignature(
    val numerator: Int = 4,
    val denominator: Int = 4,
)

/**
 * Key signature as a circle-of-fifths position. Mirrors songpack/v1
 * `keySignatures[]` (sharp count positive, flat count negative). The
 * prototype only renders the accidental markers for display.
 */
data class KeySignature(val fifths: Int = 0)

/**
 * A small score container for the scrolling-notation prototype.
 *
 * THIS IS A THROWAWAY PROTOTYPE STAND-IN for a parsed `songpack/v1` pack
 * (plan §8.1.3, frozen in P1.1). Phase 1 loads manifest.json + notes.json
 * instead; the fields here are exactly the manifest/note fields the renderer
 * needs, so replacing [ProtoScoreFactory.stressScore] with a JSON parser is
 * the whole migration.
 */
data class ProtoScore(
    val notes: List<ProtoNote>,
    val defaultTempoBpm: Double,
    val tempoMap: List<TempoPoint> = listOf(TempoPoint(0.0, defaultTempoBpm)),
    val keySignature: KeySignature = KeySignature(0),
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val lanesPerHand: Int = 5,
)

/**
 * Deterministic stress-score generator for P0.5.2: >= 200 dense simultaneous-ish
 * notes, both hands, dense beam groups, spanning a phrase. Purely arithmetic —
 * no [kotlin.random.Random] — so the same [seed] always yields the same score.
 *
 * Layout hints are generated here as stand-ins for pipeline-computed values:
 * [ProtoNote.xHint] = startBeat * 1000 (the real CMS emits spacing units),
 * [ProtoNote.lane] cycles 0..lanesPerHand-1, [ProtoNote.beamGroup] groups
 * consecutive sixteenths.
 */
object ProtoScoreFactory {

    /** Stress content floor from plan §20 P0.5.2 ("200 simultaneous-ish notes"). */
    const val STRESS_NOTE_COUNT = 240

    // Park–Miller LCG constants (modulus is the Mersenne prime 2^31 - 1).
    private const val LCG_MODULUS = 2147483647L
    private const val LCG_MULTIPLIER = 48271L

    // C-major pentatonic-ish pitch set used by the generator, per octave offset.
    private val SCALE_PITCHES = intArrayOf(60, 62, 64, 67, 69, 72, 74, 76)

    /**
     * Builds the deterministic stress score.
     *
     * @param seed any long; fixed seeds produce byte-identical scores.
     * @param noteCount number of notes; must be >= 200 (the P0.5.2 stress bar).
     */
    fun stressScore(seed: Long = 0x5EEDL, noteCount: Int = STRESS_NOTE_COUNT): ProtoScore {
        require(noteCount >= 200) {
            "stressScore needs >= 200 notes for the P0.5.2 stress bar, was $noteCount"
        }

        // Park–Miller LCG: deterministic, allocation-free, arithmetic only. The
        // seed is normalized into 1..modulus-1 so seed 0 (or any multiple of
        // the modulus) cannot degenerate the sequence into a constant voice.
        var state = Math.floorMod(seed, LCG_MODULUS - 1) + 1
        fun nextUInt31(): Int {
            state = (state * LCG_MULTIPLIER) % LCG_MODULUS
            return state.toInt() and 0x7FFFFFFF
        }

        val lanesPerHand = 5
        val notes = ArrayList<ProtoNote>(noteCount)
        // Tie candidates: (a, b) adjacent note indices that must share a pitch.
        // Index a is the last note of slot s-1 and b the first note of tie slot
        // s, so a tie always spans two beats (never a chord's voices).
        val tiePairs = ArrayList<IntArray>()
        // Each iteration emits a small chord (1..3 voices) at a sixteenth slot,
        // so the screen shows simultaneous-ish notes, not a thin melody line.
        var slot = 0
        // Pitch carried from every slot into the next; a tie slot's first voice
        // reuses it so the adjacent tie pair lands on the same staff line.
        var carriedPitch: Int? = null
        while (notes.size < noteCount) {
            val isTieSlot = slot % 16 == 7
            val startBeat = slot * 0.25
            val hand = if (slot % 8 < 4) 'R' else 'L'
            val staff = if (hand == 'R') 1 else 2
            val scaleIndex = (slot * 3) % SCALE_PITCHES.size
            val octaveShift = if (hand == 'R') 12 else -12
            val voiceCount = 1 + nextUInt31() % 3
            val slotFirstIndex = notes.size
            var lastPitch = 0
            for (voice in 0 until voiceCount) {
                if (notes.size >= noteCount) break
                val pitch = if (voice == 0 && isTieSlot && carriedPitch != null) {
                    carriedPitch
                } else {
                    SCALE_PITCHES[(scaleIndex + voice * 2) % SCALE_PITCHES.size] + octaveShift
                }
                lastPitch = pitch
                val durBeats = when {
                    slot % 16 == 0 -> 1.0
                    slot % 8 == 0 -> 0.5
                    else -> 0.25
                }
                notes.add(
                    ProtoNote(
                        pitch = pitch,
                        startBeat = startBeat,
                        durBeats = durBeats,
                        hand = hand,
                        staff = staff,
                        lane = (slot + voice) % lanesPerHand,
                        xHint = startBeat * 1000.0,
                        beamGroup = slot / 4, // 4 consecutive sixteenths = one beam group
                        accidental = if (slot % 24 == 0) Accidental.SHARP else null,
                    ),
                )
            }
            // A tie slot's first voice reuses the previous slot's last pitch;
            // record the adjacent pair so the tie pass below can link them.
            if (isTieSlot && notes.size > slotFirstIndex && slotFirstIndex > 0) {
                tiePairs.add(intArrayOf(slotFirstIndex - 1, slotFirstIndex))
            }
            carriedPitch = if (notes.size > slotFirstIndex) lastPitch else null
            slot++
        }

        // Sort by time, then resolve ties. The stable sort preserves generation
        // order (startBeat is monotonic in slot), so tie-pair indices stay valid.
        val sorted = notes.sortedBy { it.startBeat }.toMutableList()
        for ((a, b) in tiePairs) {
            require(a in sorted.indices && b in sorted.indices) {
                "tie pair ($a, $b) outside note list of size ${sorted.size}"
            }
            require(sorted[a].pitch == sorted[b].pitch) {
                "tie pair ($a, $b) pitches differ: ${sorted[a].pitch} vs ${sorted[b].pitch}"
            }
            sorted[a] = sorted[a].copy(tieToIndex = b)
        }

        return ProtoScore(
            notes = sorted,
            defaultTempoBpm = 120.0,
            keySignature = KeySignature(0),
            timeSignature = TimeSignature(4, 4),
            lanesPerHand = lanesPerHand,
        )
    }
}