# ADR-0001: Monorepo layout and toolchain

Status: Accepted
Date: 2026-08-20

## Context

Plan §20 P0.1.1 requires one repository with multiple top-level workspaces. The
SongPack content format (§8.1) is the contract between the app, the API, the CMS,
and the pipeline; a change to the format must land atomically across every consumer
or the catalog and the client silently disagree. Toolchain versions must be pinned
in files, not a wiki (P0.1.2), so CI and laptops build byte-identical artifacts.

## Options

- **Single monorepo** — all workspaces in one repository: one history, one CI,
  atomic cross-workspace changes.
- **Multi-repo** — one repository per workspace, each with its own CI and releases,
  coordinated by version tags and cross-repo PRs.

## Decision

Adopt a single monorepo with this top-level layout:

```
/engine        C++20 audio engine (portable core + Android bindings)
/android       Kotlin app (Gradle, Compose)
/api           Webman 2.1 backend
/cms           Vue 3 + Vite admin SPA
/pipeline      Python content workers
/content       SongPack sources, curriculum YAML, rights metadata
/docs          ADRs, specs, runbooks, phase-gate reviews
```

Toolchain versions are pinned in `toolchain.md` and, where they exist, in each
workspace's lockfile / version catalog (`composer.lock`, `package-lock.json`,
`gradle/libs.versions.toml`).

## Consequences

- **Positive:** SongPack schema changes land atomically across app/API/CMS/pipeline;
  one CI with cross-workspace visibility catches breakage before review (P0.1.3); a
  fresh clone plus `make bootstrap` produces a working dev environment (P0.1.1).
- **Negative:** cross-workspace PR coupling — a content-format change touches several
  workspaces in one PR; the checkout is larger; per-workspace toolchains share one CI
  budget (mitigated by the <10-minute wall-clock CI requirement, P0.1.3).
- **Licensing:** the repository is proprietary, all rights reserved (user decision
  2026-08-21); the MPL-2.0 text originally placed in the repo scaffold is superseded
  by the proprietary notice in `LICENSE`. Third-party libraries and tools keep their
  own licenses.