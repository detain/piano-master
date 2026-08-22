package com.keyquest.app.notation

/**
 * Pure layout math for the scrolling-notation prototype (plan §7.1).
 *
 * Everything here is a pure function over beats and pixels — no Android
 * dependencies, no state — so it is trivially unit-testable on the JVM.
 * The renderer's per-frame work is then just: translate the pre-laid-out
 * geometry by `songTime * pxPerBeat` and draw (see [NoteLayout.screenX]).
 *
 * Time model: the score's only time unit is the beat ([ProtoNote.startBeat],
 * [ProtoNote.durBeats]). Seconds enter once, at the boundary, via
 * [songTime]; tempo never rewrites note data (plan §8.1.1).
 */
object LayoutMath {

    /** MIDI pitch range the app accepts (plan §8.1.4). */
    const val NOTE_RANGE_MIN = 21
    const val NOTE_RANGE_MAX = 108

    /** Fixed playhead position from the left edge (plan §7.1: ~30%). */
    const val DEFAULT_PLAYHEAD_FRACTION = 0.3f

    /**
     * Seconds per beat at the given tempo.
     * @param tempoBpm quarter-note beats per minute.
     */
    fun beatsPerSecond(tempoBpm: Double): Double {
        require(tempoBpm > 0.0) { "tempoBpm must be > 0, was $tempoBpm" }
        return tempoBpm / 60.0
    }

    /**
     * Current musical position (in beats) for a frame-clock timestamp.
     *
     * This is the `songTime = f(frameClock, tempo)` mapping from plan §7.1:
     * the renderer feeds it `frameTimeNanos` from `withFrameNanos` and the
     * tempo; elapsed seconds are converted once here via [beatsPerSecond].
     *
     * @param frameTimeNanos monotonically increasing frame-clock timestamp.
     * @param startNanos frame-clock timestamp when playback started.
     * @param tempoBpm bpm; negative elapsed (clock before start) yields a
     *   negative position, which callers may clamp if the score should not
     *   begin before beat zero.
     */
    fun songTime(frameTimeNanos: Long, startNanos: Long, tempoBpm: Double): Double {
        require(tempoBpm > 0.0) { "tempoBpm must be > 0, was $tempoBpm" }
        val elapsedSeconds = (frameTimeNanos - startNanos) / 1_000_000_000.0
        return elapsedSeconds * beatsPerSecond(tempoBpm)
    }

    /**
     * Screen x of a note in right-to-left scrolling: the playhead is fixed at
     * [playheadFraction] of the width, future notes sit to its right, and each
     * beat of horizontal distance is [pxPerBeat] pixels.
     *
     * A note exactly at the playhead ([beat] == [songTimeBeats]) lands on the
     * playhead line; as `beat` approaches `songTimeBeats` from the past, x
     * approaches the playhead from the left.
     *
     * Tempo is deliberately absent: it is consumed by [songTime], so the
     * beat->pixel mapping needs only the beat distance.
     *
     * @param width canvas width in pixels.
     */
    fun noteX(
        beat: Double,
        songTimeBeats: Double,
        pxPerBeat: Float,
        playheadFraction: Float = DEFAULT_PLAYHEAD_FRACTION,
        width: Float,
    ): Float {
        require(pxPerBeat > 0f) { "pxPerBeat must be > 0, was $pxPerBeat" }
        require(width > 0f) { "width must be > 0, was $width" }
        require(playheadFraction in 0f..1f) { "playheadFraction must be in 0..1, was $playheadFraction" }
        val playheadX = width * playheadFraction
        return playheadX + ((beat - songTimeBeats) * pxPerBeat).toFloat()
    }

    /**
     * Viewport culling: returns only the notes whose beat extent can intersect
     * the visible window. Notes entirely before the window (right edge of the
     * playhead, [playheadFraction] of the width behind it) and entirely after
     * [lookaheadBeats] ahead of the playhead are excluded; a note straddling
     * the playhead is included.
     *
     * @param lookaheadBeats how many beats of future to keep on screen.
     */
    fun visibleNotes(
        notes: List<ProtoNote>,
        songTimeBeats: Double,
        lookaheadBeats: Double,
        pxPerBeat: Float,
        width: Float,
        playheadFraction: Float = DEFAULT_PLAYHEAD_FRACTION,
    ): List<ProtoNote> {
        if (width <= 0f) return emptyList()
        require(pxPerBeat > 0f) { "pxPerBeat must be > 0, was $pxPerBeat" }
        require(lookaheadBeats >= 0.0) { "lookaheadBeats must be >= 0, was $lookaheadBeats" }
        val playheadX = width * playheadFraction
        // Window edges in beats: everything behind the playhead that still fits
        // in the width, and everything up to the lookahead in front of it.
        val firstVisibleBeat = songTimeBeats - playheadX / pxPerBeat
        val lastVisibleBeat = songTimeBeats + lookaheadBeats
        return notes.filter { note ->
            note.startBeat + note.durBeats >= firstVisibleBeat && note.startBeat <= lastVisibleBeat
        }
    }

    /**
     * Note-bar skin: flattens (hand, lane) into a global 0..(2*lanesPerHand-1)
     * lane index. Left hand occupies the top lanes, right hand the bottom.
     */
    fun noteBarLaneIndex(hand: Char, lane: Int, lanesPerHand: Int = 5): Int {
        require(hand == 'L' || hand == 'R') { "hand must be 'L' or 'R', was '$hand'" }
        require(lane in 0 until lanesPerHand) { "lane $lane outside 0..${lanesPerHand - 1}" }
        return if (hand == 'L') lane else lanesPerHand + lane
    }

    /**
     * Note-bar skin: top-left y of a lane. A [splitGapPx] gap is inserted after
     * the left hand's lanes so the two hands read as separate grids.
     */
    fun noteBarY(
        laneIndex: Int,
        laneHeightPx: Float,
        splitGapPx: Float,
        topPaddingPx: Float = 0f,
    ): Float {
        require(laneHeightPx > 0f) { "laneHeightPx must be > 0, was $laneHeightPx" }
        require(splitGapPx >= 0f) { "splitGapPx must be >= 0, was $splitGapPx" }
        require(laneIndex >= 0) { "laneIndex must be >= 0, was $laneIndex" }
        val gap = if (laneIndex >= 5) splitGapPx else 0f
        return topPaddingPx + laneIndex * laneHeightPx + gap
    }

    /** Staff zone selector for [staffLineY]. */
    enum class StaffZone { TREBLE, BASS }

    // Staff geometry constants.
    // units(pitch) maps MIDI pitch to "line units" where one unit = one staff
    // space and middle C = 35:  units(p) = octave*7 + PC_INDEX[p % 12].
    //    C4 = 35 (middle C, the grand-staff anchor)
    //    TREBLE top line F5 = 45, bottom line E4 = 37
    //    BASS  top line A3 = 33, bottom line G2 = 25
    // Each adjacent line differs by 2 units (= one whole diatonic step).
    private const val MIDDLE_C_UNITS = 35
    private const val TREBLE_TOP_LINE_UNITS = 45
    private const val BASS_TOP_LINE_UNITS = 33

    /** Diatonic position of a pitch class: C=0, D=1, ..., B=6 (sharps = +0.5). */
    private val PC_INDEX = doubleArrayOf(0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 3.5, 4.0, 4.5, 5.0, 5.5, 6.0)

    /**
     * Grand-staff placement: y of the staff line (or ledger position) for a
     * pitch, measured from the staff's top line. Middle C resolves to the same
     * y on both staves, so the grand staff aligns.
     *
     * @param staffTopLineY y of the staff's top line (F5 for treble, A3 for bass).
     * @param spacePx vertical distance between adjacent lines (one "space").
     */
    fun staffLineY(
        pitch: Int,
        staff: StaffZone,
        spacePx: Float,
        staffTopLineY: Float,
    ): Float {
        require(pitch in NOTE_RANGE_MIN..NOTE_RANGE_MAX) { "pitch $pitch outside MIDI range" }
        require(spacePx > 0f) { "spacePx must be > 0, was $spacePx" }
        val topLineUnits = when (staff) {
            StaffZone.TREBLE -> TREBLE_TOP_LINE_UNITS
            StaffZone.BASS -> BASS_TOP_LINE_UNITS
        }
        val pitchUnits = (pitch / 12) * 7.0 + PC_INDEX[pitch % 12]
        return staffTopLineY + (topLineUnits - pitchUnits).toFloat() * spacePx
    }

    /**
     * Grand-staff convenience: y of the staff's top line so that middle C sits
     * at [middleCY]. Treble top line ends up 10 spaces above middle C, bass top
     * line 2 spaces below it (a 4-space gap between the staves' inner edges).
     */
    fun staffTopLineY(middleCY: Float, staff: StaffZone, spacePx: Float): Float =
        when (staff) {
            StaffZone.TREBLE -> middleCY - (TREBLE_TOP_LINE_UNITS - MIDDLE_C_UNITS) * spacePx
            StaffZone.BASS -> middleCY - (BASS_TOP_LINE_UNITS - MIDDLE_C_UNITS) * spacePx
        }

    /**
     * Accidental placement: the glyph's left edge sits a small gap to the left
     * of the notehead so it leads the note it qualifies.
     */
    fun accidentalX(
        noteX: Float,
        gapPx: Float = 8f,
        accidentalWidthPx: Float = 14f,
    ): Float = noteX - gapPx - accidentalWidthPx

    private val LETTERS = arrayOf("C", "D", "E", "F", "G", "A", "B")

    // Pitch class -> letter index. NOT pc/2: E-F and B-C are half steps, so
    // pc 4->E(2), 5/6->F(3), 7/8->G(4), 9/10->A(5), 11->B(6).
    private val PC_TO_LETTER_INDEX = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)

    /** Plain note letter for the note-bar skin ("C", "D", ...). */
    fun noteLetter(pitch: Int): String {
        require(pitch in NOTE_RANGE_MIN..NOTE_RANGE_MAX) { "pitch $pitch outside MIDI range" }
        return LETTERS[PC_TO_LETTER_INDEX[pitch % 12]]
    }

    /** Note letter with a display-only accidental suffix ("C#", "Bb", ...). */
    fun noteLetterWithAccidental(pitch: Int, accidental: Accidental?): String {
        val letter = noteLetter(pitch)
        return when (accidental) {
            Accidental.SHARP -> "$letter#"
            Accidental.FLAT -> "${letter}b"
            Accidental.NATURAL -> "$letter\u266E" // U+266E natural sign
            null -> letter
        }
    }
}