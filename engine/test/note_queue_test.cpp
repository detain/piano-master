#include "note_queue_test.h"

#include "engine/NoteEventQueue.h"

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

engine::NoteEvent makeEvent(std::size_t i) {
    engine::NoteEvent event;
    event.pitch = static_cast<int>(i % 128);
    event.velocity = static_cast<int>((i * 7) % 128);
    event.onTimeNs = static_cast<int64_t>(i) * 1000000;
    event.offTimeNs = -1;
    event.source = (i % 3 == 0)   ? engine::NoteSource::MIC
                   : (i % 3 == 1) ? engine::NoteSource::MIDI
                                  : engine::NoteSource::TOUCH;
    return event;
}

bool testRoundtrip() {
    engine::NoteEventQueue queue(8);
    CHECK(queue.capacity() == 8);
    CHECK(queue.available() == 0);

    const engine::NoteEvent in = makeEvent(0);
    CHECK(queue.push(in));
    CHECK(queue.available() == 1);

    engine::NoteEvent out{};
    CHECK(queue.pop(out));
    CHECK(out == in);
    CHECK(queue.available() == 0);
    return true;
}

bool testOrderingAndZeroLoss() {
    // 10,000 events through a 1024-slot queue with an interleaved
    // producer/consumer (the JNI drain pattern: the consumer pops bounded
    // batches whenever the producer has filled headroom). Asserts every
    // event arrives exactly once, in push order.
    constexpr std::size_t kEvents = 10000;
    constexpr std::size_t kCapacity = 1024;
    engine::NoteEventQueue queue(kCapacity);

    std::vector<engine::NoteEvent> in;
    in.reserve(kEvents);
    for (std::size_t i = 0; i < kEvents; ++i) {
        in.push_back(makeEvent(i));
    }

    std::vector<engine::NoteEvent> out;
    out.reserve(kEvents);

    std::size_t produced = 0;
    std::size_t consumed = 0;
    while (consumed < kEvents) {
        // Producer: fill up to half the queue, or until everything is produced.
        while (produced < kEvents && queue.available() < kCapacity / 2) {
            CHECK(queue.push(in[produced]));
            ++produced;
        }
        // Consumer: drain everything currently available. The loop interleave
        // is the stand-in for the real thread switch.
        engine::NoteEvent event{};
        while (queue.pop(event)) {
            out.push_back(event);
            ++consumed;
        }
    }

    CHECK(produced == kEvents);
    CHECK(out.size() == kEvents);
    for (std::size_t i = 0; i < kEvents; ++i) {
        if (out[i] != in[i]) {
            std::fprintf(stderr,
                         "FAIL %s:%d: event mismatch at %zu\n",
                         __FILE__, __LINE__, i);
            return false;
        }
    }
    CHECK(queue.available() == 0);
    return true;
}

bool testOverfill() {
    engine::NoteEventQueue queue(4);
    for (std::size_t i = 0; i < 4; ++i) {
        CHECK(queue.push(makeEvent(i)));
    }
    CHECK(queue.available() == 4);

    CHECK(!queue.push(makeEvent(4)));  // full: push refused, queue unchanged
    CHECK(queue.available() == 4);

    engine::NoteEvent first{};
    CHECK(queue.pop(first));
    CHECK(first == makeEvent(0));
    CHECK(queue.push(makeEvent(4)));   // reclaimed slot reusable
    CHECK(queue.available() == 4);

    engine::NoteEvent last{};
    for (std::size_t i = 0; i < 4; ++i) {  // drain [1,2,3,4]
        CHECK(queue.pop(last));
    }
    CHECK(last == makeEvent(4));
    return true;
}

bool testEmpty() {
    engine::NoteEventQueue queue(4);
    engine::NoteEvent event{};
    CHECK(!queue.pop(event));  // nothing to read
    CHECK(queue.available() == 0);

    CHECK(queue.push(makeEvent(0)));
    CHECK(queue.pop(event));
    CHECK(!queue.pop(event));  // drained
    return true;
}

bool testReset() {
    engine::NoteEventQueue queue(4);
    CHECK(queue.push(makeEvent(0)));
    CHECK(queue.push(makeEvent(1)));
    queue.reset();  // quiescent here: no other thread
    CHECK(queue.available() == 0);
    CHECK(queue.push(makeEvent(2)));
    engine::NoteEvent event{};
    CHECK(queue.pop(event));
    CHECK(event == makeEvent(2));
    return true;
}

#undef CHECK

}  // namespace

bool runNoteQueueTests() {
    if (!testRoundtrip()) {
        return false;
    }
    if (!testOrderingAndZeroLoss()) {
        return false;
    }
    if (!testOverfill()) {
        return false;
    }
    if (!testEmpty()) {
        return false;
    }
    if (!testReset()) {
        return false;
    }
    std::printf("note event queue tests passed\n");
    return true;
}