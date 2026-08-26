package com.keyquest.app.songpack

import com.keyquest.app.notation.KeySignature
import com.keyquest.app.notation.ProtoNote
import com.keyquest.app.notation.ProtoScore
import com.keyquest.app.notation.TempoPoint
import com.keyquest.app.notation.TimeSignature
import com.keyquest.scoring.ExpectedNote
import com.keyquest.scoring.Hand

/**
 * Adapter from the typed SongPack v1 model ([SongPack]) to the two consumers
 * of a lesson chunk:
 *
 *  - [toProtoScore] — the renderer's [ProtoScore]. The renderer draws
 *    chunk-local time, so [ProtoNote.startBeat] and the tempo map are rebased
 *    to `chunk.startBeat` (beat 0 of the score = the chunk's first beat). The
 *    scorer never sees this score.
 *  - [toExpectedNotes] — the scoring engine's expected-note list, in ABSOLUTE
 *    beats from song start. The scorer aligns incoming events to session time
 *    computed from the song's global tempo map, so its expectations must stay
 *    unrebased.
 *
 * Both functions slice the same note window — `startBeat` in
 * `[chunk.startBeat, chunk.endBeat)`, in pack order (canonical order,
 * songpack-v1 §3.1: startBeat asc, R before L, pitch asc). Notes are never
 * re-sorted; the renderer and the scorer both rely on the pack's canonical
 * order.
 *
 * Chunk-edge note: scoring windows extend backward from a note's start by the
 * engine's early window (`earlyMs`), so a note just after `chunk.endBeat` can
 * still accept an event that belongs to the previous chunk's practice window.
 * v1 deliberately accepts this chunk-edge effect — expectations are strictly
 * the notes inside `[chunk.startBeat, chunk.endBeat)` — because a practice
 * session renders and scores one chunk at a time and the overlap is bounded
 * by the early window.
 *
 * This adapter is the seam where pipeline-precomputed layout hints will
 * arrive: today [LayoutHintDeriver] derives [ProtoNote.lane]/[xHint]/
 * [beamGroup] deterministically at runtime; when the pipeline's `layout`
 * stage ships them in the pack they will be read straight off [SongNote] and
 * this derivation disappears. Hints are derived on the chunk note list exactly
 * as filtered (pack order, absolute `startBeat`): hints are computed on
 * absolute beats, so [ProtoNote.xHint] is rebased with the note's `startBeat`
 * (`hints.xHint - chunk.startBeat * 1000.0`, matching the chunk-rebased
 * `startBeat`); [ProtoNote.lane] and [ProtoNote.beamGroup] depend only on
 * pitch/staff/duration/order and are unaffected by rebasing.
 *
 * Ties ([SongNote.tieToIndex]) point into the pack's full note array. A tie
 * whose target lies inside the chunk is remapped to the chunk-local index; a
 * tie crossing a chunk boundary is dropped (null) because its target is not
 * part of this score. The renderer indexes `tieToIndex` into its own note
 * list (NoteLayout), so passing a pack index through would be out of bounds.
 */
object ProtoScoreAdapter {

    /**
     * Builds the renderer's score for one chunk. Note and tempo times are
     * rebased to the chunk start; layout hints are derived from the chunk
     * notes via [LayoutHintDeriver].
     *
     * Known gap: the key/time signature pass-through uses the pack's FIRST
     * entries — a chunk inside a mid-song key change renders the opening
     * signature. Display-only until the renderer integrates mid-song changes.
     *
     * @throws IllegalArgumentException if [chunk] is not within [pack]
     *   (`startBeat >= 0` and `endBeat <= durationBeats`).
     */
    fun toProtoScore(pack: SongPack, chunk: SongChunk): ProtoScore {
        requireChunkWithinPack(chunk, pack)
        val (chunkNotes, chunkIndexByPackIndex) = notesInChunk(pack, chunk)
        val hints = LayoutHintDeriver.derive(chunkNotes)
        return ProtoScore(
            notes = chunkNotes.mapIndexed { index, note ->
                ProtoNote(
                    pitch = note.pitch,
                    startBeat = note.startBeat - chunk.startBeat,
                    durBeats = note.durBeats,
                    hand = note.hand.toProtoChar(),
                    staff = note.staff,
                    lane = hints[index].lane,
                    xHint = hints[index].xHint - chunk.startBeat * 1000.0,
                    beamGroup = hints[index].beamGroup,
                    tieToIndex = note.tieToIndex?.let { chunkIndexByPackIndex[it] },
                )
            },
            defaultTempoBpm = pack.defaultTempoBpm,
            tempoMap = chunkTempoMap(pack, chunk),
            keySignature = KeySignature(fifths = pack.keySignatures.firstOrNull()?.fifths ?: 0),
            timeSignature = pack.timeSignatures.firstOrNull()?.let { sig ->
                TimeSignature(numerator = sig.numerator, denominator = sig.denominator)
            } ?: TimeSignature(numerator = 4, denominator = 4),
            lanesPerHand = LayoutHintDeriver.LANES_PER_HAND,
        )
    }

    /**
     * Builds the scoring engine's expected notes for one chunk, in ABSOLUTE
     * beats from song start (no rebasing — the scorer aligns events to
     * session time derived from the song's global tempo map).
     *
     * @throws IllegalArgumentException if [chunk] is not within [pack]
     *   (`startBeat >= 0` and `endBeat <= durationBeats`).
     */
    fun toExpectedNotes(pack: SongPack, chunk: SongChunk): List<ExpectedNote> {
        requireChunkWithinPack(chunk, pack)
        return notesInChunk(pack, chunk).first.map { note ->
            ExpectedNote(
                pitch = note.pitch,
                startBeat = note.startBeat,
                durBeats = note.durBeats,
                hand = note.hand,
                scoringWeight = note.scoringWeight,
            )
        }
    }

    /**
     * Fail-fast boundary check: the chunk must live inside the pack's note
     * timeline. A chunk is a slice of one pack, never an overlay.
     */
    private fun requireChunkWithinPack(chunk: SongChunk, pack: SongPack) {
        require(chunk.startBeat >= 0.0) {
            "chunk '${chunk.chunkId}' startBeat must be >= 0, was ${chunk.startBeat}"
        }
        require(chunk.endBeat <= pack.durationBeats + 1e-9) {
            "chunk '${chunk.chunkId}' endBeat ${chunk.endBeat} exceeds pack " +
                "durationBeats ${pack.durationBeats}"
        }
    }

    /**
     * The chunk's notes in pack (canonical) order, plus the pack-index ->
     * chunk-index map needed to remap ties into the chunk-local score. One
     * pass keeps the slice and the map consistent by construction.
     */
    private fun notesInChunk(
        pack: SongPack,
        chunk: SongChunk,
    ): Pair<List<SongNote>, Map<Int, Int>> {
        val chunkNotes = ArrayList<SongNote>()
        val chunkIndexByPackIndex = HashMap<Int, Int>()
        pack.notes.forEachIndexed { packIndex, note ->
            if (note.startBeat >= chunk.startBeat && note.startBeat < chunk.endBeat) {
                chunkIndexByPackIndex[packIndex] = chunkNotes.size
                chunkNotes.add(note)
            }
        }
        return chunkNotes to chunkIndexByPackIndex
    }

    /**
     * The chunk's tempo curve: every pack tempo point at or after the chunk
     * start, rebased to the chunk. Points beyond the chunk end are kept — a
     * LINEAR segment spanning the boundary must keep its destination. When no
     * point qualifies (the last tempo change predates the chunk), the renderer
     * still needs a map covering beat 0, so a single default point is emitted.
     *
     * Known gap: when a tempo point exists BEFORE the chunk, the opening
     * segment of the chunk renders at the first in-chunk point's bpm, not the
     * bpm active at chunk start. All golden fixtures are single-point maps;
     * the fix arrives with the P1.8.4 tempo control.
     */
    private fun chunkTempoMap(pack: SongPack, chunk: SongChunk): List<TempoPoint> {
        val chunkPoints = pack.tempoMap
            .filter { it.atBeat >= chunk.startBeat }
            .map { TempoPoint(atBeat = it.atBeat - chunk.startBeat, bpm = it.bpm) }
        return chunkPoints.ifEmpty {
            listOf(TempoPoint(atBeat = 0.0, bpm = pack.defaultTempoBpm))
        }
    }

    /**
     * [Hand] -> renderer `'R'`/`'L'` char. Exhaustive `when`: adding a hand
     * to the enum breaks compilation here instead of silently mis-mapping.
     */
    private fun Hand.toProtoChar(): Char = when (this) {
        Hand.R -> 'R'
        Hand.L -> 'L'
    }
}