# Architecture Decision Records (ADRs)

An ADR is a one-page justification for a non-obvious decision, written so a future
engineer can argue with it (plan §20 P0.1.4). Phase 0 is expected to produce 8–12 of
them (model choice, coroutine posture, notation renderer, SongPack versioning, …).

## Format

- **Filename:** `NNNN-title.md` — zero-padded sequence number plus a kebab-case title.
- **Status field:** the first line is `Status: Accepted` or `Status: Superseded`
  (with a pointer to the ADR that superseded it).
- **Body — exactly four sections:**

  1. **Context** — the situation, constraint, or requirement that forces a decision.
     Cite plan sections where relevant.
  2. **Options** — the realistic alternatives considered (at least two).
  3. **Decision** — what we chose and why, precisely enough to implement.
  4. **Consequences** — what the decision costs and buys; the trade-offs we accepted.

## Rules

- One page. If the justification needs more, the decision is too big — split it.
- Every non-obvious choice gets an ADR; obvious ones get a comment.
- Changing a decision means writing a new ADR that supersedes the old one —
  never editing history.