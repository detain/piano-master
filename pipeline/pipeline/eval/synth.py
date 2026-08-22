"""Synthetic audio + aligned-MIDI generation for harness self-tests.

The tests in ``tests/test_eval_harness.py`` synthesize a clean monophonic
melody (sine with harmonics) and a matching ground-truth MIDI, then prove the
harness scores the oracle at 1.0 and the pyin floor above 0.8 -- all offline.
"""

from __future__ import annotations

from collections.abc import Iterable

import numpy as np
import soundfile as sf

from pipeline.eval.metrics import write_midi_notes

# A note is (midi_pitch, onset_sec, duration_sec).
Note = tuple[int, float, float]


def make_clean_melody() -> list[Note]:
    """A 10-note ascending C-major melody (MIDI 60-76), monophonic and clean."""
    pitches = [60, 62, 64, 65, 67, 69, 71, 72, 74, 76]
    duration = 0.25
    gap = 0.05
    notes: list[Note] = []
    onset = 0.05
    for pitch in pitches:
        notes.append((pitch, onset, duration))
        onset += duration + gap
    return notes


def synthesize_melody(
    notes: Iterable[Note],
    out_wav: str,
    out_midi: str,
    *,
    sr: int = 22050,
    harmonics: tuple[float, ...] = (1.0, 0.5, 0.25),
    attack_sec: float = 0.01,
    release_sec: float = 0.03,
) -> tuple[str, str]:
    """Render a melody to ``out_wav`` and aligned ground truth to ``out_midi``.

    Each note is a sum of decaying harmonics with a short attack/release
    envelope; there is no noise and no reverberation, so pitch tracking is
    near-trivial. Returns ``(wav_path, midi_path)``.
    """
    notes = list(notes)
    total_sec = max(onset + duration for _, onset, duration in notes) + 0.1
    audio = np.zeros(int(total_sec * sr), dtype=np.float64)
    for pitch, onset, duration in notes:
        frequency = 440.0 * 2.0 ** ((pitch - 69) / 12.0)
        start = int(onset * sr)
        length = int(duration * sr)
        if length <= 0:
            continue
        time = np.arange(length) / sr
        signal = np.zeros(length, dtype=np.float64)
        for harmonic, amplitude in enumerate(harmonics, start=1):
            signal += amplitude * np.sin(2.0 * np.pi * frequency * harmonic * time)
        signal *= _envelope(length, attack_sec * sr, release_sec * sr)
        audio[start : start + length] += signal

    sf.write(out_wav, audio.astype(np.float32), sr)
    note_rows = np.asarray(
        [[onset, onset + duration, float(pitch)] for pitch, onset, duration in notes]
    )
    write_midi_notes(out_midi, note_rows)
    return out_wav, out_midi


def _envelope(length: int, attack: float, release: float) -> np.ndarray:
    """Linear attack/sustain/release envelope over ``length`` samples."""
    envelope = np.ones(length, dtype=np.float64)
    attack = max(1, int(attack))
    release = max(1, int(release))
    if attack < length:
        envelope[:attack] = np.linspace(0.0, 1.0, attack)
    if release < length:
        envelope[-release:] *= np.linspace(1.0, 0.0, release)
    return envelope