#!/usr/bin/env python3
"""Deterministic pitch fixture generator for the engine host tests (P0.2-A3).

Writes one 16-bit PCM mono WAV per MIDI note in [--low, --high], named
midi_<nnn>.wav (e.g. midi_021.wav for A0 .. midi_108.wav for C8).

Synthesis (fully deterministic — no randomness, no external packages):

    sampleRate  = 48000 Hz
    duration    = 1.0 s  (48000 samples)
    fade        = 10 ms raised-cosine in/out (480 samples each)
    f(midi)     = 440 * 2^((midi - 69) / 12)

    s[i] = fade(i) * ( A1 * sin(2*pi*f*i/sr)          # fundamental, 0 dB
                     + A2 * sin(2*pi*2*f*i/sr)        # 2nd partial, -6 dB
                     + A3 * sin(2*pi*3*f*i/sr) )      # 3rd partial, -12 dB

    A1 = 0.5, A2 = 0.25, A3 = 0.125  ->  peak <= 0.875, no clipping
    (harmonic levels are RELATIVE to the fundamental: -6 dB / -12 dB)

The 2nd/3rd partials make the fixtures representative of piano-like spectra
(the 3rd partial cancels at exactly one third of the fundamental period, so
the detector cannot cheat by locking onto a pure-sine subharmonic).

Output is deterministic: same arguments -> byte-identical WAV files.

Usage:
    gen_pitch_fixtures.py <outdir> [--low 21] [--high 108]
"""

import argparse
import math
import os
import struct
import sys

SAMPLE_RATE = 48000
DURATION_S = 1.0
FADE_S = 0.010  # 10 ms raised-cosine fade in/out
AMP_FUND = 0.5
AMP_2ND = 0.25  # -6 dB relative
AMP_3RD = 0.125  # -12 dB relative
PCM16_MAX = 32767

DEFAULT_LOW = 21  # A0, 27.5 Hz
DEFAULT_HIGH = 108  # C8, 4186 Hz


def midi_freq(midi: int) -> float:
    return 440.0 * (2.0 ** ((midi - 69) / 12.0))


def raised_cosine_fade(num_fade_samples: int, index: int) -> float:
    """Raised-cosine window: 0 at index 0, ~1 at index num-1 (continuous)."""
    return 0.5 * (1.0 - math.cos(math.pi * index / num_fade_samples))


def synthesize(midi: int) -> list[float]:
    """Return the mono float samples for one sustained note."""
    num_samples = int(round(DURATION_S * SAMPLE_RATE))
    fade = int(round(FADE_S * SAMPLE_RATE))
    freq = midi_freq(midi)
    omega = 2.0 * math.pi * freq / SAMPLE_RATE

    samples = [0.0] * num_samples
    for i in range(num_samples):
        envelope = 1.0
        if i < fade:
            envelope = raised_cosine_fade(fade, i)
        elif i >= num_samples - fade:
            envelope = raised_cosine_fade(fade, num_samples - 1 - i)

        phase = omega * i
        value = (
            AMP_FUND * math.sin(phase)
            + AMP_2ND * math.sin(2.0 * phase)
            + AMP_3RD * math.sin(3.0 * phase)
        )
        samples[i] = envelope * value
    return samples


def write_wav16(path: str, samples: list[float], sample_rate: int) -> None:
    """Write a mono 16-bit PCM little-endian WAV (RIFF/WAVE)."""
    num_frames = len(samples)
    data_bytes = num_frames * 2
    riff_size = 36 + data_bytes

    with open(path, "wb") as out:
        out.write(b"RIFF")
        out.write(struct.pack("<I", riff_size))
        out.write(b"WAVE")
        out.write(b"fmt ")
        out.write(struct.pack("<IHHIIHH", 16, 1, 1, sample_rate,
                              sample_rate * 2, 2, 16))
        out.write(b"data")
        out.write(struct.pack("<I", data_bytes))
        for value in samples:
            clamped = max(-1.0, min(1.0, value))
            pcm = int(round(clamped * PCM16_MAX))
            out.write(struct.pack("<h", pcm))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Generate deterministic MIDI-note WAV fixtures.")
    parser.add_argument("outdir", help="directory to write midi_<nnn>.wav files")
    parser.add_argument("--low", type=int, default=DEFAULT_LOW,
                        help="first MIDI note (default %(default)s)")
    parser.add_argument("--high", type=int, default=DEFAULT_HIGH,
                        help="last MIDI note (default %(default)s)")
    args = parser.parse_args(argv)

    if not 0 <= args.low <= args.high <= 127:
        print(f"error: need 0 <= low ({args.low}) <= high ({args.high}) <= 127",
              file=sys.stderr)
        return 1

    os.makedirs(args.outdir, exist_ok=True)
    for midi in range(args.low, args.high + 1):
        path = os.path.join(args.outdir, f"midi_{midi:03d}.wav")
        write_wav16(path, synthesize(midi), SAMPLE_RATE)
    print(f"wrote {args.high - args.low + 1} fixtures to {args.outdir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
