"""Stage 6 — difficulty and skill inference (v0 placeholder of §8.2.6).

v0 emits difficulty 1 everywhere with a documented note: the calibrated
transparent weighted score against ~50 educator-rated songs is P2. Skill
inference is minimal — empty required/taught skills, so the level-1 skill gate
trivially passes. Nothing here pretends to be musicology; it is the honest
placeholder the plan's ladder needs to stay unblocked.
"""

from __future__ import annotations

from typing import Any

from pipeline.build.config import BuildConfig
from pipeline.build.runner import StageReport

V0_DIFFICULTY = 1


def run_stage_difficulty(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=6, name="difficulty")
    notes = doc.get("notes", [])
    doc["difficulty"] = {
        "song": V0_DIFFICULTY,
        "perChunk": {chunk["chunkId"]: V0_DIFFICULTY for chunk in doc.get("chunks", [])},
        "note": "v0 emits difficulty 1; calibrated scoring against educator ratings is P2 (§8.2.6)",
    }
    doc["skills"] = {
        "requiredSkills": [],
        "taughtSkills": [],
        "note": "v0 emits empty skills; inference against the §9.2 skill graph is P2",
    }
    report.properties.update(
        {"songDifficulty": V0_DIFFICULTY, "notes": len(notes)}
    )
    report.note("difficulty 1 emitted (calibrated difficulty is P2, §8.2.6)")
    report.note("skill inference minimal in v0 (requiredSkills/taughtSkills empty)")
    return doc, report