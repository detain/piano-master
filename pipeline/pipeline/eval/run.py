"""Evaluation orchestration (plan §20 P0.3.1).

``evaluate_pair`` scores one (audio, aligned ground-truth MIDI) pair through
any ``ModelWrapper``; ``evaluate_corpus`` pools the raw metric primitives
across a manifest of pairs (micro-averaged notes, pooled onset-error
distribution, pooled chord recall and octave-error rate).
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np

from pipeline.eval.metrics import (
    MetricConfig,
    chord_recall_by_size,
    chord_recall_counts,
    load_midi_notes,
    note_confusion_counts,
    note_precision_recall_f1,
    octave_error_counts,
    octave_error_rate,
    onset_error_summary,
    onset_errors,
    percentile,
)
from pipeline.eval.model_wrappers import ModelWrapper

# Default analysis sample rate for wrappers.
DEFAULT_SR = 22050


@dataclass(frozen=True)
class EvalRaw:
    """Per-pair metric primitives that pool cleanly across a corpus."""

    tp: int
    fp: int
    fn: int
    onset_errors: np.ndarray
    chord_counts: dict[int, tuple[int, int]]
    octave_errors: int
    pitch_class_matched: int


def evaluate_pair_raw(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> EvalRaw:
    """Raw metric primitives for one note-estimate vs its ground truth."""
    tp, fp, fn = note_confusion_counts(ref_notes, est_notes, config)
    octave_errors, pitch_class_matched = octave_error_counts(
        ref_notes, est_notes, config
    )
    return EvalRaw(
        tp=tp,
        fp=fp,
        fn=fn,
        onset_errors=onset_errors(ref_notes, est_notes, config),
        chord_counts=chord_recall_counts(ref_notes, est_notes, config),
        octave_errors=octave_errors,
        pitch_class_matched=pitch_class_matched,
    )


def _summary_from_raw(
    ref_notes: np.ndarray,
    est_notes: np.ndarray,
    raw: EvalRaw,
    metadata: dict,
    wrapper_name: str,
    config: MetricConfig,
) -> dict:
    """Build the public per-pair result dict from raw primitives."""
    precision, recall, f1 = note_precision_recall_f1(ref_notes, est_notes, config)
    onset_summary = onset_error_summary(ref_notes, est_notes, config)
    return {
        "wrapper": wrapper_name,
        "metadata": metadata,
        "ref_note_count": len(ref_notes),
        "est_note_count": len(est_notes),
        "tp": raw.tp,
        "fp": raw.fp,
        "fn": raw.fn,
        "note_precision": precision,
        "note_recall": recall,
        "note_f1": f1,
        "onset_error_mean": float(np.mean(raw.onset_errors)) if raw.onset_errors.size else 0.0,
        "onset_error_median": onset_summary["median"],
        "onset_error_p95": onset_summary["p95"],
        "onset_error_p99": onset_summary["p99"],
        "chord_recall_by_size": chord_recall_by_size(ref_notes, est_notes, config),
        "octave_error_rate": octave_error_rate(ref_notes, est_notes, config),
        "octave_errors": raw.octave_errors,
        "pitch_class_matched": raw.pitch_class_matched,
    }


def evaluate_pair(
    audio_path: str,
    midi_path: str,
    wrapper: ModelWrapper,
    config: MetricConfig,
    *,
    sr: int = DEFAULT_SR,
) -> dict:
    """Score one (audio, aligned ground-truth MIDI) pair through ``wrapper``.

    Returns a dict with note precision/recall/F1, onset-error median/p95/p99,
    chord recall by size, octave-error rate, and per-note counts.
    """
    ref_notes = load_midi_notes(midi_path)
    est_notes, metadata = wrapper.predict_notes(audio_path, sr, config)
    raw = evaluate_pair_raw(ref_notes, est_notes, config)
    return _summary_from_raw(ref_notes, est_notes, raw, metadata, wrapper.name, config)


def evaluate_corpus(
    pairs: Sequence[tuple[str, str]],
    wrapper: ModelWrapper,
    config: MetricConfig,
    *,
    sr: int = DEFAULT_SR,
) -> dict:
    """Aggregate metrics over a manifest of ``(audio_path, midi_path)`` pairs.

    Notes pool by true-positive / false-positive / false-negative counts
    (micro-averaged), onset errors pool into one distribution, chord recall
    pools per size, and octave errors pool globally. Per-pair results are
    included under ``per_pair``.
    """
    if not pairs:
        raise ValueError("evaluate_corpus requires at least one (audio, midi) pair")

    pooled_tp = pooled_fp = pooled_fn = 0
    pooled_onset_errors: list[np.ndarray] = []
    pooled_chord_counts: dict[int, list[tuple[int, int]]] = {}
    pooled_octave_errors = 0
    pooled_pitch_class_matched = 0
    per_pair: list[dict] = []

    for audio_path, midi_path in pairs:
        ref_notes = load_midi_notes(midi_path)
        est_notes, metadata = wrapper.predict_notes(audio_path, sr, config)
        raw = evaluate_pair_raw(ref_notes, est_notes, config)
        per_pair.append(
            _summary_from_raw(ref_notes, est_notes, raw, metadata, wrapper.name, config)
        )
        pooled_tp += raw.tp
        pooled_fp += raw.fp
        pooled_fn += raw.fn
        pooled_onset_errors.append(raw.onset_errors)
        for size, counts in raw.chord_counts.items():
            pooled_chord_counts.setdefault(size, []).append(counts)
        pooled_octave_errors += raw.octave_errors
        pooled_pitch_class_matched += raw.pitch_class_matched

    all_onset_errors = (
        np.concatenate(pooled_onset_errors) if pooled_onset_errors else np.zeros(0)
    )
    precision = pooled_tp / (pooled_tp + pooled_fp) if (pooled_tp + pooled_fp) else 0.0
    recall = pooled_tp / (pooled_tp + pooled_fn) if (pooled_tp + pooled_fn) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    chord_recall = {
        size: sum(recalled for recalled, _ in entries) / sum(total for _, total in entries)
        for size, entries in pooled_chord_counts.items()
    }
    octave_rate = (
        pooled_octave_errors / pooled_pitch_class_matched
        if pooled_pitch_class_matched
        else 0.0
    )

    return {
        "n_pairs": len(pairs),
        "wrapper": wrapper.name,
        "note_precision": precision,
        "note_recall": recall,
        "note_f1": f1,
        "note_f1_macro": float(np.mean([pair["note_f1"] for pair in per_pair])),
        "onset_error_mean": (
            float(np.mean(all_onset_errors)) if all_onset_errors.size else 0.0
        ),
        "onset_error_median": percentile(all_onset_errors, 50),
        "onset_error_p95": percentile(all_onset_errors, 95),
        "onset_error_p99": percentile(all_onset_errors, 99),
        "chord_recall_by_size": chord_recall,
        "octave_error_rate": octave_rate,
        "total_ref_notes": sum(pair["ref_note_count"] for pair in per_pair),
        "total_est_notes": sum(pair["est_note_count"] for pair in per_pair),
        "per_pair": per_pair,
    }