"""Stage 10 — pack assembly and determinism (plan §8.2.10).

Assemble ``song_<id>_v<n>.pack`` — a deterministic zip:

- sorted entries, zeroed timestamps (determinism.write_zip_deterministic);
- manifest.json, notes.json, chunks.json, skills.json with sorted keys and
  fixed float formatting;
- audio/*.opus (canonicalized, byte-deterministic);
- checksums.json — sha256 per file, sorted.

Determinism rules:
- ``buildInfo.buildTimestamp`` = SOURCE_DATE_EPOCH if set, else the fixed
  sentinel ``1970-01-01T00:00:00Z``; ``--timestamp now`` is the opt-in
  wall-clock escape hatch for publishing;
- ``buildInfo`` is EXCLUDED from the content hash, so the cache key is stable
  regardless of timestamps;
- the pack's JSON documents are validated against the canonical schema with
  pipeline.songpack.validator before the zip is written — a pack that does not
  validate does not exist.
"""

from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from pipeline.build.config import PIPELINE_VERSION, BuildConfig, pack_path, stage_dir
from pipeline.build.determinism import (
    build_timestamp,
    canonical_json_bytes,
    sha256_bytes,
    sha256_file,
    write_zip_deterministic,
)
from pipeline.build.errors import PackError
from pipeline.build.runner import StageReport


def _git_sha() -> str:
    """The pipeline workspace git SHA, resolved against the repo root so the
    result never depends on the caller's working directory (determinism)."""
    from pipeline.build.config import REPO_ROOT

    try:
        result = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()[:40]
    except (OSError, subprocess.SubprocessError):
        pass
    return "unknown"


def _build_manifest(doc: dict[str, Any], config: BuildConfig) -> dict[str, Any]:
    metadata: dict[str, Any] = doc["metadata"]
    source: dict[str, Any] = doc.get("source", {})
    subtitle = config.metadata.get("subtitle")
    audio_profile = doc.get("audio", {}).get("audioProfile")
    if audio_profile is None:
        # A --no-audio build has no stems; the pack still needs a valid
        # audioProfile (schema-required) recording the v0 default renderer.
        from pipeline.build.audio import AudioProfile

        audio_profile = AudioProfile(renderer="sine").to_dict()
    manifest: dict[str, Any] = {
        "format": "songpack/v1",
        "songId": config.song_id,
        "packVersion": config.pack_version,
        "minAppVersion": metadata.get("minAppVersion", config.min_app_version),
        "title": config.metadata.get("title") or metadata.get("title") or config.song_id,
        "composer": config.metadata.get("composer") or metadata.get("composer") or "Unknown",
        "arranger": metadata.get("arranger", "KeyQuest"),
        "genre": metadata.get("genre", "other"),
        "era": metadata.get("era", "other"),
        "mood": metadata.get("mood", ["other"]),
        "difficulty": doc.get("difficulty", {}).get("song", 1),
        "durationBeats": metadata.get("durationBeats", 0.0),
        "durationSecondsAtDefaultTempo": round(
            metadata.get("durationBeats", 0.0) * 60.0 / max(1, metadata.get("defaultTempoBpm", 120)),
            12,
        ),
        "defaultTempoBpm": metadata.get("defaultTempoBpm", 120),
        "minPracticeTempoPct": metadata.get("minPracticeTempoPct", 60),
        "tempoMap": metadata.get("tempoMap", [{"atBeat": 0.0, "bpm": 120, "curve": "step"}]),
        "timeSignatures": metadata.get("timeSignatures", []),
        "keySignatures": metadata.get("keySignatures", []),
        "pickupBeats": metadata.get("pickupBeats", 0.0),
        "arrangementLevels": doc.get("arrangementLevels", [{"level": 1, "name": "Essentials", "difficulty": 1, "tier": "free"}]),
        "chunkCount": len(doc.get("chunks", [])),
        "chunkIndexRef": "default",
        "rightsRef": config.rights_ref or f"song:{config.song_id}:rights-v1",
        "audioProfile": audio_profile,
        "buildInfo": {
            "pipelineVersion": PIPELINE_VERSION,
            "sourceFileHash": source.get("sha256", "sha256:"),
            "buildTimestamp": build_timestamp(config.timestamp_mode),
            "gitSha": _git_sha(),
        },
    }
    if subtitle:
        manifest["subtitle"] = subtitle
    if doc.get("repeatMap"):
        manifest["repeatMap"] = doc["repeatMap"]
    if doc.get("pedalMarks"):
        manifest["pedalMarks"] = doc["pedalMarks"]
    if doc.get("chordSymbols"):
        manifest["chordSymbols"] = doc["chordSymbols"]
    if doc.get("lyrics"):
        manifest["lyrics"] = doc["lyrics"]
    return manifest


def _pack_files(doc: dict[str, Any], config: BuildConfig) -> dict[str, bytes]:
    """The zip entries as (name, bytes), including the JSON docs and audio."""
    manifest = _build_manifest(doc, config)
    notes = {"levels": doc.get("levels", {"1": []})}
    chunks = doc.get("chunks", [])
    skills = {"levels": {"1": {"requiredSkills": [], "taughtSkills": []}}}

    entries: dict[str, bytes] = {
        "manifest.json": canonical_json_bytes(manifest),
        "notes.json": canonical_json_bytes(notes),
        "chunks.json": canonical_json_bytes(chunks),
        "skills.json": canonical_json_bytes(skills),
    }

    audio_dir = stage_dir(config.song_id, 9) / "audio"
    for file in doc.get("audio", {}).get("files", []):
        name = file["name"]
        source = audio_dir / Path(name).name
        if not source.is_file():
            raise PackError(f"audio file {name} missing from {audio_dir}")
        entries[name] = source.read_bytes()
    return entries


def content_hash(doc: dict[str, Any], config: BuildConfig) -> str:
    """sha256 of the pack content EXCLUDING buildInfo (plan §8.2.10) — the
    stable cache key that timestamps must not perturb."""
    entries = _pack_files(doc, config)
    manifest = json.loads(entries["manifest.json"])
    manifest.pop("buildInfo", None)
    entries["manifest.json"] = canonical_json_bytes(manifest)
    digest = sha256_bytes(b"".join(entries[name] for name in sorted(entries)))
    return digest


def run_stage_pack(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=10, name="pack")

    entries = _pack_files(doc, config)
    # Validate the JSON contract before the zip exists (P1.1 validator).
    with tempfile.TemporaryDirectory(prefix="keyquest-pack-") as tmp:
        tmp_path = Path(tmp)
        for name in ("manifest.json", "notes.json", "chunks.json", "skills.json"):
            (tmp_path / name).write_bytes(entries[name])
        from pipeline.songpack.validator import validate_pack

        validation = validate_pack(tmp_path)
        if not validation.valid:
            first = validation.errors[0]
            raise PackError(
                f"pack fails SongPack schema/semantic validation: {first}"
                + (f" (+{len(validation.errors) - 1} more)" if len(validation.errors) > 1 else "")
            )

        checksums = {
            name: sha256_bytes(entries[name]) for name in sorted(entries)
        }
        entries["checksums.json"] = canonical_json_bytes(checksums)

        pack_dir = pack_path(config.song_id, config.pack_version)
        pack_dir.parent.mkdir(parents=True, exist_ok=True)
        write_zip_deterministic(pack_dir, entries.items())
        digest = sha256_file(pack_dir)

    doc["pack"] = {
        "path": str(pack_dir),
        "sha256": digest,
        "fileCount": len(entries),
        "sizeBytes": pack_dir.stat().st_size,
        "packVersion": config.pack_version,
        "contentHash": content_hash(doc, config),
    }
    report.properties.update(
        {
            "packVersion": config.pack_version,
            "files": len(entries),
            "sizeBytes": doc["pack"]["sizeBytes"],
            "sha256": digest,
            "contentHash": doc["pack"]["contentHash"],
            "buildTimestamp": build_timestamp(config.timestamp_mode),
        }
    )
    report.note(f"pack {pack_dir.name} written ({doc['pack']['sizeBytes']} bytes, {digest})")
    report.note(
        f"buildTimestamp is {build_timestamp(config.timestamp_mode)} — deterministic "
        "unless SOURCE_DATE_EPOCH is set or --timestamp now is passed"
    )
    return doc, report