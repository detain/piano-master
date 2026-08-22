// engine/tools/yin_cli.cpp
//
// Host baseline: run the engine's streaming YIN detector over a mono WAV and
// emit note events. This is the "engine YIN as the floor" candidate for the
// P0.3.3 bake-off; the pipeline harness shells out to it through
// pipeline/eval/model_wrappers.py::EngineYinWrapper.
//
// Consecutive confident pitched frames (confidence >= --confidence) coalesce
// into notes; a run ends on an unvoiced frame or a pitch change, and adjacent
// same-pitch notes separated by at most one hop are merged back together.
// Notes shorter than --min-ms are discarded. Silence and unconfident frames
// produce no notes.
//
// Output: TSV lines "onset_sec\toffset_sec\tmidi_pitch\tconfidence" on
// stdout, or a JSON array of the same records with --json. Unreadable input
// exits non-zero with a message on stderr.
//
// Usage: yin_cli <input.wav> [--window 2048|4096] [--hop N] [--sr N]
//                [--confidence 0.8] [--min-ms 60] [--json]

#include "engine/dsp/YinDetector.h"
#include "engine/wav/WavReader.h"

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

namespace {

struct Options {
    std::string inputPath;
    std::size_t windowSize = 2048;
    std::size_t hopSize = 0;  // 0 => windowSize / 2 after parsing
    int sampleRate = 0;       // 0 => the WAV's native rate
    double confidence = 0.8;
    double minNoteSec = 0.060;
    bool json = false;
};

struct NoteEvent {
    double onset = 0.0;
    double offset = 0.0;
    int midiPitch = 0;
    double confidence = 0.0;
};

[[noreturn]] void usageError(const std::string& message) {
    std::fprintf(stderr, "yin_cli: %s\n", message.c_str());
    std::fprintf(stderr,
                 "usage: yin_cli <input.wav> [--window 2048|4096] "
                 "[--hop N] [--sr N] [--confidence 0.8] [--min-ms 60] "
                 "[--json]\n");
    std::exit(2);
}

std::size_t parseSize(const std::string& arg, const std::string& value) {
    errno = 0;
    char* end = nullptr;
    const unsigned long parsed = std::strtoul(value.c_str(), &end, 10);
    if (end == value.c_str() || *end != '\0' || errno == ERANGE || parsed == 0) {
        usageError("invalid value for " + arg + ": '" + value + "'");
    }
    return static_cast<std::size_t>(parsed);
}

double parseDouble(const std::string& arg, const std::string& value) {
    char* end = nullptr;
    const double parsed = std::strtod(value.c_str(), &end);
    if (end == value.c_str() || *end != '\0' || !std::isfinite(parsed)) {
        usageError("invalid value for " + arg + ": '" + value + "'");
    }
    return parsed;
}

Options parseArgs(int argc, char** argv) {
    Options opts;
    bool hopGiven = false;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--json") {
            opts.json = true;
        } else if (arg == "--window" || arg == "--hop" || arg == "--sr" ||
                   arg == "--confidence" || arg == "--min-ms") {
            if (i + 1 >= argc) {
                usageError("missing value for " + arg);
            }
            const std::string value = argv[++i];
            if (arg == "--window") {
                opts.windowSize = parseSize(arg, value);
            } else if (arg == "--hop") {
                opts.hopSize = parseSize(arg, value);
                hopGiven = true;
            } else if (arg == "--sr") {
                const std::size_t parsed = parseSize(arg, value);
                if (parsed > static_cast<std::size_t>(INT_MAX)) {
                    usageError("sample rate too large: '" + value + "'");
                }
                opts.sampleRate = static_cast<int>(parsed);
            } else if (arg == "--confidence") {
                opts.confidence = parseDouble(arg, value);
            } else {
                opts.minNoteSec = parseDouble(arg, value) / 1000.0;
            }
        } else if (!arg.empty() && arg[0] == '-') {
            usageError("unknown option '" + arg + "'");
        } else {
            if (!opts.inputPath.empty()) {
                usageError("multiple input files given");
            }
            opts.inputPath = arg;
        }
    }
    if (opts.inputPath.empty()) {
        usageError("missing input WAV path");
    }
    if (opts.windowSize == 0) {
        usageError("window size must be positive");
    }
    if (hopGiven) {
        if (opts.hopSize == 0 || opts.hopSize > opts.windowSize) {
            usageError("hop size must be in [1, window]");
        }
    } else {
        opts.hopSize = opts.windowSize / 2;
    }
    if (opts.confidence < 0.0 || opts.confidence > 1.0) {
        usageError("confidence must be in [0, 1]");
    }
    if (opts.sampleRate < 0) {
        usageError("sample rate must be positive");
    }
    if (opts.minNoteSec < 0.0) {
        usageError("min-ms must be non-negative");
    }
    return opts;
}

// Deterministic linear-interpolation resampler for host tooling. This is
// documented as crude: the engine has no proper resampler yet, and the
// pipeline wrapper pre-resamples with librosa (the same path as the pyin
// baseline) when fair cross-model comparison matters. yin_cli only needs
// --sr for standalone use on arbitrary WAVs.
std::vector<float> resampleLinear(const std::vector<float>& samples,
                                  int inRate, int outRate) {
    if (inRate == outRate || samples.empty()) {
        return samples;
    }
    const double ratio = static_cast<double>(inRate) / static_cast<double>(outRate);
    const std::size_t outLength = static_cast<std::size_t>(
        std::floor(static_cast<double>(samples.size()) / ratio));
    std::vector<float> out(outLength);
    for (std::size_t i = 0; i < outLength; ++i) {
        const double position = static_cast<double>(i) * ratio;
        const std::size_t lower = static_cast<std::size_t>(position);
        const std::size_t upper = std::min(lower + 1, samples.size() - 1);
        const double fraction = position - static_cast<double>(lower);
        out[i] = static_cast<float>((1.0 - fraction) * samples[lower] +
                                    fraction * samples[upper]);
    }
    return out;
}

std::vector<NoteEvent> runDetector(const std::vector<float>& samples,
                                   int sampleRate, const Options& opts) {
    engine::dsp::YinConfig config;
    config.windowSize = opts.windowSize;
    config.hopSize = opts.hopSize;
    config.sampleRate = static_cast<double>(sampleRate);
    engine::dsp::YinStream stream(config);

    const double hopSec =
        static_cast<double>(config.hopSize) / static_cast<double>(sampleRate);

    std::vector<NoteEvent> notes;
    NoteEvent current{};
    bool active = false;

    const auto closeNote = [&]() {
        if (active) {
            notes.push_back(current);
            active = false;
        }
    };

    const auto feed = [&](const engine::dsp::PitchResult& result,
                          double frameStartSec) {
        const bool voiced =
            result.pitch >= 0 && result.confidence >= opts.confidence;
        if (!voiced) {
            closeNote();
            return;
        }
        // A frame's temporal bin is [start, start + hop) -- the analysis
        // window is support, not duration, so the offset extends by one hop
        // (the same convention the pyin baseline uses).
        const double frameEndSec = frameStartSec + hopSec;
        if (active && result.pitch == current.midiPitch) {
            current.offset = frameEndSec;
            current.confidence = std::max(current.confidence, result.confidence);
        } else {
            closeNote();
            // Onset debounce: the note starts at the frame that CONFIRMED the
            // pitch (the second consecutive voiced frame of this pitch, one
            // hop after the first). A single transition-frame flip must not
            // start a note early.
            current = NoteEvent{frameStartSec + hopSec, frameEndSec,
                                result.pitch, result.confidence};
            active = true;
        }
    };

    std::size_t frameIndex = 0;
    const std::size_t hop = config.hopSize;
    for (std::size_t offset = 0; offset < samples.size(); offset += hop) {
        const std::size_t n = std::min(hop, samples.size() - offset);
        if (auto result = stream.process(samples.data() + offset, n)) {
            feed(*result, static_cast<double>(frameIndex) * hopSec);
            ++frameIndex;
        }
    }
    if (auto result = stream.finish()) {
        feed(*result, static_cast<double>(frameIndex) * hopSec);
    }
    closeNote();

    // Merge adjacent same-pitch notes separated by at most one hop (a single
    // dropped or unvoiced frame), so brief confidence dips do not split one
    // sustained note into two.
    std::vector<NoteEvent> merged;
    for (NoteEvent& note : notes) {
        if (!merged.empty() && note.midiPitch == merged.back().midiPitch &&
            note.onset - merged.back().offset <= hopSec) {
            merged.back().offset = note.offset;
            merged.back().confidence =
                std::max(merged.back().confidence, note.confidence);
        } else {
            merged.push_back(note);
        }
    }

    std::vector<NoteEvent> result;
    for (const NoteEvent& note : merged) {
        if (note.offset - note.onset >= opts.minNoteSec) {
            result.push_back(note);
        }
    }
    return result;
}

void printTsv(const std::vector<NoteEvent>& notes) {
    for (const NoteEvent& note : notes) {
        std::printf("%.6f\t%.6f\t%d\t%.4f\n", note.onset, note.offset,
                    note.midiPitch, note.confidence);
    }
}

void printJson(const std::vector<NoteEvent>& notes) {
    std::printf("[");
    for (std::size_t i = 0; i < notes.size(); ++i) {
        if (i > 0) {
            std::printf(",");
        }
        std::printf(
            "\n  {\"onset\": %.6f, \"offset\": %.6f, \"midi_pitch\": %d, "
            "\"confidence\": %.4f}",
            notes[i].onset, notes[i].offset, notes[i].midiPitch,
            notes[i].confidence);
    }
    std::printf(notes.empty() ? "]\n" : "\n]\n");
}

}  // namespace

int main(int argc, char** argv) {
    try {
        const Options opts = parseArgs(argc, argv);
        engine::wav::WavData wav = engine::wav::readWav(opts.inputPath);
        const int sampleRate =
            opts.sampleRate != 0 ? opts.sampleRate : wav.sampleRate;
        const std::vector<float> samples =
            sampleRate == wav.sampleRate
                ? std::move(wav.samples)
                : resampleLinear(wav.samples, wav.sampleRate, sampleRate);
        const std::vector<NoteEvent> notes = runDetector(samples, sampleRate, opts);
        if (opts.json) {
            printJson(notes);
        } else {
            printTsv(notes);
        }
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "yin_cli: %s\n", error.what());
        return 1;
    }
}