#include "ring_test.h"

#include "engine/dsp/RingBuffer.h"

#include <algorithm>
#include <cstddef>
#include <cstdio>
#include <vector>

namespace {

// Fail loud: print the failing condition with file/line before returning.
#define CHECK(cond)                                                    \
    do {                                                               \
        if (!(cond)) {                                                 \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__,         \
                         __LINE__, #cond);                             \
            return false;                                              \
        }                                                              \
    } while (0)

bool testCapacityAvailableRoundtrip() {
    engine::dsp::RingBuffer rb(8);
    CHECK(rb.capacity() == 8);
    CHECK(rb.available() == 0);

    float data[8];
    for (std::size_t i = 0; i < 8; ++i) {
        data[i] = static_cast<float>(i);
    }
    CHECK(rb.push(data, 8));
    CHECK(rb.available() == 8);

    float out[8];
    CHECK(rb.pop(out, 8));
    CHECK(rb.available() == 0);
    return true;
}

bool testPatternIntegrity() {
    constexpr std::size_t kSamples = 1000;
    engine::dsp::RingBuffer rb(1024);

    std::vector<float> in(kSamples);
    for (std::size_t i = 0; i < kSamples; ++i) {
        in[i] = static_cast<float>(i) * 0.001f;
    }

    std::size_t written = 0;
    while (written < kSamples) {
        const std::size_t chunk =
            std::min<std::size_t>(137, kSamples - written);
        CHECK(rb.push(in.data() + written, chunk));
        written += chunk;
    }

    std::vector<float> out(kSamples);
    std::size_t read = 0;
    while (read < kSamples) {
        const std::size_t chunk = std::min<std::size_t>(89, kSamples - read);
        CHECK(rb.pop(out.data() + read, chunk));
        read += chunk;
    }

    for (std::size_t i = 0; i < kSamples; ++i) {
        if (out[i] != in[i]) {
            std::fprintf(stderr,
                         "FAIL %s:%d: pattern mismatch at %zu: got %f want %f\n",
                         __FILE__, __LINE__, i, out[i], in[i]);
            return false;
        }
    }
    return true;
}

bool testOverfill() {
    engine::dsp::RingBuffer rb(8);
    float data[8];
    for (std::size_t i = 0; i < 8; ++i) {
        data[i] = static_cast<float>(i);
    }
    CHECK(rb.push(data, 8));
    CHECK(rb.available() == 8);

    float extra[10];
    for (std::size_t i = 0; i < 10; ++i) {
        extra[i] = 999.0f;
    }
    CHECK(!rb.push(extra, 10));
    CHECK(rb.available() == 8);  // failed push left the buffer untouched

    float out[8];
    CHECK(rb.pop(out, 8));
    for (std::size_t i = 0; i < 8; ++i) {
        if (out[i] != data[i]) {
            std::fprintf(stderr,
                         "FAIL %s:%d: data corrupted after overfill at %zu: "
                         "got %f want %f\n",
                         __FILE__, __LINE__, i, out[i], data[i]);
            return false;
        }
    }
    return true;
}

bool testUnderfill() {
    engine::dsp::RingBuffer rb(8);
    float data[4] = {1.0f, 2.0f, 3.0f, 4.0f};
    CHECK(rb.push(data, 4));

    float out[5];
    CHECK(!rb.pop(out, 5));   // not enough data
    CHECK(rb.available() == 4);  // failed pop left the buffer untouched

    float small[4];
    CHECK(rb.pop(small, 4));
    CHECK(rb.available() == 0);
    return true;
}

bool testWrapAround() {
    // capacity 8. First push fills slots 0..5; pop 4 consumes 0..3. Second
    // push of 6 writes slots 6,7 then wraps to 0..3. Final pop of 8 reads
    // slots 4..7 then wraps to 0..3 — both directions cross the boundary.
    engine::dsp::RingBuffer rb(8);

    float first[6];
    for (std::size_t i = 0; i < 6; ++i) {
        first[i] = static_cast<float>(i) * 0.001f;
    }
    CHECK(rb.push(first, 6));

    float drop[4];
    CHECK(rb.pop(drop, 4));

    float second[6];
    for (std::size_t i = 0; i < 6; ++i) {
        second[i] = static_cast<float>(i + 6) * 0.001f;
    }
    CHECK(rb.push(second, 6));
    CHECK(rb.available() == 8);

    float expected[8];
    for (std::size_t i = 0; i < 8; ++i) {
        expected[i] = static_cast<float>(i + 4) * 0.001f;
    }

    float out[8];
    CHECK(rb.pop(out, 8));
    for (std::size_t i = 0; i < 8; ++i) {
        if (out[i] != expected[i]) {
            std::fprintf(stderr,
                         "FAIL %s:%d: wrap mismatch at %zu: got %f want %f\n",
                         __FILE__, __LINE__, i, out[i], expected[i]);
            return false;
        }
    }
    CHECK(rb.available() == 0);
    return true;
}

bool testEmpty() {
    engine::dsp::RingBuffer rb(8);
    CHECK(rb.available() == 0);

    float out[4];
    CHECK(!rb.pop(out, 4));  // nothing to read

    float data[8];
    for (std::size_t i = 0; i < 8; ++i) {
        data[i] = static_cast<float>(i);
    }
    CHECK(rb.push(data, 8));
    float all[8];
    CHECK(rb.pop(all, 8));
    CHECK(rb.available() == 0);
    return true;
}

#undef CHECK

}  // namespace

bool runRingBufferTests() {
    if (!testCapacityAvailableRoundtrip()) {
        return false;
    }
    if (!testPatternIntegrity()) {
        return false;
    }
    if (!testOverfill()) {
        return false;
    }
    if (!testUnderfill()) {
        return false;
    }
    if (!testWrapAround()) {
        return false;
    }
    if (!testEmpty()) {
        return false;
    }
    std::printf("ring buffer tests passed\n");
    return true;
}