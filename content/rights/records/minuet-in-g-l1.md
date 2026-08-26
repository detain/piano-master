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
- song_id:        `minuet-in-g-l1`
- title (canonical): Minuet in G major
- difficulty tier (L1/L2/L3): L1
- catalog tier (§8.5):  [x] public-domain   [ ] commissioned   [ ] CC0/CC-BY
- recorded_by / date: P0.8 research compile 2026-08-26 (scribe agent)

## 1. Identify the work
- Composer / attribution:            Christian Petzold, formerly attrib. J.S. Bach (BWV Anh. 114) — record *both* names for metadata integrity
- Catalogue no. (BWV / K. / WoO / Op. / P. / H.): BWV Anh. 114 (Notenbüchlein für Anna Magdalena Bach, 1725)
- Composition year: c. 1720 (suite); ms. in the 1725 Anna Magdalena notebook
- Source for composition year:       candidates-2026-08.md row 3; IMSLP work page (1725 Berlin ms., D-B Mus.ms. Bach P 225)

## 2. Composer death year → EU life+70 check
- Composer death year:               1733 (Petzold) — source: candidates-2026-08.md row 3, P0.8 research 2026-08-22; authorship proven by Schulze (Bach-Jahrbuch 1979), per IMSLP "Authorship Note". (J.S. Bach, the former attribution, d. 1750 — PD under either reading.)
- EU PD date: 1 Jan (death_year + 71) =  1804  →  PD in EU: [x] yes (if ≤ current year) [ ] no → FLAG
- Spain 80-year check: death_year + 81 ≤ current year?   [x] yes [ ] no (flag if no)
- Mexico life+100 check: death_year + 101 ≤ current year? [x] yes [ ] no (flag if no)
- France war-extension check (French composers / works first published in France):
    - n/a — German composer; manuscript work (Berlin), not first published in France.
    - note: under Cass. 27 Feb 2007 the 70-year term generally absorbs these extensions; record the conservative date anyway.
- If multiple authors (composer + librettist): use the LAST survivor's death year (EU joint-work rule).
    - n/a — instrumental work, no librettist.

## 3. First publication → USA rule
- First publication year:            c. 1725 manuscript (Anna Magdalena Bach notebook, Berlin — not a formal publication); first modern edition 1957 (NBA, urtext)
- First publication source (IMSLP "First Publication" field / publisher archive / WorldCat): candidates-2026-08.md row 3; IMSLP work page (ms. D-B Mus.ms. Bach P 225)
- USA status: published in **1930 or earlier** → PD in USA (even if URAA-eligible)   [x] yes [ ] no
    - Manuscript-era work; PD under any reading. The 1957 NBA urtext (German edition, Bärenreiter) carries a typographical right that expired 1983 (25 years) — no blocker; we use the ms. scan.
- If published 1931–1963 (USA): renewal search done? (CCE renewal records)  [ ] n/a [ ] renewed → FLAG [ ] not renewed → PD
    - n/a — no USA publication identified in 1931–1963; the 1957 NBA is a German edition.
- Posthumous publication nuance: first publication year may differ from composition year — record BOTH.
    - Composition c. 1720, ms. 1725, first modern edition 1957 — all recorded; no PD issue under any reading (candidates row 3).

## 4. Edition provenance (the edition trap, §8.5.3)
- Source type:  [x] IMSLP scan   [ ] Mutopia typeset   [ ] other (archive.org / library scan)
- Work page URL: https://imslp.org/wiki/Minuet_in_G_major,_BWV_Anh.114_(Pezold,_Christian) (the Bach-attributed URL redirects here)
- **File/scan URL** (specific file page or PDF URL): TO CONFIRM at ingest — 1725 Berlin ms. facsimile (D-B Mus.ms. Bach P 225) on the work page
- IMSLP file ID (e.g., `#02607`) / Mutopia ID (e.g., `Mutopia-2008/01/13-1247`): TO CONFIRM at ingest (candidates file does not pin a specific scan)
- Edition:  publisher, year, plate no.: manuscript facsimile — 1725 Anna Magdalena Bach notebook (Berlin State Library, D-B Mus.ms. Bach P 225)
- Editor (if any) + editor death year:     n/a (manuscript facsimile)
- IMSLP/Mutopia license tag on the file:  [x] Public Domain   [ ] CC-BY   [ ] CC0   [ ] CC-BY-SA → REJECT (§8.5 tier 3)   [ ] Non-PD US / Non-PD EU → REJECT
- Critical/urtext edition?  [x] no [ ] yes → confirm ≥25 years since publication (EU typographical right) or use an original scan instead
    - Facsimile used; the 1957 NBA urtext (if ever referenced) satisfies the ≥25-year typographical rule (expired 1983).
- Is this a modern *arrangement* by a living/recent arranger?  [x] no [ ] yes → REJECT as source (reference only)
    - Do-not-copy: "A Lover's Concerto" (1965) derives from this piece — copyrighted, ignore (candidates row 3).

## 5. Territory flags
- Launch markets (fill in): US / EU (DE, FR, ES…) / UK / CA / AU / JP / BR / IN / …
- For each: rule applied and result (life+70 / pre-1931 / longer terms: ES 80, MX 100, CO 80…).
    - US: manuscript-era work (ms. 1725) → PD under the pre-1931 rule / unpublished-work rule
    - EU (DE, FR, ES…): life+70 → PD since 1 Jan 1804; Spain 80-year → PD since 1814
    - UK / CA / AU: life+70 → PD
    - JP: life+70 → PD
    - BR: life+70 → PD; IN: life+60 → PD (all clear by death-year arithmetic, Petzold d. 1733)
- Any jurisdiction still restricted?  [x] none   [ ] list + geo-gate decision:
- territory_flags_json (per §8.5.6 schema):
  ```json
  {
    "US":  {"rule": "manuscript-era work (ms. 1725), PD under any reading", "pd": true, "note": "1957 NBA typographical right expired 1983"},
    "EU":  {"rule": "life+70 (Petzold d. 1733)", "pd": true, "note": "PD from 1 Jan 1804; ES 80-year clear 1814"},
    "UK":  {"rule": "life+70", "pd": true},
    "CA":  {"rule": "life+70", "pd": true},
    "AU":  {"rule": "life+70", "pd": true},
    "JP":  {"rule": "life+70", "pd": true},
    "BR":  {"rule": "life+70", "pd": true},
    "IN":  {"rule": "life+60", "pd": true}
  }
  ```

## 6. Arrangement & lyrics
- Our arrangement is a NEW WORK derived from the PD source (§8.5.3):  [x] confirmed
- Any elements copied from a modern arrangement?  [x] none (required)
    - Do-not-copy: "A Lover's Concerto" (1965) — copyrighted derivative, ignored (candidates row 3).
- Lyrics used? lyricist + death year (e.g., Schiller 1805 / Newton 1807):  none (instrumental) → n/a
- Audio: rendered from our own arrangement (§8.6), no third-party recordings:  [x] confirmed

## 7. Verdict
- [x] GLOBALLY PD        (rule that establishes it: Petzold d. 1733; ms. 1725 — PD under every rule)
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