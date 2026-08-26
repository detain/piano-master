package com.keyquest.app.songpack

import com.keyquest.app.notation.TempoPoint
import com.keyquest.app.notation.TimeSignature
import com.keyquest.scoring.Hand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProtoScoreAdapterTest — unit tests for [ProtoScoreAdapter], the seam from
 * the typed SongPack v1 model to the two consumers of a lesson chunk: the
 * renderer's [com.keyquest.app.notation.ProtoScore] (chunk-local, rebased
 * times) and the scoring engine's [com.keyquest.scoring.ExpectedNote] list
 * (absolute beats from song start). Expected values are computed by hand from
 * the adapter's slicing, rebasing and tie-remapping rules.
 *
 * JVM-only (unit test, no device).
 */
class ProtoScoreAdapterTest {

    @Test
    fun toProtoScoreFiltersAndRebases() {
        // Six notes at beats 0..5; the chunk window [1.0, 4.0) keeps the
        // notes at beats 1, 2, 3 and rebases them so beat 0 of the score is
        // the chunk's first beat.
        val notes = (0..5).map { i -> note(60 + i, i.toDouble()) }
        val score = ProtoScoreAdapter.toProtoScore(pack(notes), chunk(1.0, 4.0))

        assertEquals(3, score.notes.size)
        assertEquals(listOf(0.0, 1.0, 2.0), score.notes.map { it.startBeat })
        assertEquals(listOf(61, 62, 63), score.notes.map { it.pitch })
        assertEquals(listOf('R', 'R', 'R'), score.notes.map { it.hand })
        assertEquals(listOf(1, 1, 1), score.notes.map { it.staff })

        // Layout hints come from the deriver over the chunk notes: distinct
        // pitches 61, 62, 63 rank 0, 1, 2 -> lanes; x hints are rebased with
        // the notes; every note gets a beam group.
        assertEquals(listOf(0, 1, 2), score.notes.map { it.lane })
        assertEquals(listOf(0.0, 1000.0, 2000.0), score.notes.map { it.xHint })
        assertTrue(score.notes.all { it.beamGroup != null })

        // Manifest-level fields are passed through from the pack.
        assertEquals(120.0, score.defaultTempoBpm, 0.0)
        assertEquals(1, score.keySignature.fifths)
        assertEquals(TimeSignature(4, 4), score.timeSignature)
        assertEquals(5, score.lanesPerHand)
        // The only tempo point sits at beat 0, before the chunk start, so the
        // renderer gets the default fallback point.
        assertEquals(listOf(TempoPoint(0.0, 120.0)), score.tempoMap)
    }

    @Test
    fun toExpectedNotesKeepsAbsoluteBeats() {
        val notes = listOf(
            note(60, 0.0),
            note(61, 1.0, durBeats = 0.5, hand = Hand.L, staff = 2, scoringWeight = 0.2),
            note(62, 2.0),
            note(63, 3.0),
        )
        val expected = ProtoScoreAdapter.toExpectedNotes(pack(notes), chunk(1.0, 4.0))

        assertEquals(3, expected.size)
        // The scorer aligns events to the song's global tempo map, so its
        // expectations stay in absolute beats — no rebasing to the chunk.
        assertEquals(listOf(1.0, 2.0, 3.0), expected.map { it.startBeat })
        assertEquals(listOf(61, 62, 63), expected.map { it.pitch })
        assertEquals(listOf(0.5, 1.0, 1.0), expected.map { it.durBeats })
        assertEquals(listOf(Hand.L, Hand.R, Hand.R), expected.map { it.hand })
        assertEquals(listOf(0.2, 1.0, 1.0), expected.map { it.scoringWeight })
    }

    @Test
    fun tieToIndexRemappedWithinChunk() {
        // Pack indices 0..5 at beats 0..5; the chunk window [1.0, 4.0) keeps
        // pack indices 1, 2, 3 -> chunk-local 0, 1, 2.
        val notes = (0..5).map { i -> note(60 + i, i.toDouble()) }.toMutableList()
        notes[2] = notes[2].copy(tieToIndex = 3) // both ends inside the chunk
        notes[1] = notes[1].copy(tieToIndex = 0) // target outside, before the chunk
        val score = ProtoScoreAdapter.toProtoScore(pack(notes), chunk(1.0, 4.0))

        // Pack 3 lands at chunk-local 2, so the note at chunk-local 1 (pack
        // 2) points at 2 — a pack index would be out of bounds in the score.
        assertEquals(2, score.notes[1].tieToIndex)
        // Pack 0 is not part of this score; the cross-chunk tie is dropped.
        assertNull(score.notes[0].tieToIndex)
    }

    @Test
    fun tempoFallbackEmitsDefault() {
        // The only tempo point sits at beat 0, before the chunk starts at
        // 5.0: no point qualifies, so the renderer still gets a single
        // default point covering beat 0 of the chunk-local map.
        val score = ProtoScoreAdapter.toProtoScore(
            pack(emptyList(), tempoMap = listOf(SongTempoPoint(0.0, 120.0))),
            chunk(5.0, 6.0),
        )
        assertEquals(listOf(TempoPoint(0.0, 120.0)), score.tempoMap)
    }

    @Test
    fun chunkOutsidePackRejected() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ProtoScoreAdapter.toProtoScore(pack(emptyList(), durationBeats = 8.0), chunk(0.0, 9.0))
        }
        assertTrue(
            "offending duration must be named, was: ${e.message}",
            e.message!!.contains("durationBeats"),
        )
    }

    @Test
    fun emptyChunkNotes() {
        val score = ProtoScoreAdapter.toProtoScore(pack(emptyList()), chunk(0.0, 1.0))
        assertTrue(score.notes.isEmpty())
        assertTrue(ProtoScoreAdapter.toExpectedNotes(pack(emptyList()), chunk(0.0, 1.0)).isEmpty())
    }

    /** Builds a [SongPack] with a fixed identity and the given musical content. */
    private fun pack(
        notes: List<SongNote>,
        chunks: List<SongChunk> = defaultChunks,
        tempoMap: List<SongTempoPoint> = listOf(SongTempoPoint(0.0, 120.0)),
        timeSignatures: List<SongTimeSignature> = listOf(SongTimeSignature(0.0, 4, 4)),
        keySignatures: List<SongKeySignature> = listOf(SongKeySignature(0.0, 1)),
        pickupBeats: Double = 0.0,
        durationBeats: Double = 8.0,
    ): SongPack = SongPack(
        title = "T",
        songId = "t",
        defaultTempoBpm = 120.0,
        pickupBeats = pickupBeats,
        durationBeats = durationBeats,
        tempoMap = tempoMap,
        timeSignatures = timeSignatures,
        keySignatures = keySignatures,
        notes = notes,
        chunks = chunks,
    )

    /** Builds a [SongChunk] with a fixed identity covering [startBeat]..[endBeat]. */
    private fun chunk(startBeat: Double, endBeat: Double): SongChunk =
        SongChunk(chunkId = "c", ord = 1, startBeat = startBeat, endBeat = endBeat)

    /** Builds a [SongNote] with the defaults the adapter's tests rely on. */
    private fun note(
        pitch: Int,
        startBeat: Double,
        durBeats: Double = 1.0,
        hand: Hand = Hand.R,
        staff: Int = 1,
        scoringWeight: Double = 1.0,
    ): SongNote = SongNote(pitch, startBeat, durBeats, hand, staff, scoringWeight = scoringWeight)

    private companion object {
        /** A chunk covering the whole pack, valid against the default 8.0-beat duration. */
        val defaultChunks = listOf(SongChunk(chunkId = "c", ord = 1, startBeat = 0.0, endBeat = 8.0))
    }
}