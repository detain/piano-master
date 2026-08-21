#include "engine/dsp/YinDetector.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <stdexcept>
#include <vector>

// Textbook YIN (de Cheveigne & Kawahara 2002) with a pYIN-style tau
// refinement. The detector only searches periods inside [minFreq, maxFreq],
// and returns pitch == -1 for silence (RMS below the floor), constant/flat
// signals, below-range periods, or degenerate windows so callers never see a
// confident pitch for unpitched audio.
//
// Not thread-safe: YinStream owns a mutable overlap buffer and must be used
// from a single worker thread.

namespace engine::dsp {

namespace {

constexpr double kSilenceRmsFloor = 1e-4;
constexpr double kOctaveRiskDelta = 0.10;

PitchResult silenceResult() {
    return PitchResult{};
}

// Parabolic interpolation around the integer minimum at `tau`. Returns the
// fractional period and the d' value at that point. Falls back to the integer
// tau when the parabola is degenerate (flat or collinear samples).
void interpolateAround(const std::vector<double>& dPrime, std::size_t tau,
                       double& tauOut, double& costOut) {
    const double s0 = dPrime[tau - 1];
    const double s1 = dPrime[tau];
    const double s2 = dPrime[tau + 1];
    tauOut = static_cast<double>(tau);
    costOut = s1;
    const double denominator = s0 - 2.0 * s1 + s2;
    if (denominator != 0.0) {
        const double adjustment = 0.5 * (s0 - s2) / denominator;
        tauOut += adjustment;
        // Value of the fitted parabola at its vertex (the interpolated d').
        costOut = s1 + 0.25 * (s0 - s2) * adjustment;
    }
}

PitchResult detectPitchInWindow(const float* window, std::size_t n,
                                double sampleRate, float threshold,
                                float minFreq, float maxFreq) {
    if (n == 0) {
        return silenceResult();
    }
    if (window == nullptr) {
        throw std::invalid_argument(
            "yinDetect: window must not be null when n > 0");
    }

    double rms = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        rms += static_cast<double>(window[i]) * static_cast<double>(window[i]);
    }
    rms = std::sqrt(rms / static_cast<double>(n));
    if (rms < kSilenceRmsFloor) {
        return silenceResult();
    }

    // Difference function needs tau + 1 samples on both sides of a candidate,
    // so the searchable period range is [minTau, maxTau] with maxTau + 1
    // still inside the window.
    const std::size_t halfWindow = n / 2;
    if (halfWindow < 2) {
        return silenceResult();
    }
    const std::size_t minTau = std::max<std::size_t>(
        1, static_cast<std::size_t>(std::ceil(sampleRate / maxFreq)));
    const std::size_t maxTau = std::min(
        static_cast<std::size_t>(std::floor(sampleRate / minFreq)),
        halfWindow - 1);
    if (maxTau < minTau) {
        return silenceResult();
    }
    const std::size_t tauLimit = std::min(maxTau + 1, halfWindow);

    // 1) Difference function d(tau) = sum (x[j] - x[j+tau])^2.
    std::vector<double> difference(tauLimit + 1, 0.0);
    for (std::size_t tau = 0; tau <= tauLimit; ++tau) {
        double sum = 0.0;
        for (std::size_t j = 0; j < n - tau; ++j) {
            const double diff =
                static_cast<double>(window[j]) -
                static_cast<double>(window[j + tau]);
            sum += diff * diff;
        }
        difference[tau] = sum;
    }

    // 2) Cumulative mean normalized difference d'(tau). Guard: when the
    //    running sum is zero (constant signal) the normalization is undefined,
    //    so treat that tau as maximally different (1.0); d'(0) = 1 by
    //    definition.
    std::vector<double> dPrime(tauLimit + 1, 1.0);
    dPrime[0] = 1.0;
    double runningSum = 0.0;
    for (std::size_t tau = 1; tau <= tauLimit; ++tau) {
        runningSum += difference[tau];
        dPrime[tau] = (runningSum > 0.0)
                          ? difference[tau] * static_cast<double>(tau) / runningSum
                          : 1.0;
    }

    // 3) Absolute threshold: first tau whose d' dips below the threshold;
    //    otherwise the global argmin over the valid period range.
    std::size_t tau0 = minTau;
    bool thresholdMet = false;
    for (std::size_t tau = minTau; tau <= maxTau; ++tau) {
        if (dPrime[tau] < threshold) {
            tau0 = tau;
            thresholdMet = true;
            break;
        }
    }
    if (!thresholdMet) {
        for (std::size_t tau = minTau + 1; tau <= maxTau; ++tau) {
            if (dPrime[tau] < dPrime[tau0]) {
                tau0 = tau;
            }
        }
    }

    // DC/constant-signal guard: when every normalized difference sits at its
    // maximum (1.0) the window is flat — report silence rather than a phantom
    // pitch. (A threshold hit always has d' < threshold < 1.0, so this only
    // fires on the degenerate argmin path.)
    if (dPrime[tau0] >= 1.0) {
        return silenceResult();
    }

    // Boundary honesty: a pick pinned to maxTau means the true period lies at
    // or beyond the low-frequency search boundary — report below-range rather
    // than a confident-but-wrong pitch.
    if (tau0 == maxTau) {
        return silenceResult();
    }

    // 4) Parabolic interpolation around the integer candidate.
    double tauInterp = 0.0;
    double costAtTau = 0.0;
    interpolateAround(dPrime, tau0, tauInterp, costAtTau);

    // 5) pYIN-style refinement: walk downward from the interpolated period
    //    and adopt any lower local minimum of d' (corrects subharmonic picks,
    //    e.g. a threshold hit at 2x the true period).
    double refinedTau = tauInterp;
    double refinedCost = costAtTau;
    const int scanFloor = static_cast<int>(std::floor(tauInterp));
    for (int i = scanFloor; i >= static_cast<int>(minTau); --i) {
        const bool isLocalMinimum =
            dPrime[i] <= dPrime[i - 1] && dPrime[i] <= dPrime[i + 1];
        if (isLocalMinimum && dPrime[i] < refinedCost) {
            double candidateTau = 0.0;
            double candidateCost = 0.0;
            interpolateAround(dPrime, static_cast<std::size_t>(i),
                              candidateTau, candidateCost);
            refinedTau = candidateTau;
            refinedCost = candidateCost;
        }
    }

    // 6) Frequency from the refined period, clamped to the detector range.
    double freqHz = sampleRate / refinedTau;
    if (freqHz < minFreq) {
        freqHz = minFreq;
    }
    if (freqHz > maxFreq) {
        freqHz = maxFreq;
    }
    const int pitch = static_cast<int>(
        std::lround(69.0 + 12.0 * std::log2(freqHz / 440.0)));

    // 7) Confidence from the interpolated d' at the chosen period.
    double confidence = 1.0 - refinedCost;
    confidence = std::clamp(confidence, 0.0, 1.0);

    // 8) Octave-error risk: an octave-up candidate (half the period) is
    //    nearly as good, so the pick is ambiguous.
    bool octaveErrorRisk = false;
    const std::size_t tauHalf =
        static_cast<std::size_t>(std::lround(refinedTau / 2.0));
    if (tauHalf >= minTau && tauHalf <= tauLimit &&
        std::fabs(refinedCost - dPrime[tauHalf]) <= kOctaveRiskDelta) {
        octaveErrorRisk = true;
    }

    return PitchResult{pitch, confidence, freqHz, octaveErrorRisk};
}

}  // namespace

PitchResult yinDetect(const float* window, std::size_t n, double sampleRate,
                      float threshold) {
    const YinConfig defaults;
    return detectPitchInWindow(window, n, sampleRate, threshold,
                               defaults.minFreq, defaults.maxFreq);
}

YinStream::YinStream(YinConfig cfg) : cfg_(cfg) {
    // Fail fast: a stream must be able to advance one window per hop.
    if (cfg_.windowSize == 0 || cfg_.hopSize == 0 ||
        cfg_.hopSize > cfg_.windowSize) {
        throw std::invalid_argument(
            "YinStream requires 0 < hopSize <= windowSize");
    }
}

std::optional<PitchResult> YinStream::process(const float* samples,
                                              std::size_t n) {
    if (n == 0) {
        return std::nullopt;
    }
    if (samples == nullptr) {
        throw std::invalid_argument(
            "YinStream::process: samples must not be null when n > 0");
    }

    overlap_.insert(overlap_.end(), samples, samples + n);

    std::optional<PitchResult> lastResult;
    while (overlap_.size() >= cfg_.windowSize) {
        lastResult = detectPitchInWindow(overlap_.data(), cfg_.windowSize,
                                         cfg_.sampleRate, cfg_.threshold,
                                         cfg_.minFreq, cfg_.maxFreq);
        // Advance by one hop: keep only the tail the next window overlaps.
        const std::size_t consumed = cfg_.hopSize;
        overlap_.erase(overlap_.begin(),
                       overlap_.begin() +
                           static_cast<std::ptrdiff_t>(consumed));
    }
    return lastResult;
}

std::optional<PitchResult> YinStream::finish() {
    if (overlap_.empty()) {
        return std::nullopt;
    }
    // Pad the remaining partial window with zeros so the final detection
    // covers a full window.
    overlap_.resize(cfg_.windowSize, 0.0f);
    const PitchResult result = detectPitchInWindow(
        overlap_.data(), cfg_.windowSize, cfg_.sampleRate, cfg_.threshold,
        cfg_.minFreq, cfg_.maxFreq);
    overlap_.clear();
    return result;
}

}  // namespace engine::dsp
