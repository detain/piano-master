"""Command-line interface for the KeyQuest content pipeline (plan §8.2).

Only stdlib imports at module top level, so this file compiles and `--help`
works before dependencies are installed. Stage implementations land in later
phases (P1/P2); ``eval`` is live in P0.3.1.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import NoReturn

# CLI surface mirrors plan §8.2 ("also the CMS's backend").
_STAGES: dict[str, str] = {
    "ingest": "record source + provenance into the content store (stage 1)",
    "build": "run the normalization→levels stages (2–8) with --from-stage/--level controls",
    "audio": "render stems via the dgx|fluidsynth renderer (stage 9)",
    "validate": "validate a song or intermediate artifact; --strict re-checks (stage 2)",
    "diff": "diff a build against another version or 'published' (stage 10/11 tooling)",
    "publish": "run the pre-publish gate and flip the catalog pointer (stage 11)",
    "batch": "drive a manifest of songs with --parallel workers (§8.2.12)",
}

# Live tooling that does not map to a §8.2 pipeline stage.
_EVAL_HELP = "score a model wrapper against aligned ground-truth MIDI (P0.3.1)"


def _add_eval_subparser(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("eval", help=_EVAL_HELP)
    parser.add_argument("audio", help="path to the audio file (wav/flac/mp3)")
    parser.add_argument("midi", help="path to the aligned ground-truth MIDI")
    parser.add_argument(
        "--wrapper",
        choices=["ground-truth", "pyin"],
        default="pyin",
        help="model wrapper to score (default: pyin)",
    )
    parser.add_argument(
        "--json",
        metavar="OUT",
        help="also write machine-readable results as JSON to OUT",
    )
    parser.add_argument(
        "--onset-tol",
        type=float,
        default=0.05,
        help="onset matching tolerance in seconds (default: 0.05)",
    )
    parser.add_argument(
        "--offset-tol",
        type=float,
        default=0.2,
        help=(
            "mir_eval offset_ratio: offset must land within this fraction of "
            "the reference note duration (default: 0.2)"
        ),
    )
    parser.add_argument(
        "--sr",
        type=int,
        default=22050,
        help="analysis sample rate for pitch-tracking wrappers (default: 22050)",
    )


def main(argv: list[str] | None = None) -> NoReturn:
    """Parse arguments and dispatch to the requested pipeline stage.

    ``parser.error`` raises SystemExit(2), so this never returns normally;
    the dispatch table above keeps the surface discoverable via --help.
    """
    parser = argparse.ArgumentParser(
        prog="pipeline",
        description="KeyQuest content build pipeline: MusicXML/MIDI → SongPack.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    for name, help_text in _STAGES.items():
        subparsers.add_parser(name, help=help_text)
    _add_eval_subparser(subparsers)

    args = parser.parse_args(argv)

    if args.command == "eval":
        _run_eval(args)

    # TODO(P1): dispatch args.command to the matching stage module.
    parser.error(f"stage '{args.command}' is not implemented yet (TODO P1)")


def _run_eval(args: argparse.Namespace) -> NoReturn:
    """Score one (audio, aligned MIDI) pair and print/emit the metrics."""
    from pipeline.eval.metrics import MetricConfig
    from pipeline.eval.model_wrappers import GroundTruthWrapper, PyinBaselineWrapper
    from pipeline.eval.run import evaluate_pair

    config = MetricConfig(onset_tol=args.onset_tol, offset_tol=args.offset_tol)
    if args.wrapper == "ground-truth":
        wrapper = GroundTruthWrapper(args.midi)
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


if __name__ == "__main__":
    main()