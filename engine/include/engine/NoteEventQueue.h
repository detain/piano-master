#pragma once

#include <atomic>
#include <cstddef>
#include <stdexcept>
#include <vector>

#include "engine/NoteEvent.h"

namespace engine {

// Lock-free single-producer/single-consumer queue of NoteEvents.
//
// CONTRACT (mirrors engine::dsp::RingBuffer)
// - Exactly ONE producer thread may call push() (the write side).
// - Exactly ONE consumer thread may call pop() (the read side).
// - available() is a hint and may be called from either side.
// - The queue NEVER blocks: push()/pop() return false when the requested
//   transfer cannot complete immediately, leaving the queue unchanged.
// - reset() is only safe while the queue is quiescent (no other thread is
//   pushing or popping).
//
// This is the engine's event transport (plan §5.1 "event queue to JNI"):
// a detector pushes NoteEvents from its worker thread, a Kotlin coroutine
// drains them via the JNI bridge. The audio callback never touches this
// queue directly.
//
// Thread-safety model (memory ordering)
// - The producer writes the event slot, then publishes it with a RELEASE
//   store of head_. The consumer's ACQUIRE load of head_ guarantees the
//   event data is visible.
// - The consumer reads the slot, then publishes consumption with a RELEASE
//   store of tail_. The producer's ACQUIRE load of tail_ guarantees
//   reclaimed slots were fully read before being overwritten.
// - head_ has exactly one writer (the producer) and tail_ exactly one writer
//   (the consumer), so each atomic is contention-free: SPSC needs no locks.
//
// Capacity is a power of two so index arithmetic reduces to a mask.
class NoteEventQueue {
public:
    explicit NoteEventQueue(std::size_t capacityPowerOfTwo = 1024);

    NoteEventQueue(const NoteEventQueue&) = delete;
    NoteEventQueue& operator=(const NoteEventQueue&) = delete;

    // Writes one event. Returns false if the queue is full; the queue is
    // left unchanged in that case. Never blocks.
    bool push(const NoteEvent& event);

    // Reads one event into out. Returns false if the queue is empty; the
    // queue is left unchanged in that case. Never blocks.
    bool pop(NoteEvent& out);

    // Number of events currently available to pop (a hint; may be stale).
    std::size_t available() const;

    std::size_t capacity() const;

    // Empties the queue. Only safe when no other thread is using it.
    void reset();

private:
    std::vector<NoteEvent> slots_;   // slot array; slots are overwritten before read
    std::size_t capacity_;           // power of two
    std::size_t mask_;               // capacity_ - 1
    std::atomic<std::size_t> head_;  // producer write position (monotonic)
    std::atomic<std::size_t> tail_;  // consumer read position (monotonic)
};

inline NoteEventQueue::NoteEventQueue(std::size_t capacityPowerOfTwo)
    : slots_(capacityPowerOfTwo),
      capacity_(capacityPowerOfTwo),
      mask_(capacityPowerOfTwo - 1),
      head_(0),
      tail_(0) {
    // Fail fast: a non-power-of-two capacity would corrupt the mask math.
    if (capacityPowerOfTwo == 0 || (capacityPowerOfTwo & (capacityPowerOfTwo - 1)) != 0) {
        throw std::invalid_argument(
            "NoteEventQueue capacity must be a power of two");
    }
}

inline bool NoteEventQueue::push(const NoteEvent& event) {
    // ACQUIRE on tail_: guarantees the consumer's release-stored tail_ has
    // published its reads, so slots we are about to overwrite were fully
    // consumed. head_ is owned by this thread; relaxed is enough.
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    const std::size_t head = head_.load(std::memory_order_relaxed);
    if (head - tail >= capacity_) {
        return false;
    }

    slots_[head & mask_] = event;

    // RELEASE on head_: publishes the event write above so the consumer's
    // acquire load of head_ observes it.
    head_.store(head + 1, std::memory_order_release);
    return true;
}

inline bool NoteEventQueue::pop(NoteEvent& out) {
    // ACQUIRE on head_: guarantees the producer's release-stored head_ has
    // published its event writes, so the slot we read is visible. tail_ is
    // owned by this thread; relaxed is enough.
    const std::size_t head = head_.load(std::memory_order_acquire);
    const std::size_t tail = tail_.load(std::memory_order_relaxed);
    if (head == tail) {
        return false;
    }

    out = slots_[tail & mask_];

    // RELEASE on tail_: publishes consumption so the producer's acquire load
    // of tail_ knows these slots may be overwritten.
    tail_.store(tail + 1, std::memory_order_release);
    return true;
}

inline std::size_t NoteEventQueue::available() const {
    const std::size_t head = head_.load(std::memory_order_acquire);
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    return head - tail;
}

inline std::size_t NoteEventQueue::capacity() const {
    return capacity_;
}

inline void NoteEventQueue::reset() {
    // Caller guarantees quiescence, so relaxed stores are sufficient.
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
}

}  // namespace engine