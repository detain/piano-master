"""Repeat-expansion state machine unit tests (plan §8.2.3, §8.2.13).

The supported v0 set: simple repeats, first/second endings (voltas), and
nested repeats (the open-bracket stack is hierarchical by construction).
D.S./D.C./Coda are rejected in stage 2, never reaching this machine.
"""

from __future__ import annotations

import itertools
from collections.abc import Iterator

import pytest

from pipeline.build.errors import NormalizeError
from pipeline.build.repeats import (
    MAX_OUTPUT_MEASURES,
    MeasureFlag,
    build_repeat_map,
    linearize_measures,
)


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


def nested_volta_flags(measure_count: int, level: int) -> Iterator[list[MeasureFlag]]:
    """Yield every well-formed ``level``-deep nested+volta structure.

    ``level`` forward repeats followed by ``level`` backward repeats (strictly
    nested: all starts before all ends), every measure optionally carrying
    ending 1 or 2, with at least one ending on a backward repeat — the
    reviewer's brute-force working-set shape for the owning-section fix
    (0e60f14). Counts: 1080 structures for (5, 2), 14742 for (7, 3).
    """
    for starts in itertools.combinations(range(measure_count), level):
        for ends in itertools.combinations(range(measure_count), level):
            if max(starts) >= min(ends):
                continue  # a backward repeat before a forward repeat is not nested
            for endings in itertools.product((None, 1, 2), repeat=measure_count):
                if not any(endings[end] is not None for end in ends):
                    continue  # no ending on any backward repeat — not a volta structure
                yield [
                    MeasureFlag(
                        measure=index,
                        starts_repeat=index in starts,
                        end_repeat_times=2 if index in ends else None,
                        voltas=[endings[index]] if endings[index] is not None else [],
                    )
                    for index in range(measure_count)
                ]


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


def test_nested_voltas_use_owning_section_pass() -> None:
    # M0 |: M1 |: M2 :|(2, volta 1) M3 M4 :|(2, volta 2) — the inner first
    # ending (M1-M2) is judged against the INNER section's pass, so it plays
    # again on the outer's second pass instead of being skipped like an outer
    # first ending. Both outer passes play the full [0,1,2,3,4] block.
    flags = flags_from(
        [
            (0, "start", None),
            (1, "start", None),
            (2, "end", 2),
            (3, None, None),
            (4, "end", 2),
        ]
    )
    flags = voltas(flags, 1, [1, 2])
    flags = voltas(flags, 2, [3, 4])
    order, passes = linearize_measures(flags)
    assert order == [0, 1, 2, 3, 4, 0, 1, 2, 3, 4]
    assert passes == [1, 1, 1, 2, 2, 2, 2, 2, 2, 2]


def test_nested_voltas_previously_capping_now_terminates() -> None:
    # M0 |: M1 |: M2 M3 :|(2) M4 :|(2) with voltas 1=[M1], 2=[M4] used to
    # loop past the 2000-measure output cap: the skipped M1 left the inner
    # bracket stale, so M3's repeat kept matching the wrong section. With the
    # bracket stack kept consistent by the owning-section attribution, the
    # expansion terminates: the inner section plays [M1,M2,M3] then [M2,M3]
    # (M1 is a first ending), and the outer wraps both around M4.
    flags = flags_from(
        [
            (0, "start", None),
            (1, "start", None),
            (2, None, None),
            (3, "end", 2),
            (4, "end", 2),
        ]
    )
    flags = voltas(flags, 1, [1])
    flags = voltas(flags, 2, [4])
    order, passes = linearize_measures(flags)
    assert order == [0, 1, 2, 3, 2, 3, 4, 0, 1, 2, 3, 2, 3, 4]
    assert passes == [1, 1, 1, 1, 2, 2, 1, 2, 2, 2, 2, 2, 2, 2]


@pytest.mark.parametrize(
    ("measure_count", "level"),
    [(5, 2), (7, 3)],
    ids=["2-level", "3-level"],
)
def test_nested_volta_structures_never_hit_cap(measure_count: int, level: int) -> None:
    # Brute-force lock for the owning-section fix: no well-formed nested+volta
    # structure may trip the 2000-measure output cap (the stale-bracket bug
    # looped forever on some of them), and every expansion stays non-empty.
    for flags in nested_volta_flags(measure_count, level):
        try:
            order, _passes = linearize_measures(flags)
        except NormalizeError as exc:
            if "repeat expansion exceeded" in str(exc):
                pytest.fail(f"output cap raised for {flags}: {exc}")
            raise
        assert 1 <= len(order) <= MAX_OUTPUT_MEASURES


def test_volta_without_open_repeat_warns_and_succeeds() -> None:
    # A stray ending bracket with no repeat anywhere: the ending numbers are
    # meaningless, so the measures play once — but the score author deserves a
    # warning, raised through the warnings module (not printed).
    flags = flags_from([(1, None, None), (2, None, None), (3, None, None)])
    flags = voltas(flags, 1, [1, 2])
    with pytest.warns(UserWarning, match="volta ending without an enclosing repeat"):
        order, passes = linearize_measures(flags)
    assert order == [1, 2, 3]
    assert passes == [1, 1, 1]


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