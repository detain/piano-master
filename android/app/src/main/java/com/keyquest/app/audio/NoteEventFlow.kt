package com.keyquest.app.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Polls a [NoteEventSource] on [Dispatchers.Default] and emits every drained
 * event, in order, without loss.
 *
 * The JNI audio callback never touches this code: it only writes into the
 * native lock-free SPSC queue, and this flow drains that queue from a
 * coroutine (plan §20 P0.2.3 rule). When the source is empty the flow just
 * sleeps -- no busy spinning.
 *
 * The flow never completes on its own; the collector controls its lifetime
 * (e.g. `take(n)`, or cancelling the collecting coroutine).
 *
 * Single-collector contract: the native side's pre-allocated drain arrays are
 * not safe for concurrent drains. If multiple consumers are ever needed,
 * share one collected flow (e.g. `shareIn`).
 */
class NoteEventFlow(
    private val source: NoteEventSource,
    private val drainIntervalMillis: Long = DEFAULT_DRAIN_INTERVAL_MILLIS,
) {
    val noteEvents: Flow<NoteEvent> = flow {
        while (currentCoroutineContext().isActive) {
            for (event in source.drain()) {
                emit(event)
            }
            delay(drainIntervalMillis)
        }
    }.flowOn(Dispatchers.Default)

    companion object {
        const val DEFAULT_DRAIN_INTERVAL_MILLIS = 10L
    }
}