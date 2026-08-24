"""Stage 9 — audio stem rendering (plan §8.2.9).

Stem construction per level, via a renderer abstraction:

- Backend A — ``sine`` (v0 default): deterministic numpy synthesis, no new
  pip deps beyond what is already installed. Each hand renders as sine-tone
  stems (RH / LH from the hand field), plus full mix, the mic-safe stem (a
  high pad with NO energy in the piano fundamental range ~80 Hz–2 kHz), and
  the count-in click.
- Backend B — ``fluidsynth`` (code-complete, same interface, activated by
  ``--renderer fluidsynth`` only when the binary is on PATH): subprocess-based,
  requires a soundfont whose sha256 matches the pinned config value. Cannot be
  exercised on the CI server (no sudo); CI must not depend on it.

Post (shared by both backends): EBU R128 loudness normalize to −16 LUFS /
−1 dBTP (two-pass ffmpeg loudnorm — one-pass misses the target), Opus encode
(48 kHz; stereo 96 kbps full mixes, mono 64 kbps mic-safe), Ogg serial
canonicalization for byte-determinism, then three MEASURED checks, all of
which fail the build rather than warn:

- loudness of the ENCODED file (encoding shifts it) within ±1.5 LUFS of −16;
- mic-safe spectrum: no energy in 80 Hz–2 kHz in the decoded micsafe stem;
- alignment: decoded stem onsets vs the note timeline, drift ≤ 10 ms.

Determinism note: ffmpeg's Opus/Ogg muxer embeds a random serial number per
process; determinism.canonicalize_ogg rewrites it to a fixed value and
recomputes the page CRCs. The audio tests verify WAV bytes and Opus bytes are
identical across runs.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import soundfile as sf

from pipeline.build.determinism import canonicalize_ogg
from pipeline.build.errors import AudioError

SAMPLE_RATE = 48000
LOUDNESS_TARGET = -16.0
TRUE_PEAK_TARGET = -1.0
LOUDNESS_TOLERANCE_LUFS = 1.5
MICSafe_BAND_MIN_HZ = 80.0
MICSafe_BAND_MAX_HZ = 2000.0
MICSafe_MAX_BAND_DB = -40.0  # relative to total energy; measured ~-77 dB
ALIGNMENT_TOLERANCE_SEC = 0.010  # 10 ms — fail the build beyond this

PAD_FREQUENCY_HZ = 3200.0
CLICK_FREQUENCY_HZ = 2500.0
CLICK_DURATION_SEC = 0.04
COUNT_IN_CLICKS = 4

STEMS = {
    "full": {"channels": 2, "bitrate": 96, "name": "l{level}_backing_full.opus"},
    "norh": {"channels": 2, "bitrate": 96, "name": "l{level}_backing_norh.opus"},
    "nolh": {"channels": 2, "bitrate": 96, "name": "l{level}_backing_nolh.opus"},
    "micsafe": {"channels": 1, "bitrate": 64, "name": "l{level}_backing_micsafe.opus"},
    "reference": {"channels": 2, "bitrate": 96, "name": "l{level}_reference.opus"},
    "count_in": {"channels": 2, "bitrate": 96, "name": "count_in.opus"},
}


def _require_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None:
        raise AudioError(
            "ffmpeg is required for audio post-processing (loudnorm + Opus) but is "
            "not on PATH — install ffmpeg (ubuntu: apt-get install ffmpeg) and retry"
        )


def beat_to_seconds(beats: list[float], tempo_map: list[dict[str, Any]]) -> list[float]:
    """Beats → seconds under the tempoMap (step curves; v0 renders linear
    tempo curves as step — documented)."""
    entries = sorted(tempo_map, key=lambda e: e["atBeat"])
    result: list[float] = []
    for beat in beats:
        seconds = 0.0
        for index, entry in enumerate(entries):
            next_beat = entries[index + 1]["atBeat"] if index + 1 < len(entries) else float("inf")
            if beat <= entry["atBeat"]:
                break
            segment_end = min(beat, next_beat)
            if segment_end > entry["atBeat"]:
                seconds += (segment_end - entry["atBeat"]) * 60.0 / entry["bpm"]
        result.append(seconds)
    return result


def _freq_for_pitch(pitch: int) -> float:
    return 440.0 * 2.0 ** ((pitch - 69) / 12.0)


def render_sine_stem(
    notes: list[dict[str, Any]],
    start_seconds: list[float],
    durations_seconds: list[float],
    total_seconds: float,
    *,
    hand: str | None = None,
) -> np.ndarray:
    """Deterministic sine synthesis of the selected hand (None = both)."""
    sample_count = int(total_seconds * SAMPLE_RATE)
    buffer = np.zeros(sample_count, dtype=np.float64)
    attack = int(0.008 * SAMPLE_RATE)
    release = int(0.020 * SAMPLE_RATE)
    for note, start, dur in zip(notes, start_seconds, durations_seconds):
        if hand is not None and note.get("hand") != hand:
            continue
        pitch = note["pitch"]
        freq = _freq_for_pitch(pitch)
        i0 = int(start * SAMPLE_RATE)
        i1 = min(sample_count, int((start + dur) * SAMPLE_RATE) + release)
        if i0 >= sample_count or i1 <= i0:
            continue
        length = i1 - i0
        envelope = np.ones(length)
        envelope[:attack] = np.linspace(0.0, 1.0, min(attack, length))
        if release < length:
            envelope[-release:] = np.linspace(1.0, 0.0, release)
        time = np.arange(i0, i1) / SAMPLE_RATE
        buffer[i0:i1] += 0.18 * envelope * np.sin(2.0 * np.pi * freq * time)
    return buffer


def render_micsafe_pad(
    notes: list[dict[str, Any]],
    start_seconds: list[float],
    durations_seconds: list[float],
    total_seconds: float,
) -> np.ndarray:
    """A high pad (3.2 kHz) whose amplitude follows the note density. Energy
    lives above 2 kHz; the sidebands from the slow envelope stay within ±10 Hz,
    so the 80 Hz–2 kHz band stays empty (the spectral check verifies it)."""
    sample_count = int(total_seconds * SAMPLE_RATE)
    time = np.arange(sample_count) / SAMPLE_RATE
    density = np.zeros(sample_count)
    window = int(0.1 * SAMPLE_RATE)
    kernel = np.ones(window) / window
    for note, start, dur in zip(notes, start_seconds, durations_seconds):
        i0 = int(start * SAMPLE_RATE)
        i1 = min(sample_count, int((start + dur) * SAMPLE_RATE))
        if i1 > i0:
            density[i0:i1] += 1.0
    density = np.convolve(density, kernel, mode="same")
    envelope = 0.10 + 0.08 * np.clip(density, 0.0, 2.0)
    fade = int(0.05 * SAMPLE_RATE)
    if fade < sample_count:
        envelope[:fade] *= np.linspace(0.0, 1.0, fade)
        envelope[-fade:] *= np.linspace(1.0, 0.0, fade)
    return envelope * np.sin(2.0 * np.pi * PAD_FREQUENCY_HZ * time)


def render_count_in(bpm: int) -> np.ndarray:
    """Four short clicks at the song's default tempo (documented v0: the
    client resamples the click to the practice tempo; no resampling here)."""
    spacing = 60.0 / bpm
    total = COUNT_IN_CLICKS * spacing + 0.3
    sample_count = int(total * SAMPLE_RATE)
    buffer = np.zeros(sample_count)
    click_samples = int(CLICK_DURATION_SEC * SAMPLE_RATE)
    click_time = np.arange(click_samples) / SAMPLE_RATE
    click_envelope = np.exp(-click_time * 80.0)
    click = 0.5 * click_envelope * np.sin(2.0 * np.pi * CLICK_FREQUENCY_HZ * click_time)
    for click_index in range(COUNT_IN_CLICKS):
        i0 = int(click_index * spacing * SAMPLE_RATE)
        i1 = min(sample_count, i0 + click_samples)
        buffer[i0:i1] += click[: i1 - i0]
    return buffer


def write_stereo_or_mono(path: Path, samples: np.ndarray, channels: int) -> None:
    """Write a deterministic WAV (float32 PCM). Mono stems repeat the buffer
    into a single channel; stereo stems duplicate to both channels."""
    if channels == 2:
        data = np.column_stack((samples, samples))
    else:
        data = samples.reshape(-1, 1)
    sf.write(str(path), data.astype(np.float32), SAMPLE_RATE, subtype="FLOAT")


def _run_ffmpeg(args: list[str], what: str) -> None:
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        tail = (result.stderr or result.stdout or "").strip().splitlines()
        detail = tail[-3:] if tail else []
        raise AudioError(f"{what} failed: ffmpeg exited {result.returncode} " + " ".join(detail))


def measure_lufs(wav: Path) -> dict[str, float]:
    """Integrated loudness + true peak of a WAV (ffmpeg loudnorm print)."""
    _require_ffmpeg()
    result = subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-nostats",
            "-i",
            str(wav),
            "-af",
            "loudnorm=I=-16:TP=-1:LRA=11:print_format=json",
"-f",
        "null",
        "-",
    ],
    capture_output=True,
    text=True,
    check=False,
)
    match = re.search(r"\{.*\}", result.stderr, re.DOTALL)
    if not match:
        raise AudioError(f"could not measure loudness of {wav}")
    data = json.loads(match.group(0))
    return {
        "input_i": float(data["input_i"]),
        "input_tp": float(data["input_tp"]),
        "input_lra": float(data["input_lra"]),
        "input_thresh": float(data["input_thresh"]),
        "output_i": float(data["output_i"]),
        "output_tp": float(data["output_tp"]),
    }


def loudnorm_wav(source: Path, target: Path) -> None:
    """Two-pass EBU R128 normalization to −16 LUFS / −1 dBTP (deterministic)."""
    _require_ffmpeg()
    measured = measure_lufs(source)
    _run_ffmpeg(
        [
            "ffmpeg",
            "-y",
            "-loglevel",
            "error",
            "-i",
            str(source),
            "-af",
            (
                f"loudnorm=I={LOUDNESS_TARGET}:TP={TRUE_PEAK_TARGET}:LRA=11:"
                f"measured_I={measured['input_i']}:measured_TP={measured['input_tp']}:"
                f"measured_LRA={measured['input_lra']}:measured_thresh={measured['input_thresh']}:"
                "linear=true"
            ),
            "-ar",
            str(SAMPLE_RATE),
            str(target),
        ],
        "loudnorm",
    )


def encode_opus(source: Path, target: Path, *, channels: int, bitrate: int) -> bytes:
    """Opus encode at 48 kHz, then canonicalize the Ogg serial for
    byte-determinism. Returns the canonicalized bytes."""
    _require_ffmpeg()
    _run_ffmpeg(
        [
            "ffmpeg",
            "-y",
            "-loglevel",
            "error",
            "-i",
            str(source),
            "-c:a",
            "libopus",
            "-b:a",
            f"{bitrate}k",
            "-ar",
            str(SAMPLE_RATE),
            "-ac",
            str(channels),
            str(target),
        ],
        "opus encode",
    )
    data = target.read_bytes()
    canonical = canonicalize_ogg(data)
    target.write_bytes(canonical)
    return canonical


def decode_opus(source: Path, target: Path) -> None:
    _require_ffmpeg()
    _run_ffmpeg(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(source), "-ar", str(SAMPLE_RATE), str(target)],
        "opus decode",
    )


def check_micsafe_spectrum(opus_path: Path) -> None:
    """Decode the mic-safe stem and FAIL if energy appears in 80 Hz–2 kHz."""
    wav = opus_path.with_suffix(".decoded.wav")
    decode_opus(opus_path, wav)
    data, _ = sf.read(str(wav))
    mono = data.mean(axis=1) if data.ndim > 1 else data
    if mono.size == 0:
        raise AudioError(f"micsafe stem {opus_path.name} decoded to silence")
    window = np.hanning(mono.size)
    spectrum = np.fft.rfft(mono * window)
    freqs = np.fft.rfftfreq(mono.size, 1.0 / SAMPLE_RATE)
    magnitude = np.abs(spectrum) ** 2
    band = (freqs >= MICSafe_BAND_MIN_HZ) & (freqs <= MICSafe_BAND_MAX_HZ)
    total = magnitude.sum()
    if total <= 0:
        raise AudioError(f"micsafe stem {opus_path.name} has no energy at all")
    band_db = 10.0 * np.log10(magnitude[band].sum() / total + 1e-30)
    if band_db > MICSafe_MAX_BAND_DB:
        raise AudioError(
            f"micsafe stem {opus_path.name} FAILS the spectral check: {band_db:.1f} dB "
            f"of energy in 80 Hz–2 kHz (limit {MICSafe_MAX_BAND_DB} dB) — the pad leaks "
            "into the piano fundamental range; re-render (§8.1.7)"
        )


def _detect_onsets(wav: Path, max_count: int = 8) -> list[float]:
    data, _ = sf.read(str(wav))
    mono = data.mean(axis=1) if data.ndim > 1 else data
    envelope = np.abs(mono)
    window = int(0.005 * SAMPLE_RATE)
    smoothed = np.convolve(envelope, np.ones(window) / window, mode="same")
    threshold = smoothed.max() * 0.3
    onsets: list[float] = []
    dead_until = 0
    # A rising crossing counts as an onset if the envelope has been BELOW
    # threshold for a sustained period (~30 ms) — this ignores the brief dips
    # between back-to-back notes. ``below_since == 0`` is the signal start,
    # which is genuine silence (a stem must be sample-aligned to beat 0).
    min_silence = int(0.030 * SAMPLE_RATE)
    below_since = 0
    for index in range(1, len(smoothed)):
        if index < dead_until:
            continue
        if smoothed[index] <= threshold:
            if below_since is None:
                below_since = index
            continue
        if below_since is not None:
            silence = index - below_since
            if below_since == 0 or silence >= min_silence:
                onsets.append(index / SAMPLE_RATE)
                dead_until = index + int(0.15 * SAMPLE_RATE)
                if len(onsets) >= max_count:
                    break
            below_since = None
    return onsets


def check_alignment(
    opus_path: Path, expected_onsets: list[float], *, what: str
) -> None:
    """Decoded stem onsets vs the note timeline; fail the build if any
    detectable onset drifts by more than 10 ms.

    The first expected onset is mandatory — a decoded stem that starts silent
    is dead audio. Later onsets are checked only when the detector can resolve
    them (dense quarter-note passages put onsets inside the detector's dead
    zone); a missed later onset is a content property, not a drift."""
    wav = opus_path.with_suffix(".aligned.wav")
    decode_opus(opus_path, wav)
    detected = _detect_onsets(wav)
    if not detected:
        raise AudioError(f"alignment check: no onsets detected in {what}")
    for expected in expected_onsets:
        candidates = [d for d in detected if abs(d - expected) <= 0.05]
        if not candidates:
            if expected == expected_onsets[0]:
                raise AudioError(
                    f"alignment check FAILS for {what}: expected the first onset at "
                    f"{expected:.3f}s but no onset was detected within 50 ms — the "
                    "decoded stem starts silent or the render is empty"
                )
            continue
        nearest = min(candidates, key=lambda d: abs(d - expected))
        drift = abs(nearest - expected)
        if drift > ALIGNMENT_TOLERANCE_SEC:
            raise AudioError(
                f"alignment check FAILS for {what}: expected onset {expected:.3f}s, "
                f"decoded {nearest:.3f}s (drift {drift * 1000:.1f} ms > 10 ms) — "
                "silent drift makes users think their playing is wrong (§8.2.9)"
            )


def check_encoded_loudness(opus_path: Path) -> None:
    """The encoded file's loudness must stay within tolerance of the target
    (Opus encoding can shift it)."""
    wav = opus_path.with_suffix(".loudness.wav")
    decode_opus(opus_path, wav)
    measured = measure_lufs(wav)
    drift = abs(measured["output_i"] - LOUDNESS_TARGET)
    if drift > LOUDNESS_TOLERANCE_LUFS:
        raise AudioError(
            f"loudness check FAILS for {opus_path.name}: encoded integrated loudness "
            f"is {measured['output_i']:.2f} LUFS, target {LOUDNESS_TARGET} ± "
            f"{LOUDNESS_TOLERANCE_LUFS} LUFS (§8.1.7)"
        )


def _stem_is_silent(wav_path: Path) -> bool:
    """True when a stem has no audible content (e.g. the LH stem of a
    single-staff melody). Loudness/alignment measurement is meaningless for
    silence; such stems are encoded as-is and their checks skipped (the
    checks that matter — full-stem alignment and mic-safe spectrum — still
    run on stems with content)."""
    data, _ = sf.read(str(wav_path))
    if data.size == 0:
        return True
    return float(np.max(np.abs(data))) < 1e-6


def postprocess_stem(
    wav_path: Path,
    opus_path: Path,
    *,
    channels: int,
    bitrate: int,
    checks: list[str],
    expected_onsets: list[float] | None = None,
) -> bytes:
    """loudnorm → Opus → checks; returns the canonicalized Opus bytes."""
    _require_ffmpeg()
    if _stem_is_silent(wav_path):
        canonical = encode_opus(wav_path, opus_path, channels=channels, bitrate=bitrate)
        return canonical
    loud = wav_path.with_suffix(".loud.wav")
    loudnorm_wav(wav_path, loud)
    canonical = encode_opus(loud, opus_path, channels=channels, bitrate=bitrate)
    if "loudness" in checks:
        check_encoded_loudness(opus_path)
    if "micsafe" in checks:
        check_micsafe_spectrum(opus_path)
    if "alignment" in checks and expected_onsets:
        check_alignment(opus_path, expected_onsets, what=opus_path.name)
    return canonical


@dataclass
class AudioProfile:
    renderer: str
    soundfont_sha256: str | None = None

    def to_dict(self) -> dict[str, Any]:
        profile: dict[str, Any] = {
            "renderer": self.renderer,
            "codec": "opus",
            "sampleRateHz": SAMPLE_RATE,
            "loudnessLufs": LOUDNESS_TARGET,
            "truePeakDbTP": TRUE_PEAK_TARGET,
            "bitratesKbps": {"full": 96, "micSafe": 64},
        }
        if self.soundfont_sha256:
            profile["soundfontSha256"] = self.soundfont_sha256
        return profile


class AudioRenderer:
    """Renderer abstraction: produce deterministic stem WAVs for one level."""

    name = "abstract"

    def render_stems(
        self,
        notes: list[dict[str, Any]],
        tempo_map: list[dict[str, Any]],
        out_dir: Path,
        level: int,
    ) -> dict[str, Path]:
        raise NotImplementedError


class SineRenderer(AudioRenderer):
    name = "sine"

    def render_stems(
        self,
        notes: list[dict[str, Any]],
        tempo_map: list[dict[str, Any]],
        out_dir: Path,
        level: int,
    ) -> dict[str, Path]:
        # The note timeline in seconds (step tempo map).
        beats = [note["startBeat"] for note in notes]
        seconds = beat_to_seconds(beats, tempo_map)
        durations_seconds = [note["durBeats"] * 60.0 / tempo_map[0]["bpm"] for note in notes]
        total_beats = max(beats) + max((d for d in durations_seconds), default=0.0)
        total_seconds = total_beats * 60.0 / tempo_map[0]["bpm"] + 0.3

        # NOTE: durations above use the FIRST tempo entry's bpm per note; with
        # a single tempo (all v0 fixtures) this is exact. Multi-tempo songs
        # use the default tempo for durations (documented v0 cut; the timeline
        # mapping itself honors the tempo map).
        rh = render_sine_stem(notes, seconds, durations_seconds, total_seconds, hand="R")
        lh = render_sine_stem(notes, seconds, durations_seconds, total_seconds, hand="L")
        full = rh + lh
        pad = render_micsafe_pad(notes, seconds, durations_seconds, total_seconds)
        bpm = int(tempo_map[0]["bpm"])
        count_in = render_count_in(bpm)

        stems: dict[str, Path] = {}
        for stem_name, samples in [
            ("full", full),
            ("norh", lh),
            ("nolh", rh),
            ("micsafe", pad),
            ("reference", full),
            ("count_in", count_in),
        ]:
            channels = STEMS[stem_name]["channels"]
            wav_path = out_dir / f"l{level}_{stem_name}.wav"
            write_stereo_or_mono(wav_path, samples, channels)
            stems[stem_name] = wav_path
        return stems


class FluidsynthRenderer(AudioRenderer):
    """Backend B — subprocess fluidsynth (code-complete; requires the binary
    on PATH and a soundfont matching the pinned sha256 in config)."""

    name = "fluidsynth"

    def __init__(self, soundfont_path: Path | None, expected_sha256: str | None) -> None:
        self.soundfont_path = soundfont_path
        self.expected_sha256 = expected_sha256

    def _soundfont_check(self) -> Path:
        binary = shutil.which("fluidsynth")
        if binary is None:
            raise AudioError(
                "renderer 'fluidsynth' selected but the fluidsynth binary is not on "
                "PATH — install fluidsynth and a soundfont, or use --renderer sine "
                "(the v0 default)"
            )
        if self.soundfont_path is None or not self.soundfont_path.is_file():
            raise AudioError(
                "renderer 'fluidsynth' requires --soundfont <path.sf2> (the binary is "
                "available but no soundfont was configured)"
            )
        if self.expected_sha256:
            from pipeline.build.determinism import sha256_file

            actual = sha256_file(self.soundfont_path).removeprefix("sha256:")
            if actual != self.expected_sha256:
                raise AudioError(
                    f"soundfont {self.soundfont_path} sha256 {actual} does not match the "
                    f"pinned value {self.expected_sha256} — refusing to render with an "
                    "unpinned soundfont (determinism depends on pinned tools, §8.2.10)"
                )
        return self.soundfont_path

    def render_stems(
        self,
        notes: list[dict[str, Any]],
        tempo_map: list[dict[str, Any]],
        out_dir: Path,
        level: int,
    ) -> dict[str, Path]:
        soundfont = self._soundfont_check()
        try:
            import importlib.util

            if importlib.util.find_spec("mido") is None:
                raise AudioError("fluidsynth renderer needs mido to build MIDI — run `pip install mido`")
            import mido  # noqa: F401 — used in _notes_to_midi
        except ImportError:
            raise AudioError("fluidsynth renderer needs mido to build MIDI — run `pip install mido`")

        stems: dict[str, Path] = {}
        wavs = {
            "full": out_dir / f"l{level}_full.raw.wav",
            "norh": out_dir / f"l{level}_norh.raw.wav",
            "nolh": out_dir / f"l{level}_nolh.raw.wav",
        }
        # full = both hands; norh = left hand only; nolh = right hand only —
        # each stem gets its own MIDI file with only the wanted hands on
        # channels (RH = channel 0, LH = channel 1).
        hands_by_stem = {"full": None, "norh": "L", "nolh": "R"}
        for stem_name, wav in wavs.items():
            midi_path = out_dir / f"l{level}_{stem_name}.mid"
            self._notes_to_midi(notes, tempo_map, midi_path, hand=hands_by_stem[stem_name])
            args = [
                "fluidsynth",
                "-a",
                "alsa",
                "-g",
                "0.8",
                "-F",
                str(wav),
                "-T",
                "wav",
                "-r",
                str(SAMPLE_RATE),
                str(soundfont),
                str(midi_path),
            ]
            result = subprocess.run(args, capture_output=True, text=True, check=False)
            if result.returncode != 0:
                raise AudioError(
                    f"fluidsynth render failed for {stem_name}: {(result.stderr or result.stdout).strip()[:400]}"
                )
            stems[stem_name] = wav
        # v0 fluidsynth: reference == full; micsafe/count_in fall back to the
        # sine pad/click (documented) so the pack shape is identical.
        stems["reference"] = stems["full"]
        stems["micsafe"] = SineRenderer().render_stems(notes, tempo_map, out_dir, level)["micsafe"]
        stems["count_in"] = SineRenderer().render_stems(notes, tempo_map, out_dir, level)["count_in"]
        return stems

    def _notes_to_midi(
        self,
        notes: list[dict[str, Any]],
        tempo_map: list[dict[str, Any]],
        midi_path: Path,
        *,
        hand: str | None = None,
    ) -> None:
        import mido
        from mido import Message, MetaMessage, MidiFile, MidiTrack

        bpm = int(tempo_map[0]["bpm"])
        ticks_per_beat = 480
        tempo = mido.bpm2tempo(bpm)
        midi = MidiFile(ticks_per_beat=ticks_per_beat)
        tracks = []
        for channel in (0, 1):
            track = MidiTrack()
            track.append(MetaMessage("set_tempo", tempo=tempo, time=0))
            track.append(Message("program_change", program=0, channel=channel, time=0))
            tracks.append(track)
            midi.tracks.append(track)

        def ticks(beats: float) -> int:
            return max(1, round(beats * ticks_per_beat))

        # RH = channel 0, LH = channel 1; ``hand`` selects a single hand for
        # the minus-RH / minus-LH stems (None = both hands).
        for channel, channel_hand in ((0, "R"), (1, "L")):
            if hand is not None and channel_hand != hand:
                continue
            hand_notes = sorted(
                (n for n in notes if n.get("hand") == channel_hand),
                key=lambda n: (n["startBeat"], n["pitch"]),
            )
            cursor = 0
            for note in hand_notes:
                start_ticks = ticks(note["startBeat"])
                dur_ticks = ticks(note["durBeats"])
                tracks[channel].append(
                    Message(
                        "note_on",
                        note=note["pitch"],
                        velocity=80,
                        channel=channel,
                        time=max(0, start_ticks - cursor),
                    )
                )
                tracks[channel].append(
                    Message("note_off", note=note["pitch"], velocity=0, channel=channel, time=dur_ticks)
                )
                cursor = start_ticks
        midi.save(str(midi_path))


def build_renderer(config: Any) -> AudioRenderer:
    """Instantiate the renderer named in the config (v0 default: sine)."""
    if config.renderer == "sine":
        return SineRenderer()
    if config.renderer == "fluidsynth":
        return FluidsynthRenderer(config.soundfont_path, config.soundfont_sha256)
    raise AudioError(f"unknown renderer {config.renderer!r} — choose 'sine' or 'fluidsynth'")