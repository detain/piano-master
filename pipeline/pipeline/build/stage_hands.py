"""Stage 4 — hand assignment (v0 simplification of plan §8.2.4).

Primary signal: staff assignment (staff 1 ≈ right hand, staff 2 ≈ left).
A simple crossing correction flips notes whose pitch contradicts their staff
region (a staff-2 note above the staff-1 register is likely a crossed RH
melody; a staff-1 note below the staff-2 register likely belongs to the LH).

Every note carries a per-note confidence; corrected notes get low confidence
(0.5) so the CMS can surface them for human review instead of silently
guessing (§8.2.4). Single-staff sources split at MIDI 60 with reduced
confidence (documented v0 default).

Fingering is NOT generated in v0 (documented; the plan treats fingering as a
draft the educator accepts or overrides — P2).
"""

from __future__ import annotations

from typing import Any

from pipeline.build.config import BuildConfig
from pipeline.build.errors import HandsError
from pipeline.build.runner import StageReport

SPLIT_PITCH = 60  # MIDI C4: single-staff split point (documented v0 default)
CONFIDENCE_PRIMARY = 1.0
CONFIDENCE_CORRECTED = 0.5
CONFIDENCE_SINGLE_STAFF = 0.7
LOW_CONFIDENCE_THRESHOLD = 0.75


def _staff_region_pitches(notes: list[dict[str, Any]]) -> tuple[float, float]:
    staff1 = [n["pitch"] for n in notes if n["staff"] == 1]
    staff2 = [n["pitch"] for n in notes if n["staff"] == 2]
    staff1_max = max(staff1) if staff1 else None
    staff2_min = min(staff2) if staff2 else None
    return staff1_max, staff2_min


def assign_hands(notes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Pure: returns new note dicts with hand + _handConfidence set."""
    output = [dict(note) for note in notes]
    staffs_used = {note["staff"] for note in output}
    staff1_max, staff2_min = _staff_region_pitches(output)

    if len(staffs_used) == 1:
        for note in output:
            if note["pitch"] >= SPLIT_PITCH:
                note["hand"] = "R"
                note["_handConfidence"] = CONFIDENCE_SINGLE_STAFF
            else:
                note["hand"] = "L"
                note["_handConfidence"] = CONFIDENCE_SINGLE_STAFF
        return output

    for note in output:
        # Primary signal: staff.
        hand = "R" if note["staff"] == 1 else "L"
        confidence = CONFIDENCE_PRIMARY
        # Crossing correction: a staff-2 note above the staff-1 register is
        # almost certainly a crossed RH melody.
        if note["staff"] == 2 and staff1_max is not None and note["pitch"] > staff1_max:
            hand = "R"
            confidence = CONFIDENCE_CORRECTED
        # And the mirror: a staff-1 note below the staff-2 register belongs
        # to the LH.
        elif note["staff"] == 1 and staff2_min is not None and note["pitch"] < staff2_min:
            hand = "L"
            confidence = CONFIDENCE_CORRECTED
        note["hand"] = hand
        note["_handConfidence"] = confidence
    return output


def run_stage_hands(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=4, name="hands")
    notes = doc.get("notes", [])
    if not notes:
        raise HandsError("no notes to assign hands to — stage 3 produced an empty timeline")
    doc["notes"] = assign_hands(notes)

    confidence_by_index = {
        note["_index"]: note.pop("_handConfidence") for note in doc["notes"]
    }
    doc["hands"] = {
        "confidence": [
            {"index": index, "confidence": confidence}
            for index, confidence in sorted(confidence_by_index.items())
        ]
    }
    low = [
        entry
        for entry in doc["hands"]["confidence"]
        if entry["confidence"] < LOW_CONFIDENCE_THRESHOLD
    ]
    doc["hands"]["lowConfidence"] = low
    report.properties.update(
        {
            "notes": len(doc["notes"]),
            "lowConfidence": len(low),
        }
    )
    if low:
        report.warn(
            f"{len(low)} note(s) have low hand-assignment confidence — review them "
            "in the CMS before publishing (§8.2.4)"
        )
    report.note("fingering is NOT generated in v0 (documented; §8.2.4 fingering is P2)")
    return doc, report