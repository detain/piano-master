package com.keyquest.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.keyquest.app.audio.NoteEvent
import com.keyquest.app.audio.NoteSource
import com.keyquest.app.lesson.LessonPhase
import com.keyquest.app.lesson.LessonSession
import com.keyquest.app.notation.NoteFeedback
import com.keyquest.app.notation.NotationSkin
import com.keyquest.app.notation.ProtoScore
import com.keyquest.app.notation.ScrollingNotationPlayer
import com.keyquest.app.songpack.ProtoScoreAdapter
import com.keyquest.app.songpack.SongCurve
import com.keyquest.app.songpack.SongPack
import com.keyquest.app.songpack.SongPackLoader
import com.keyquest.scoring.TempoCurve
import com.keyquest.scoring.TempoMap
import com.keyquest.scoring.TempoPoint
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi screenshot tests for the P1.6 lesson-player components (plan §24
 * D4): both notation skins under per-note feedback, the on-screen keyboard
 * target glow, and the finished-pass results overlay.
 *
 * ## Why component-level screenshots
 * The full [LessonPlayerScreen] is not screenshot-friendly: it loads the demo
 * pack from assets asynchronously in a LaunchedEffect (a "Loading…" frame
 * until the read completes) and runs an unbounded per-frame clock loop, so a
 * snapshot would race the asset load and depend on frame timing. Each
 * component is instead driven with fully deterministic input — fixture pack,
 * fixed feedback arrays, a fixed external song time, a scripted session pass —
 * so the goldens are byte-stable across runs.
 *
 * ## Golden recording
 * Goldens are recorded with `recordPaparazziDebug` and verified with
 * `verifyPaparazziDebug` (default maxPercentDifference 0.01). They MUST be
 * recorded on Linux with the SAME JDK 21 as CI: font rasterization differs
 * across OSes, and the CI gate runs the same verify task against goldens
 * recorded here.
 *
 * ## Fixtures
 * The pickup_anacrusis fixture is read from the GENERATED test resources
 * (build/generated/songpack, copied by the copySongpackFixtures Gradle task
 * from content/fixtures — the same canonical files the Python pipeline and
 * the PHP API consume), so the goldens cannot drift from the
 * schema-validated content. A missing resource fails the test loudly instead
 * of rendering a blank pack.
 *
 * ## No HandlerThread (Paparazzi issue #2342)
 * Paparazzi issue #2342 (NoSuchMethodError Thread.setPosixNicenessInternal)
 * fires when code under test starts a HandlerThread. These tests touch none:
 * the notation renderer's layout build uses Dispatchers.Default, the session
 * is a pure-Kotlin state machine driven by scripted events and ticks, and the
 * keyboard is gesture-free here.
 */
class LessonPlayerScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 1280,
            screenHeight = 720,
            density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE,
        ),
    )

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * The canonical pickup_anacrusis golden fixture, loaded from the
     * generated test resources (see the class KDoc). 96 bpm, single tempo
     * point, pickup of 1 beat; chunk c01 spans [0, 9) beats with 13 notes.
     */
    private fun pack(): SongPack {
        val classLoader = requireNotNull(javaClass.classLoader) {
            "app classloader unavailable"
        }
        fun fixture(name: String): String = requireNotNull(
            classLoader.getResourceAsStream("songpack-v1/pickup_anacrusis/$name"),
        ) { "missing pickup_anacrusis/$name on the test classpath — did copySongpackFixtures run?" }
            .bufferedReader().use { it.readText() }
        return SongPackLoader.load(
            manifestJson = fixture("manifest.json"),
            notesJson = fixture("notes.json"),
            chunksJson = fixture("chunks.json"),
        )
    }

    /** The renderer score for chunk c01 (13 notes, rebased to the chunk). */
    private fun chunkScore(): ProtoScore = ProtoScoreAdapter.toProtoScore(pack(), pack().chunks.first())

    /**
     * Deterministic feedback state over [n] notes: index 0 PERFECT (hit beat
     * 1.0), 1 GOOD (hit beat 1.1), 2 MISSED, 3 WRONG, the rest OPEN. With an
     * external song time of 1.0 beats, note 0 pops at full growth while every
     * other hit is past or ahead of its pop window — the golden shows all
     * four feedback colors at once.
     */
    private fun feedback(n: Int): NoteFeedback {
        val state = NoteFeedback.open(n)
        state.verdicts[0] = NoteFeedback.PERFECT
        state.hitBeats[0] = 1.0f
        state.verdicts[1] = NoteFeedback.GOOD
        state.hitBeats[1] = 1.1f
        state.verdicts[2] = NoteFeedback.MISSED
        state.verdicts[3] = NoteFeedback.WRONG
        return state
    }

    // ------------------------------------------------------------------
    // screenshots
    // ------------------------------------------------------------------

    @Test
    fun noteBarSkinShowsFeedback() {
        paparazzi.snapshot {
            MaterialTheme {
                ScrollingNotationPlayer(
                    score = chunkScore(),
                    skin = NotationSkin.NoteBar,
                    tempoBpm = 96.0,
                    playing = false,
                    feedback = feedback(13),
                    externalSongTimeBeats = 1.0,
                    // Paparazzi capture size at this device config — onSizeChanged
                    // never fires under Paparazzi, so the viewport is injected.
                    fixedViewportSize = IntSize(1000, 562),
                    layoutDispatcher = Dispatchers.Unconfined, // deterministic layout before capture; production uses Dispatchers.Default
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun staffSkinShowsFeedback() {
        paparazzi.snapshot {
            MaterialTheme {
                ScrollingNotationPlayer(
                    score = chunkScore(),
                    skin = NotationSkin.Staff,
                    tempoBpm = 96.0,
                    playing = false,
                    feedback = feedback(13),
                    externalSongTimeBeats = 1.0,
                    // Paparazzi capture size at this device config — onSizeChanged
                    // never fires under Paparazzi, so the viewport is injected.
                    fixedViewportSize = IntSize(1000, 562),
                    layoutDispatcher = Dispatchers.Unconfined, // deterministic layout before capture; production uses Dispatchers.Default
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun keyboardShowsTargetGlow() {
        val score = chunkScore()
        val range = KeyboardLayout.visibleRange(score.notes.map { it.pitch })
        val targets = BooleanArray(range.count()).also { t ->
            t[0] = true
            t[2] = true
        }
        paparazzi.snapshot {
            MaterialTheme {
                OnScreenKeyboard(
                    pitchRange = range,
                    targets = targets,
                    onNoteOn = {},
                    onNoteOff = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                )
            }
        }
    }

    @Test
    fun resultsOverlayAfterFinishedPass() {
        val pack = pack()
        val chunk = pack.chunks.first()
        // Song-global beat -> seconds map, converted exactly as LessonSession
        // builds its own, so event timestamps land on the expected beats.
        val tempoMap = TempoMap(
            pack.tempoMap.map { point ->
                TempoPoint(
                    atBeat = point.atBeat,
                    bpm = point.bpm,
                    curve = if (point.curve == SongCurve.STEP) TempoCurve.STEP else TempoCurve.LINEAR,
                )
            },
        )
        val session = LessonSession(pack)
        session.play()
        // Scripted pass: touch the first 8 expected notes of c01, each at its
        // exact expected song time (deviation 0 -> PERFECT), delivered before
        // the tick that passes their time (scorer delivery contract).
        ProtoScoreAdapter.toExpectedNotes(pack, chunk).take(8).forEach { expected ->
            session.onEvent(
                NoteEvent(
                    pitch = expected.pitch,
                    velocity = 100,
                    onTimeNs = (tempoMap.beatToSeconds(expected.startBeat) * 1e9).toLong(),
                    offTimeNs = -1,
                    source = NoteSource.TOUCH,
                ),
            )
        }
        // Chunk c01 spans 9 beats at 96 bpm = 5.625 s; tick(6.0) finalizes the
        // pass: 8 PERFECT, 5 MISSED -> score 100 * 8 * 1.1 / 13 = 67.7, 1 star.
        session.tick(6.0)
        assertEquals(LessonPhase.FINISHED, session.phase)
        paparazzi.snapshot {
            MaterialTheme {
                ResultsOverlay(session = session, modifier = Modifier.fillMaxSize())
            }
        }
    }
}