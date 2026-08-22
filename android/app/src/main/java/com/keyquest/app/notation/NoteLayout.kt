package com.keyquest.app.notation

/**
 * Glyph kind for a laid-out note. Derived from duration at layout time so the
 * per-frame draw never classifies notes.
 */
enum class NoteGlyph { WHOLE, HALF, QUARTER, EIGHTH, SIXTEENTH }

/**
 * Absolute draw geometry for one note, computed ONCE per
 * (score, skin, pxPerBeat, viewport) change — never per frame.
 *
 * [baseX] is the note's left-edge screen x when `songTime == 0`; the renderer
 * translates it each frame with [screenX] (one multiply-subtract), then draws
 * at the translated x. This is the "pre-layout geometry once, per-frame
 * translate + draw only" contract from plan §7.1.
 *
 * @param y the note's vertical center in absolute canvas coordinates.
 * @param label pre-rendered text (note letter for the note-bar skin).
 */
data class NoteLayout(
    val note: ProtoNote,
    val baseX: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val glyph: NoteGlyph,
    val label: String,
) {
    /** Screen-space left edge at the given playhead position. */
    fun screenX(songTimeBeats: Double, pxPerBeat: Float): Float =
        baseX - (songTimeBeats * pxPerBeat).toFloat()

    /** Whether the note's x-extent intersects [0, viewportWidth] at songTime. */
    fun isVisible(songTimeBeats: Double, pxPerBeat: Float, viewportWidth: Float): Boolean {
        val sx = screenX(songTimeBeats, pxPerBeat)
        return sx + width >= 0f && sx <= viewportWidth
    }
}

/**
 * A beam connecting two beamed noteheads (same [ProtoNote.beamGroup], adjacent
 * in score order). Endpoints are note indices into the layout set; x is
 * resolved per frame from the two notes' translated positions.
 */
data class BeamLayout(
    val noteAIndex: Int,
    val noteBIndex: Int,
    val yA: Float,
    val yB: Float,
)

/**
 * A tie curve connecting a note to its [ProtoNote.tieToIndex] target.
 * [y] is the notehead-center y; the curve is drawn above it.
 */
data class TieLayout(
    val noteAIndex: Int,
    val noteBIndex: Int,
    val y: Float,
)

/** A key-signature accidental marker at a fixed staff position. */
data class KeySigMarker(
    val glyph: String,
    val y: Float,
)

/** Note-bar skin static geometry (5 lanes per hand, split gap). */
data class NoteBarLayout(
    val lanesPerHand: Int,
    val laneHeightPx: Float,
    val splitGapPx: Float,
    val topPaddingPx: Float,
)

/** Staff skin static geometry + decoration markers (clefs, key/time sigs). */
data class StaffLayout(
    val spacePx: Float,
    val trebleTopY: Float,
    val bassTopY: Float,
    val middleCY: Float,
    val clefX: Float,
    val timeSigX: Float,
    val timeSigText: String,
    val keySigMarkers: List<KeySigMarker>,
    val beams: List<BeamLayout>,
    val ties: List<TieLayout>,
)

/** The full pre-layout for a score under one skin. */
data class NoteLayoutSet(
    val notes: List<NoteLayout>,
    val noteBar: NoteBarLayout?,
    val staff: StaffLayout?,
)

/**
 * Builds the complete pre-layout for a score under a skin. Pure and fast
 * (~microseconds for the 240-note stress score); the caller runs it off the
 * main thread via `produceState(Dispatchers.Default)` and only rebuilds it when
 * (score, skin, pxPerBeat, viewport) change. Tempo is NOT an input: it only
 * affects the [LayoutMath.songTime] mapping, never note geometry.
 */
object NoteLayoutBuilder {

    /** Staff skin: vertical space between adjacent staff lines. */
    const val STAFF_SPACE_PX = 10f

    /** Scale of the prototype's xHint units (generator emits startBeat * 1000). */
    const val X_HINT_UNITS_PER_BEAT = 1000.0

    private const val MIN_BAR_WIDTH_PX = 6f
    private const val BAR_VERTICAL_PADDING_PX = 4f

    /** Maps a duration (in beats) to the glyph the staff skin should draw. */
    fun glyphForDuration(durBeats: Double): NoteGlyph = when {
        durBeats >= 4.0 -> NoteGlyph.WHOLE
        durBeats >= 2.0 -> NoteGlyph.HALF
        durBeats >= 1.0 -> NoteGlyph.QUARTER
        durBeats >= 0.5 -> NoteGlyph.EIGHTH
        else -> NoteGlyph.SIXTEENTH
    }

    fun build(
        score: ProtoScore,
        skin: NotationSkin,
        pxPerBeat: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        playheadFraction: Float = LayoutMath.DEFAULT_PLAYHEAD_FRACTION,
    ): NoteLayoutSet {
        require(viewportWidth > 0f && viewportHeight > 0f) {
            "viewport must be positive, was ${viewportWidth}x${viewportHeight}"
        }
        val playheadX = viewportWidth * playheadFraction

        val noteLayouts = ArrayList<NoteLayout>(score.notes.size)
        for (note in score.notes) {
            val (width, height, y) = when (skin) {
                NotationSkin.NoteBar -> {
                    val laneHeight = laneHeightFor(score, viewportHeight)
                    val laneIndex = LayoutMath.noteBarLaneIndex(note.hand, note.lane, score.lanesPerHand)
                    val laneTop = LayoutMath.noteBarY(
                        laneIndex = laneIndex,
                        laneHeightPx = laneHeight,
                        splitGapPx = NOTE_BAR_SPLIT_GAP_PX,
                        topPaddingPx = NOTE_BAR_TOP_PADDING_PX,
                    )
                    Triple(
                        first = kotlin.math.max(note.durBeats * pxPerBeat.toDouble(), MIN_BAR_WIDTH_PX.toDouble()).toFloat(),
                        second = laneHeight - BAR_VERTICAL_PADDING_PX,
                        third = laneTop + laneHeight / 2f, // center in the lane
                    )
                }
                NotationSkin.Staff -> {
                    val spacePx = STAFF_SPACE_PX
                    val staff = if (note.staff == 1) LayoutMath.StaffZone.TREBLE else LayoutMath.StaffZone.BASS
                    val topLineY = LayoutMath.staffTopLineY(
                        middleCY = viewportHeight / 2f,
                        staff = staff,
                        spacePx = spacePx,
                    )
                    Triple(
                        first = NOTEHEAD_WIDTH_PX,
                        second = NOTEHEAD_HEIGHT_PX,
                        third = LayoutMath.staffLineY(note.pitch, staff, spacePx, topLineY),
                    )
                }
            }
            val baseX = playheadX + when (skin) {
                NotationSkin.NoteBar -> (note.startBeat * pxPerBeat).toFloat()
                NotationSkin.Staff -> (note.xHint / X_HINT_UNITS_PER_BEAT * pxPerBeat).toFloat()
            }
            noteLayouts.add(
                NoteLayout(
                    note = note,
                    baseX = baseX,
                    y = y,
                    width = width,
                    height = height,
                    glyph = glyphForDuration(note.durBeats),
                    label = LayoutMath.noteLetterWithAccidental(note.pitch, note.accidental),
                ),
            )
        }

        return NoteLayoutSet(
            notes = noteLayouts,
            noteBar = if (skin == NotationSkin.NoteBar) {
                NoteBarLayout(
                    lanesPerHand = score.lanesPerHand,
                    laneHeightPx = laneHeightFor(score, viewportHeight),
                    splitGapPx = NOTE_BAR_SPLIT_GAP_PX,
                    topPaddingPx = NOTE_BAR_TOP_PADDING_PX,
                )
            } else null,
            staff = if (skin == NotationSkin.Staff) buildStaffLayout(score, viewportHeight, noteLayouts) else null,
        )
    }

    private fun buildStaffLayout(
        score: ProtoScore,
        viewportHeight: Float,
        noteLayouts: List<NoteLayout>,
    ): StaffLayout {
        val spacePx = STAFF_SPACE_PX
        val middleCY = viewportHeight / 2f
        val trebleTopY = LayoutMath.staffTopLineY(middleCY, LayoutMath.StaffZone.TREBLE, spacePx)
        val bassTopY = LayoutMath.staffTopLineY(middleCY, LayoutMath.StaffZone.BASS, spacePx)

        val beams = ArrayList<BeamLayout>()
        for (i in 0 until noteLayouts.size - 1) {
            val a = noteLayouts[i].note
            val b = noteLayouts[i + 1].note
            val sameBeamGroup = a.beamGroup != null && a.beamGroup == b.beamGroup
            if (sameBeamGroup && a.staff == b.staff) {
                beams.add(
                    BeamLayout(
                        noteAIndex = i,
                        noteBIndex = i + 1,
                        yA = noteLayouts[i].y,
                        yB = noteLayouts[i + 1].y,
                    ),
                )
            }
        }

        val ties = ArrayList<TieLayout>()
        for (i in noteLayouts.indices) {
            val target = noteLayouts[i].note.tieToIndex ?: continue
            ties.add(TieLayout(noteAIndex = i, noteBIndex = target, y = noteLayouts[i].y))
        }

        return StaffLayout(
            spacePx = spacePx,
            trebleTopY = trebleTopY,
            bassTopY = bassTopY,
            middleCY = middleCY,
            clefX = STAFF_CLEF_X,
            timeSigX = STAFF_TIME_SIG_X,
            timeSigText = "${score.timeSignature.numerator}/${score.timeSignature.denominator}",
            keySigMarkers = keySigMarkersFor(score.keySignature, spacePx, trebleTopY, bassTopY),
            beams = beams,
            ties = ties,
        )
    }

    /**
     * Key-signature accidentals for the given circle-of-fifths position,
     * placed on the line each accidental qualifies (treble then bass staff).
     */
    private fun keySigMarkersFor(
        key: KeySignature,
        spacePx: Float,
        trebleTopY: Float,
        bassTopY: Float,
    ): List<KeySigMarker> {
        if (key.fifths == 0) return emptyList()
        val glyph = if (key.fifths > 0) "\uE262" else "\uE260" // sharp / flat
        val pitch = if (key.fifths > 0) 66 else 61 // F#5 line / Bb3 line stand-ins
        val trebleY = LayoutMath.staffLineY(pitch, LayoutMath.StaffZone.TREBLE, spacePx, trebleTopY)
        val bassY = LayoutMath.staffLineY(pitch, LayoutMath.StaffZone.BASS, spacePx, bassTopY)
        return listOf(KeySigMarker(glyph, trebleY), KeySigMarker(glyph, bassY))
    }

    private fun laneHeightFor(score: ProtoScore, viewportHeight: Float): Float {
        val lanes = score.lanesPerHand * 2
        val usable = (viewportHeight - NOTE_BAR_TOP_PADDING_PX - NOTE_BAR_SPLIT_GAP_PX).coerceAtLeast(1f)
        return usable / lanes
    }
}

// Note-bar skin geometry constants (spike defaults; real values come from the
// design system in Phase 1).
private const val NOTE_BAR_SPLIT_GAP_PX = 16f
private const val NOTE_BAR_TOP_PADDING_PX = 8f

// Staff skin geometry constants.
private const val STAFF_CLEF_X = 10f
private const val STAFF_TIME_SIG_X = 56f
private const val NOTEHEAD_WIDTH_PX = 11f
private const val NOTEHEAD_HEIGHT_PX = 9f