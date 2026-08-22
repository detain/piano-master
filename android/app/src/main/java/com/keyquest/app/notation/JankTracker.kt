package com.keyquest.app.notation

import android.util.Log
import android.view.Window
import androidx.metrics.performance.JankStats
import java.util.Locale

/**
 * P0.5.3 instrumentation hook: JankStats frame listener that logs
 * frame-over-24ms and dropped (janky) frames to logcat under the tag
 * [TAG], and prints a session summary on [stop].
 *
 * The ≥58 fps / ≤1% dropped / no-frame-over-24ms acceptance measurement
 * happens later on the low-end device (plan §20 P0.5.3); this object is the
 * on-device collector that produces those numbers. Attach it to a screen's
 * lifecycle (ON_RESUME / ON_PAUSE) so the session covers only the notation
 * player.
 */
object JankTracker {

    const val TAG = "KeyQuestJank"

    /** The P0.5.3 acceptance bar for a single frame, in milliseconds. */
    const val MAX_FRAME_MILLIS = 24f

    private var jankStats: JankStats? = null

    // Session counters (the frame listener runs on the main thread, so plain
    // fields are safe; reset by [start]).
    private var frameCount = 0L
    private var jankCount = 0L
    private var overBudgetCount = 0L
    private var maxFrameMillis = 0f

    /** Starts tracking the given window. No-op if already tracking. */
    fun start(window: Window) {
        if (jankStats != null) return
        frameCount = 0
        jankCount = 0
        overBudgetCount = 0
        maxFrameMillis = 0f
        val tracker = JankStats.createAndTrack(window) { frame ->
            frameCount++
            val durationMillis = frame.frameDurationUiNanos / 1_000_000f
            if (durationMillis > maxFrameMillis) maxFrameMillis = durationMillis
            if (frame.isJank) jankCount++
            if (durationMillis > MAX_FRAME_MILLIS) {
                overBudgetCount++
                Log.w(
                    TAG,
                    "frame over ${MAX_FRAME_MILLIS}ms: " +
                        String.format(Locale.US, "%.1f", durationMillis) +
                        "ms isJank=${frame.isJank}",
                )
            }
        }
        tracker.isTrackingEnabled = true
        jankStats = tracker
        Log.i(TAG, "JankStats tracking started")
    }

    /** Stops tracking and logs the session summary (frames / jank / >24ms / max). */
    fun stop() {
        val tracker = jankStats ?: return
        tracker.isTrackingEnabled = false
        jankStats = null
        val dropPct = if (frameCount > 0) 100.0 * jankCount / frameCount else 0.0
        Log.i(
            TAG,
            "session: frames=$frameCount jank=$jankCount " +
                String.format(Locale.US, "(%.2f%%)", dropPct) +
                " over${MAX_FRAME_MILLIS}ms=$overBudgetCount " +
                String.format(Locale.US, "max=%.1fms", maxFrameMillis),
        )
    }
}