package com.keyquest.app.lesson

import com.keyquest.app.audio.NoteEvent
import com.keyquest.app.songpack.ProtoScoreAdapter
import com.keyquest.app.songpack.SongChunk
import com.keyquest.app.songpack.SongCurve
import com.keyquest.app.songpack.SongPack
import com.keyquest.scoring.MeasureMapper
import com.keyquest.scoring.PlayedNote
import com.keyquest.scoring.RealtimeScorer
import com.keyquest.scoring.ScoreConfig
import com.keyquest.scoring.ScoreReport
import com.keyquest.scoring.Snapshot
import com.keyquest.scoring.StarThresholds
import com.keyquest.scoring.TempoCurve
import com.keyquest.scoring.TempoMap
import com.keyquest.scoring.TempoPoint
import com.keyquest.scoring.TimeSignature

/**
 * Transport state of a [LessonSession] pass.
 *
 * READY = a pass is armed (scorer built) but not running; PLAYING = the pass
 * clock advances with [LessonSession.tick]; PAUSED = the pass is held;
 * FINISHED = the chunk-end report is available via [LessonSession.results].
 */
enum class LessonPhase { READY, PLAYING, PAUSED, FINISHED }

/**
 * One practice session over a single chunk: a pure-Kotlin, frame-driven
 * state machine the UI drives with its monotone frame clock and input
 * events (plan §24 P1.6). No Android imports — JVM-testable.
 *
 * ## Session clock (open question (d))
 *
 * The session derives pass-relative time from the UI's FRAME clock: a pass
 * starts at [play] (anchored to the last frame [tick] saw) and pass time is
 * `frameSeconds - passStartFrameSeconds`. [NoteEvent.onTimeNs] must therefore
 * be on the SAME frame clock — the touch path is; aligning the mic and MIDI
 * clocks to the frame clock is P1.8. While PAUSED the pass clock is not
 * re-anchored on resume: a wall-clock frame source advances the pass clock
 * across the pause, so the UI must freeze its frame clock while paused to
 * hold the playhead (open question (d), revisited with dogfooding).
 *
 * ## Pass lifecycle
 *
 * - [play] from READY starts the pass, from PAUSED resumes it.
 * - [tick] advances the pass clock; at the chunk end the pass is finalized
 *   and the session either auto-replays — loop = reset-and-replay with the
 *   pass's events cleared — or moves to FINISHED, where [results] serves the
 *   final report. The freeze theorem makes [results] EXACTLY the batch
 *   result over the pass's events.
 * - [retry] rebuilds the scorer for the same chunk, CLEARING its events
 *   (a retry is a fresh pass) and resetting the combo; [next] does the same
 *   for the following chunk in teaching order.
 * - Combo counts PER-NOTE in freeze order, one per newly frozen verdict
 *   (see [ComboTracker]): a 3-tone chord that lands as three hits adds +3.
 */
class LessonSession(
    private val pack: SongPack,
    private val config: ScoreConfig = ScoreConfig(),
    private val thresholds: StarThresholds = StarThresholds(),
) {

    /** The pack's chunks in teaching order (1-based `ord` asc); never empty. */
    private val chunks: List<SongChunk> = pack.chunks.sortedBy { it.ord }
        .also { require(it.isNotEmpty()) { "pack must contain at least one chunk" } }

    /**
     * Song-global beat -> seconds map, cached once at construction and shared
     * by every pass's scorer and the chunk-end cache.
     */
    private val tempoMap: TempoMap = TempoMap(
        pack.tempoMap.map { point ->
            TempoPoint(
                atBeat = point.atBeat,
                bpm = point.bpm,
                curve = if (point.curve == SongCurve.STEP) TempoCurve.STEP else TempoCurve.LINEAR,
            )
        },
    )

    /**
     * Pass length in seconds per chunk (cached at construction): the chunk's
     * beat span under the global [tempoMap]. The pass clock restarts at 0 on
     * every [play], so the chunk-end check compares it against this duration.
     */
    private val chunkEndSeconds: List<Double> = chunks.map { chunk ->
        tempoMap.beatToSeconds(chunk.endBeat) - tempoMap.beatToSeconds(chunk.startBeat)
    }

    /** 0-based index into [chunks] of the chunk being practiced. */
    private var chunkIndex = 0

    /** Per-note combo tracking for the active pass (freeze order). */
    private val comboTracker = ComboTracker()

    /**
     * The live pass scorer; built at construction and rebuilt on every
     * [retry]. Rebuilding drops the pass's events — retry and loop are
     * reset-and-replay.
     */
    private var scorer: RealtimeScorer? = null

    /** Transport state of the session. */
    var phase: LessonPhase = LessonPhase.READY
        private set

    /** The most recent frame-clock value seen by [tick], in seconds. */
    private var lastFrameSeconds = 0.0

    /** The frame-clock value at which the active pass started, in seconds. */
    private var passStartFrameSeconds = 0.0

    /**
     * When true, reaching the chunk end auto-replays the chunk from the top
     * (transport loop toggle, P1.6.1) instead of entering FINISHED.
     */
    var loopEnabled: Boolean = false

    /** The chunk being practiced, in teaching order. */
    val currentChunk: SongChunk get() = chunks[chunkIndex]

    /** True when a [next] chunk exists beyond [currentChunk]. */
    val hasNextChunk: Boolean get() = chunkIndex + 1 < chunks.size

    /** Current combo streak of the active pass ([ComboTracker.combo]). */
    val combo: Int get() = comboTracker.combo

    /** Best combo reached during the active pass ([ComboTracker.bestCombo]). */
    val bestCombo: Int get() = comboTracker.bestCombo

    init {
        retry()
    }

    /**
     * Starts the pass from READY, or resumes it from PAUSED.
     *
     * From READY the pass start is anchored to the last frame seen by [tick]
     * (pass time restarts at 0); from PAUSED the pass clock continues. No-op
     * while PLAYING or FINISHED (use [retry] or [next] after FINISHED).
     */
    fun play() {
        when (phase) {
            LessonPhase.READY -> {
                passStartFrameSeconds = lastFrameSeconds
                phase = LessonPhase.PLAYING
            }
            LessonPhase.PAUSED -> phase = LessonPhase.PLAYING
            LessonPhase.PLAYING, LessonPhase.FINISHED -> Unit
        }
    }

    /** Pauses the pass; no-op unless PLAYING. */
    fun pause() {
        if (phase == LessonPhase.PLAYING) phase = LessonPhase.PAUSED
    }

    /**
     * Advances the pass clock by one frame.
     *
     * Only meaningful while PLAYING (no-op otherwise). [frameSeconds] is the
     * UI's monotone frame clock; the pass clock is
     * `frameSeconds - passStartFrameSeconds` and is fed to the scorer, so
     * every event with onTimeNs <= the current frame must have been delivered
     * via [onEvent] BEFORE this call (scorer delivery contract). Newly frozen
     * verdicts (freeze order, the prefix `[before.frozenCount,
     * after.frozenCount)`) advance the per-note combo — on both event and
     * clock freezes. When the pass clock reaches the chunk end the pass is
     * finalized and the session either auto-replays ([loopEnabled];
     * reset-and-replay with events cleared) or enters FINISHED, where the
     * results overlay reads [results].
     */
    fun tick(frameSeconds: Double) {
        if (phase != LessonPhase.PLAYING) return
        require(frameSeconds >= lastFrameSeconds) {
            "frameSeconds must be non-decreasing (frame clock), " +
                "was $frameSeconds after $lastFrameSeconds"
        }
        lastFrameSeconds = frameSeconds
        val passSeconds = frameSeconds - passStartFrameSeconds
        val before = activeScorer.snapshot()
        activeScorer.tick(passSeconds)
        val after = activeScorer.snapshot()
        for (index in before.frozenCount until after.frozenCount) {
            comboTracker.onVerdict(
                checkNotNull(after.frozenVerdicts[index]) {
                    "newly frozen note $index must carry a verdict (scorer invariant)"
                }
            )
        }
        if (passSeconds >= chunkEndSeconds[chunkIndex]) {
            activeScorer.finalize()
            if (loopEnabled) {
                retry()
                // retry() arms the fresh pass (READY); play() from READY
                // re-anchors the pass clock to the current frame and plays.
                play()
            } else {
                phase = LessonPhase.FINISHED
            }
        }
    }

    /**
     * Delivers one played note to the active pass.
     *
     * Ignored unless PLAYING. [NoteEvent.onTimeNs] must be on the frame clock
     * (see class KDoc); the event is rebased to pass time and dropped when it
     * predates the pass start (e.g. a key held from before [play]). The
     * offTimeNs is passed through unchanged — the scorer does not consume it,
     * and telemetry keeps the raw frame-clock value.
     *
     * Newly frozen verdicts (freeze order, the prefix
     * `[before.frozenCount, after.frozenCount)`) advance the per-note combo.
     */
    fun onEvent(noteEvent: NoteEvent) {
        if (phase != LessonPhase.PLAYING) return
        val onTimeNs = noteEvent.onTimeNs - (passStartFrameSeconds * 1e9).toLong()
        if (onTimeNs < 0) return // the event predates the pass start
        val played = PlayedNote(
            pitch = noteEvent.pitch,
            velocity = noteEvent.velocity,
            onTimeNs = onTimeNs,
            offTimeNs = noteEvent.offTimeNs,
        )
        val before = activeScorer.snapshot()
        activeScorer.onEvent(played)
        val after = activeScorer.snapshot()
        for (index in before.frozenCount until after.frozenCount) {
            comboTracker.onVerdict(
                checkNotNull(after.frozenVerdicts[index]) {
                    "newly frozen note $index must carry a verdict (scorer invariant)"
                }
            )
        }
    }

    /**
     * Re-arms the CURRENT chunk for a fresh pass: rebuilds the scorer —
     * which clears the previous pass's events — resets the combo, and returns
     * to READY (the pass clock re-anchors on the next [play]).
     */
    fun retry() {
        val chunk = chunks[chunkIndex]
        scorer = RealtimeScorer(
            expected = ProtoScoreAdapter.toExpectedNotes(pack, chunk),
            config = config,
            tempoMap = tempoMap,
            measureMapper = MeasureMapper(
                signatures = pack.timeSignatures.map { signature ->
                    TimeSignature(signature.atBeat, signature.numerator, signature.denominator)
                },
                pickupBeats = pack.pickupBeats,
                durationBeats = pack.durationBeats,
            ),
            thresholds = thresholds,
        )
        comboTracker.reset()
        phase = LessonPhase.READY
    }

    /**
     * Advances to the following chunk (teaching order) and arms it for a
     * fresh pass; no-op on the last chunk.
     */
    fun next() {
        if (hasNextChunk) {
            chunkIndex++
            retry()
        }
    }

    /**
     * The live scoring view of the active pass (READY/PLAYING/PAUSED), or
     * null after FINISHED — the overlay reads the final report from
     * [results].
     */
    fun snapshot(): Snapshot? = if (phase == LessonPhase.FINISHED) null else scorer?.snapshot()

    /**
     * The final [ScoreReport] of the active (or last completed) pass: exactly
     * the batch result over the pass's events (freeze theorem). Before the
     * first pass completes this is the report over the events so far (score
     * 0.0, 0 stars on a fresh pass).
     */
    fun results(): ScoreReport = activeScorer.finalize()

    /** The active pass scorer; never null after construction (built by [retry]). */
    private val activeScorer: RealtimeScorer
        get() = checkNotNull(scorer) {
            "scorer must exist: it is built at construction and rebuilt on every retry"
        }
}