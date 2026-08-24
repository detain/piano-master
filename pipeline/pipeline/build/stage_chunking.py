"""Stage 5 — auto-chunking suggestions (plan §8.2.5).

Propose 2–8 bar chunks at musical phrase boundaries with a one-line rationale
and a confidence; a human confirms in the CMS later. Suggestions only — this
stage never decides chunk boundaries that get published without review.

Boundary signals at a measure boundary (between linear measures i and i+1):
- +2  the previous measure ends in a rest,
- +2  the next measure starts a repeated section (repeatMap pass change),
- +1  the previous measure ends on a long note (>= 2 beats — cadence-ish),
- +1  phrase-length regularity (a 4-measure multiple).

Constraints (enforced, not aspirational): never split a tie across the
boundary, never start mid-beat (measure boundaries only), loopSafe only where
a loop can restart musically (chunk starts on a downbeat and ends with a rest
or long note).
"""

from __future__ import annotations

from typing import Any

from pipeline.build.config import BuildConfig
from pipeline.build.runner import StageReport

MIN_CHUNK_MEASURES = 2
MAX_CHUNK_MEASURES = 8
PREFERRED_CHUNK_MEASURES = 4
LONG_NOTE_BEATS = 2.0
STRONG_SIGNAL = 2
WEAK_SIGNAL = 1


def _rest_at_boundary(rests: list[dict[str, Any]], measure: dict[str, Any]) -> bool:
    """True when a rest sits in the last beat of ``measure`` (a whole-measure
    rest ends the measure by construction)."""
    for rest in rests:
        start = rest["startBeat"]
        end = start + rest["durBeats"]
        if (
            measure["startBeat"] - 1e-6 <= start < measure["endBeat"] - 1e-6
            and end >= measure["endBeat"] - 1.0 - 1e-6
        ):
            return True
    return False


def _last_note_duration(notes: list[dict[str, Any]], measure: dict[str, Any]) -> float:
    candidates = [
        note["durBeats"]
        for note in notes
        if measure["startBeat"] - 1e-6 <= note["startBeat"] < measure["endBeat"] - 1e-6
    ]
    return max(candidates) if candidates else 0.0


def _tie_crosses(ties: list[dict[str, Any]], from_measure: int, to_measure: int) -> bool:
    return any(tie["fromMeasure"] == from_measure and tie["toMeasure"] == to_measure for tie in ties)


def _teaching_modes(notes: list[dict[str, Any]], start_beat: float, end_beat: float) -> list[str]:
    """Dense two-hand passages get the full RH → LH → BOTH walk; a simple
    melody gets BOTH directly (§8.2.5 default from density)."""
    in_chunk = [
        note
        for note in notes
        if start_beat - 1e-6 <= note["startBeat"] < end_beat - 1e-6
    ]
    hands = {note.get("hand") for note in in_chunk if note.get("hand")}
    if len(hands) >= 2:
        return ["RH", "LH", "BOTH"]
    return ["BOTH"]


def _loop_safe(
    measure: dict[str, Any],
    end_rest: bool,
    end_long: bool,
    start_of_song: bool,
) -> bool:
    """A chunk loops cleanly when it begins on a downbeat (always true at a
    measure boundary) and ends on a rest or long note; the very start of the
    song can always restart."""
    if start_of_song:
        return True
    return bool(end_rest or end_long)


def suggest_chunks(
    measures: list[dict[str, Any]],
    notes: list[dict[str, Any]],
    rests: list[dict[str, Any]],
    ties: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Pure: ordered chunk suggestions with confidence + rationale."""
    if not measures:
        return []
    suggestions: list[dict[str, Any]] = []
    chunk_index = 0
    start_pos = 0

    def close_chunk(start_pos: int, end_pos: int, rationale: str, confidence: float) -> None:
        nonlocal chunk_index
        chunk_index += 1
        start_measure = measures[start_pos]
        end_measure = measures[end_pos - 1]
        start_beat = start_measure["startBeat"]
        end_beat = end_measure["endBeat"]
        end_rest = _rest_at_boundary(rests, end_measure)
        end_long = _last_note_duration(notes, end_measure) >= LONG_NOTE_BEATS
        suggestions.append(
            {
                "chunkId": f"c{chunk_index:02d}",
                "ord": chunk_index,
                "startBeat": round(start_beat, 12),
                "endBeat": round(end_beat, 12),
                "teachingModes": _teaching_modes(notes, start_beat, end_beat),
                "waitModeDefault": {"RH": True, "LH": True, "BOTH": True},
                "prerequisiteChunks": [f"c{chunk_index - 1:02d}"] if chunk_index > 1 else [],
                "loopSafe": _loop_safe(
                    start_measure,
                    end_rest,
                    end_long,
                    start_of_song=start_pos == 0,
                ),
                "countInBeats": 1 if start_pos == 0 else 2,
                "label": _chunk_label(start_measure, end_measure),
                "difficulty": 1,
                "newSkills": [],
                "confidence": round(confidence, 2),
                "rationale": rationale,
                "measureStart": start_measure["measure"],
                "measureEnd": end_measure["measure"],
            }
        )

    pos = start_pos
    while pos < len(measures):
        # The candidate cut is AFTER measure[pos]: boundary signals and tie
        # checks describe the boundary between measures[pos] and [pos+1].
        boundary_score = 0
        rationale_bits: list[str] = []
        has_next = pos + 1 < len(measures)
        if has_next:
            chunk_end_measure = measures[pos]
            next_measure = measures[pos + 1]
            if _rest_at_boundary(rests, chunk_end_measure):
                boundary_score += STRONG_SIGNAL
                rationale_bits.append(f"rest ends {chunk_end_measure['measure']}")
            if next_measure["pass"] != chunk_end_measure["pass"]:
                boundary_score += STRONG_SIGNAL
                rationale_bits.append(f"repeated material begins {next_measure['measure']}")
            if _last_note_duration(notes, chunk_end_measure) >= LONG_NOTE_BEATS:
                boundary_score += WEAK_SIGNAL
                rationale_bits.append(f"cadence-like long note ends {chunk_end_measure['measure']}")
            if (pos + 1) % PREFERRED_CHUNK_MEASURES == 0:
                boundary_score += WEAK_SIGNAL
                rationale_bits.append("4-measure phrase")

        size = pos - start_pos + 1
        if size < MIN_CHUNK_MEASURES:
            pos += 1
            continue

        tie_block = False
        if has_next:
            from_m = measures[pos]["measure"]
            to_m = measures[pos + 1]["measure"]
            tie_block = _tie_crosses(ties, from_m, to_m)

        can_close = (
            not has_next
            or (size >= MAX_CHUNK_MEASURES)
            or (boundary_score >= STRONG_SIGNAL and not tie_block)
            or (size >= PREFERRED_CHUNK_MEASURES and boundary_score >= WEAK_SIGNAL and not tie_block)
        )
        if can_close and not tie_block:
            confidence = min(0.95, 0.5 + boundary_score * 0.15 + (0.1 if size >= PREFERRED_CHUNK_MEASURES else 0.0))
            rationale = "; ".join(rationale_bits) or "measure boundary"
            close_chunk(start_pos, pos + 1, rationale, confidence)
            start_pos = pos + 1
            pos = start_pos
        else:
            pos += 1

    if start_pos < len(measures):
        close_chunk(start_pos, len(measures), "end of piece", 0.5)

    return suggestions


def _chunk_label(start_measure: dict[str, Any], end_measure: dict[str, Any]) -> str:
    if start_measure["measure"] == end_measure["measure"]:
        return f"Bar {start_measure['measure']}"
    return f"Bars {start_measure['measure']}-{end_measure['measure']}"


def run_stage_chunking(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=5, name="chunking")
    suggestions = suggest_chunks(
        doc.get("measures", []),
        doc.get("notes", []),
        doc.get("rests", []),
        doc.get("ties", []),
    )
    doc["chunks"] = suggestions
    doc["chunkSuggestions"] = True  # never auto-published without review
    report.properties.update(
        {"chunks": len(suggestions), "chunkBeats": [c["endBeat"] - c["startBeat"] for c in suggestions]}
    )
    report.note(
        "chunks are SUGGESTIONS — the CMS confirms them; nothing here auto-publishes (§8.2.5)"
    )
    return doc, report