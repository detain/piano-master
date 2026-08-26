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
- song_id:        `greensleeves-l1-2`
- title (canonical): Greensleeves (traditional English, 16th c.)
- difficulty tier (L1/L2/L3): L1–L2
- catalog tier (§8.5):  [x] public-domain   [ ] commissioned   [ ] CC0/CC-BY
- recorded_by / date: P0.8 research compile 2026-08-26 (scribe agent)

## 1. Identify the work
- Composer / attribution:            Traditional English, 16th c. (registered 1580); anonymous
- Catalogue no. (BWV / K. / WoO / Op. / P. / H.): n/a (traditional tune; no catalogue system)
- Composition year: 16th c.
- Source for composition year:       candidates-2026-08.md row 14 ("Traditional English, 16th c. (registered 1580)"); Mutopia piece-info page id 1247

## 2. Composer death year → EU life+70 check
- Composer death year:               n/a — anonymous traditional English tune, 16th c. (registered 1580); no composer recorded (candidates row 14)
- EU PD date: n/a — traditional tune, no life+70 arithmetic (candidates row 14 basis: "EU: traditional → PD")  →  PD in EU: [x] yes (if ≤ current year) [ ] no → FLAG
- Spain 80-year check: death_year + 81 ≤ current year?   [x] yes [ ] no (flag if no)
    - n/a — anonymous 16th-c. traditional; PD on traditional-work grounds.
- Mexico life+100 check: death_year + 101 ≤ current year? [x] yes [ ] no (flag if no)
    - n/a — anonymous 16th-c. traditional; PD on traditional-work grounds.
- France war-extension check (French composers / works first published in France):
    - n/a — English traditional tune, 16th-c. English sources, not first published in France.
    - note: under Cass. 27 Feb 2007 the 70-year term generally absorbs these extensions; record the conservative date anyway.
- If multiple authors (composer + librettist): use the LAST survivor's death year (EU joint-work rule).
    - n/a — traditional tune; optional lyric overlay "What Child Is This?" (William Chatterton Dix, 1865, d. 1898) is PD if ever used (candidates row 14) — see §6.

## 3. First publication → USA rule
- First publication year:            16th c. — registered 1580 (candidates row 14); earliest printed edition TO CONFIRM at ingest
- First publication source (IMSLP "First Publication" field / publisher archive / WorldCat): candidates-2026-08.md row 14 ("registered 1580"); Mutopia piece-info page id 1247
- USA status: published in **1930 or earlier** → PD in USA (even if URAA-eligible)   [x] yes [ ] no
    - Pre-20th-c. tune ✓ (candidates row 14).
- If published 1931–1963 (USA): renewal search done? (CCE renewal records)  [ ] n/a [ ] renewed → FLAG [ ] not renewed → PD
- Posthumous publication nuance: first publication year may differ from composition year — record BOTH.
    - n/a — anonymous 16th-c. traditional tune; no composition/publication split to record.

## 4. Edition provenance (the edition trap, §8.5.3)
- Source type:  [ ] IMSLP scan   [x] Mutopia typeset   [ ] other (archive.org / library scan)
- Work page URL: https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=1247
- **File/scan URL** (specific file page or PDF URL): https://www.ibiblio.org/mutopia/ftp/Traditional/greensleeves/greensleeves-let.pdf (PD piano typeset; candidates row 14)
- IMSLP file ID (e.g., `#02607`) / Mutopia ID (e.g., `Mutopia-2008/01/13-1247`): Mutopia id 1247 (full `Mutopia-YYYY/MM/DD-1247` identifier TO CONFIRM at ingest). **Mutopia id 1943 (Kastrup) is CC-BY-SA → REJECT per §8.5 tier 3** (candidates row 14).
- Edition:  publisher, year, plate no.: Mutopia PD typeset (engraver/year TO CONFIRM at ingest); IMSLP page (reference only): https://imslp.org/wiki/Greensleeves_(Folk_Songs,_English)
- Editor (if any) + editor death year:     n/a — Mutopia typeset tagged Public Domain (engraver TO CONFIRM at ingest)
- IMSLP/Mutopia license tag on the file:  [x] Public Domain   [ ] CC-BY   [ ] CC0   [ ] CC-BY-SA → REJECT (§8.5 tier 3)   [ ] Non-PD US / Non-PD EU → REJECT
    - Mutopia id 1943 (Kastrup) is CC-BY-SA → REJECT per §8.5 tier 3 (candidates row 14); use Mutopia id 1247 / ibiblio PDF (Public Domain) instead.
- Critical/urtext edition?  [x] no [ ] yes → confirm ≥25 years since publication (EU typographical right) or use an original scan instead
- Is this a modern *arrangement* by a living/recent arranger?  [x] no [ ] yes → REJECT as source (reference only)
    - IMSLP's Greensleeves pages are modern arrangements → reference only (candidates row 14); the Mutopia PD typesets are the source.

## 5. Territory flags
- Launch markets (fill in): US / EU (DE, FR, ES…) / UK / CA / AU / JP / BR / IN / …
- For each: rule applied and result (life+70 / pre-1931 / longer terms: ES 80, MX 100, CO 80…).
    - US: pre-20th-c. traditional tune → PD (pre-1931 rule)
    - EU (DE, FR, ES…): traditional → PD (anonymous 16th c., registered 1580)
    - UK / CA / AU: traditional → PD
    - JP: traditional/ancient work → PD
    - BR: traditional → PD; IN: traditional → PD
- Any jurisdiction still restricted?  [x] none   [ ] list + geo-gate decision:
- territory_flags_json (per §8.5.6 schema):
  ```json
  {
    "US":  {"rule": "pre-20th-c. traditional tune (pre-1931 rule)", "pd": true},
    "EU":  {"rule": "traditional (16th c., registered 1580)", "pd": true, "note": "anonymous — no life+70 arithmetic"},
    "UK":  {"rule": "traditional (16th c.)", "pd": true},
    "CA":  {"rule": "traditional (16th c.)", "pd": true},
    "AU":  {"rule": "traditional (16th c.)", "pd": true},
    "JP":  {"rule": "traditional/ancient work", "pd": true},
    "BR":  {"rule": "traditional", "pd": true},
    "IN":  {"rule": "traditional", "pd": true}
  }
  ```

## 6. Arrangement & lyrics
- Our arrangement is a NEW WORK derived from the PD source (§8.5.3):  [x] confirmed
- Any elements copied from a modern arrangement?  [x] none (required)
    - Mutopia id 1943 (Kastrup, CC-BY-SA) is rejected as a source (§8.5 tier 3); IMSLP Greensleeves pages are modern arrangements → reference only (candidates row 14).
- Lyrics used? lyricist + death year (e.g., Schiller 1805 / Newton 1807):  traditional "Greensleeves" text — traditional → PD; optional overlay "What Child Is This?" — William Chatterton Dix, 1865, d. 1898 → PD? [x] yes [ ] no (flag/omit) (candidates row 14: overlay "if ever needed")
    - The Dix overlay is not required for the tune; if ever used, the 1865 text is PD (Dix d. 1898).
- Audio: rendered from our own arrangement (§8.6), no third-party recordings:  [x] confirmed

## 7. Verdict
- [x] GLOBALLY PD        (rule that establishes it: 16th-c. traditional tune (registered 1580) — PD everywhere)
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