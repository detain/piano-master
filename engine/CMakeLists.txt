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
    src/dsp/YinDetector.cpp
)

target_include_directories(engine_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# m (libm) is required by the YIN pitch detector (std::log2/std::lround) and
# by std::lround in the WAV test util. Carried PUBLIC so every consumer of
# engine_core (host tests, Android NDK) links it transitively.
target_link_libraries(engine_core PUBLIC m)

# --- Host tests (run on Linux CI, no device) --------------------------------
enable_testing()

# Deterministic pitch fixtures (midi_021.wav .. midi_108.wav) are generated at
# build time by a committed stdlib-only Python script; binaries are never
# committed (they live under the build dir, which is git-ignored).
find_package(Python3 COMPONENTS Interpreter REQUIRED)

set(PITCH_FIXTURES_DIR ${CMAKE_BINARY_DIR}/fixtures)
add_custom_command(
    OUTPUT ${PITCH_FIXTURES_DIR}/midi_108.wav
    COMMAND ${CMAKE_COMMAND} -E make_directory ${PITCH_FIXTURES_DIR}
    COMMAND ${Python3_EXECUTABLE}
            ${CMAKE_CURRENT_SOURCE_DIR}/test/tools/gen_pitch_fixtures.py
            ${PITCH_FIXTURES_DIR}
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/test/tools/gen_pitch_fixtures.py
    COMMENT "Generating deterministic pitch fixtures (midi_021.wav..midi_108.wav)"
    VERBATIM
)
add_custom_target(engine_pitch_fixtures ALL
    DEPENDS ${PITCH_FIXTURES_DIR}/midi_108.wav
)

add_executable(engine_host_tests
    test/main.cpp
    test/ring_test.cpp
    test/wav_util.cpp
    test/pitch_test.cpp
)

target_include_directories(engine_host_tests PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/test
)

# m comes transitively from engine_core's PUBLIC link.
target_link_libraries(engine_host_tests PRIVATE engine_core)

target_compile_definitions(engine_host_tests PRIVATE
    PITCH_FIXTURES_DIR="${PITCH_FIXTURES_DIR}"
)

add_dependencies(engine_host_tests engine_pitch_fixtures)

add_test(NAME engine_host_tests COMMAND engine_host_tests)
