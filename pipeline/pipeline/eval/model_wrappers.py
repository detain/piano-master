"""Model wrappers for the offline evaluation harness (plan §20 P0.3.1).

A wrapper is anything implementing the ``ModelWrapper`` protocol: it turns an
audio file into a note estimate ``(N, 3)`` array ``[onset_sec, offset_sec,
midi_pitch]`` plus a metadata dict, given a target sample rate and the
evaluation config.

The wrappers here form the P0.3.x baseline zoo:

- ``GroundTruthWrapper``  -- the oracle; echoes the aligned MIDI back as the
  estimate. Every metric must score perfectly against it.
- ``PyinBaselineWrapper`` -- the monophonic floor (plan §5.3). Primary engine
  is ``librosa.pyin``; a compact numpy-only textbook YIN backs it up when
  librosa is unavailable.
- ``EngineYinWrapper``    -- the engine's C++ YIN baseline (P0.3.3): shells
  out to the ``engine/tools/yin_cli`` host executable, which runs the same
  streaming detector the app will use.
- ``BasicPitchWrapper``   -- optional Spotify Basic Pitch, for the published
  MAESTRO calibration (F1 ~0.82). Requires ``basic-pitch`` (tensorflow); the
  import is lazy so the rest of the harness stays lightweight.
"""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Protocol, runtime_checkable

import numpy as np
import soundfile as sf

from pipeline.eval.metrics import MetricConfig, load_midi_notes


@runtime_checkable
class ModelWrapper(Protocol):
    """Protocol every wrapper satisfies: audio in, ``(notes, metadata)`` out."""

    name: str

    def predict_notes(
        self, audio_path: str, sr: int, config: MetricConfig
    ) -> tuple[np.ndarray, dict]:
        """Estimate notes from ``audio_path`` at sample rate ``sr``.

        Returns ``((N, 3) [onset, offset, midi_pitch] array, metadata dict)``.
        """
        ...


# ---------------------------------------------------------------------------
# Ground-truth oracle
# ---------------------------------------------------------------------------


class GroundTruthWrapper:
    """Echoes the aligned ground-truth MIDI as the estimation.

    Used by self-tests to prove the plumbing: a perfect oracle must yield
    perfect metrics (F1 = 1.0, zero onset error, zero octave error, full
    chord recall).
    """

    name = "ground-truth"

    def __init__(self, midi_path: str) -> None:
        self.midi_path = midi_path

    def predict_notes(
        self, audio_path: str, sr: int, config: MetricConfig
    ) -> tuple[np.ndarray, dict]:
        notes = load_midi_notes(self.midi_path)
        return notes, {"wrapper": self.name, "midi_path": self.midi_path}


# ---------------------------------------------------------------------------
# Monophonic pitch floor (librosa.pyin, numpy YIN fallback)
# ---------------------------------------------------------------------------


class PyinBaselineWrapper:
    """Monophonic pitch floor: thresholded pitch frames segmented into notes.

    This is the placeholder floor for plan §5.3; the engine's C++ YIN baseline
    replaces it in the P0.3.3 bake-off. Parameters are in samples and are
    tuned for the default ``sr`` of 22050 (frame 2048 ~= 93 ms, hop 512 ~=
    23 ms). Confidence-thresholded voiced frames are grouped into notes of at
    least ``min_note_sec`` seconds whose pitch does not drift more than
    ``midi_tolerance`` semitones.
    """

    name = "pyin-baseline"

    def __init__(
        self,
        *,
        fmin: float = 27.5,
        fmax: float = 4186.0,
        hop_length: int = 512,
        frame_length: int = 2048,
        confidence_threshold: float = 0.7,
        min_note_sec: float = 0.06,
        midi_tolerance: float = 0.5,
    ) -> None:
        self.fmin = fmin
        self.fmax = fmax
        self.hop_length = hop_length
        self.frame_length = frame_length
        self.confidence_threshold = confidence_threshold
        self.min_note_sec = min_note_sec
        self.midi_tolerance = midi_tolerance

    def predict_notes(
        self, audio_path: str, sr: int, config: MetricConfig
    ) -> tuple[np.ndarray, dict]:
        y, native_sr = sf.read(audio_path, always_2d=False, dtype="float32")
        if y.ndim > 1:
            y = y.mean(axis=1)
        if native_sr != sr:
            y = _resample(y, native_sr, sr)
        try:
            times, f0 = self._pitch_track_librosa(y, sr)
            engine = "librosa.pyin"
        except ImportError:
            # Fallback exists only for the case librosa is missing; genuine
            # errors from librosa.pyin (bad audio, parameter conflicts) must
            # propagate loudly instead of being masked by the numpy floor.
            times, f0 = self._pitch_track_yin(y, sr)
            engine = "numpy-yin"
        notes = _pitch_track_to_notes(
            times,
            f0,
            sr,
            self.hop_length,
            self.min_note_sec,
            self.midi_tolerance,
        )
        metadata = {
            "wrapper": self.name,
            "engine": engine,
            "sr": sr,
            "n_notes": len(notes),
        }
        return notes, metadata

    def _pitch_track_librosa(self, y: np.ndarray, sr: int) -> tuple[np.ndarray, np.ndarray]:
        """Pitch track via librosa.pyin; unvoiced frames become ``nan``."""
        import librosa

        f0, _, voiced_probs = librosa.pyin(
            y,
            fmin=self.fmin,
            fmax=self.fmax,
            sr=sr,
            frame_length=self.frame_length,
            hop_length=self.hop_length,
        )
        times = librosa.frames_to_time(
            np.arange(len(f0)), sr=sr, hop_length=self.hop_length
        )
        f0 = f0.astype(float)
        f0[voiced_probs < self.confidence_threshold] = np.nan
        return times, f0

    def _pitch_track_yin(self, y: np.ndarray, sr: int) -> tuple[np.ndarray, np.ndarray]:
        """Textbook YIN pitch track in numpy (fallback when librosa is absent)."""
        return _numpy_yin_pitch_track(
            y,
            sr,
            self.fmin,
            self.fmax,
            self.hop_length,
            self.frame_length,
            self.confidence_threshold,
        )


# ---------------------------------------------------------------------------
# Engine C++ YIN baseline (shells out to engine/tools/yin_cli)
# ---------------------------------------------------------------------------


def default_yin_cli_path() -> Path:
    """Default ``yin_cli`` binary path: ``<repo-root>/engine/build/yin_cli``.

    ``pipeline/`` sits three levels below the repo root, so the wrapper can
    find the engine build without any configuration. ``KEYQUEST_YIN_CLI``
    overrides it (see ``EngineYinWrapper``).
    """
    return Path(__file__).resolve().parents[3] / "engine" / "build" / "yin_cli"


class EngineYinWrapper:
    """Engine C++ YIN baseline for the P0.3.3 bake-off.

    Shells out to ``engine/tools/yin_cli`` -- the host executable that runs
    the engine's streaming YIN detector over a mono WAV and emits notes as
    TSV or JSON -- so the harness scores the exact detector the app will use
    (no re-implementation drift). Audio is read and resampled with the same
    librosa path as ``PyinBaselineWrapper`` so the cross-model comparison is
    apples-to-apples.

    The binary path comes from ``KEYQUEST_YIN_CLI`` or defaults to
    ``<repo-root>/engine/build/yin_cli``. A missing binary raises a
    descriptive ``RuntimeError`` at construction -- fail fast, fail loud;
    a silent empty estimate would poison a bake-off run.
    """

    name = "engine-yin"

    def __init__(
        self,
        *,
        window_size: int = 2048,
        hop_size: int | None = None,
        confidence: float = 0.8,
        min_note_ms: float = 60.0,
        binary: str | os.PathLike[str] | None = None,
    ) -> None:
        self.window_size = window_size
        self.hop_size = hop_size
        self.confidence = confidence
        self.min_note_ms = min_note_ms
        self.binary = self._resolve_binary(binary)
        # In-process cache keyed by (audio path, mtime, sr, params): corpus
        # runs re-score the same audio with the same settings repeatedly.
        self._cache: dict[tuple, tuple[np.ndarray, dict]] = {}

    @staticmethod
    def _resolve_binary(
        binary: str | os.PathLike[str] | None,
    ) -> Path:
        if binary is None:
            binary = os.environ.get("KEYQUEST_YIN_CLI") or default_yin_cli_path()
        resolved = Path(binary)
        if not resolved.is_file():
            raise RuntimeError(
                f"yin_cli binary not found at {resolved}. Build it with "
                "`cmake -S engine -B engine/build -DCMAKE_BUILD_TYPE=Release "
                "&& cmake --build engine/build --target yin_cli`, or point "
                "KEYQUEST_YIN_CLI at the built executable."
            )
        return resolved

    def predict_notes(
        self, audio_path: str, sr: int, config: MetricConfig
    ) -> tuple[np.ndarray, dict]:
        cache_key = self._cache_key(audio_path, sr)
        cached = self._cache.get(cache_key)
        if cached is not None:
            return cached

        y, native_sr = sf.read(audio_path, always_2d=False, dtype="float32")
        if y.ndim > 1:
            y = y.mean(axis=1)
        if native_sr != sr:
            y = _resample(y, native_sr, sr)
        notes, metadata = self._run_yin_cli(y, sr)
        self._cache[cache_key] = (notes, metadata)
        return notes, metadata

    def _cache_key(self, audio_path: str, sr: int) -> tuple:
        mtime = os.path.getmtime(audio_path)
        return (
            audio_path,
            mtime,
            sr,
            self.window_size,
            self.hop_size,
            self.confidence,
            self.min_note_ms,
            str(self.binary),
        )

    def _run_yin_cli(self, y: np.ndarray, sr: int) -> tuple[np.ndarray, dict]:
        """Run ``yin_cli`` on ``y`` at ``sr`` and parse its JSON note list."""
        cmd = [
            str(self.binary),
            "--sr",
            str(sr),
            "--window",
            str(self.window_size),
            "--confidence",
            str(self.confidence),
            "--min-ms",
            str(self.min_note_ms),
            "--json",
        ]
        if self.hop_size is not None:
            cmd += ["--hop", str(self.hop_size)]

        with tempfile.TemporaryDirectory() as tmp_dir:
            wav_path = os.path.join(tmp_dir, "input.wav")
            sf.write(wav_path, y, sr)
            result = subprocess.run(
                cmd + [wav_path], capture_output=True, text=True, check=False
            )
            if result.returncode != 0:
                raise RuntimeError(
                    f"yin_cli failed (exit {result.returncode}) on {wav_path}: "
                    f"{result.stderr.strip()}"
                )
            try:
                events = json.loads(result.stdout)
            except json.JSONDecodeError as exc:
                raise RuntimeError(
                    f"yin_cli produced unparseable JSON on {wav_path}: {exc}"
                ) from exc

        notes = np.asarray(
            [[event["onset"], event["offset"], event["midi_pitch"]] for event in events],
            dtype=float,
        )
        if notes.size == 0:
            notes = np.zeros((0, 3))
        metadata = {
            "wrapper": self.name,
            "engine": "engine-yin",
            "binary": str(self.binary),
            "sr": sr,
            "window_size": self.window_size,
            "hop_size": self.hop_size,
            "confidence": self.confidence,
            "min_note_ms": self.min_note_ms,
            "n_notes": len(notes),
        }
        return notes, metadata


def _resample(y: np.ndarray, orig_sr: int, target_sr: int) -> np.ndarray:
    """Resample mono audio (librosa when available, scipy otherwise)."""
    if orig_sr == target_sr:
        return y
    try:
        import librosa

        return librosa.resample(y, orig_sr=orig_sr, target_sr=target_sr)
    except ImportError:
        from math import gcd

        from scipy.signal import resample_poly

        g = gcd(orig_sr, target_sr)
        return resample_poly(y, target_sr // g, orig_sr // g).astype(np.float32)


# ---------------------------------------------------------------------------
# numpy-only textbook YIN (plan §5.3 fallback floor)
# ---------------------------------------------------------------------------


def _numpy_yin_pitch_track(
    y: np.ndarray,
    sr: int,
    fmin: float,
    fmax: float,
    hop_length: int,
    frame_length: int,
    threshold: float,
) -> tuple[np.ndarray, np.ndarray]:
    """Sliding-window YIN pitch tracker; unvoiced frames are ``nan``."""
    tau_min = max(1, int(sr / fmax))
    tau_max = max(tau_min + 1, int(sr / fmin))
    n_frames = max(0, (len(y) - frame_length) // hop_length + 1)
    times = np.arange(n_frames) * (hop_length / sr)
    f0 = np.full(n_frames, np.nan)
    for frame_idx in range(n_frames):
        start = frame_idx * hop_length
        frame = y[start : start + frame_length].astype(float)
        difference = _yin_difference_function(frame, tau_max)
        normalized = _cumulative_mean_normalized_difference(difference)
        tau = _yin_choose_tau(normalized, tau_min, threshold)
        if tau is not None:
            f0[frame_idx] = sr / tau
    return times, f0


def _yin_difference_function(x: np.ndarray, tau_max: int) -> np.ndarray:
    """d(tau) = sum_t (x[t] - x[t+tau])^2 for tau in 1..tau_max (exact).

    Uses the linear autocorrelation (FFT of the zero-padded window) plus
    cumulative energies, so every lag is exact -- no circular wraparound.
    """
    window = len(x)
    cumsum_sq = np.concatenate(([0.0], np.cumsum(x * x)))
    spectrum = np.fft.rfft(x, 2 * window)
    autocorr = np.fft.irfft(spectrum * np.conj(spectrum), 2 * window)[:window]
    taus = np.arange(1, tau_max + 1)
    left_energy = cumsum_sq[window - taus] - cumsum_sq[0]
    right_energy = cumsum_sq[window] - cumsum_sq[taus]
    return left_energy + right_energy - 2.0 * autocorr[taus]


def _cumulative_mean_normalized_difference(d: np.ndarray) -> np.ndarray:
    """d'(tau) = d(tau) / (mean of d(1..tau)); the YIN CMND curve."""
    cumulative = np.cumsum(d)
    mean = cumulative / np.arange(1, len(d) + 1)
    return d / np.maximum(mean, np.finfo(float).eps)


def _yin_choose_tau(
    cmnd: np.ndarray, tau_min: int, threshold: float
) -> float | None:
    """First dip below ``threshold`` (parabolic-interpolated), else global min."""
    taus = np.arange(1, len(cmnd) + 1)
    valid = taus >= tau_min
    candidate_mask = valid & (cmnd < threshold)
    if np.any(candidate_mask):
        tau = float(taus[np.argmax(candidate_mask)])
    elif np.any(valid):
        tau = float(taus[np.argmin(np.where(valid, cmnd, np.inf))])
    else:
        return None
    index = round(tau)
    if 1 <= index < len(cmnd):
        alpha, beta, gamma = cmnd[index - 1], cmnd[index], cmnd[index + 1]
        denominator = 2.0 * (2.0 * beta - gamma - alpha)
        if abs(denominator) > np.finfo(float).eps:
            tau = tau + (gamma - alpha) / denominator
    return tau


def _pitch_track_to_notes(
    times: np.ndarray,
    f0: np.ndarray,
    sr: int,
    hop_length: int,
    min_note_sec: float,
    midi_tolerance: float,
) -> np.ndarray:
    """Segment a pitch track into ``(N, 3)`` notes.

    A note is a run of voiced frames whose MIDI pitch stays within
    ``midi_tolerance`` semitones of the run's first frame; runs shorter than
    ``min_note_sec`` are discarded. The note pitch is the median frame pitch,
    the onset is the first frame time and the offset the end of the last
    frame.
    """
    notes: list[list[float]] = []
    n_frames = len(f0)
    index = 0
    while index < n_frames:
        if not np.isfinite(f0[index]):
            index += 1
            continue
        run_midis: list[float] = [_hz_to_midi(f0[index])]
        run_end = index + 1
        while run_end < n_frames and np.isfinite(f0[run_end]):
            midi = _hz_to_midi(f0[run_end])
            if abs(midi - run_midis[0]) > midi_tolerance:
                break
            run_midis.append(midi)
            run_end += 1
        onset = float(times[index])
        offset = float(times[run_end - 1]) + hop_length / sr
        if offset - onset >= min_note_sec:
            notes.append([onset, offset, float(round(np.median(run_midis)))])
        index = run_end
    if not notes:
        return np.zeros((0, 3))
    return np.asarray(notes, dtype=float)


def _hz_to_midi(frequency: float) -> float:
    """Frequency in Hz to a continuous MIDI pitch (A4 = 440 Hz)."""
    return 69.0 + 12.0 * np.log2(frequency / 440.0)


# ---------------------------------------------------------------------------
# Optional published-baseline wrapper (Spotify Basic Pitch)
# ---------------------------------------------------------------------------


class BasicPitchWrapper:
    """Spotify Basic Pitch -- the published MAESTRO baseline for calibration.

    Basic Pitch reports note-level F1 ~0.82 and onset error ~0.052 s on
    MAESTRO. The import is lazy and raises a descriptive error when
    ``basic-pitch`` is missing: it pins ``tensorflow<2.15.1``, which has no
    Python 3.12 wheels, so it must be run in a Python < 3.12 environment.
    """

    name = "basic-pitch"

    def predict_notes(
        self, audio_path: str, sr: int, config: MetricConfig
    ) -> tuple[np.ndarray, dict]:
        try:
            from basic_pitch.inference import predict
        except ImportError as exc:
            raise RuntimeError(
                "basic-pitch is not installed. Install it with "
                "`pip install basic-pitch` in a Python < 3.12 environment: "
                "it pins tensorflow<2.15.1, which ships no Python 3.12 wheels."
            ) from exc
        _, _, note_events = predict(audio_path)
        notes = np.asarray(
            [[onset, offset, pitch] for onset, offset, pitch, _ in note_events],
            dtype=float,
        )
        if notes.size == 0:
            notes = np.zeros((0, 3))
        return notes, {"wrapper": self.name, "n_notes": len(notes)}