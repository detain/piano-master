package com.keyquest.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Pure geometry for the on-screen piano (plan §24 P1.6.8).
 *
 * Everything here is a pure function over MIDI pitches — no Android or
 * Compose dependencies — so it is trivially unit-testable on the JVM. The
 * keyboard spans the full 88-key range, A0 (21) .. C8 (108); the renderer
 * and the touch hit-test both derive every x from these functions, so what
 * is drawn is exactly what is hittable.
 */
object KeyboardLayout {

    /** Lowest MIDI pitch of an 88-key piano (A0). */
    const val MIN_PITCH = 21

    /** Highest MIDI pitch of an 88-key piano (C8). */
    const val MAX_PITCH = 108

    /** Pitch classes with black keys: C#, D#, F#, G#, A#. */
    private val BLACK_PITCH_CLASSES = intArrayOf(1, 3, 6, 8, 10)

    // White keys in [0, pc) for each pitch class: C=0, C#=1, D=1, D#=2,
    // E=2, F=3, F#=4, G=4, G#=5, A=5, A#=6, B=6.
    private val WHITE_BELOW_IN_PC = intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6)

    /** White keys in [0, MIN_PITCH): octave C-1..B-1 (7) + C0..G0 (5). */
    private const val WHITE_KEYS_BELOW_MIN_PITCH = 12

    /**
     * Whether the pitch is a black key (C#, D#, F#, G#, A#).
     */
    fun isBlack(pitch: Int): Boolean {
        require(pitch in MIN_PITCH..MAX_PITCH) { "pitch $pitch outside $MIN_PITCH..$MAX_PITCH" }
        return pitch % 12 in BLACK_PITCH_CLASSES
    }

    /**
     * Number of white keys strictly below the pitch on the full 88-key
     * keyboard (white keys in [MIN_PITCH, pitch)): A0 -> 0, C4 -> 23, C8 -> 51.
     */
    fun whiteKeyOffset(pitch: Int): Int {
        require(pitch in MIN_PITCH..MAX_PITCH) { "pitch $pitch outside $MIN_PITCH..$MAX_PITCH" }
        // 7 white keys per full octave, plus the white keys below the pitch
        // class within its octave, minus the 12 white keys below A0.
        return (pitch / 12) * 7 + WHITE_BELOW_IN_PC[pitch % 12] - WHITE_KEYS_BELOW_MIN_PITCH
    }

    /**
     * Number of white keys in the inclusive range [firstPitch, lastPitch].
     */
    fun whiteKeyCount(firstPitch: Int, lastPitch: Int): Int {
        require(firstPitch in MIN_PITCH..MAX_PITCH) { "firstPitch $firstPitch outside $MIN_PITCH..$MAX_PITCH" }
        require(lastPitch in MIN_PITCH..MAX_PITCH) { "lastPitch $lastPitch outside $MIN_PITCH..$MAX_PITCH" }
        require(firstPitch <= lastPitch) { "firstPitch $firstPitch > lastPitch $lastPitch" }
        var count = 0
        for (pitch in firstPitch..lastPitch) {
            if (!isBlack(pitch)) count++
        }
        return count
    }

    /**
     * The sub-range of the 88-key piano that fits the given pitches, padded
     * by [padSemitones] on both sides (default 7 = a fifth of hand room).
     * Empty input yields the full 88-key range; results are clamped to
     * MIN_PITCH..MAX_PITCH.
     */
    fun visibleRange(pitches: Collection<Int>, padSemitones: Int = 7): IntRange {
        require(padSemitones >= 0) { "padSemitones must be >= 0, was $padSemitones" }
        if (pitches.isEmpty()) return MIN_PITCH..MAX_PITCH
        var minPitch = MAX_PITCH
        var maxPitch = MIN_PITCH
        for (pitch in pitches) {
            require(pitch in MIN_PITCH..MAX_PITCH) { "pitch $pitch outside $MIN_PITCH..$MAX_PITCH" }
            if (pitch < minPitch) minPitch = pitch
            if (pitch > maxPitch) maxPitch = pitch
        }
        return (minPitch - padSemitones).coerceAtLeast(MIN_PITCH)..(maxPitch + padSemitones).coerceAtMost(MAX_PITCH)
    }

    /**
     * Fraction of the visible white-key span at which the white key's LEFT
     * edge sits: 0 for the first white key, 1 for one-past-the-last. Callers
     * pass the range's first white key as [firstPitch] and the range's
     * white-key count as [whiteCount] (from [whiteKeyCount]).
     */
    fun whiteXFraction(pitch: Int, firstPitch: Int, whiteCount: Int): Float {
        require(pitch in MIN_PITCH..MAX_PITCH) { "pitch $pitch outside $MIN_PITCH..$MAX_PITCH" }
        require(firstPitch in MIN_PITCH..MAX_PITCH) { "firstPitch $firstPitch outside $MIN_PITCH..$MAX_PITCH" }
        require(whiteCount > 0) { "whiteCount must be > 0, was $whiteCount" }
        return (whiteKeyOffset(pitch) - whiteKeyOffset(firstPitch)) / whiteCount.toFloat()
    }

    /**
     * Fraction of the visible white-key span at which the black key's CENTER
     * sits: the BOUNDARY between its two surrounding white keys (one semitone
     * below and above — every black key is flanked by whites, and neither
     * extreme of the 88-key range is black). The boundary lies one
     * white-key-width right of the lower white's (pitch - 1) left edge; for
     * a black pitch, [whiteKeyOffset] already counts the lower white, so it
     * yields exactly that boundary. Requires a black-key [pitch].
     */
    fun blackXFraction(pitch: Int, firstPitch: Int, whiteCount: Int): Float {
        require(pitch in MIN_PITCH..MAX_PITCH) { "pitch $pitch outside $MIN_PITCH..$MAX_PITCH" }
        require(isBlack(pitch)) { "pitch $pitch is not a black key" }
        require(firstPitch in MIN_PITCH..MAX_PITCH) { "firstPitch $firstPitch outside $MIN_PITCH..$MAX_PITCH" }
        require(whiteCount > 0) { "whiteCount must be > 0, was $whiteCount" }
        return (whiteKeyOffset(pitch) - whiteKeyOffset(firstPitch)) / whiteCount.toFloat()
    }
}

/**
 * Multi-touch on-screen piano keyboard for the lesson player (plan §24
 * P1.6.8: on-screen keyboard, target glow, wrong-key flash).
 *
 * Touch contract — every pointer is tracked by id from down to up/cancel:
 *  - a pointer down on a key calls [onNoteOn] exactly once, with the pitch
 *    fixed at the down position (a slide never retriggers);
 *  - that pointer's up, or a gesture cancelled mid-play (parent consumed
 *    the stream, pointer stolen), calls [onNoteOff] with the same pitch —
 *    release is guaranteed, so notes can never stick;
 *  - two pointers on one key each report note-on/note-off (the audio layer
 *    decides how to stack them); the key stays visually pressed while any
 *    pointer holds it.
 *
 * Hit-test rule: black keys are narrower and drawn ON TOP of the whites; an
 * x inside a black key's rect yields the black pitch, otherwise the white
 * key under x. Both hit-testing and drawing derive x from [KeyboardLayout],
 * so they cannot disagree.
 *
 * Targets: [targets] is index-parallel to [pitchRange] — index
 * `pitch - pitchRange.first`, true = "expected key". The caller recomputes
 * it per frame with ~1 beat of lookahead (plan §24 "target glow ~1 beat
 * lead") and must back it with snapshot state so each update re-runs the
 * draw. Expected keys get a bright glow stroke; with [reducedMotion] the
 * glow collapses to a solid outline.
 *
 * Wrong-key feedback (P1.6.7): a pressed key that is not in [targets] fills
 * red for as long as it is held; an expected key fills with the accent
 * color while pressed.
 *
 * @param pitchRange inclusive MIDI range to render (e.g. from
 *   [KeyboardLayout.visibleRange]).
 * @param targets per-pitch expected-key flags, see above.
 */
@Composable
fun OnScreenKeyboard(
    pitchRange: IntRange,
    targets: BooleanArray,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    require(pitchRange.first >= KeyboardLayout.MIN_PITCH) {
        "pitchRange.first ${pitchRange.first} below ${KeyboardLayout.MIN_PITCH}"
    }
    require(pitchRange.last <= KeyboardLayout.MAX_PITCH) {
        "pitchRange.last ${pitchRange.last} above ${KeyboardLayout.MAX_PITCH}"
    }
    require(pitchRange.first <= pitchRange.last) { "pitchRange must be non-empty, was $pitchRange" }
    val whiteCount = KeyboardLayout.whiteKeyCount(pitchRange.first, pitchRange.last)
    require(whiteCount > 0) { "keyboard range $pitchRange contains no white keys" }
    require(targets.size == pitchRange.count()) {
        "targets.size ${targets.size} must equal range count ${pitchRange.count()}"
    }

    // White/black pitch lists for the range — computed once per range; the
    // per-frame draw and the hit-test only iterate them.
    val whitePitches = remember(pitchRange) {
        pitchRange.filterNot { KeyboardLayout.isBlack(it) }.toIntArray()
    }
    val blackPitches = remember(pitchRange) {
        pitchRange.filter { KeyboardLayout.isBlack(it) }.toIntArray()
    }
    val firstWhitePitch = whitePitches[0] // whiteCount > 0, so never empty

    // Snapshot state so the draw re-runs as fingers move. A list with one
    // entry per pointer (not a set) keeps a key held by two fingers lit
    // until both are lifted.
    val pressedKeys = remember { mutableStateListOf<Int>() }

    // The gesture coroutine always calls the latest callbacks.
    val currentOnNoteOn by rememberUpdatedState(onNoteOn)
    val currentOnNoteOff by rememberUpdatedState(onNoteOff)

    Canvas(
        modifier = modifier
            .clipToBounds() // edge black keys sit half outside the first/last white
            .pointerInput(pitchRange) {
                awaitEachGesture {
                    // Per-gesture bookkeeping: each pointer's down pitch, so
                    // up always releases the note it started.
                    val pointerPitches = HashMap<PointerId, Int>()
                    val blackKeyHalfWidth = size.width.toFloat() / whiteCount * BLACK_KEY_WIDTH_FRACTION / 2f

                    fun press(change: PointerInputChange) {
                        val pitch = pitchAt(
                            x = change.position.x,
                            width = size.width.toFloat(),
                            whitePitches = whitePitches,
                            pitchRange = pitchRange,
                            blackKeyHalfWidth = blackKeyHalfWidth,
                        )
                        pointerPitches[change.id] = pitch
                        pressedKeys.add(pitch)
                        currentOnNoteOn(pitch)
                    }

                    fun release(change: PointerInputChange) {
                        val pitch = pointerPitches.remove(change.id) ?: return
                        pressedKeys.remove(pitch)
                        currentOnNoteOff(pitch)
                    }

                    val down = awaitFirstDown(requireUnconsumed = false)
                    press(down)
                    try {
                        do {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                when {
                                    change.changedToDown() -> press(change)
                                    !change.pressed -> release(change) // up or cancel
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        // Cancelled mid-gesture (parent consumed the stream,
                        // pointer lost): release every note we still hold so
                        // keys can never stick.
                        for (id in pointerPitches.keys.toList()) {
                            val pitch = pointerPitches.remove(id) ?: continue
                            pressedKeys.remove(pitch)
                            currentOnNoteOff(pitch)
                        }
                    }
                }
            },
    ) {
        val whiteWidth = size.width / whiteCount

        // White keys: full-height, slightly separated so they read as keys.
        for (i in whitePitches.indices) {
            val pitch = whitePitches[i]
            val pressed = pitch in pressedKeys
            val target = targets[pitch - pitchRange.first]
            val left = i * whiteWidth + WHITE_KEY_GAP_PX / 2f
            val width = whiteWidth - WHITE_KEY_GAP_PX
            val rect = Rect(left, 0f, left + width, size.height)
            drawRoundRect(
                color = keyColor(pressed = pressed, target = target, base = WHITE_KEY_COLOR),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = KEY_CORNER_RADIUS,
            )
            if (target) drawTargetGlow(rect, reducedMotion)
        }

        // Black keys: narrower and shorter, drawn ON TOP of the whites.
        val blackWidth = whiteWidth * BLACK_KEY_WIDTH_FRACTION
        val blackHeight = size.height * BLACK_KEY_HEIGHT_FRACTION
        for (pitch in blackPitches) {
            val pressed = pitch in pressedKeys
            val target = targets[pitch - pitchRange.first]
            val centerX = KeyboardLayout.blackXFraction(pitch, firstWhitePitch, whiteCount) * size.width
            val left = centerX - blackWidth / 2f
            val rect = Rect(left, 0f, left + blackWidth, blackHeight)
            drawRoundRect(
                color = keyColor(pressed = pressed, target = target, base = BLACK_KEY_COLOR),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = KEY_CORNER_RADIUS,
            )
            if (target) drawTargetGlow(rect, reducedMotion)
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------

/** Pressed keys fill with the accent when expected, red when wrong (P1.6.7). */
private fun keyColor(pressed: Boolean, target: Boolean, base: Color): Color = when {
    pressed && target -> PRESSED_KEY_COLOR
    pressed -> WRONG_KEY_COLOR
    else -> base
}

/**
 * Target glow: a soft outer halo plus a bright inner outline. [reducedMotion]
 * collapses the halo to a plain solid outline (plan §24 reduced-motion pass).
 */
private fun DrawScope.drawTargetGlow(rect: Rect, reducedMotion: Boolean) {
    if (reducedMotion) {
        drawRect(color = TARGET_GLOW_COLOR, topLeft = rect.topLeft, size = rect.size, style = TARGET_SOLID_STROKE)
        return
    }
    drawRect(color = TARGET_GLOW_HALO_COLOR, topLeft = rect.topLeft, size = rect.size, style = TARGET_GLOW_HALO_STROKE)
    drawRect(color = TARGET_GLOW_COLOR, topLeft = rect.topLeft, size = rect.size, style = TARGET_GLOW_INNER_STROKE)
}

/**
 * Piano hit-test: black keys are narrower and drawn on top, so an x inside a
 * black key's rect yields the black pitch; otherwise the white key under x.
 * The white key under x is found first, then its black neighbors (one
 * semitone below/above, per [KeyboardLayout.blackXFraction]) are tested
 * against their drawn rects; every black key's rect overlaps its lower
 * neighbor's white span, so the test can never miss a black key the drawing
 * shows.
 */
private fun pitchAt(
    x: Float,
    width: Float,
    whitePitches: IntArray,
    pitchRange: IntRange,
    blackKeyHalfWidth: Float,
): Int {
    val whiteWidth = width / whitePitches.size
    val whiteIndex = (x / whiteWidth).toInt().coerceIn(0, whitePitches.size - 1)
    val whitePitch = whitePitches[whiteIndex]
    for (neighbor in intArrayOf(whitePitch - 1, whitePitch + 1)) {
        if (neighbor !in pitchRange || !KeyboardLayout.isBlack(neighbor)) continue
        val center = KeyboardLayout.blackXFraction(neighbor, whitePitches[0], whitePitches.size) * width
        if (abs(x - center) <= blackKeyHalfWidth) return neighbor
    }
    return whitePitch
}

// ---------------------------------------------------------------------------
// Keyboard palette + geometry constants (prototype colors, plan §24)
// ---------------------------------------------------------------------------

private val WHITE_KEY_COLOR = Color(0xFFF8F9FA)
private val BLACK_KEY_COLOR = Color(0xFF212529)
private val PRESSED_KEY_COLOR = Color(0xFF4C6EF5) // accent (NoteBar left-hand indigo)
private val WRONG_KEY_COLOR = Color(0xFFE03131) // NoteBar miss red
private val TARGET_GLOW_COLOR = Color(0xFFFFD43B) // bright amber "next key" glow
private val TARGET_GLOW_HALO_COLOR = TARGET_GLOW_COLOR.copy(alpha = 0.30f)

private const val WHITE_KEY_GAP_PX = 1f
private const val BLACK_KEY_WIDTH_FRACTION = 0.6f
private const val BLACK_KEY_HEIGHT_FRACTION = 0.62f
private val KEY_CORNER_RADIUS = CornerRadius(3f)

// Pre-built strokes: the per-frame draw path never allocates a style.
private val TARGET_GLOW_HALO_STROKE = Stroke(width = 8f)
private val TARGET_GLOW_INNER_STROKE = Stroke(width = 2.5f)
private val TARGET_SOLID_STROKE = Stroke(width = 2.5f)