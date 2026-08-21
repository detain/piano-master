# KeyQuest documentation

Index of the docs workspace. Docs are Markdown, one page per decision, spec, or
runbook — the ADR practice that governs them is plan §20 P0.1.4.

## Categories

| Category | Purpose | Contents |
|---|---|---|
| `adr/` | Architecture Decision Records — one page each: Context, Options, Decision, Consequences | `0001-monorepo-layout-and-toolchain.md`, … |
| `specs/` | Formal specifications (SongPack v1, OpenAPI, curriculum format) | SongPack v1 spec lands here in Phase 1 (plan §20 P1.1.1) |
| `runbooks/` | Operational runbooks (incidents, deployment, content takedown) | (empty — populated as operations are stood up) |

## Reading order for newcomers

1. [`../plan_piano.md`](../plan_piano.md) — the full product plan (start with §1–§4).
2. [`adr/`](adr/) — why the monorepo and every later non-obvious choice.
3. [`specs/`](specs/) — the formats the app, API, CMS, and pipeline all build against.