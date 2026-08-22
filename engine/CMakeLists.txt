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
    src/wav/WavReader.cpp
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
# The generator writes zero-padded three-digit names (midi_021.wav .. midi_108.wav).
# Declare ALL 88 files as OUTPUTs of the custom command so deleting any single
# fixture triggers regeneration; the generator is deterministic, so a rerun is
# idempotent.
set(PITCH_FIXTURE_FILES)
foreach(midi RANGE 21 108)
    if(midi LESS 100)
        set(_fixture "midi_0${midi}.wav")
    else()
        set(_fixture "midi_${midi}.wav")
    endif()
    list(APPEND PITCH_FIXTURE_FILES ${PITCH_FIXTURES_DIR}/${_fixture})
endforeach()

add_custom_command(
    OUTPUT ${PITCH_FIXTURE_FILES}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${PITCH_FIXTURES_DIR}
    COMMAND ${Python3_EXECUTABLE}
            ${CMAKE_CURRENT_SOURCE_DIR}/test/tools/gen_pitch_fixtures.py
            ${PITCH_FIXTURES_DIR}
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/test/tools/gen_pitch_fixtures.py
    COMMENT "Generating deterministic pitch fixtures (midi_021.wav..midi_108.wav)"
    VERBATIM
)
add_custom_target(engine_pitch_fixtures ALL
    DEPENDS ${PITCH_FIXTURE_FILES}
)

add_executable(engine_host_tests
    test/main.cpp
    test/ring_test.cpp
    test/note_queue_test.cpp
    test/wav_util.cpp
    test/wav_reader_test.cpp
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

# --- yin_cli: engine YIN baseline for the pipeline bake-off (P0.3.3) --------
# A small host executable that reads a mono WAV and emits note events via the
# engine's streaming YIN detector. The pipeline harness shells out to it
# (pipeline/eval/model_wrappers.py::EngineYinWrapper); it is deliberately NOT
# installed -- it exists only for host evaluation.
add_executable(yin_cli tools/yin_cli.cpp)
target_link_libraries(yin_cli PRIVATE engine_core)

# The smoke test needs the generated pitch fixtures even when building only
# the yin_cli target.
add_dependencies(yin_cli engine_pitch_fixtures)

# Regression: the A4 fixture (midi_069.wav) must emit at least one note with
# MIDI pitch 69. Run with --json and match the plain-text key so the regex
# needs no tab/whitespace classes (CTest's regex engine is ECMAScript).
add_test(NAME yin_cli_midi_069
    COMMAND yin_cli ${PITCH_FIXTURES_DIR}/midi_069.wav --json
)
set_tests_properties(yin_cli_midi_069 PROPERTIES
    PASS_REGULAR_EXPRESSION "\"midi_pitch\": 69"
)
