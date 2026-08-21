# Content workspace

The source of truth for everything the product serves: songs, curriculum, rights,
and the schemas that define them. Nothing ships without passing through here.

## What lives here

| Directory | Contents |
|---|---|
| `songs/` | SongPack sources — one directory per song (source scores, intermediate artifacts, published packs) |
| `curriculum/` | Course and lesson content as human-readable YAML (`*.yaml`, format in plan §8.3.3) |
| `rights/` | Per-song rights records — mandatory before publish (plan §8.5.6) |
| `schema/` | Canonical schemas; first of them the SongPack JSON Schema (`songpack-v1.json`, plan §8.1.10) |

## Rules

- **Sources:** public-domain scores from IMSLP (editions explicitly marked public
  domain) or Mutopia only. Never scrape user-uploaded MusicXML — unknowable
  provenance is bad provenance (plan §8.5.3).
- **Rights first:** every published song requires a complete, reviewed rights record;
  publish is blocked without it (plan §8.2.11, §8.5.6). `cleared_globally = false`
  forces exclusion or an explicit geo-gating decision.
- **Curriculum is data:** authored as YAML before the CMS exists, so the CMS reads an
  existing shape rather than inventing one (plan §8.3.3).
- **One schema, three consumers:** the SongPack schema here is consumed by the Python
  pipeline, the PHP API, and Kotlin tests; drift between copies is a CI failure
  (plan §8.1.10).

Audio stems and other large build artifacts are gitignored
(`/content/songs/**/audio/`) — the pipeline regenerates them.