package com.keyquest.app.lesson

import com.keyquest.app.audio.NoteEvent
import com.keyquest.app.audio.NoteSource
import com.keyquest.app.songpack.SongChunk
import com.keyquest.app.songpack.SongKeySignature
import com.keyquest.app.songpack.SongNote
import com.keyquest.app.songpack.SongPack
import com.keyquest.app.songpack.SongTempoPoint
import com.keyquest.app.songpack.SongTimeSignature
import com.keyquest.scoring.Hand
import com.keyquest.scoring.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LessonSessionTest — unit tests for [LessonSession], the frame-driven
 * transport state machine over one chunk (plan §24 P1.6): play/pause/retry/
 * next/loop transport, the pass clock (`frameSeconds - passStartFrameSeconds`),
 * event rebasing to pass time, and the per-note combo in freeze order.
 *
 * All packs sit at a constant 120 bpm, so one beat is 0.5 s: chunk c01
 * [0, 3) beats -> 1.5 s, c02 [3, 7) beats -> 2.0 s of pass time. The scorer
 * closes each note at its expected time + 0.18 s (default late window), and
 * events must be delivered BEFORE the tick that passes their time (scorer
 * delivery contract).
 *
 * JVM-only (unit test, no device).
 */
class LessonSessionTest {

    // ------------------------------------------------------------------
    // transport
    // ------------------------------------------------------------------

    @Test
    fun initialState() {
        val session = LessonSession(twoChunkPack())
        assertEquals(LessonPhase.READY, session.phase)
        assertEquals("c01", session.currentChunk.chunkId)
        assertTrue(session.hasNextChunk)
        // READY arms the pass scorer (built at construction), so a live
        // snapshot exists before the first play().
        assertNotNull(session.snapshot())
        assertEquals(0, session.combo)
        assertEquals(0, session.bestCombo)
    }

    @Test
    fun playTickFinishFlow() {
        val session = LessonSession(twoChunkPack())
        session.play()
        assertEquals(LessonPhase.PLAYING, session.phase)
        // Note 60 sits at beat 0 -> pass time 0.0 s, so a touch at pass time
        // 0 is a PERFECT hit (deviation 0). Delivered before the first tick
        // per the scorer delivery contract.
        session.onEvent(event(60, 0.0))
        session.tick(0.5)
        // Watermark 0.5 s > close(0) = 0.0 + 0.18 s: note 60 froze PERFECT,
        // advancing the combo.
        assertEquals(LessonPhase.PLAYING, session.phase)
        assertEquals(1, session.combo)
        assertEquals(Verdict.PERFECT, session.snapshot()!!.frozenVerdicts[0])
        // Chunk c01 ends at 1.5 s of pass time; 1.6 s passes it.
        session.tick(1.6)
        assertEquals(LessonPhase.FINISHED, session.phase)
        val report = session.results()
        assertEquals(1, report.perfectCount)
        assertEquals(2, report.missedCount)
        // One PERFECT of three notes: 100 * (1 * 1.1) / 3.
        assertEquals(100.0 * 1.1 / 3.0, report.score, 0.001)
        // The two unplayed notes froze MISSED at the finalize tick, so the
        // streak reset — bestCombo remembers the single PERFECT.
        assertEquals(0, session.combo)
        assertEquals(1, session.bestCombo)
    }

    @Test
    fun pauseFreezesClock() {
        val session = LessonSession(twoChunkPack())
        session.play()
        session.tick(0.5)
        session.pause()
        assertEquals(LessonPhase.PAUSED, session.phase)
        // Ticks while PAUSED are no-ops: the pass clock is held and the live
        // snapshot stays accessible.
        session.tick(0.9)
        assertEquals(LessonPhase.PAUSED, session.phase)
        assertNotNull(session.snapshot())
        // Resume continues the SAME pass clock — frame - passStart, with
        // passStart anchored at play() (frame 0.0) — so tick(1.6) puts the
        // pass at 1.6 s, past the 1.5 s chunk end.
        session.play()
        assertEquals(LessonPhase.PLAYING, session.phase)
        session.tick(1.6)
        assertEquals(LessonPhase.FINISHED, session.phase)
        assertEquals(3, session.results().missedCount)
        assertEquals(0.0, session.results().score, 0.0)
    }

    @Test
    fun retryClearsEventsAndResults() {
        val session = LessonSession(twoChunkPack())
        session.play()
        session.onEvent(event(60, 0.0))
        session.tick(1.6)
        assertEquals(LessonPhase.FINISHED, session.phase)
        assertEquals(1, session.results().perfectCount)
        // retry() rebuilds the scorer for the SAME chunk: the pass's events
        // are gone, the combo resets, and READY arms a fresh pass.
        session.retry()
        assertEquals(LessonPhase.READY, session.phase)
        assertNotNull(session.snapshot())
        assertEquals(0, session.combo)
        assertEquals(0, session.bestCombo)
        // results() is valid again: the fresh pass has no events.
        assertEquals(0.0, session.results().score, 0.0)
        // A second, eventless pass over c01: all three notes miss. play()
        // from READY re-anchors the pass clock at the last frame (1.6 s), so
        // tick(3.2) is pass time 1.6 s — past the 1.5 s chunk end.
        session.play()
        session.tick(3.2)
        assertEquals(LessonPhase.FINISHED, session.phase)
        assertEquals(3, session.results().missedCount)
        assertEquals(0.0, session.results().score, 0.0)
    }

    @Test
    fun nextAdvancesChunk() {
        val session = LessonSession(twoChunkPack())
        session.play()
        session.tick(1.6)
        assertEquals(LessonPhase.FINISHED, session.phase)
        session.next()
        assertEquals("c02", session.currentChunk.chunkId)
        assertEquals(LessonPhase.READY, session.phase)
        assertFalse(session.hasNextChunk)
        // next() on the last chunk is a no-op: same chunk, still armed.
        session.next()
        assertEquals("c02", session.currentChunk.chunkId)
        assertEquals(LessonPhase.READY, session.phase)
        assertFalse(session.hasNextChunk)
    }

    @Test
    fun loopAutoReplays() {
        val session = LessonSession(twoChunkPack())
        session.loopEnabled = true
        session.play()
        session.tick(1.6)
        // Chunk end (1.5 s) auto-replays instead of FINISHED: retry() +
        // play(), with the pass clock re-anchored to the current frame.
        assertEquals(LessonPhase.PLAYING, session.phase)
        // The fresh pass started at frame 1.6, so tick(3.0) is pass time
        // 1.4 s — still short of the 1.5 s chunk end.
        session.tick(3.0)
        assertEquals(LessonPhase.PLAYING, session.phase)
        // The replay cleared the previous pass's events and combo.
        assertEquals(0, session.combo)
        assertEquals(0, session.bestCombo)
    }

    // ------------------------------------------------------------------
    // event handling
    // ------------------------------------------------------------------

    @Test
    fun eventsIgnoredUnlessPlaying() {
        val session = LessonSession(twoChunkPack())
        // READY: the event predates any pass; dropped without a crash.
        session.onEvent(event(60, 0.0))
        assertEquals(0, session.combo)
        assertEquals(0.0, session.snapshot()!!.tentativeScore, 0.0)
        session.play()
        session.tick(1.6)
        assertEquals(LessonPhase.FINISHED, session.phase)
        // FINISHED: the pass is closed; events are ignored.
        session.onEvent(event(60, 0.0))
        assertEquals(0, session.combo)
        assertEquals(3, session.results().missedCount)
    }

    @Test
    fun prePassEventsDropped() {
        val session = LessonSession(twoChunkPack())
        session.play()
        val before = session.snapshot()
        // An event timestamped before the pass start (negative pass time —
        // e.g. a key held from before play()) is dropped: the live view and
        // the results are unchanged.
        session.onEvent(
            NoteEvent(pitch = 60, velocity = 100, onTimeNs = -5L, offTimeNs = -1, source = NoteSource.TOUCH)
        )
        assertEquals(before, session.snapshot())
        assertEquals(0, session.combo)
        assertEquals(0.0, session.results().score, 0.0)
    }

    // ------------------------------------------------------------------
    // combo in freeze order
    // ------------------------------------------------------------------

    @Test
    fun comboCountsPerNoteInFreezeOrder() {
        val session = LessonSession(chordPack())
        session.play()
        // A rolled chord: notes 60/62/64 at beats 0/0.05/0.1 -> pass times
        // 0.0/0.025/0.05 s, all PERFECT (deviation 0). The events' own
        // watermark (0.05 s) is below every close, so nothing freezes yet.
        session.onEvent(event(60, 0.0))
        session.onEvent(event(62, 0.025))
        session.onEvent(event(64, 0.05))
        session.tick(1.6)
        // runningMaxClose[2] = 0.23 s < 1.6: all three froze PERFECT — the
        // combo counts ONE PER NOTE in freeze order (3 notes -> +3).
        assertEquals(3, session.combo)
        assertEquals(3, session.bestCombo)
        assertEquals(LessonPhase.PLAYING, session.phase) // chunk end is 2.5 s
        // Note 66 at beat 4.9 -> pass time 2.45 s, close 2.63 s: still open
        // at watermark 1.6 s, so the frozen set is the PERFECT prefix.
        val snap = session.snapshot()
        assertNotNull(snap)
        assertEquals(3, snap!!.frozenCount)
        assertEquals(
            listOf(Verdict.PERFECT, Verdict.PERFECT, Verdict.PERFECT, null),
            snap.frozenVerdicts,
        )
        // A tick past the chunk end freezes note 66 as MISSED (no event) and
        // finalizes: the miss resets the streak, bestCombo survives.
        session.tick(2.7)
        assertEquals(LessonPhase.FINISHED, session.phase)
        assertEquals(0, session.combo)
        assertEquals(3, session.bestCombo)
        assertEquals(3, session.results().perfectCount)
        assertEquals(1, session.results().missedCount)
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Pack A: two chunks over an 8-beat song at a constant 120 bpm. */
    private fun twoChunkPack(): SongPack = pack(
        notes = listOf(
            note(60, 0.0), note(62, 1.0), note(64, 2.0),
            note(67, 4.0), note(69, 5.0), note(71, 6.0),
        ),
        chunks = listOf(
            SongChunk(chunkId = "c01", ord = 1, startBeat = 0.0, endBeat = 3.0),
            SongChunk(chunkId = "c02", ord = 2, startBeat = 3.0, endBeat = 7.0),
        ),
    )

    /** Pack B: one longer chunk whose last note closes AFTER the chunk end. */
    private fun chordPack(): SongPack = pack(
        notes = listOf(
            note(60, 0.0), note(62, 0.05), note(64, 0.1), note(66, 4.9),
        ),
        chunks = listOf(SongChunk(chunkId = "c01", ord = 1, startBeat = 0.0, endBeat = 5.0)),
    )

    /** Builds a [SongPack] with the fixed identity the session tests rely on. */
    private fun pack(notes: List<SongNote>, chunks: List<SongChunk>): SongPack = SongPack(
        title = "T",
        songId = "t",
        defaultTempoBpm = 120.0,
        pickupBeats = 0.0,
        durationBeats = 8.0,
        tempoMap = listOf(SongTempoPoint(0.0, 120.0)),
        timeSignatures = listOf(SongTimeSignature(0.0, 4, 4)),
        keySignatures = listOf(SongKeySignature(0.0, 0)),
        notes = notes,
        chunks = chunks,
    )

    /** Builds a right-hand melody [SongNote] with the defaults the tests rely on. */
    private fun note(pitch: Int, startBeat: Double): SongNote = SongNote(
        pitch = pitch,
        startBeat = startBeat,
        durBeats = 1.0,
        hand = Hand.R,
        staff = 1,
    )

    /** A touch event at [passSeconds] on the frame clock (pass start at frame 0). */
    private fun event(pitch: Int, passSeconds: Double): NoteEvent = NoteEvent(
        pitch = pitch,
        velocity = 100,
        onTimeNs = (passSeconds * 1e9).toLong(),
        offTimeNs = -1,
        source = NoteSource.TOUCH,
    )
}