"""Stage 1 — ingest and source provenance (plan §8.2.1).

Take a source file and permanently record where it came from, before anything
mutates it: byte-for-byte copy into the content store, hashed, with a
mandatory ``--source`` provenance reference. Re-ingesting the same file from
the same source is a no-op (same sha256 → nothing changes).

v0 scope cut: MusicXML only (.musicxml/.xml); .mxl and MIDI are rejected with
named messages in musicxml.validate_source_format.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from pipeline.build.config import provenance_path, song_source_dir, validate_song_id
from pipeline.build.determinism import sha256_file, write_json_deterministic
from pipeline.build.errors import IngestError
from pipeline.build.musicxml import validate_source_format
from pipeline.build.runner import StageReport


def run_ingest(
    source_path: Path,
    song_id: str,
    source_ref: str,
    *,
    license_claim: str | None = None,
    editor: str | None = None,
    publication_year: int | None = None,
    edition: str | None = None,
    notes: str | None = None,
    config=None,
) -> tuple[dict[str, Any], StageReport]:
    """Ingest one source file into the content store (stage 1).

    Ingest FAILS without ``source_ref`` (§8.2.1): provenance is captured at
    ingest, never retrofitted. Returns the stage-1 song doc (source + rights
    provenance) and report."""
    report = StageReport(stage=1, name="ingest")
    validate_song_id(song_id)
    source_path = Path(source_path)
    if not source_path.is_file():
        raise IngestError(f"source file {source_path} does not exist")
    source_format = validate_source_format(source_path)

    if not source_ref or not source_ref.strip():
        raise IngestError(
            "ingest requires --source provenance (e.g. --source 'imslp:12345' or "
            "--source 'mutopia:XYZ') — provenance is captured at ingest, never "
            "retrofitted (§8.2.1)"
        )

    digest = sha256_file(source_path)
    target_dir = song_source_dir(song_id)
    target_dir.mkdir(parents=True, exist_ok=True)
    stored_name = f"source.{source_format}"
    target = target_dir / stored_name

    if target.is_file() and sha256_file(target) == digest:
        report.note(f"source already ingested (sha256 {digest}) — no-op")
    else:
        shutil.copyfile(source_path, target)
        report.note(f"copied {source_path.name} → {target} ({digest})")

    provenance = {
        "songId": song_id,
        "sourceRef": source_ref.strip(),
        "licenseClaim": license_claim.strip() if license_claim else None,
        "editor": editor.strip() if editor else None,
        "publicationYear": publication_year,
        "edition": edition.strip() if edition else None,
        "notes": notes.strip() if notes else None,
        "sourceFile": stored_name,
        "format": source_format,
        "sha256": digest,
    }
    write_json_deterministic(provenance_path(song_id), provenance)
    report.properties.update(
        {"songId": song_id, "sourceRef": provenance["sourceRef"], "sha256": digest}
    )

    doc = {
        "songId": song_id,
        "source": {
            "file": stored_name,
            "sha256": digest,
            "format": source_format,
            "provenance": {
                "sourceRef": provenance["sourceRef"],
                "licenseClaim": provenance["licenseClaim"],
                "editor": provenance["editor"],
                "publicationYear": provenance["publicationYear"],
                "edition": provenance["edition"],
                "notes": provenance["notes"],
            },
        },
    }
    return doc, report


def load_provenance(song_id: str, config=None) -> dict[str, Any]:
    """The stored provenance record; the source of truth for stage 2+."""
    path = provenance_path(song_id)
    if not path.is_file():
        raise IngestError(
            f"song {song_id!r} has no provenance record — ingest it first "
            "(`pipeline ingest <file> --song-id <id> --source <ref>`)"
        )
    try:
        provenance = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise IngestError(f"provenance record {path} is unreadable: {exc}")
    return provenance


def stored_source_path(song_id: str, provenance: dict[str, Any]) -> Path:
    source_dir = song_source_dir(song_id)
    return source_dir / provenance["sourceFile"]