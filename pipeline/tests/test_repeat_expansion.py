"""Repeat-expansion state machine unit tests (plan §8.2.3, §8.2.13).

The supported v0 set: simple repeats, first/second endings (voltas), and
nested repeats (the open-bracket stack is hierarchical by construction).
D.S./D.C./Coda are rejected in stage 2, never reaching this machine.
"""

from __future__ import annotations

import pytest

from pipeline.build.errors import NormalizeError
from pipeline.build.repeats import MeasureFlag, build_repeat_map, linearize_measures


def flags_from(spec: list[tuple[int, str | None, int | None]]) -> list[MeasureFlag]:
    """spec entries: (measure_number, repeat_marker, end_times).

    repeat_marker: None | 'start' | 'end'. Voltas are supplied separately."""
    out = []
    for number, marker, times in spec:
        out.append(
            MeasureFlag(
                measure=number,
                starts_repeat=marker == "start",
                end_repeat_times=times if marker == "end" else None,
            )
        )
    return out


def voltas(flags: list[MeasureFlag], number: int, measures: list[int]) -> list[MeasureFlag]:
    for flag in flags:
        if flag.measure in measures:
            flag.voltas.append(number)
    return flags


def test_simple_repeat_without_forward_jumps_to_piece_start() -> None:
    # A B :| C  — no forward repeat means "repeat from the beginning".
    flags = flags_from([(1, None, None), (2, None, None), (3, "end", 2), (4, None, None)])
    order, passes = linearize_measures(flags)
    assert order == [1, 2, 3, 1, 2, 3, 4]
    assert passes == [1, 1, 1, 2, 2, 2, 1]


def test_simple_repeat_with_forward_jumps_to_section() -> None:
    # A |: B :| C  → B plays twice.
    flags = flags_from(
        [(1, None, None), (2, "start", None), (3, "end", 2), (4, None, None)]
    )
    order, passes = linearize_measures(flags)
    assert order == [1, 2, 3, 2, 3, 4]
    assert passes == [1, 1, 1, 2, 2, 1]


def test_repeat_times_three() -> None:
    flags = flags_from(
        [(1, "start", None), (2, "end", 3), (3, None, None)]
    )
    order, passes = linearize_measures(flags)
    assert order == [1, 2, 1, 2, 1, 2, 3]
    assert passes == [1, 1, 2, 2, 3, 3, 1]


def test_first_and_second_endings() -> None:
    # M1 |: M2 M3 (volta 1) :| M4 M5 (volta 2) → M1 M2 M3 M4 M5.
    flags = flags_from(
        [
            (1, None, None),
            (2, "start", None),
            (3, "end", 2),
            (4, None, None),
            (5, None, None),
        ]
    )
    flags = voltas(flags, 1, [2, 3])
    flags = voltas(flags, 2, [4, 5])
    order, passes = linearize_measures(flags)
    assert order == [1, 2, 3, 4, 5]
    assert passes == [1, 1, 1, 2, 2]


def test_nested_repeats() -> None:
    # |: M1 |: M2 M3 :| M4 :| M5  (music21 agrees: [1,2,3,2,3,4,1,2,3,2,3,4,5])
    flags = flags_from(
        [
            (1, "start", None),
            (2, "start", None),
            (3, "end", 2),
            (4, "end", 2),
            (5, None, None),
        ]
    )
    order, passes = linearize_measures(flags)
    assert order == [1, 2, 3, 2, 3, 4, 1, 2, 3, 2, 3, 4, 5]
    assert passes == [1, 1, 1, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1]


def test_malformed_double_backward_repeat_hits_cap() -> None:
    # Two consecutive end repeats with no structure loop forever → the cap
    # must fail with an actionable NormalizeError, not hang.
    flags = flags_from([(1, None, None), (2, "end", 2), (3, "end", 2), (4, None, None)])
    with pytest.raises(NormalizeError, match="repeat expansion exceeded"):
        linearize_measures(flags)


def test_invalid_repeat_times_rejected() -> None:
    flags = flags_from([(1, "start", None), (2, "end", 0), (3, None, None)])
    with pytest.raises(NormalizeError, match="backward repeat times=0 is invalid"):
        linearize_measures(flags)


def test_build_repeat_map_main_then_repeat() -> None:
    flags = flags_from([(1, None, None), (2, None, None), (3, "end", 2), (4, None, None)])
    order, passes = linearize_measures(flags)
    starts = {0: 0.0, 1: 4.0, 2: 8.0, 3: 12.0, 4: 16.0, 5: 20.0, 6: 24.0}
    ends = {0: 4.0, 1: 8.0, 2: 12.0, 3: 16.0, 4: 20.0, 5: 24.0, 6: 28.0}
    repeat_map = build_repeat_map(flags, order, passes, starts, ends)
    assert repeat_map == [
        {"label": "A", "startBeat": 0.0, "endBeat": 12.0, "source": "main"},
        {"label": "A", "startBeat": 12.0, "endBeat": 24.0, "source": "repeat-of-A"},
        {"label": "B", "startBeat": 24.0, "endBeat": 28.0, "source": "main"},
    ]


def test_build_repeat_map_second_ending_is_main() -> None:
    flags = flags_from(
        [
            (1, None, None),
            (2, "start", None),
            (3, "end", 2),
            (4, None, None),
            (5, None, None),
        ]
    )
    flags = voltas(flags, 1, [2, 3])
    flags = voltas(flags, 2, [4, 5])
    order, passes = linearize_measures(flags)
    starts = {0: 0.0, 1: 4.0, 2: 8.0, 3: 12.0, 4: 16.0}
    ends = {0: 4.0, 1: 8.0, 2: 12.0, 3: 16.0, 4: 20.0}
    repeat_map = build_repeat_map(flags, order, passes, starts, ends)
    # M1-M3 is the first section; the second ending (M4-M5) appears only once
    # so it is "main" material, not a repeat of an earlier section.
    assert repeat_map[0] == {"label": "A", "startBeat": 0.0, "endBeat": 12.0, "source": "main"}
    assert repeat_map[1] == {"label": "B", "startBeat": 12.0, "endBeat": 20.0, "source": "main"}