package com.keyquest.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChordClusterer tests (plan §6, §20 P1.5.2): 90 ms cluster grouping with
 * seconds-converted gaps, canonical ordering, and the documented rule that
 * the cluster tolerance is absolute milliseconds — beginner config never
 * affects clustering.
 */
class ChordClustererTest {

    private val tempo120 = TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = 120.0)))

    private fun clusterer(config: ScoreConfig = ScoreConfig()): ChordClusterer =
        ChordClusterer(config, tempo120)

    private fun note(pitch: Int, startBeat: Double, hand: Hand = Hand.R): ExpectedNote =
        ExpectedNote(pitch = pitch, startBeat = startBeat, durBeats = 1.0, hand = hand)

    // At 120 bpm one beat is 0.5 s: 80 ms = 0.16 beats, 120 ms = 0.24 beats.

    @Test
    fun twoNotesEightyMsApartCluster() {
        val clusters = clusterer().cluster(listOf(note(60, 0.0), note(64, 0.16)))
        assertEquals(1, clusters.size)
        assertEquals(2, clusters[0].notes.size)
    }

    @Test
    fun twoNotesOneHundredTwentyMsApartDoNotCluster() {
        val clusters = clusterer().cluster(listOf(note(60, 0.0), note(64, 0.24)))
        assertEquals(2, clusters.size)
        assertEquals(1, clusters[0].notes.size)
        assertEquals(1, clusters[1].notes.size)
    }

    @Test
    fun rolledChordOfThreeTonesWithinNinetyMsClusters() {
        // Tones spread 0, 50, 80 ms from the first — all within 90 ms.
        val clusters = clusterer().cluster(listOf(note(60, 0.0), note(64, 0.10), note(67, 0.16)))
        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].notes.size)
    }

    @Test
    fun tonesBeyondTheToleranceStartANewCluster() {
        val clusters = clusterer().cluster(listOf(note(60, 0.0), note(64, 0.10), note(67, 0.50)))
        assertEquals(2, clusters.size)
        assertEquals(2, clusters[0].notes.size)
        assertEquals(1, clusters[1].notes.size)
    }

    @Test
    fun canonicalOrderingIsPreservedInsideClusters() {
        // Input deliberately unsorted: pitch 67 first, L-handed note second.
        // Canonical order at equal startBeat: R before L, then pitch asc,
        // so the L note comes AFTER both R notes.
        val input = listOf(note(67, 0.0), note(64, 0.0, Hand.L), note(60, 0.0))
        val clusters = clusterer().cluster(input)
        assertEquals(1, clusters.size)
        val pitches = clusters[0].notes.map { it.pitch }
        assertEquals(listOf(60, 67, 64), pitches)
        val hands = clusters[0].notes.map { it.hand }
        assertEquals(listOf(Hand.R, Hand.R, Hand.L), hands)
    }

    @Test
    fun beginnerConfigDoesNotAffectClustering() {
        val notes = listOf(note(60, 0.0), note(64, 0.16)) // 80 ms apart
        val regular = clusterer().cluster(notes)
        val beginner = clusterer(ScoreConfig(beginner = true)).cluster(notes)
        assertEquals(regular, beginner)
    }

    @Test
    fun clusteringDependsOnSecondsNotBeats() {
        // 0.16 beats = 80 ms at 120 bpm (clusters) but 160 ms at 60 bpm (does not).
        val slow = ChordClusterer(ScoreConfig(), TempoMap(listOf(TempoPoint(atBeat = 0.0, bpm = 60.0))))
        val notes = listOf(note(60, 0.0), note(64, 0.16))
        assertEquals(1, clusterer().cluster(notes).size)
        assertEquals(2, slow.cluster(notes).size)
    }

    @Test
    fun emptyInputYieldsNoClusters() {
        assertTrue(clusterer().cluster(emptyList()).isEmpty())
    }

    @Test
    fun singleNoteIsItsOwnCluster() {
        val clusters = clusterer().cluster(listOf(note(60, 0.0)))
        assertEquals(1, clusters.size)
        assertEquals(1, clusters[0].notes.size)
    }
}