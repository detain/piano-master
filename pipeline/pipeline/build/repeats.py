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
2. ``sections`` — active repeated sections with pass counts. A section is
   pushed when its forward repeat is played (``passes`` = 0 while the first
   pass is in flight) and matched by its backward repeat, which increments
   the pass count and jumps back when a pass remains.

Volta membership is checked on every pass after the first: a measure whose
volta numbers exclude the pass of the section that CONTAINS it is skipped
(the whole ending group is skipped in one jump). Attribution to the owning
section — not the top of the stack — is what keeps a first ending nested
inside an outer repeat on its own pass count.
"""

from __future__ import annotations

import warnings
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


def _section_start(open_starts: list[int]) -> int:
    """The backward-repeat jump target: the most recent unmatched forward
    repeat, or 0 (the piece start) when no forward repeat is open."""
    if open_starts:
        return open_starts.pop()
    return 0


def _warn_voltas_without_repeat(flags: list[MeasureFlag]) -> None:
    """Warn when a volta ending has no enclosing repeat to distinguish.

    Such endings are played once (their volta numbers are meaningless), which
    is what the machine would do anyway — but a human score with a stray
    ``1.``/``2.`` bracket deserves a heads-up, not silence.

    A volta measure is covered when a repeat section contains it or ended
    before it (the second-ending continuation of a first/second pair).
    """
    spans: list[tuple[int, int]] = []
    open_starts: list[int] = []
    for i, flag in enumerate(flags):
        if flag.starts_repeat:
            open_starts.append(i)
        if flag.end_repeat_times is not None:
            start = open_starts.pop() if open_starts else 0
            spans.append((start, i))
    for i, flag in enumerate(flags):
        if not flag.has_volta:
            continue
        covered = any(s <= i <= e for s, e in spans) or any(e < i for _, e in spans)
        if not covered:
            warnings.warn(
                f"measure {flag.measure}: volta ending without an enclosing "
                "repeat — ending numbers ignored",
                UserWarning,
                stacklevel=3,
            )


def _bypass_skipped_marker(
    flags: list[MeasureFlag],
    index: int,
    open_starts: list[int],
) -> None:
    """Keep the bracket stack consistent when a volta skip bypasses a repeat
    marker.

    A skipped forward repeat is still the start of the section being repeated,
    so its bracket is re-pushed for the section's own backward repeat to pop.
    A skipped backward repeat would have popped its section's bracket — do the
    pop so the enclosing repeat's end finds the right bracket.
    """
    flag = flags[index]
    if flag.starts_repeat:
        open_starts.append(index)
    if flag.end_repeat_times is not None and open_starts:
        open_starts.pop()


def linearize_measures(flags: list[MeasureFlag]) -> tuple[list[int], list[int]]:
    """Return (measure_numbers_in_performance_order, pass_number_per_measure).

    ``pass_number`` is 1 for first-play material and increments for each
    replay of a repeated section, which is what lets a later stage draw the
    ``repeatMap`` and skip building chunk boundaries across pass changes.

    Sections are pushed when their forward repeat is played (``passes`` = 0
    while the first pass is in flight) and matched by their backward repeat.
    Every volta skip is attributed to the section that CONTAINS the skipped
    measure, so a first ending nested inside an outer repeat keeps its own
    pass count instead of borrowing the outer section's.

    Raises NormalizeError for malformed repeat structures that exceed the
    output cap or use a backward repeat times below 1.
    """
    n = len(flags)
    order: list[int] = []
    passes: list[int] = []
    open_starts: list[int] = []
    sections: list[dict[str, Any]] = []
    index = 0

    _warn_voltas_without_repeat(flags)

    def current_pass() -> int:
        # The pass reported for output: innermost section with a completed
        # pass. Fresh first-pass sections report their enclosing section's
        # replay, keeping the array monotonic per traversal.
        for section in reversed(sections):
            if section["passes"] >= 1:
                return section["passes"] + 1
        return 1

    def volta_pass(i: int) -> int | None:
        """The pass of the section that owns measure ``i`` for volta skips.

        Returns ``None`` for first-visit material (no filtering): the first
        time through, every measure plays regardless of ending numbers.
        """
        # A jump back into an open section: its next pass.
        for section in reversed(sections):
            if section["start"] == i:
                return section["passes"] + 1
        # A forward repeat not yet on the stack starts a fresh section.
        if flags[i].starts_repeat:
            return None
        # The innermost open section containing the measure.
        for section in reversed(sections):
            if section["start"] <= i:
                if section["passes"] == 0:
                    return None
                return section["passes"] + 1
        return None

    while index < n:
        if len(order) >= MAX_OUTPUT_MEASURES:
            raise NormalizeError(
                f"repeat expansion exceeded {MAX_OUTPUT_MEASURES} output measures — "
                "malformed or pathologically nested repeat structure (check repeat "
                "barlines and volta endings)"
            )
        flag = flags[index]

        # Volta membership: skip measures whose ending excludes the owning
        # section's current pass, jumping the whole ending group.
        if flag.has_volta:
            pass_number = volta_pass(index)
            if pass_number is not None and pass_number not in flag.voltas:
                while (
                    index < n
                    and flags[index].has_volta
                    and (p := volta_pass(index)) is not None
                    and p not in flags[index].voltas
                ):
                    _bypass_skipped_marker(flags, index, open_starts)
                    index += 1
                continue

        # A forward repeat opens a bracket (re-pushed on every pass so nested
        # sections re-open correctly) and, on a fresh visit, a section whose
        # first pass is now in flight.
        if flag.starts_repeat:
            open_starts.append(index)
            if not any(s["start"] == index for s in sections):
                sections.append({"start": index, "times": None, "passes": 0})

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
        start = _section_start(open_starts)

        matched = next(
            (k for k, section in enumerate(sections) if section["start"] == start),
            None,
        )
        if matched is not None:
            # Another pass of the section whose start this repeat matched.
            # Sections above it are complete (their ends were bypassed by a
            # volta skip and their pass-2 material just ran out).
            del sections[matched + 1 :]
            section = sections[matched]
            section["times"] = times
            section["passes"] += 1
            if section["passes"] >= times:
                del sections[matched:]
            else:
                index = start
        else:
            # A backward repeat with no matching forward bracket: a fresh
            # section (repeat from the piece start). Inner open sections are
            # complete — the flow bypassed their ends.
            sections = [s for s in sections if s["start"] < start]
            sections.append({"start": start, "times": times, "passes": 1})
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