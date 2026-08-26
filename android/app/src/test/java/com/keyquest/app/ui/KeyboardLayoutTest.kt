package com.keyquest.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math tests for [KeyboardLayout] (plan §24 P1.6.8: on-screen keyboard):
 * black/white classification, white-key offsets and counts, visible-range
 * padding, and the x-fractions the renderer and hit-test share. Everything
 * under test is a pure function over MIDI pitches — no Android or Compose —
 * so these run on the JVM in milliseconds.
 */
class KeyboardLayoutTest {

    // ------------------------------------------------------------------
    // black/white classification
    // ------------------------------------------------------------------

    @Test
    fun isBlackOctavePattern() {
        // C4..B4: black exactly on pitch classes {1,3,6,8,10} (C#, D#, F#, G#, A#).
        assertFalse(KeyboardLayout.isBlack(60)) // C4
        assertTrue(KeyboardLayout.isBlack(61)) // C#4
        assertFalse(KeyboardLayout.isBlack(62)) // D4
        assertTrue(KeyboardLayout.isBlack(63)) // D#4
        assertFalse(KeyboardLayout.isBlack(64)) // E4
        assertFalse(KeyboardLayout.isBlack(65)) // F4
        assertTrue(KeyboardLayout.isBlack(66)) // F#4
        assertFalse(KeyboardLayout.isBlack(67)) // G4
        assertTrue(KeyboardLayout.isBlack(68)) // G#4
        assertFalse(KeyboardLayout.isBlack(69)) // A4
        assertTrue(KeyboardLayout.isBlack(70)) // A#4
        assertFalse(KeyboardLayout.isBlack(71)) // B4
    }

    // ------------------------------------------------------------------
    // white-key offsets and counts
    // ------------------------------------------------------------------

    @Test
    fun whiteKeyOffsetKnownValues() {
        // White keys strictly below the pitch on the 88-key keyboard.
        assertEquals(0, KeyboardLayout.whiteKeyOffset(21)) // A0
        assertEquals(23, KeyboardLayout.whiteKeyOffset(60)) // C4
        assertEquals(51, KeyboardLayout.whiteKeyOffset(108)) // C8
        // A#0 (22) is black; exactly one white (A0) sits below it.
        assertEquals(1, KeyboardLayout.whiteKeyOffset(22)) // A#0
        // C1 (24): the whites below it are A0 and B0.
        assertEquals(2, KeyboardLayout.whiteKeyOffset(24)) // C1
    }

    @Test
    fun whiteKeyCountRange() {
        assertEquals(52, KeyboardLayout.whiteKeyCount(21, 108)) // full 88-key piano
        assertEquals(1, KeyboardLayout.whiteKeyCount(60, 60)) // single white key
        assertEquals(1, KeyboardLayout.whiteKeyCount(21, 22)) // A0 + black A#0
    }

    // ------------------------------------------------------------------
    // visible-range padding and clamping
    // ------------------------------------------------------------------

    @Test
    fun visibleRangeClampsAndPads() {
        // One note pads 7 semitones each way.
        assertEquals(53..67, KeyboardLayout.visibleRange(listOf(60), 7))
        // Empty input yields the full 88-key range.
        assertEquals(21..108, KeyboardLayout.visibleRange(emptyList()))
        // Pad below A0 clamps to the keyboard's low edge.
        assertEquals(21..24, KeyboardLayout.visibleRange(listOf(21), 3))
        // Pad above C8 clamps to the keyboard's high edge.
        assertEquals(103..108, KeyboardLayout.visibleRange(listOf(108), 5))
    }

    // ------------------------------------------------------------------
    // x-fractions shared by the renderer and the hit-test
    // ------------------------------------------------------------------

    @Test
    fun whiteXFractionRange() {
        // Left edge of the only white key in a 1-key span sits at 0.
        assertEquals(0f, KeyboardLayout.whiteXFraction(60, 60, 1), 1e-6f)
        // C8 is the 52nd white: its left edge is at 51/52 (1 is one-past-the-last).
        assertEquals(51f / 52f, KeyboardLayout.whiteXFraction(108, 21, 52), 1e-6f)
        // C4 is the 24th white, so 23 of 52 whites lie before it.
        assertEquals(23f / 52f, KeyboardLayout.whiteXFraction(60, 21, 52), 1e-6f)
    }

    @Test
    fun blackXFractionCentered() {
        // C#4 sits on the C4|D4 boundary: whiteKeyOffset(61) = 24 — the lower
        // white C4 (offset 23) counts as strictly below the black pitch — so
        // the center is 1 white-width in from the range start: 1 / 2.
        assertEquals(0.5f, KeyboardLayout.blackXFraction(61, 60, 2), 1e-6f)
        // D#4 sits on the D4|E4 boundary: whiteKeyOffset(63) = 25, so the
        // center is 2 white-widths in from C4 (offset 23): 2 / 4.
        assertEquals(0.5f, KeyboardLayout.blackXFraction(63, 60, 4), 1e-6f)
    }

    // ------------------------------------------------------------------
    // invalid inputs fail fast
    // ------------------------------------------------------------------

    @Test
    fun outOfRangeRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardLayout.whiteKeyOffset(20) // below A0
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardLayout.whiteKeyOffset(109) // above C8
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardLayout.whiteKeyCount(100, 90) // first > last
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardLayout.visibleRange(listOf(60), -1) // negative pad
        }
    }

    @Test
    fun blackXFractionRejectsWhitePitch() {
        // 60 (C4) is a white key — blackXFraction requires a black pitch.
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardLayout.blackXFraction(60, 60, 2)
        }
    }
}