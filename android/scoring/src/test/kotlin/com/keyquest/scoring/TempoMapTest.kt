package com.keyquest.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TempoMap tests (plan §6, §20 P1.5.1): the beat->seconds port of
 * pipeline/pipeline/build/audio.py `_interval_seconds` / `_linear_bpm_at` /
 * `_bpm_seconds`. The linear-ramp assertions are hand-computed against the
 * Python closed form so the two implementations cannot drift silently.
 */
class TempoMapTest {

    private fun constant(bpm: Double): TempoMap = TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = bpm)))

    // ------------------------------------------------------------------
    // constant tempo
    // ------------------------------------------------------------------

    @Test
    fun constantTempoConvertsBeatsToSeconds() {
        val map = constant(120.0)
        assertEquals(0.0, map.beatToSeconds(0.0), 1e-9)
        assertEquals(0.5, map.beatToSeconds(1.0), 1e-9)
        assertEquals(2.0, map.beatToSeconds(4.0), 1e-9)
        assertEquals(60.0, map.beatToSeconds(120.0), 1e-9)
    }

    @Test
    fun nonPositiveBeatsMapToZeroSeconds() {
        val map = constant(120.0)
        assertEquals(0.0, map.beatToSeconds(0.0), 0.0)
        assertEquals(0.0, map.beatToSeconds(-1.0), 0.0)
        assertEquals(0.0, map.beatToSeconds(-1e9), 0.0)
    }

    @Test
    fun constantTempoBpmAtIsEverywhereTheSame() {
        val map = constant(90.0)
        assertEquals(90.0, map.bpmAt(0.0), 0.0)
        assertEquals(90.0, map.bpmAt(1.5), 0.0)
        assertEquals(90.0, map.bpmAt(-1.0), 0.0) // beats before beat 0 take the first entry
        assertEquals(90.0, map.bpmAt(1e6), 0.0)
    }

    // ------------------------------------------------------------------
    // step changes
    // ------------------------------------------------------------------

    @Test
    fun stepCurveHoldsBpmUntilTheNextEntry() {
        val map = TempoMap(
            listOf(
                TempoPoint(atBeat = 0.0, bpm = 120.0, curve = TempoCurve.STEP),
                TempoPoint(atBeat = 4.0, bpm = 60.0, curve = TempoCurve.STEP),
            )
        )
        // First 4 beats at 120 bpm (0.5 s/beat), then 60 bpm (1 s/beat).
        assertEquals(2.0, map.beatToSeconds(4.0), 1e-9)
        assertEquals(2.5, map.beatToSeconds(4.5), 1e-9)
        assertEquals(3.0, map.beatToSeconds(5.0), 1e-9)
        // bpmAt: the entry AT the boundary wins; the last entry holds forever.
        assertEquals(120.0, map.bpmAt(3.999), 0.0)
        assertEquals(60.0, map.bpmAt(4.0), 0.0)
        assertEquals(60.0, map.bpmAt(100.0), 0.0)
    }

    @Test
    fun beatToSecondsIntegratesStepSegmentsPiecewise() {
        val map = TempoMap(
            listOf(
                TempoPoint(atBeat = 0.0, bpm = 120.0),
                TempoPoint(atBeat = 2.0, bpm = 60.0),
            )
        )
        assertEquals(1.0, map.beatToSeconds(2.0), 1e-9)
        assertEquals(2.0, map.beatToSeconds(3.0), 1e-9)
    }

    // ------------------------------------------------------------------
    // linear ramps (hand-computed against the Python log-integral)
    // ------------------------------------------------------------------

    @Test
    fun linearRampMatchesThePythonClosedForm() {
        // 60 -> 120 bpm linearly over 4 beats: slope = 15 bpm/beat.
        // beat 2: bpm_a=60, bpm_b=90 -> (60/15)*ln(90/60) = 4*ln(1.5) = 1.62186...
        val map = TempoMap(
            listOf(
                TempoPoint(atBeat = 0.0, bpm = 60.0, curve = TempoCurve.LINEAR),
                TempoPoint(atBeat = 4.0, bpm = 120.0, curve = TempoCurve.LINEAR),
            )
        )
        assertEquals(4.0 * Math.log(1.5), map.beatToSeconds(2.0), 1e-9)
        assertEquals(4.0 * Math.log(2.0), map.beatToSeconds(4.0), 1e-9)
        // bpmAt interpolates linearly within the segment.
        assertEquals(60.0, map.bpmAt(0.0), 1e-9)
        assertEquals(90.0, map.bpmAt(2.0), 1e-9)
        assertEquals(120.0, map.bpmAt(4.0), 1e-9)
        // Past the last entry the final bpm holds (step-like extension).
        assertEquals(120.0, map.bpmAt(10.0), 0.0)
        // Beats 4-7 continue at 120 bpm: 3 beats * 0.5 s = 1.5 s.
        assertEquals(4.0 * Math.log(2.0) + 1.5, map.beatToSeconds(7.0), 1e-9)
    }

    @Test
    fun linearSegmentWithEqualEndpointsUsesTheConstantFormula() {
        // |bpm_b - bpm_a| < 1e-12 triggers the constant-bpm branch.
        val map = TempoMap(
            listOf(
                TempoPoint(atBeat = 0.0, bpm = 100.0, curve = TempoCurve.LINEAR),
                TempoPoint(atBeat = 4.0, bpm = 100.0, curve = TempoCurve.LINEAR),
            )
        )
        assertEquals(4.0 * 60.0 / 100.0, map.beatToSeconds(4.0), 1e-9)
        assertEquals(100.0, map.bpmAt(2.0), 1e-9)
    }

    // ------------------------------------------------------------------
    // multiple segments
    // ------------------------------------------------------------------

    @Test
    fun multipleSegmentsIntegratePiecewise() {
        val map = TempoMap(
            listOf(
                TempoPoint(atBeat = 0.0, bpm = 120.0, curve = TempoCurve.STEP),
                TempoPoint(atBeat = 2.0, bpm = 60.0, curve = TempoCurve.LINEAR),
                TempoPoint(atBeat = 6.0, bpm = 120.0, curve = TempoCurve.STEP),
            )
        )
        // 0-2 at 120 (1.0 s) + 2-6 linear 60->120 (4*ln 2) + 6-8 at 120 (1.0 s).
        val expectedAtSix = 1.0 + 4.0 * Math.log(2.0)
        assertEquals(expectedAtSix, map.beatToSeconds(6.0), 1e-9)
        assertEquals(expectedAtSix + 1.0, map.beatToSeconds(8.0), 1e-9)
        // bpmAt jumps at the step boundary and ramps across the linear one.
        assertEquals(120.0, map.bpmAt(1.999), 0.0)
        assertEquals(60.0, map.bpmAt(2.0), 1e-9)
        assertEquals(90.0, map.bpmAt(4.0), 1e-9)
        assertEquals(120.0, map.bpmAt(6.0), 1e-9)
        assertEquals(120.0, map.bpmAt(6.001), 0.0)
    }

    // ------------------------------------------------------------------
    // constructor guards
    // ------------------------------------------------------------------

    @Test
    fun constructorRejectsEmptyMap() {
        assertThrows(IllegalArgumentException::class.java) { TempoMap(emptyList()) }
    }

    @Test
    fun constructorRequiresFirstEntryAtBeatZero() {
        assertThrows(IllegalArgumentException::class.java) {
            TempoMap(listOf(TempoPoint(atBeat = 1.0, bpm = 120.0)))
        }
    }

    @Test
    fun constructorRejectsNonIncreasingAtBeat() {
        // Duplicate atBeat: even after sorting, entries are not strictly
        // increasing. (Unsorted-but-distinct input is legal — the constructor
        // sorts — see constructorSortsEntriesByAtBeat.)
        assertThrows(IllegalArgumentException::class.java) {
            TempoMap(
                listOf(
                    TempoPoint(atBeat = 0.0, bpm = 120.0),
                    TempoPoint(atBeat = 2.0, bpm = 100.0),
                    TempoPoint(atBeat = 2.0, bpm = 80.0), // duplicate atBeat
                )
            )
        }
    }

    @Test
    fun constructorRejectsNonPositiveBpm() {
        assertThrows(IllegalArgumentException::class.java) {
            TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = 0.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = -60.0)))
        }
    }

    @Test
    fun tempoPointRejectsNegativeAtBeat() {
        assertThrows(IllegalArgumentException::class.java) {
            TempoPoint(atBeat = -1.0, bpm = 120.0)
        }
    }

    // ------------------------------------------------------------------
    // determinism + convenience
    // ------------------------------------------------------------------

    @Test
    fun constructorSortsEntriesByAtBeat() {
        val map = TempoMap(
            listOf(
                TempoPoint(atBeat = 4.0, bpm = 60.0),
                TempoPoint(atBeat = 0.0, bpm = 120.0),
            )
        )
        assertEquals(120.0, map.points.first().bpm, 0.0)
        assertEquals(60.0, map.points.last().bpm, 0.0)
        assertEquals(3.0, map.beatToSeconds(5.0), 1e-9)
    }

    @Test
    fun identicalInputsProduceIdenticalResults() {
        val points = listOf(
            TempoPoint(atBeat = 0.0, bpm = 60.0, curve = TempoCurve.LINEAR),
            TempoPoint(atBeat = 4.0, bpm = 120.0, curve = TempoCurve.LINEAR),
        )
        val first = TempoMap(points)
        val second = TempoMap(points)
        for (beat in 0..40) {
            assertEquals(first.beatToSeconds(beat.toDouble()), second.beatToSeconds(beat.toDouble()), 0.0)
        }
        assertEquals(first.beatToSeconds(2.0), second.beatToSeconds(2.0), 0.0)
    }

    @Test
    fun beatToSecondsListConvenienceMapsEachBeat() {
        val map = constant(120.0)
        val seconds = map.beatToSeconds(listOf(0.0, 1.0, 2.0, 3.0, 4.0))
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5, 2.0), seconds)
        assertTrue(seconds.size == 5)
    }
}