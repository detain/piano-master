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
- song_id:        `swan-lake-theme-l1`
- title (canonical): Swan Lake, Act II No. 10 "Scène" — theme (ballet, Op. 20)
- difficulty tier (L1/L2/L3): L1
- catalog tier (§8.5):  [x] public-domain   [ ] commissioned   [ ] CC0/CC-BY
- recorded_by / date: P0.8 research compile 2026-08-26 (scribe agent)

## 1. Identify the work
- Composer / attribution:            Pyotr Ilyich Tchaikovsky
- Catalogue no. (BWV / K. / WoO / Op. / P. / H.): Op. 20 (Swan Lake), Act II No. 10 "Scène"
- Composition year: 1875–77
- Source for composition year:       candidates-2026-08.md row 8 ("1875–77 / full score first ed. **1895 Jurgenson** (piano reduction 1877, Langer)"); IMSLP work page "Swan Lake (ballet), Op.20"

## 2. Composer death year → EU life+70 check
- Composer death year:               1893 (source: candidates-2026-08.md row 8, P0.8 research 2026-08-22, verified against the IMSLP work page)
- EU PD date: 1 Jan (death_year + 71) =  1964  →  PD in EU: [x] yes (if ≤ current year) [ ] no → FLAG
- Spain 80-year check: death_year + 81 ≤ current year?   [x] yes [ ] no (flag if no)
- Mexico life+100 check: death_year + 101 ≤ current year? [x] yes [ ] no (flag if no)
- France war-extension check (French composers / works first published in France):
    - n/a — Russian composer; first editions published by Jurgenson (Moscow), not in France.
    - note: under Cass. 27 Feb 2007 the 70-year term generally absorbs these extensions; record the conservative date anyway.
- If multiple authors (composer + librettist): use the LAST survivor's death year (EU joint-work rule).
    - n/a — instrumental ballet theme we arrange; the ballet scenario/libretto is not used in our piano arrangement.
    - Reviser layer: the 1895 Jurgenson score reflects Drigo's revision; Drigo d. 1930 → that revision layer is PD in the EU since 1 Jan 2001 (candidates row 8).

## 3. First publication → USA rule
- First publication year:            1895 — first edition full score, Jurgenson, Moscow (piano reduction first published 1877, ed. Langer)
- First publication source (IMSLP "First Publication" field / publisher archive / WorldCat): IMSLP work page "First Publication" field; candidates-2026-08.md row 8
- USA status: published in **1930 or earlier** → PD in USA (even if URAA-eligible)   [x] yes [ ] no
- If published 1931–1963 (USA): renewal search done? (CCE renewal records)  [ ] n/a [ ] renewed → FLAG [ ] not renewed → PD
- Posthumous publication nuance: first publication year may differ from composition year — record BOTH.
    - Composition 1875–77, piano reduction 1877 (within the composer's lifetime, d. 1893), full score 1895 (posthumous) — both recorded; 1895 ≤ 1930, so no USA issue.

## 4. Edition provenance (the edition trap, §8.5.3)
- Source type:  [x] IMSLP scan   [ ] Mutopia typeset   [ ] other (archive.org / library scan)
- Work page URL: https://imslp.org/wiki/Swan_Lake_(ballet),_Op.20_(Tchaikovsky,_Pyotr)
- **File/scan URL** (specific file page or PDF URL): TO CONFIRM at ingest — 1895 Jurgenson first-edition full-score scan or 1877 piano reduction (both PD-tagged, per candidates row 8)
- IMSLP file ID (e.g., `#02607`) / Mutopia ID (e.g., `Mutopia-2008/01/13-1247`): TO CONFIRM at ingest (candidates file does not pin a specific scan)
- Edition:  publisher, year, plate no.: 1895, Jurgenson, Moscow (first-edition full score); or 1877 piano reduction, ed. Langer — plate no. TO CONFIRM at ingest
- Editor (if any) + editor death year:     n/a (first-edition full-score scan); if the 1877 piano reduction (ed. Langer) is used, Langer's death year TO CONFIRM at ingest
- IMSLP/Mutopia license tag on the file:  [x] Public Domain   [ ] CC-BY   [ ] CC0   [ ] CC-BY-SA → REJECT (§8.5 tier 3)   [ ] Non-PD US / Non-PD EU → REJECT
- Critical/urtext edition?  [x] no [ ] yes → confirm ≥25 years since publication (EU typographical right) or use an original scan instead
- Is this a modern *arrangement* by a living/recent arranger?  [x] no [ ] yes → REJECT as source (reference only)
    - We arrange the Act II No. 10 "Scène" swan theme for piano ourselves (candidates row 8); Drigo's 1895 revision layer is PD (Drigo d. 1930).

## 5. Territory flags
- Launch markets (fill in): US / EU (DE, FR, ES…) / UK / CA / AU / JP / BR / IN / …
- For each: rule applied and result (life+70 / pre-1931 / longer terms: ES 80, MX 100, CO 80…).
    - US: first publication 1895 ≤ 1930 → pre-1931 rule → PD
    - EU (DE, FR, ES…): life+70 → PD since 1 Jan 1964; Drigo revision layer PD since 1 Jan 2001; Spain 80-year → PD since 1974
    - UK / CA / AU: life+70 → PD
    - JP: life+70 → PD
    - BR: life+70 → PD; IN: life+60 → PD (all clear by death-year arithmetic, d. 1893)
- Any jurisdiction still restricted?  [x] none   [ ] list + geo-gate decision:
- territory_flags_json (per §8.5.6 schema):
  ```json
  {
    "US":  {"rule": "first publication <= 1930 (1895 Jurgenson full score)", "pd": true},
    "EU":  {"rule": "life+70 (d. 1893)", "pd": true, "note": "PD from 1 Jan 1964; Drigo revision layer (d. 1930) PD from 1 Jan 2001; ES 80-year clear 1974"},
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
    - None identified in the candidates research (row 8); Drigo's 1895 revision layer is PD (Drigo d. 1930) — no modern layer to avoid.
- Lyrics used? lyricist + death year (e.g., Schiller 1805 / Newton 1807):  none (instrumental theme; ballet scenario not set) → n/a
- Audio: rendered from our own arrangement (§8.6), no third-party recordings:  [x] confirmed

## 7. Verdict
- [x] GLOBALLY PD        (rule that establishes it: Tchaikovsky d. 1893 → EU PD 1964; pub. 1895 → USA pre-1931)
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