"""Repeat/jump expansion — an explicit state machine (plan §8.2.3).

The format is linear: repeated material is written out again at its later beat
offsets, and a ``repeatMap`` records which source measures produced which
output beats. This is the single most bug-prone transformation in the
pipeline, so it is deliberately a state machine with its own unit-test suite
(tests/test_repeat_expansion.py), not a music21 helper.

v0 supported set (documented in pipeline/README.md and docs/specs/pipeline-v0.md):
- simple repeats (``|: ... :|``) — the backward repeat jumps to the nearest
  still-open forward repeat, or the start of the piece when none is open;
- first/second endings (volta brackets with numeric endings);
- nested repeats, because the open-bracket stack is hierarchical by
  construction.

D.S./D.C./Coda/Segno/Fine are rejected with a named error in stage 2 before
this state machine ever runs.

The machine walks source measures in performance order, maintaining two
stacks:

1. ``open_starts`` — indices of forward-repeat measures not yet matched by a
   backward repeat (the brackets). A backward repeat pops the top.
2. ``sections`` — active repeated sections with pass counts. A backward
   repeat either increments the matching section's pass count (and jumps back
   when a pass remains) or opens a fresh section.

Volta membership is checked on every pass after the first: a measure whose
volta numbers exclude the current pass is skipped (the whole ending group is
skipped in one jump).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from pipeline.build.errors import NormalizeError

# A pathological repeat structure (e.g. ``:|`` with no forward repeat) would
# otherwise loop forever; the cap turns it into an actionable error.
MAX_OUTPUT_MEASURES = 2000


@dataclass
class MeasureFlag:
    """What the state machine needs to know about one source measure."""

    measure: int
    starts_repeat: bool = False
    end_repeat_times: int | None = None  # None = no backward repeat here
    voltas: list[int] = field(default_factory=list)  # ending numbers

    @property
    def has_volta(self) -> bool:
        return bool(self.voltas)


def _section_start(flags: list[MeasureFlag], open_starts: list[int]) -> int:
    """The backward-repeat jump target: the most recent unmatched forward
    repeat, or 0 (the piece start) when no forward repeat is open."""
    if open_starts:
        return open_starts.pop()
    return 0


def linearize_measures(flags: list[MeasureFlag]) -> tuple[list[int], list[int]]:
    """Return (measure_numbers_in_performance_order, pass_number_per_measure).

    ``pass_number`` is 1 for first-play material and increments for each
    replay of a repeated section, which is what lets a later stage draw the
    ``repeatMap`` and skip building chunk boundaries across pass changes.

    Raises NormalizeError for malformed repeat structures that exceed the
    output cap or use a backward repeat times below 1.
    """
    n = len(flags)
    order: list[int] = []
    passes: list[int] = []
    open_starts: list[int] = []
    sections: list[dict[str, Any]] = []
    index = 0

    def current_pass() -> int:
        return sections[-1]["passes"] + 1 if sections else 1

    while index < n:
        if len(order) >= MAX_OUTPUT_MEASURES:
            raise NormalizeError(
                f"repeat expansion exceeded {MAX_OUTPUT_MEASURES} output measures — "
                "malformed or pathologically nested repeat structure (check repeat "
                "barlines and volta endings)"
            )
        flag = flags[index]

        # Volta membership: on a repeated pass, skip measures whose ending
        # excludes the current pass, jumping the whole ending group.
        if sections and sections[-1]["has_voltas"]:
            pass_number = current_pass()
            if flag.has_volta and pass_number not in flag.voltas:
                while (
                    index < n
                    and flags[index].has_volta
                    and current_pass() not in flags[index].voltas
                ):
                    index += 1
                continue

        # A forward repeat opens a bracket: the matching backward repeat jumps
        # back to THIS measure (re-pushing on every pass so nested sections
        # re-open correctly).
        if flag.starts_repeat:
            open_starts.append(index)

        order.append(flag.measure)
        passes.append(current_pass())
        index += 1

        # The measure that just played may carry a backward repeat.
        previous = flags[index - 1]
        if previous.end_repeat_times is None:
            continue
        times = previous.end_repeat_times
        if times < 1:
            raise NormalizeError(
                f"measure {previous.measure}: backward repeat times={times} is invalid"
            )
        start = _section_start(flags, open_starts)

        if sections and sections[-1]["start"] == start:
            # Another pass of the section currently on top.
            sections[-1]["passes"] += 1
            if sections[-1]["passes"] >= times:
                sections.pop()
            else:
                index = start
        else:
            # A fresh section (either nested or first time through).
            section = {
                "start": start,
                "times": times,
                "passes": 1,
                "has_voltas": any(f.has_volta for f in flags[start:index]),
            }
            sections.append(section)
            if times <= 1:
                sections.pop()
            else:
                index = start

    return order, passes


def measure_flags_from_parts(parts: list[dict[str, Any]], score) -> list[MeasureFlag]:
    """Build MeasureFlag[] from the first part's measure dicts plus music21
    volta spanners (RepeatBracket), which the extraction dicts do not carry."""
    if not parts:
        return []
    measures = parts[0]["measures"]
    flags: list[MeasureFlag] = []
    for idx, info in enumerate(measures):
        end_times = info.get("endRepeatTimes")
        flags.append(
            MeasureFlag(
                measure=info["measure"],
                starts_repeat=bool(info.get("startsRepeat")),
                end_repeat_times=end_times if end_times else None,
            )
        )
    # Voltas come from music21 spanners spanning source measures.
    for spanner in score.flatten().spannerBundle.getByClass("RepeatBracket"):
        try:
            number = int(spanner.number)
        except (TypeError, ValueError):
            continue
        for element in spanner.getSpannedElements():
            measure_number = getattr(element, "number", None)
            if measure_number is None:
                continue
            for flag in flags:
                if flag.measure == measure_number:
                    flag.voltas.append(number)
    return flags


def build_repeat_map(
    flags: list[MeasureFlag],
    order: list[int],
    passes: list[int],
    measure_starts: dict[int, float],
    measure_ends: dict[int, float],
) -> list[dict[str, Any]]:
    """The optional manifest ``repeatMap`` (spec §6.8): one entry per
    contiguous output segment, labeled A/B/C by first appearance, with
    ``source`` = ``main`` or ``repeat-of-<label>``.

    ``measure_starts``/``measure_ends`` map output beat ranges per linear
    measure occurrence (key: linear position, see stage_normalize).
    """
    if not order:
        return []
    segments: list[dict[str, Any]] = []
    labels: dict[tuple[int, int], str] = {}  # (first_measure, last_measure) -> label
    seen: set[tuple[int, int]] = set()  # measure ranges that already appeared
    next_label = iter("ABCDEFGHIJKLMNOPQRSTUVWXYZ")

    start_pos = 0
    for pos in range(1, len(order) + 1):
        boundary = pos == len(order) or (
            order[pos] != order[pos - 1] + 1  # source measures not sequential
            or passes[pos] != passes[pos - 1]  # a repeat pass ended
        )
        if not boundary:
            continue
        segment_measures = order[start_pos:pos]
        first, last = segment_measures[0], segment_measures[-1]
        key = (first, last)
        if key not in labels:
            try:
                labels[key] = next(next_label)
            except StopIteration:
                labels[key] = f"S{len(labels) + 1}"
        label = labels[key]
        first_time = key not in seen
        seen.add(key)
        segments.append(
            {
                "label": label,
                "startBeat": measure_starts[start_pos],
                "endBeat": measure_ends[pos - 1],
                "source": "main" if first_time else f"repeat-of-{label}",
            }
        )
        start_pos = pos
    return segments