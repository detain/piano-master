package com.keyquest.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * MeasureMapper tests (plan §6, §20 P1.5.4 — per-measure heatmap). The
 * derivation mirrors pipeline/pipeline/build/stage_normalize.py `_linearize`:
 * measure 0 = the pickup measure [0, pickupBeats) (empty when no pickup),
 * measure m >= 1 = a full measure of the active time signature's
 * numerator*4/denominator beats.
 */
class MeasureMapperTest {

    private fun fourFour(pickup: Double, duration: Double = Double.POSITIVE_INFINITY): MeasureMapper =
        MeasureMapper(listOf(TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4)), pickup, duration)

    // ------------------------------------------------------------------
    // 4/4 without pickup
    // ------------------------------------------------------------------

    @Test
    fun fourFourNoPickupMapsBeatsToMeasures() {
        val mapper = fourFour(pickup = 0.0, duration = 16.0)
        // Measure 0 is the empty pickup slot; measure 1 = [0, 4), etc.
        assertEquals(1, mapper.measureIndex(0.0))
        assertEquals(1, mapper.measureIndex(3.9))
        assertEquals(2, mapper.measureIndex(4.0))
        assertEquals(3, mapper.measureIndex(8.0))
        assertEquals(4, mapper.measureIndex(15.9))
    }

    @Test
    fun beatsAtOrPastTheDurationClampToTheLastMeasure() {
        val mapper = fourFour(pickup = 0.0, duration = 16.0)
        assertEquals(4, mapper.measureIndex(16.0))
        assertEquals(4, mapper.measureIndex(100.0))
    }

    // ------------------------------------------------------------------
    // spec example: 1-beat pickup + 8 measures of 4/4 -> 33 beats
    // ------------------------------------------------------------------

    @Test
    fun pickupOneBeatSpecExample() {
        // durationBeats 33 = 1-beat pickup + 8 x 4/4 (songpack-v1.md §2).
        val mapper = fourFour(pickup = 1.0, duration = 33.0)
        assertEquals(0, mapper.measureIndex(0.0))
        assertEquals(0, mapper.measureIndex(0.999))
        assertEquals(1, mapper.measureIndex(1.0))
        assertEquals(1, mapper.measureIndex(4.999))
        assertEquals(2, mapper.measureIndex(5.0))
        assertEquals(8, mapper.measureIndex(29.0))
        assertEquals(8, mapper.measureIndex(32.999))
        // Clamp at/past the end of the last measure.
        assertEquals(8, mapper.measureIndex(33.0))
        assertEquals(8, mapper.measureIndex(50.0))
    }

    @Test
    fun measureStartBeatRoundTripsThroughMeasureIndex() {
        val mapper = fourFour(pickup = 1.0, duration = 33.0)
        assertEquals(0.0, mapper.measureStartBeat(0), 0.0)
        assertEquals(1.0, mapper.measureStartBeat(1), 0.0)
        assertEquals(5.0, mapper.measureStartBeat(2), 0.0)
        assertEquals(29.0, mapper.measureStartBeat(8), 0.0)
        for (measure in 0..8) {
            val start = mapper.measureStartBeat(measure)
            assertEquals(measure, mapper.measureIndex(start + 0.001))
        }
    }

    @Test
    fun constructorPropertiesExposeSortedInputs() {
        val mapper = MeasureMapper(
            listOf(
                TimeSignature(atBeat = 4.0, numerator = 3, denominator = 4),
                TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4),
            ),
            pickupBeats = 1.0,
            durationBeats = 33.0,
        )
        // Sorted by atBeat; pickup/duration stored verbatim.
        assertEquals(
            listOf(TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4), TimeSignature(atBeat = 4.0, numerator = 3, denominator = 4)),
            mapper.timeSignatures,
        )
        assertEquals(1.0, mapper.pickupBeats, 0.0)
        assertEquals(33.0, mapper.durationBeats, 0.0)
    }

    // ------------------------------------------------------------------
    // other signatures
    // ------------------------------------------------------------------

    @Test
    fun sixEightMeasuresAreThreeBeats() {
        val mapper = MeasureMapper(
            listOf(TimeSignature(atBeat = 0.0, numerator = 6, denominator = 8)),
            pickupBeats = 0.0,
            durationBeats = 12.0,
        )
        assertEquals(1, mapper.measureIndex(2.9))
        assertEquals(2, mapper.measureIndex(3.0))
        assertEquals(3, mapper.measureIndex(6.0))
        assertEquals(4, mapper.measureIndex(11.9))
        assertEquals(4, mapper.measureIndex(12.0))
    }

    @Test
    fun midSongTimeSignatureChangeTakesEffectAtItsBeat() {
        val mapper = MeasureMapper(
            listOf(
                TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4),
                TimeSignature(atBeat = 8.0, numerator = 3, denominator = 4),
            ),
            pickupBeats = 0.0,
            durationBeats = 14.0,
        )
        // Two 4/4 measures [0,8), then 3/4 measures [8,11), [11,14).
        assertEquals(2, mapper.measureIndex(7.9))
        assertEquals(3, mapper.measureIndex(8.0))
        assertEquals(3, mapper.measureIndex(10.9))
        assertEquals(4, mapper.measureIndex(11.0))
        assertEquals(4, mapper.measureIndex(13.9))
        assertEquals(3.0, mapper.beatsPerMeasureAt(9.0), 0.0)
        assertEquals(8.0, mapper.measureStartBeat(3), 0.0)
    }

    @Test
    fun pickupEqualToAFullMeasureStillCountsAsMeasureZero() {
        val mapper = fourFour(pickup = 4.0, duration = 20.0)
        assertEquals(0, mapper.measureIndex(2.0))
        assertEquals(1, mapper.measureIndex(4.0))
        assertEquals(4, mapper.measureIndex(19.9))
        assertEquals(4, mapper.measureIndex(20.0))
        assertEquals(4.0, mapper.beatsPerMeasureAt(2.0), 0.0)
    }

    // ------------------------------------------------------------------
    // infinite duration (walk path)
    // ------------------------------------------------------------------

    @Test
    fun withoutDurationMeasuresExtendStructurally() {
        val mapper = fourFour(pickup = 1.0)
        assertEquals(9, mapper.measureIndex(33.0)) // not clamped — structural
        assertEquals(10, mapper.measureIndex(40.0))
        assertEquals(17.0, mapper.measureStartBeat(5), 0.0) // 1 + 4*4
        assertEquals(5, mapper.measureIndex(17.5))
    }

    // ------------------------------------------------------------------
    // constructor guards
    // ------------------------------------------------------------------

    @Test
    fun constructorRejectsEmptyTimeSignatures() {
        assertThrows(IllegalArgumentException::class.java) {
            MeasureMapper(emptyList(), pickupBeats = 0.0)
        }
    }

    @Test
    fun constructorRequiresFirstSignatureAtBeatZero() {
        assertThrows(IllegalArgumentException::class.java) {
            MeasureMapper(listOf(TimeSignature(atBeat = 4.0, numerator = 4, denominator = 4)), 0.0)
        }
    }

    @Test
    fun constructorRejectsNonIncreasingSignatureBeats() {
        assertThrows(IllegalArgumentException::class.java) {
            MeasureMapper(
                listOf(
                    TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4),
                    TimeSignature(atBeat = 0.0, numerator = 3, denominator = 4),
                ),
                0.0,
            )
        }
    }

    @Test
    fun constructorRejectsNegativePickupAndDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            MeasureMapper(listOf(TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4)), -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasureMapper(listOf(TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4)), 0.0, -2.0)
        }
    }

    @Test
    fun constructorRejectsPickupLongerThanOneMeasure() {
        // songpack-v1.md §7.1: an anacrusis is at most one measure.
        assertThrows(IllegalArgumentException::class.java) {
            fourFour(pickup = 4.5)
        }
    }

    @Test
    fun timeSignatureRejectsSchemaViolations() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeSignature(atBeat = 0.0, numerator = 0, denominator = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeSignature(atBeat = 0.0, numerator = 33, denominator = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeSignature(atBeat = 0.0, numerator = 4, denominator = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeSignature(atBeat = -1.0, numerator = 4, denominator = 4)
        }
    }

    @Test
    fun measureIndexRejectsNegativeBeat() {
        val mapper = fourFour(pickup = 0.0)
        assertThrows(IllegalArgumentException::class.java) { mapper.measureIndex(-0.5) }
        assertThrows(IllegalArgumentException::class.java) { mapper.beatsPerMeasureAt(-1.0) }
    }

    @Test
    fun measureStartBeatRejectsOutOfRange() {
        val mapper = fourFour(pickup = 1.0, duration = 33.0)
        assertThrows(IllegalArgumentException::class.java) { mapper.measureStartBeat(-1) }
        // 9 entries (0..8) + sentinel = index 9 is the sentinel measure start.
        assertEquals(33.0, mapper.measureStartBeat(9), 0.0)
        assertThrows(IllegalArgumentException::class.java) { mapper.measureStartBeat(10) }
    }
}