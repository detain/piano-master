#include "engine/engine.h"

#include <cassert>

int main() {
    const int current = engine::version();
    assert(current == 1 && "engine::version() must start at 1");
    // Keep the check meaningful even when NDEBUG strips the assert.
    return current == 1 ? 0 : 1;
}