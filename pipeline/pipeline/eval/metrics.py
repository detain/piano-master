"""Core offline evaluation metrics (plan §20 P0.3.1).

Every metric follows mir_eval transcription conventions. The harness owns one
canonical note format: ``np.ndarray (N, 3)`` with columns
``[onset_sec, offset_sec, midi_pitch]``. Because ``mir_eval.transcription``
speaks frequencies (Hz) rather than MIDI pitches, notes are converted to Hz at
the mir_eval boundary; the public functions here always take MIDI pitches.

Metric definitions
------------------
- Note precision/recall/F1: ``mir_eval.transcription.precision_recall_f1_overlap``
  (a note matches when onset is within ``onset_tol`` seconds, pitch within
  ``pitch_tol_semitones`` -- passed to mir_eval as ``pitch_tol_semitones * 100``
  **cents**, because mir_eval measures pitch distance in cents (50 cents =
  0.5 semitone, the classic convention) -- and offset within ``offset_tol``
  *fraction of the reference note's duration* -- mir_eval's ``offset_ratio``,
  not seconds).
- Onset timing error: absolute ``ref - est`` onset difference for onsets
  matched by ``mir_eval.util.match_events`` within ``onset_tol`` (the same
  matching primitive that ``mir_eval.onset.f_measure`` uses).
- Chord recall by size: reference onsets are clustered into chords (notes
  starting within ``chord_cluster_tol`` of the chord's first onset); a chord
  of size ``k`` is recalled iff all ``k`` distinct pitches appear among the
  estimated notes whose onsets fall within ``chord_window`` of the chord's
  onset. Duplicate pitches inside one reference chord count once.
- Octave error rate: among estimated notes that match a reference note on
  onset (within ``onset_tol``) *and* pitch class, the fraction whose octave
  differs by at least one octave.
"""

from __future__ import annotations

from dataclasses import dataclass

import mir_eval
import numpy as np

# Note array layout: columns are (onset_sec, offset_sec, midi_pitch).
_ONSET, _OFFSET, _PITCH = 0, 1, 2

# A4 = MIDI 69 = 440 Hz: the reference for every MIDI -> Hz conversion.
_REFERENCE_MIDI = 69.0


@dataclass(frozen=True)
class MetricConfig:
    """Tolerances for all metrics (mir_eval transcription conventions).

    onset_tol: seconds; estimated and reference onsets closer than this (with
        matching pitch) count as a match.
    offset_tol: mir_eval's ``offset_ratio`` -- an estimated offset must land
        within this *fraction of the reference note's duration* of the
        reference offset. A ratio, not seconds; 0.2 is the classic default.
    pitch_tol_semitones: how close a pitch must be (in semitones) to count as
        the same note. Applied at the mir_eval boundary as cents (x100):
        mir_eval measures pitch distance in cents, so 0.5 semitones = 50 cents,
        the classic transcription convention.
    chord_cluster_tol: seconds; reference onsets within this of a chord's
        first onset join that chord.
    chord_window: seconds; estimated notes with onsets within ±this of a
        chord's onset are candidates for recalling its pitches.
    """

    onset_tol: float = 0.05
    offset_tol: float = 0.2
    pitch_tol_semitones: float = 0.5
    chord_cluster_tol: float = 0.025
    chord_window: float = 0.05


# ---------------------------------------------------------------------------
# Boundary conversion (MIDI -> Hz) and note helpers
# ---------------------------------------------------------------------------


def midi_to_hz(midi: float) -> float:
    """Convert a MIDI pitch to frequency in Hz (A4 = 440 Hz)."""
    return 440.0 * 2.0 ** ((midi - _REFERENCE_MIDI) / 12.0)


def load_midi_notes(midi_path: str) -> np.ndarray:
    """Parse a MIDI file into the canonical ``(N, 3)`` note array.

    Notes come from every instrument, sorted by onset; each row is
    ``[onset_sec, offset_sec, midi_pitch]``.
    """
    import pretty_midi

    midi = pretty_midi.PrettyMIDI(midi_path)
    rows: list[list[float]] = []
    for instrument in midi.instruments:
        for note in instrument.notes:
            rows.append([note.start, note.end, float(note.pitch)])
    if not rows:
        return np.zeros((0, 3))
    notes = np.asarray(rows, dtype=float)
    order = np.argsort(notes[:, _ONSET], kind="stable")
    return notes[order]


def write_midi_notes(midi_path: str, notes: np.ndarray, velocity: int = 80) -> None:
    """Write a canonical ``(N, 3)`` note array to a MIDI file (for tests)."""
    import pretty_midi

    midi = pretty_midi.PrettyMIDI()
    instrument = pretty_midi.Instrument(program=0)
    for onset, offset, pitch in notes:
        instrument.notes.append(
            pretty_midi.Note(
                velocity=velocity,
                pitch=round(pitch),
                start=float(onset),
                end=float(offset),
            )
        )
    midi.instruments.append(instrument)
    midi.write(midi_path)


def _notes_to_hz(notes: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Split a (N, 3) note array into intervals and Hz pitches."""
    intervals = notes[:, [_ONSET, _OFFSET]]
    pitches_hz = midi_to_hz(notes[:, _PITCH])
    return intervals, pitches_hz


def _require_note_array(notes: np.ndarray, label: str) -> None:
    """Fail fast: a note array must be (N, 3) with the canonical columns."""
    if notes.ndim != 2 or notes.shape[1] != 3:
        raise ValueError(
            f"{label} must be an (N, 3) array of [onset_sec, offset_sec, midi_pitch]; "
            f"got shape {notes.shape}"
        )
    if np.any(notes[:, _OFFSET] < notes[:, _ONSET]):
        raise ValueError(f"{label} contains notes whose offset precedes their onset")


# ---------------------------------------------------------------------------
# Note precision / recall / F1
# ---------------------------------------------------------------------------


def note_precision_recall_f1_hz(
    ref_hz: np.ndarray, est_hz: np.ndarray, config: MetricConfig
) -> tuple[float, float, float]:
    """mir_eval-native variant taking notes whose pitch column is in Hz.

    Exposed for calibration against mir_eval's own unit-test fixtures; the
    standard entry point ``note_precision_recall_f1`` takes MIDI pitches.
    """
    ref_intervals = ref_hz[:, [_ONSET, _OFFSET]]
    est_intervals = est_hz[:, [_ONSET, _OFFSET]]
    precision, recall, f1, _ = mir_eval.transcription.precision_recall_f1_overlap(
        ref_intervals,
        ref_hz[:, _PITCH],
        est_intervals,
        est_hz[:, _PITCH],
        onset_tolerance=config.onset_tol,
        offset_ratio=config.offset_tol,
        pitch_tolerance=config.pitch_tol_semitones * 100.0,
    )
    return float(precision), float(recall), float(f1)


def note_precision_recall_f1(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> tuple[float, float, float]:
    """Precision / recall / F1 of estimated vs reference notes (MIDI pitches)."""
    _require_note_array(ref_notes, "ref_notes")
    _require_note_array(est_notes, "est_notes")
    ref_intervals, ref_hz = _notes_to_hz(ref_notes)
    est_intervals, est_hz = _notes_to_hz(est_notes)
    precision, recall, f1, _ = mir_eval.transcription.precision_recall_f1_overlap(
        ref_intervals,
        ref_hz,
        est_intervals,
        est_hz,
        onset_tolerance=config.onset_tol,
        offset_ratio=config.offset_tol,
        pitch_tolerance=config.pitch_tol_semitones * 100.0,
    )
    return float(precision), float(recall), float(f1)


def note_confusion_counts(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> tuple[int, int, int]:
    """Count true positives / false positives / false negatives.

    Uses the exact same matching as ``precision_recall_f1_overlap``, so the
    counts pool cleanly across a corpus for micro-averaged scores.
    """
    _require_note_array(ref_notes, "ref_notes")
    _require_note_array(est_notes, "est_notes")
    ref_intervals, ref_hz = _notes_to_hz(ref_notes)
    est_intervals, est_hz = _notes_to_hz(est_notes)
    matching = mir_eval.transcription.match_notes(
        ref_intervals,
        ref_hz,
        est_intervals,
        est_hz,
        onset_tolerance=config.onset_tol,
        offset_ratio=config.offset_tol,
        pitch_tolerance=config.pitch_tol_semitones * 100.0,
    )
    true_positives = len(matching)
    return (
        true_positives,
        len(est_notes) - true_positives,
        len(ref_notes) - true_positives,
    )


# ---------------------------------------------------------------------------
# Onset timing error distribution
# ---------------------------------------------------------------------------


def onset_errors(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> np.ndarray:
    """Absolute onset errors (seconds) for onsets matched within ``onset_tol``."""
    matching = mir_eval.util.match_events(
        ref_notes[:, _ONSET], est_notes[:, _ONSET], config.onset_tol
    )
    if not matching:
        return np.zeros(0, dtype=float)
    ref_idx = np.asarray([m[0] for m in matching])
    est_idx = np.asarray([m[1] for m in matching])
    return np.abs(ref_notes[ref_idx, _ONSET] - est_notes[est_idx, _ONSET])


def percentile(errors: np.ndarray, q: float) -> float:
    """A single percentile; 0.0 when there are no matched onsets."""
    if errors.size == 0:
        return 0.0
    return float(np.percentile(errors, q))


def onset_error_summary(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> dict[str, float]:
    """Median / p95 / p99 of the absolute onset timing error (seconds)."""
    errors = onset_errors(ref_notes, est_notes, config)
    return {
        "median": percentile(errors, 50),
        "p95": percentile(errors, 95),
        "p99": percentile(errors, 99),
    }


# ---------------------------------------------------------------------------
# Chord recall by chord size
# ---------------------------------------------------------------------------


def _cluster_reference_chords(
    ref_notes: np.ndarray, config: MetricConfig
) -> list[tuple[float, set[int]]]:
    """Cluster reference notes into chords by onset proximity.

    Returns ``[(chord_onset, frozenset(pitches)), ...]``. A note joins the
    current chord while its onset is within ``chord_cluster_tol`` of the
    chord's first onset; otherwise it starts a new chord.
    """
    order = np.argsort(ref_notes[:, _ONSET], kind="stable")
    chords: list[tuple[float, set[int]]] = []
    for idx in order:
        onset = float(ref_notes[idx, _ONSET])
        pitch = round(float(ref_notes[idx, _PITCH]))
        if chords and onset - chords[-1][0] <= config.chord_cluster_tol:
            chords[-1][1].add(pitch)
        else:
            chords.append((onset, {pitch}))
    return chords


def chord_recall_counts(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> dict[int, tuple[int, int]]:
    """Per chord size: ``(recalled_chords, total_chords)`` in the reference."""
    if len(ref_notes) == 0:
        return {}
    counts: dict[int, tuple[int, int]] = {}
    for chord_onset, pitches in _cluster_reference_chords(ref_notes, config):
        size = len(pitches)
        recalled, total = counts.get(size, (0, 0))
        window = (
            (est_notes[:, _ONSET] >= chord_onset - config.chord_window)
            & (est_notes[:, _ONSET] <= chord_onset + config.chord_window)
        )
        est_pitches_in_window = set(np.round(est_notes[window, _PITCH]).astype(int))
        if pitches.issubset(est_pitches_in_window):
            recalled += 1
        counts[size] = (recalled, total + 1)
    return counts


def chord_recall_by_size(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> dict[int, float]:
    """Chord recall grouped by chord size: ``{size: fraction_recalled}``."""
    return {
        size: recalled / total if total else 0.0
        for size, (recalled, total) in chord_recall_counts(ref_notes, est_notes, config).items()
    }


# ---------------------------------------------------------------------------
# Octave error rate
# ---------------------------------------------------------------------------


def octave_error_counts(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> tuple[int, int]:
    """Count ``(octave_errors, pitch_class_matched)`` estimated notes.

    An estimated note counts as pitch-class matched when a reference note with
    the same pitch class (``midi % 12``) starts within ``onset_tol``; the
    closest such reference note is consumed greedily. Of those, an octave
    error is one whose octave differs by at least 12 semitones.
    """
    if len(est_notes) == 0 or len(ref_notes) == 0:
        return 0, 0
    ref_class = ref_notes[:, _PITCH] % 12
    used = np.zeros(len(ref_notes), dtype=bool)
    octave_errors = 0
    pitch_class_matched = 0
    for est_idx in np.argsort(est_notes[:, _ONSET], kind="stable"):
        est_onset = est_notes[est_idx, _ONSET]
        est_pitch = est_notes[est_idx, _PITCH]
        candidates = np.where(
            (~used)
            & (ref_class == est_pitch % 12)
            & (np.abs(ref_notes[:, _ONSET] - est_onset) <= config.onset_tol)
        )[0]
        if candidates.size == 0:
            continue
        ref_idx = int(
            candidates[np.argmin(np.abs(ref_notes[candidates, _ONSET] - est_onset))]
        )
        used[ref_idx] = True
        pitch_class_matched += 1
        if abs(est_pitch - ref_notes[ref_idx, _PITCH]) >= 12:
            octave_errors += 1
    return octave_errors, pitch_class_matched


def octave_error_rate(
    ref_notes: np.ndarray, est_notes: np.ndarray, config: MetricConfig
) -> float:
    """Fraction of correctly-pitch-class estimated notes whose octave is wrong."""
    octave_errors, pitch_class_matched = octave_error_counts(
        ref_notes, est_notes, config
    )
    if pitch_class_matched == 0:
        return 0.0
    return octave_errors / pitch_class_matched