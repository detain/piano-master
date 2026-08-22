package com.keyquest.app.audio

/**
 * Pull-based source of note events for [NoteEventFlow].
 *
 * Implementations must never block: the real one drains the native lock-free
 * SPSC queue (via JNI) and returns whatever is currently available, and tests
 * inject a fake. Splitting the source from the flow is what makes the drain
 * logic unit-testable without a device or JNI.
 */
fun interface NoteEventSource {
    /**
     * Drains all currently-available events. Returns an empty list when the
     * queue is empty. Must not block or throw.
     */
    fun drain(): List<NoteEvent>
}