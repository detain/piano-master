package com.keyquest.app.notation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The P0.5 scrolling-notation renderer: a single Compose [Canvas] drawing the
 * visible window of pre-laid-out notes each frame (plan §7.1).
 *
 * Architecture (the whole point of the spike):
 *  - [NoteLayoutBuilder.build] runs OFF the main thread via [produceState] on
 *    [Dispatchers.Default], and only when (score, skin, pxPerBeat, viewport)
 *    change — never per frame.
 *  - The per-frame draw lambda only translates each [NoteLayout] by
 *    `songTime * pxPerBeat` ([NoteLayout.screenX]), culls by x, and draws.
 *    No layout, no allocation, no measure inside the frame.
 *  - songTime advances only while [playing], driven by [withFrameNanos].
 *  - Bravura glyphs (clefs, accidentals, key/time signatures) are pre-measured
 *    once and drawn from the cached layout; noteheads/beams/ties are paths.
 *
 * @param frameClock optional injectable frame clock (test harness); when null
 *   the composition's default [MonotonicFrameClock] is used.
 */
@Composable
fun ScrollingNotationPlayer(
    score: ProtoScore,
    skin: NotationSkin,
    tempoBpm: Double,
    modifier: Modifier = Modifier,
    pxPerBeat: Float = DEFAULT_PX_PER_BEAT,
    lookaheadBeats: Double = DEFAULT_LOOKAHEAD_BEATS,
    playing: Boolean = true,
    frameClock: MonotonicFrameClock? = null,
) {
    var songTimeBeats by remember { mutableDoubleStateOf(0.0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val currentTempo by rememberUpdatedState(tempoBpm)

    // Playhead clock: accumulate songTime only while playing.
    LaunchedEffect(playing, frameClock) {
        var lastFrameNanos = -1L
        while (true) {
            val now = if (frameClock != null) {
                frameClock.withFrameNanos { it }
            } else {
                withFrameNanos { it }
            }
            if (playing && lastFrameNanos >= 0L) {
                val deltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0
                songTimeBeats += deltaSeconds * LayoutMath.beatsPerSecond(currentTempo)
            }
            lastFrameNanos = now
        }
    }

    // Pre-layout, off the main thread. Rebuilds only when the keys change.
    val layoutSet by produceState(
        initialValue = null as NoteLayoutSet?,
        score, skin, pxPerBeat, viewportSize,
    ) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            value = withContext(Dispatchers.Default) {
                NoteLayoutBuilder.build(
                    score = score,
                    skin = skin,
                    pxPerBeat = pxPerBeat,
                    viewportWidth = viewportSize.width.toFloat(),
                    viewportHeight = viewportSize.height.toFloat(),
                )
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val glyphs = remember(layoutSet, textMeasurer) { PreMeasuredGlyphs.build(textMeasurer, layoutSet) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
    ) {
        val set = layoutSet ?: return@Canvas
        val songTime = songTimeBeats
        val viewportW = size.width
        val playheadX = size.width * LayoutMath.DEFAULT_PLAYHEAD_FRACTION

        when (skin) {
            NotationSkin.NoteBar -> drawNoteBarBackground(set.noteBar)
            NotationSkin.Staff -> drawStaffBackground(set.staff, glyphs)
        }

        // Beams and ties sit under the noteheads they connect.
        set.staff?.let { staff ->
            for (beam in staff.beams) {
                val a = set.notes[beam.noteAIndex]
                val b = set.notes[beam.noteBIndex]
                val ax = a.screenX(songTime, pxPerBeat) + a.width / 2f
                val bx = b.screenX(songTime, pxPerBeat) + b.width / 2f
                if ((ax < 0f && bx < 0f) || (ax > viewportW && bx > viewportW)) continue
                drawBeam(ax, bx, beam.yA, beam.yB, staff.spacePx)
            }
            for (tie in staff.ties) {
                val a = set.notes[tie.noteAIndex]
                val b = set.notes[tie.noteBIndex]
                val ax = a.screenX(songTime, pxPerBeat) + a.width / 2f
                val bx = b.screenX(songTime, pxPerBeat) + b.width / 2f
                if ((ax < 0f && bx < 0f) || (ax > viewportW && bx > viewportW)) continue
                drawTie(ax, bx, tie.y, staff.spacePx)
            }
        }

        // Notes: translate + draw only, no per-frame layout or allocation.
        for (layout in set.notes) {
            if (!layout.isVisible(songTime, pxPerBeat, viewportW)) continue
            val sx = layout.screenX(songTime, pxPerBeat)
            when (skin) {
                NotationSkin.NoteBar -> drawNoteBarNote(layout, sx, set.noteBar, glyphs)
                NotationSkin.Staff -> drawStaffNote(layout, sx, set.staff, glyphs)
            }
        }

        // Fixed playhead line at the configured fraction of the width.
        drawLine(
            color = PLAYHEAD_COLOR,
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, size.height),
            strokeWidth = PLAYHEAD_STROKE_PX,
        )
    }
}

// ---------------------------------------------------------------------------
// Note-bar skin drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawNoteBarBackground(bar: NoteBarLayout?) {
    if (bar == null) return
    val laneCount = bar.lanesPerHand * 2
    for (laneIndex in 0 until laneCount) {
        val y = LayoutMath.noteBarY(
            laneIndex = laneIndex,
            laneHeightPx = bar.laneHeightPx,
            splitGapPx = bar.splitGapPx,
            topPaddingPx = bar.topPaddingPx,
        )
        val hand = if (laneIndex < bar.lanesPerHand) NotationSkin.NoteBar.neutralLeft
        else NotationSkin.NoteBar.neutralRight
        drawRect(
            color = hand.copy(alpha = 0.10f),
            topLeft = Offset(0f, y),
            size = Size(size.width, bar.laneHeightPx),
        )
    }
}

private fun DrawScope.drawNoteBarNote(
    layout: NoteLayout,
    sx: Float,
    bar: NoteBarLayout?,
    glyphs: PreMeasuredGlyphs,
) {
    val laneHeight = bar?.laneHeightPx ?: 24f
    val color = if (layout.note.hand == 'L') {
        NotationSkin.NoteBar.neutralLeft
    } else {
        NotationSkin.NoteBar.neutralRight
    }
    val topLeft = Offset(sx, layout.y - layout.height / 2f)
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = Size(layout.width, layout.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(laneHeight * 0.22f),
    )
    val label = glyphs.labels[layout.label] ?: return
    val labelWidth = label.size.width.toFloat()
    val labelHeight = label.size.height.toFloat()
    if (labelWidth + 4f > layout.width) return // bar too narrow for its letter
    drawText(
        textLayoutResult = label,
        color = Color.White,
        topLeft = Offset(
            sx + (layout.width - labelWidth) / 2f,
            layout.y - labelHeight / 2f,
        ),
    )
}

// ---------------------------------------------------------------------------
// Staff skin drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawStaffBackground(staff: StaffLayout?, glyphs: PreMeasuredGlyphs) {
    if (staff == null) return
    val space = staff.spacePx
    // Five lines per staff, pinned (the notation scrolls horizontally only).
    for (i in 0..4) {
        val trebleY = staff.trebleTopY + i * space
        val bassY = staff.bassTopY + i * space
        drawLine(
            color = NotationSkin.Staff.lineColor,
            start = Offset(0f, trebleY),
            end = Offset(size.width, trebleY),
            strokeWidth = STAFF_LINE_STROKE_PX,
        )
        drawLine(
            color = NotationSkin.Staff.lineColor,
            start = Offset(0f, bassY),
            end = Offset(size.width, bassY),
            strokeWidth = STAFF_LINE_STROKE_PX,
        )
    }
    // Grand-staff brace on the left.
    drawBrace(
        x = 3f,
        topY = staff.trebleTopY + 4 * space,
        bottomY = staff.bassTopY,
    )
    // Clefs (Bravura), centered on each staff.
    drawText(
        textLayoutResult = glyphs.trebleClef,
        color = NotationSkin.Staff.noteColor,
        topLeft = Offset(
            staff.clefX,
            staff.trebleTopY + 2 * space - glyphs.trebleClef.size.height / 2f,
        ),
    )
    drawText(
        textLayoutResult = glyphs.bassClef,
        color = NotationSkin.Staff.noteColor,
        topLeft = Offset(
            staff.clefX,
            staff.bassTopY + 2 * space - glyphs.bassClef.size.height / 2f,
        ),
    )
    // Key-signature markers.
    val markerX = staff.timeSigX - ACCIDENTAL_GLYPH_PX * 1.6f
    for (marker in staff.keySigMarkers) {
        val layout = glyphs.accidentals[marker.glyph] ?: continue
        drawText(
            textLayoutResult = layout,
            color = NotationSkin.Staff.accidentalColor,
            topLeft = Offset(markerX, marker.y - layout.size.height / 2f),
        )
    }
    // Time signature (plain text, both staves).
    drawText(
        textLayoutResult = glyphs.timeSig,
        color = NotationSkin.Staff.noteColor,
        topLeft = Offset(
            staff.timeSigX,
            staff.trebleTopY + 2 * space - glyphs.timeSig.size.height / 2f,
        ),
    )
    drawText(
        textLayoutResult = glyphs.timeSig,
        color = NotationSkin.Staff.noteColor,
        topLeft = Offset(
            staff.timeSigX,
            staff.bassTopY + 2 * space - glyphs.timeSig.size.height / 2f,
        ),
    )
}

private fun DrawScope.drawStaffNote(
    layout: NoteLayout,
    sx: Float,
    staff: StaffLayout?,
    glyphs: PreMeasuredGlyphs,
) {
    val space = staff?.spacePx ?: NoteLayoutBuilder.STAFF_SPACE_PX
    val noteColor = NotationSkin.Staff.noteColor
    val centerX = sx + layout.width / 2f
    val centerY = layout.y

    // Ledger lines for notes outside the staff bounds.
    val topLine = if (layout.note.staff == 1) staff?.trebleTopY else staff?.bassTopY
    if (topLine != null) {
        val bottomLine = topLine + 4 * space
        if (centerY < topLine || centerY > bottomLine) {
            drawLine(
                color = noteColor,
                start = Offset(sx - LEDGER_OVERHANG_PX, centerY),
                end = Offset(sx + layout.width + LEDGER_OVERHANG_PX, centerY),
                strokeWidth = STAFF_LINE_STROKE_PX,
            )
        }
    }

    val isWhole = layout.glyph == NoteGlyph.WHOLE
    val isHalf = layout.glyph == NoteGlyph.HALF
    if (!isWhole) {
        val stemUp = stemUpFor(layout)
        val stemTop = if (stemUp) centerY - STEM_LENGTH_PX else centerY + STEM_LENGTH_PX
        val stemX = centerX + layout.width * 0.35f
        drawLine(
            color = noteColor,
            start = Offset(stemX, centerY),
            end = Offset(stemX, stemTop),
            strokeWidth = STEM_STROKE_PX,
            cap = StrokeCap.Round,
        )
    }

    // Notehead: filled for quarter-and-shorter, hollow for half/whole.
    val rect = Rect(
        left = sx,
        top = centerY - layout.height / 2f,
        right = sx + layout.width,
        bottom = centerY + layout.height / 2f,
    )
    if (isWhole || isHalf) {
        drawOval(color = noteColor, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 1.8f))
    } else {
        drawOval(color = noteColor, topLeft = rect.topLeft, size = rect.size)
    }

    // Display-only accidental (Bravura) to the left of the notehead.
    val accidental = layout.note.accidental ?: return
    val glyph = glyphs.accidentals[BravuraGlyphs.glyphFor(accidental)] ?: return
    drawText(
        textLayoutResult = glyph,
        color = NotationSkin.Staff.accidentalColor,
        topLeft = Offset(
            LayoutMath.accidentalX(sx, gapPx = ACCIDENTAL_GAP_PX, accidentalWidthPx = glyph.size.width.toFloat()),
            centerY - glyph.size.height / 2f,
        ),
    )
}

private fun DrawScope.drawBeam(ax: Float, bx: Float, yA: Float, yB: Float, spacePx: Float) {
    val yAEnd = yA - BEAM_OFFSET_SPACES * spacePx
    val yBEnd = yB - BEAM_OFFSET_SPACES * spacePx
    drawLine(
        color = NotationSkin.Staff.noteColor,
        start = Offset(ax, yAEnd),
        end = Offset(bx, yBEnd),
        strokeWidth = BEAM_STROKE_PX,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawTie(ax: Float, bx: Float, noteY: Float, spacePx: Float) {
    val y = noteY - TIE_OFFSET_SPACES * spacePx
    val rise = TIE_RISE_SPACES * spacePx
    val span = bx - ax
    val path = Path().apply {
        moveTo(ax, y)
        cubicTo(
            ax + span * 0.3f, y - rise,
            ax + span * 0.7f, y - rise,
            bx, y,
        )
    }
    drawPath(path = path, color = NotationSkin.Staff.noteColor, style = Stroke(width = TIE_STROKE_PX))
}

/** Grand-staff brace: a light curly connector from treble bottom to bass top. */
private fun DrawScope.drawBrace(x: Float, topY: Float, bottomY: Float) {
    val color = NotationSkin.Staff.lineColor
    val midY = (topY + bottomY) / 2f
    val half = (bottomY - topY) / 2f
    val path = Path().apply {
        moveTo(x, topY)
        cubicTo(x - 4f, topY + half * 0.25f, x + 4f, topY + half * 0.4f, x, midY)
        cubicTo(x - 4f, midY + half * 0.1f, x - 4f, midY - half * 0.1f, x, midY)
        cubicTo(x + 4f, midY - half * 0.4f, x - 4f, bottomY - half * 0.25f, x, bottomY)
    }
    drawPath(path = path, color = color, style = Stroke(width = 2f))
}

/** Stem direction: up for notes on/below the staff's middle line. */
private fun stemUpFor(layout: NoteLayout): Boolean {
    val middlePitch = if (layout.note.staff == 1) 71 else 50 // B4 treble / D3 bass
    return layout.note.pitch <= middlePitch
}

// ---------------------------------------------------------------------------
// Pre-measured glyphs (Bravura + note-bar labels)
// ---------------------------------------------------------------------------

/**
 * Glyph layout results measured ONCE per (layoutSet, textMeasurer) change so
 * per-frame drawing never re-measures text (the classic Canvas text trap).
 */
private class PreMeasuredGlyphs(
    val labels: Map<String, TextLayoutResult>,
    val trebleClef: TextLayoutResult,
    val bassClef: TextLayoutResult,
    val accidentals: Map<String, TextLayoutResult>,
    val timeSig: TextLayoutResult,
) {
    companion object {
        fun build(textMeasurer: TextMeasurer, layoutSet: NoteLayoutSet?): PreMeasuredGlyphs {
            val uniqueLabels = layoutSet?.notes?.mapTo(linkedSetOf()) { it.label }.orEmpty()
            val labels = uniqueLabels.associateWith { label ->
                textMeasurer.measure(
                    text = AnnotatedString(label),
                    style = TextStyle(fontSize = NOTE_BAR_LABEL_SP, color = Color.White),
                )
            }
            val trebleClef = BravuraGlyphs.measureGlyph(textMeasurer, BravuraGlyphs.TREBLE_CLEF, CLEF_SP)
            val bassClef = BravuraGlyphs.measureGlyph(textMeasurer, BravuraGlyphs.BASS_CLEF, CLEF_SP)
            val accidentals = listOf(BravuraGlyphs.SHARP, BravuraGlyphs.FLAT, BravuraGlyphs.NATURAL)
                .associateWith { BravuraGlyphs.measureGlyph(textMeasurer, it, ACCIDENTAL_SP) }
            val timeSig = textMeasurer.measure(
                text = AnnotatedString("4/4"),
                style = TextStyle(fontSize = TIME_SIG_SP, color = Color.Unspecified),
            )
            return PreMeasuredGlyphs(labels, trebleClef, bassClef, accidentals, timeSig)
        }
    }
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private const val DEFAULT_PX_PER_BEAT = 90f
private const val DEFAULT_LOOKAHEAD_BEATS = 12.0
private val PLAYHEAD_COLOR = Color(0xCCE03131)
private const val PLAYHEAD_STROKE_PX = 2.5f
private const val STAFF_LINE_STROKE_PX = 1.2f
private const val STEM_LENGTH_PX = 34f
private const val STEM_STROKE_PX = 2f
private const val LEDGER_OVERHANG_PX = 5f
private const val BEAM_OFFSET_SPACES = 3.5f
private const val BEAM_STROKE_PX = 4f
private const val TIE_OFFSET_SPACES = 1.6f
private const val TIE_RISE_SPACES = 1.0f
private const val TIE_STROKE_PX = 1.8f
private const val ACCIDENTAL_GAP_PX = 6f
private const val ACCIDENTAL_GLYPH_PX = 16f
private val CLEF_SP = 56.sp
private val ACCIDENTAL_SP = 18.sp
private val TIME_SIG_SP = 14.sp
private val NOTE_BAR_LABEL_SP = 12.sp