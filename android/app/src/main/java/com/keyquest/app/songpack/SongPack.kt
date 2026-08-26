package com.keyquest.app.songpack

import com.keyquest.scoring.Hand

/**
 * Tempo curve of a [SongTempoPoint] — SongPack v1 `tempoMap[].curve`
 * (`step` | `linear`, docs/specs/songpack-v1.md §6.1).
 *
 * `STEP` = instant change at `atBeat`; `LINEAR` = rit./accel. ramping from
 * this point's `bpm` to the next point's `bpm`. `STEP` is the default because
 * it is what most packs use (a tempo that never changes is a single STEP
 * point at beat 0).
 */
enum class SongCurve { STEP, LINEAR }

/**
 * One entry of the tempo map — SongPack v1 `tempoMap[]` (docs/specs/songpack-v1.md
 * §6.1). The map is ordered by [atBeat]; the first entry MUST sit at beat 0
 * (validator-enforced, §7 check 2), which [SongPack] enforces.
 *
 * @property atBeat beat at which this tempo change takes effect (>= 0).
 * @property bpm beats per minute at this point (> 0).
 * @property curve how the tempo behaves from here: [SongCurve.STEP] instant,
 *   [SongCurve.LINEAR] ramping to the next entry's `bpm`.
 */
data class SongTempoPoint(
    val atBeat: Double,
    val bpm: Double,
    val curve: SongCurve = SongCurve.STEP,
) {
    init {
        require(atBeat >= 0.0) { "atBeat must be >= 0, was $atBeat" }
        require(bpm > 0.0) { "bpm must be > 0, was $bpm" }
    }
}

/**
 * One entry of the time signature map — SongPack v1 `timeSignatures[]`
 * (docs/specs/songpack-v1.md §6.2). The first entry defines the opening
 * measure; mid-song changes are normal, not exotic.
 *
 * @property atBeat beat at which this time signature takes effect (>= 0).
 * @property numerator beats per measure (> 0; the spec constrains content to
 *   1..32).
 * @property denominator beat unit as a power of two (> 0; the spec constrains
 *   content to `[1, 2, 4, 8, 16, 32]`). A measure is `numerator * 4 /
 *   denominator` beats, so in 6/8 a measure is 3 beats and the beat unit is
 *   time-signature-independent (songpack-v1.md §2).
 */
data class SongTimeSignature(
    val atBeat: Double,
    val numerator: Int,
    val denominator: Int,
) {
    init {
        require(atBeat >= 0.0) { "atBeat must be >= 0, was $atBeat" }
        require(numerator in 1..32) { "numerator must be in 1..32, was $numerator" }
        require(denominator in setOf(1, 2, 4, 8, 16, 32)) {
            "denominator must be in 1, 2, 4, 8, 16, 32, was $denominator"
        }
    }
}

/**
 * One entry of the key signature map — SongPack v1 `keySignatures[]`
 * (docs/specs/songpack-v1.md §6.3). The first entry defines the opening key.
 *
 * @property atBeat beat at which this key signature takes effect (>= 0).
 * @property fifths circle of fifths: 0 = C major / A minor, +1 = G major /
 *   E minor, -1 = F major / D minor, etc. The spec range -7..7 is enforced
 *   in `init`; the app never derives pitch from key signature (pitch is
 *   absolute per note, §3.2).
 */
data class SongKeySignature(
    val atBeat: Double,
    val fifths: Int,
) {
    init {
        require(atBeat >= 0.0) { "atBeat must be >= 0, was $atBeat" }
        require(fifths in -7..7) { "fifths must be in -7..7, was $fifths" }
    }
}

/**
 * One note record — SongPack v1 `notes.json` level array entry
 * (docs/specs/songpack-v1.md §3.2). Maps 1:1 onto the scoring engine's
 * [com.keyquest.scoring.ExpectedNote] fields, plus the renderer's visual
 * fields. All timing is in beats; seconds appear nowhere in note data (§2).
 *
 * @property pitch MIDI pitch, 0..127 (SongPack content is 21..108, A0..C8).
 * @property startBeat absolute beat from song start, pickup-adjusted — beat 0
 *   is the first pickup note (>= 0).
 * @property durBeats duration in beats (> 0; grace notes still have a
 *   duration — their weight is 0, not their length).
 * @property hand which hand should play this note — [Hand] reused from
 *   :scoring; the scorer matches on `hand`, never `staff`.
 * @property staff visual staff, 1 upper / 2 lower. NOT always the hand —
 *   cross-staff passages are common; the renderer uses `staff`, the scorer
 *   uses [hand].
 * @property voice voice index within the staff (spec: >= 1).
 * @property scoringWeight contribution to the score denominator, >= 0;
 *   ornament expansions default 0.2 and grace notes 0 (spec: 0..1).
 * @property finger fingering 1..5, present only where the lesson teaches
 *   fingering (spec); null otherwise.
 * @property tieToIndex index, within this level's notes array, of the note
 *   this note ties INTO — an index reference, never a beat reference, and it
 *   must point forward to a later note of the same pitch (spec, validator
 *   check 3). Ties may cross chunk boundaries. null when not tied.
 */
data class SongNote(
    val pitch: Int,
    val startBeat: Double,
    val durBeats: Double,
    val hand: Hand,
    val staff: Int,
    val voice: Int = 1,
    val scoringWeight: Double = 1.0,
    val finger: Int? = null,
    val tieToIndex: Int? = null,
) {
    init {
        require(pitch in 0..127) { "pitch must be in 0..127, was $pitch" }
        require(startBeat >= 0.0) { "startBeat must be >= 0, was $startBeat" }
        require(durBeats > 0.0) { "durBeats must be > 0, was $durBeats" }
        require(staff in 1..2) { "staff must be in 1..2, was $staff" }
        require(voice >= 1) { "voice must be >= 1, was $voice" }
        require(scoringWeight >= 0.0) { "scoringWeight must be >= 0, was $scoringWeight" }
        require(finger == null || finger in 1..5) { "finger must be in 1..5 when set, was $finger" }
    }
}

/**
 * One chunk — SongPack v1 `chunks.json` array entry (docs/specs/songpack-v1.md
 * §4). Chunks are chosen at phrase boundaries, 2-8 bars each; `ord` is the
 * 1-based teaching order (the array index is not `ord`).
 *
 * @property chunkId stable identifier, unique within the pack (non-blank).
 * @property ord 1-based teaching order (spec).
 * @property startBeat chunk start in beats (>= 0; within `durationBeats`).
 * @property endBeat chunk end in beats (> [startBeat]; <= `durationBeats`).
 * @property label shown to the user, e.g. "Chorus, first half" (default ""
 *   when a pack omits it).
 * @property loopSafe can this chunk loop musically without a lead-in?
 *   Auto-loop suggestions skip unsafe chunks.
 * @property difficulty chunk difficulty (spec: 1..10).
 * @property prerequisiteChunks `chunkId`s that must be completed first; may
 *   be empty; each must exist in the pack (spec, validator check 4).
 * @property countInBeats click count-in length in beats (spec: >= 0).
 */
data class SongChunk(
    val chunkId: String,
    val ord: Int,
    val startBeat: Double,
    val endBeat: Double,
    val label: String = "",
    val loopSafe: Boolean = false,
    val difficulty: Int = 1,
    val prerequisiteChunks: List<String> = emptyList(),
    val countInBeats: Int = 1,
) {
    init {
        require(chunkId.isNotBlank()) { "chunkId must not be blank, was \"$chunkId\"" }
        require(ord >= 1) { "ord must be >= 1, was $ord" }
        require(startBeat >= 0.0) { "startBeat must be >= 0, was $startBeat" }
        require(endBeat > startBeat) {
            "endBeat must be > startBeat, was endBeat=$endBeat startBeat=$startBeat"
        }
        require(difficulty in 1..10) { "difficulty must be in 1..10, was $difficulty" }
        require(countInBeats >= 0) { "countInBeats must be >= 0, was $countInBeats" }
    }
}

/**
 * An in-memory SongPack v1 (docs/specs/songpack-v1.md) — the app-native
 * format for playable piano content, compiled from MusicXML ahead of time by
 * the pipeline (the app never parses MusicXML at runtime).
 *
 * A pack is a zip archive holding `manifest.json` (identity, tempo map,
 * signatures), `notes.json` (full note sets per arrangement level),
 * `chunks.json` (pedagogy), `skills.json`, audio stems and checksums. This
 * model covers the scoring/lesson-player slice: tempo + time/key maps from
 * the manifest, one level's notes, and the chunk index. Beats are the unit of
 * musical time everywhere; seconds appear nowhere in note data (§2), and
 * `durationBeats` counts from beat 0 (the first pickup note).
 *
 * @property title display title (spec: non-empty).
 * @property songId stable identifier of the song, not the pack (spec:
 *   non-empty). Progress keys are on songId + level + chunk, never on pack
 *   version (§8).
 * @property defaultTempoBpm tempo at the first [tempoMap] entry (> 0).
 * @property pickupBeats anacrusis length (>= 0); getting this wrong offsets
 *   the whole song by a beat — the most common single content bug (§6).
 * @property durationBeats total song length in beats, including pickup
 *   (> 0).
 * @property tempoMap ordered tempo map, non-empty, first entry at beat 0
 *   (spec §6.1 + §7 check 2).
 * @property timeSignatures ordered time signature map, non-empty; first entry
 *   defines the opening measure (spec §6.2).
 * @property keySignatures ordered key signature map; first entry defines the
 *   opening key (spec §6.3).
 * @property notes one arrangement level's full, playable note set, in
 *   canonical order: `startBeat` asc, then R before L, then `pitch` asc —
 *   `tieToIndex` refers to this order (spec §3.1).
 * @property chunks chunk definitions in teaching order (spec §4).
 */
data class SongPack(
    val title: String,
    val songId: String,
    val defaultTempoBpm: Double,
    val pickupBeats: Double,
    val durationBeats: Double,
    val tempoMap: List<SongTempoPoint>,
    val timeSignatures: List<SongTimeSignature>,
    val keySignatures: List<SongKeySignature>,
    val notes: List<SongNote>,
    val chunks: List<SongChunk>,
) {
    init {
        require(defaultTempoBpm > 0.0) { "defaultTempoBpm must be > 0, was $defaultTempoBpm" }
        require(pickupBeats >= 0.0) { "pickupBeats must be >= 0, was $pickupBeats" }
        require(durationBeats > 0.0) { "durationBeats must be > 0, was $durationBeats" }
        require(tempoMap.isNotEmpty()) { "tempoMap must not be empty" }
        require(tempoMap.first().atBeat == 0.0) {
            "tempoMap[0].atBeat must be 0.0, was ${tempoMap.first().atBeat}"
        }
        require(tempoMap.zipWithNext().all { (a, b) -> a.atBeat < b.atBeat }) {
            "tempoMap atBeat values must be strictly increasing"
        }
        require(timeSignatures.isNotEmpty()) { "timeSignatures must not be empty" }
    }
}