package com.keyquest.scoring

/**
 * Groups expected notes into chord clusters (plan §6, §20 P1.5.2).
 *
 * Rule (documented in docs/specs/scoring-v1.md): sort expected notes by the
 * SongPack canonical order (startBeat asc, R before L, pitch asc —
 * songpack-v1.md §3.1); walk them in order; a note joins the CURRENT cluster
 * while its onset, converted to SECONDS, is within `chordClusterMs` of the
 * cluster's FIRST note; otherwise it starts a new cluster. The gap is
 * converted at the tempo of the gap's midpoint (`bpmAt`), so cluster
 * membership is a physical gesture: rolled chords (tones spread up to the
 * cluster tolerance) still cluster.
 *
 * The cluster tolerance is an ABSOLUTE milliseconds value — never
 * tempo-scaled, never widened by the beginner flag (a chord is a physical
 * gesture, independent of level; only the hit WINDOW widens for beginners).
 *
 * @property config supplies [ScoreConfig.chordClusterMs].
 * @property tempoMap converts beat gaps to seconds.
 */
class ChordClusterer(
    private val config: ScoreConfig,
    private val tempoMap: TempoMap,
) {

    /**
     * Clusters [expected] into [ChordCluster]s in canonical-note order. The
     * input may be in any order — a copy is sorted internally, so the result
     * is deterministic regardless of input order.
     */
    fun cluster(expected: List<ExpectedNote>): List<ChordCluster> {
        val sorted = expected.sortedWith(COMPARATOR)
        val clusters = mutableListOf<ChordCluster>()
        var current = mutableListOf<ExpectedNote>()
        for (note in sorted) {
            if (current.isEmpty()) {
                current.add(note)
                continue
            }
            val clusterStartBeat = current.first().startBeat
            val gapBeats = note.startBeat - clusterStartBeat
            val gapSeconds = gapBeats * 60.0 / tempoMap.bpmAt(clusterStartBeat + gapBeats / 2.0)
            if (gapSeconds > config.chordClusterMs / 1000.0) {
                clusters.add(ChordCluster(current))
                current = mutableListOf(note)
            } else {
                current.add(note)
            }
        }
        if (current.isNotEmpty()) clusters.add(ChordCluster(current))
        return clusters
    }

    companion object {
        /** SongPack canonical note order (songpack-v1.md §3.1): startBeat asc, R before L, pitch asc. */
        val COMPARATOR: Comparator<ExpectedNote> =
            compareBy<ExpectedNote>({ it.startBeat }, { it.hand }, { it.pitch })
    }
}