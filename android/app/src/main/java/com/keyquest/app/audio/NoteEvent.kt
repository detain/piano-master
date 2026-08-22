package com.keyquest.app.audio

/**
 * One detected or synthesized note, timestamped on the audio clock.
 *
 * Mirrors the C++ `engine::NoteEvent` struct (engine/include/engine/NoteEvent.h)
 * so the JNI bridge maps fields 1:1:
 *   pitch, velocity          -> MIDI note number / velocity, 0-127
 *   onTimeNs, offTimeNs      -> audio-clock times in nanoseconds (-1 = still on)
 *   source                   -> [NoteSource]
 */
data class NoteEvent(
    val pitch: Int,
    val velocity: Int,
    val onTimeNs: Long,
    val offTimeNs: Long,
    val source: NoteSource,
)

/**
 * Where a [NoteEvent] originated. The integer ids MUST match the C++
 * `engine::NoteSource` enum values (engine/include/engine/NoteEvent.h), since
 * the JNI bridge passes them across as raw ints.
 */
enum class NoteSource(val id: Int) {
    MIC(0),
    MIDI(1),
    TOUCH(2),
    ;

    companion object {
        /**
         * Parses the int the JNI bridge produces. Fails fast: an unknown id
         * means the native and Kotlin enums drifted out of sync.
         */
        fun fromId(id: Int): NoteSource =
            when (id) {
                MIC.id -> MIC
                MIDI.id -> MIDI
                TOUCH.id -> TOUCH
                else -> error("Unknown NoteSource id $id (native/Kotlin enum drift?)")
            }
    }
}