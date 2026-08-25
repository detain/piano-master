package com.keyquest.scoring

/**
 * Star thresholds, tunable via remote config (plan §6: "Stars: ★ >= 60%,
 * ★★ >= 80%, ★★★ >= 95% (tunable via remote config)"). The scorer takes them
 * as a parameter; the app feeds live remote-config values in P1.6+.
 *
 * Star count = the number of thresholds the score meets: score >= oneStar -> 1
 * star, >= twoStar -> 2, >= threeStar -> 3, else 0.
 *
 * @property oneStar score needed for 1 star, 0..100.
 * @property twoStar score needed for 2 stars, >= [oneStar].
 * @property threeStar score needed for 3 stars, >= [twoStar].
 */
data class StarThresholds(
    val oneStar: Double = 60.0,
    val twoStar: Double = 80.0,
    val threeStar: Double = 95.0,
) {
    init {
        require(oneStar in 0.0..100.0) { "oneStar must be in 0..100, was $oneStar" }
        require(twoStar in oneStar..100.0) { "twoStar must be in oneStar..100, was $twoStar (oneStar=$oneStar)" }
        require(threeStar in twoStar..100.0) { "threeStar must be in twoStar..100, was $threeStar (twoStar=$twoStar)" }
    }
}

/**
 * Scoring configuration (plan §6 defaults; docs/specs/scoring-v1.md §"Window
 * formula").
 *
 * Matching window at expected beat t:
 *   bpm   = tempoMap.bpmAt(t)
 *   scale = (refBpm / bpm).coerceIn(windowScaleClampMin, windowScaleClampMax)
 *   earlyMs = baseEarlyMs * scale      (beginnerEarlyMs * scale when beginner)
 *   lateMs  = baseLateMs  * scale      (beginnerLateMs  * scale when beginner)
 *   window = [t - earlyMs/1000, t + lateMs/1000] in seconds
 *
 * Documented decisions (see scoring-v1.md):
 *  - The window scales with tempo (half the bpm -> double the window) so a
 *    slow rendition is not punished for human timing error at half speed
 *    (plan §6 "window scales with tempo and level").
 *  - The tempo scaling is clamped to [windowScaleClampMin, windowScaleClampMax]
 *    so extreme tempos cannot produce degenerate (near-zero or absurdly wide)
 *    windows.
 *  - Beginner mode widens the window to ±250 ms at reference tempo (plan §6
 *    "beginners get ±250 ms"), also tempo-scaled and clamped.
 *  - |deviation| <= perfectBandMs (at reference tempo) at the note's window
 *    center => PERFECT, otherwise GOOD. The band is deliberately smaller than
 *    the window, and the two are independent knobs (calibration will use
 *    device latency data from P0.2.4 — see scoring-v1.md "open questions").
 *  - PERFECT hits weigh scoringWeight * (1 + perfectBonus) — the "timing
 *    bonus for PERFECT" (plan §6) — so perfect timing is worth slightly more
 *    than a good hit, while GOOD still gives full weight.
 *  - Chord cluster tolerance: notes whose onsets land within
 *    chordClusterMs of a cluster's first tone are one chord (plan §6 "90 ms
 *    cluster"); the tolerance is an absolute milliseconds value, NOT
 *    tempo-scaled (a chord is a physical gesture).
 *
 * @property refBpm the bpm at which the base windows are defined (> 0).
 * @property baseEarlyMs early-window width at reference tempo (> 0).
 * @property baseLateMs late-window width at reference tempo (> 0).
 * @property beginner when true, widen the window to the beginner widths.
 * @property beginnerEarlyMs early-window width for beginners (> 0; only used
 *   when [beginner]).
 * @property beginnerLateMs late-window width for beginners (> 0; only used
 *   when [beginner]).
 * @property windowScaleClampMin lower clamp of the tempo scale (0 < min).
 * @property windowScaleClampMax upper clamp of the tempo scale (>= min).
 * @property perfectBandMs |deviation| <= this (at reference tempo) is
 *   PERFECT, else GOOD (> 0).
 * @property perfectBonus PERFECT hit weight factor 1 + this (>= 0).
 * @property chordClusterMs chord cluster tolerance in milliseconds (> 0).
 */
data class ScoreConfig(
    val refBpm: Double = 120.0,
    val baseEarlyMs: Double = 120.0,
    val baseLateMs: Double = 180.0,
    val beginner: Boolean = false,
    val beginnerEarlyMs: Double = 250.0,
    val beginnerLateMs: Double = 250.0,
    val windowScaleClampMin: Double = 0.5,
    val windowScaleClampMax: Double = 2.0,
    val perfectBandMs: Double = 50.0,
    val perfectBonus: Double = 0.10,
    val chordClusterMs: Double = 90.0,
) {
    init {
        require(refBpm > 0.0) { "refBpm must be > 0, was $refBpm" }
        require(baseEarlyMs > 0.0) { "baseEarlyMs must be > 0, was $baseEarlyMs" }
        require(baseLateMs > 0.0) { "baseLateMs must be > 0, was $baseLateMs" }
        require(beginnerEarlyMs > 0.0) { "beginnerEarlyMs must be > 0, was $beginnerEarlyMs" }
        require(beginnerLateMs > 0.0) { "beginnerLateMs must be > 0, was $beginnerLateMs" }
        require(windowScaleClampMin > 0.0) { "windowScaleClampMin must be > 0, was $windowScaleClampMin" }
        require(windowScaleClampMax >= windowScaleClampMin) {
            "windowScaleClampMax ($windowScaleClampMax) must be >= windowScaleClampMin ($windowScaleClampMin)"
        }
        require(perfectBandMs > 0.0) { "perfectBandMs must be > 0, was $perfectBandMs" }
        require(perfectBonus >= 0.0) { "perfectBonus must be >= 0, was $perfectBonus" }
        require(chordClusterMs > 0.0) { "chordClusterMs must be > 0, was $chordClusterMs" }
    }

    /**
     * The effective hit window at [noteBeat] under [tempoMap], in
     * milliseconds: tempo-scaled (clamped), beginner-aware (see class KDoc
     * for the formula).
     */
    fun windowMs(noteBeat: Double, tempoMap: TempoMap): HitWindow {
        val bpm = tempoMap.bpmAt(noteBeat)
        val scale = (refBpm / bpm).coerceIn(windowScaleClampMin, windowScaleClampMax)
        val early = if (beginner) beginnerEarlyMs else baseEarlyMs
        val late = if (beginner) beginnerLateMs else baseLateMs
        return HitWindow(early * scale, late * scale)
    }
}

/**
 * The effective early/late window widths in milliseconds for one note
 * ([ScoreConfig.windowMs] result).
 */
data class HitWindow(
    val earlyMs: Double,
    val lateMs: Double,
)