package com.keyquest.app.notation

/**
 * Display-only accidental for [ProtoNote]. Mirrors the songpack/v1 `accidental`
 * field: it exists so the staff skin can show a glyph, and it must never be
 * used to derive pitch — [ProtoNote.pitch] is already absolute (plan §8.1.4).
 */
enum class Accidental { SHARP, FLAT, NATURAL }

/**
 * A single note in the scrolling-notation prototype.
 *
 * THIS IS A THROWAWAY PROTOTYPE STAND-IN for the real SongPack note record
 * (plan §8.1.4, frozen in P1.1). It approximates `notes.json` field-for-field
 * so the renderer consumes the same shape it will consume in Phase 1; the
 * prototype will be replaced by parsed `songpack/v1` JSON and the layout hints
 * ([lane], [xHint], [beamGroup]) will arrive pre-computed from the pipeline
 * instead of being derived here.
 *
 * @param pitch MIDI pitch, 21..108 — the app's only pitch representation.
 * @param startBeat absolute beats from song start (pickup-adjusted in real packs).
 * @param durBeats note duration in beats. Seconds never appear in note data
 *   (plan §8.1.1: "beats are the unit of musical time").
 * @param hand `'L'` or `'R'` — drives hand-isolation and the note-bar skin.
 * @param staff 1 = treble, 2 = bass. Deliberately NOT always equal to [hand]:
 *   cross-staff passages are real piano writing (plan §8.1.4).
 * @param lane note-bar skin lane hint, 0 until lanes-per-hand (precomputed in
 *   real packs; deterministic generator value here).
 * @param xHint precomputed horizontal spacing hint for the staff skin
 *   (precomputed in real packs; deterministic generator value here).
 * @param beamGroup dense-beaming stress: notes sharing a group id are beamed
 *   together by the staff skin. Mirrors songpack `beamGroup` (precomputed).
 * @param accidental display-only accidental glyph hint, or null.
 * @param tieToIndex index of the note this one ties to, or null. Mirrors
 *   songpack `tieToIndex` (index into the score's note array).
 */
data class ProtoNote(
    val pitch: Int,
    val startBeat: Double,
    val durBeats: Double,
    val hand: Char,
    val staff: Int,
    val lane: Int,
    val xHint: Double,
    val beamGroup: Int? = null,
    val accidental: Accidental? = null,
    val tieToIndex: Int? = null,
) {
    init {
        require(pitch in LayoutMath.NOTE_RANGE_MIN..LayoutMath.NOTE_RANGE_MAX) {
            "ProtoNote pitch $pitch outside MIDI ${LayoutMath.NOTE_RANGE_MIN}..${LayoutMath.NOTE_RANGE_MAX}"
        }
        require(hand == 'L' || hand == 'R') { "ProtoNote hand must be 'L' or 'R', was '$hand'" }
        require(staff == 1 || staff == 2) { "ProtoNote staff must be 1 (treble) or 2 (bass), was $staff" }
        require(startBeat >= 0.0) { "ProtoNote startBeat must be >= 0, was $startBeat" }
        require(durBeats > 0.0) { "ProtoNote durBeats must be > 0, was $durBeats" }
    }

    /** Readable identity for debug logs ("R3/G4@4.0"). */
    override fun toString(): String =
        "${hand}${staff}/${LayoutMath.noteLetter(pitch)}@${startBeat}"
}