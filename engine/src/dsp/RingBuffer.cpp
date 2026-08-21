#include "engine/dsp/RingBuffer.h"

#include <algorithm>
#include <cstring>
#include <stdexcept>

namespace engine::dsp {

namespace {

bool isPowerOfTwo(std::size_t n) {
    return n != 0 && (n & (n - 1)) == 0;
}

}  // namespace

RingBuffer::RingBuffer(std::size_t capacityPowerOfTwo) {
    // Fail fast: a non-power-of-two capacity would corrupt the mask math.
    if (!isPowerOfTwo(capacityPowerOfTwo)) {
        throw std::invalid_argument(
            "RingBuffer capacity must be a power of two");
    }
    capacity_ = capacityPowerOfTwo;
    mask_ = capacityPowerOfTwo - 1;
    storage_.resize(capacityPowerOfTwo);
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
}

bool RingBuffer::push(const float* data, std::size_t n) {
    if (n == 0) {
        return true;
    }

    // ACQUIRE on tail_: guarantees the consumer's release-stored tail_ has
    // published its reads, so slots we are about to overwrite were fully
    // consumed. head_ is owned by this thread; relaxed is enough.
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    const std::size_t head = head_.load(std::memory_order_relaxed);
    if (head - tail + n > capacity_) {
        return false;
    }

    const std::size_t start = head & mask_;
    const std::size_t first = std::min(n, capacity_ - start);
    std::memcpy(storage_.data() + start, data, first * sizeof(float));
    if (first < n) {
        std::memcpy(storage_.data(), data + first, (n - first) * sizeof(float));
    }

    // RELEASE on head_: publishes the sample data written above so the
    // consumer's acquire load of head_ observes it.
    head_.store(head + n, std::memory_order_release);
    return true;
}

bool RingBuffer::pop(float* out, std::size_t n) {
    if (n == 0) {
        return true;
    }

    // ACQUIRE on head_: guarantees the producer's release-stored head_ has
    // published its sample writes, so the data we read is visible. tail_ is
    // owned by this thread; relaxed is enough.
    const std::size_t head = head_.load(std::memory_order_acquire);
    const std::size_t tail = tail_.load(std::memory_order_relaxed);
    if (head - tail < n) {
        return false;
    }

    const std::size_t start = tail & mask_;
    const std::size_t first = std::min(n, capacity_ - start);
    std::memcpy(out, storage_.data() + start, first * sizeof(float));
    if (first < n) {
        std::memcpy(out + first, storage_.data(), (n - first) * sizeof(float));
    }

    // RELEASE on tail_: publishes consumption so the producer's acquire load
    // of tail_ knows these slots may be overwritten.
    tail_.store(tail + n, std::memory_order_release);
    return true;
}

std::size_t RingBuffer::available() const {
    const std::size_t head = head_.load(std::memory_order_acquire);
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    return head - tail;
}

std::size_t RingBuffer::capacity() const {
    return capacity_;
}

void RingBuffer::reset() {
    // Caller guarantees quiescence, so relaxed stores are sufficient.
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
}

}  // namespace engine::dsp