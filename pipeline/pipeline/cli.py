"""Command-line interface for the KeyQuest content pipeline (plan §8.2).

Only stdlib imports at module top level, so this file compiles and `--help`
works before dependencies are installed. Stage implementations land in later
phases (P1/P2).
"""

from __future__ import annotations

import argparse
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

    args = parser.parse_args(argv)

    # TODO(P1): dispatch args.command to the matching stage module.
    parser.error(f"stage '{args.command}' is not implemented yet (TODO P1)")


if __name__ == "__main__":
    main()