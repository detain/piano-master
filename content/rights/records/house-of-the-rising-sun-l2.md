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
- song_id:        `house-of-the-rising-sun-l2`
- title (canonical): House of the Rising Sun (traditional, disputed origins)
- difficulty tier (L1/L2/L3): L2
- catalog tier (§8.5):  [x] public-domain   [ ] commissioned   [ ] CC0/CC-BY
- recorded_by / date: P0.8 research compile 2026-08-26 (scribe agent)

## 1. Identify the work
- Composer / attribution:            Traditional (disputed origins); anonymous
- Catalogue no. (BWV / K. / WoO / Op. / P. / H.): n/a (traditional folk song; no catalogue system)
- Composition year: unknown — traditional folk song, disputed origins (candidates row A3); earliest published transcription 1925
- Source for composition year:       candidates-2026-08.md row A3 ("Traditional (disputed origins)", "Earliest published transcription **1925** (R.W. Gordon, *Adventure* magazine)"); Wikipedia "The House of the Rising Sun"; SecondHandSongs work page

## 2. Composer death year → EU life+70 check
- Composer death year:               n/a — anonymous traditional folk song; no composer recorded (candidates row A3)
- EU PD date: n/a — folk song, PD on traditional-work grounds (candidates row A3 basis: "folk → PD EU"), no life+70 arithmetic  →  PD in EU: [x] yes (if ≤ current year) [ ] no → FLAG
- Spain 80-year check: death_year + 81 ≤ current year?   [x] yes [ ] no (flag if no)
    - n/a — anonymous traditional; PD on traditional-work grounds.
- Mexico life+100 check: death_year + 101 ≤ current year? [x] yes [ ] no (flag if no)
    - n/a — anonymous traditional; PD on traditional-work grounds.
- France war-extension check (French composers / works first published in France):
    - n/a — American folk song; earliest published transcription in *Adventure* magazine (USA, 1925), not first published in France.
    - note: under Cass. 27 Feb 2007 the 70-year term generally absorbs these extensions; record the conservative date anyway.
- If multiple authors (composer + librettist): use the LAST survivor's death year (EU joint-work rule).
    - n/a — traditional folk song; no known authors. (First *recorded* 1933 by Ashley — a recording, not authorship; see §6.)

## 3. First publication → USA rule
- First publication year:            1925 — earliest published transcription, R.W. Gordon, *Adventure* magazine (candidates row A3)
- First publication source (IMSLP "First Publication" field / publisher archive / WorldCat): candidates-2026-08.md row A3; Wikipedia (R.W. Gordon transcription, *Adventure* magazine 1925); SecondHandSongs
- USA status: published in **1930 or earlier** → PD in USA (even if URAA-eligible)   [x] yes [ ] no
    - 1925 ≤ 1930 → pre-1931 rule → PD (candidates row A3).
- If published 1931–1963 (USA): renewal search done? (CCE renewal records)  [ ] n/a [ ] renewed → FLAG [ ] not renewed → PD
- Posthumous publication nuance: first publication year may differ from composition year — record BOTH.
    - n/a — traditional folk song, disputed origins; no composition/publication split to record (earliest publication recorded as the 1925 transcription).

## 4. Edition provenance (the edition trap, §8.5.3)
- Source type:  [ ] IMSLP scan   [ ] Mutopia typeset   [x] other (web research references — Wikipedia, SecondHandSongs; no score scan; candidates row A3: "No IMSLP/Mutopia page")
- Work page URL: n/a — no IMSLP/Mutopia page (candidates row A3). Reference sources: https://en.wikipedia.org/wiki/The_House_of_the_Rising_Sun ; https://secondhandsongs.com/work/6485
- **File/scan URL** (specific file page or PDF URL): TO CONFIRM at ingest — no PD score scan pinned in candidates row A3
- IMSLP file ID (e.g., `#02607`) / Mutopia ID (e.g., `Mutopia-2008/01/13-1247`): n/a — no IMSLP/Mutopia page (candidates row A3)
- Edition:  publisher, year, plate no.: n/a — no edition sourced (no score file pinned in candidates row A3)
- Editor (if any) + editor death year:     n/a
- IMSLP/Mutopia license tag on the file:  n/a — no score file sourced (candidates row A3: "No IMSLP/Mutopia page"); sources are web references, not score files
- Critical/urtext edition?  [x] no [ ] yes → confirm ≥25 years since publication (EU typographical right) or use an original scan instead
    - n/a — no edition sourced.
- Is this a modern *arrangement* by a living/recent arranger?  [x] no [ ] yes → REJECT as source (reference only)
    - Do-not-copy: The Animals' 1964 arrangement is copyrighted — ignore (candidates row A3).

## 5. Territory flags
- Launch markets (fill in): US / EU (DE, FR, ES…) / UK / CA / AU / JP / BR / IN / …
- For each: rule applied and result (life+70 / pre-1931 / longer terms: ES 80, MX 100, CO 80…).
    - US: first published transcription 1925 ≤ 1930 → pre-1931 rule → PD
    - EU (DE, FR, ES…): folk/traditional → PD (anonymous)
    - UK / CA / AU: traditional → PD
    - JP: traditional → PD
    - BR: traditional → PD; IN: traditional → PD
- Any jurisdiction still restricted?  [x] none   [ ] list + geo-gate decision:
- territory_flags_json (per §8.5.6 schema):
  ```json
  {
    "US":  {"rule": "first published transcription <= 1930 (1925 R.W. Gordon, Adventure)", "pd": true},
    "EU":  {"rule": "folk/traditional (disputed origins)", "pd": true, "note": "no life+70 arithmetic"},
    "UK":  {"rule": "traditional", "pd": true},
    "CA":  {"rule": "traditional", "pd": true},
    "AU":  {"rule": "traditional", "pd": true},
    "JP":  {"rule": "traditional", "pd": true},
    "BR":  {"rule": "traditional", "pd": true},
    "IN":  {"rule": "traditional", "pd": true}
  }
  ```

## 6. Arrangement & lyrics
- Our arrangement is a NEW WORK derived from the PD source (§8.5.3):  [x] confirmed
- Any elements copied from a modern arrangement?  [x] none (required)
    - Do-not-copy: The Animals' 1964 arrangement is copyrighted — our own arrangement required (candidates row A3). First *recorded* 1933 (Ashley) — sound-recording regime is separate; we render our own audio.
- Lyrics used? lyricist + death year (e.g., Schiller 1805 / Newton 1807):  traditional folk text (disputed origins) — traditional → PD? [x] yes [ ] no (flag/omit)
- Audio: rendered from our own arrangement (§8.6), no third-party recordings:  [x] confirmed

## 7. Verdict
- [ ] GLOBALLY PD        (rule that establishes it: _____________)
- [x] CLEARED-WITH-NOTE  (note: Traditional folk song, disputed origins; earliest published transcription 1925 (R.W. Gordon, *Adventure* magazine) → USA pre-1931; folk → PD EU; SecondHandSongs tags it PD (candidates row A3); first recorded 1933 (Ashley); The Animals' 1964 arrangement copyrighted — our own arrangement required; human second-person review required at the P0.8.4 gate.)
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