package com.keyquest.app.audio

import kotlinx.coroutines.flow.Flow

/**
 * Android-side entry point for the KeyQuest native audio engine
 * (libkeyquest_engine.so).
 *
 * Lifecycle: [openInput] creates and opens the Oboe input stream, [start]
 * begins capture, [stop] ends it. These are not internally synchronized --
 * call them from a single thread, in order.
 *
 * [noteEvents] drains the native NoteEvent SPSC queue from a coroutine on
 * [NoteEventFlow]'s dispatcher and emits each event as a [NoteEvent]. The
 * audio callback itself never crosses the JNI boundary (plan §20 P0.2.3).
 */
object EngineBridge {
    init {
        System.loadLibrary("keyquest_engine")
    }

    /** Bounded JNI drain batch; large enough that one call covers a note burst. */
    private const val DRAIN_BATCH_SIZE = 256

    // Pre-allocated once: the JNI drain fills these and returns the count
    // written, so the native side never allocates per drain call.
    private val pitches = IntArray(DRAIN_BATCH_SIZE)
    private val velocities = IntArray(DRAIN_BATCH_SIZE)
    private val onTimesNs = LongArray(DRAIN_BATCH_SIZE)
    private val offTimesNs = LongArray(DRAIN_BATCH_SIZE)
    private val sources = IntArray(DRAIN_BATCH_SIZE)

    private val jniSource = NoteEventSource { drainFromJni() }

    /**
     * Cold flow of note events from the native queue. Collect once per app
     * (see [NoteEventFlow]'s single-collector contract).
     */
    val noteEvents: Flow<NoteEvent> = NoteEventFlow(jniSource).noteEvents

    /** Opens the Oboe input stream. Returns false if the device refused. */
    fun openInput(
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        bufferSizeHint: Int = 0,
    ): Boolean = nativeOpenInput(sampleRate, bufferSizeHint)

    /** Starts capture. Returns false if the stream failed to start. */
    fun start(): Boolean = nativeStart()

    /** Stops capture. Safe to call when not started. */
    fun stop(): Boolean = nativeStop()

    private fun drainFromJni(): List<NoteEvent> {
        // Loop until the queue is drained: a short batch (< capacity) or 0
        // means there is nothing left for this tick.
        val result = ArrayList<NoteEvent>(DRAIN_BATCH_SIZE)
        while (true) {
            val count = nativeDrainNoteEvents(
                pitches, velocities, onTimesNs, offTimesNs, sources,
            )
            if (count == 0) break
            for (i in 0 until count) {
                result.add(
                    NoteEvent(
                        pitch = pitches[i],
                        velocity = velocities[i],
                        onTimeNs = onTimesNs[i],
                        offTimeNs = offTimesNs[i],
                        source = NoteSource.fromId(sources[i]),
                    ),
                )
            }
            if (count < DRAIN_BATCH_SIZE) break
        }
        return result
    }

    private external fun nativeOpenInput(sampleRate: Int, bufferSizeHint: Int): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativeStop(): Boolean
    private external fun nativeDrainNoteEvents(
        pitches: IntArray,
        velocities: IntArray,
        onTimesNs: LongArray,
        offTimesNs: LongArray,
        sources: IntArray,
    ): Int

    private const val DEFAULT_SAMPLE_RATE = 48000
}