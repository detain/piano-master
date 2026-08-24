# SongPack v1 — Format Specification

Status: **Done** (P1.1, plan §20 P1.1)
Canonical schema: `content/schema/songpack-v1.json` (this spec is the human
companion; the schema is the contract)
Consumers: Python pipeline (build-time validation), PHP API (publish-time
validation), Kotlin tests (fixture validation) — plan §8.1.10

This document is normative. Where this prose and the schema disagree, the
schema wins (it is the machine-enforced contract). Everything here is frozen
for all of Phase 1; changing it requires an ADR and a migration of the golden
fixtures (plan §20 P1.1).

---

## 1. Purpose and scope

SongPack v1 is the app-native format for playable piano content (plan §8.1).
MusicXML is compiled into SongPack **ahead of time** by the pipeline; the app
never parses MusicXML at runtime. The format answers one question: *what does
the renderer need in the next 16 milliseconds?* — pre-computed, not derived.

A pack is a zip archive `song_<id>_v<n>.pack` (deterministic ordering, no
timestamps). Inside it, two kinds of files:

**JSON-format-contract files** (validated by the schema — every field below is
machine-enforced):

| File | Contents |
|---|---|
| `manifest.json` | Identity, metadata, tempo map, chunk index, rights reference (§2) |
| `notes.json` | Note data + layout hints, per arrangement level (§3) |
| `chunks.json` | Chunk definitions, teaching modes, prerequisites (§4) |
| `skills.json` | Skill requirements/teachings, per arrangement level (§5) |

**Contractually-described non-JSON files** (prose contract only; no audio
files exist yet — plan §8.1.2/§8.1.7/§8.1.8):

| File | Contract |
|---|---|
| `audio/l<N>_backing_full.opus` | Full mix — demo/listen mode |
| `audio/l<N>_backing_norh.opus` | Minus right hand → LH practice |
| `audio/l<N>_backing_nolh.opus` | Minus left hand → RH practice |
| `audio/l<N>_backing_micsafe.opus` | Drums/pad only, no piano-range content (§5.6) |
| `audio/l<N>_reference.opus` | The piece played correctly, for "listen first" |
| `audio/count_in.opus` | Click bars, tempo-agnostic (pitched click, resampled) |
| `cover.webp` | 1:1, 512 px and 1024 px variants |
| `LICENSE.txt` | Attribution text if CC-BY sourced (§8.5.5) |
| `checksums.json` | sha256 per file, signed at publish (§8.1.8) |

The audio stem contract is summarized in §9. Integrity in §10.

---

## 2. Units policy (read this first)

**Beats are the unit of musical time. Seconds appear NOWHERE in note data.**
This is the #1 source of bugs when violated (plan §8.1.1), so:

- One beat = **one quarter note**, always. The time signature tells you how
  many beats are in a measure (`numerator * 4 / denominator`). In 6/8 a
  measure is 3 beats and an eighth note has `durBeats: 0.5`. The beat unit is
  therefore time-signature-independent, which is what makes mid-song time
  signature changes and tempo control (§11) work without rewriting notes.
- All note timing fields (`startBeat`, `durBeats`, pickup, chunk bounds,
  tempoMap `atBeat`) are in beats.
- The **single allowed exception** is `durationSecondsAtDefaultTempo` in the
  manifest, which is **display-only**: clients must never use it for timing.
- The schema forbids a `seconds` key on every note record (see §3.3) and the
  validator enforces it. `seconds` anywhere in note data is a format bug.
- `durationBeats` counts from beat 0, which is the first pickup note. A pack
  with a 1-beat pickup and 8 measures of 4/4 has `durationBeats: 33`.
- Durations are floats. Fractional beat values such as `0.3333333333333333`
  (= 1/3, an eighth-note triplet in 6/8) are normal. Clients must not assume
  exact float summation of durations; the pipeline formats fixed rational-ish
  floats deterministically (§8.2.10).

---

## 3. `notes.json` — the note record

### 3.1 Per-level shape (design decision — frozen)

`notes.json` holds **separate full note sets per arrangement level**, never a
diff (plan §8.1.6 — diffs were considered and rejected):

```json
{
  "levels": {
    "1": [ { "pitch": 60, ... }, ... ],
    "2": [ ... ]
  }
}
```

- Object keys are the **decimal string of `arrangementLevels[].level`**
  (`"1"`, `"2"`, `"3"`), so the notes file and the manifest cannot disagree
  about which level is which.
- Each array is a full, playable note set for that level, ordered
  **chronologically by `startBeat` ascending**; notes with equal `startBeat`
  are ordered by `hand` (R before L), then `pitch` ascending. This is the
  canonical array order — `tieToIndex` refers to it.
- The `levels` map is deliberately strict in the schema (`additionalProperties:
  false`): its keys are a closed, enumerated key space matching
  `arrangementLevels`, so an unknown key there is a content error, not a
  forward-compatible field. The file itself stays open for future top-level
  keys.

### 3.2 The note record, field by field

| Field | Type | Units | Required | Semantics |
|---|---|---|---|---|
| `pitch` | integer 21–108 | MIDI | **required** | A0 (21) to C8 (108). The app's only pitch representation. |
| `startBeat` | number ≥ 0 | beats | **required** | Absolute beats from song start, pickup-adjusted. |
| `durBeats` | number > 0 | beats | **required** | Duration in beats. |
| `hand` | enum `L` \| `R` | — | **required** | Never null (§8.2.4). Used by the scorer and LH/RH hand-isolation. |
| `staff` | enum `1` \| `2` | — | **required** | Visual staff (1 upper, 2 lower). **NOT always the hand** — cross-staff passages are common (§8.1.4). The renderer uses `staff`; the scorer uses `hand`. |
| `voice` | integer ≥ 1 | — | **required** | Voice index within the staff. |
| `finger` | integer 1–5 | — | optional | Present only where the lesson teaches fingering. |
| `tieToIndex` | integer ≥ 0 | array index | optional | Index, within this level's notes array, of the note this note ties **into** — an INDEX reference, never a beat reference (§8.1.4). Must point forward to a later note of the same pitch (validator-enforced). Ties may cross chunk boundaries. |
| `accidental` | enum `sharp` \| `flat` \| `natural` \| null | — | optional | **Display-only**; `pitch` is absolute. A client must never derive pitch from key signature + accidental. |
| `beamGroup` | integer ≥ 0 | — | optional | Precomputed beam-group id (§8.1.4). |
| `lane` | integer ≥ 0 | — | optional | Note-bar skin lane assignment (§7.2). |
| `xHint` | number ≥ 0 | spacing unit | optional | Precomputed horizontal spacing unit (§8.1.4). |
| `isOrnamentExpansion` | boolean | — | optional, default `false` | True for notes generated from a trill/mordent. |
| `scoringWeight` | number 0–1 | — | optional, default `1.0` | Ornament expansions default 0.2; grace notes 0 (§8.1.4). |

### 3.3 The `seconds` prohibition

Every note record carries the schema pattern:

```json
"not": { "required": ["seconds"] }
```

which rejects **any** note object that contains a `seconds` key (even
`seconds: null`) while leaving `additionalProperties` open for future keys —
reconciling "unknown keys are ignored" (§8.1.9) with "forbid seconds"
(§8.1.1).

> **Implementation note (why not the obvious alternative):** the pattern
> `not: { "properties": { "seconds": {} } }` was considered and rejected. In
> JSON Schema, `properties` is vacuously valid for any object *without* the
> named key, so `not` would reject **every** note. `required` checks key
> presence only, which is exactly the semantics wanted here. The schema
> comment documents this.

---

## 4. `chunks.json` — the pedagogy, encoded as data

`chunks.json` is a bare JSON **array** of chunk objects, in teaching order
(the array index is not `ord`; `ord` is explicit). Plan §8.1.5.

| Field | Type | Units | Required | Semantics |
|---|---|---|---|---|
| `chunkId` | string (non-empty) | — | **required** | Stable identifier, unique within the pack. |
| `ord` | integer ≥ 1 | — | **required** | 1-based teaching order. |
| `startBeat` | number ≥ 0 | beats | **required** | Chunk start. Validator: `< endBeat` and within `durationBeats`. |
| `endBeat` | number | beats | **required** | Chunk end. Validator: `> startBeat` and `≤ durationBeats`. |
| `teachingModes` | array of enum `RH` \| `LH` \| `BOTH`, min 1, unique | — | **required** | **Ordered** — the sequence the learner walks. This is the whole hands-separate-then-together pedagogy expressed as data (§8.1.5). |
| `waitModeDefault` | object `{RH, LH, BOTH}` booleans | — | **required** | Per-mode wait-on-input defaults (§8.1.5). |
| `prerequisiteChunks` | array of string | — | **required** | `chunkId`s that must be completed first. May be empty. Validator: each must exist. |
| `loopSafe` | boolean | — | **required** | Can this chunk loop musically without a lead-in? Auto-loop suggestions skip unsafe chunks (§8.1.5). |
| `countInBeats` | integer ≥ 0 | beats | **required** | Click count-in length. |
| `label` | string (non-empty) | — | **required** | Shown to the user, e.g. "Chorus, first half". |
| `difficulty` | integer 1–10 | — | **required** | Chunk difficulty. |
| `newSkills` | array of string | — | **required** | Skill ids this chunk teaches (§8.1.5). May be empty. |

Chunks are chosen at phrase boundaries, 2–8 bars each (§8.2.5). `chunkCount`
in the manifest must equal `len(chunks)` (validator-enforced).

---

## 5. `skills.json` — skill requirements/teachings (design decision — frozen)

```json
{
  "levels": {
    "1": {
      "requiredSkills": ["hand_position_c", "quarter_notes"],
      "taughtSkills": ["pickup_entries"]
    }
  }
}
```

- Keyed like `notes.json` by the decimal string of `arrangementLevels[].level`.
- `requiredSkills`: skill ids the learner must already have to play this level
  (the pipeline's level-1 skill gate fails the build if L1 requires an
  unteachable skill — §8.2.6).
- `taughtSkills`: skill ids this level teaches; chunk `newSkills` entries
  refine *where* within the level.
- Both arrays are required; either may be empty. Skill ids are opaque strings
  (defined by the §9.2 skill graph, not by this format).
- The `levels` map is strict (closed key space), the file stays open — same
  rationale as `notes.json` §3.1.

---

## 6. `manifest.json` — field by field

| Field | Type | Units | Required | Semantics |
|---|---|---|---|---|
| `format` | const `"songpack/v1"` | — | **required** | Refuse to load anything else (§8.1.3). |
| `songId` | string (non-empty) | — | **required** | Stable identifier of the song, not the pack. |
| `packVersion` | integer ≥ 1 | — | **required** | Increments on every publish, **never reused** (§8.1.9). |
| `minAppVersion` | integer ≥ 1 | — | **required** | Client below this refuses the pack and prompts to update (§8.1.9). |
| `title` | string (non-empty) | — | **required** | |
| `subtitle` | string | — | optional | Most songs have no subtitle. |
| `composer` | string (non-empty) | — | **required** | |
| `arranger` | string (non-empty) | — | **required** | Always us, always, for PD works (§8.1.3). |
| `genre` | enum | — | **required** | Library browse facet (§9.4). Vocabulary may grow additively; clients must tolerate unknown values. |
| `era` | enum | — | **required** | Library browse facet (§9.4). Same additive rule. |
| `mood` | array of enum, min 1 | — | **required** | Library browse facet (§9.4). Same additive rule. |
| `difficulty` | integer 1–10 | — | **required** | Song-level difficulty (§8.2.6), human-overridable (override recorded with a reason). |
| `durationBeats` | number ≥ 0 | beats | **required** | Total song length in beats, including pickup. |
| `durationSecondsAtDefaultTempo` | number ≥ 0 | seconds | **required** | **DISPLAY-ONLY** — the single allowed seconds value in the pack (§2). |
| `defaultTempoBpm` | integer ≥ 1 | bpm | **required** | Tempo at the first tempoMap entry. |
| `minPracticeTempoPct` | integer 1–100 | percent | **required** | Floor for the tempo slider, per song. |
| `tempoMap` | array of tempoMap entries, min 1 | beats | **required** | First entry MUST have `atBeat: 0` (validator-enforced). |
| `timeSignatures` | array of timeSignature entries, min 1 | beats | **required** | First entry defines the opening measure. |
| `keySignatures` | array of keySignature entries, min 1 | beats | **required** | First entry defines the opening key. |
| `pickupBeats` | number ≥ 0 | beats | **required** | Anacrusis length. Getting this wrong offsets the whole song by a beat — the most common single content bug; the validator sanity-checks it against the opening time signature (§7). |
| `arrangementLevels` | array of arrangement-level entries, min 1 | — | **required** | At least one level. |
| `chunkCount` | integer ≥ 0 | — | **required** | Must equal `len(chunks)` (validator-enforced). |
| `chunkIndexRef` | string (non-empty) | — | **required** | Identifier of the chunk index record in `chunks.json`. |
| `rightsRef` | string (non-empty) | — | **required** | FK into the rights record (§8.5.6). **Publish is blocked without it.** |
| `audioProfile` | object (§6.6) | — | **required** | Loudness/codec/bitrate, recorded so a re-encode can be verified. |
| `buildInfo` | object (§6.7) | — | **required** | Build provenance. |
| `repeatMap` | array of repeat-map entries | beats | optional | Linearized repeat annotation (§6.8). Additive; clients that do not know it ignore it. |

### 6.1 `tempoMap[]` entry

| Field | Type | Units | Required | Semantics |
|---|---|---|---|---|
| `atBeat` | number ≥ 0 | beats | **required** | Beat at which this tempo change takes effect. First entry MUST be 0. |
| `bpm` | number > 0 | bpm | **required** | Beats per minute at this point. |
| `curve` | enum `step` \| `linear` | — | **required** | `step` = instant change; `linear` = rit./accel. ramping to the next entry's bpm. |

### 6.2 `timeSignatures[]` entry

| Field | Type | Units | Required | Semantics |
|---|---|---|---|---|
| `atBeat` | number ≥ 0 | beats | **required** | Beat at which this time signature takes effect. Mid-song changes are normal, not exotic. |
| `numerator` | integer 1–32 | — | **required** | |
| `denominator` | integer in `[1, 2, 4, 8, 16, 32]` | — | **required** | Beat unit as a power of two. |

### 6.3 `keySignatures[]` entry

| Field | Type | Units | Required | Semantics |
|---|---|---|---|---|
| `atBeat` | number ≥ 0 | beats | **required** | Beat at which this key signature takes effect. |
| `fifths` | integer −7…7 | — | **required** | Circle of fifths: 0 = C major / A minor, +1 = G major / E minor, −1 = F major / D minor, etc. |
| `mode` | enum `major` \| `minor` | — | **required** | |

### 6.4 `arrangementLevels[]` entry

| Field | Type | Units | Required | Semantics |
|---|---|---|---|---|
| `level` | integer ≥ 1 | — | **required** | 1 = Essentials, 2 = Intermediate, 3 = Pro (§8.1.6). Referenced by `notes.json`/`skills.json` as its decimal string. |
| `name` | string (non-empty) | — | **required** | Display name. |
| `difficulty` | integer 1–10 | — | **required** | Per-level difficulty. |
| `tier` | enum `free` \| `premium` | — | **required** | Free vs premium access tier. |

### 6.5 Vocabulary enums (`genre`, `era`, `mood`)

- `genre`: `classical`, `folk`, `traditional`, `children`, `etude`,
  `exercise`, `jazz`, `pop`, `contemporary`, `other`
- `era`: `renaissance`, `baroque`, `classical`, `romantic`, `modern`,
  `contemporary`, `traditional`, `other`
- `mood`: `calm`, `happy`, `sad`, `majestic`, `playful`, `serene`,
  `dramatic`, `lyrical`, `energetic`, `nostalgic`, `bright`, `tender`,
  `other`

These are bounded vocabularies. Extending a vocabulary is **additive**: old
clients must tolerate unknown values (treat as `other`), so a vocabulary
extension needs a spec+schema update (ADR-lite) but **not** a format version
bump — it introduces no new required behavior. `other` is the escape hatch so
a new piece never blocks on a schema change.

### 6.6 `audioProfile`

| Field | Type | Required | Semantics |
|---|---|---|---|
| `codec` | const `opus` | **required** | §8.1.7. |
| `sampleRateHz` | const `48000` | **required** | §8.1.7. |
| `loudnessLufs` | number | **required** | Integrated loudness target, −16 LUFS. |
| `truePeakDbTP` | number | **required** | True-peak ceiling, −1 dBTP. |
| `bitratesKbps.full` | integer ≥ 1 | **required** | Stereo full-mix bitrate (96 kbps default). |
| `bitratesKbps.micSafe` | integer ≥ 1 | **required** | Mono mic-safe stem bitrate (64 kbps default). |

### 6.7 `buildInfo` (design decision — frozen)

`buildInfo` is **required** in published packs: support needs provenance
everywhere. Golden fixtures carry minimal-but-valid values. Fields:

| Field | Type | Required | Semantics |
|---|---|---|---|
| `pipelineVersion` | string (non-empty) | **required** | Pipeline version that built this pack. |
| `sourceFileHash` | string (non-empty) | **required** | Hash of the source score, e.g. `sha256:<hex>`. |
| `buildTimestamp` | string, ISO-8601 UTC | **required** | e.g. `2026-08-24T00:00:00Z`. Format is advisory in some consumers; the Python validator enforces it via `FormatChecker`. |
| `gitSha` | string (non-empty) | **required** | Git SHA of `/pipeline` at build time. |

Non-hashed for determinism (§8.2.10), present for support (§8.1.3).

### 6.8 `repeatMap[]` — repeat structure (design decision — frozen)

**The format is linear.** The pipeline pre-expands repeats (A–B–A′, D.C., D.S.,
voltas) into a flat note timeline (plan §8.2.3). Repeat material is therefore
encoded as:

1. **Linearized notes** — repeated sections are written out again at their
   later beat offsets (this is the authoritative data);
2. **`loopSafe` chunks** — chunks that can loop musically without a lead-in
   (§8.1.5);
3. **`repeatMap` (optional)** — pure annotation describing the linearization
   for form visualization:

```json
"repeatMap": [
  { "label": "A",  "startBeat": 0.0,  "endBeat": 16.0, "source": "main" },
  { "label": "B",  "startBeat": 16.0, "endBeat": 32.0, "source": "main" },
  { "label": "A'", "startBeat": 32.0, "endBeat": 48.0, "source": "repeat-of-A" }
]
```

| Field | Type | Units | Required | Semantics |
|---|---|---|---|---|
| `label` | string (non-empty) | — | **required** | Human-readable section label. |
| `startBeat` | number ≥ 0 | beats | **required** | |
| `endBeat` | number | beats | **required** | Must be > startBeat (validator-enforced). |
| `source` | string | — | optional | Provenance annotation, e.g. `main`, `repeat-of-A`. |

---

## 7. Semantic checks (beyond the schema)

The schema is structural; cross-field consistency lives in the Python
validator (`pipeline/pipeline/songpack/validator.py`), which every consumer
reproduces as fixtures:

1. **pickupBeats sanity** (§8.1.3): `0 ≤ pickupBeats`; and `pickupBeats ≤` a
   full measure at the opening time signature (`numerator * 4 / denominator`).
   A pickup longer than a measure is an error — an anacrusis is at most one
   measure.
2. **tempoMap[0].atBeat == 0** — the tempo map must cover beat 0.
3. **Ties** (§8.1.4): every `tieToIndex` points to an existing index in the
   same level's notes array, points **forward** (`target > index`), and the
   target pitch matches. Ties may cross chunk boundaries.
4. **Chunks** (§8.1.5): `chunkId` unique; `startBeat < endBeat`; both within
   `durationBeats`; every `prerequisiteChunks` entry exists.
5. **chunkCount == len(chunks)** (§8.1.3).
6. **Level ids**: every level id in `notes.json`/`skills.json` exists in
   `arrangementLevels[].level` (missing notes for a declared level is a
   warning).
7. **Finite numbers**: NaN/Infinity are not JSON; a hand-authored file that
   contains them (via a non-JSON parser) is rejected at parse time.

---

## 8. Forward compatibility and versioning (§8.1.9)

- **Unknown keys are ignored by clients.** `additionalProperties` is open on
  every forward-compatible object type. The only forbidden key is `seconds` on
  note records (§3.3). This is what lets the format grow without breaking
  shipped clients.
- **New optional field**: no version bump; old clients ignore it.
- **New required behavior**: bump `minAppVersion`; old clients hide the song
  from the catalog rather than downloading and failing (the catalog API
  filters by client version).
- **`songpack/v2`** would be a **parallel format** during a transition window
  (pipeline emits both), never a flag day.
- **Republishing** an existing song increments `packVersion` (never reused);
  clients holding the old pack are told at catalog refresh and re-download in
  the background. **Progress keys are on `songId` + level + chunk ordinal,
  never on pack version.**
- **Golden fixtures are the regression test**: content authored in Phase 1
  week 4 must load unmodified at launch. A CI job builds the fixtures against
  the current schema on every pipeline change.

---

## 9. Audio stem contract (summary of plan §8.1.7)

- **48 kHz, Opus.** Stereo 96 kbps for full mixes; mono 64 kbps for the
  mic-safe stem.
- **Loudness normalized to −16 LUFS integrated, −1 dBTP ceiling**, uniformly
  across the whole catalog (a volume jump between songs reads as broken).
- **Every stem is sample-aligned to beat 0** including the pickup, with
  silence padded rather than trimmed. A trimmed stem desyncs the whole song
  and looks like a player bug.
- **The mic-safe stem must contain no energy in the piano fundamental range**
  (roughly 80 Hz–2 kHz for the played register). The build measures this and
  fails if violated — it is the difference between mic mode working in a real
  room and not.
- `reference.opus` is the piece played correctly at default tempo, for
  "listen first".

---

## 10. Integrity and packaging (plan §8.1.8)

`checksums.json` holds sha256 per file; the pack itself is signed at publish.
The client verifies on download and on load; a failed verification deletes and
re-downloads rather than attempting partial use.

---

## 11. One schema, three consumers (§8.1.10)

The canonical JSON Schema lives in exactly one place:
`content/schema/songpack-v1.json`. Every consumer reads that file **directly
from the repository** — never a committed copy — so drift is impossible by
construction, and a CI drift guard fails if any copy ever appears:

| Consumer | Where | Role | Drift mechanism |
|---|---|---|---|
| Python pipeline | `pipeline/pipeline/songpack/validator.py` | Build-time validation (primary; most checks) | Loads `content/schema/songpack-v1.json` via `Path(__file__).resolve().parents[3]` (env override `KEYQUEST_SONGPACK_SCHEMA`) |
| PHP API | `api/tests/SongPack/SongPackSchemaTest.php` | Publish-time validation (never trust the pipeline) | Reads `content/schema/songpack-v1.json` from the repo |
| Kotlin tests | `android/app/src/test/.../songpack/SongPackSchemaTest.kt` | Fixture validation | A Gradle `Copy` task copies the canonical schema + fixtures into a **generated** test-resources dir (`build/generated/songpack`); the test reads that. The generated dir is gitignored — a committed copy would trip the CI drift guard |

The schema's root is a `oneOf` dispatcher over the four document types
(`manifest`, `notesFile`, `chunksFile`, `skillsFile`); consumers validate each
whole JSON file against the root. The Python validator additionally validates
each file against its specific `$def` for precise error messages; any document
that validates against its own `$def` validates against exactly one root
branch.

---

## 12. Schema metadata

- JSON Schema **draft-07** (`http://json-schema.org/draft-07/schema#`) — the
  intersection of what Python `jsonschema`, PHP `opis/json-schema`, and Kotlin
  `com.networknt:json-schema-validator` all support.
- `$id`: `https://keyquest.dev/schema/songpack-v1.json` (a stable identifier;
  internal `$ref`s are `#/$defs/...` fragments resolved within the document).
- The schema is the contract. This prose document is the human companion; the
  fixture suite (`content/fixtures/songpack-v1/`) is the executable
  specification.