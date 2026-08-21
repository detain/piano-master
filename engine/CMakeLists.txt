cmake_minimum_required(VERSION 3.16)

project(engine_core
    VERSION 0.1.0
    DESCRIPTION "KeyQuest platform-portable C++20 audio engine"
    LANGUAGES CXX
)

# --- Language standard ------------------------------------------------------
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# --- Warnings as errors for GCC/Clang (host and Android NDK) ----------------
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-Wall -Wextra -Wpedantic -Werror)
endif()

# --- engine_core static library ---------------------------------------------
add_library(engine_core STATIC
    src/stub.cpp
    src/dsp/RingBuffer.cpp
)

target_include_directories(engine_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# --- Host tests (run on Linux CI, no device) --------------------------------
enable_testing()

add_executable(engine_host_tests
    test/main.cpp
    test/ring_test.cpp
    test/wav_util.cpp
)

target_include_directories(engine_host_tests PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/test
)

# m (libm) is linked for upcoming DSP work (YIN pitch detection) and for
# std::lround in the WAV test util; no harm while unused.
target_link_libraries(engine_host_tests PRIVATE engine_core m)

add_test(NAME engine_host_tests COMMAND engine_host_tests)