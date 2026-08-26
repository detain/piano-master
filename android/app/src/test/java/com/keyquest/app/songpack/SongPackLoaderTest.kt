package com.keyquest.app.songpack

import com.keyquest.scoring.Hand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SongPackLoaderTest — unit tests for [SongPackLoader], the app's fail-fast
 * boundary between raw SongPack v1 JSON and the typed [SongPack] model.
 *
 * Golden fixtures are COPIED into build/generated/songpack by the
 * copySongpackFixtures Gradle task and exposed as test resources; this test
 * reads them from the classpath (same pattern as SongPackSchemaTest), never a
 * committed copy.
 *
 * JVM-only (unit test, no device).
 */
class SongPackLoaderTest {

    @Test
    fun pickupAnacrusisLoadsFully() {
        val pack = loadFixture("pickup_anacrusis")

        // Manifest identity.
        assertEquals("The Morning Pickup", pack.title)
        assertEquals("kq-fixture-pickup", pack.songId)
        assertEquals(96.0, pack.defaultTempoBpm, 0.0)
        assertEquals(1.0, pack.pickupBeats, 0.0)
        assertEquals(33.0, pack.durationBeats, 0.0)

        // Signature maps, first entries at beat 0.
        assertEquals(listOf(SongTempoPoint(0.0, 96.0, SongCurve.STEP)), pack.tempoMap)
        assertEquals(listOf(SongTimeSignature(0.0, 4, 4)), pack.timeSignatures)

        // Notes: full level-1 set (46 in the fixture), JSON order preserved.
        assertEquals(46, pack.notes.size)
        assertEquals(67, pack.notes.first().pitch)
        assertEquals(0.0, pack.notes.first().startBeat, 0.0)
        assertEquals(Hand.R, pack.notes.first().hand)

        // Chunks: full index (4 in the fixture), array order preserved.
        assertEquals(4, pack.chunks.size)
        val first = pack.chunks.first()
        assertEquals("c01", first.chunkId)
        assertEquals(1, first.ord)
        assertEquals(0.0, first.startBeat, 0.0)
        assertEquals(9.0, first.endBeat, 0.0)
        assertTrue(first.loopSafe)
        assertEquals(1, first.countInBeats)
    }

    @Test
    fun tieAcrossChunksTiesLoad() {
        val pack = loadFixture("tie_across_chunks")

        val tied = pack.notes.filter { it.tieToIndex != null }
        assertTrue("fixture must contain tied notes", tied.isNotEmpty())
        for (note in tied) {
            val target = note.tieToIndex!!
            assertTrue(
                "tieToIndex $target must refer to an existing note (notes.size = ${pack.notes.size})",
                target in pack.notes.indices,
            )
        }

        // Pack-level index semantics: note 20 (0-based) ties into note 21, the
        // same pitch, across the chunk boundary at beat 16.
        assertEquals(20, pack.notes[19].tieToIndex)
        assertEquals(67, pack.notes[19].pitch)
        assertEquals(67, pack.notes[20].pitch)
    }

    @Test
    fun missingFieldFails() {
        // Minimal manifest with "title" removed.
        val manifest = """
            {
              "format": "songpack/v1",
              "songId": "test-song",
              "defaultTempoBpm": 96.0,
              "pickupBeats": 0.0,
              "durationBeats": 8.0,
              "tempoMap": [{"atBeat": 0, "bpm": 96, "curve": "step"}],
              "timeSignatures": [{"atBeat": 0, "numerator": 4, "denominator": 4}]
            }
        """.trimIndent()
        val e = assertThrows(IllegalArgumentException::class.java) {
            SongPackLoader.load(manifest, minimalNotes())
        }
        assertTrue(
            "missing field must be named, was: ${e.message}",
            e.message!!.contains("title"),
        )
    }

    @Test
    fun badHandFails() {
        val notes = """
            {
              "levels": {
                "1": [
                  {"pitch": 60, "startBeat": 0.0, "durBeats": 1.0, "hand": "X", "staff": 1, "voice": 1}
                ]
              }
            }
        """.trimIndent()
        val e = assertThrows(IllegalArgumentException::class.java) {
            SongPackLoader.load(minimalManifest(), notes)
        }
        assertTrue(
            "offending hand must be named, was: ${e.message}",
            e.message!!.contains("hand"),
        )
    }

    @Test
    fun badCurveFails() {
        val manifest = minimalManifest().replace("\"curve\": \"step\"", "\"curve\": \"glissando\"")
        val e = assertThrows(IllegalArgumentException::class.java) {
            SongPackLoader.load(manifest, minimalNotes())
        }
        assertTrue(
            "offending curve must be named, was: ${e.message}",
            e.message!!.contains("curve"),
        )
    }

    @Test
    fun badFormatFails() {
        val manifest = minimalManifest().replace("\"format\": \"songpack/v1\"", "\"format\": \"songpack/v2\"")
        val e = assertThrows(IllegalArgumentException::class.java) {
            SongPackLoader.load(manifest, minimalNotes())
        }
        assertTrue(
            "offending format must be named, was: ${e.message}",
            e.message!!.contains("format"),
        )
    }

    @Test
    fun malformedJsonFails() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SongPackLoader.load("not json", minimalNotes())
        }
        assertTrue(
            "malformed JSON must be rethrown uniformly, was: ${e.message}",
            e.message!!.contains("malformed JSON"),
        )
    }

    @Test
    fun missingLevelFails() {
        val notes = """{"levels": {"2": []}}"""
        val e = assertThrows(IllegalArgumentException::class.java) {
            SongPackLoader.load(minimalManifest(), notes)
        }
        assertTrue(
            "missing level must be named, was: ${e.message}",
            e.message!!.contains("level 1"),
        )
    }

    @Test
    fun nullChunksGivesEmpty() {
        val pack = loadFixture("pickup_anacrusis", withChunks = false)
        assertTrue("chunks must be empty when chunks.json is absent", pack.chunks.isEmpty())
    }

    @Test
    fun missingKeySignaturesOk() {
        // minimalManifest has no keySignatures key; the loader parses that as
        // an empty list rather than failing.
        val pack = SongPackLoader.load(minimalManifest(), minimalNotes())
        assertTrue(pack.keySignatures.isEmpty())
    }

    @Test
    fun optionalFieldsParsed() {
        val notes = """
            {
              "levels": {
                "1": [
                  {"pitch": 60, "startBeat": 0.0, "durBeats": 1.0, "hand": "R", "staff": 1, "voice": 1,
                   "scoringWeight": 0.2, "finger": 3, "tieToIndex": 1},
                  {"pitch": 60, "startBeat": 1.0, "durBeats": 1.0, "hand": "R", "staff": 1, "voice": 1}
                ]
              }
            }
        """.trimIndent()
        val pack = SongPackLoader.load(minimalManifest(), notes)

        val first = pack.notes[0]
        assertEquals(0.2, first.scoringWeight, 0.0)
        assertEquals(3, first.finger)
        assertEquals(1, first.tieToIndex)

        // Absent optional keys fall back to their model defaults.
        val second = pack.notes[1]
        assertEquals(1, second.voice)
        assertEquals(1.0, second.scoringWeight, 0.0)
        assertNull(second.finger)
        assertNull(second.tieToIndex)
    }

    /** Reads a golden fixture file from the test classpath (build/generated/songpack). */
    private fun resource(name: String): String {
        val classLoader = requireNotNull(javaClass.classLoader) {
            "app classloader unavailable"
        }
        val stream = requireNotNull(classLoader.getResourceAsStream(name)) {
            "missing $name on the test classpath — did the copySongpackFixtures Gradle task run?"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Loads a golden fixture pack, optionally with its chunks.json. */
    private fun loadFixture(fixtureId: String, withChunks: Boolean = true): SongPack {
        val chunks = if (withChunks) resource("songpack-v1/$fixtureId/chunks.json") else null
        return SongPackLoader.load(
            resource("songpack-v1/$fixtureId/manifest.json"),
            resource("songpack-v1/$fixtureId/notes.json"),
            chunks,
        )
    }

    /** A minimal valid manifest (all required fields, no keySignatures). */
    private fun minimalManifest(): String = """
        {
          "format": "songpack/v1",
          "title": "Test Song",
          "songId": "test-song",
          "defaultTempoBpm": 96.0,
          "pickupBeats": 0.0,
          "durationBeats": 8.0,
          "tempoMap": [{"atBeat": 0, "bpm": 96, "curve": "step"}],
          "timeSignatures": [{"atBeat": 0, "numerator": 4, "denominator": 4}]
        }
    """.trimIndent()

    /** A minimal valid notes.json with a single level-1 note. */
    private fun minimalNotes(): String = """
        {
          "levels": {
            "1": [
              {"pitch": 60, "startBeat": 0.0, "durBeats": 1.0, "hand": "R", "staff": 1, "voice": 1}
            ]
          }
        }
    """.trimIndent()
}