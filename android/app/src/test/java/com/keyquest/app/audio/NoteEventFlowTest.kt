package com.keyquest.app.audio

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the drain-and-emit logic of [NoteEventFlow] with a fake event
 * source, so the SPSC transport is proven zero-loss before any device is
 * involved. The audio callback / JNI side is not exercised here; it is
 * covered by the engine host tests (engine/test/note_queue_test.cpp) and the
 * pending device soak.
 */
class NoteEventFlowTest {

    @Test
    fun emitsAllEventsInOrderWithoutLoss() = runBlocking {
        val eventCount = 10_000
        val expected = List(eventCount) { i ->
            NoteEvent(
                pitch = i % 128,
                velocity = (i * 7) % 128,
                onTimeNs = i * 1_000_000L,
                offTimeNs = -1L,
                source = when (i % 3) {
                    0 -> NoteSource.MIC
                    1 -> NoteSource.MIDI
                    else -> NoteSource.TOUCH
                },
            )
        }
        val source = FakeNoteEventSource(expected)
        val flow = NoteEventFlow(source, drainIntervalMillis = 1)

        val received = withTimeout(10_000) {
            flow.noteEvents.take(eventCount).toList()
        }

        assertEquals(expected, received)
    }

    @Test
    fun emitsNothingWhileSourceIsEmpty() {
        val source = FakeNoteEventSource(emptyList())
        val flow = NoteEventFlow(source, drainIntervalMillis = 1)
        val received = mutableListOf<NoteEvent>()

        val outcome = runBlocking {
            try {
                withTimeout(100) {
                    flow.noteEvents.collect { received += it }
                }
                "completed"
            } catch (e: TimeoutCancellationException) {
                "timeout"
            }
        }

        // The flow is infinite, so the only way out is the timeout; the
        // important part is that an empty source produced no emissions.
        assertEquals("timeout", outcome)
        assertTrue(received.isEmpty())
    }
}