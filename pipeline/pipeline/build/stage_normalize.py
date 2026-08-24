"""Stage 3 — normalization (plan §8.2.3).

Collapse the expressive variety of MusicXML into the small, regular structure
the app renders. Input is the stage-2 *validated parse* (parts, tempo marks,
measure flags); output is a linearized timeline with canonical note records.

Transformations:
- repeat/jump expansion into a linear timeline via the repeats state machine,
  emitting the ``repeatMap`` (source measures → output beats);
- tuplets → exact fractional beat durations (no grid rounding);
- grace notes → real notes with scoringWeight 0 placed just before the beat;
- ornaments (trill/mordent/inverted-mordent) → deterministic expansion notes
  with isOrnamentExpansion:true + scoringWeight 0.2; anything exotic was
  already rejected by name in stage 2;
- voice renumbering to a canonical scheme (1..N per staff, by first onset);
- ties kept as paired records carrying _tieType (index resolution happens in
  stage 8 once the canonical array order is known);
- explicit rests recorded (chunking uses them as boundary signals);
- pedal marks, chord symbols and lyrics retained as metadata.

Normalization is idempotent: running it twice on the same validated parse
changes nothing (the fixture tests assert this).
"""

from __future__ import annotations

from typing import Any

from pipeline.build.config import BuildConfig
from pipeline.build.errors import NormalizeError
from pipeline.build.repeats import build_repeat_map, linearize_measures
from pipeline.build.runner import StageReport

GRACE_DUR_BEATS = 0.125  # 32nd note: ~62 ms at 120 bpm (documented default)
TRILL_NOTE_BEATS = 0.25  # 16th-note trill subdivision (documented default)
ORNAMENT_SCORING_WEIGHT = 0.2


def _ts_beats(time_signature: tuple[int, int] | None) -> float | None:
    if time_signature is None:
        return None
    return time_signature[0] * 4.0 / time_signature[1]


def _pickup_beats(first_measure: dict[str, Any]) -> float:
    """The first measure is an anacrusis when it is shorter than the opening
    time signature; its content length is the pickup length in beats."""
    ts_beats = _ts_beats(first_measure["timeSignature"])
    if ts_beats is None:
        return 0.0
    content = first_measure["contentLength"]
    if content < ts_beats - 1e-6:
        return content
    return 0.0


def _measure_lookup(parts: list[dict[str, Any]]) -> dict[int, dict[str, Any]]:
    """Source measure number → measure dict for a part (first part = the
    structural reference)."""
    return {measure["measure"]: measure for measure in parts[0]["measures"]}


def _renumber_voices(parts: list[dict[str, Any]]) -> dict[int, dict[int, int]]:
    """Canonical voice numbering: per staff, distinct original voice ids get
    1..N in order of first appearance (MusicXML exporters number voices
    wildly inconsistently)."""
    mapping: dict[int, dict[int, int]] = {}
    order: dict[int, list[int]] = {}
    for part in parts:
        staff = part["staff"]
        mapping.setdefault(staff, {})
        order.setdefault(staff, [])
        for measure in part["measures"]:
            for voice_records in measure["voices"]:
                for record in voice_records:
                    original = record["voice"]
                    if original not in mapping[staff]:
                        mapping[staff][original] = len(mapping[staff]) + 1
                        order[staff].append(original)
    return mapping


def _diatonic_neighbor(main_pitch: int, direction: int) -> int:
    """v0 ornament neighbor: whole-step up (+2) for trills/inverted-mordents,
    chromatic down (-1) for mordents. Documented default; diatonic neighbor
    from the key signature is a P2 refinement."""
    return main_pitch + direction


def _expand_ornament(
    record: dict[str, Any],
    start_beat: float,
    *,
    measure: int,
    staff: int,
    canonical_voice: int,
    seq: int,
) -> tuple[list[dict[str, Any]], int]:
    """Expand a trill/mordent into deterministic note records. Returns the
    records plus the next sequence counter."""
    main = record["pitch"]
    duration = record["qlen"]
    ornaments = record.get("ornaments", [])
    if "trill" in ornaments:
        count = max(2, round(duration / TRILL_NOTE_BEATS))
        upper = _diatonic_neighbor(main, +2)
        if upper > 108:
            raise NormalizeError(
                f"measure {measure}: trill on pitch {main} expands to {upper}, "
                "outside the supported range A0–C8"
            )
        pitches = [upper if k % 2 == 0 else main for k in range(count)]
    elif "invertedmordent" in ornaments:
        upper = _diatonic_neighbor(main, +2)
        pitches = [main, upper, main]
    elif "mordent" in ornaments:
        lower = _diatonic_neighbor(main, -1)
        if lower < 21:
            raise NormalizeError(
                f"measure {measure}: mordent on pitch {main} expands to {lower}, "
                "outside the supported range A0–C8"
            )
        pitches = [main, lower, main]
    else:
        return [], seq

    each = duration / len(pitches)
    records: list[dict[str, Any]] = []
    for k, pitch in enumerate(pitches):
        seq += 1
        records.append(
            {
                "pitch": pitch,
                "startBeat": start_beat + k * each,
                "durBeats": each,
                "staff": staff,
                "voice": canonical_voice,
                "accidental": None,
                "isOrnamentExpansion": True,
                "scoringWeight": ORNAMENT_SCORING_WEIGHT,
                "beamGroup": None,
                "lane": None,
                "xHint": None,
                "_sourceMeasure": measure,
                "_tieType": None,
                "_grace": False,
                "_seq": seq,
            }
        )
    return records, seq


def _emit_notes(
    part: dict[str, Any],
    linear_order: list[dict[str, Any]],
    measure_starts: dict[int, float],
    voice_mapping: dict[int, int],
    report: StageReport,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    """All notes, rests, and lyrics for one part over the linear timeline."""
    measures_by_number = _measure_lookup([part])
    notes: list[dict[str, Any]] = []
    rests: list[dict[str, Any]] = []
    lyrics: list[dict[str, Any]] = []
    seq = 0

    def next_seq() -> int:
        nonlocal seq
        seq += 1
        return seq

    def flush_graces(pending: list[dict[str, Any]], target_beat: float | None) -> None:
        """Place pending grace notes just before ``target_beat`` (or at their
        own offset when no main note follows), back-to-back, weight 0."""
        if not pending:
            return
        if target_beat is None:
            target_beat = pending[0].get("offset") or 0.0
        for index, record in enumerate(pending):
            grace_beat = target_beat - GRACE_DUR_BEATS * (len(pending) - index)
            if grace_beat < 0:
                grace_beat = 0.0
            notes.append(
                {
                    "pitch": record["pitch"],
                    "startBeat": round(grace_beat, 12),
                    "durBeats": GRACE_DUR_BEATS,
                    "staff": part["staff"],
                    "voice": canonical_voice,
                    "accidental": record.get("accidental"),
                    "isOrnamentExpansion": False,
                    "scoringWeight": 0.0,
                    "beamGroup": None,
                    "lane": None,
                    "xHint": None,
                    "_sourceMeasure": -1,
                    "_tieType": None,
                    "_grace": True,
                    "_seq": next_seq(),
                }
            )
        pending.clear()

    for position, entry in enumerate(linear_order):
        source_measure = measures_by_number.get(entry["measure"])
        if source_measure is None:
            continue
        measure_start = measure_starts[position]
        for voice_records in source_measure["voices"]:
            canonical_voice = voice_mapping.get(
                voice_records[0]["voice"] if voice_records else 1, 1
            )
            pending_graces: list[dict[str, Any]] = []
            for record in voice_records:
                offset = record["offset"] or 0.0
                if record["kind"] == "rest":
                    rests.append(
                        {
                            "startBeat": round(measure_start + offset, 12),
                            "durBeats": record["qlen"],
                            "staff": part["staff"],
                        }
                    )
                    flush_graces(pending_graces, measure_start + offset)
                    continue
                start_beat = measure_start + offset
                if record["grace"]:
                    pending_graces.append(record)
                    continue
                flush_graces(pending_graces, start_beat)
                expansion, seq = _expand_ornament(
                    record,
                    start_beat,
                    measure=entry["measure"],
                    staff=part["staff"],
                    canonical_voice=canonical_voice,
                    seq=seq,
                )
                if expansion:
                    notes.extend(expansion)
                    continue
                note_record = {
                    "pitch": record["pitch"],
                    "startBeat": round(start_beat, 12),
                    "durBeats": record["qlen"],
                    "staff": part["staff"],
                    "voice": canonical_voice,
                    "accidental": record.get("accidental"),
                    "isOrnamentExpansion": False,
                    "scoringWeight": 1.0,
                    "beamGroup": None,
                    "lane": None,
                    "xHint": None,
                    "_sourceMeasure": entry["measure"],
                    "_tieType": record.get("tieType"),
                    "_grace": False,
                    "_seq": next_seq(),
                }
                notes.append(note_record)
                if record.get("lyric"):
                    lyrics.append(
                        {
                            "startBeat": round(start_beat, 12),
                            "text": record["lyric"],
                            "staff": part["staff"],
                        }
                    )
            flush_graces(pending_graces, None)
    return notes, rests, lyrics


def _collect_tie_pairs(parts: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """(fromMeasure, toMeasure) pairs for every tie chain, per staff/voice."""
    pairs: list[dict[str, Any]] = []
    for part in parts:
        voice_notes: dict[int, list[dict[str, Any]]] = {}
        for measure in part["measures"]:
            for voice_records in measure["voices"]:
                for record in voice_records:
                    if record["kind"] != "note":
                        continue
                    voice_notes.setdefault(record["voice"], []).append(
                        {**record, "measure": measure["measure"]}
                    )
        for notes in voice_notes.values():
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
                if target is not None:
                    pairs.append({"fromMeasure": record["measure"], "toMeasure": target["measure"]})
    # Deduplicate, preserving order.
    seen: set[tuple[int, int]] = set()
    unique = []
    for pair in pairs:
        key = (pair["fromMeasure"], pair["toMeasure"])
        if key not in seen:
            seen.add(key)
            unique.append(pair)
    return unique


def run_stage_normalize(
    doc: dict[str, Any], config: BuildConfig
) -> tuple[dict[str, Any], StageReport]:
    report = StageReport(stage=3, name="normalize")
    parts: list[dict[str, Any]] = doc["parts"]
    flags = measure_flags_from_dicts(doc["measureFlags"])
    if not parts:
        raise NormalizeError("no parts to normalize — stage 2 found no score content")

    order, passes = linearize_measures(flags)

    first_measure = parts[0]["measures"][0]
    pickup_beats = _pickup_beats(first_measure)
    reference = _measure_lookup(parts)

    # Beat timing of every linear measure occurrence.
    measure_starts: dict[int, float] = {}
    measure_ends: dict[int, float] = {}
    cursor = 0.0
    for position, source_number in enumerate(order):
        measure_starts[position] = cursor
        if position == 0 and pickup_beats > 0:
            length = pickup_beats
        else:
            ts = reference.get(source_number, {}).get("timeSignature")
            length = _ts_beats(ts)
            if length is None:
                raise NormalizeError(
                    f"measure {source_number} has no time signature; cannot compute the timeline"
                )
        measure_ends[position] = cursor + length
        cursor = measure_ends[position]
    duration_beats = measure_ends[len(order) - 1] if order else 0.0

    voice_mapping = _renumber_voices(parts)
    linear_order = [
        {"measure": number, "pass": passes[pos]} for pos, number in enumerate(order)
    ]

    all_notes: list[dict[str, Any]] = []
    all_rests: list[dict[str, Any]] = []
    all_lyrics: list[dict[str, Any]] = []
    pedal_events: list[dict[str, Any]] = []
    harmony_events: list[dict[str, Any]] = []
    measures_meta: list[dict[str, Any]] = []

    for part in parts:
        notes, rests, lyrics = _emit_notes(
            part, linear_order, measure_starts, voice_mapping[part["staff"]], report
        )
        all_notes.extend(notes)
        all_rests.extend(rests)
        all_lyrics.extend(lyrics)
        for position, entry in enumerate(linear_order):
            source_measure = reference.get(entry["measure"])
            if source_measure is None or source_measure["staff"] != part["staff"]:
                continue
            start = measure_starts[position]
            for pedal in source_measure.get("pedals", []):
                pedal_events.append(
                    {
                        "type": pedal["type"],
                        "startBeat": round(start + pedal["offset"], 12),
                    }
                )
            for harmony in source_measure.get("harmonies", []):
                harmony_events.append(
                    {
                        "figure": harmony["figure"],
                        "startBeat": round(start + harmony["offset"], 12),
                    }
                )

    # Canonical internal order: chronological, then pitch/voice/staff; the
    # final hand-aware sort happens in stage 8.
    all_notes.sort(
        key=lambda n: (
            n["startBeat"],
            n["pitch"],
            n["voice"],
            n["staff"],
            n["_seq"],
        )
    )
    for index, note in enumerate(all_notes):
        note["_index"] = index

    # The linearized measure table (used by chunking, layout, manifest).
    time_sigs: list[dict[str, Any]] = []
    key_sigs: list[dict[str, Any]] = []
    for position, source_number in enumerate(order):
        source = reference.get(source_number, {})
        ts = source.get("timeSignature")
        ks = source.get("keySignature")
        time_sigs.append(
            {
                "atBeat": measure_starts[position],
                "numerator": ts[0] if ts else None,
                "denominator": ts[1] if ts else None,
            }
        )
        key_sigs.append(
            {
                "atBeat": measure_starts[position],
                "fifths": ks["fifths"] if ks else 0,
                "mode": ks["mode"] if ks else "major",
            }
        )
        measures_meta.append(
            {
                "measure": source_number,
                "pass": passes[position],
                "startBeat": measure_starts[position],
                "endBeat": measure_ends[position],
                "durationBeats": measure_ends[position] - measure_starts[position],
                "timeSignature": f"{ts[0]}/{ts[1]}" if ts else "?",
                "keyFifths": ks["fifths"] if ks else 0,
                "keyMode": ks["mode"] if ks else "major",
                "isPickup": position == 0 and pickup_beats > 0,
            }
        )

    repeat_map = build_repeat_map(
        flags, order, passes, measure_starts, measure_ends
    )

    tempo_map = _build_tempo_map(
        doc.get("tempoMarks", []),
        order,
        reference,
        measure_starts,
        config.tempo_override,
    )

    doc["metadata"] = {
        "title": doc.get("scoreMetadata", {}).get("title"),
        "composer": doc.get("scoreMetadata", {}).get("composer"),
        "arranger": config.metadata.get("arranger", "KeyQuest"),
        "genre": config.metadata.get("genre", "other"),
        "era": config.metadata.get("era", "other"),
        "mood": config.metadata.get("mood", ["other"]),
        "minAppVersion": config.min_app_version,
        "pickupBeats": pickup_beats,
        "defaultTempoBpm": tempo_map[0]["bpm"] if tempo_map else 120,
        "minPracticeTempoPct": 60,
        "tempoMap": tempo_map,
        "timeSignatures": _collapse_signatures(time_sigs),
        "keySignatures": _collapse_signatures(key_sigs),
        "durationBeats": round(duration_beats, 12),
    }
    doc["measures"] = measures_meta
    doc["notes"] = all_notes
    doc["rests"] = all_rests
    doc["lyrics"] = all_lyrics
    doc["pedalMarks"] = sorted(pedal_events, key=lambda e: e["startBeat"])
    doc["chordSymbols"] = sorted(harmony_events, key=lambda e: e["startBeat"])
    doc["ties"] = _collect_tie_pairs(parts)
    doc["repeatMap"] = repeat_map

    report.properties.update(
        {
            "linearMeasures": len(order),
            "notes": len(all_notes),
            "rests": len(all_rests),
            "pickupBeats": pickup_beats,
            "durationBeats": round(duration_beats, 12),
            "repeatSegments": len(repeat_map),
        }
    )
    report.note(
        f"linearized {len(order)} measures → {duration_beats:.3f} beats "
        f"({len(repeat_map)} repeatMap segments)"
    )
    return doc, report


def _build_tempo_map(
    tempo_marks: list[dict[str, Any]],
    order: list[int],
    reference: dict[int, dict[str, Any]],
    measure_starts: dict[int, float],
    tempo_override: int | None,
) -> list[dict[str, Any]]:
    """tempoMap with the first entry at beat 0; marks map onto the first
    linear occurrence of their source measure."""
    if tempo_override is not None:
        return [{"atBeat": 0.0, "bpm": tempo_override, "curve": "step"}]
    if not tempo_marks:
        return [{"atBeat": 0.0, "bpm": 120, "curve": "step"}]
    first_bpm = tempo_marks[0]["bpm"]
    entries: list[dict[str, Any]] = []
    for mark in tempo_marks:
        mark_measure = mark.get("measure")
        at_beat = None
        for position, source_number in enumerate(order):
            if source_number == mark_measure:
                at_beat = measure_starts[position]
                break
        if at_beat is None:
            at_beat = 0.0
        entries.append({"atBeat": round(at_beat, 12), "bpm": mark["bpm"], "curve": "step"})
    # The tempo map must cover beat 0 (§8.1.3).
    if entries[0]["atBeat"] != 0.0:
        entries.insert(0, {"atBeat": 0.0, "bpm": first_bpm, "curve": "step"})
    # Collapse duplicate atBeats (keep the last) and sort.
    by_beat: dict[float, int] = {}
    for entry in entries:
        by_beat[entry["atBeat"]] = entry["bpm"]
    return [{"atBeat": beat, "bpm": by_beat[beat], "curve": "step"} for beat in sorted(by_beat)]


def _collapse_signatures(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Keep only changes (drop consecutive duplicates), ensure atBeat 0."""
    collapsed: list[dict[str, Any]] = []
    for entry in entries:
        if entry.get("numerator") is None and entry.get("fifths") is None:
            continue
        if collapsed and _signature_key(collapsed[-1]) == _signature_key(entry):
            continue
        collapsed.append(dict(entry))
    if collapsed and collapsed[0]["atBeat"] != 0.0:
        first = dict(collapsed[0])
        first["atBeat"] = 0.0
        collapsed.insert(0, first)
    if not collapsed:
        collapsed.append({"atBeat": 0.0, "numerator": 4, "denominator": 4})
    return collapsed


def _signature_key(entry: dict[str, Any]) -> tuple[Any, ...]:
    if "numerator" in entry:
        return (entry["numerator"], entry["denominator"])
    return (entry["fifths"], entry["mode"])


def measure_flags_from_dicts(flags: list[dict[str, Any]]):
    """Rebuild MeasureFlag objects from the stored stage-2 flags (avoids a
    music21 dependency in the normalize stage)."""
    from pipeline.build.repeats import MeasureFlag

    return [
        MeasureFlag(
            measure=flag["measure"],
            starts_repeat=bool(flag.get("startsRepeat")),
            end_repeat_times=flag.get("endRepeatTimes"),
            voltas=list(flag.get("voltas", [])),
        )
        for flag in flags
    ]