"""Stage reports and the pipeline runner (plan §8.2).

A stage is a pure function ``(song doc dict, BuildConfig) -> (song doc dict,
StageReport)``. The runner owns all I/O: it loads the previous stage's stored
``song.json``, runs the stage, and writes ``song.json`` + ``report.json``
under ``content/builds/<song_id>/stage-N/`` so ``--from-stage N`` can resume
without re-running the whole pipeline (§8.2.12).

Stage functions never raise for expected failures — they return a report with
``errors`` filled in. The runner converts a non-empty error list into a
``PipelineError`` so the CLI boundary can print one actionable line.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from pipeline.build.config import (
    BUILD_LAST_STAGE,
    STAGE_NAMES,
    BuildConfig,
    stage_dir,
    stage_report_path,
    stage_song_path,
)
from pipeline.build.determinism import write_json_deterministic
from pipeline.build.errors import CliError, PipelineError

SONG_DOC_SCHEMA = "keyquest/songdoc-v0"


@dataclass
class StageReport:
    """Machine-readable stage outcome; ``errors`` block, warnings proceed."""

    stage: int
    name: str
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    info: list[str] = field(default_factory=list)
    properties: dict[str, Any] = field(default_factory=dict)

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    def note(self, message: str) -> None:
        self.info.append(message)

    def to_dict(self) -> dict[str, Any]:
        return {
            "stage": self.stage,
            "name": self.name,
            "errors": self.errors,
            "warnings": self.warnings,
            "info": self.info,
            "properties": self.properties,
        }


StageFn = Callable[[dict[str, Any], BuildConfig], tuple[dict[str, Any], StageReport]]


def load_song_doc(path: Path) -> dict[str, Any]:
    """Load a stored stage artifact, failing loudly on corruption."""
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PipelineError(f"cannot read stored intermediate {path}: {exc}", stage="runner")
    if not isinstance(doc, dict) or doc.get("_schema") != SONG_DOC_SCHEMA:
        raise PipelineError(
            f"{path} is not a pipeline intermediate artifact (missing _schema "
            f"{SONG_DOC_SCHEMA!r})",
            stage="runner",
        )
    return doc


def run_stage(
    song_id: str,
    stage: int,
    stage_fn: StageFn,
    config: BuildConfig,
    *,
    previous_doc: dict[str, Any],
) -> tuple[dict[str, Any], StageReport]:
    """Run one stage: pure transform, then persist song.json + report.json."""
    doc, report = stage_fn(previous_doc, config)
    doc["_schema"] = SONG_DOC_SCHEMA
    doc["songId"] = song_id
    out_dir = stage_dir(song_id, stage)
    out_dir.mkdir(parents=True, exist_ok=True)
    write_json_deterministic(stage_song_path(song_id, stage), doc)
    write_json_deterministic(stage_report_path(song_id, stage), report.to_dict())
    return doc, report


def persist_stage_1(song_id: str, doc: dict[str, Any], report: StageReport) -> None:
    """Store the ingest output as the stage-1 intermediate so `build
    --from-stage 2` and `validate` can resume without re-ingesting."""
    run_stage(
        song_id,
        1,
        lambda _previous, _config: (doc, report),
        BuildConfig(song_id=song_id),
        previous_doc={"_schema": SONG_DOC_SCHEMA, "songId": song_id},
    )


def _load_previous(config: BuildConfig, stage: int) -> dict[str, Any]:
    """The input artifact for ``stage``: the latest stored intermediate at or
    before ``stage - 1``. Skipped stages (e.g. --no-audio drops stage 9) walk
    backward to the nearest stored one."""
    if stage == 1:
        return {"_schema": SONG_DOC_SCHEMA, "songId": config.song_id}
    for candidate in range(stage - 1, 0, -1):
        path = stage_song_path(config.song_id, candidate)
        if path.is_file():
            return load_song_doc(path)
    return {"_schema": SONG_DOC_SCHEMA, "songId": config.song_id}


def run_stages(
    config: BuildConfig,
    stage_fns: dict[int, StageFn],
    *,
    from_stage: int = 1,
    to_stage: int = BUILD_LAST_STAGE,
) -> list[StageReport]:
    """Run stages ``from_stage..to_stage`` inclusive, resuming from stored
    intermediates. Returns the reports; raises PipelineError on first error."""
    config = config.with_paths()
    reports: list[StageReport] = []
    for stage in range(from_stage, to_stage + 1):
        if stage not in stage_fns:
            # Skipped stage (e.g. --no-audio removes stage 9); the next stage
            # walks back to the nearest stored intermediate.
            continue
        previous = _load_previous(config, stage)
        _doc, report = run_stage(config.song_id, stage, stage_fns[stage], config, previous_doc=previous)
        reports.append(report)
        if report.errors:
            detail = report.errors[0]
            if len(report.errors) > 1:
                detail = f"{detail} (+{len(report.errors) - 1} more)"
            raise PipelineError(detail, stage=STAGE_NAMES.get(stage, str(stage)))
    return reports


def ensure_ingested(song_id: str, config: BuildConfig) -> None:
    """Guard: build/audio/validate/publish all need stage 1 first."""
    config = config.with_paths()
    provenance = config.store / song_id / "provenance.json"
    if not provenance.is_file():
        raise CliError(
            f"song {song_id!r} is not ingested yet — run "
            f"`pipeline ingest <score.musicxml> --song-id {song_id} --source <ref>` first"
        )


def stage_ready(song_id: str, stage: int, config: BuildConfig) -> bool:
    return stage_song_path(song_id, stage).is_file()