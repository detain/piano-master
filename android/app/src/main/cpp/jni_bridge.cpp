// JNI bridge between the KeyQuest native audio engine and Kotlin.
//
// P0.2.3 rule: the Oboe audio callback never calls JNI and never allocates;
// it only writes frames into the lock-free ring buffer. Every JNI entry point
// in this file runs on a Kotlin-side thread (a coroutine drains the event
// queue, never the audio callback).
//
// The JNI drain allocates only on the Kotlin side of the boundary: Kotlin
// pre-allocates the primitive arrays and passes them in; this side fills up
// to their capacity and returns the count written.

#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <memory>
#include <utility>

#include "engine/NoteEventQueue.h"
#include "OboeInput.h"

namespace {

constexpr const char* kLogTag = "KeyQuestEngine";

// Bridge-wide SPSC event queue: the (future) detector pushes NoteEvents from
// its worker thread; the Kotlin coroutine drains them via
// nativeDrainNoteEvents. Sized generously (power of two) so a 10-minute soak
// at a 10 ms drain cadence has enormous headroom and never drops an event.
engine::NoteEventQueue g_noteEvents(1u << 12);

// The one-and-only Oboe input stream, owned by the bridge. Lifecycle calls
// (open/start/stop) must come from a single thread and must not race.
std::unique_ptr<engine::io::OboeInput> g_input;

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_keyquest_app_audio_EngineBridge_nativeOpenInput(
    JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint bufferSizeHint) {
    // Fail fast: re-opening with a new config is not supported.
    if (g_input) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "nativeOpenInput: stream already open "
                            "(restart the app to reconfigure)");
        return JNI_FALSE;
    }

    engine::io::OboeInput::Config config;
    config.sampleRate = sampleRate;
    config.bufferSizeHint = bufferSizeHint;

    auto input = std::make_unique<engine::io::OboeInput>(config);
    if (!input->open()) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "nativeOpenInput: open() failed");
        return JNI_FALSE;
    }
    g_input = std::move(input);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_keyquest_app_audio_EngineBridge_nativeStart(JNIEnv* /*env*/,
                                                     jobject /*thiz*/) {
    // Fail fast: start() before open() is a caller bug.
    if (!g_input) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "nativeStart: input not open");
        return JNI_FALSE;
    }
    return g_input->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_keyquest_app_audio_EngineBridge_nativeStop(JNIEnv* /*env*/,
                                                    jobject /*thiz*/) {
    if (!g_input) {
        return JNI_TRUE;  // nothing to stop
    }
    return g_input->stop() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_keyquest_app_audio_EngineBridge_nativeDrainNoteEvents(
    JNIEnv* env, jobject /*thiz*/, jintArray pitches, jintArray velocities,
    jlongArray onTimesNs, jlongArray offTimesNs, jintArray sources) {
    // Early exit: any null or mismatched array is a caller bug; drain nothing.
    if (pitches == nullptr || velocities == nullptr || onTimesNs == nullptr ||
        offTimesNs == nullptr || sources == nullptr) {
        return 0;
    }
    const jsize capacity = env->GetArrayLength(pitches);
    if (capacity == 0 || env->GetArrayLength(velocities) != capacity ||
        env->GetArrayLength(onTimesNs) != capacity ||
        env->GetArrayLength(offTimesNs) != capacity ||
        env->GetArrayLength(sources) != capacity) {
        return 0;
    }

    jint* pitchPtr = env->GetIntArrayElements(pitches, nullptr);
    jint* velocityPtr = env->GetIntArrayElements(velocities, nullptr);
    jlong* onTimePtr = env->GetLongArrayElements(onTimesNs, nullptr);
    jlong* offTimePtr = env->GetLongArrayElements(offTimesNs, nullptr);
    jint* sourcePtr = env->GetIntArrayElements(sources, nullptr);
    if (pitchPtr == nullptr || velocityPtr == nullptr || onTimePtr == nullptr ||
        offTimePtr == nullptr || sourcePtr == nullptr) {
        // OOM while pinning: release what pinned (JNI_ABORT: nothing written)
        // and fail fast rather than half-drain.
        if (pitchPtr != nullptr) {
            env->ReleaseIntArrayElements(pitches, pitchPtr, JNI_ABORT);
        }
        if (velocityPtr != nullptr) {
            env->ReleaseIntArrayElements(velocities, velocityPtr, JNI_ABORT);
        }
        if (onTimePtr != nullptr) {
            env->ReleaseLongArrayElements(onTimesNs, onTimePtr, JNI_ABORT);
        }
        if (offTimePtr != nullptr) {
            env->ReleaseLongArrayElements(offTimesNs, offTimePtr, JNI_ABORT);
        }
        if (sourcePtr != nullptr) {
            env->ReleaseIntArrayElements(sources, sourcePtr, JNI_ABORT);
        }
        return 0;
    }

    jsize count = 0;
    engine::NoteEvent event;
    while (count < capacity && g_noteEvents.pop(event)) {
        pitchPtr[count] = event.pitch;
        velocityPtr[count] = event.velocity;
        onTimePtr[count] = static_cast<jlong>(event.onTimeNs);
        offTimePtr[count] = static_cast<jlong>(event.offTimeNs);
        sourcePtr[count] = static_cast<jint>(event.source);
        ++count;
    }

    env->ReleaseIntArrayElements(pitches, pitchPtr, 0);
    env->ReleaseIntArrayElements(velocities, velocityPtr, 0);
    env->ReleaseLongArrayElements(onTimesNs, onTimePtr, 0);
    env->ReleaseLongArrayElements(offTimesNs, offTimePtr, 0);
    env->ReleaseIntArrayElements(sources, sourcePtr, 0);

    return count;
}