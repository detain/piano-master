"""Pipeline error hierarchy (plan §8.2).

Every failure the pipeline can produce is a ``PipelineError`` carrying the
stage that failed and a message a musician can act on. The CLI boundary wraps
ALL exceptions (including unexpected ones) so a stage failure never prints a
Python stack trace — the bad-input corpus asserts this.

The 5 Laws of Elegant Defense: fail fast, fail loud, with the name of the
thing that broke ("Unsupported feature" with no name is a bug in stage 2).
"""

from __future__ import annotations

from typing import NoReturn


class PipelineError(Exception):
    """Base failure: a specific, actionable message for one stage."""

    def __init__(self, message: str, *, stage: str | None = None) -> None:
        self.stage = stage
        super().__init__(message)

    def render(self) -> str:
        if self.stage:
            return f"[stage {self.stage}] {self.args[0]}"
        return str(self.args[0])


class CliError(PipelineError):
    """Usage error detected after argparse (missing state, bad flag combos)."""

    def __init__(self, message: str) -> None:
        super().__init__(message, stage="cli")


class IngestError(PipelineError):
    """Stage 1 failure — provenance is mandatory, never retrofitted."""


class ValidationError(PipelineError):
    """Stage 2 failure — the source cannot become a SongPack."""


class NormalizeError(PipelineError):
    """Stage 3 failure — a construct has no defined normalization."""


class HandsError(PipelineError):
    """Stage 4 failure — hand assignment has nothing to work with."""


class StrictError(PipelineError):
    """Build gate failure — a --strict build hit a stage warning."""


class AudioError(PipelineError):
    """Stage 9 failure — a rendered stem failed a measured check."""


class PackError(PipelineError):
    """Stage 10 failure — deterministic assembly or schema validation."""


class PublishError(PipelineError):
    """Stage 11 failure — the pre-publish gate or the pointer flip."""


def fail(message: str, *, stage: str | None = None) -> NoReturn:
    """Raise a PipelineError; exists so call sites read like English."""
    raise PipelineError(message, stage=stage)