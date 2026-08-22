"""Self-tests for the offline evaluation harness (plan §20 P0.3.1).

Three layers:

1. Synthetic plumbing proof: a clean monophonic melody synthesized to wav +
   aligned MIDI must score perfectly through the ground-truth oracle, above
   0.8 through the pyin floor, and near-zero through a deliberately corrupted
   wrapper (proving the metrics discriminate).
2. mir_eval fixture calibration: the harness must reproduce mir_eval's own
   reference precision/recall/F1 values (from its unit-test fixtures, committed
   under ``tests/data/mir_eval/``) exactly.
3. Corpus aggregation sanity: pooled micro-F1 and pooled onset-error
   percentiles from ``evaluate_corpus`` are consistent with per-pair numbers.

Everything runs offline and stays well under the 30-second budget.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from pipeline.eval.metrics import (
    MetricConfig,
    note_confusion_counts,
    note_precision_recall_f1_hz,
)
from pipeline.eval.model_wrappers import GroundTruthWrapper, PyinBaselineWrapper
from pipeline.eval.run import evaluate_corpus, evaluate_pair
from pipeline.eval.synth import make_clean_melody, synthesize_melody

# Default tolerances the harness and CLI use.
CONFIG = MetricConfig()


class PitchShiftedWrapper:
    """Corrupted oracle: shifts every estimated pitch up by an octave."""

    name = "pitch-shifted"

    def __init__(self, midi_path: str, shift: int = 12) -> None:
        self.midi_path = midi_path
        self.shift = shift

    def predict_notes(self, audio_path: str, sr: int, config: MetricConfig):
        from pipeline.eval.metrics import load_midi_notes

        notes = load_midi_notes(self.midi_path).copy()
        notes[:, 2] += self.shift
        return notes, {"wrapper": self.name, "shift": self.shift}


@pytest.fixture(scope="module")
def synthetic_pair(tmp_path_factory: pytest.TempPathFactory) -> tuple[str, str]:
    """One clean 10-note melody rendered to wav + aligned MIDI (once)."""
    tmp = tmp_path_factory.mktemp("synth")
    wav, midi = synthesize_melody(
        make_clean_melody(), str(tmp / "melody.wav"), str(tmp / "melody.mid")
    )
    return wav, midi


def test_ground_truth_wrapper_is_perfect(synthetic_pair: tuple[str, str]) -> None:
    wav, midi = synthetic_pair
    result = evaluate_pair(wav, midi, GroundTruthWrapper(midi), CONFIG)
    assert result["note_f1"] == pytest.approx(1.0)
    assert result["note_precision"] == pytest.approx(1.0)
    assert result["note_recall"] == pytest.approx(1.0)
    assert result["onset_error_median"] == 0.0
    assert result["onset_error_p95"] == 0.0
    assert result["octave_error_rate"] == 0.0
    # 10 single notes -> 10 size-1 chords, all recalled.
    assert result["chord_recall_by_size"] == {1: 1.0}
    assert result["ref_note_count"] == 10
    assert result["est_note_count"] == 10


def test_pyin_baseline_on_synthetic_melody(synthetic_pair: tuple[str, str]) -> None:
    wav, midi = synthetic_pair
    result = evaluate_pair(wav, midi, PyinBaselineWrapper(), CONFIG)
    assert result["note_f1"] > 0.8, (
        f"pyin floor scored F1={result['note_f1']:.3f} on the clean synthetic "
        f"melody (expected > 0.8); inspect the synthesis or thresholds"
    )


def test_corrupted_wrapper_is_discriminated(synthetic_pair: tuple[str, str]) -> None:
    wav, midi = synthetic_pair
    result = evaluate_pair(wav, midi, PitchShiftedWrapper(midi, shift=12), CONFIG)
    assert result["note_f1"] < 0.01
    assert result["note_precision"] < 0.01
    assert result["note_recall"] < 0.01
    # Every shifted note keeps its pitch class, so all are octave errors.
    assert result["octave_error_rate"] > 0.9
    assert result["pitch_class_matched"] == 10
    assert result["octave_errors"] == 10


def test_pitch_tolerance_is_in_cents() -> None:
    """A +30-cent pitch shift still matches at ``pitch_tol_semitones=0.5``.

    mir_eval computes pitch distance in cents (``1200 * log2(ref/est)``) and
    compares it directly against ``pitch_tolerance``. The harness must pass the
    tolerance in cents (50.0 = 0.5 semitone); the pre-fix bug passed a scalar
    Hz window (~12.89), shrinking the effective window to ~0.13 semitones and
    breaking this match.
    """
    ref = np.array([[0.0, 0.5, 60.0], [1.0, 1.5, 62.0], [2.0, 2.5, 64.0]])
    est = ref.copy()
    est[1, 2] += 0.3  # +30 cents = +0.3 semitone shift in frequency

    tp, fp, fn = note_confusion_counts(ref, est, CONFIG)
    assert tp == len(ref), f"expected all {len(ref)} notes to match, got tp={tp}"
    assert fp == 0
    assert fn == 0


def test_mir_eval_fixture_calibration() -> None:
    """Reproduce mir_eval's own reference F1 values on its unit fixtures."""
    import mir_eval

    fixtures_dir = (
        Path(__file__).resolve().parent / "data" / "mir_eval" / "transcription"
    )
    assert fixtures_dir.is_dir(), f"mir_eval fixtures missing: {fixtures_dir}"

    for fixture_idx in range(10):
        ref_int, ref_val = mir_eval.io.load_valued_intervals(
            str(fixtures_dir / f"ref{fixture_idx:02d}.txt")
        )
        est_int, est_val = mir_eval.io.load_valued_intervals(
            str(fixtures_dir / f"est{fixture_idx:02d}.txt")
        )
        expected = json.loads(
            (fixtures_dir / f"output{fixture_idx:02d}.json").read_text()
        )
        ref_notes = np.column_stack([ref_int[:, 0], ref_int[:, 1], ref_val])
        est_notes = np.column_stack([est_int[:, 0], est_int[:, 1], est_val])
        precision, recall, f1 = note_precision_recall_f1_hz(
            ref_notes, est_notes, MetricConfig()
        )
        assert precision == pytest.approx(
            expected["Precision"], abs=1e-9
        ), f"fixture {fixture_idx} precision mismatch"
        assert recall == pytest.approx(
            expected["Recall"], abs=1e-9
        ), f"fixture {fixture_idx} recall mismatch"
        assert f1 == pytest.approx(
            expected["F-measure"], abs=1e-9
        ), f"fixture {fixture_idx} F1 mismatch"


def test_corpus_pooling_matches_per_pair(synthetic_pair: tuple[str, str]) -> None:
    wav, midi = synthetic_pair
    pairs = [(wav, midi), (wav, midi)]
    corpus = evaluate_corpus(pairs, GroundTruthWrapper(midi), CONFIG)
    assert corpus["n_pairs"] == 2
    assert corpus["note_f1"] == pytest.approx(1.0)
    assert corpus["onset_error_median"] == 0.0
    assert corpus["chord_recall_by_size"] == {1: 1.0}
    assert corpus["octave_error_rate"] == 0.0
    assert corpus["total_ref_notes"] == 20
    assert corpus["total_est_notes"] == 20
    assert all(pair["note_f1"] == pytest.approx(1.0) for pair in corpus["per_pair"])


def test_note_array_validation_fails_fast() -> None:
    from pipeline.eval.run import evaluate_pair_raw

    ref = np.zeros((2, 3))
    bad_shape = np.zeros((2, 2))
    with pytest.raises(ValueError, match="N, 3"):
        evaluate_pair_raw(ref, bad_shape, CONFIG)
    with pytest.raises(ValueError, match="offset"):
        invalid = np.array([[0.1, 0.05, 60.0]])
        evaluate_pair_raw(ref, invalid, CONFIG)