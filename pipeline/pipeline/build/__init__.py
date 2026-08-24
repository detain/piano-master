"""Pipeline stage implementations (plan §8.2, P1.2 v0).

Each stage is a pure function ``(song doc, BuildConfig) -> (song doc,
StageReport)``; the runner (pipeline.build.runner) owns all I/O. See
docs/specs/pipeline-v0.md for the v0 contract and what is deliberately
deferred.
"""