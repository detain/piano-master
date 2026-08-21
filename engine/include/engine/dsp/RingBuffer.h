#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

namespace engine::dsp {

// Lock-free single-producer/single-consumer ring buffer of float samples.
//
// CONTRACT (read before using)
// - Exactly ONE producer thread may call push() (the write side).
// - Exactly ONE consumer thread may call pop() (the read side).
// - available() is a hint and may be called from either side.
// - The buffer NEVER blocks: push()/pop() return false when the requested
//   transfer cannot complete immediately, leaving the buffer unchanged.
//   This is what lets an audio callback push frames without stalling.
// - reset() is only safe while the buffer is quiescent (no other thread is
//   pushing or popping).
//
// Thread-safety model (memory ordering)
// - The producer writes sample data into storage_, then publishes it with a
//   RELEASE store of head_. The consumer's ACQUIRE load of head_ guarantees
//   the published data is visible.
// - The consumer reads the samples, then publishes consumption with a
//   RELEASE store of tail_. The producer's ACQUIRE load of tail_ guarantees
//   reclaimed slots were fully read before being overwritten.
// - head_ has exactly one writer (the producer) and tail_ exactly one writer
//   (the consumer), so each atomic is contention-free: SPSC needs no locks.
//
// Capacity is a power of two so index arithmetic reduces to a mask.
class RingBuffer {
public:
    explicit RingBuffer(std::size_t capacityPowerOfTwo = 4096);

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    // Writes n samples from data. Returns false if there is not enough free
    // space; the buffer is left unchanged in that case. Never blocks.
    bool push(const float* data, std::size_t n);

    // Reads n samples into out. Returns false if fewer than n samples are
    // available; the buffer is left unchanged in that case. Never blocks.
    bool pop(float* out, std::size_t n);

    // Number of samples currently available to pop (a hint; may be stale).
    std::size_t available() const;

    std::size_t capacity() const;

    // Empties the buffer. Only safe when no other thread is using it.
    void reset();

private:
    std::vector<float> storage_;
    std::size_t capacity_;            // power of two
    std::size_t mask_;                // capacity_ - 1
    std::atomic<std::size_t> head_;   // producer write position (monotonic)
    std::atomic<std::size_t> tail_;   // consumer read position (monotonic)
};

}  // namespace engine::dsp