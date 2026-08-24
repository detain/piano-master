package com.keyquest.app.songpack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SongPackSchemaTest — the Kotlin consumer of the canonical SongPack v1 schema
 * (plan §8.1.10: one schema, three consumers — Python pipeline, PHP API,
 * Kotlin tests; drift is impossible by construction).
 *
 * The canonical schema + golden fixtures are COPIED into
 * build/generated/songpack by the copySongpackSchema / copySongpackFixtures
 * Gradle tasks and exposed as test resources; this test validates the
 * canonical files from content/, never a committed copy. The generated dir is
 * gitignored, and the CI lint-all drift guard fails if a stray committed
 * songpack-v1.json appears anywhere else.
 *
 * JVM-only (unit test, no device).
 */
class SongPackSchemaTest {

    private val mapper = ObjectMapper()

    private val schema: JsonSchema by lazy {
        val classLoader = requireNotNull(javaClass.classLoader) {
            "app classloader unavailable"
        }
        val bytes = requireNotNull(classLoader.getResourceAsStream("songpack-v1.json")) {
            "canonical schema not on the test classpath — did the copySongpackSchema Gradle task run?"
        }.use { it.readBytes() }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(mapper.readTree(bytes))
    }

    @Test
    fun goldenFixturesValidateAgainstCanonicalSchema() {
        val fixtures = fixtureDirs()
        assertTrue(
            "no golden fixtures copied — check content/fixtures/songpack-v1",
            fixtures.isNotEmpty(),
        )
        for (dir in fixtures) {
            for (file in PACK_FILES) {
                val violations = schema.validate(fixtureDoc(dir.name, file))
                assertEquals(
                    "${dir.name}/$file must validate against the canonical schema",
                    emptySet<Any>(),
                    violations,
                )
            }
        }
    }

    @Test
    fun secondsKeyInNoteIsRejected() {
        // plan §8.1.1: beats are the unit of musical time; seconds appear
        // nowhere in note data. The schema forbids the key even when the value
        // would otherwise be a valid number.
        val notes = fixtureDoc("pickup_anacrusis", "notes.json")
        (notes.at("/levels/1/0") as ObjectNode).put("seconds", 1.0)
        val violations = schema.validate(notes)
        assertTrue("a note containing a seconds key must be rejected (plan §8.1.1)", violations.isNotEmpty())
    }

    private fun fixtureDirs(): List<File> {
        val classLoader = requireNotNull(javaClass.classLoader) {
            "app classloader unavailable"
        }
        val root = requireNotNull(classLoader.getResource("songpack-v1")) {
            "golden fixtures not on the test classpath — did the copySongpackFixtures Gradle task run?"
        }
        return File(root.toURI()).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?: error("cannot list generated fixture resources")
    }

    private fun fixtureDoc(fixtureId: String, fileName: String): JsonNode {
        val classLoader = requireNotNull(javaClass.classLoader) {
            "app classloader unavailable"
        }
        val stream = requireNotNull(
            classLoader.getResourceAsStream("songpack-v1/$fixtureId/$fileName"),
        ) { "missing $fixtureId/$fileName on the test classpath" }
        return stream.use { mapper.readTree(it) }
    }

    private companion object {
        val PACK_FILES = listOf("manifest.json", "notes.json", "chunks.json", "skills.json")
    }
}