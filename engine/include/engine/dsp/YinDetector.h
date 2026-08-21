#pragma once
#include <cstddef>
#include <optional>
#include <vector>

struct YinConfig {
  size_t windowSize = 2048;
  size_t hopSize = 1024;
  float threshold = 0.15f;
  double sampleRate = 48000.0;
  float minFreq = 27.5f;
  float maxFreq = 4186.0f;
};

struct PitchResult {
  int pitch = -1;
  double confidence = 0.0;
  double freqHz = 0.0;
  bool octaveErrorRisk = false;
};

PitchResult yinDetect(const float* window, size_t n, double sampleRate, float threshold = 0.15f);

class YinStream {
 public:
  explicit YinStream(YinConfig cfg = {});
  std::optional<PitchResult> process(const float* samples, size_t n);
  std::optional<PitchResult> finish();
 private:
  YinConfig cfg_;
  std::vector<float> overlap_;
};