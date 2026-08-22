#pragma once

#include <string>
#include <vector>

namespace engine::wav {

// Read-only WAV decoder for host tooling (yin_cli now, the P1.3.5 host
// harness later). Promoted out of the test-only wav_util because the bake-off
// CLI and future host tooling both need a shared, warning-clean decoder that
// does not depend on the test directory.
//
// Supported encodings: 16-bit PCM (format 1) and 32-bit IEEE float
// (format 3), little-endian. Multi-channel files are downmixed to mono by
// averaging channels per frame. This is deliberately read-only -- the test
// harness keeps its own writer in test/wav_util.{h,cpp}.
struct WavData {
    std::vector<float> samples;  // mono float samples in [-1, 1]
    int sampleRate = 0;          // frames per second from the fmt chunk
    int channels = 1;            // always 1 after the mono downmix
};

// Decodes ``path`` into mono float samples. Throws ``std::runtime_error``
// with a descriptive message on any parse or I/O error (unreadable file,
// truncated header, unsupported encoding, missing data chunk) -- fail fast,
// fail loud.
WavData readWav(const std::string& path);

}  // namespace engine::wav