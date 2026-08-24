"""Stage 7 — layout precompute (plan §8.2.7).

Do the engraving math now so the device does none of it. v0 computes on every
note:

- ``beamGroup`` — the MusicXML beam number when present, else an inferred
  rhythmic grouping: consecutive notes of the same voice within one beat that
  are shorter than a quarter share a beam group (deterministic);
- ``xHint`` — duration-derived spacing unit (1.0 per beat; documented);
- ``lane`` — note-bar skin lane: RH uses lanes 0-1, LH lanes 2-3, by voice
  (deterministic, overlap-free by construction);
- ``staff`` — from the source staff (already on the note).

Per chunk it adds ``viewport`` hints — the pitch range and the recommended
keyboard-zone window — so the on-screen keyboard auto-centers without the
client computing it. v0 stores viewport on the chunk records in chunks.json
(forward-compatible: chunk objects are open to unknown keys).
"""

from __future__ import annotations

from typing import Any

from pipeline.build.config import BuildConfig
from pipeline.build.runner import StageReport


def _infer_beam_groups(notes: list[dict[str, Any]]) -> None:
    """Group short same-voice notes within each beat into one beam group.

    MusicXML beams (stage 3 stores beam numbers) would override this; v0
    currently derives the group from rhythm alone because the extraction does
    not carry beams through (documented). Group id: sequential per voice,
    restarting at each strong beat (offset % 1.0 == 0).
    """
    for voice, staff in sorted({(n["voice"], n["staff"]) for n in notes}):
        group = 0
        current_beat = -1.0
        in_group = False
        for note in sorted(
            [n for n in notes if n["voice"] == voice and n["staff"] == staff],
            key=lambda n: (n["startBeat"], n["_seq"]),
        ):
            beat = round(note["startBeat"] % 1.0, 9)
            if note["durBeats"] >= 1.0:
                note["beamGroup"] = 0
                in_group = False
                continue
            if not in_group or beat <= current_beat:
                group += 1
                in_group = True
            note["beamGroup"] = group
            current_beat = beat


def assign_layout(notes: list[dict[str, Any]]) -> None:
    """Pure-ish: fills beamGroup/lane/xHint on the given note dicts."""
    for note in notes:
        note["xHint"] = round(max(note["durBeats"], 0.25), 12)
        voice = max(1, note["voice"])
        note["lane"] = (2 if note.get("hand") == "L" else 0) + min(voice - 1, 1)
        note.setdefault("beamGroup", None)
    _infer_beam_groups(notes)


def _viewport_for(
    notes: list[dict[str, Any]], start_beat: float, end_beat: float
) -> dict[str, int]:
    pitches = [
        note["pitch"]
        for note in notes
        if start_beat - 1e-6 <= note["startBeat"] < end_beat - 1e-6
    ]
    if not pitches:
        return {"minPitch": 60, "maxPitch": 60, "zoneStart": 48, "zoneEnd": 72}
    min_pitch = min(pitches)
    max_pitch = max(pitches)
    zone_start = (min_pitch // 12) * 12
    zone_end = ((max_pitch // 12) + 1) * 12
    return {
        "minPitch": min_pitch,
        "maxPitch": max_pitch,
        "zoneStart": zone_start,
        "zoneEnd": zone_end,
    }


def run_stage_layout(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=7, name="layout")
    notes = doc.get("notes", [])
    assign_layout(notes)
    chunks = doc.get("chunks", [])
    for chunk in chunks:
        chunk["viewport"] = _viewport_for(notes, chunk["startBeat"], chunk["endBeat"])
    doc["layout"] = {
        "viewportHints": [
            {"chunkId": chunk["chunkId"], **chunk["viewport"]} for chunk in chunks
        ]
    }
    report.properties.update(
        {
            "notes": len(notes),
            "chunks": len(chunks),
            "beamGroups": len({n["beamGroup"] for n in notes if n["beamGroup"]}),
        }
    )
    report.note("layout precomputed — the client only translates and draws (§8.2.7)")
    return doc, report