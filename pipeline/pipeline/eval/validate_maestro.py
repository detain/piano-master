"""MAESTRO published-result validation (plan §20 P0.3.1 acceptance step).

Goal: score a known published result on a MAESTRO subset within a couple of
points of the paper's number, proving the harness is calibrated. The target is
Spotify Basic Pitch, which reports note-level F1 ~0.82 and onset error
~0.052 s on MAESTRO ("A Lightweight and Real-Time ... Basic Pitch").

Run:  python -m pipeline.eval.validate_maestro [--workdir DIR] [--limit N]
      [--require-comparison]

Two hard blockers are documented in pipeline/README.md and reported here:

1. basic-pitch pins ``tensorflow<2.15.1``, which ships no Python 3.12 wheels,
   so the published model cannot run in this toolchain.
2. The magentadata GCS bucket stores no individual MAESTRO wav objects
   (verified via the GCS JSON API: only the 65-96 GB aggregated zips and
   tfrecords are present); individual-wav URLs 404 for both v3.0.0 and v2.0.0.

This script therefore always:
- runs the offline metric calibration against mir_eval's own unit-test
  fixtures (committed under tests/data/mir_eval/) -- this proves the metrics
  themselves are correct, and
- prepares the MAESTRO MIDI subset (CSV + local midi files) so the published
  comparison runs the moment both blockers lift.
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

import numpy as np

from pipeline.eval.metrics import MetricConfig, note_precision_recall_f1_hz

MAESTRO_MIDI_URL = (
    "https://storage.googleapis.com/magentadata/datasets/maestro/v3.0.0/"
    "maestro-v3.0.0-midi.zip"
)
MAESTRO_CSV_RELATIVE = "maestro-v3.0.0/maestro-v3.0.0.csv"

# Individual-wav URL patterns tried for audio acquisition (all 404 today).
AUDIO_URL_TEMPLATES = [
    "https://storage.googleapis.com/magentadata/datasets/maestro/v3.0.0/{audio_filename}",
    "https://storage.googleapis.com/magentadata/datasets/maestro/v2.0.0/{audio_filename}",
]

# Spotify Basic Pitch published numbers on MAESTRO.
PUBLISHED_NOTE_F1 = 0.8226
# TODO(confirm): the paper reports onset error ~0.052 s, but its exact
# convention (all-notes vs onset-matched events, mean vs median) could not be
# verified. The harness compares mean absolute onset error over onset-matched
# events; confirm the paper's convention matches before unblocking this
# comparison (see pipeline/README.md).
PUBLISHED_ONSET_ERROR = 0.052
TOLERANCE = 0.02  # "within a couple of points"

CALIBRATION_TOLERANCE = 1e-9


def ensure_maestro_csv(workdir: Path) -> Path:
    """Download (if needed) and extract the MAESTRO v3.0.0 CSV."""
    workdir.mkdir(parents=True, exist_ok=True)
    zip_path = workdir / "maestro-v3.0.0-midi.zip"
    if not zip_path.exists():
        print(f"downloading {MAESTRO_MIDI_URL} ...")
        urllib.request.urlretrieve(MAESTRO_MIDI_URL, zip_path)
    csv_path = workdir / MAESTRO_CSV_RELATIVE
    if not csv_path.exists():
        print(f"extracting {zip_path.name} ...")
        with zipfile.ZipFile(zip_path) as archive:
            archive.extractall(workdir)
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV missing after extraction: {csv_path}")
    return csv_path


def load_test_subset(csv_path: Path, limit: int) -> list[dict[str, str]]:
    """The ``limit`` shortest test-split entries from the MAESTRO CSV."""
    with csv_path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    test_rows = [row for row in rows if row.get("split") == "test"]
    test_rows.sort(key=lambda row: float(row.get("duration") or 0.0))
    return test_rows[:limit]


def ensure_local_midis(workdir: Path, subset: list[dict[str, str]]) -> list[Path]:
    """Resolve each subset row's MIDI file inside the extracted zip."""
    midi_root = workdir / "maestro-v3.0.0"
    midi_paths = []
    for row in subset:
        midi_path = midi_root / row["midi_filename"]
        if not midi_path.exists():
            raise FileNotFoundError(
                f"MIDI missing from extracted archive: {midi_path} "
                f"(row: {row['canonical_composer']} / {row['canonical_title']})"
            )
        midi_paths.append(midi_path)
    return midi_paths


def try_fetch_audio(audio_filename: str, workdir: Path) -> Path | None:
    """Download one MAESTRO wav; return the path or None when every URL 404s."""
    audio_dir = workdir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)
    target = audio_dir / Path(audio_filename).name
    if target.exists() and target.stat().st_size > 1024:
        return target
    for template in AUDIO_URL_TEMPLATES:
        url = template.format(audio_filename=audio_filename)
        try:
            print(f"  fetching {url}")
            with urllib.request.urlopen(url, timeout=120) as response:
                target.write_bytes(response.read())
            return target
        except urllib.error.HTTPError as exc:
            print(f"    -> HTTP {exc.code}")
            if exc.code != 404:
                return None
    return None


def basic_pitch_available() -> bool:
    """True when ``basic_pitch`` is importable in this interpreter."""
    return importlib.util.find_spec("basic_pitch") is not None


def run_metric_calibration() -> dict:
    """Reproduce mir_eval's own reference scores on its unit-test fixtures.

    Returns a report dict; ``ok`` is True only when every fixture matches its
    reference F1 within ``CALIBRATION_TOLERANCE``.
    """
    fixtures_dir = Path(__file__).resolve().parents[2] / "tests" / "data" / "mir_eval" / "transcription"
    if not fixtures_dir.is_dir():
        raise FileNotFoundError(f"mir_eval fixtures missing: {fixtures_dir}")
    import mir_eval

    failures: list[dict] = []
    for fixture_idx in range(10):
        prefix = f"{fixtures_dir}/ref{fixture_idx:02d}"
        ref_int, ref_val = mir_eval.io.load_valued_intervals(f"{prefix}.txt")
        est_int, est_val = mir_eval.io.load_valued_intervals(
            f"{fixtures_dir}/est{fixture_idx:02d}.txt"
        )
        expected = json.loads(
            (fixtures_dir / f"output{fixture_idx:02d}.json").read_text()
        )
        ref_notes = np.column_stack(
            [ref_int[:, 0], ref_int[:, 1], ref_val]
        )
        est_notes = np.column_stack(
            [est_int[:, 0], est_int[:, 1], est_val]
        )
        _, _, f1 = note_precision_recall_f1_hz(
            ref_notes, est_notes, MetricConfig()
        )
        mismatch = abs(f1 - expected["F-measure"])
        if mismatch > CALIBRATION_TOLERANCE:
            failures.append(
                {
                    "fixture": fixture_idx,
                    "expected_f1": expected["F-measure"],
                    "got_f1": f1,
                    "abs_diff": mismatch,
                }
            )
    return {
        "n_fixtures": 10,
        "ok": not failures,
        "failures": failures,
    }


def _run_published_comparison(
    workdir: Path, midi_paths: list[Path], subset: list[dict[str, str]]
) -> dict:
    """Score Basic Pitch on the subset and compare against the paper."""
    from pipeline.eval.model_wrappers import BasicPitchWrapper
    from pipeline.eval.run import evaluate_corpus

    pairs: list[tuple[str, str]] = []
    for row, midi_path in zip(subset, midi_paths):
        audio_path = try_fetch_audio(row["audio_filename"], workdir)
        if audio_path is None:
            continue
        pairs.append((str(audio_path), str(midi_path)))
    if not pairs:
        return {"ok": False, "reason": "no MAESTRO audio could be downloaded"}

    config = MetricConfig(onset_tol=0.05, offset_tol=0.2)
    results = evaluate_corpus(pairs, BasicPitchWrapper(), config)
    note_f1 = results["note_f1"]
    onset_error = results["onset_error_mean"]
    f1_within = abs(note_f1 - PUBLISHED_NOTE_F1) <= TOLERANCE
    onset_within = abs(onset_error - PUBLISHED_ONSET_ERROR) <= TOLERANCE
    return {
        "ok": f1_within and onset_within,
        "n_pairs": len(pairs),
        "note_f1": note_f1,
        "published_note_f1": PUBLISHED_NOTE_F1,
        "onset_error_mean": onset_error,
        "published_onset_error": PUBLISHED_ONSET_ERROR,
        "f1_within_tolerance": f1_within,
        "onset_within_tolerance": onset_within,
        "results": results,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="pipeline.eval.validate_maestro",
        description="MAESTRO published-result validation for the eval harness.",
    )
    parser.add_argument(
        "--workdir",
        type=Path,
        default=Path("/tmp/opencode/maestro"),
        help="data dir for MAESTRO downloads (kept out of the repo)",
    )
    parser.add_argument(
        "--limit", type=int, default=8, help="max test-split pieces to score"
    )
    parser.add_argument(
        "--require-comparison",
        action="store_true",
        help=(
            "exit non-zero when the published-number comparison is blocked "
            "(default: exit 0 with BLOCKED diagnostics)"
        ),
    )
    args = parser.parse_args(argv)

    print("==> 1/3 metric calibration against mir_eval unit-test fixtures")
    calibration = run_metric_calibration()
    print(f"    fixtures checked: {calibration['n_fixtures']}, ok={calibration['ok']}")
    for failure in calibration["failures"]:
        print(f"    MISMATCH fixture {failure['fixture']}: {failure}")
    if not calibration["ok"]:
        print("!! metric calibration FAILED -- the harness is wrong")
        return 1

    print("==> 2/3 MAESTRO MIDI subset preparation")
    csv_path = ensure_maestro_csv(args.workdir)
    subset = load_test_subset(csv_path, args.limit)
    midi_paths = ensure_local_midis(args.workdir, subset)
    print(f"    test-split pieces selected: {len(subset)}")
    for row, midi_path in zip(subset, midi_paths):
        print(
            f"    {row['year']}  {row['canonical_composer']} -- "
            f"{row['canonical_title']}  ({float(row['duration']):.1f}s)"
        )

    print("==> 3/3 published-number comparison (Basic Pitch, F1 ~0.82)")
    if not basic_pitch_available():
        print("    BLOCKED: basic-pitch is not installed in this interpreter.")
        print("    basic-pitch 0.4.0 pins tensorflow>=2.4.1,<2.15.1, which has no")
        print("    Python 3.12 wheels; install in a Python < 3.12 venv and re-run.")
    missing_audio = sum(
        1 for row in subset if try_fetch_audio(row["audio_filename"], args.workdir) is None
    )
    if missing_audio:
        print(f"    BLOCKED: could not download {missing_audio}/{len(subset)} wav files.")
        print("    Verified via the GCS JSON API: the magentadata bucket stores no")
        print("    individual MAESTRO wav objects (only the 65-96 GB aggregated zips")
        print("    and tfrecords). URLs tried:")
        for template in AUDIO_URL_TEMPLATES:
            print(f"      {template}")
    if not basic_pitch_available() or missing_audio:
        print("    Validation of the published number is blocked; the metric-level")
        print("    calibration above plus tests/test_eval_harness.py stand in until")
        print("    P0.3.3 bake-off models provide a runnable published baseline.")
        if args.require_comparison:
            print("!! --require-comparison set: blocked, exiting non-zero")
            return 2
        return 0

    comparison = _run_published_comparison(args.workdir, midi_paths, subset)
    print(json.dumps(comparison, indent=2, default=str))
    if not comparison["ok"]:
        print("!! published-number comparison OUTSIDE tolerance")
        return 1
    print("==> VALIDATED: harness scores Basic Pitch within a couple of points")
    return 0


if __name__ == "__main__":
    sys.exit(main())