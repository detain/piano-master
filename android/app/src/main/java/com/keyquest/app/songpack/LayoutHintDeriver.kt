package com.keyquest.app.songpack

import com.keyquest.scoring.Hand

/**
 * Derives layout hints ([NoteHints]) that pipeline v0 does not precompute.
 *
 * SongPack v1 fixtures carry no layout data: the pipeline's `layout` stage
 * precomputes lanes and beam groups only in later milestones, so until then
 * every pack needs the same deterministic derivation. The ProtoNote adapter
 * is the seam — when pipeline-computed hints arrive they will be read straight
 * off the pack and this deriver will stop being called. Everything here is
 * therefore pure and side-effect free: same input list, same output list.
 *
 * Derivation rules:
 *  - [NoteHints.lane] — per hand ([Hand.R] / [Hand.L] separately), the hand's
 *    DISTINCT pitches are ranked ascending (C4 = MIDI 60 first); the lane is
 *    `rank % 5`. Equal pitches always share a lane, and pitch ranges wider
 *    than 5 fold back onto the note-bar skin's 5 lanes per hand.
 *  - [NoteHints.xHint] — `startBeat * 1000.0`, a millisecond-scale horizontal
 *    position for the staff skin (the same scaling the prototype generator
 *    used, so derived and precomputed packs render identically).
 *  - [NoteHints.beamGroup] — computed separately per staff (1 upper, 2
 *    lower). Notes are walked in time order (input order breaks ties at equal
 *    startBeat, so chord voices stay adjacent); a run of consecutive notes all
 *    with `durBeats <= 0.5` shares one group id, and any longer note starts a
 *    fresh group. Every note gets an id — never null — because the staff skin
 *    draws beams per group and a single-note group simply draws none. Group
 *    ids increment per staff, 1-based.
 *
 * The result is aligned to the input list order.
 */
object LayoutHintDeriver {

    /** Lanes per hand in the note-bar skin (NotationSkin.NoteBar). */
    const val LANES_PER_HAND = 5

    /** Longest duration (beats) a note may have and still join a beam run. */
    private const val MAX_BEAMABLE_DURATION_BEATS = 0.5

    /**
     * Derived layout hints for one [SongNote].
     *
     * @property lane note-bar lane, 0..[LANES_PER_HAND]-1, within the note's
     *   hand (the skin flattens hand + lane into the global grid).
     * @property xHint horizontal position hint for the staff skin, in
     *   millibeats (`startBeat * 1000`).
     * @property beamGroup beam-group id within the note's staff, 1-based;
     *   `Int?` mirrors [com.keyquest.app.notation.ProtoNote.beamGroup], but
     *   this deriver always assigns an id (a single-note group draws no beam).
     */
    data class NoteHints(
        val lane: Int,
        val xHint: Double,
        val beamGroup: Int?,
    ) {
        init {
            require(lane in 0 until LANES_PER_HAND) {
                "lane must be in 0..${LANES_PER_HAND - 1}, was $lane"
            }
        }
    }

    /**
     * Derives one [NoteHints] per [SongNote], aligned to the input order.
     * Pure and deterministic: the same [notes] always yields the same hints.
     */
    fun derive(notes: List<SongNote>): List<NoteHints> {
        val laneByPitch = laneByPitchPerHand(notes)
        val beamGroupByIndex = beamGroupByNoteIndex(notes)
        return notes.mapIndexed { index, note ->
            NoteHints(
                lane = laneByPitch.getValue(note.hand).getValue(note.pitch),
                xHint = note.startBeat * 1000.0,
                beamGroup = beamGroupByIndex.getValue(index),
            )
        }
    }

    /** hand -> pitch -> lane: distinct pitches of the hand, ranked ascending. */
    private fun laneByPitchPerHand(notes: List<SongNote>): Map<Hand, Map<Int, Int>> =
        notes.groupBy { it.hand }.mapValues { (_, handNotes) ->
            handNotes.map { it.pitch }
                .distinct()
                .sorted()
                .mapIndexed { rank, pitch -> pitch to rank % LANES_PER_HAND }
                .toMap()
        }

    /**
     * note index -> beam-group id: per staff, in time order (stable by input
     * order within equal startBeat), a run of consecutive notes with
     * `durBeats <= MAX_BEAMABLE_DURATION_BEATS` shares one id; every other
     * note starts a fresh id. Ids are 1-based per staff.
     */
    private fun beamGroupByNoteIndex(notes: List<SongNote>): Map<Int, Int> {
        val groupByIndex = HashMap<Int, Int>(notes.size)
        for (staff in 1..2) {
            var nextGroupId = 1
            var lastNoteShort = false
            var currentGroupId = 0
            notes.withIndex()
                .filter { it.value.staff == staff }
                .sortedWith(compareBy { it.value.startBeat })
                .forEach { indexed ->
                    val isShort = indexed.value.durBeats <= MAX_BEAMABLE_DURATION_BEATS
                    if (!(isShort && lastNoteShort)) {
                        currentGroupId = nextGroupId++
                    }
                    groupByIndex[indexed.index] = currentGroupId
                    lastNoteShort = isShort
                }
        }
        return groupByIndex
    }
}