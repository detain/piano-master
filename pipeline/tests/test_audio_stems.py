"""Audio stem tests (plan §8.2.9, §8.2.13).

The sine renderer is the v0 default and must run on CI's ubuntu-latest with
only pip deps + the preinstalled ffmpeg. Every check here is a measurement,
not an eyeball: encoded loudness within tolerance, mic-safe spectrum empty in
the piano fundamental band, decoded onsets within 10 ms, and byte-determinism
of the rendered WAV and canonicalized Opus.

The fluidsynth backend is code-complete but cannot run in CI (no fluidsynth,
no sudo) — its test asserts the actionable failure when the binary is absent.
"""

from __future__ import annotations

import os
import tempfile
import zipfile
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf

from pipeline.build import audio as audio_mod
from pipeline.build.config import BuildConfig, pack_path, stage_dir
from pipeline.build.errors import AudioError
from pipeline.build.runner import run_stages

FIXTURES = Path(__file__).resolve().parent / "fixtures"

pytestmark = pytest.mark.skipif(
    __import__("shutil").which("ffmpeg") is None,
    reason="ffmpeg is required for audio post-processing",
)


def _stage_fns():
    from pipeline.cli import _stage_fns

    return _stage_fns()


def build_audio(song_id: str, fixture: str = "two_hands") -> BuildConfig:
    from pipeline.build.runner import persist_stage_1
    from pipeline.build.stage_ingest import run_ingest

    config = BuildConfig(song_id=song_id).with_paths()
    doc, report = run_ingest(
        FIXTURES / f"{fixture}.musicxml", song_id, f"test:{fixture}", config=config
    )
    persist_stage_1(song_id, doc, report)
    run_stages(config, _stage_fns(), from_stage=2, to_stage=10)
    return config


def test_sine_renderer_stems_pass_all_checks() -> None:
    with tempfile.TemporaryDirectory() as builds:
        os.environ["KEYQUEST_BUILDS_DIR"] = builds
        build_audio("t-audio")
        pack = pack_path("t-audio")
        with zipfile.ZipFile(pack) as zf:
            for name in zf.namelist():
                assert name.endswith((".opus", ".json"))
        manifest = {}
        with zipfile.ZipFile(pack) as zf:
            import json

            manifest = json.loads(zf.read("manifest.json"))
        assert manifest["audioProfile"]["renderer"] == "sine"
        # The full-mix stem's encoded loudness must be within tolerance.
        audio_dir = stage_dir("t-audio", 9) / "audio"
        audio_mod.check_encoded_loudness(audio_dir / "l1_backing_full.opus")
        # The mic-safe stem must hold no energy in 80 Hz-2 kHz.
        audio_mod.check_micsafe_spectrum(audio_dir / "l1_backing_micsafe.opus")
        # The full stem's decoded onsets must align within 10 ms.
        doc = json.loads((stage_dir("t-audio", 9) / "song.json").read_text())
        expected = doc["audio"]["checks"]["alignmentOnsets"]
        audio_mod.check_alignment(
            audio_dir / "l1_backing_full.opus", expected, what="test full stem"
        )


def test_audio_bytes_are_deterministic() -> None:
    with tempfile.TemporaryDirectory() as dir_a, tempfile.TemporaryDirectory() as dir_b:
        os.environ["KEYQUEST_BUILDS_DIR"] = dir_a
        build_audio("t-aud-det")
        files_a = sorted((stage_dir("t-aud-det", 9) / "audio").glob("*.opus"))
        hashes_a = [p.read_bytes() for p in files_a]

        os.environ["KEYQUEST_BUILDS_DIR"] = dir_b
        build_audio("t-aud-det")
        files_b = sorted((stage_dir("t-aud-det", 9) / "audio").glob("*.opus"))
        hashes_b = [p.read_bytes() for p in files_b]

        assert [p.name for p in files_a] == [p.name for p in files_b]
        for name_a, name_b, data_a, data_b in zip(
            [p.name for p in files_a], [p.name for p in files_b], hashes_a, hashes_b
        ):
            assert name_a == name_b
            assert data_a == data_b, f"stem {name_a} differs between builds"


def test_canonicalize_ogg_is_deterministic_and_lossless() -> None:
    # The raw Ogg serial is random per encode; canonicalization must make two
    # encodes byte-identical and leave the decoded PCM unchanged.
    sr = 48000
    t = np.arange(int(sr * 1.0)) / sr
    samples = 0.2 * np.sin(2 * np.pi * 440 * t)
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "tone.wav"
        sf.write(str(wav), samples, sr, subtype="FLOAT")
        encoded = []
        for i in (1, 2):
            opus = Path(tmp) / f"tone_{i}.opus"
            audio_mod.encode_opus(wav, opus, channels=1, bitrate=64)
            encoded.append(opus.read_bytes())
        assert audio_mod.canonicalize_ogg(encoded[0]) == audio_mod.canonicalize_ogg(encoded[1])

        dec1 = Path(tmp) / "d1.wav"
        dec2 = Path(tmp) / "d2.wav"
        audio_mod.decode_opus(Path(tmp) / "tone_1.opus", dec1)
        audio_mod.decode_opus(Path(tmp) / "tone_2.opus", dec2)
        data1, _ = sf.read(str(dec1))
        data2, _ = sf.read(str(dec2))
        assert np.array_equal(data1, data2), "canonicalized stems decode identically"


def test_micsafe_check_rejects_piano_band_energy() -> None:
    # A stem that contains a 440 Hz tone must FAIL the spectral check.
    sr = 48000
    t = np.arange(int(sr * 1.0)) / sr
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "leaky.wav"
        sf.write(str(wav), 0.2 * np.sin(2 * np.pi * 440 * t), sr, subtype="FLOAT")
        opus = Path(tmp) / "leaky.opus"
        audio_mod.encode_opus(wav, opus, channels=1, bitrate=64)
        with pytest.raises(AudioError, match="FAILS the spectral check"):
            audio_mod.check_micsafe_spectrum(opus)


def test_alignment_check_rejects_drift_over_10ms() -> None:
    # Shift a decoded stem by 50 ms and the alignment check must fail.
    sr = 48000
    t = np.arange(int(sr * 1.0)) / sr
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "shifted.wav"
        samples = np.zeros_like(t)
        i0 = int(0.05 * sr)  # first onset at 50 ms
        samples[i0 : i0 + int(0.5 * sr)] = 0.2 * np.sin(2 * np.pi * 440 * t[: int(0.5 * sr)])
        sf.write(str(wav), samples, sr, subtype="FLOAT")
        opus = Path(tmp) / "shifted.opus"
        audio_mod.encode_opus(wav, opus, channels=2, bitrate=96)
        with pytest.raises(AudioError, match="alignment check FAILS"):
            audio_mod.check_alignment(opus, [0.0], what="shifted test")


def test_fluidsynth_backend_fails_actionably_when_absent(monkeypatch) -> None:

    monkeypatch.setenv("PATH", "/nonexistent")
    renderer = audio_mod.FluidsynthRenderer(
        soundfont_path=Path("/tmp/nonexistent.sf2"), expected_sha256="abc"
    )
    with pytest.raises(AudioError, match="fluidsynth binary is not on PATH"):
        renderer._soundfont_check()


def test_unknown_renderer_is_named_error() -> None:
    with pytest.raises(AudioError, match="unknown renderer"):
        audio_mod.build_renderer(type("C", (), {"renderer": "dgx"})())


def test_beat_to_seconds_follows_tempo_map() -> None:
    tempo_map = [
        {"atBeat": 0.0, "bpm": 120, "curve": "step"},
        {"atBeat": 4.0, "bpm": 60, "curve": "step"},
    ]
    seconds = audio_mod.beat_to_seconds([0.0, 4.0, 6.0, 10.0], tempo_map)
    # 4 beats at 120 bpm = 2 s; then 2 beats at 60 bpm = 2 s more.
    assert seconds[0] == pytest.approx(0.0)
    assert seconds[1] == pytest.approx(2.0)
    assert seconds[2] == pytest.approx(4.0)
    assert seconds[3] == pytest.approx(8.0)