package com.keyquest.app.notation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
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
 * @param feedback per-note verdicts and hit beats (plan P1.6.5), reused across
 *   frames and read in place — never triggers a layout rebuild. Both arrays
 *   are index-parallel to [ProtoScore.notes] (the canonical scorer order, and
 *   the same order [NoteLayoutSet.notes] is built in), so `verdicts[i]` colors
 *   exactly the note drawn at index `i`. When null, every note renders neutral
 *   with no pop.
 * @param reducedMotion when true, feedback is color-only (plan P1.6.6): hit
 *   notes still take the hit color, but the pop animation is suppressed.
 * @param externalSongTimeBeats when non-null, the draw position uses this value
 *   INSTEAD of the internal accumulated songTimeBeats (the internal clock keeps
 *   running but is unused — the prototype screen passes null).
 * @param layoutDispatcher dispatcher the pre-layout build runs on. The
 *   production default builds off-main ([Dispatchers.Default]); screenshot
 *   tests pass [Dispatchers.Unconfined] so the layout lands synchronously
 *   before the first captured frame. Mirrors the existing [frameClock]
 *   testability seam.
 * @param fixedViewportSize when non-null, the pre-layout build uses this size
 *   instead of the [onSizeChanged]-reported viewport. Production leaves it
 *   null and builds from the measured size; screenshot tests pass the device
 *   pixel size (Paparazzi does not deliver layout-phase callbacks).
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
    feedback: NoteFeedback? = null,
    reducedMotion: Boolean = false,
    externalSongTimeBeats: Double? = null,
    layoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
    fixedViewportSize: IntSize? = null,
) {
    var songTimeBeats by remember { mutableDoubleStateOf(0.0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val currentTempo by rememberUpdatedState(tempoBpm)

    // Frame-timestamp store shared by the clock coroutine and the lifecycle
    // observer below. ON_RESUME drops the stale timestamp so a background gap
    // can never fast-forward the score.
    val lastFrameNanos = remember { AtomicLong(-1L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lastFrameNanos.set(-1L)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Playhead clock: accumulate songTime only while playing.
    LaunchedEffect(playing, frameClock) {
        // Fresh start whenever playback toggles or the frame clock changes.
        lastFrameNanos.set(-1L)
        while (true) {
            val now = if (frameClock != null) {
                frameClock.withFrameNanos { it }
            } else {
                withFrameNanos { it }
            }
            val previous = lastFrameNanos.getAndSet(now)
            if (playing && previous >= 0L) {
                // Clamp the delta: a hiccup or stale timestamp must not jump
                // the playhead (a background gap is handled by ON_RESUME above).
                val deltaNanos = (now - previous).coerceIn(0L, MAX_FRAME_DELTA_NANOS)
                val deltaSeconds = deltaNanos / 1_000_000_000.0
                songTimeBeats += deltaSeconds * LayoutMath.beatsPerSecond(currentTempo)
            }
        }
    }

    // Pre-layout, off the main thread. Rebuilds only when the keys change.
    val layoutSet by produceState(
        initialValue = null as NoteLayoutSet?,
        score, skin, pxPerBeat, viewportSize, fixedViewportSize,
    ) {
        val viewport = fixedViewportSize ?: viewportSize
        if (viewport.width > 0 && viewport.height > 0) {
            value = withContext(layoutDispatcher) {
                NoteLayoutBuilder.build(
                    score = score,
                    skin = skin,
                    pxPerBeat = pxPerBeat,
                    viewportWidth = viewport.width.toFloat(),
                    viewportHeight = viewport.height.toFloat(),
                )
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val glyphs = remember(layoutSet, textMeasurer) { PreMeasuredGlyphs.build(textMeasurer, layoutSet) }

    // Mutable path pool, created ONCE per layout set on the UI thread (never
    // per frame): the staff brace is traced once (static geometry) and each tie
    // path is re-traced via reset() when its x moves with the scrolling score.
    val staffPathPool = remember(layoutSet) { StaffPathPool.build(layoutSet?.staff) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
    ) {
        val set = layoutSet ?: return@Canvas
        val songTime = externalSongTimeBeats ?: songTimeBeats
        val viewportW = size.width
        val playheadX = size.width * LayoutMath.DEFAULT_PLAYHEAD_FRACTION

        when (skin) {
            NotationSkin.NoteBar -> drawNoteBarBackground(set.noteBar)
            NotationSkin.Staff -> drawStaffBackground(set.staff, glyphs, staffPathPool)
        }

        // Beams and ties sit under the noteheads they connect.
        set.staff?.let { staff ->
            val pool = staffPathPool ?: return@let // staff and pool are 1:1 with layoutSet
            for (beam in staff.beams) {
                val a = set.notes[beam.noteAIndex]
                val b = set.notes[beam.noteBIndex]
                val ax = a.screenX(songTime, pxPerBeat) + a.width / 2f
                val bx = b.screenX(songTime, pxPerBeat) + b.width / 2f
                if ((ax < 0f && bx < 0f) || (ax > viewportW && bx > viewportW)) continue
                drawBeam(ax, bx, beam.yA, beam.yB, staff.spacePx)
            }
            for (tieIndex in staff.ties.indices) {
                val tie = staff.ties[tieIndex]
                val a = set.notes[tie.noteAIndex]
                val b = set.notes[tie.noteBIndex]
                val ax = a.screenX(songTime, pxPerBeat) + a.width / 2f
                val bx = b.screenX(songTime, pxPerBeat) + b.width / 2f
                if ((ax < 0f && bx < 0f) || (ax > viewportW && bx > viewportW)) continue
                drawTie(pool.ties[tieIndex], ax, bx, tie.y, staff.spacePx)
            }
        }

        // Notes: translate + draw only, no per-frame layout or allocation.
        // Feedback arrays are index-parallel to score.notes (see NoteFeedback),
        // and set.notes preserves that order (NoteLayoutBuilder appends 1:1),
        // so index i maps to exactly the note the scorer judged.
        val feedbackVerdicts = feedback?.verdicts
        val feedbackHitBeats = feedback?.hitBeats
        val nowBeats = songTime.toFloat()
        for (index in set.notes.indices) {
            val layout = set.notes[index]
            if (!layout.isVisible(songTime, pxPerBeat, viewportW)) continue
            val sx = layout.screenX(songTime, pxPerBeat)
            val verdict = feedbackVerdicts?.get(index) ?: NoteFeedback.OPEN
            val hitBeat = feedbackHitBeats?.get(index) ?: -1f
            when (skin) {
                NotationSkin.NoteBar -> drawNoteBarNote(layout, sx, set.noteBar, glyphs, verdict, hitBeat, nowBeats, reducedMotion)
                NotationSkin.Staff -> drawStaffNote(layout, sx, set.staff, glyphs, verdict)
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
            lanesPerHand = bar.lanesPerHand,
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
    feedbackVerdict: Int,
    hitBeat: Float,
    nowBeats: Float,
    reducedMotion: Boolean,
) {
    val laneHeight = bar?.laneHeightPx ?: 24f
    val color = when (feedbackVerdict) {
        NoteFeedback.PERFECT, NoteFeedback.GOOD -> NotationSkin.NoteBar.hit
        NoteFeedback.MISSED, NoteFeedback.WRONG -> NotationSkin.NoteBar.miss
        else -> if (layout.note.hand == 'L') {
            NotationSkin.NoteBar.neutralLeft
        } else {
            NotationSkin.NoteBar.neutralRight
        }
    }
    val topLeft = Offset(sx, layout.y - layout.height / 2f)
    val size = Size(layout.width, layout.height)
    val popScale = popScale(nowBeats, hitBeat, reducedMotion)
    drawRoundRect(
        color = color,
        topLeft = Offset(
            topLeft.x + size.width * (1f - popScale) / 2f,
            topLeft.y + size.height * (1f - popScale) / 2f,
        ),
        size = Size(size.width * popScale, size.height * popScale),
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

/**
 * Hit-pop scale for a note-bar: the bar grows to 1 + [POP_GROWTH_FRACTION] at
 * the instant of the hit and decays back to 1 over [POP_DECAY_BEATS] of song
 * time. Returns `1f` (no pop) when there is no hit, the decay window has
 * closed, or reduced motion is enabled. Pure float math — no allocation.
 */
private fun popScale(nowBeats: Float, hitBeat: Float, reducedMotion: Boolean): Float {
    if (reducedMotion || hitBeat < 0f) return 1f
    val age = nowBeats - hitBeat
    if (age < 0f || age > POP_DECAY_BEATS) return 1f
    return 1f + POP_GROWTH_FRACTION * (1f - age / POP_DECAY_BEATS)
}

// ---------------------------------------------------------------------------
// Staff skin drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawStaffBackground(staff: StaffLayout?, glyphs: PreMeasuredGlyphs, pathPool: StaffPathPool?) {
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
    // Grand-staff brace on the left: pre-traced path, per-frame draw only.
    pathPool?.let { drawBrace(it.brace) }
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
    feedbackVerdict: Int,
) {
    val space = staff?.spacePx ?: NoteLayoutBuilder.STAFF_SPACE_PX
    val noteColor = NotationSkin.Staff.noteColor
    // Feedback colors the notehead only (plan P1.6.5); stems and ledger lines
    // keep the neutral ink. No pop for the staff skin — color-only, minimal.
    val noteheadColor = when (feedbackVerdict) {
        NoteFeedback.PERFECT, NoteFeedback.GOOD -> NotationSkin.NoteBar.hit
        NoteFeedback.MISSED, NoteFeedback.WRONG -> NotationSkin.NoteBar.miss
        else -> noteColor
    }
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
        drawOval(color = noteheadColor, topLeft = rect.topLeft, size = rect.size, style = HOLLOW_NOTE_STROKE)
    } else {
        drawOval(color = noteheadColor, topLeft = rect.topLeft, size = rect.size)
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

private fun DrawScope.drawTie(path: Path, ax: Float, bx: Float, noteY: Float, spacePx: Float) {
    val y = noteY - TIE_OFFSET_SPACES * spacePx
    val rise = TIE_RISE_SPACES * spacePx
    val span = bx - ax
    // Re-trace the pre-allocated path from [StaffPathPool]: zero per-frame
    // allocation (plan §7.1 "translate + draw only").
    path.reset()
    path.moveTo(ax, y)
    path.cubicTo(
        ax + span * 0.3f, y - rise,
        ax + span * 0.7f, y - rise,
        bx, y,
    )
    drawPath(path = path, color = NotationSkin.Staff.noteColor, style = TIE_STROKE)
}

/** Grand-staff brace: pre-traced once in [StaffPathPool]; per-frame draw only. */
private fun DrawScope.drawBrace(path: Path) {
    drawPath(path = path, color = NotationSkin.Staff.lineColor, style = BRACE_STROKE)
}

/**
 * Mutable path pool for the staff skin, created ONCE per layout set on the UI
 * thread (never per frame). The brace is traced once because its geometry is
 * static; each tie path is re-traced per frame via [Path.reset] because its x
 * moves with the scrolling song time. Per-frame work stays allocation-free.
 *
 * [ties] is index-parallel to [StaffLayout.ties].
 */
private class StaffPathPool(
    val brace: Path,
    val ties: List<Path>,
) {
    companion object {
        fun build(staff: StaffLayout?): StaffPathPool? = staff?.let { s ->
            val topY = s.trebleTopY + 4 * s.spacePx
            val bottomY = s.bassTopY
            val midY = (topY + bottomY) / 2f
            val half = (bottomY - topY) / 2f
            val dx = BRACE_CONTROL_DX
            StaffPathPool(
                brace = Path().apply {
                    moveTo(BRACE_X, topY)
                    cubicTo(BRACE_X - dx, topY + half * 0.25f, BRACE_X + dx, topY + half * 0.4f, BRACE_X, midY)
                    cubicTo(BRACE_X - dx, midY + half * 0.1f, BRACE_X - dx, midY - half * 0.1f, BRACE_X, midY)
                    cubicTo(BRACE_X + dx, midY - half * 0.4f, BRACE_X - dx, bottomY - half * 0.25f, BRACE_X, bottomY)
                },
                ties = s.ties.map { Path() },
            )
        }
    }
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
private const val BRACE_X = 3f
private const val BRACE_CONTROL_DX = 4f
private const val MAX_FRAME_DELTA_NANOS = 100_000_000L // 100ms defense clamp
private const val POP_DECAY_BEATS = 0.25f // hit-pop decay window, in song-time beats
private const val POP_GROWTH_FRACTION = 0.15f // peak hit-pop growth above 1f

// Pre-built Stroke objects so the per-frame draw path never allocates a style.
private val TIE_STROKE = Stroke(width = TIE_STROKE_PX)
private val BRACE_STROKE = Stroke(width = 2f)
private val HOLLOW_NOTE_STROKE = Stroke(width = 1.8f)

private val CLEF_SP = 56.sp
private val ACCIDENTAL_SP = 18.sp
private val TIME_SIG_SP = 14.sp
private val NOTE_BAR_LABEL_SP = 12.sp