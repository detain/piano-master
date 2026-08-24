"""SongPack v1 — the primary consumer of the canonical schema.

Plan §20 P1.1.2; human spec docs/specs/songpack-v1.md; canonical schema
content/schema/songpack-v1.json (plan §8.1.10 — one schema, three consumers:
this Python module, the PHP API, and the Kotlin tests).

The schema is loaded DIRECTLY from the repository (never copied), so the three
consumers cannot drift by construction. Schema validation uses the canonical
schema's per-document $defs (precise, actionable error paths); the oneOf root
dispatcher — the entry point the PHP/Kotlin consumers use — is implied: any
document that validates against its own $def validates against exactly one
root branch.
"""

from __future__ import annotations

import argparse
import json
import os
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

import jsonschema

# `format: date-time` enforcement (plan §8.1.10): jsonschema only registers a
# date-time check when its OPTIONAL rfc3339_validator dependency is importable,
# which it is not in this environment — so without the check below the Python
# consumer would silently accept a malformed buildInfo.buildTimestamp that the
# PHP (opis) and Kotlin (networknt) consumers reject. Registering the check on
# our own FormatChecker instance keeps enforcement unconditional.
_format_checker = jsonschema.FormatChecker()


@_format_checker.checks("date-time", raises=ValueError)
def _is_rfc3339_date_time(instance: object) -> bool:
    """True iff instance is an RFC 3339 date-time carrying an explicit offset.

    Non-strings pass through — the schema's `type: string` owns that decision.
    """
    if not isinstance(instance, str):
        return True
    parsed = datetime.fromisoformat(instance.replace("Z", "+00:00"))
    return parsed.tzinfo is not None


# Repo root is three parents up from pipeline/pipeline/songpack/validator.py.
CANONICAL_SCHEMA_PATH = (
    Path(__file__).resolve().parents[3] / "content" / "schema" / "songpack-v1.json"
)

# JSON-format-contract files (§8.1.2), each validated against its $def.
PACK_FILES = ("manifest.json", "notes.json", "chunks.json", "skills.json")

# File name -> $def name inside the canonical schema.
PACK_FILE_DEFS = {
    "manifest.json": "manifest",
    "notes.json": "notesFile",
    "chunks.json": "chunksFile",
    "skills.json": "skillsFile",
}


def _reject_non_finite(constant: str) -> Any:
    """json.loads(parse_constant=...) hook: NaN/Infinity are not JSON and would
    silently corrupt beat math, so a hand-authored file that contains them must
    fail loudly instead of validating with garbage numbers."""
    raise ValueError(
        f"non-finite numeric literal {constant!r} is not valid SongPack JSON "
        "(NaN/Infinity are not JSON)"
    )


def load_json(path: Path) -> Any:
    """Load one pack file, rejecting NaN/Infinity at the JSON boundary."""
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle, parse_constant=_reject_non_finite)


@dataclass
class ValidationReport:
    """Outcome of validate_pack. valid == no errors; warnings never fail."""

    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def valid(self) -> bool:
        return not self.errors

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)


def schema_path() -> Path:
    """Path of the canonical schema; KEYQUEST_SONGPACK_SCHEMA overrides it."""
    override = os.environ.get("KEYQUEST_SONGPACK_SCHEMA")
    return Path(override) if override else CANONICAL_SCHEMA_PATH


def load_schema() -> dict[str, Any]:
    """Load the canonical schema as a plain dict (the $refs stay internal)."""
    return json.loads(schema_path().read_text(encoding="utf-8"))


def _validate_document(document: Any, schema: dict[str, Any], filename: str) -> list[str]:
    """Validate one document against its $def, returning actionable messages."""
    def_name = PACK_FILE_DEFS[filename]
    validator = jsonschema.Draft7Validator(
        schema,
        format_checker=_format_checker,
    ).evolve(schema=schema["$defs"][def_name])
    return [
        f"{filename}: {error.message} (at {error.json_path or '/'})"
        for error in validator.iter_errors(document)
    ]


def _check_pickup_beats(manifest: dict[str, Any], report: ValidationReport) -> None:
    """§8.1.3: pickupBeats must be >= 0 and no longer than a full measure at the
    opening time signature — getting it wrong offsets the entire song by a beat."""
    pickup = manifest.get("pickupBeats", 0.0)
    if pickup < 0:
        report.error(
            f"pickupBeats {pickup!r} is negative — the whole song is offset by a beat (§8.1.3)"
        )
        return
    time_signatures = manifest.get("timeSignatures", [])
    if not time_signatures:
        report.warn("timeSignatures is empty; cannot sanity-check pickupBeats")
        return
    opening = min(time_signatures, key=lambda entry: entry.get("atBeat", 0))
    measure_beats = opening["numerator"] * 4.0 / opening["denominator"]
    if pickup > measure_beats:
        report.error(
            f"pickupBeats {pickup} exceeds a full measure at the opening time signature "
            f"({opening['numerator']}/{opening['denominator']} = {measure_beats} beats) — "
            "an anacrusis is at most one measure (§8.1.3)"
        )


def _check_tempo_map(manifest: dict[str, Any], report: ValidationReport) -> None:
    tempo_map = manifest.get("tempoMap", [])
    if tempo_map and tempo_map[0].get("atBeat") != 0:
        report.error(
            f"tempoMap[0].atBeat must be 0 (got {tempo_map[0].get('atBeat')!r}) — "
            "the tempo map must cover beat 0"
        )


def _check_ties(notes_by_level: dict[str, Any], report: ValidationReport) -> None:
    """§8.1.4: tieToIndex is an INDEX into the same level's notes array (never a
    beat), points forward, and must target the same pitch."""
    for level_id, notes in notes_by_level.items():
        for index, note in enumerate(notes):
            target = note.get("tieToIndex")
            if target is None:
                continue
            if not isinstance(target, int):
                report.error(
                    f"level {level_id} note[{index}]: tieToIndex must be an integer index, "
                    f"got {target!r}"
                )
                continue
            if target < 0 or target >= len(notes):
                report.error(
                    f"level {level_id} note[{index}]: tieToIndex {target} is out of range "
                    f"(level array has {len(notes)} notes) — ties are INDEX-based across the "
                    "whole level array, not beat-based (§8.1.4)"
                )
                continue
            if target <= index:
                report.error(
                    f"level {level_id} note[{index}]: tieToIndex {target} must point FORWARD "
                    "to a later note in the same level array (§8.1.4)"
                )
                continue
            if notes[target].get("pitch") != note.get("pitch"):
                report.error(
                    f"level {level_id} note[{index}]: tieToIndex {target} points to pitch "
                    f"{notes[target].get('pitch')} but this note is pitch {note.get('pitch')} — "
                    "a tie must connect the same pitch (§8.1.4)"
                )


def _check_chunks(manifest: dict[str, Any] | None, chunks: list[dict[str, Any]], report: ValidationReport) -> None:
    """§8.1.5: chunkIds unique, startBeat < endBeat, both within durationBeats,
    prerequisites reference existing chunks."""
    chunk_ids = {chunk.get("chunkId") for chunk in chunks}
    seen: set[str] = set()
    duration_beats = manifest.get("durationBeats") if manifest else None
    for chunk in chunks:
        chunk_id = chunk.get("chunkId")
        if chunk_id in seen:
            report.error(f"chunkId {chunk_id!r} is duplicated — chunkIds must be unique (§8.1.5)")
        seen.add(chunk_id)
        start = chunk.get("startBeat")
        end = chunk.get("endBeat")
        if start is not None and end is not None and start >= end:
            report.error(f"chunk {chunk_id!r}: startBeat {start} must be < endBeat {end} (§8.1.5)")
            continue
        if duration_beats is not None:
            if end is not None and end > duration_beats:
                report.error(
                    f"chunk {chunk_id!r}: endBeat {end} exceeds durationBeats {duration_beats}"
                )
            if start is not None and start > duration_beats:
                report.error(
                    f"chunk {chunk_id!r}: startBeat {start} exceeds durationBeats {duration_beats}"
                )
    for chunk in chunks:
        for prereq in chunk.get("prerequisiteChunks", []):
            if prereq not in chunk_ids:
                report.error(
                    f"chunk {chunk.get('chunkId')!r}: prerequisiteChunks references unknown "
                    f"chunkId {prereq!r}"
                )


def _check_chunk_count(manifest: dict[str, Any], chunks: list[dict[str, Any]], report: ValidationReport) -> None:
    expected = manifest.get("chunkCount")
    if expected is not None and expected != len(chunks):
        report.error(
            f"chunkCount {expected} does not match the {len(chunks)} chunks in chunks.json (§8.1.3)"
        )


def _check_level_ids(manifest: dict[str, Any], notes: dict[str, Any], skills: dict[str, Any], report: ValidationReport) -> None:
    """Every level id in notes.json/skills.json must be declared in
    arrangementLevels[].level (§8.1.6); the reverse is a warning."""
    declared = {str(level.get("level")) for level in manifest.get("arrangementLevels", [])}
    for level_id in notes.get("levels", {}):
        if level_id not in declared:
            report.error(
                f"notes.json level {level_id!r} is not declared in arrangementLevels[].level "
                f"({sorted(declared) or 'none'}) (§8.1.6)"
            )
    for level_id in skills.get("levels", {}):
        if level_id not in declared:
            report.error(
                f"skills.json level {level_id!r} is not declared in arrangementLevels[].level "
                f"({sorted(declared) or 'none'}) (§8.1.6)"
            )
    for level_id in sorted(declared):
        if level_id not in notes.get("levels", {}):
            report.warn(
                f"arrangementLevel {level_id} has no notes in notes.json — every declared level "
                "should ship a full note set (§8.1.6)"
            )


def validate_pack(pack_dir: str | os.PathLike[str]) -> ValidationReport:
    """Validate a SongPack v1 directory: schema + semantic checks.

    Schema validation (per-document $defs) first, then the semantic
    cross-field checks the schema cannot express.
    """
    report = ValidationReport()
    root = Path(pack_dir)
    if not root.is_dir():
        report.error(f"{root} is not a directory")
        return report

    try:
        schema = load_schema()
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        report.error(f"cannot load canonical schema {schema_path()}: {exc}")
        return report

    documents: dict[str, Any] = {}
    for filename in PACK_FILES:
        path = root / filename
        if not path.is_file():
            report.error(f"missing {filename} in {root}")
            continue
        try:
            document = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            report.error(f"{filename}: {exc}")
            continue
        documents[filename] = document
        report.errors.extend(_validate_document(document, schema, filename))

    manifest = documents.get("manifest.json")
    if manifest is not None:
        _check_pickup_beats(manifest, report)
        _check_tempo_map(manifest, report)

    notes = documents.get("notes.json")
    if notes is not None:
        _check_ties(notes.get("levels", {}), report)

    chunks = documents.get("chunks.json")
    if chunks is not None:
        _check_chunks(manifest, chunks, report)
        if manifest is not None:
            _check_chunk_count(manifest, chunks, report)

    skills = documents.get("skills.json")
    if manifest is not None and (notes is not None or skills is not None):
        _check_level_ids(
            manifest,
            notes if notes is not None else {"levels": {}},
            skills if skills is not None else {"levels": {}},
            report,
        )

    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate a SongPack v1 directory against the canonical schema "
        "and semantic checks."
    )
    parser.add_argument("pack_dir", help="Directory containing the four pack JSON files")
    args = parser.parse_args(argv)
    report = validate_pack(args.pack_dir)
    for message in report.errors:
        print(f"ERROR: {message}")
    for message in report.warnings:
        print(f"WARNING: {message}")
    if report.valid:
        print(f"{args.pack_dir}: OK")
        return 0
    print(f"{args.pack_dir}: INVALID ({len(report.errors)} error(s))")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())