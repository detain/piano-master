"""Offline evaluation harness (plan §20 P0.3.1).

Takes (audio, aligned ground-truth MIDI) pairs plus any model wrapper and
emits note-level precision/recall/F1 (mir_eval conventions), the onset timing
error distribution, chord recall by chord size, and the octave-error rate.

Public API: ``MetricConfig``, the metric functions from ``metrics``, the
``ModelWrapper`` protocol plus the baseline wrappers, and the
``evaluate_pair`` / ``evaluate_corpus`` runners.
"""

from pipeline.eval.metrics import (
    MetricConfig,
    chord_recall_by_size,
    load_midi_notes,
    midi_to_hz,
    note_confusion_counts,
    note_precision_recall_f1,
    note_precision_recall_f1_hz,
    octave_error_rate,
    onset_error_summary,
    write_midi_notes,
)
from pipeline.eval.model_wrappers import (
    BasicPitchWrapper,
    EngineYinWrapper,
    GroundTruthWrapper,
    ModelWrapper,
    OafTfliteWrapper,
    PyinBaselineWrapper,
)
from pipeline.eval.run import DEFAULT_SR, EvalRaw, evaluate_corpus, evaluate_pair

__all__ = [
    "DEFAULT_SR",
    "BasicPitchWrapper",
    "EngineYinWrapper",
    "EvalRaw",
    "GroundTruthWrapper",
    "MetricConfig",
    "ModelWrapper",
    "OafTfliteWrapper",
    "PyinBaselineWrapper",
    "chord_recall_by_size",
    "evaluate_corpus",
    "evaluate_pair",
    "load_midi_notes",
    "midi_to_hz",
    "note_confusion_counts",
    "note_precision_recall_f1",
    "note_precision_recall_f1_hz",
    "octave_error_rate",
    "onset_error_summary",
    "write_midi_notes",
]