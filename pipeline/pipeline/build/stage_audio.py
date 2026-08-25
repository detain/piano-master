"""Stage 9 — audio stems orchestration (plan §8.2.9).

Runs the renderer over every arrangement level, post-processes each stem
(loudnorm → Opus → checks), and records the audioProfile + per-file sha256 for
the pack stage. Every stem must pass the measured checks before it can be
packed; a failure here fails the build with the reason.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from pipeline.build import audio as audio_mod
from pipeline.build.config import BuildConfig, stage_dir
from pipeline.build.determinism import sha256_bytes
from pipeline.build.errors import AudioError
from pipeline.build.runner import StageReport


def render_level_audio(
    notes: list[dict[str, Any]],
    tempo_map: list[dict[str, Any]],
    renderer: audio_mod.AudioRenderer,
    out_dir: Path,
    level: int,
    expected_onsets: list[float],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Render + post-process one level's stems. Returns (files, profile)."""
    out_dir.mkdir(parents=True, exist_ok=True)
    stems = renderer.render_stems(notes, tempo_map, out_dir, level)

    files: list[dict[str, Any]] = []
    for stem_name in audio_mod.STEMS:
        spec = audio_mod.STEMS[stem_name]
        wav_path = stems.get(stem_name)
        if wav_path is None:
            raise AudioError(f"renderer {renderer.name} produced no stem {stem_name!r}")
        opus_path = out_dir / spec["name"].format(level=level)
        checks: list[str] = []
        if stem_name == "micsafe":
            checks.append("micsafe")
        if stem_name in ("full", "reference"):
            checks.append("loudness")
        if stem_name == "full":
            checks.append("alignment")
        canonical = audio_mod.postprocess_stem(
            wav_path,
            opus_path,
            channels=spec["channels"],
            bitrate=spec["bitrate"],
            checks=checks,
            expected_onsets=expected_onsets if stem_name == "full" else None,
        )
        files.append(
            {
                "name": f"audio/{spec['name'].format(level=level)}",
                "sha256": sha256_bytes(canonical),
                "bytes": len(canonical),
                "renderer": renderer.name,
            }
        )
    files.sort(key=lambda f: f["name"])
    return files, renderer_audio_profile(renderer)


def renderer_audio_profile(renderer: audio_mod.AudioRenderer) -> dict[str, Any]:
    if isinstance(renderer, audio_mod.FluidsynthRenderer):
        profile = audio_mod.AudioProfile(
            renderer=renderer.name,
            soundfont_sha256=renderer.expected_sha256,
        )
    else:
        profile = audio_mod.AudioProfile(renderer=renderer.name)
    return profile.to_dict()


def run_stage_audio(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=9, name="audio")
    notes = doc.get("levels", {}).get("1", [])
    if not notes:
        raise AudioError("no level-1 notes to render — stage 8 produced no notes")

    renderer = audio_mod.build_renderer(config)
    if config.renderer == "fluidsynth" and isinstance(renderer, audio_mod.FluidsynthRenderer):
        # Guard early: this backend cannot run in CI and should fail loudly
        # with the actionable message before any rendering starts.
        renderer._soundfont_check()

    from pipeline.build.config import stage_dir

    out_dir = stage_dir(config.song_id, 9) / "audio"
    out_dir.mkdir(parents=True, exist_ok=True)

    # Expected onsets for the alignment check: the first note (mandatory) plus
    # later notes that begin after a real silence gap (>= 80 ms), so the
    # onset detector measures a clean attack rather than a back-to-back
    # transition. Up to 3 references.
    tempo_map = doc["metadata"]["tempoMap"]
    seconds = audio_mod.beat_to_seconds([n["startBeat"] for n in notes], tempo_map)
    durations = audio_mod.note_durations_seconds(
        [n["startBeat"] for n in notes], [n["durBeats"] for n in notes], tempo_map
    )
    candidates = sorted(
        (n["startBeat"], seconds[i], durations[i]) for i, n in enumerate(notes) if seconds[i] >= 0.0
    )
    gap = 0.0
    selected: list[float] = []
    for pos, (_, sec, dur) in enumerate(candidates):
        if pos == 0:
            selected.append(sec)
            gap = sec + dur
            continue
        if sec - gap >= 0.08 and len(selected) < 3:
            selected.append(sec)
            gap = sec + dur
        elif sec > gap:
            gap = sec + dur
    # Always include the last note when it is well separated from the last
    # selected one (catches cumulative drift in the fluidsynth path).
    if candidates and len(selected) < 3 and candidates[-1][1] - selected[-1] >= 0.08:
        selected.append(candidates[-1][1])

    files, profile = render_level_audio(
        notes,
        tempo_map,
        renderer,
        out_dir,
        level=1,
        expected_onsets=selected,
    )
    doc["audio"] = {
        "audioProfile": profile,
        "files": files,
        "checks": {"alignmentOnsets": selected},
    }

    report.properties.update(
        {
            "renderer": renderer.name,
            "stems": len(files),
            "audioBytes": sum(f["bytes"] for f in files),
            "alignmentOnsets": selected,
        }
    )
    for file in files:
        report.note(f"{file['name']} ({file['bytes']} bytes, {file['sha256'][:16]}…)")
    report.note(
        "audio checks passed: encoded loudness within ±1.5 LUFS of −16, mic-safe "
        "spectrum empty in 80 Hz–2 kHz, stem onsets within 10 ms"
    )
    return doc, report