# Specifications

Formal, versioned specifications that the app, API, CMS, and pipeline all build
against.

| Spec | Status | Location |
|---|---|---|
| SongPack v1 (`songpack/v1`) | Done (P1.1) | `songpack-v1.md`; canonical JSON Schema at `/content/schema/songpack-v1.json` (§8.1.10) |
| API (OpenAPI) | Planned — Phase 1 | TBD |
| Curriculum YAML | Defined in plan §8.3.3 | Decisions in `../adr/`; examples live under `/content/curriculum/` |

A spec is *done* when the canonical schema exists in one place and every consumer —
Python pipeline, PHP API, Kotlin tests — validates against it, with drift between
copies a CI failure (§8.1.10).