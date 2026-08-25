package com.keyquest.scoring

/**
 * A cluster of expected notes played as one chord (plan §6: "all chord tones
 * must arrive within a 90 ms cluster; partial chords score partial credit").
 *
 * The cluster's [notes] preserve the SongPack canonical ordering (startBeat
 * asc, then R before L, then pitch asc — songpack-v1.md §3.1) — the
 * ChordClusterer sorts by that key, so relative order inside a cluster is
 * deterministic.
 */
data class ChordCluster(
    val notes: List<ExpectedNote>,
) {
    init {
        require(notes.isNotEmpty()) { "a chord cluster must contain at least one note" }
    }
}