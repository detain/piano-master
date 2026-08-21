#include "engine/engine.h"
#include "ring_test.h"

#include <cassert>

int main() {
    if (!runRingBufferTests()) {
        return 1;
    }

    const int current = engine::version();
    assert(current == 1 && "engine::version() must start at 1");
    // Keep the check meaningful even when NDEBUG strips the assert.
    return current == 1 ? 0 : 1;
}