"""Stage 8 — arrangement levels (v0 of plan §8.2.8).

v0 emits the normalized arrangement as a single level — level 1, name
"Essentials" — and documents that L2/L3 generation is later. The output must
satisfy the frozen notes.json shape ``{"levels": {"1": [...]}}``.

This stage is where notes become final SongPack records:

1. The canonical array order — chronological by ``startBeat`` ascending;
   notes with equal ``startBeat`` ordered by hand (R before L), then pitch
   ascending (spec §3.1). The internal ``_seq`` tiebreak keeps the order
   stable when everything else ties.
2. ``tieToIndex`` is resolved HERE, index-based over the whole level array:
   every tie start/continue links to the next same-pitch same-voice note in
   the same array (spec §3.2) — ties across chunk boundaries work because the
   reference is an array index, not a beat.
3. Internal underscore-prefixed fields are stripped.
"""

from __future__ import annotations

from typing import Any

from pipeline.build.config import BuildConfig
from pipeline.build.errors import NormalizeError
from pipeline.build.runner import StageReport

LEVEL_1_NAME = "Essentials"


def _sort_key(note: dict[str, Any]) -> tuple[Any, ...]:
    hand_rank = 0 if note.get("hand") == "R" else 1
    return (
        note["startBeat"],
        hand_rank,
        note["pitch"],
        note.get("voice", 1),
        note.get("staff", 1),
        note.get("_seq", 0),
    )


def _resolve_ties(notes: list[dict[str, Any]]) -> None:
    """Set tieToIndex on every tie start/continue note."""
    for index, note in enumerate(notes):
        if note.get("_tieType") not in ("start", "continue"):
            continue
        target = next(
            (
                candidate_index
                for candidate_index, candidate in enumerate(notes)
                if candidate_index > index
                and candidate.get("pitch") == note["pitch"]
                and candidate.get("voice") == note.get("voice")
                and candidate.get("staff") == note.get("staff")
                and candidate.get("_tieType") in ("stop", "continue")
            ),
            None,
        )
        if target is None:
            raise NormalizeError(
                f"tie on pitch {note['pitch']} (beat {note['startBeat']}) has no "
                "matching stop note in the level array"
            )
        note["tieToIndex"] = target


def _strip_internal(note: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in note.items() if not key.startswith("_")}


def run_stage_levels(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=8, name="levels")
    notes = list(doc.get("notes", []))
    notes.sort(key=_sort_key)
    _resolve_ties(notes)
    doc["levels"] = {"1": [_strip_internal(note) for note in notes]}
    doc["arrangementLevels"] = [
        {
            "level": 1,
            "name": LEVEL_1_NAME,
            "difficulty": doc.get("difficulty", {}).get("song", 1),
            "tier": "free",
        }
    ]
    report.properties.update({"levels": 1, "notes": len(notes)})
    report.note(
        "v0 emits a single level (1, Essentials) — L2/L3 automated reductions are later (§8.2.8)"
    )
    return doc, report