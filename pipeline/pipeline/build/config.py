"""Build configuration and content-store layout (plan §8.2).

All paths default to the monorepo layout (``content/store`` for ingested
sources, ``content/builds`` for stage intermediates and packs) but are
overridable via environment so tests and CI never touch the real store:
``KEYQUEST_STORE_DIR`` and ``KEYQUEST_BUILDS_DIR``.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from pipeline.build.errors import CliError

# Repo root: pipeline/pipeline/build/config.py → parents[3] = repo root.
REPO_ROOT = Path(__file__).resolve().parents[3]

PIPELINE_VERSION = "0.1.0"

# The stage table mirrors plan §8.2 and the CLI surface in cli.py.
STAGE_NAMES: dict[int, str] = {
    1: "ingest",
    2: "validate",
    3: "normalize",
    4: "hands",
    5: "chunking",
    6: "difficulty",
    7: "layout",
    8: "levels",
    9: "audio",
    10: "pack",
    11: "publish",
}
BUILD_LAST_STAGE = 10  # `build` runs 1..10; `publish` is stage 11 alone.


def store_root() -> Path:
    override = os.environ.get("KEYQUEST_STORE_DIR")
    return Path(override) if override else REPO_ROOT / "content" / "store"


def builds_root() -> Path:
    override = os.environ.get("KEYQUEST_BUILDS_DIR")
    return Path(override) if override else REPO_ROOT / "content" / "builds"


def catalog_root() -> Path:
    override = os.environ.get("KEYQUEST_CATALOG_DIR")
    return Path(override) if override else REPO_ROOT / "content" / "catalog"


def song_store_dir(song_id: str) -> Path:
    return store_root() / song_id


def song_source_dir(song_id: str) -> Path:
    return song_store_dir(song_id) / "source"


def provenance_path(song_id: str) -> Path:
    return song_store_dir(song_id) / "provenance.json"


def song_builds_dir(song_id: str) -> Path:
    return builds_root() / song_id


def stage_dir(song_id: str, stage: int) -> Path:
    return song_builds_dir(song_id) / f"stage-{stage}"


def stage_song_path(song_id: str, stage: int) -> Path:
    return stage_dir(song_id, stage) / "song.json"


def stage_report_path(song_id: str, stage: int) -> Path:
    return stage_dir(song_id, stage) / "report.json"


def packs_dir(song_id: str) -> Path:
    return song_builds_dir(song_id) / "packs"


def pack_path(song_id: str, pack_version: int = 1) -> Path:
    return packs_dir(song_id) / f"song_{song_id}_v{pack_version}.pack"


def catalog_env_dir(env: str) -> Path:
    return catalog_root() / env


def catalog_pointer_path(env: str, song_id: str) -> Path:
    return catalog_env_dir(env) / f"{song_id}.json"


def validate_song_id(song_id: str) -> None:
    """Song ids become directory names and zip entries; keep them tame."""
    if not song_id or any(char.isspace() or char in "/\\" for char in song_id):
        raise CliError(
            f"invalid song id {song_id!r}: use a single token without whitespace or slashes"
        )


@dataclass(frozen=True)
class BuildConfig:
    """Everything a stage may ask for, resolved once at the CLI boundary."""

    song_id: str
    # metadata defaults are overridable from the CLI (see cli.py).
    metadata: dict[str, object] = field(default_factory=dict)
    tempo_override: int | None = None
    renderer: str = "sine"  # "sine" | "fluidsynth"
    timestamp_mode: str = "epoch-or-sentinel"  # | "now"
    strict: bool = False
    with_audio: bool = True
    pack_version: int = 1
    min_app_version: int = 1
    rights_ref: str = ""
    soundfont_path: Path | None = None
    soundfont_sha256: str | None = None
    # resolved paths (set by resolve_paths / runner)
    store: Path | None = None
    builds: Path | None = None

    def with_paths(self) -> BuildConfig:
        if self.store is None:
            object.__setattr__(self, "store", store_root())
        if self.builds is None:
            object.__setattr__(self, "builds", builds_root())
        return self