#include "wav_util.h"

#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>

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

bool readExactly(std::ifstream& in, void* buffer, std::size_t n) {
    return static_cast<bool>(
        in.read(static_cast<char*>(buffer), static_cast<std::streamsize>(n)));
}

}  // namespace

bool readWav(const std::string& path, std::vector<float>& samples,
             int& sampleRate, int& channels) {
    samples.clear();

    std::ifstream in(path, std::ios::binary);
    if (!in) {
        std::cerr << "readWav: cannot open " << path << "\n";
        return false;
    }

    uint8_t header[12];
    if (!readExactly(in, header, sizeof(header))) {
        std::cerr << "readWav: truncated RIFF header\n";
        return false;
    }
    if (readLe32(header) != kRiffTag || readLe32(header + 8) != kWaveTag) {
        std::cerr << "readWav: not a RIFF/WAVE file\n";
        return false;
    }

    bool haveFmt = false;
    uint16_t format = 0;
    uint16_t numChannels = 0;
    uint32_t sampleRateRaw = 0;
    uint16_t bitsPerSample = 0;

    while (true) {
        uint8_t chunkHeader[8];
        if (!readExactly(in, chunkHeader, sizeof(chunkHeader))) {
            break;  // clean EOF between chunks
        }
        const uint32_t tag = readLe32(chunkHeader);
        const uint32_t size = readLe32(chunkHeader + 4);

        if (tag == kFmtTag) {
            uint8_t fmt[16];
            if (size < sizeof(fmt) || !readExactly(in, fmt, sizeof(fmt))) {
                std::cerr << "readWav: malformed fmt chunk\n";
                return false;
            }
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
                std::cerr << "readWav: data chunk before fmt chunk\n";
                return false;
            }
            const uint32_t bytesPerSample =
                static_cast<uint32_t>(numChannels) * (bitsPerSample / 8u);
            if (bytesPerSample == 0) {
                std::cerr << "readWav: zero bytes per sample\n";
                return false;
            }
            const uint32_t numFrames = size / bytesPerSample;

            std::vector<uint8_t> raw(size);
            if (!readExactly(in, raw.data(), raw.size())) {
                std::cerr << "readWav: truncated data chunk\n";
                return false;
            }

            samples.resize(numFrames);
            if (format == kFormatPcm && bitsPerSample == 16) {
                for (uint32_t frame = 0; frame < numFrames; ++frame) {
                    float sum = 0.0f;
                    for (uint32_t ch = 0; ch < numChannels; ++ch) {
                        const uint8_t* p =
                            raw.data() + (frame * numChannels + ch) * 2u;
                        const int16_t s = static_cast<int16_t>(readLe16(p));
                        sum += static_cast<float>(s) / 32768.0f;
                    }
                    samples[frame] = sum / static_cast<float>(numChannels);
                }
            } else if (format == kFormatFloat && bitsPerSample == 32) {
                for (uint32_t frame = 0; frame < numFrames; ++frame) {
                    float sum = 0.0f;
                    for (uint32_t ch = 0; ch < numChannels; ++ch) {
                        const uint8_t* p =
                            raw.data() + (frame * numChannels + ch) * 4u;
                        const uint32_t bits = readLe32(p);
                        float f;
                        std::memcpy(&f, &bits, sizeof(f));
                        sum += f;
                    }
                    samples[frame] = sum / static_cast<float>(numChannels);
                }
            } else {
                std::cerr << "readWav: unsupported format " << format << " / "
                          << bitsPerSample << " bits\n";
                return false;
            }

            sampleRate = static_cast<int>(sampleRateRaw);
            channels = 1;  // mono after downmix (1 channel stays 1)
            return true;
        } else {
            // Unknown chunk: skip it and keep scanning for data.
            in.seekg(static_cast<std::streamoff>(size), std::ios::cur);
        }
    }

    std::cerr << "readWav: no data chunk found\n";
    return false;
}

bool writeWav(const std::string& path, const std::vector<float>& samples,
              int sampleRate, int channels) {
    if (sampleRate <= 0 || channels < 1 || channels > 2) {
        std::cerr << "writeWav: invalid sampleRate (" << sampleRate
                  << ") or channels (" << channels << ")\n";
        return false;
    }

    const uint32_t numChannels = static_cast<uint32_t>(channels);
    const uint32_t numFrames =
        static_cast<uint32_t>(samples.size() / numChannels);
    const uint32_t dataBytes = numFrames * numChannels * 2u;
    const uint32_t riffSize = 36u + dataBytes;

    std::ofstream out(path, std::ios::binary);
    if (!out) {
        std::cerr << "writeWav: cannot open " << path << " for writing\n";
        return false;
    }

    auto putLe16 = [&out](uint16_t v) {
        const char bytes[2] = {static_cast<char>(v & 0xffu),
                               static_cast<char>((v >> 8) & 0xffu)};
        out.write(bytes, 2);
    };
    auto putLe32 = [&out](uint32_t v) {
        const char bytes[4] = {static_cast<char>(v & 0xffu),
                               static_cast<char>((v >> 8) & 0xffu),
                               static_cast<char>((v >> 16) & 0xffu),
                               static_cast<char>((v >> 24) & 0xffu)};
        out.write(bytes, 4);
    };

    out.write("RIFF", 4);
    putLe32(riffSize);
    out.write("WAVE", 4);
    out.write("fmt ", 4);
    putLe32(16u);                     // fmt chunk size
    putLe16(kFormatPcm);              // 16-bit PCM
    putLe16(numChannels);
    putLe32(static_cast<uint32_t>(sampleRate));
    putLe32(static_cast<uint32_t>(sampleRate) * numChannels * 2u);  // byte rate
    putLe16(static_cast<uint16_t>(numChannels * 2u));               // block align
    putLe16(16u);                                                   // bits
    out.write("data", 4);
    putLe32(dataBytes);

    for (float s : samples) {
        const float clamped = s < -1.0f ? -1.0f : (s > 1.0f ? 1.0f : s);
        const int32_t v = static_cast<int32_t>(std::lround(clamped * 32767.0f));
        putLe16(static_cast<uint16_t>(v & 0xffff));
    }

    out.flush();
    return static_cast<bool>(out);
}