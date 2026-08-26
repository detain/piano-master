package com.keyquest.app.audio

/**
 * Seam between the lesson player and the note sound.
 *
 * The player (RealtimeScorer / OnScreenKeyboard) emits [NoteEvent]s without
 * knowing how they are voiced; the voice owns all audio concerns. Today the
 * only implementation is [SilentVoice], which keeps playback silent while the
 * app ships without a synthesizer. When fluidsynth provisioning lands (P1.8),
 * a soundfont-backed implementation plugs in here — no player code changes.
 *
 * Thread contract: implementations are called on the UI thread and MUST
 * return quickly. The default [SilentVoice] is trivially fast; the P1.8
 * fluidsynth voice is expected to enqueue work onto its own audio thread
 * (respecting the audio-callback rule: never allocate, lock, or call JNI
 * from the callback) and return immediately.
 */
interface NoteVoice {
    /** Voices one played note. Called on the UI thread; must return quickly. */
    fun play(note: NoteEvent)

    /** Stops a held note ([NoteEvent.offTimeNs] set). */
    fun stop(note: NoteEvent)
}

/**
 * Default no-op voice: plays nothing.
 *
 * Keeps the player path exercised end-to-end (timing, scoring, transport)
 * before the P1.8 fluidsynth voice replaces it. Never throws, never blocks.
 */
object SilentVoice : NoteVoice {
    override fun play(note: NoteEvent) = Unit
    override fun stop(note: NoteEvent) = Unit
}