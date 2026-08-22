// Regression tests for engine::wav::readWav (engine/src/wav/WavReader.cpp).
//
// Reviewed bug (P0.3.3 fix): a fmt chunk with bitsPerSample in [1, 7] made
// bytesPerSample == 0, then size / bytesPerSample raised SIGFPE (exit 136)
// instead of the documented "descriptive std::runtime_error on any parse
// error". The regression asserts the decoder throws for that layout, and that
// a well-formed 16-bit PCM file still decodes (no over-rejection).

#include "wav_reader_test.h"

#include "engine/wav/WavReader.h"

#include <cstdint>
#include <cstdio>
#include <fstream>
#include <stdexcept>
#include <string>

namespace {

constexpr uint32_t kRiffTag = 0x46464952u;  // "RIFF" little-endian
constexpr uint32_t kWaveTag = 0x45564157u;  // "WAVE"
constexpr uint32_t kFmtTag = 0x20746d66u;   // "fmt "
constexpr uint32_t kDataTag = 0x61746164u;  // "data"

void putLe16(std::ofstream& out, uint16_t v) {
    const char bytes[2] = {static_cast<char>(v & 0xffu),
                           static_cast<char>((v >> 8) & 0xffu)};
    out.write(bytes, 2);
}

void putLe32(std::ofstream& out, uint32_t v) {
    const char bytes[4] = {static_cast<char>(v & 0xffu),
                           static_cast<char>((v >> 8) & 0xffu),
                           static_cast<char>((v >> 16) & 0xffu),
                           static_cast<char>((v >> 24) & 0xffu)};
    out.write(bytes, 4);
}

// Writes a minimal RIFF/WAVE file with the given encoding parameters and a
// small data payload, so the decoder's validation paths can be exercised
// without committing binary fixtures.
bool writeWavWithBits(const std::string& path, uint16_t format,
                      uint16_t numChannels, uint16_t bitsPerSample) {
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        return false;
    }
    const uint32_t dataBytes = 16;
    const uint32_t blockAlign =
        static_cast<uint32_t>(numChannels) * (bitsPerSample / 8u);
    out.write("RIFF", 4);
    putLe32(out, 36u + dataBytes);
    out.write("WAVE", 4);
    out.write("fmt ", 4);
    putLe32(out, 16u);
    putLe16(out, format);
    putLe16(out, numChannels);
    putLe32(out, 22050u);                                        // sample rate
    putLe32(out, 22050u * blockAlign);                           // byte rate
    putLe16(out, static_cast<uint16_t>(blockAlign));             // block align
    putLe16(out, bitsPerSample);
    out.write("data", 4);
    putLe32(out, dataBytes);
    for (uint32_t i = 0; i < dataBytes; ++i) {
        out.put(static_cast<char>(i & 0xffu));
    }
    out.flush();
    return static_cast<bool>(out);
}

// Returns true when readWav throws a runtime_error whose message mentions
// ``needle`` (proving the rejection came from the expected validation path).
bool readThrowsWith(const std::string& path, const char* needle) {
    try {
        (void)engine::wav::readWav(path);
    } catch (const std::runtime_error& error) {
        if (std::string(error.what()).find(needle) != std::string::npos) {
            return true;
        }
        std::fprintf(stderr,
                     "wav_reader: unexpected message for %s: %s\n",
                     path.c_str(), error.what());
        return false;
    }
    std::fprintf(stderr, "wav_reader: %s did not throw\n", path.c_str());
    return false;
}

}  // namespace

bool runWavReaderTests() {
    bool pass = true;

    // Regression: bitsPerSample=4 truncates to zero bytes per frame and used
    // to SIGFPE in size / bytesPerSample. Must throw, must not crash.
    const std::string badBits4 = "wav_reader_bad_bits4.wav";
    if (!writeWavWithBits(badBits4, 1, 1, 4)) {
        std::fprintf(stderr, "wav_reader: cannot write %s\n", badBits4.c_str());
        return false;
    }
    if (!readThrowsWith(badBits4, "bitsPerSample")) {
        pass = false;
    }
    std::remove(badBits4.c_str());

    // Same family: any sub-byte bit depth must be rejected the same way.
    const std::string badBits7 = "wav_reader_bad_bits7.wav";
    if (!writeWavWithBits(badBits7, 1, 1, 7)) {
        std::fprintf(stderr, "wav_reader: cannot write %s\n", badBits7.c_str());
        return false;
    }
    if (!readThrowsWith(badBits7, "bitsPerSample")) {
        pass = false;
    }
    std::remove(badBits7.c_str());

    // Positive control: a well-formed 16-bit PCM file must still decode, so
    // the new guard does not reject valid audio.
    const std::string validPcm = "wav_reader_valid_pcm.wav";
    if (!writeWavWithBits(validPcm, 1, 1, 16)) {
        std::fprintf(stderr, "wav_reader: cannot write %s\n", validPcm.c_str());
        return false;
    }
    try {
        const engine::wav::WavData wav = engine::wav::readWav(validPcm);
        if (wav.sampleRate != 22050 || wav.channels != 1 ||
            wav.samples.size() != 8) {
            std::fprintf(stderr,
                         "wav_reader: valid 16-bit PCM decoded unexpectedly: "
                         "sr=%d channels=%d samples=%zu\n",
                         wav.sampleRate, wav.channels, wav.samples.size());
            pass = false;
        }
    } catch (const std::runtime_error& error) {
        std::fprintf(stderr, "wav_reader: valid 16-bit PCM threw: %s\n",
                     error.what());
        pass = false;
    }
    std::remove(validPcm.c_str());

    std::printf("wav_reader suite: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}
