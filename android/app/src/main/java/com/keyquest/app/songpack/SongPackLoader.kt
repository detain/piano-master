package com.keyquest.app.songpack

import com.keyquest.scoring.Hand
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses SongPack v1 JSON documents (docs/specs/songpack-v1.md) into the typed
 * [SongPack] model at the app's format boundary. Content is validated by the
 * pipeline at build time (spec §7); this loader is the app's defense-in-depth
 * and the only place raw JSON enters the lesson player. Per spec §8 unknown
 * keys are ignored, so forward-compatible packs parse unchanged.
 *
 * Fail-fast contract: every structural, type or enum error throws
 * [IllegalArgumentException] naming the file, the field and the offending
 * value — e.g. `manifest.json: missing field 'title'`,
 * `notes.json level 1 note 3: hand 'X' must be R or L`. Malformed JSON is
 * caught and rethrown as [IllegalArgumentException] with the file name, so
 * callers get one uniform exception type. Numeric-range and cross-field
 * invariants (spec §7 checks 1-5) are additionally enforced by the model's
 * `init` blocks, which throw [IllegalArgumentException] too.
 *
 * @param manifestJson the pack's `manifest.json` (§6): identity, tempo map,
 *   time/key signatures. `keySignatures` may be missing entirely (parsed as an
 *   empty list); all other fields are required.
 * @param notesJson the pack's `notes.json` (§3); only arrangement level "1"
 *   is read. Note order is preserved exactly as produced — canonical order
 *   (startBeat asc, R before L, pitch asc, §3.1) is the producer's contract,
 *   and `tieToIndex` refers to that order.
 * @param chunksJson the pack's `chunks.json` (§4), or null when the pack has
 *   no chunk index. A null yields an empty chunk list; synthesizing the single
 *   implicit chunk is the caller's job, not the loader's.
 */
object SongPackLoader {

    fun load(manifestJson: String, notesJson: String, chunksJson: String? = null): SongPack {
        val manifest = parseObject("manifest.json", manifestJson)
        requireFormat(manifest)
        val notesFile = parseObject("notes.json", notesJson)
        val chunksFile = chunksJson?.let { parseArray("chunks.json", it) }
        return SongPack(
            title = manifest.fieldValue("manifest.json", "title") { getString("title") },
            songId = manifest.fieldValue("manifest.json", "songId") { getString("songId") },
            defaultTempoBpm = manifest.fieldValue("manifest.json", "defaultTempoBpm") { getDouble("defaultTempoBpm") },
            pickupBeats = manifest.fieldValue("manifest.json", "pickupBeats") { getDouble("pickupBeats") },
            durationBeats = manifest.fieldValue("manifest.json", "durationBeats") { getDouble("durationBeats") },
            tempoMap = parseTempoMap(manifest),
            timeSignatures = parseTimeSignatures(manifest),
            keySignatures = parseKeySignatures(manifest),
            notes = parseLevelOneNotes(notesFile),
            chunks = parseChunks(chunksFile),
        )
    }

    private fun parseObject(fileName: String, json: String): JSONObject = try {
        JSONObject(json)
    } catch (e: JSONException) {
        throw IllegalArgumentException("$fileName: malformed JSON: ${e.message}", e)
    }

    private fun parseArray(fileName: String, json: String): JSONArray = try {
        JSONArray(json)
    } catch (e: JSONException) {
        throw IllegalArgumentException("$fileName: malformed JSON: ${e.message}", e)
    }

    /** Reads [key] or fails fast, naming [where] and the offending value. */
    private inline fun <T> JSONObject.fieldValue(where: String, key: String, get: JSONObject.() -> T): T {
        if (!has(key)) throw IllegalArgumentException("$where: missing field '$key'")
        return try {
            get()
        } catch (e: JSONException) {
            throw IllegalArgumentException("$where: ${e.message}", e)
        }
    }

    /** Spec §6: the manifest must declare `format: "songpack/v1"`; refuse anything else. */
    private fun requireFormat(manifest: JSONObject) {
        val format = manifest.fieldValue("manifest.json", "format") { getString("format") }
        if (format != "songpack/v1") {
            throw IllegalArgumentException("manifest.json: format '$format' must be 'songpack/v1'")
        }
    }

    /** Reads element [index] or fails fast, naming [where] and the offending value. */
    private inline fun <T> JSONArray.elementValue(where: String, index: Int, get: JSONArray.() -> T): T = try {
        get()
    } catch (e: JSONException) {
        throw IllegalArgumentException("$where: ${e.message}", e)
    }

    /** Optional int field: null when absent or explicitly null, else parsed. */
    private fun JSONObject.optionalInt(where: String, key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return fieldValue(where, key) { getInt(key) }
    }

    private fun parseTempoMap(manifest: JSONObject): List<SongTempoPoint> {
        val array = manifest.fieldValue("manifest.json", "tempoMap") { getJSONArray("tempoMap") }
        return List(array.length()) { i ->
            val where = "manifest.json tempoMap[$i]"
            val entry = array.elementValue(where, i) { getJSONObject(i) }
            SongTempoPoint(
                atBeat = entry.fieldValue(where, "atBeat") { getDouble("atBeat") },
                bpm = entry.fieldValue(where, "bpm") { getDouble("bpm") },
                curve = parseCurve(entry, where),
            )
        }
    }

    private fun parseCurve(entry: JSONObject, where: String): SongCurve {
        val curve = entry.fieldValue(where, "curve") { getString("curve") }
        return when (curve) {
            "step" -> SongCurve.STEP
            "linear" -> SongCurve.LINEAR
            else -> throw IllegalArgumentException("$where: curve '$curve' must be 'step' or 'linear'")
        }
    }

    private fun parseTimeSignatures(manifest: JSONObject): List<SongTimeSignature> {
        val array = manifest.fieldValue("manifest.json", "timeSignatures") { getJSONArray("timeSignatures") }
        return List(array.length()) { i ->
            val where = "manifest.json timeSignatures[$i]"
            val entry = array.elementValue(where, i) { getJSONObject(i) }
            SongTimeSignature(
                atBeat = entry.fieldValue(where, "atBeat") { getDouble("atBeat") },
                numerator = entry.fieldValue(where, "numerator") { getInt("numerator") },
                denominator = entry.fieldValue(where, "denominator") { getInt("denominator") },
            )
        }
    }

    private fun parseKeySignatures(manifest: JSONObject): List<SongKeySignature> {
        if (!manifest.has("keySignatures")) return emptyList()
        val array = manifest.fieldValue("manifest.json", "keySignatures") { getJSONArray("keySignatures") }
        return List(array.length()) { i ->
            val where = "manifest.json keySignatures[$i]"
            val entry = array.elementValue(where, i) { getJSONObject(i) }
            SongKeySignature(
                atBeat = entry.fieldValue(where, "atBeat") { getDouble("atBeat") },
                fifths = entry.fieldValue(where, "fifths") { getInt("fifths") },
            )
        }
    }

    private fun parseLevelOneNotes(notesFile: JSONObject): List<SongNote> {
        val levels = notesFile.fieldValue("notes.json", "levels") { getJSONObject("levels") }
        if (!levels.has("1")) throw IllegalArgumentException("notes.json has no level 1")
        val notes = levels.fieldValue("notes.json", "1") { getJSONArray("1") }
        return List(notes.length()) { i ->
            val where = "notes.json level 1 note ${i + 1}"
            val note = notes.elementValue(where, i) { getJSONObject(i) }
            SongNote(
                pitch = note.fieldValue(where, "pitch") { getInt("pitch") },
                startBeat = note.fieldValue(where, "startBeat") { getDouble("startBeat") },
                durBeats = note.fieldValue(where, "durBeats") { getDouble("durBeats") },
                hand = parseHand(note, where),
                staff = parseStaff(note, where),
                voice = note.fieldValue(where, "voice") { getInt("voice") },
                scoringWeight = note.optDouble("scoringWeight", 1.0),
                finger = note.optionalInt(where, "finger"),
                tieToIndex = note.optionalInt(where, "tieToIndex"),
            )
        }
    }

    private fun parseHand(note: JSONObject, where: String): Hand {
        val hand = note.fieldValue(where, "hand") { getString("hand") }
        return when (hand) {
            "R" -> Hand.R
            "L" -> Hand.L
            else -> throw IllegalArgumentException("$where: hand '$hand' must be R or L")
        }
    }

    private fun parseStaff(note: JSONObject, where: String): Int {
        val staff = note.fieldValue(where, "staff") { getInt("staff") }
        if (staff !in 1..2) throw IllegalArgumentException("$where: staff $staff must be 1 or 2")
        return staff
    }

    private fun parseChunks(chunksFile: JSONArray?): List<SongChunk> {
        if (chunksFile == null) return emptyList()
        return List(chunksFile.length()) { i ->
            val where = "chunks.json [$i]"
            val chunk = chunksFile.elementValue(where, i) { getJSONObject(i) }
            val chunkId = chunk.fieldValue(where, "chunkId") { getString("chunkId") }
            val chunkWhere = "$where (chunkId '$chunkId')"
            SongChunk(
                chunkId = chunkId,
                ord = chunk.fieldValue(chunkWhere, "ord") { getInt("ord") },
                startBeat = chunk.fieldValue(chunkWhere, "startBeat") { getDouble("startBeat") },
                endBeat = chunk.fieldValue(chunkWhere, "endBeat") { getDouble("endBeat") },
                label = chunk.fieldValue(chunkWhere, "label") { getString("label") },
                loopSafe = chunk.fieldValue(chunkWhere, "loopSafe") { getBoolean("loopSafe") },
                difficulty = chunk.fieldValue(chunkWhere, "difficulty") { getInt("difficulty") },
                prerequisiteChunks = parsePrerequisiteChunks(chunk, chunkWhere),
                countInBeats = chunk.fieldValue(chunkWhere, "countInBeats") { getInt("countInBeats") },
            )
        }
    }

    private fun parsePrerequisiteChunks(chunk: JSONObject, where: String): List<String> {
        val array = chunk.fieldValue(where, "prerequisiteChunks") { getJSONArray("prerequisiteChunks") }
        return List(array.length()) { i -> array.elementValue(where, i) { getString(i) } }
    }
}