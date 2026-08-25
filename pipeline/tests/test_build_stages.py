"""Golden-path build tests for the P1.2 pipeline stages (plan §8.2, §8.2.13).

Every awkward-case fixture (pickup, key change, triplets, 6/8, ties across
the chunk boundary, repeats+voltas, grace/ornaments, two hands, multi-voice)
builds end-to-end into a SongPack that validates against the canonical schema.
Determinism, stage purity, idempotent normalization, tie/grace/ornament and
chunking semantics are asserted directly.
"""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

import pytest

from pipeline.build.config import BuildConfig, pack_path, stage_dir
from pipeline.build.runner import run_stage, run_stages
from pipeline.songpack.validator import validate_pack

FIXTURES = Path(__file__).resolve().parent / "fixtures"
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

ALL_STAGE_FNS = None


def stage_fns() -> dict[int, object]:
    global ALL_STAGE_FNS
    if ALL_STAGE_FNS is None:
        from pipeline.cli import _stage_fns

        ALL_STAGE_FNS = _stage_fns()
    return ALL_STAGE_FNS


def ingest(song_id: str, fixture_name: str) -> BuildConfig:
    from pipeline.build.stage_ingest import run_ingest

    config = BuildConfig(song_id=song_id).with_paths()
    doc, report = run_ingest(
        FIXTURES / f"{fixture_name}.musicxml",
        song_id,
        f"test:{fixture_name}-v1",
        license_claim="test fixture",
        config=config,
    )
    from pipeline.build.runner import persist_stage_1

    persist_stage_1(song_id, doc, report)
    return config


def build_full(song_id: str, fixture_name: str, *, audio: bool = True) -> BuildConfig:
    config = ingest(song_id, fixture_name)
    fns = stage_fns()
    if not audio:
        fns = {key: fn for key, fn in fns.items() if key != 9}
    run_stages(config, fns, from_stage=2, to_stage=10)
    return config


def read_doc(song_id: str, stage: int) -> dict:
    return json.loads(stage_dir(song_id, stage).joinpath("song.json").read_text())


def read_pack_json(song_id: str, pack_version: int = 1, filename: str = "manifest.json") -> dict:
    with zipfile.ZipFile(pack_path(song_id, pack_version)) as zf:
        return json.loads(zf.read(filename))


@pytest.mark.parametrize("fixture", GOLDEN_FIXTURES)
def test_fixture_builds_and_pack_validates(fixture: str) -> None:
    build_full(f"build-{fixture}", fixture, audio=False)
    report = read_pack_json(f"build-{fixture}", filename="manifest.json")
    assert report["songId"] == f"build-{fixture}"
    assert report["format"] == "songpack/v1"
    assert report["durationBeats"] > 0
    # The pack validates against the canonical schema + semantic checks.
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(pack_path(f"build-{fixture}")) as zf:
            zf.extractall(tmp)
        validation = validate_pack(tmp)
        assert validation.valid, f"fixture {fixture}: {validation.errors}"


def test_pickup_beats_and_duration() -> None:
    build_full("t-pickup", "pickup", audio=False)
    manifest = read_pack_json("t-pickup")
    assert manifest["pickupBeats"] == 1.0
    assert manifest["durationBeats"] == 17.0


def test_key_change_mid_song() -> None:
    build_full("t-key", "key_change", audio=False)
    manifest = read_pack_json("t-key")
    assert manifest["keySignatures"] == [
        {"atBeat": 0.0, "fifths": 0, "mode": "major"},
        {"atBeat": 16.0, "fifths": 1, "mode": "major"},
    ]
    notes = read_pack_json("t-key", filename="notes.json")["levels"]["1"]
    sharps = [n for n in notes if n.get("accidental") == "sharp"]
    assert sharps, "F# notes must carry display-only sharp accidentals"


def test_triplets_use_exact_fractional_beats() -> None:
    build_full("t-trip", "triplets", audio=False)
    notes = read_pack_json("t-trip", filename="notes.json")["levels"]["1"]
    durations = {n["durBeats"] for n in notes}
    assert 1.0 in durations
    assert any(abs(d - 1 / 3) < 1e-9 for d in durations), (
        "triplet eighths must be 1/3 beat exactly, not grid-rounded"
    )


def test_six_eight_beat_unit_is_quarter() -> None:
    build_full("t-68", "six_eight", audio=False)
    manifest = read_pack_json("t-68")
    assert manifest["durationBeats"] == 12.0  # 4 measures × 3 beats
    assert manifest["timeSignatures"][0] == {"atBeat": 0.0, "numerator": 6, "denominator": 8}
    notes = read_pack_json("t-68", filename="notes.json")["levels"]["1"]
    assert {n["durBeats"] for n in notes} == {0.5}  # eighths = half a quarter-beat


def test_tie_across_chunk_boundary_is_index_based() -> None:
    build_full("t-ties", "ties_across", audio=False)
    notes = read_pack_json("t-ties", filename="notes.json")["levels"]["1"]
    tied = [n for n in notes if "tieToIndex" in n]
    assert tied, "expected at least one tie in the fixture"
    tie = tied[0]
    assert tie["startBeat"] == 15.0
    target = notes[tie["tieToIndex"]]
    assert target["pitch"] == tie["pitch"]
    assert target["startBeat"] == 16.0
    # The tie reference is an index into the WHOLE level array — both records
    # are present and the chunker must not have split them.
    assert tie["tieToIndex"] > notes.index(tie)
    chunks = read_pack_json("t-ties", filename="chunks.json")
    for chunk in chunks:
        assert not (tie["startBeat"] < chunk["endBeat"] <= target["startBeat"]), (
            f"chunk {chunk['chunkId']} splits the tie"
        )


def test_chunker_never_splits_a_tie() -> None:
    build_full("t-ties2", "ties_across", audio=False)
    chunks = read_pack_json("t-ties2", filename="chunks.json")
    for chunk in chunks:
        assert chunk["startBeat"] < chunk["endBeat"]
    # No chunk boundary may sit between the tie's two notes.
    notes = read_pack_json("t-ties2", filename="notes.json")["levels"]["1"]
    tie = next(n for n in notes if "tieToIndex" in n)
    for chunk in chunks:
        boundary = chunk["endBeat"]
        if tie["startBeat"] < boundary <= notes[tie["tieToIndex"]]["startBeat"]:
            pytest.fail(f"chunk {chunk['chunkId']} boundary splits a tie at beat {boundary}")
    # And every chunk boundary sits on a measure boundary (never mid-beat);
    # the final chunk may end at the total duration.
    doc = read_doc("t-ties2", 8)
    measure_starts = {round(m["startBeat"], 6) for m in doc["measures"]}
    duration = doc["metadata"]["durationBeats"]
    for chunk in chunks:
        assert round(chunk["startBeat"], 6) in measure_starts
        assert round(chunk["endBeat"], 6) in measure_starts or round(chunk["endBeat"], 6) == round(duration, 6)


def test_mixed_tie_chord_only_tied_voice_gets_tie_to_index() -> None:
    """Review M1: a chord where only the top voice ties across a barline must
    not leak that tie onto the other chord tones (per-note tie extraction)."""
    build_full("t-mixed-tie", "mixed_tie_chord", audio=False)
    notes = read_pack_json("t-mixed-tie", filename="notes.json")["levels"]["1"]
    # Beat 4.0 is the M2 chord C5+G4+E4+C4 — only C5 (72) ties to M3.
    chord_tones = [n for n in notes if n["startBeat"] == 4.0]
    assert {n["pitch"] for n in chord_tones} == {60, 64, 67, 72}
    tied = [n for n in notes if "tieToIndex" in n]
    assert [n["pitch"] for n in tied] == [72], "only the tied voice may carry tieToIndex"
    tie = tied[0]
    target = notes[tie["tieToIndex"]]
    assert target["pitch"] == 72
    assert target["startBeat"] == 8.0  # the M3 stop chord
    assert tie["tieToIndex"] > notes.index(tie)
    # The pack validates against the canonical schema + semantic checks.
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(pack_path("t-mixed-tie")) as zf:
            zf.extractall(tmp)
        validation = validate_pack(tmp)
        assert validation.valid, f"mixed_tie_chord: {validation.errors}"


def test_repeats_voltas_expand_into_linear_timeline() -> None:
    build_full("t-rep", "repeats_voltas", audio=False)
    doc = read_doc("t-rep", 8)
    assert [(m["measure"], m["pass"]) for m in doc["measures"]] == [
        (1, 1), (2, 1), (3, 1), (4, 2), (5, 2),
    ]
    assert doc["repeatMap"] == [
        {"label": "A", "startBeat": 0.0, "endBeat": 12.0, "source": "main"},
        {"label": "B", "startBeat": 12.0, "endBeat": 20.0, "source": "main"},
    ]
    manifest = read_pack_json("t-rep")
    assert len(manifest["repeatMap"]) == 2


def test_grace_notes_weight_zero_and_ornament_expansion() -> None:
    build_full("t-grace", "grace_ornaments", audio=False)
    notes = read_pack_json("t-grace", filename="notes.json")["levels"]["1"]
    grace = [n for n in notes if n.get("scoringWeight") == 0]
    assert len(grace) == 1
    assert grace[0]["pitch"] == 74  # D5
    trill = [n for n in notes if n.get("isOrnamentExpansion") and 72 <= n["pitch"] <= 76]
    # A whole-note (4-beat) trill on C5 expands into 16th subdivisions:
    # 4.0 / 0.25 = 16 notes alternating C5 (72) and D5 (74).
    trill_before_mordent = [n for n in trill if n["startBeat"] < 8.0]
    assert len(trill_before_mordent) == 16
    assert trill_before_mordent[0]["pitch"] == 74  # starts on the upper neighbor
    assert trill_before_mordent[1]["pitch"] == 72
    assert all(n.get("scoringWeight") == pytest.approx(0.2) for n in trill_before_mordent)
    mordent = [n for n in notes if n.get("isOrnamentExpansion") and 70 <= n["pitch"] <= 71]
    assert len(mordent) == 3  # main → lower → main
    assert mordent[1]["pitch"] == 70  # A4 = lower neighbor of B4


def test_multi_voice_renumbering_and_simultaneity() -> None:
    build_full("t-mv", "multi_voice", audio=False)
    notes = read_pack_json("t-mv", filename="notes.json")["levels"]["1"]
    voices = sorted({n["voice"] for n in notes})
    assert voices == [1, 2]
    at_zero = [n for n in notes if n["startBeat"] == 0]
    assert sorted(n["pitch"] for n in at_zero) == [60, 62]  # C4 and D4 together


def test_two_hands_assignment() -> None:
    build_full("t-th", "two_hands", audio=False)
    notes = read_pack_json("t-th", filename="notes.json")["levels"]["1"]
    hands = {n["hand"] for n in notes}
    assert hands == {"R", "L"}
    doc = read_doc("t-th", 4)
    assert doc["hands"]["lowConfidence"] == []  # clean staff-based assignment


def test_normalization_is_idempotent() -> None:
    config = ingest("t-idem", "two_hands")
    run_stages(config, stage_fns(), from_stage=2, to_stage=2)
    from pipeline.build.stage_normalize import run_stage_normalize

    doc = read_doc("t-idem", 2)
    doc1, _ = run_stage_normalize(doc, config)
    doc2, _ = run_stage_normalize(doc, config)
    assert doc1["notes"] == doc2["notes"]
    assert doc1["measures"] == doc2["measures"]
    assert doc1["repeatMap"] == doc2["repeatMap"]


def test_build_is_byte_identical_twice(monkeypatch) -> None:
    """The §8.2.10 determinism contract: build the same fixture twice in
    separate builds dirs and byte-compare the packs (incl. checksums)."""
    import tempfile

    with tempfile.TemporaryDirectory() as dir_a, tempfile.TemporaryDirectory() as dir_b:
        monkeypatch.setenv("KEYQUEST_BUILDS_DIR", dir_a)
        build_full("t-det", "pickup", audio=True)
        pack_a = pack_path("t-det").read_bytes()

        monkeypatch.setenv("KEYQUEST_BUILDS_DIR", dir_b)
        build_full("t-det", "pickup", audio=True)
        pack_b = pack_path("t-det").read_bytes()

        assert pack_a == pack_b, "two builds of the same input must be byte-identical"


def test_source_date_epoch_controls_build_timestamp(monkeypatch) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as builds:
        monkeypatch.setenv("KEYQUEST_BUILDS_DIR", builds)
        monkeypatch.setenv("SOURCE_DATE_EPOCH", "1700000000")
        build_full("t-epoch", "pickup", audio=False)
        manifest = read_pack_json("t-epoch")
        assert manifest["buildInfo"]["buildTimestamp"] == "2023-11-14T22:13:20Z"
        # The content hash must NOT change with the timestamp.
        content_hash_epoch = read_doc("t-epoch", 10)["pack"]["contentHash"]

        monkeypatch.delenv("SOURCE_DATE_EPOCH")
        build_full("t-epoch", "pickup", audio=False)
        manifest2 = read_pack_json("t-epoch")
        assert manifest2["buildInfo"]["buildTimestamp"] == "1970-01-01T00:00:00Z"
        content_hash_default = read_doc("t-epoch", 10)["pack"]["contentHash"]
        assert content_hash_epoch == content_hash_default


def test_stage_purity_resume_from_intermediate(monkeypatch) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as builds:
        monkeypatch.setenv("KEYQUEST_BUILDS_DIR", builds)
        config = ingest("t-resume", "two_hands")
        run_stages(config, stage_fns(), from_stage=2, to_stage=8)
        full_doc = read_doc("t-resume", 8)

        # Resume from stage 4's stored intermediate (fresh dir, same store):
        # re-running 5-8 must produce the identical stage-8 doc.
        run_stages(config, stage_fns(), from_stage=5, to_stage=8)
        resumed_doc = read_doc("t-resume", 8)
        assert full_doc == resumed_doc


def test_audio_stems_in_pack(monkeypatch) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as builds:
        monkeypatch.setenv("KEYQUEST_BUILDS_DIR", builds)
        build_full("t-audio-pack", "pickup", audio=True)
        with zipfile.ZipFile(pack_path("t-audio-pack")) as zf:
            names = zf.namelist()
        for expected in (
            "audio/l1_backing_full.opus",
            "audio/l1_backing_norh.opus",
            "audio/l1_backing_nolh.opus",
            "audio/l1_backing_micsafe.opus",
            "audio/l1_reference.opus",
            "audio/count_in.opus",
            "checksums.json",
        ):
            assert expected in names, f"missing {expected} in pack"
        manifest = read_pack_json("t-audio-pack")
        assert manifest["audioProfile"]["renderer"] == "sine"
        assert manifest["audioProfile"]["loudnessLufs"] == -16.0


def test_batch_continues_past_a_failed_song(tmp_path) -> None:
    """Review M11: a per-song pipeline failure must not abort the batch — the
    failed song is reported FAILED and the healthy song still builds."""
    import argparse

    from pipeline.cli import _cmd_batch

    BAD = Path(__file__).resolve().parent / "bad"
    manifest = tmp_path / "manifest.yaml"
    manifest.write_text(
        "songs:\n"
        f"  - songId: batch-good\n"
        f"    source: {FIXTURES / 'pickup.musicxml'}\n"
        "    sourceRef: test:batch-good\n"
        f"  - songId: batch-bad\n"
        f"    source: {BAD / 'measure_sum_mismatch.musicxml'}\n"
        "    sourceRef: test:batch-bad\n",
        encoding="utf-8",
    )
    rc = _cmd_batch(argparse.Namespace(manifest=str(manifest), parallel=1))
    assert rc == 1  # one song failed, one succeeded
    assert pack_path("batch-good").is_file(), "the healthy song must still build"


def test_publish_requires_provenance_and_gate(monkeypatch) -> None:
    import tempfile


    with tempfile.TemporaryDirectory() as builds, tempfile.TemporaryDirectory() as catalog:
        monkeypatch.setenv("KEYQUEST_BUILDS_DIR", builds)
        monkeypatch.setenv("KEYQUEST_CATALOG_DIR", catalog)
        config = ingest("t-pub", "pickup")
        # Ingest without --source must fail at ingest time (provenance gate).
        from pipeline.build.errors import IngestError
        from pipeline.build.stage_ingest import run_ingest

        with pytest.raises(IngestError, match="requires --source"):
            run_ingest(FIXTURES / "pickup.musicxml", "t-no-source", "", config=config)
        # Build then publish flips the pointer.
        run_stages(config, stage_fns(), from_stage=2, to_stage=10)
        doc10 = read_doc("t-pub", 10)
        pub_config = BuildConfig(
            song_id="t-pub", metadata={"env": "staging"}, renderer="sine"
        ).with_paths()
        doc_pub, report = run_stage("t-pub", 11, stage_fns()[11], pub_config, previous_doc=doc10)
        assert report.errors == []
        assert doc_pub["published"]["env"] == "staging"


def test_strict_build_fails_on_warnings() -> None:
    """Review M5: --strict must fail the build on the first stage warning;
    without it the same input builds fine."""
    from pipeline.build.errors import StrictError
    from pipeline.build.stage_ingest import run_ingest
    from pipeline.build.runner import persist_stage_1

    BAD = Path(__file__).resolve().parent / "bad"
    config = BuildConfig(song_id="t-strict").with_paths()
    doc, report = run_ingest(
        BAD / "chord_span.musicxml", "t-strict", "test:chord_span", config=config
    )
    persist_stage_1("t-strict", doc, report)
    fns = {key: fn for key, fn in stage_fns().items() if key != 9}

    # Without strict: the stage-2 warning is reported but the build proceeds.
    loose = BuildConfig(song_id="t-strict").with_paths()
    reports = run_stages(loose, fns, from_stage=2, to_stage=10)
    assert any(report.warnings for report in reports)

    # With strict: the same warning fails the build with a named error.
    strict = BuildConfig(song_id="t-strict", strict=True).with_paths()
    with pytest.raises(StrictError, match="strict build: stage validate"):
        run_stages(strict, fns, from_stage=2, to_stage=10)


def test_from_stage_exact_intermediate_required() -> None:
    """Review M8: --from-stage N must fail loudly when the exact N-1
    intermediate is missing — never silently downgrade to an older one."""
    from pipeline.build.errors import PipelineError

    config = ingest("t-exact", "pickup")
    run_stages(config, stage_fns(), from_stage=2, to_stage=4)
    with pytest.raises(PipelineError, match="intermediate for stage 5"):
        run_stages(config, stage_fns(), from_stage=6, to_stage=6)


def test_from_stage_resume_nearest_walks_back() -> None:
    """Review M8: --resume-nearest keeps the documented walk-to-nearest
    behavior when the exact intermediate is missing."""
    config = ingest("t-nearest", "pickup")
    run_stages(config, stage_fns(), from_stage=2, to_stage=3)
    # Stage 4 (hands) never ran; resume-nearest must fall back to stage 3.
    run_stages(config, stage_fns(), from_stage=5, to_stage=5, resume_nearest=True)
    assert stage_dir("t-nearest", 5).joinpath("song.json").is_file()


def test_chord_tones_share_beam_group() -> None:
    """Review M10: notes at the same startBeat (chord tones) must share one
    beamGroup, while a later note starts a new group."""
    from pipeline.build.stage_layout import assign_layout

    notes = [
        {"pitch": 60, "voice": 1, "staff": 1, "startBeat": 0.0, "durBeats": 0.5, "_seq": 0},
        {"pitch": 64, "voice": 1, "staff": 1, "startBeat": 0.0, "durBeats": 0.5, "_seq": 1},
        {"pitch": 67, "voice": 1, "staff": 1, "startBeat": 0.0, "durBeats": 0.5, "_seq": 2},
        {"pitch": 62, "voice": 1, "staff": 1, "startBeat": 1.0, "durBeats": 0.5, "_seq": 3},
    ]
    assign_layout(notes)
    chord_groups = {note["beamGroup"] for note in notes[:3]}
    assert len(chord_groups) == 1, f"chord tones split across beam groups: {chord_groups}"
    assert notes[3]["beamGroup"] != notes[0]["beamGroup"]


def test_pack_write_is_atomic(monkeypatch) -> None:
    """Review M6: a failed zip write must never leave a partial pack at the
    final path — the previous pack survives and no temp file is left behind."""
    import tempfile

    from pipeline.build.errors import PackError
    from pipeline.build.runner import load_song_doc, stage_song_path
    from pipeline.build.stage_pack import run_stage_pack

    with tempfile.TemporaryDirectory() as builds:
        monkeypatch.setenv("KEYQUEST_BUILDS_DIR", builds)
        config = ingest("t-atomic", "pickup")
        fns = {key: fn for key, fn in stage_fns().items() if key != 9}
        run_stages(config, fns, from_stage=2, to_stage=8)
        doc8 = load_song_doc(stage_song_path("t-atomic", 8))

        # A healthy first pack exists at the final path.
        run_stage("t-atomic", 10, stage_fns()[10], config, previous_doc=doc8)
        final_pack = pack_path("t-atomic")
        before = final_pack.read_bytes()

        # Force the zip write to fail: the final path must keep the old pack
        # byte-for-byte, and the temp file must be cleaned up.
        def boom(*args, **kwargs):
            raise PackError("simulated disk failure")

        monkeypatch.setattr("pipeline.build.stage_pack.write_zip_deterministic", boom)
        with pytest.raises(PackError, match="simulated disk failure"):
            run_stage("t-atomic", 10, stage_fns()[10], config, previous_doc=doc8)

        assert final_pack.read_bytes() == before
        assert list(final_pack.parent.glob("*.tmp")) == []