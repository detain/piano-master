package com.keyquest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keyquest.app.audio.NoteEvent
import com.keyquest.app.audio.NoteSource
import com.keyquest.app.audio.SilentVoice
import com.keyquest.app.lesson.LessonPhase
import com.keyquest.app.lesson.LessonSession
import com.keyquest.app.notation.LayoutMath
import com.keyquest.app.notation.NoteFeedback
import com.keyquest.app.notation.NotationSkin
import com.keyquest.app.notation.ScrollingNotationPlayer
import com.keyquest.app.songpack.ProtoScoreAdapter
import com.keyquest.app.songpack.SongCurve
import com.keyquest.app.songpack.SongPack
import com.keyquest.app.songpack.SongPackLoader
import com.keyquest.scoring.TempoCurve
import com.keyquest.scoring.TempoMap
import com.keyquest.scoring.TempoPoint
import com.keyquest.scoring.Verdict
import java.util.Locale

/**
 * The P1.6 lesson player screen (plan §24 P1.6): a real SongPack chunk
 * rendered by [ScrollingNotationPlayer] with live per-note feedback, an
 * on-screen keyboard that drives the scorer through touch, transport
 * controls, and a results overlay.
 *
 * ## Asset load
 * The bundled demo pack `songpack/pickup_anacrusis` (manifest/notes/chunks)
 * is read and parsed once by [SongPackLoader] in a [LaunchedEffect]. While it
 * loads the screen centers "Loading…"; a load failure fails LOUD — the
 * exception is kept in state and rendered verbatim, never swallowed into a
 * silent blank screen.
 *
 * ## Session
 * One [LessonSession] per pack owns the pass state machine (READY -> PLAYING
 * -> PAUSED/FINISHED) and scores every touch against the chunk's expected
 * notes. [SilentVoice] is the audio seam: playback is silent until the P1.8
 * soundfont voice plugs in, and the player code does not change.
 *
 * ## Virtual frame clock — the single source of truth (plan §24 (d))
 * [frameSeconds] is the ONLY time source on the screen. Each frame it
 * advances by the wall-frame delta — clamped to 100 ms so a hiccup or a
 * background gap can never fast-forward — but ONLY while the session is
 * PLAYING: pausing freezes the playhead, resuming continues seamlessly, and
 * no re-anchoring is needed because the session's pass clock is anchored to
 * this virtual clock. The same value feeds [LessonSession.tick], the
 * renderer's song time, the keyboard-target window and the touch
 * timestamps, so scoring, rendering and input cannot drift. [framePulse] is
 * a per-frame state write that keeps the session's PLAIN vars (`phase`,
 * `loopEnabled`) observable in composition even while the clock is frozen
 * (paused/finished), so the transport and the results overlay react
 * immediately. A loop auto-replay (the session resets inside [LessonSession.tick])
 * is detected by the frozen-count regression while PLAYING and re-anchors
 * the progress readout.
 *
 * ## Per-frame feedback and keyboard targets (zero allocation)
 * [NoteFeedback] is allocated ONCE per chunk score and its arrays are filled
 * in place every frame from the scorer snapshot — verdict = frozen verdict
 * when frozen, else the tentative verdict; hit beats are rebased to the
 * chunk's renderer time so the pop decays in song beats. Neither array is
 * ever reallocated per frame. The keyboard target array is likewise reused:
 * filled false first, then true for every note whose start beat falls in the
 * ~1-beat lookahead window ahead of the playhead.
 *
 * ## Reduced motion
 * When toggled on, feedback becomes color-only: the notation player
 * suppresses the hit-pop scale animation and the keyboard collapses its
 * target glow to a solid outline.
 *
 * ## Results overlay
 * When the pass finishes, a scrim + card shows the stars, the final score
 * and the per-measure error heatmap, with Retry (fresh pass over the same
 * chunk) and Next (the following chunk in teaching order, disabled on the
 * last one).
 */
@Composable
fun LessonPlayerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pack by remember { mutableStateOf<SongPack?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(Unit) {
        try {
            fun readAsset(name: String): String = context.assets
                .open("songpack/pickup_anacrusis/$name")
                .bufferedReader()
                .use { it.readText() }
            pack = SongPackLoader.load(
                manifestJson = readAsset("manifest.json"),
                notesJson = readAsset("notes.json"),
                chunksJson = readAsset("chunks.json"),
            )
        } catch (e: Exception) {
            loadError = e
        }
    }

    val loaded = pack
    val error = loadError
    when {
        error != null -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Failed to load lesson pack: $error", style = MaterialTheme.typography.bodyMedium)
        }
        loaded != null -> LessonPlayerContent(pack = loaded, modifier = modifier)
        else -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The loaded lesson: transport bar, scrolling notation, on-screen keyboard
 * and the frame loop that drives all of them (see [LessonPlayerScreen] for
 * the full flow).
 */
@Composable
private fun LessonPlayerContent(pack: SongPack, modifier: Modifier = Modifier) {
    val session = remember(pack) { LessonSession(pack) }
    val voice = remember { SilentVoice }

    // Virtual frame clock (single source of truth) and its bookkeeping.
    var frameSeconds by remember { mutableDoubleStateOf(0.0) }
    var passStartFrameSeconds by remember { mutableDoubleStateOf(0.0) }
    var framePulse by remember { mutableIntStateOf(0) }
    var lastFrozenCount by remember { mutableIntStateOf(0) }

    // Chunk score, remembered per chunk: retry keeps the same chunk, so the
    // score — and with it the feedback arrays and keyboard range — survives.
    val score = remember(session.currentChunk) {
        ProtoScoreAdapter.toProtoScore(pack, session.currentChunk)
    }
    val chunkEndSeconds = remember(session.currentChunk) {
        val tempoMap = TempoMap(
            pack.tempoMap.map { point ->
                TempoPoint(
                    atBeat = point.atBeat,
                    bpm = point.bpm,
                    curve = if (point.curve == SongCurve.STEP) TempoCurve.STEP else TempoCurve.LINEAR,
                )
            },
        )
        tempoMap.beatToSeconds(session.currentChunk.endBeat) -
            tempoMap.beatToSeconds(session.currentChunk.startBeat)
    }
    val beatsPerSecond = LayoutMath.beatsPerSecond(pack.defaultTempoBpm)
    // Pass-relative time: the same expression drives the renderer's song time
    // and the keyboard-target window, so retry/loop replay re-anchors both.
    val passSeconds = frameSeconds - passStartFrameSeconds
    val songTimeBeats = passSeconds * beatsPerSecond

    // Reused per-frame arrays (zero allocation while the loop runs).
    val feedback = remember(score) { NoteFeedback.open(score.notes.size) }
    val pitchRange = remember(score) { KeyboardLayout.visibleRange(score.notes.map { it.pitch }) }
    val targets = remember(pitchRange) { BooleanArray(pitchRange.count()) }

    var skin by remember { mutableStateOf<NotationSkin>(NotationSkin.NoteBar) }
    var reducedMotion by remember { mutableStateOf(false) }

    // The chunk-dependent captures track the current chunk via
    // rememberUpdatedState; the effect itself is keyed on the chunk so a swap
    // restarts the loop. lastWallNanos resets with it and the first frame of
    // the new loop is skipped; frameSeconds lives outside the effect, so the
    // frame clock stays continuous across chunk swaps.
    val currentScore by rememberUpdatedState(score)
    val currentFeedback by rememberUpdatedState(feedback)
    val currentPitchRange by rememberUpdatedState(pitchRange)
    val currentTargets by rememberUpdatedState(targets)

    LaunchedEffect(session.currentChunk) {
        var lastWallNanos = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (lastWallNanos >= 0L) {
                val deltaSeconds = (now - lastWallNanos).coerceIn(0L, MAX_FRAME_DELTA_NANOS) / 1_000_000_000.0
                if (session.phase == LessonPhase.PLAYING) frameSeconds += deltaSeconds
            }
            lastWallNanos = now
            session.tick(frameSeconds)
            framePulse++ // keeps plain-var session state observable while frozen

            val snapshot = session.snapshot()
            if (snapshot != null) {
                // A frozen-count regression while PLAYING is the loop auto-
                // replay: the session re-anchored its pass clock inside tick,
                // so the progress readout must re-anchor too.
                if (session.phase == LessonPhase.PLAYING && snapshot.frozenCount < lastFrozenCount) {
                    passStartFrameSeconds = frameSeconds
                }
                lastFrozenCount = snapshot.frozenCount

                val verdicts = currentFeedback.verdicts
                val hitBeats = currentFeedback.hitBeats
                val notes = currentScore.notes
                for (index in verdicts.indices) {
                    verdicts[index] = feedbackCode(snapshot.frozenVerdicts[index] ?: snapshot.tentativeVerdicts[index])
                    val deviationMs = snapshot.deviationMs[index]
                    hitBeats[index] = if (deviationMs != null) {
                        // Rebased to the renderer's chunk-local time: the pop
                        // decays in song beats from the moment the note hit.
                        (notes[index].startBeat + deviationMs / 1000.0 * beatsPerSecond).toFloat()
                    } else {
                        -1f
                    }
                }
            }

            // ~1-beat lookahead target glow (P1.6.7): fill the whole array
            // every frame, then mark every note starting inside the window.
            val passSeconds = frameSeconds - passStartFrameSeconds
            val windowStart = passSeconds * beatsPerSecond
            currentTargets.fill(false)
            val windowEnd = windowStart + 1.0
            for (note in currentScore.notes) {
                if (note.startBeat >= windowStart && note.startBeat < windowEnd) {
                    currentTargets[note.pitch - currentPitchRange.first] = true
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (session.phase == LessonPhase.PLAYING) {
                            session.pause()
                        } else {
                            if (session.phase == LessonPhase.READY) passStartFrameSeconds = frameSeconds
                            session.play()
                        }
                    },
                    enabled = session.phase != LessonPhase.FINISHED,
                ) {
                    Text(
                        when (session.phase) {
                            LessonPhase.READY -> "Play"
                            LessonPhase.PLAYING -> "Pause"
                            LessonPhase.PAUSED -> "Resume"
                            LessonPhase.FINISHED -> "Play"
                        },
                    )
                }
                TextButton(onClick = { session.loopEnabled = !session.loopEnabled }) {
                    Text(if (session.loopEnabled) "Loop: on" else "Loop: off")
                }
                if (session.combo > 1) {
                    Text(
                        text = "x${session.combo}",
                        style = MaterialTheme.typography.titleMedium,
                        color = NotationSkin.NoteBar.hit,
                    )
                }
                Text(text = "♩=${pack.defaultTempoBpm.toInt()}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1f/%.1f s",
                        frameSeconds - passStartFrameSeconds,
                        chunkEndSeconds,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { skin = NotationSkin.NoteBar }, enabled = skin != NotationSkin.NoteBar) {
                    Text("Note-bar")
                }
                TextButton(onClick = { skin = NotationSkin.Staff }, enabled = skin != NotationSkin.Staff) {
                    Text("Staff")
                }
                TextButton(onClick = { reducedMotion = !reducedMotion }) {
                    Text(if (reducedMotion) "Motion: off" else "Motion: on")
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ScrollingNotationPlayer(
                    score = score,
                    skin = skin,
                    tempoBpm = pack.defaultTempoBpm,
                    playing = session.phase == LessonPhase.PLAYING,
                    feedback = feedback,
                    reducedMotion = reducedMotion,
                    externalSongTimeBeats = songTimeBeats,
                )
            }
            OnScreenKeyboard(
                pitchRange = pitchRange,
                targets = targets,
                onNoteOn = { pitch ->
                    // Timestamped on the same virtual frame clock the scorer
                    // ticks, so touch time and pass time cannot drift.
                    val event = NoteEvent(pitch, 100, (frameSeconds * 1e9).toLong(), -1L, NoteSource.TOUCH)
                    session.onEvent(event)
                    voice.play(event)
                },
                onNoteOff = { pitch ->
                    val nowNs = (frameSeconds * 1e9).toLong()
                    voice.stop(NoteEvent(pitch, 100, nowNs, nowNs, NoteSource.TOUCH))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                reducedMotion = reducedMotion,
            )
        }

        if (session.phase == LessonPhase.FINISHED) {
            ResultsOverlay(session = session, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The P1.6.9 results overlay: stars, final score, per-measure error heatmap,
 * and Retry/Next. Renders the FINISHED-phase results (the frozen batch result
 * over the pass's events per the freeze theorem, remembered once per finished
 * pass). Public so Paparazzi screenshot tests can render it with a
 * deterministically-driven session.
 */
@Composable
fun ResultsOverlay(session: LessonSession, modifier: Modifier = Modifier) {
    val report = remember(session) { session.results() }
    Box(modifier = modifier.background(SCRIM_COLOR), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.padding(24.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "★".repeat(report.stars) + "☆".repeat(3 - report.stars),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = String.format(Locale.US, "Score %.1f", report.score),
                    style = MaterialTheme.typography.titleMedium,
                )
                val heatmapLines = report.measureHeatmap.entries.joinToString("\n") { (measure, summary) ->
                    "m${measure + 1}: ${summary.missed} missed / ${summary.wrong} wrong"
                }
                if (heatmapLines.isNotEmpty()) {
                    Text(text = heatmapLines, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { session.retry() }) { Text("Retry") }
                    Button(onClick = { session.next() }, enabled = session.hasNextChunk) { Text("Next") }
                }
            }
        }
    }
}

/** Maps a scorer [Verdict] to the [NoteFeedback] constants the renderer colors by. */
private fun feedbackCode(verdict: Verdict): Int = when (verdict) {
    Verdict.PERFECT -> NoteFeedback.PERFECT
    Verdict.GOOD -> NoteFeedback.GOOD
    Verdict.MISSED -> NoteFeedback.MISSED
    Verdict.WRONG -> NoteFeedback.WRONG
}

/** Frame-delta clamp: a hiccup or a background gap must not fast-forward the pass clock. */
private const val MAX_FRAME_DELTA_NANOS = 100_000_000L // 100 ms, matches the renderer's clamp

/** Dim scrim behind the results card. */
private val SCRIM_COLOR = Color(0x99000000)