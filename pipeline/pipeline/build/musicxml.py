"""MusicXML extraction (stage 2/3 boundary).

This module is the only place that talks to music21. It parses the source at
the boundary into a trusted, plain-dict representation the stages consume, so
the algorithmic stages never see music21 objects (Parse, Don't Validate).

v0 scope cut: sources must be uncompressed MusicXML (``.musicxml``/``.xml``).
Compressed ``.mxl`` and MIDI are rejected with a named message at ingest —
§8.2.1 lists them as accepted, but the v0 renderers normalize MusicXML only.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from music21 import bar, converter, expressions, stream

from pipeline.build.errors import ValidationError

# Raw-XML markers for constructs music21 parses inconsistently or not at all
# (D.C./D.S./Coda directions, glissandi, cue notes, exotic ornaments). Every
# one gets a named, actionable message — "unsupported feature" with no name
# is a bug in this stage (§8.2.2).
_RAW_UNSUPPORTED: list[tuple[str, str]] = [
    ("<dacapo", "D.C. (Da Capo)"),
    ("<dalsegno", "D.S. (Dal Segno)"),
    ("<tocoda", "To Coda"),
    ("<fine", "Fine"),
    ("<coda", "Coda sign"),
    ("<segno", "Segno sign"),
    ("<glissando", "glissando"),
    ("<slide", "slide/glissando"),
    ("<cue", "cue note"),
    ("<turn", "turn ornament"),
    ("<inverted-turn", "inverted turn ornament"),
    ("<wavy-line", "trill extension (wavy-line)"),
]

# Exotic music21 expression classes with no v0 normalization.
_EXOTIC_EXPRESSIONS = {
    "Turn",
    "InvertedTurn",
    "TrillExtension",
    "WavyLine",
    "Appoggiatura",
    "Acciaccatura",
    "ArpeggioMark",
    "Glissando",
    "Slide",
}

PIANO_FAMILY = {
    "",
    "piano",
    "grand piano",
    "keyboard",
    "organ",
    "harpsichord",
    "celesta",
    "clavichord",
    "synthesizer",
    "synth",
    "electric piano",
    "e-piano",
    "ep",
}


def parse_score(path: Path) -> stream.Score:
    """Parse one MusicXML file, wrapping every failure as an actionable
    ValidationError (the bad-input corpus asserts these messages)."""
    try:
        score = converter.parse(str(path))
    except Exception as exc:  # noqa: BLE001 — music21 raises many types for malformed input
        raise ValidationError(
            f"failed to parse MusicXML {path.name}: {exc.__class__.__name__}: {exc} "
            "(is the file well-formed? is it really MusicXML?)"
        )
    if score is None:
        raise ValidationError(f"failed to parse MusicXML {path.name}: no score found")
    return score


def scan_unsupported_source(path: Path, score: stream.Score) -> list[str]:
    """Named list of unsupported constructs present in the source.

    Scans the raw XML (catches what music21 drops) and the parsed expression
    objects (catches names the raw scan misses). Ornaments that v0 EXPANDS
    (trill, mordent, inverted-mordent) are not listed here.
    """
    found: list[str] = []
    raw = ""
    try:
        raw = path.read_text(encoding="utf-8", errors="replace").lower()
    except OSError:
        pass
    for marker, name in _RAW_UNSUPPORTED:
        if marker in raw:
            found.append(name)
    # music21 silently "fixes" a zero-duration note into a playable duration,
    # so the defect must be caught at the raw XML boundary, not after parse.
    if re.search(r"<duration>\s*0\s*</duration>", raw):
        found.append("note with zero duration (<duration>0</duration>)")
    for expression in score.flatten().getElementsByClass(expressions.Expression):
        class_name = type(expression).__name__
        if class_name in _EXOTIC_EXPRESSIONS and class_name not in found:
            found.append(class_name)
    return sorted(set(found))


def extract_metadata(score: stream.Score) -> dict[str, str | None]:
    """Title/composer from the score metadata (None when absent)."""
    meta = score.metadata
    return {
        "title": meta.title if meta else None,
        "composer": meta.composer if meta else None,
    }


def part_warnings(score: stream.Score) -> list[str]:
    """Warnings about the part layout: >2 parts is an error, non-piano part
    names are warnings."""
    warnings: list[str] = []
    parts = list(score.parts)
    if len(parts) > 2:
        raise ValidationError(
            f"score has {len(parts)} parts; v0 supports piano scores with 1-2 staves"
        )
    for part in parts:
        name = (part.partName or "").strip().lower()
        if name and name not in PIANO_FAMILY:
            warnings.append(
                f"part {name!r} is not a piano-family part — assuming a piano arrangement"
            )
    return warnings


def _accidental_name(pitch) -> str | None:
    acc = getattr(pitch, "accidental", None)
    if acc is None:
        return None
    name = (acc.name or "").lower()
    if name in ("sharp", "flat", "natural"):
        return name
    if name == "double-sharp":
        return "sharp"
    if name == "double-flat":
        return "flat"
    return None


def _beam_list(note_obj) -> list[list[Any]]:
    beams = []
    for beam in getattr(note_obj, "beams", []) or []:
        beams.append([getattr(beam, "number", 1), getattr(beam, "type", "start")])
    return beams


def _note_records(container, voice_id: int, *, use_offsets: bool) -> list[dict[str, Any]]:
    """Extract plain note/rest/chord records from a Voice or Measure.

    ``container`` is a music21 stream whose notesAndRests carry .offset
    relative to the measure start; offset-based extraction is correct for
    multi-voice material (backup/forward are already folded in by music21).
    """
    records: list[dict[str, Any]] = []
    for element in container.notesAndRests:
        offset = float(element.offset) if use_offsets else None
        if element.isRest:
            records.append(
                {
                    "kind": "rest",
                    "qlen": float(element.quarterLength),
                    "offset": offset,
                    "voice": voice_id,
                    "grace": False,
                }
            )
            continue
        ornaments = [type(exp).__name__.lower() for exp in element.expressions]
        common: dict[str, Any] = {
            "qlen": float(element.quarterLength),
            "offset": offset,
            "voice": voice_id,
            "grace": bool(element.duration.isGrace),
            "ornaments": sorted(set(ornaments)),
            "beams": _beam_list(element),
            "lyric": getattr(element, "lyric", None),
            "accidental": None,
        }
        if element.isChord:
            # Ties are per-sub-note: Chord.tie returns the first non-None tie,
            # which would leak one voice's tie onto every chord tone (review M1).
            for sub_note in element.notes:
                record = dict(common)
                record["kind"] = "note"
                record["pitch"] = sub_note.pitch.midi
                record["accidental"] = _accidental_name(sub_note.pitch)
                record["tieType"] = sub_note.tie.type if sub_note.tie else None
                records.append(record)
        else:
            common["kind"] = "note"
            common["pitch"] = element.pitch.midi
            common["accidental"] = _accidental_name(element.pitch)
            common["tieType"] = element.tie.type if element.tie else None
            records.append(common)
    return records


def _time_signature_of(measure) -> tuple[int, int] | None:
    ts = measure.timeSignature
    if ts is None:
        ts = measure.getContextByClass("TimeSignature")
    if ts is None:
        return None
    return int(ts.numerator), int(ts.denominator)


def _key_signature_of(measure) -> dict[str, Any] | None:
    ks = measure.keySignature
    if ks is None:
        ks = measure.getContextByClass("KeySignature")
    if ks is None:
        return None
    try:
        fifths = int(ks.sharps)
    except (TypeError, ValueError):
        return None
    try:
        mode = ks.mode
    except AttributeError:
        key = ks.asKey() if hasattr(ks, "asKey") else None
        mode = getattr(key, "mode", "major")
    return {"fifths": fifths, "mode": "minor" if mode == "minor" else "major"}


def _measure_info(measure, staff: int, measure_index: int) -> dict[str, Any]:
    """One measure → plain dict with per-voice note records + metadata."""
    voices = list(measure.getElementsByClass(stream.Voice))
    if voices:
        voice_records = [
            _note_records(voice, int(voice.id), use_offsets=True) for voice in voices
        ]
    else:
        voice_records = [_note_records(measure, 1, use_offsets=True)]

    # Per-voice content length: the furthest end offset across the measure.
    content_len = 0.0
    for records in voice_records:
        for record in records:
            if record["grace"]:
                continue
            end = record["offset"] + record["qlen"] if record["offset"] is not None else 0.0
            content_len = max(content_len, end)

    # Pedal marks are Direction objects parsed by music21 into PedalMark
    # spanners; chord symbols are harmony.ChordSymbol elements.
    pedals: list[dict[str, Any]] = []
    for spanner in measure.spannerBundle.getByClass("PedalMark"):
        pedals.append(
            {
                "type": getattr(spanner, "type", "start"),
                "offset": float(getattr(spanner, "offset", 0.0) or 0.0),
            }
        )
    harmonies: list[dict[str, Any]] = []
    for harmony in measure.getElementsByClass("Harmony"):
        harmonies.append(
            {
                "figure": str(getattr(harmony, "figure", "") or ""),
                "offset": float(getattr(harmony, "offset", 0.0) or 0.0),
            }
        )

    left = measure.leftBarline
    right = measure.rightBarline
    return {
        "measure": measure.number,
        "measureIndex": measure_index,
        "implicit": bool(getattr(measure, "implicit", False)),
        "timeSignature": _time_signature_of(measure),
        "keySignature": _key_signature_of(measure),
        "contentLength": content_len,
        "startsRepeat": bool(
            isinstance(left, bar.Repeat) and left.direction == "start"
        ),
        "endRepeatTimes": (
            right.times if isinstance(right, bar.Repeat) and right.direction == "end" else None
        ),
        "voices": voice_records,
        "pedals": pedals,
        "harmonies": harmonies,
        "staff": staff,
    }


def extract_parts(score: stream.Score) -> list[dict[str, Any]]:
    """Every part → ordered list of measure dicts (see _measure_info).

    Staff numbers follow the source order: part 0 = staff 1 (upper), part 1 =
    staff 2 (lower) — the piano convention v0 assumes.
    """
    parts: list[dict[str, Any]] = []
    for part_index, part in enumerate(score.parts):
        measures = []
        for measure_index, measure in enumerate(part.getElementsByClass("Measure")):
            if measure_index == 0 or not getattr(measure, "implicit", False):
                measures.append(_measure_info(measure, part_index + 1, measure_index))
        parts.append(
            {
                "staff": part_index + 1,
                "name": (part.partName or "").strip(),
                "measures": measures,
            }
        )
    return parts


def extract_tempo(score: stream.Score) -> tuple[list[dict[str, Any]], list[str]]:
    """Numeric tempo marks → (measure_number, bpm) list; text-only marks are
    warnings. Stage 2 maps the first mark onto beat 0."""
    marks: list[dict[str, Any]] = []
    warnings: list[str] = []
    for mark in score.flatten().getElementsByClass("MetronomeMark"):
        measure_number = getattr(mark, "measureNumber", None)
        number = getattr(mark, "number", None)
        if number:
            bpm = round(float(number))
            if bpm <= 0:
                warnings.append(f"tempo mark at measure {measure_number} has implausible bpm {bpm}")
                continue
            marks.append({"measure": measure_number, "bpm": bpm})
        else:
            text = getattr(mark, "text", None) or type(mark).__name__
            warnings.append(
                f"text-only tempo {text!r} at measure {measure_number} — a human BPM "
                "is required (or pass --tempo)"
            )
    marks.sort(key=lambda entry: (entry["measure"] is None, entry["measure"] or 0))
    return marks, warnings


def validate_source_format(path: Path) -> str:
    """The source format token, rejecting compressed/foreign inputs with a
    named message (v0 scope cut)."""
    suffix = path.suffix.lower()
    if suffix in (".mxl",):
        raise ValidationError(
            "compressed .mxl is not supported in v0 — unzip it to .musicxml and re-ingest"
        )
    if suffix in (".mid", ".midi", ".kar"):
        raise ValidationError("MIDI sources are not supported in v0 — provide MusicXML")
    if suffix not in (".musicxml", ".xml"):
        raise ValidationError(
            f"unrecognized source format {suffix!r} — v0 accepts .musicxml/.xml"
        )
    return "musicxml"