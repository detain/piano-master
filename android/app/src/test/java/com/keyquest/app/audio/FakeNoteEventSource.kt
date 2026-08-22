package com.keyquest.app.audio

/**
 * Test double for [NoteEventSource]: serves a fixed list in FIFO order in
 * bounded batches, mimicking the native JNI drain (which can only hand back
 * one pre-allocated batch per call).
 */
class FakeNoteEventSource(
    events: List<NoteEvent>,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : NoteEventSource {
    private val pending = ArrayDeque(events)

    override fun drain(): List<NoteEvent> {
        val batch = ArrayList<NoteEvent>(minOf(batchSize, pending.size))
        repeat(minOf(batchSize, pending.size)) { batch.add(pending.removeFirst()) }
        return batch
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 64
    }
}