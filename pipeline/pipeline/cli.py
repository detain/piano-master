"""Command-line interface for the KeyQuest content pipeline (plan §8.2).

The full v0 CLI surface (ingest, build, audio, validate, diff, publish, batch)
plus the live eval tooling. Every failure is rendered as one actionable line
on stderr with a non-zero exit — never a Python stack trace; the bad-input
corpus asserts this.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import zipfile
from pathlib import Path
from typing import Any, NoReturn

from pipeline.build import runner as runner_mod
from pipeline.build.config import (
    BUILD_LAST_STAGE,
    BuildConfig,
    catalog_env_dir,
    pack_path,
    stage_song_path,
    validate_song_id,
)
from pipeline.build.errors import CliError, PipelineError

_EVAL_HELP = "score a model wrapper against aligned ground-truth MIDI (P0.3.1)"

_GENRE_VOCAB = {"classical", "folk", "traditional", "children", "etude", "exercise", "jazz", "pop", "contemporary", "other"}
_ERA_VOCAB = {"renaissance", "baroque", "classical", "romantic", "modern", "contemporary", "traditional", "other"}
_MOOD_VOCAB = {"calm", "happy", "sad", "majestic", "playful", "serene", "dramatic", "lyrical", "energetic", "nostalgic", "bright", "tender", "other"}


def _stage_fns() -> dict[int, Any]:
    """The stage registry (imported lazily so --help stays fast)."""
    from pipeline.build.stage_audio import run_stage_audio
    from pipeline.build.stage_chunking import run_stage_chunking
    from pipeline.build.stage_difficulty import run_stage_difficulty
    from pipeline.build.stage_hands import run_stage_hands
    from pipeline.build.stage_layout import run_stage_layout
    from pipeline.build.stage_levels import run_stage_levels
    from pipeline.build.stage_normalize import run_stage_normalize
    from pipeline.build.stage_pack import run_stage_pack
    from pipeline.build.stage_publish import run_stage_publish
    from pipeline.build.stage_validate import run_stage_validate

    return {
        2: run_stage_validate,
        3: run_stage_normalize,
        4: run_stage_hands,
        5: run_stage_chunking,
        6: run_stage_difficulty,
        7: run_stage_layout,
        8: run_stage_levels,
        9: run_stage_audio,
        10: run_stage_pack,
        11: run_stage_publish,
    }


def _metadata_from_args(args: argparse.Namespace) -> dict[str, object]:
    metadata: dict[str, object] = {}
    for key in ("title", "composer", "arranger", "genre", "era", "mood", "subtitle"):
        value = getattr(args, key, None)
        if value:
            metadata[key] = value
    for key, vocab in (("genre", _GENRE_VOCAB), ("era", _ERA_VOCAB)):
        if key in metadata and metadata[key] not in vocab:
            raise CliError(
                f"{key} {metadata[key]!r} is not in the SongPack vocabulary — use one of "
                f"{sorted(vocab)} or 'other'"
            )
    if "mood" in metadata:
        bad = [m for m in metadata["mood"] if m not in _MOOD_VOCAB]
        if bad:
            raise CliError(
                f"mood {bad!r} not in the SongPack vocabulary — use one of "
                f"{sorted(_MOOD_VOCAB)} or 'other'"
            )
    return metadata


def _build_config(args: argparse.Namespace) -> BuildConfig:
    validate_song_id(args.song_id)
    return BuildConfig(
        song_id=args.song_id,
        metadata=_metadata_from_args(args),
        tempo_override=getattr(args, "tempo", None),
        renderer=getattr(args, "renderer", "sine") or "sine",
        timestamp_mode="now" if getattr(args, "timestamp", None) == "now" else "epoch-or-sentinel",
        strict=bool(getattr(args, "strict", False)),
        with_audio=not getattr(args, "no_audio", False),
        pack_version=getattr(args, "pack_version", 1) or 1,
        min_app_version=getattr(args, "min_app_version", 1) or 1,
        rights_ref=getattr(args, "rights_ref", "") or "",
        soundfont_path=Path(args.soundfont) if getattr(args, "soundfont", None) else None,
        soundfont_sha256=getattr(args, "soundfont_sha256", None),
    )


# ---------------------------------------------------------------------------
# handlers
# ---------------------------------------------------------------------------


def _cmd_ingest(args: argparse.Namespace) -> int:
    from pipeline.build.stage_ingest import run_ingest

    config = _build_config(args)
    doc, report = run_ingest(
        Path(args.source_file),
        args.song_id,
        args.source,
        license_claim=args.license_claim,
        editor=args.editor,
        publication_year=args.publication_year,
        edition=args.edition,
        notes=args.notes,
        config=config,
    )
    runner_mod.persist_stage_1(config.song_id, doc, report)
    _print_report(report)
    return 0


def _cmd_build(args: argparse.Namespace) -> int:
    config = _build_config(args)
    runner_mod.ensure_ingested(config.song_id, config)
    if args.level:
        requested = {int(part) for part in str(args.level).split(",") if part.strip()}
        if requested - {1}:
            print(
                f"WARNING: level generation beyond 1 is not available in v0 — "
                f"emitting level 1 only (requested {sorted(requested)})",
                file=sys.stderr,
            )
    from_stage = args.from_stage or 2
    to_stage = args.stage if args.stage else BUILD_LAST_STAGE
    stage_fns = _stage_fns()
    if args.no_audio:
        stage_fns = {key: fn for key, fn in stage_fns.items() if key != 9}
        print(
            "WARNING: --no-audio — pack will contain JSON only (not publishable)",
            file=sys.stderr,
        )
    if from_stage < 2:
        raise CliError("build --from-stage must be >= 2 (stage 1 is ingest; run `pipeline ingest` first)")
    reports = runner_mod.run_stages(
        config, stage_fns, from_stage=from_stage, to_stage=to_stage,
        resume_nearest=bool(getattr(args, "resume_nearest", False)),
    )
    for report in reports:
        _print_report(report)
    print(f"==> build {config.song_id} OK (stages {from_stage}-{to_stage})")
    return 0


def _cmd_audio(args: argparse.Namespace) -> int:
    config = _build_config(args)
    runner_mod.ensure_ingested(config.song_id, config)
    if args.renderer and args.renderer not in ("sine", "fluidsynth"):
        raise CliError(f"unknown renderer {args.renderer!r} — choose 'sine' or 'fluidsynth'")
    previous = runner_mod.load_song_doc(stage_song_path(config.song_id, 8))
    _doc, report = runner_mod.run_stage(
        config.song_id, 9, _stage_fns()[9], config, previous_doc=previous
    )
    _print_report(report)
    return 0


def _cmd_validate(args: argparse.Namespace) -> int:
    config = _build_config(args)
    runner_mod.ensure_ingested(config.song_id, config)
    previous = runner_mod.load_song_doc(stage_song_path(config.song_id, 1))
    _doc, report = runner_mod.run_stage(
        config.song_id, 2, _stage_fns()[2], config, previous_doc=previous
    )
    _print_report(report)
    if args.strict and report.warnings:
        print(f"==> validate {config.song_id}: STRICT FAIL — {len(report.warnings)} warning(s)", file=sys.stderr)
        return 1
    print(f"==> validate {config.song_id}: {'OK' if not report.errors else 'FAIL'}")
    return 0 if not report.errors else 1


def _load_pack_docs(pack_file: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    with zipfile.ZipFile(pack_file) as zf:
        manifest = json.loads(zf.read("manifest.json"))
        chunks = json.loads(zf.read("chunks.json"))
        notes = json.loads(zf.read("notes.json"))
    return manifest, chunks, notes


def _cmd_diff(args: argparse.Namespace) -> int:
    config = _build_config(args)
    validate_song_id(args.song_id)
    target = args.against
    if target == "published":
        from pipeline.build.stage_publish import published_pointer

        pointer = published_pointer("prod", args.song_id) or published_pointer("staging", args.song_id)
        if pointer is None:
            raise CliError(f"song {args.song_id!r} has no published pack to diff against")
        target = str(catalog_env_dir(pointer["env"]) / pointer["pack"])
    other_pack = Path(target)
    if not other_pack.is_file():
        raise CliError(f"cannot diff against {target}: not a file")

    current = pack_path(args.song_id, config.pack_version)
    if not current.is_file():
        raise CliError(f"no current pack for {args.song_id} v{config.pack_version} — run `pipeline build` first")
    left_manifest, left_chunks, left_notes = _load_pack_docs(current)
    right_manifest, right_chunks, right_notes = _load_pack_docs(other_pack)

    left_count = sum(len(notes) for notes in left_notes.get("levels", {}).values())
    right_count = sum(len(notes) for notes in right_notes.get("levels", {}).values())
    print(f"==> diff {args.song_id}: {current.name} vs {other_pack.name}")
    print(f"    notes: {left_count} -> {right_count} (delta {right_count - left_count:+d})")
    print(f"    chunks: {len(left_chunks)} -> {len(right_chunks)} (delta {len(right_chunks) - len(left_chunks):+d})")
    print(f"    difficulty: {left_manifest.get('difficulty')} -> {right_manifest.get('difficulty')}")
    left_audio = {f['name'] for f in _audio_files_in(current)}
    right_audio = {f['name'] for f in _audio_files_in(other_pack)}
    print(f"    audio re-render: {'YES' if left_audio != right_audio else 'no'}")
    if left_chunks != right_chunks:
        left_ids = [c.get("chunkId") for c in left_chunks]
        right_ids = [c.get("chunkId") for c in right_chunks]
        print(f"    chunk order: {left_ids} -> {right_ids}")
    return 0


def _audio_files_in(pack_file: Path) -> list[dict[str, Any]]:
    with zipfile.ZipFile(pack_file) as zf:
        return [{"name": name} for name in zf.namelist() if name.startswith("audio/")]


def _cmd_publish(args: argparse.Namespace) -> int:
    config = _build_config(args)
    config = BuildConfig(**{**config.__dict__, "metadata": {**config.metadata, "env": args.env}})
    runner_mod.ensure_ingested(config.song_id, config)
    previous = runner_mod.load_song_doc(stage_song_path(config.song_id, 10))
    _doc, report = runner_mod.run_stage(
        config.song_id, 11, _stage_fns()[11], config, previous_doc=previous
    )
    _print_report(report)
    return 0


def _cmd_batch(args: argparse.Namespace) -> int:
    import yaml

    manifest_file = Path(args.manifest)
    if not manifest_file.is_file():
        raise CliError(f"batch manifest {manifest_file} does not exist")
    data = yaml.safe_load(manifest_file.read_text(encoding="utf-8"))
    songs = data.get("songs") if isinstance(data, dict) else None
    if not isinstance(songs, list) or not songs:
        raise CliError(f"batch manifest {manifest_file} needs a 'songs' list")

    from concurrent.futures import ThreadPoolExecutor

    def build_one(song: dict[str, Any]) -> tuple[str, str]:
        song_id = song["songId"]
        validate_song_id(song_id)
        try:
            from pipeline.build.stage_ingest import run_ingest

            config = BuildConfig(song_id=song_id, metadata={}, renderer=song.get("renderer", "sine"))
            if song.get("source"):
                doc, report = run_ingest(
                    Path(song["source"]),
                    song_id,
                    song["sourceRef"],
                    config=config,
                )
                runner_mod.persist_stage_1(song_id, doc, report)
            runner_mod.ensure_ingested(song_id, config)
            stage_fns = _stage_fns()
            runner_mod.run_stages(config, stage_fns, from_stage=2, to_stage=BUILD_LAST_STAGE)
            if song.get("publish"):
                env = song["publish"]
                pub_config = BuildConfig(
                    song_id=song_id,
                    metadata={"env": env},
                    renderer=song.get("renderer", "sine"),
                )
                previous = runner_mod.load_song_doc(runner_mod.stage_song_path(song_id, 10))
                runner_mod.run_stage(song_id, 11, stage_fns[11], pub_config, previous_doc=previous)
            return song_id, "OK"
        except PipelineError as exc:
            # An expected pipeline failure fails THIS song only; the batch
            # continues with the rest (review M11).
            return song_id, f"FAILED: {exc.render()}"
        except Exception as exc:
            # Truly unexpected exceptions abort the batch — but name the song
            # that broke it so the abort is actionable, not a bare traceback.
            raise RuntimeError(
                f"batch aborted on song {song_id}: unexpected "
                f"{type(exc).__name__}: {exc}"
            ) from exc

    workers = max(1, args.parallel or 1)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(build_one, songs))
    failures = 0
    for song_id, status in results:
        print(f"    {song_id}: {status}")
        failures += status != "OK"
    print(f"==> batch complete: {len(results) - failures}/{len(results)} OK")
    return 1 if failures else 0


def _print_report(report: Any) -> None:
    title = f"stage {report.stage} {report.name}"
    print(f"==> {title}")
    for warning in report.warnings:
        print(f"    WARNING: {warning}")
    for note in report.info:
        print(f"    {note}")
    for error in report.errors:
        print(f"    ERROR: {error}", file=sys.stderr)
    if report.properties:
        props = ", ".join(f"{key}={value}" for key, value in sorted(report.properties.items()))
        print(f"    [{props}]")


# ---------------------------------------------------------------------------
# argument surface
# ---------------------------------------------------------------------------


def _add_metadata_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--title", help="override the score title")
    parser.add_argument("--composer", help="override the score composer")
    parser.add_argument("--subtitle", help="optional subtitle")
    parser.add_argument("--arranger", default=None, help="arranger credit (default: KeyQuest)")
    parser.add_argument("--genre", default=None, help="library genre facet")
    parser.add_argument("--era", default=None, help="library era facet")
    parser.add_argument("--mood", nargs="+", default=None, help="library mood facets (one or more)")


def _add_build_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--renderer", choices=["sine", "fluidsynth"], default="sine")
    parser.add_argument("--timestamp", choices=["now"], help="--timestamp now opts into wall-clock build time")
    parser.add_argument("--strict", action="store_true", help="treat warnings as failures")
    parser.add_argument("--tempo", type=int, help="explicit BPM override when the score has no metronome mark")
    parser.add_argument("--min-app-version", type=int, default=1)
    parser.add_argument("--pack-version", type=int, default=1)
    parser.add_argument("--rights-ref", help="rights record reference (default derived from song id)")
    parser.add_argument("--soundfont", help="soundfont path for --renderer fluidsynth")
    parser.add_argument("--soundfont-sha256", help="pinned soundfont sha256 for --renderer fluidsynth")


def _add_eval_subparser(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("eval", help=_EVAL_HELP)
    parser.add_argument("audio", help="path to the audio file (wav/flac/mp3)")
    parser.add_argument("midi", help="path to the aligned ground-truth MIDI")
    parser.add_argument(
        "--wrapper",
        choices=["ground-truth", "pyin", "engine-yin"],
        default="pyin",
        help="model wrapper to score (default: pyin)",
    )
    parser.add_argument("--json", metavar="OUT", help="also write machine-readable results as JSON to OUT")
    parser.add_argument("--onset-tol", type=float, default=0.05, help="onset matching tolerance in seconds")
    parser.add_argument("--offset-tol", type=float, default=0.2, help="mir_eval offset_ratio (fraction of reference note duration)")
    parser.add_argument("--pitch-tol-cents", type=float, default=50.0, help="pitch matching tolerance in cents")
    parser.add_argument("--sr", type=int, default=22050, help="analysis sample rate (default: 22050)")


def _run_eval(args: argparse.Namespace) -> NoReturn:
    from pipeline.eval.metrics import MetricConfig
    from pipeline.eval.model_wrappers import (
        EngineYinWrapper,
        GroundTruthWrapper,
        PyinBaselineWrapper,
    )
    from pipeline.eval.run import evaluate_pair

    config = MetricConfig(
        onset_tol=args.onset_tol,
        offset_tol=args.offset_tol,
        pitch_tol_semitones=args.pitch_tol_cents / 100.0,
    )
    if args.wrapper == "ground-truth":
        wrapper = GroundTruthWrapper(args.midi)
    elif args.wrapper == "engine-yin":
        wrapper = EngineYinWrapper()
    else:
        wrapper = PyinBaselineWrapper()

    result = evaluate_pair(args.audio, args.midi, wrapper, config, sr=args.sr)

    print(f"==> eval: {args.audio} vs {args.midi}")
    print(f"    wrapper: {result['wrapper']}")
    print(
        f"    notes: ref={result['ref_note_count']} est={result['est_note_count']} "
        f"(tp={result['tp']} fp={result['fp']} fn={result['fn']})"
    )
    print(
        f"    note precision/recall/F1: {result['note_precision']:.4f} / "
        f"{result['note_recall']:.4f} / {result['note_f1']:.4f}"
    )
    print(
        f"    onset error median/p95/p99 (s): {result['onset_error_median']:.4f} / "
        f"{result['onset_error_p95']:.4f} / {result['onset_error_p99']:.4f}"
    )
    print(f"    chord recall by size: {result['chord_recall_by_size']}")
    print(f"    octave error rate: {result['octave_error_rate']:.4f}")

    if args.json:
        Path(args.json).write_text(json.dumps(result, indent=2, default=str))
        print(f"    JSON written to {args.json}")

    raise SystemExit(0)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="pipeline",
        description="KeyQuest content build pipeline: MusicXML/MIDI → SongPack.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    ingest = subparsers.add_parser("ingest", help="record source + provenance into the content store (stage 1)")
    ingest.add_argument("source_file", help="path to the MusicXML source (.musicxml/.xml)")
    ingest.add_argument("--song-id", required=True, help="stable song identifier")
    ingest.add_argument("--source", required=False, help="provenance reference (e.g. imslp:12345) — REQUIRED")
    ingest.add_argument("--license-claim", help="the source's licensing claim, verbatim")
    ingest.add_argument("--editor", help="edition editor")
    ingest.add_argument("--publication-year", type=int, help="edition publication year")
    ingest.add_argument("--edition", help="edition name/reference")
    ingest.add_argument("--notes", help="free-text provenance notes")

    build = subparsers.add_parser("build", help="run the normalization→levels stages (2–10) with --from-stage controls")
    build.add_argument("song_id")
    build.add_argument("--from-stage", type=int, help="resume EXACTLY at stage N (requires the stage N-1 intermediate)")
    build.add_argument("--resume-nearest", action="store_true", help="with --from-stage: walk back to the nearest earlier intermediate instead of failing")
    build.add_argument("--stage", type=int, help="run only up to stage N")
    build.add_argument("--level", help="levels to emit (v0 emits level 1 only)")
    _add_metadata_arguments(build)
    _add_build_options(build)
    build.add_argument("--no-audio", action="store_true", help="skip audio rendering (fast iteration; pack is JSON-only)")

    audio = subparsers.add_parser("audio", help="render stems via the sine|fluidsynth renderer (stage 9)")
    audio.add_argument("song_id")
    _add_build_options(audio)

    validate = subparsers.add_parser("validate", help="validate the ingested source (stage 2)")
    validate.add_argument("song_id")
    validate.add_argument("--strict", action="store_true", help="treat warnings as failures")

    diff = subparsers.add_parser("diff", help="diff a build against another pack or 'published'")
    diff.add_argument("song_id")
    diff.add_argument("--against", required=True, help="path to another .pack file, or 'published'")

    publish = subparsers.add_parser("publish", help="run the pre-publish gate and flip the catalog pointer (stage 11)")
    publish.add_argument("song_id")
    publish.add_argument("--env", required=True, choices=["staging", "prod"])
    _add_build_options(publish)

    batch = subparsers.add_parser("batch", help="drive a manifest of songs with --parallel workers")
    batch.add_argument("--manifest", required=True, help="YAML manifest with a 'songs' list")
    batch.add_argument("--parallel", type=int, default=1, help="parallel workers")

    _add_eval_subparser(subparsers)
    return parser


def main(argv: list[str] | None = None) -> int:
    """Parse and dispatch. Returns the process exit code; never prints a
    traceback for expected pipeline failures (bad-input corpus discipline)."""
    parser = build_parser()
    args = parser.parse_args(argv)

    handlers = {
        "ingest": _cmd_ingest,
        "build": _cmd_build,
        "audio": _cmd_audio,
        "validate": _cmd_validate,
        "diff": _cmd_diff,
        "publish": _cmd_publish,
        "batch": _cmd_batch,
    }

    try:
        if args.command == "eval":
            _run_eval(args)
            return 0
        return handlers[args.command](args)
    except PipelineError as exc:
        print(f"pipeline: error: {exc.render()}", file=sys.stderr)
        return 1
    except Exception as exc:
        if os.environ.get("KEYQUEST_PIPELINE_DEBUG") == "1":
            raise
        print(
            f"pipeline: internal error: {type(exc).__name__}: {exc} "
            "(set KEYQUEST_PIPELINE_DEBUG=1 for a traceback)",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())