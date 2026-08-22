#pragma once
#include <cstddef>
#include <optional>
#include <vector>

namespace engine::dsp {

// Pitch-detection configuration for the YIN detector.
//
// Standard 2048/48kHz resolves reliably ≥ ~52 Hz (G#1+); notes below ~43 Hz
// (≈A0..E1) are refused by boundary honesty; F1–G1 sit in a gray zone
// resolved by interpolation extrapolation with a ragged low edge (G1 can read
// one semitone flat). Route the below-standard range (MIDI 21–42, A0..F#2)
// through lowFreq mode (windowSize 4096); standard handles MIDI 43+.
struct YinConfig {
  std::size_t windowSize = 2048;
  std::size_t hopSize = 1024;
  float threshold = 0.15f;
  double sampleRate = 48000.0;
  float minFreq = 27.5f;
  float maxFreq = 4186.0f;
};

// One-shot pitch estimate.
//
// CONTRACT: pitch == -1 means no pitch detected (silence, below-range, or
// degenerate input). Always gate on confidence — a caller checking only
// pitch != -1 can see phantom notes.
struct PitchResult {
  int pitch = -1;
  double confidence = 0.0;
  double freqHz = 0.0;
  bool octaveErrorRisk = false;
};

// One-shot YIN/pYIN detection over a full window of samples.
// Throws std::invalid_argument when window is null and n > 0.
PitchResult yinDetect(const float* window, std::size_t n, double sampleRate,
                      float threshold = 0.15f);

// Streaming YIN detector: feed overlapping audio via process(), then call
// finish() to drain the final partial window. Not thread-safe.
class YinStream {
 public:
  explicit YinStream(YinConfig cfg = {});
  std::optional<PitchResult> process(const float* samples, std::size_t n);
  std::optional<PitchResult> finish();

 private:
  YinConfig cfg_;
  std::vector<float> overlap_;
};

}  // namespace engine::dsp
