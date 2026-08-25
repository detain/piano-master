package com.keyquest.scoring.replay

import com.keyquest.scoring.ExpectedNote
import com.keyquest.scoring.MeasureMapper
import com.keyquest.scoring.PlayedNote
import com.keyquest.scoring.ScoreConfig
import com.keyquest.scoring.ScoreReport
import com.keyquest.scoring.Scorer
import com.keyquest.scoring.StarThresholds
import com.keyquest.scoring.TempoMap
import com.keyquest.scoring.TimeSignature

/**
 * The pure replay pipeline (plan §20 P1.5.6): recorded events + expected
 * notes + tempo map -> the same [ScoreReport] the live scorer produces.
 *
 * The live app scores against the SongPack manifest's real time-signature
 * map; a recorded session TSV carries no time signatures, so the replay
 * derives a default 4/4 measure structure with no pickup and a duration
 * covering the expected notes. This means replay heatmap MEASURE BOUNDARIES
 * assume 4/4 (a documented limitation of the replay format — see
 * docs/specs/scoring-v1.md); scores, verdicts, and chord outcomes are
 * unaffected because they never consult the measure mapper.
 */
object ReplayRunner {

    /**
     * Replays one recorded session. [expected] may be empty (empty report,
     * score 0); [events] may be empty (everything MISSED).
     */
    fun replay(
        events: List<PlayedNote>,
        expected: List<ExpectedNote>,
        tempoMap: TempoMap,
        config: ScoreConfig,
        thresholds: StarThresholds,
    ): ScoreReport {
        val durationBeats = expected.maxOfOrNull { it.startBeat + it.durBeats } ?: 0.0
        val measureMapper = MeasureMapper(
            signatures = listOf(TimeSignature(atBeat = 0.0, numerator = 4, denominator = 4)),
            pickupBeats = 0.0,
            durationBeats = durationBeats,
        )
        return Scorer(config, tempoMap, measureMapper).score(expected, events, thresholds)
    }
}