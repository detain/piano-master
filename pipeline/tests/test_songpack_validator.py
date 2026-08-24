"""Tests for the SongPack v1 validator (plan §20 P1.1.2).

Covers the golden fixtures, the negative cases every consumer must reject,
and the forward-compatibility guarantees that make additive evolution safe
(plan §8.1.9):

1. Golden fixtures (content/fixtures/songpack-v1/*) validate clean — schema
   AND semantic checks.
2. Negative cases: a `seconds` key in a note, a missing required field, a
   pitch outside 21-108, a broken tieToIndex, a `format` other than
   songpack/v1.
3. Forward-compat positive: unknown keys on the manifest and on a note are
   IGNORED (still valid) while `seconds` is still rejected.
4. The root oneOf dispatcher (the entry point PHP/Kotlin consumers use) also
   rejects a `seconds` note, and accepts unknown keys.
"""

from __future__ import annotations

import copy
import json
import shutil
from pathlib import Path

import jsonschema
import pytest

from pipeline.songpack.validator import load_schema, validate_pack

FIXTURES_ROOT = Path(__file__).resolve().parents[2] / "content" / "fixtures" / "songpack-v1"
GOLDEN_FIXTURES = sorted(p for p in FIXTURES_ROOT.iterdir() if p.is_dir())

PACK_FILES = ("manifest.json", "notes.json", "chunks.json", "skills.json")


def _copy_fixture(tmp_path: Path, fixture_id: str) -> Path:
    """Copy a golden fixture into tmp_path so tests never mutate content/."""
    dest = tmp_path / fixture_id
    shutil.copytree(FIXTURES_ROOT / fixture_id, dest)
    return dest


def _mutate(pack_dir: Path, filename: str, mutator) -> None:
    """Load one pack file, apply mutator, write it back."""
    path = pack_dir / filename
    document = json.loads(path.read_text(encoding="utf-8"))
    mutator(document)
    path.write_text(json.dumps(document, indent=2, sort_keys=True), encoding="utf-8")


def _assert_invalid(report, substring: str | None = None, context: str = "") -> None:
    assert not report.valid, f"expected INVALID {context}, got valid"
    if substring is not None:
        assert any(substring in error for error in report.errors), (
            f"expected an error containing {substring!r} in {context}; got {report.errors}"
        )


# ---------------------------------------------------------------------------
# (a) Golden fixtures validate clean
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("fixture_dir", GOLDEN_FIXTURES, ids=lambda p: p.name)
def test_golden_fixtures_validate_clean(fixture_dir: Path) -> None:
    assert GOLDEN_FIXTURES, "no golden fixtures found under content/fixtures/songpack-v1"
    report = validate_pack(fixture_dir)
    assert report.valid, f"{fixture_dir.name} errors: {report.errors}"
    assert report.errors == []
    assert report.warnings == []


def test_all_pack_files_present_in_every_fixture() -> None:
    for fixture_dir in GOLDEN_FIXTURES:
        for filename in PACK_FILES:
            assert (fixture_dir / filename).is_file(), f"{fixture_dir.name} missing {filename}"


# ---------------------------------------------------------------------------
# (b) Negative cases
# ---------------------------------------------------------------------------
def _add_seconds(doc) -> None:
    doc["levels"]["1"][0]["seconds"] = 1.0


def test_seconds_key_in_note_is_rejected(tmp_path: Path) -> None:
    pack = _copy_fixture(tmp_path, "pickup_anacrusis")
    _mutate(pack, "notes.json", _add_seconds)
    _assert_invalid(validate_pack(pack), "seconds", "seconds key on a note")


def test_seconds_null_still_rejected(tmp_path: Path) -> None:
    """seconds: null is still a seconds key — the prohibition is on the key."""
    pack = _copy_fixture(tmp_path, "pickup_anacrusis")

    def _add_null_seconds(doc) -> None:
        doc["levels"]["1"][0]["seconds"] = None

    _mutate(pack, "notes.json", _add_null_seconds)
    _assert_invalid(validate_pack(pack), "seconds", "seconds: null on a note")


def test_missing_required_field_is_rejected(tmp_path: Path) -> None:
    pack = _copy_fixture(tmp_path, "pickup_anacrusis")

    def _remove_voice(doc) -> None:
        del doc["levels"]["1"][0]["voice"]

    _mutate(pack, "notes.json", _remove_voice)
    _assert_invalid(validate_pack(pack), "voice", "missing required field")


def test_pitch_out_of_range_is_rejected(tmp_path: Path) -> None:
    pack = _copy_fixture(tmp_path, "pickup_anacrusis")

    def _pitch_too_high(doc) -> None:
        doc["levels"]["1"][0]["pitch"] = 200

    _mutate(pack, "notes.json", _pitch_too_high)
    _assert_invalid(validate_pack(pack), "pitch", "pitch above 108")


def test_broken_tie_to_index_is_rejected(tmp_path: Path) -> None:
    pack = _copy_fixture(tmp_path, "tie_across_chunks")

    def _break_tie(doc) -> None:
        doc["levels"]["1"][19]["tieToIndex"] = 9999

    _mutate(pack, "notes.json", _break_tie)
    _assert_invalid(validate_pack(pack), "tieToIndex", "out-of-range tieToIndex")


def test_tie_to_wrong_pitch_is_rejected(tmp_path: Path) -> None:
    pack = _copy_fixture(tmp_path, "tie_across_chunks")

    def _retarget_tie(doc) -> None:
        doc["levels"]["1"][19]["tieToIndex"] = 21  # B4, not G4

    _mutate(pack, "notes.json", _retarget_tie)
    _assert_invalid(validate_pack(pack), "tie", "pitch-mismatched tie")


def test_non_songpack_format_is_rejected(tmp_path: Path) -> None:
    pack = _copy_fixture(tmp_path, "pickup_anacrusis")

    def _bad_format(doc) -> None:
        doc["format"] = "songpack/v2"

    _mutate(pack, "manifest.json", _bad_format)
    _assert_invalid(validate_pack(pack), "format", "format other than songpack/v1")


def test_nan_in_file_is_rejected(tmp_path: Path) -> None:
    """NaN is not JSON; a non-JSON parser could leak it into a hand-authored
    file, and it would corrupt beat math silently."""
    pack = _copy_fixture(tmp_path, "pickup_anacrusis")
    (pack / "notes.json").write_text(
        json.dumps({"levels": {"1": [{"pitch": 60, "startBeat": float("nan"),
                                      "durBeats": 1.0, "hand": "R", "staff": 1, "voice": 1}]}}),
        encoding="utf-8",
    )
    _assert_invalid(validate_pack(pack), "not valid SongPack JSON", "NaN startBeat")


def test_invalid_build_timestamp_is_rejected(tmp_path: Path) -> None:
    """§8.1.10: the Python consumer must enforce `format: date-time` on
    buildInfo.buildTimestamp, exactly like the PHP (opis) and Kotlin (networknt)
    consumers. Non-vacuous: the failure must name the buildTimestamp path."""
    pack = _copy_fixture(tmp_path, "pickup_anacrusis")

    def _bad_timestamp(doc) -> None:
        doc["buildInfo"]["buildTimestamp"] = "not-a-date"

    _mutate(pack, "manifest.json", _bad_timestamp)
    _assert_invalid(
        validate_pack(pack),
        "buildTimestamp",
        "malformed buildInfo.buildTimestamp",
    )


# ---------------------------------------------------------------------------
# (c) Forward compatibility: unknown keys are ignored
# ---------------------------------------------------------------------------
def _add_future_manifest_field(doc) -> None:
    doc["futureField"] = True


def _add_future_note_field(doc) -> None:
    doc["levels"]["1"][0]["futureNoteField"] = 1


def test_unknown_keys_are_ignored(tmp_path: Path) -> None:
    pack = _copy_fixture(tmp_path, "pickup_anacrusis")
    _mutate(pack, "manifest.json", _add_future_manifest_field)
    _mutate(pack, "notes.json", _add_future_note_field)
    report = validate_pack(pack)
    assert report.valid, f"unknown keys must be ignored; errors: {report.errors}"


def test_unknown_note_key_ok_while_seconds_still_rejected(tmp_path: Path) -> None:
    pack = _copy_fixture(tmp_path, "repeat_structure")
    _mutate(pack, "manifest.json", _add_future_manifest_field)
    _mutate(pack, "notes.json", _add_future_note_field)

    def _add_seconds_too(doc) -> None:
        doc["levels"]["1"][0]["seconds"] = 1.0

    _mutate(pack, "notes.json", _add_seconds_too)
    _assert_invalid(
        validate_pack(pack),
        "seconds",
        "unknown keys pass but seconds must still fail",
    )


def test_repeat_structure_fixture_carries_forward_compat_keys() -> None:
    """The repeat_structure golden fixture itself ships futureField /
    futureNoteField, proving unknown keys in committed content are fine."""
    manifest = json.loads((FIXTURES_ROOT / "repeat_structure" / "manifest.json").read_text())
    notes = json.loads((FIXTURES_ROOT / "repeat_structure" / "notes.json").read_text())
    assert manifest["futureField"] is True
    assert notes["levels"]["1"][0]["futureNoteField"] == 1
    assert validate_pack(FIXTURES_ROOT / "repeat_structure").valid


# ---------------------------------------------------------------------------
# (d) Root oneOf dispatcher (the PHP/Kotlin entry point) agrees
# ---------------------------------------------------------------------------
def _root_dispatcher_errors(document) -> list:
    schema = load_schema()
    return list(jsonschema.Draft7Validator(schema).iter_errors(document))


def test_root_dispatcher_rejects_seconds() -> None:
    notes = json.loads((FIXTURES_ROOT / "pickup_anacrusis" / "notes.json").read_text())
    notes["levels"]["1"][0]["seconds"] = 1.0
    assert _root_dispatcher_errors(notes), "root oneOf dispatcher must reject a seconds note"


def test_root_dispatcher_accepts_unknown_keys() -> None:
    manifest = json.loads((FIXTURES_ROOT / "pickup_anacrusis" / "manifest.json").read_text())
    manifest["futureField"] = True
    assert _root_dispatcher_errors(manifest) == [], "root dispatcher must ignore unknown keys"


def test_root_dispatcher_rejects_bad_format() -> None:
    manifest = json.loads((FIXTURES_ROOT / "pickup_anacrusis" / "manifest.json").read_text())
    manifest["format"] = "songpack/v2"
    assert _root_dispatcher_errors(manifest), "root dispatcher must reject a non-songpack/v1 format"


def test_canonical_schema_is_draft07() -> None:
    schema = load_schema()
    assert schema["$schema"] == "http://json-schema.org/draft-07/schema#"
    jsonschema.Draft7Validator.check_schema(schema)  # meta-validates