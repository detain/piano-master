#pragma once

#include <string>
#include <vector>

// Minimal WAV reader/writer for the host test harness ONLY.
// NOT part of libengine_core.

// Reads a RIFF/WAVE file. Supports 16-bit PCM (format 1) and 32-bit IEEE
// float (format 3), little-endian. Multi-channel files are downmixed to mono
// by averaging channels per frame. Returns false on any parse error; on
// success `samples` holds mono float samples in [-1, 1] and `sampleRate` /
// `channels` describe the file (channels is always 1 after downmix).
bool readWav(const std::string& path, std::vector<float>& samples,
             int& sampleRate, int& channels);

// Writes a 16-bit PCM WAV file, little-endian. `samples` must be interleaved
// when `channels` > 1 (1 or 2 supported). Floats are clamped to [-1, 1].
// Returns false on invalid parameters or I/O error.
bool writeWav(const std::string& path, const std::vector<float>& samples,
              int sampleRate, int channels);