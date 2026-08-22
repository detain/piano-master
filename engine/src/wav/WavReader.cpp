#include "engine/wav/WavReader.h"

#include <cstdint>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace engine::wav {

namespace {

constexpr uint32_t kRiffTag = 0x46464952u;  // "RIFF" little-endian
constexpr uint32_t kWaveTag = 0x45564157u;  // "WAVE"
constexpr uint32_t kFmtTag = 0x20746d66u;   // "fmt "
constexpr uint32_t kDataTag = 0x61746164u;  // "data"
constexpr uint16_t kFormatPcm = 1;
constexpr uint16_t kFormatFloat = 3;

uint16_t readLe16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) |
           static_cast<uint16_t>(static_cast<uint16_t>(p[1]) << 8);
}

uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

void readExactly(std::ifstream& in, void* buffer, std::size_t n,
                 const std::string& path, const char* what) {
    if (!in.read(static_cast<char*>(buffer),
                 static_cast<std::streamsize>(n))) {
        throw std::runtime_error(path + ": " + what);
    }
}

}  // namespace

WavData readWav(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        throw std::runtime_error(path + ": cannot open file");
    }

    uint8_t header[12];
    readExactly(in, header, sizeof(header), path, "truncated RIFF header");
    if (readLe32(header) != kRiffTag || readLe32(header + 8) != kWaveTag) {
        throw std::runtime_error(path + ": not a RIFF/WAVE file");
    }

    bool haveFmt = false;
    uint16_t format = 0;
    uint16_t numChannels = 0;
    uint32_t sampleRateRaw = 0;
    uint16_t bitsPerSample = 0;

    while (true) {
        uint8_t chunkHeader[8];
        if (!in.read(reinterpret_cast<char*>(chunkHeader),
                     sizeof(chunkHeader))) {
            break;  // clean EOF between chunks
        }
        const uint32_t tag = readLe32(chunkHeader);
        const uint32_t size = readLe32(chunkHeader + 4);

        if (tag == kFmtTag) {
            uint8_t fmt[16];
            if (size < sizeof(fmt)) {
                throw std::runtime_error(path + ": malformed fmt chunk");
            }
            readExactly(in, fmt, sizeof(fmt), path, "truncated fmt chunk");
            format = readLe16(fmt);
            numChannels = readLe16(fmt + 2);
            sampleRateRaw = readLe32(fmt + 4);
            bitsPerSample = readLe16(fmt + 14);
            haveFmt = true;
            if (size > sizeof(fmt)) {
                in.seekg(static_cast<std::streamoff>(size - sizeof(fmt)),
                         std::ios::cur);
            }
        } else if (tag == kDataTag) {
            if (!haveFmt) {
                throw std::runtime_error(path + ": data chunk before fmt chunk");
            }
            if (numChannels == 0 || bitsPerSample == 0) {
                throw std::runtime_error(path + ": invalid channel/bit layout");
            }
            const uint32_t bytesPerSample =
                static_cast<uint32_t>(numChannels) * (bitsPerSample / 8u);
            const uint32_t numFrames = size / bytesPerSample;

            WavData wav;
            wav.sampleRate = static_cast<int>(sampleRateRaw);
            wav.channels = 1;  // mono after the per-frame downmix
            wav.samples.reserve(numFrames);

            if (format == kFormatPcm && bitsPerSample == 16) {
                std::vector<uint8_t> frame(bytesPerSample);
                for (uint32_t frameIdx = 0; frameIdx < numFrames; ++frameIdx) {
                    readExactly(in, frame.data(), frame.size(), path,
                                "truncated data chunk");
                    float sum = 0.0f;
                    for (uint32_t ch = 0; ch < numChannels; ++ch) {
                        const int16_t s = static_cast<int16_t>(
                            readLe16(frame.data() + ch * 2u));
                        sum += static_cast<float>(s) / 32768.0f;
                    }
                    wav.samples.push_back(sum / static_cast<float>(numChannels));
                }
            } else if (format == kFormatFloat && bitsPerSample == 32) {
                std::vector<uint8_t> frame(bytesPerSample);
                for (uint32_t frameIdx = 0; frameIdx < numFrames; ++frameIdx) {
                    readExactly(in, frame.data(), frame.size(), path,
                                "truncated data chunk");
                    float sum = 0.0f;
                    for (uint32_t ch = 0; ch < numChannels; ++ch) {
                        const uint32_t bits = readLe32(frame.data() + ch * 4u);
                        float f;
                        std::memcpy(&f, &bits, sizeof(f));
                        sum += f;
                    }
                    wav.samples.push_back(sum / static_cast<float>(numChannels));
                }
            } else {
                throw std::runtime_error(
                    path + ": unsupported encoding format " +
                    std::to_string(format) + " / " +
                    std::to_string(bitsPerSample) +
                    " bits (expected 16-bit PCM or 32-bit float)");
            }
            return wav;
        } else {
            // Unknown chunk: skip it and keep scanning for the data chunk.
            in.seekg(static_cast<std::streamoff>(size), std::ios::cur);
        }
    }

    throw std::runtime_error(path + ": no data chunk found");
}

}  // namespace engine::wav