"""Stage 11 — publish (v0 simplified, plan §8.2.11).

Pre-publish gate (all mandatory): SongPack schema valid · provenance present ·
audio checks passed (files exist) · size within budget. Then copy the pack to
a filesystem catalog and flip the pointer file; rollback is a pointer flip to
the previous version.

v0 scope cut (documented): the real CDN/CMS orchestration (§8.2.11/12) — job
outbox, upload-verify-pointer, cache purge — is later; this is a filesystem
catalog with the same pointer-flip discipline.
"""

from __future__ import annotations

import json
import shutil
import zipfile
from pathlib import Path
from typing import Any

from pipeline.build.config import BuildConfig, catalog_env_dir, catalog_pointer_path
from pipeline.build.determinism import sha256_file, write_json_deterministic
from pipeline.build.errors import PublishError
from pipeline.build.runner import StageReport

MAX_PACK_BYTES = 200 * 1024 * 1024  # 200 MB size budget (v0 default)


def _validate_pack_zip(pack_file: Path) -> None:
    """The gate re-validates the packed JSON docs (never trust stage 10 alone)."""
    import tempfile

    from pipeline.songpack.validator import validate_pack

    with tempfile.TemporaryDirectory(prefix="keyquest-publish-") as tmp:
        tmp_path = Path(tmp)
        with zipfile.ZipFile(pack_file) as zf:
            for name in ("manifest.json", "notes.json", "chunks.json", "skills.json"):
                (tmp_path / name).write_bytes(zf.read(name))
        validation = validate_pack(tmp_path)
        if not validation.valid:
            raise PublishError(
                f"pre-publish gate FAILS: packed JSON fails SongPack validation: "
                f"{validation.errors[0]}"
            )


def run_stage_publish(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=11, name="publish")
    env = str(config.metadata.get("env", "staging"))
    if env not in ("staging", "prod"):
        raise PublishError(f"publish --env must be 'staging' or 'prod', got {env!r}")

    pack = doc.get("pack")
    if not pack:
        raise PublishError("no pack to publish — run `pipeline build` (stage 10) first")
    pack_file = Path(pack["path"])
    if not pack_file.is_file():
        raise PublishError(f"pack file {pack_file} is missing")

    provenance = doc.get("source", {}).get("provenance", {})
    if not provenance.get("sourceRef"):
        raise PublishError(
            "pre-publish gate FAILS: provenance missing — ingest requires --source "
            "(§8.2.1) and publish refuses without it (§8.2.11)"
        )
    if not doc.get("audio", {}).get("files"):
        raise PublishError(
            "pre-publish gate FAILS: no audio stems — run the audio stage (9) before "
            "publishing"
        )
    if pack_file.stat().st_size > MAX_PACK_BYTES:
        raise PublishError(
            f"pre-publish gate FAILS: pack is {pack_file.stat().st_size} bytes, "
            f"over the {MAX_PACK_BYTES} byte budget"
        )

    _validate_pack_zip(pack_file)

    env_dir = catalog_env_dir(env)
    env_dir.mkdir(parents=True, exist_ok=True)
    target = env_dir / pack_file.name
    shutil.copyfile(pack_file, target)
    pointer_path = catalog_pointer_path(env, config.song_id)
    pointer = {
        "songId": config.song_id,
        "env": env,
        "packVersion": pack["packVersion"],
        "pack": target.name,
        "sha256": sha256_file(target),
        "rightsRef": provenance.get("sourceRef"),
    }
    write_json_deterministic(pointer_path, pointer)

    doc["published"] = {
        "env": env,
        "pack": target.name,
        "pointer": str(pointer_path),
        "sha256": pointer["sha256"],
    }
    report.properties.update(
        {
            "env": env,
            "pack": target.name,
            "pointer": str(pointer_path),
            "sha256": pointer["sha256"],
        }
    )
    report.note(
        f"published {target.name} to catalog/{env} and flipped the pointer — rollback "
        "is a pointer flip to the previous version (§8.2.11)"
    )
    return doc, report


def published_pointer(env: str, song_id: str) -> dict[str, Any] | None:
    """The live catalog pointer for a song/env, if any."""
    path = catalog_pointer_path(env, song_id)
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))