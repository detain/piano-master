"""Bad-input corpus tests (plan §8.2.2, §8.2.13).

One small MusicXML per defect class in tests/bad/. Each must produce a
SPECIFIC, actionable error message — never a Python stack trace and never an
unnamed "unsupported feature". The CLI boundary wraps every failure into one
stderr line; these tests assert the message contains the defect's name.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

from pipeline.build.config import BuildConfig
from pipeline.build.errors import PipelineError
from pipeline.build.runner import run_stage

FIXTURES = Path(__file__).resolve().parent / "fixtures"
BAD = Path(__file__).resolve().parent / "bad"


def _stage_fns():
    from pipeline.cli import _stage_fns

    return _stage_fns()


def ingest_bad(song_id: str, filename: str) -> None:
    from pipeline.build.runner import persist_stage_1
    from pipeline.build.stage_ingest import run_ingest

    config = BuildConfig(song_id=song_id).with_paths()
    doc, report = run_ingest(BAD / filename, song_id, f"test:{filename}", config=config)
    persist_stage_1(song_id, doc, report)


def validation_error(song_id: str) -> str:
    """Run stage 2 (validate) and return its error messages joined (stage 2
    either fills the report or raises an actionable PipelineError for parse
    failures — both are the contract)."""
    from pipeline.build.runner import load_song_doc, stage_song_path

    config = BuildConfig(song_id=song_id).with_paths()
    doc = load_song_doc(stage_song_path(song_id, 1))
    try:
        _, report = run_stage(song_id, 2, _stage_fns()[2], config, previous_doc=doc)
        return "\n".join(report.errors)
    except PipelineError as exc:
        return str(exc)


BAD_CASES: list[tuple[str, str, str]] = [
    # (filename, expected message fragment, is_parse_failure)
    ("malformed.xml", "failed to parse MusicXML", True),
    ("zero_duration.musicxml", "zero duration", False),
    ("measure_sum_mismatch.musicxml", "content is 3.5 beats", False),
    ("no_tempo.musicxml", "no tempo mark found", False),
    ("out_of_range.musicxml", "outside the supported range A0–C8", False),
    ("ds_al_coda.musicxml", "D.S. (Dal Segno)", False),
    ("same_pitch_same_voice.musicxml", "simultaneous notes on the same pitch", False),
    ("voice_overflow.musicxml", "5 voices", False),
    ("glissando.musicxml", "glissando", False),
    ("cue_note.musicxml", "cue note", False),
    ("turn_ornament.musicxml", "turn ornament", False),
]


@pytest.mark.parametrize("filename,fragment,is_parse", BAD_CASES)
def test_bad_input_produces_named_actionable_error(filename: str, fragment: str, is_parse: bool) -> None:
    song_id = f"bad-{filename.replace('.', '-')}"
    ingest_bad(song_id, filename)
    errors = validation_error(song_id)
    assert fragment in errors, f"{filename}: expected {fragment!r} in {errors!r}"
    assert "Traceback" not in errors


def test_ingest_fails_without_source() -> None:
    from pipeline.build.errors import IngestError
    from pipeline.build.stage_ingest import run_ingest

    config = BuildConfig(song_id="t-nosource").with_paths()
    with pytest.raises(IngestError, match="requires --source"):
        run_ingest(FIXTURES / "pickup.musicxml", "t-nosource", "", config=config)


def test_ingest_rejects_compressed_and_midi(tmp_path) -> None:
    from pipeline.build.stage_ingest import run_ingest

    config = BuildConfig(song_id="t-mxl").with_paths()
    mxl = tmp_path / "source.mxl"
    mxl.write_text("x")
    with pytest.raises(PipelineError, match="compressed .mxl"):
        run_ingest(mxl, "t-mxl", "x", config=config)
    mid = tmp_path / "source.mid"
    mid.write_text("x")
    with pytest.raises(PipelineError, match="MIDI sources"):
        run_ingest(mid, "t-mid", "x", config=config)


def test_chord_span_is_a_warning_not_an_error() -> None:
    ingest_bad("t-chordspan", "chord_span.musicxml")
    config = BuildConfig(song_id="t-chordspan").with_paths()
    from pipeline.build.runner import load_song_doc, stage_song_path

    doc = load_song_doc(stage_song_path("t-chordspan", 1))
    _, report = run_stage("t-chordspan", 2, _stage_fns()[2], config, previous_doc=doc)
    assert report.errors == []
    assert any("spanning" in message for message in report.warnings)


def test_cli_fails_without_stack_trace() -> None:
    """The CLI boundary prints one actionable stderr line, never a traceback
    (the bad-input corpus discipline, §8.2.2)."""
    import tempfile

    env = dict(os.environ)
    with tempfile.TemporaryDirectory() as store, tempfile.TemporaryDirectory() as builds:
        env["KEYQUEST_STORE_DIR"] = store
        env["KEYQUEST_BUILDS_DIR"] = builds
        ingest_cmd = [
            sys.executable, "-m", "pipeline.cli", "ingest",
            str(BAD / "measure_sum_mismatch.musicxml"),
            "--song-id", "cli-bad", "--source", "test:cli-bad",
        ]
        proc = subprocess.run(ingest_cmd, capture_output=True, text=True, env=env, check=False)
        assert proc.returncode == 0, proc.stderr
        build_cmd = [
            sys.executable, "-m", "pipeline.cli", "build", "cli-bad",
        ]
        proc = subprocess.run(build_cmd, capture_output=True, text=True, env=env, check=False)
        assert proc.returncode != 0
        assert "Traceback" not in proc.stderr
        assert "content is 3.5 beats" in proc.stderr


def test_missing_ingest_fails_actionably() -> None:
    from pipeline.build.errors import CliError
    from pipeline.build.runner import ensure_ingested

    with pytest.raises(CliError, match="not ingested yet"):
        ensure_ingested("never-ingested", BuildConfig(song_id="never-ingested").with_paths())