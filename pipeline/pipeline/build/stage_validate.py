"""Stage 2 — validation (plan §8.2.2).

Reject bad input loudly, with an error a musician can act on, rather than
producing subtly wrong content. Every check produces ``error`` (blocks) or
``warning`` (proceeds, shown in the report):

- Structural: parseable; 1–2 staves; a piano part identified; no zero/negative
  durations; every measure sums to its time signature.
- Range: all pitches within A0–C8 (MIDI 21–108); warn outside a 61-key range
  (C2–C7 = 36–96).
- Musical sanity: no simultaneous same-pitch same-voice; chord spans beyond a
  10th are warnings; every tie target exists and matches pitch.
- Unsupported constructs, explicitly enumerated (see
  musicxml.scan_unsupported_source) — each has a normalization in stage 3 or
  a clear named rejection.
- Tempo: at least one numeric tempo mark or an explicit --tempo override;
  text-only tempo is a warning requiring a human BPM.

The stage's output artifact is the *validated parse*: the parts extraction,
tempo marks, metadata, and measure flags are stored in the doc so stage 3
(and any --from-stage resume) never re-parses the source.
"""

from __future__ import annotations

from typing import Any

from pipeline.build.config import BuildConfig
from pipeline.build.musicxml import (
    extract_metadata,
    extract_parts,
    extract_tempo,
    parse_score,
    part_warnings,
    scan_unsupported_source,
)
from pipeline.build.repeats import measure_flags_from_parts
from pipeline.build.runner import StageReport
from pipeline.build.stage_ingest import load_provenance, stored_source_path

MIDI_MIN = 21  # A0
MIDI_MAX = 108  # C8
KEYBOARD_61_MIN = 36  # C2
KEYBOARD_61_MAX = 96  # C7
TENTH_SEMITONES = 16  # a 10th = 16 semitones (C4 → E5)
DURATION_EPSILON = 1e-6


def _ts_expected_beats(time_signature: tuple[int, int] | None) -> float | None:
    if time_signature is None:
        return None
    numerator, denominator = time_signature
    return numerator * 4.0 / denominator


def _check_structural(
    parts: list[dict[str, Any]], report: StageReport
) -> None:
    if not parts:
        report.error("score contains no parts")
        return
    if len(parts) > 2:
        report.error(
            f"score has {len(parts)} parts; v0 supports piano scores with 1-2 staves"
        )
    for part in parts:
        measures = part["measures"]
        if not measures:
            report.error(f"part {part['staff']} ({part['name']!r}) has no measures")
            continue
        for measure in measures:
            if len(measure["voices"]) > 4:
                report.error(
                    f"measure {measure['measure']}: {len(measure['voices'])} voices "
                    "on one staff — v0 normalizes up to 4 voices, more is rejected "
                    "(explicitly enumerated, §8.2.2)"
                )
            if measure["implicit"]:
                continue
            expected = _ts_expected_beats(measure["timeSignature"])
            content = measure["contentLength"]
            if measure["measureIndex"] == 0:
                # The first measure may be a pickup (anacrusis) shorter than
                # the opening time signature — allowed; anything longer is not.
                if expected is not None and content > expected + DURATION_EPSILON:
                    report.error(
                        f"measure {measure['measure']}: content {content:.6g} beats "
                        f"exceeds the opening time signature "
                        f"({measure['timeSignature'][0]}/{measure['timeSignature'][1]}"
                        f" = {expected:.6g} beats)"
                    )
                continue
            if expected is None:
                report.error(f"measure {measure['measure']}: no time signature")
                continue
            if abs(content - expected) > DURATION_EPSILON:
                report.error(
                    f"measure {measure['measure']}: content is {content:.6g} beats but "
                    f"the time signature {measure['timeSignature'][0]}/"
                    f"{measure['timeSignature'][1]} requires {expected:.6g} — "
                    "a classic MusicXML export defect (plan §8.2.2)"
                )
            for voice_records in measure["voices"]:
                for record in voice_records:
                    if record["kind"] == "rest":
                        continue
                    if record["qlen"] <= 0 and not record["grace"]:
                        report.error(
                            f"measure {measure['measure']} voice {record['voice']}: "
                            f"zero or negative duration ({record['qlen']!r}) on a note"
                        )


def _check_range(
    parts: list[dict[str, Any]], report: StageReport
) -> None:
    for part in parts:
        for measure in part["measures"]:
            for voice_records in measure["voices"]:
                for record in voice_records:
                    if record["kind"] != "note":
                        continue
                    pitch = record["pitch"]
                    if pitch < MIDI_MIN or pitch > MIDI_MAX:
                        report.error(
                            f"measure {measure['measure']}: pitch {pitch} "
                            f"(MIDI) is outside the supported range A0–C8 "
                            f"({MIDI_MIN}–{MIDI_MAX})"
                        )
                    elif pitch < KEYBOARD_61_MIN or pitch > KEYBOARD_61_MAX:
                        report.warn(
                            f"measure {measure['measure']}: pitch {pitch} (MIDI) is "
                            f"outside the 61-key range C2–C7 "
                            f"({KEYBOARD_61_MIN}–{KEYBOARD_61_MAX}) — learners on "
                            "small keyboards cannot play it"
                        )


def _simultaneous_pitch_check(
    measure: dict[str, Any], report: StageReport
) -> None:
    for voice_records in measure["voices"]:
        by_pitch: dict[int, list[dict[str, Any]]] = {}
        for record in voice_records:
            if record["kind"] != "note":
                continue
            if record["grace"]:
                continue
            by_pitch.setdefault(record["pitch"], []).append(record)
        for pitch, records in sorted(by_pitch.items()):
            records.sort(key=lambda r: (r["offset"] or 0.0))
            for index, record in enumerate(records):
                start = record["offset"] or 0.0
                end = start + record["qlen"]
                for other in records[index + 1 :]:
                    other_start = other["offset"] or 0.0
                    if other_start < end + DURATION_EPSILON:
                        report.error(
                            f"measure {measure['measure']} voice {record['voice']}: "
                            f"simultaneous notes on the same pitch {pitch} (MIDI) — "
                            "same-pitch unisons in one voice are a source defect (§8.2.2)"
                        )
                    else:
                        break


def _chord_span_check(measure: dict[str, Any], report: StageReport) -> None:
    """A chord spanning more than a 10th in one hand is usually a hand-
    assignment error, not genuine — warn (never guess silently)."""
    for voice_records in measure["voices"]:
        by_offset: dict[float, list[dict[str, Any]]] = {}
        for record in voice_records:
            if record["kind"] != "note" or record["grace"]:
                continue
            by_offset.setdefault(round(record["offset"] or 0.0, 6), []).append(record)
        for offset, records in by_offset.items():
            if len(records) < 2:
                continue
            pitches = sorted(record["pitch"] for record in records)
            if pitches[-1] - pitches[0] > TENTH_SEMITONES:
                report.warn(
                    f"measure {measure['measure']} at beat {offset}: chord spanning "
                    f"{pitches[-1] - pitches[0]} semitones (>{TENTH_SEMITONES} = a 10th) "
                    "in one hand — likely a hand-assignment error (§8.2.2)"
                )


def _tie_check(parts: list[dict[str, Any]], report: StageReport) -> None:
    """Every tie start/continue must have a later stop/continue of the same
    pitch in the same voice (across measures)."""
    for part in parts:
        voice_notes: dict[tuple[int, int], list[dict[str, Any]]] = {}
        for measure in part["measures"]:
            for voice_index, voice_records in enumerate(measure["voices"]):
                for record in voice_records:
                    if record["kind"] != "note":
                        continue
                    voice_notes.setdefault((part["staff"], voice_index), []).append(record)
        for (staff, voice_index), notes in voice_notes.items():
            for record in notes:
                if record["tieType"] not in ("start", "continue"):
                    continue
                target = next(
                    (
                        other
                        for other in notes
                        if other["pitch"] == record["pitch"]
                        and other["tieType"] in ("stop", "continue")
                        and other is not record
                    ),
                    None,
                )
                if target is None:
                    report.error(
                        f"staff {staff} voice {voice_index + 1}: tie on pitch "
                        f"{record['pitch']} (MIDI) has no matching target — every tie "
                        "needs a stop note of the same pitch (§8.2.2)"
                    )


def _check_tempo(
    tempo_marks: list[dict[str, Any]],
    warnings: list[str],
    tempo_override: int | None,
    report: StageReport,
) -> None:
    for message in warnings:
        report.warn(message)
    if tempo_override is not None:
        report.note(f"using --tempo override {tempo_override} bpm")
        return
    if not tempo_marks:
        report.error(
            "no tempo mark found in the score — add a metronome mark or pass "
            "--tempo <bpm> to the build"
        )


def run_stage_validate(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    """Stage 2 entry point: parse, check, and store the validated parse."""
    report = StageReport(stage=2, name="validate")
    provenance = load_provenance(config.song_id, config)
    source_path = stored_source_path(config.song_id, provenance)

    score = parse_score(source_path)
    unsupported = scan_unsupported_source(source_path, score)
    for name in unsupported:
        report.error(
            f"unsupported construct in v0: {name} — D.S./D.C./Coda/glissando/cue/"
            "turn structures have no defined normalization in this pipeline; "
            "simplify the source or arrange it by hand (§8.2.2)"
        )

    report.warnings.extend(part_warnings(score))
    parts = extract_parts(score)
    _check_structural(parts, report)
    _check_range(parts, report)
    for part in parts:
        for measure in part["measures"]:
            _simultaneous_pitch_check(measure, report)
            _chord_span_check(measure, report)
    _tie_check(parts, report)

    tempo_marks, tempo_warnings = extract_tempo(score)
    _check_tempo(tempo_marks, tempo_warnings, config.tempo_override, report)

    if report.errors:
        report.properties.update({"errors": len(report.errors)})
        return doc, report

    metadata = extract_metadata(score)
    flags = measure_flags_from_parts(parts, score)
    doc["source"] = doc.get("source", {})
    doc["source"].update(
        {
            "file": provenance["sourceFile"],
            "sha256": provenance["sha256"],
            "format": provenance["format"],
        }
    )
    doc["scoreMetadata"] = metadata
    doc["parts"] = parts
    doc["tempoMarks"] = tempo_marks
    doc["measureFlags"] = [
        {
            "measure": flag.measure,
            "startsRepeat": flag.starts_repeat,
            "endRepeatTimes": flag.end_repeat_times,
            "voltas": flag.voltas,
        }
        for flag in flags
    ]
    note_count = sum(
        1
        for part in parts
        for measure in part["measures"]
        for voice in measure["voices"]
        for record in voice
        if record["kind"] == "note"
    )
    report.properties.update(
        {
            "parts": len(parts),
            "measures": sum(len(part["measures"]) for part in parts),
            "notes": note_count,
            "tempoMarks": len(tempo_marks),
        }
    )
    report.note(f"validated {report.properties['notes']} notes in {report.properties['parts']} part(s)")
    return doc, report