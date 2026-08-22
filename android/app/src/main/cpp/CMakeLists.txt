# KeyQuest native audio engine (Android).
#
# Builds libkeyquest_engine.so from the platform-portable engine sources that
# P0.2 needs (lock-free ring buffer + Oboe input) plus the Android JNI bridge.
# Oboe arrives via prefab (android/app/build.gradle.kts: buildFeatures.prefab
# + libs.oboe), resolved by find_package below.
#
# The host engine_core build (engine/CMakeLists.txt) deliberately does NOT
# compile OboeInput.cpp: Oboe is Android-only, and the engine core stays
# platform-portable. The YIN detector is also not compiled here yet -- the JNI
# bridge transports NoteEvents only; the detector lands with P0.2.2.

cmake_minimum_required(VERSION 3.22)
project(keyquest_engine LANGUAGES C CXX)

# Repo-root engine sources. The path from this file (android/app/src/main/cpp)
# to the repo root is five levels up:
#   android/app/src/main/cpp -> android/app/src/main -> android/app/src ->
#   android/app -> android -> repo root.
get_filename_component(KEYQUEST_ENGINE_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
set(KEYQUEST_ENGINE_SRC "${KEYQUEST_ENGINE_ROOT}/engine/src")
set(KEYQUEST_ENGINE_INCLUDE "${KEYQUEST_ENGINE_ROOT}/engine/include")

add_library(keyquest_engine SHARED
    ${KEYQUEST_ENGINE_SRC}/dsp/RingBuffer.cpp
    ${KEYQUEST_ENGINE_SRC}/io/OboeInput.cpp
    ${KEYQUEST_ENGINE_SRC}/io/OboeInput.h
    jni_bridge.cpp
)

target_compile_features(keyquest_engine PRIVATE cxx_std_20)

target_include_directories(keyquest_engine PRIVATE
    ${KEYQUEST_ENGINE_INCLUDE}
    ${KEYQUEST_ENGINE_SRC}/io
)

# Oboe via prefab; log + android for __android_log_print and the JNI glue.
find_package(oboe REQUIRED CONFIG)
target_link_libraries(keyquest_engine oboe::oboe log android)

# Treat Oboe's own headers as system headers so third-party warnings can
# never trip our -Werror (prefab imported targets do not carry SYSTEM dirs).
target_include_directories(keyquest_engine SYSTEM PRIVATE
    $<TARGET_PROPERTY:oboe::oboe,INTERFACE_INCLUDE_DIRECTORIES>
)