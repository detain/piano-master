"""KeyQuest content build pipeline (plan §8.2).

The 11 stages, left to right:

    1.  ingest      — source file + mandatory provenance → content store,
                      byte-for-byte and hashed; ingest fails without --source.
    2.  validate    — structural, range, musical-sanity, and unsupported-
                      construct checks; errors block, warnings proceed.
    3.  normalize   — expand repeats/voltas/D.C./D.S./Coda into a linear
                      timeline (explicit state machine), exact tuplet
                      fractions, grace/ornament expansion, canonical voices.
    4.  hands+fingering — cost-minimizing hand assignment + fingering drafts
                      with per-note confidence; low confidence is surfaced in
                      the CMS, never silently guessed.
    5.  chunking    — 2–8 bar phrase-boundary suggestions with rationale;
                      human confirms; never auto-published.
    6.  difficulty  — transparent weighted score + skill inference against the
                      §9.2 skill graph; the level-1 skill gate fails the build.
    7.  layout      — staff-skin and note-bar-skin engraving math precomputed
                      so the device only translates and draws.
    8.  levels      — L1/L2 automated reductions (drafts), L3 = source
                      arrangement; melody and chunk boundaries invariant.
    9.  audio       — stem rendering (DGX-520 rig or fluidsynth), then
                      loudness / alignment (≤ 10 ms drift) / mic-safe checks.
    10. pack        — deterministic SongPack assembly: sorted JSON keys, fixed
                      float formatting, zeroed zip timestamps, fixed seeds.
    11. publish     — pre-publish gate → upload → verify → pointer flip →
                      cache purge; rollback is a pointer flip.

Each stage is a pure function ``(input artifact, config) -> (output artifact,
report)``, runnable standalone from the CLI. Stage artifacts are cached by
input hash, so ``--from-stage`` re-runs never repeat an expensive render
(§8.2.12) and output is byte-identical for identical input (§8.2.10).
"""

__version__ = "0.1.0"