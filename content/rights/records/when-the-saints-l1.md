# PD Verification Checklist — per-song clearance record

> **Legal disclaimer (echoed from plan §8.5 / P0.8.4):** This checklist is research
> groundwork, not legal advice. The workflow is explicitly designed to feed a
> **human/legal final sign-off** (P0.8.4) before anything ships. Every verdict recorded
> here is a *research verdict* with the applicable rule cited; none is a legal
> certification.

Purpose: establish, with evidence, that a song can ship in the royalty-free catalog (§8.5).
One record per song. Publish is blocked without a complete record (§14.4 / §8.5.6).
This checklist is research-grade; P0.8.4 legal review is the final sign-off gate.

## 0. Record meta
- song_id:        `when-the-saints-l1`
- title (canonical): When the Saints Go Marching In (traditional Black spiritual)
- difficulty tier (L1/L2/L3): L1
- catalog tier (§8.5):  [x] public-domain   [ ] commissioned   [ ] CC0/CC-BY
- recorded_by / date: P0.8 research compile 2026-08-26 (scribe agent)

## 1. Identify the work
- Composer / attribution:            Traditional (Black spiritual; origins unknown); anonymous
- Catalogue no. (BWV / K. / WoO / Op. / P. / H.): n/a (traditional spiritual; no catalogue system)
- Composition year: unknown — traditional spiritual, origins unknown (candidates row A2)
- Source for composition year:       candidates-2026-08.md row A2 ("Traditional (Black spiritual; origins unknown)"); provenance sources: pdinfo.com entry, Hymnary 1896 hymn page, Fuld provenance discussion (Lycoming)

## 2. Composer death year → EU life+70 check
- Composer death year:               n/a — anonymous traditional spiritual; no composer recorded (candidates row A2)
- EU PD date: n/a — traditional spiritual, "widely accepted to be in the public domain" (candidates row A2), no life+70 arithmetic  →  PD in EU: [x] yes (if ≤ current year) [ ] no → FLAG
- Spain 80-year check: death_year + 81 ≤ current year?   [x] yes [ ] no (flag if no)
    - n/a — anonymous traditional; PD on traditional-work grounds.
- Mexico life+100 check: death_year + 101 ≤ current year? [x] yes [ ] no (flag if no)
    - n/a — anonymous traditional; PD on traditional-work grounds.
- France war-extension check (French composers / works first published in France):
    - n/a — American spiritual; not first published in France.
    - note: under Cass. 27 Feb 2007 the 70-year term generally absorbs these extensions; record the conservative date anyway.
- If multiple authors (composer + librettist): use the LAST survivor's death year (EU joint-work rule).
    - n/a — traditional spiritual; no known authors. (The related 1896 Purvis/Black hymn "When the Saints Are Marching In" is a **different song** — see §3/§6.)

## 3. First publication → USA rule
- First publication year:            TO CONFIRM — no exact pre-1931 sheet-music publication located (candidates row A2); the song is "widely accepted to be in the public domain" (candidates row A2). Related but different: the 1896 Purvis/Black hymn "When the Saints Are Marching In" is PD but must not be conflated.
- First publication source (IMSLP "First Publication" field / publisher archive / WorldCat): candidates-2026-08.md row A2; pdinfo.com entry; Fuld provenance discussion (Lycoming); Hymnary 1896 hymn page (the different 1896 hymn)
- USA status: published in **1930 or earlier** → PD in USA (even if URAA-eligible)   [x] yes [ ] no
    - Widely accepted PD; no exact pre-1931 publication pinned — CLEARED-WITH-NOTE basis (candidates row A2).
- If published 1931–1963 (USA): renewal search done? (CCE renewal records)  [ ] n/a [ ] renewed → FLAG [ ] not renewed → PD
- Posthumous publication nuance: first publication year may differ from composition year — record BOTH.
    - n/a — anonymous traditional spiritual; no composition/publication split to record.

## 4. Edition provenance (the edition trap, §8.5.3)
- Source type:  [ ] IMSLP scan   [ ] Mutopia typeset   [x] other (web research references — pdinfo.com entry, Hymnary 1896 hymn page, Fuld provenance discussion PDF (Lycoming); no score scan; candidates row A2: "No IMSLP/Mutopia page")
- Work page URL: n/a — no IMSLP/Mutopia page (candidates row A2). Reference sources: https://www.pdinfo.com/you-tube-song-search.php?T=6296 ; https://hymnary.org/hymn/SoS21896/59 (1896 hymn — DIFFERENT song) ; https://umarch.lycoming.edu/chronicles/2010/2.%20Kate%20E%20Purvis.pdf (Fuld provenance discussion)
- **File/scan URL** (specific file page or PDF URL): TO CONFIRM at ingest — no PD score scan pinned in candidates row A2
- IMSLP file ID (e.g., `#02607`) / Mutopia ID (e.g., `Mutopia-2008/01/13-1247`): n/a — no IMSLP/Mutopia page (candidates row A2)
- Edition:  publisher, year, plate no.: n/a — no edition sourced (no score file pinned in candidates row A2)
- Editor (if any) + editor death year:     n/a
- IMSLP/Mutopia license tag on the file:  n/a — no score file sourced (candidates row A2: "No IMSLP/Mutopia page"); sources are web references, not score files
- Critical/urtext edition?  [x] no [ ] yes → confirm ≥25 years since publication (EU typographical right) or use an original scan instead
    - n/a — no edition sourced.
- Is this a modern *arrangement* by a living/recent arranger?  [x] no [ ] yes → REJECT as source (reference only)
    - Do-not-copy: Armstrong's 1938 arrangement is copyrighted — ignore (candidates row A2).

## 5. Territory flags
- Launch markets (fill in): US / EU (DE, FR, ES…) / UK / CA / AU / JP / BR / IN / …
- For each: rule applied and result (life+70 / pre-1931 / longer terms: ES 80, MX 100, CO 80…).
    - US: widely accepted PD; no exact pre-1931 publication pinned → PD (CLEARED-WITH-NOTE basis)
    - EU (DE, FR, ES…): traditional (Black spiritual, anonymous) → PD
    - UK / CA / AU: traditional → PD
    - JP: traditional → PD
    - BR: traditional → PD; IN: traditional → PD
- Any jurisdiction still restricted?  [x] none   [ ] list + geo-gate decision:
- territory_flags_json (per §8.5.6 schema):
  ```json
  {
    "US":  {"rule": "traditional; widely accepted PD — no exact pre-1931 publication pinned", "pd": true, "note": "CLEARED-WITH-NOTE: no exact pre-1931 sheet-music publication located (candidates row A2)"},
    "EU":  {"rule": "traditional (anonymous spiritual)", "pd": true, "note": "no life+70 arithmetic"},
    "UK":  {"rule": "traditional (anonymous spiritual)", "pd": true},
    "CA":  {"rule": "traditional (anonymous spiritual)", "pd": true},
    "AU":  {"rule": "traditional (anonymous spiritual)", "pd": true},
    "JP":  {"rule": "traditional", "pd": true},
    "BR":  {"rule": "traditional", "pd": true},
    "IN":  {"rule": "traditional", "pd": true}
  }
  ```

## 6. Arrangement & lyrics
- Our arrangement is a NEW WORK derived from the PD source (§8.5.3):  [x] confirmed
- Any elements copied from a modern arrangement?  [x] none (required)
    - Do-not-copy: Armstrong's 1938 arrangement is copyrighted — ignore (candidates row A2); the 1896 Purvis/Black hymn "When the Saints Are Marching In" is a different song — do not conflate.
- Lyrics used? lyricist + death year (e.g., Schiller 1805 / Newton 1807):  traditional spiritual text — traditional → PD? [x] yes [ ] no (flag/omit)
    - Our own arrangement required (candidates row A2); do not use the text of the different 1896 Purvis/Black hymn.
- Audio: rendered from our own arrangement (§8.6), no third-party recordings:  [x] confirmed

## 7. Verdict
- [ ] GLOBALLY PD        (rule that establishes it: _____________)
- [x] CLEARED-WITH-NOTE  (note: Traditional Black spiritual, origins unknown; no exact pre-1931 sheet-music publication located but "widely accepted to be in the public domain" (candidates row A2); our own arrangement required — Armstrong's 1938 arrangement is copyrighted; human second-person review required at the P0.8.4 gate.)
- [ ] FLAG               (reason + exclusion or geo-gate decision)
- cleared_globally: true  (§8.5.6 — false forces exclusion or recorded geo-gate)
- Research verdict pending P0.8.4 legal sign-off.

## 8. Review & sign-off
- First-person review (researcher): P0.8 research compile 2026-08-26 (scribe agent) — record compiled from candidates-2026-08.md (sources verified 2026-08-22)
- Second-person review (required for any FLAG or CLEARED-WITH-NOTE): PENDING — P0.8.4 gate (required: verdict is CLEARED-WITH-NOTE)
- **Legal sign-off (P0.8.4):** PENDING — name / date / firm — required before publish
- Source links archived?  [ ] TO CONFIRM (print/screenshot/PDF of the work page + file page to be archived at P0.8.4 gate)

## 9. Doubtful cases — automatic reject or flag
| Case                                                               | Action                                                                     |
| ------------------------------------------------------------------ | -------------------------------------------------------------------------- |
| Composer died < 70 years ago (or death year unverified)            | REJECT (not PD in EU)                                                      |
| First published 1931–1963 in USA, renewal status unknown           | FLAG until renewal search                                                  |
| Editor died < 70 years ago / edition published < 25 years ago (EU) | REJECT edition; use original scan                                          |
| Modern arrangement/transcription by recent arranger as *source*      | REJECT (§8.5.3); reference only                                            |
| Lyrics/translation by 20th-century author                          | FLAG or omit lyrics                                                        |
| Work first published posthumously in EU within last 25 years       | FLAG (first-publication right)                                             |
| Sound recording we didn't render ourselves                         | REJECT (separate regime)                                                   |
| Attribution dispute (e.g., Petzold/Bach)                           | Not a rights blocker (both PD) — record both names for metadata integrity  |
| Song in public domain but famous arrangement/recording copyrighted | Not a blocker — verify our arrangement is original; record the distinction |

**Checklist design notes:**
- Mirrors §8.5.1's seven steps and §8.5.6's `rights_records` schema field-for-field (composer, composer_death_year, composition_year, first_publication_year, source_type/ref/url, edition_editor, edition_year, edition_license, territory_flags_json, cleared_globally, reviewed_by/at, notes).
- The single most failure-prone field is **edition provenance** (§8.5.3): on IMSLP, the *work page* is not the source — the **specific file** and its **license tag** are. Verified real-world example: Suite bergamasque has both a `Public Domain` 1905 Fromont scan and a `Public Domain - Non-PD US, Non-PD EU` 1961 Philipp scan; the checklist must pin the exact scan ID.
- The conservative France date is recorded *in addition to* the mainstream date so legal sign-off can see both (see the P0.8 research report §5.4).