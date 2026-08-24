"""Build every golden fixture (ingest + full build incl. audio) into the
content store/builds dirs named by KEYQUEST_STORE_DIR / KEYQUEST_BUILDS_DIR.

The determinism CI step runs this twice with different dirs and byte-compares
the resulting packs (plan §8.2.10, §20 P1.2). Usage:

    KEYQUEST_STORE_DIR=/tmp/a/store KEYQUEST_BUILDS_DIR=/tmp/a/builds \\
        pipeline/.venv/bin/python pipeline/scripts/build_golden.py
"""

from __future__ import annotations

import sys
from pathlib import Path

FIXTURES = Path(__file__).resolve().parents[1] / "tests" / "fixtures"
GOLDEN_FIXTURES = [
    "pickup",
    "key_change",
    "triplets",
    "six_eight",
    "ties_across",
    "repeats_voltas",
    "grace_ornaments",
    "two_hands",
    "multi_voice",
]


def main() -> int:
    from pipeline.build.config import BuildConfig, pack_path
    from pipeline.build.runner import persist_stage_1, run_stages
    from pipeline.build.stage_ingest import run_ingest
    from pipeline.cli import _stage_fns

    builds_dir = None
    for fixture in GOLDEN_FIXTURES:
        song_id = f"golden-{fixture}"
        config = BuildConfig(song_id=song_id).with_paths()
        builds_dir = str(config.builds)
        doc, report = run_ingest(
            FIXTURES / f"{fixture}.musicxml",
            song_id,
            f"test:{fixture}",
            license_claim="test fixture",
            config=config,
        )
        persist_stage_1(song_id, doc, report)
        run_stages(config, _stage_fns(), from_stage=2, to_stage=10)
        pack = pack_path(song_id)
        print(f"{fixture}: {pack} ({pack.stat().st_size} bytes)")
    print(f"==> built {len(GOLDEN_FIXTURES)} golden fixtures in {builds_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())