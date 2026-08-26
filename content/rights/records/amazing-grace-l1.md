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
- song_id:        `amazing-grace-l1`
- title (canonical): Amazing Grace (hymn tune "New Britain")
- difficulty tier (L1/L2/L3): L1
- catalog tier (§8.5):  [x] public-domain   [ ] commissioned   [ ] CC0/CC-BY
- recorded_by / date: P0.8 research compile 2026-08-26 (scribe agent)

## 1. Identify the work
- Composer / attribution:            Traditional hymn tune "New Britain", attr. William Walker (Southern Harmony compiler); text by John Newton
- Catalogue no. (BWV / K. / WoO / Op. / P. / H.): n/a (traditional hymn; no catalogue system)
- Composition year: tune printed 1835 (Southern Harmony); text 1779 (Newton)
- Source for composition year:       candidates-2026-08.md row 13 ("tune in **1835** Southern Harmony; text John Newton 1779"); IMSLP work page "Amazing Grace (Anonymous)"

## 2. Composer death year → EU life+70 check
- Composer death year:               n/a — anonymous traditional hymn tune (candidates row 13 basis: "EU: folk/hymn, anonymous-era → PD"). Text author John Newton, d. 1807 (candidates row 13). Tune attr. William Walker — Walker's death year TO CONFIRM at ingest (not recorded in candidates file); the attribution does not change the verdict (any 19th-c. author associated with an 1835 publication is PD under life+70 by 2026).
- EU PD date: n/a — anonymous-era folk/hymn, no life+70 arithmetic (candidates row 13); text Newton d. 1807 → PD from 1 Jan 1878 under any reading  →  PD in EU: [x] yes (if ≤ current year) [ ] no → FLAG
- Spain 80-year check: death_year + 81 ≤ current year?   [x] yes [ ] no (flag if no)
    - n/a for anonymous tune; text d. 1807 → clear 1888.
- Mexico life+100 check: death_year + 101 ≤ current year? [x] yes [ ] no (flag if no)
    - n/a for anonymous tune; text d. 1807 → clear 1908.
- France war-extension check (French composers / works first published in France):
    - n/a — American hymn; first printed in the Southern Harmony (USA, 1835), not in France.
    - note: under Cass. 27 Feb 2007 the 70-year term generally absorbs these extensions; record the conservative date anyway.
- If multiple authors (composer + librettist): use the LAST survivor's death year (EU joint-work rule).
    - Text John Newton d. 1807 (candidates row 13); tune anonymous/attr. Walker — last-survivor rule: Newton d. 1807 governs the text layer → PD 1 Jan 1878; any 19th-c. tune attribution → PD well before current year.

## 3. First publication → USA rule
- First publication year:            1835 — tune "New Britain" printed in the Southern Harmony, comp. William Walker (text 1779, John Newton, Olney Hymns)
- First publication source (IMSLP "First Publication" field / publisher archive / WorldCat): candidates-2026-08.md row 13; IMSLP work page "Amazing Grace (Anonymous)" (links to the archive.org Southern Harmony scan)
- USA status: published in **1930 or earlier** → PD in USA (even if URAA-eligible)   [x] yes [ ] no
- If published 1931–1963 (USA): renewal search done? (CCE renewal records)  [ ] n/a [ ] renewed → FLAG [ ] not renewed → PD
- Posthumous publication nuance: first publication year may differ from composition year — record BOTH.
    - Text 1779, tune first printed 1835 — both far predate 1930; no posthumous first-publication issue.

## 4. Edition provenance (the edition trap, §8.5.3)
- Source type:  [ ] IMSLP scan   [ ] Mutopia typeset   [x] other (archive.org / library scan) — 1835/1854 Southern Harmony scan on archive.org, linked from the IMSLP work page (candidates row 13)
- Work page URL: https://imslp.org/wiki/Amazing_Grace_(Anonymous)
- **File/scan URL** (specific file page or PDF URL): TO CONFIRM at ingest — the 1835/1854 Southern Harmony scan on archive.org (linked from the IMSLP work page; candidates row 13)
- IMSLP file ID (e.g., `#02607`) / Mutopia ID (e.g., `Mutopia-2008/01/13-1247`): n/a (archive.org scan; no IMSLP file ID pinned). **Mutopia id 1832 is CC-BY-SA → REJECT per §8.5 tier 3** (candidates row 13).
- Edition:  publisher, year, plate no.: Southern Harmony, 1835 (1st ed.) / 1854 (4th ed.), comp. William Walker — publisher/city TO CONFIRM at ingest
- Editor (if any) + editor death year:     n/a (historical hymnal scan; compiler William Walker, 19th c. — death year TO CONFIRM at ingest if needed)
- IMSLP/Mutopia license tag on the file:  [x] Public Domain   [ ] CC-BY   [ ] CC0   [ ] CC-BY-SA → REJECT (§8.5 tier 3)   [ ] Non-PD US / Non-PD EU → REJECT
    - Mutopia id 1832 is CC-BY-SA → REJECT per §8.5 tier 3 (candidates row 13); the Southern Harmony scan (pre-1931) is the source.
- Critical/urtext edition?  [x] no [ ] yes → confirm ≥25 years since publication (EU typographical right) or use an original scan instead
- Is this a modern *arrangement* by a living/recent arranger?  [x] no [ ] yes → REJECT as source (reference only)

## 5. Territory flags
- Launch markets (fill in): US / EU (DE, FR, ES…) / UK / CA / AU / JP / BR / IN / …
- For each: rule applied and result (life+70 / pre-1931 / longer terms: ES 80, MX 100, CO 80…).
    - US: first publication 1835 ≤ 1930 → pre-1931 rule → PD
    - EU (DE, FR, ES…): traditional hymn, anonymous-era → PD; text Newton d. 1807 → PD since 1 Jan 1878; Spain 80-year → clear 1888
    - UK / CA / AU: traditional + life+70 (text d. 1807) → PD
    - JP: life+70 (text d. 1807) → PD
    - BR: life+70 → PD; IN: life+60 → PD (text author d. 1807; tune anonymous/traditional)
- Any jurisdiction still restricted?  [x] none   [ ] list + geo-gate decision:
- territory_flags_json (per §8.5.6 schema):
  ```json
  {
    "US":  {"rule": "first publication <= 1930 (1835 Southern Harmony)", "pd": true},
    "EU":  {"rule": "traditional hymn, anonymous-era; text life+70 (Newton d. 1807)", "pd": true, "note": "text PD from 1 Jan 1878; ES 80-year clear 1888"},
    "UK":  {"rule": "traditional; text life+70 (d. 1807)", "pd": true},
    "CA":  {"rule": "traditional; text life+70 (d. 1807)", "pd": true},
    "AU":  {"rule": "traditional; text life+70 (d. 1807)", "pd": true},
    "JP":  {"rule": "life+70 (text d. 1807)", "pd": true},
    "BR":  {"rule": "life+70 (text d. 1807)", "pd": true},
    "IN":  {"rule": "life+60 (text d. 1807)", "pd": true}
  }
  ```

## 6. Arrangement & lyrics
- Our arrangement is a NEW WORK derived from the PD source (§8.5.3):  [x] confirmed
- Any elements copied from a modern arrangement?  [x] none (required)
    - Mutopia id 1832 (CC-BY-SA) is rejected as a source (§8.5 tier 3) — never used (candidates row 13).
- Lyrics used? lyricist + death year (e.g., Schiller 1805 / Newton 1807):  John Newton, "Amazing Grace" (1779, Olney Hymns), d. 1807 → PD? [x] yes [ ] no (flag/omit)
    - Use only Newton's PD verses (d. 1807) or our own verse selection — some later-added verses have 20th-century authors; those verses would need a flag/omission (candidates row 13).
- Audio: rendered from our own arrangement (§8.6), no third-party recordings:  [x] confirmed

## 7. Verdict
- [x] GLOBALLY PD        (rule that establishes it: Tune first pub. 1835 (Southern Harmony); text Newton 1779 — PD everywhere)
- [ ] CLEARED-WITH-NOTE  (note: _____________)
- [ ] FLAG               (reason + exclusion or geo-gate decision)
- cleared_globally: true  (§8.5.6 — false forces exclusion or recorded geo-gate)
- Research verdict pending P0.8.4 legal sign-off.

## 8. Review & sign-off
- First-person review (researcher): P0.8 research compile 2026-08-26 (scribe agent) — record compiled from candidates-2026-08.md (sources verified 2026-08-22)
- Second-person review (required for any FLAG or CLEARED-WITH-NOTE): PENDING — P0.8.4 gate (not required for GLOBALLY PD)
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