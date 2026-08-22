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
- song_id:        (e.g., `ode-to-joy-l1`)
- title (canonical):
- difficulty tier (L1/L2/L3):
- catalog tier (§8.5):  [ ] public-domain   [ ] commissioned   [ ] CC0/CC-BY
- recorded_by / date:

## 1. Identify the work
- Composer / attribution:            (include *both* names for disputed attributions, e.g., "Christian Petzold, formerly attrib. J.S. Bach (BWV Anh. 114)")
- Catalogue no. (BWV / K. / WoO / Op. / P. / H.):
- Composition year:
- Source for composition year:       (URL or scan citation)

## 2. Composer death year → EU life+70 check
- Composer death year:               (source for the death year, not just the number)
- EU PD date: 1 Jan (death_year + 71) =  ______   →  PD in EU: [ ] yes (if ≤ current year) [ ] no → FLAG
- Spain 80-year check: death_year + 81 ≤ current year?   [ ] yes [ ] no (flag if no)
- Mexico life+100 check: death_year + 101 ≤ current year? [ ] yes [ ] no (flag if no)
- France war-extension check (French composers / works first published in France):
    - work published ≤ 1920? (+6y152d) / ≤ 1947? (+8y120d) → conservative EU PD date: ______
    - note: under Cass. 27 Feb 2007 the 70-year term generally absorbs these extensions; record the conservative date anyway.
- If multiple authors (composer + librettist): use the LAST survivor's death year (EU joint-work rule).

## 3. First publication → USA rule
- First publication year:            (edition + publisher + city)
- First publication source (IMSLP "First Publication" field / publisher archive / WorldCat):
- USA status: published in **1930 or earlier** → PD in USA (even if URAA-eligible)   [ ] yes [ ] no
- If published 1931–1963 (USA): renewal search done? (CCE renewal records)  [ ] n/a [ ] renewed → FLAG [ ] not renewed → PD
- Posthumous publication nuance: first publication year may differ from composition year — record BOTH.

## 4. Edition provenance (the edition trap, §8.5.3)
- Source type:  [ ] IMSLP scan   [ ] Mutopia typeset   [ ] other (archive.org / library scan)
- Work page URL:
- **File/scan URL** (specific file page or PDF URL):
- IMSLP file ID (e.g., `#02607`) / Mutopia ID (e.g., `Mutopia-2008/01/13-1247`):
- Edition:  publisher, year, plate no.:
- Editor (if any) + editor death year:     (editor must be PD: died ≤1955 for EU, or edition published ≤1930 for USA)
- IMSLP/Mutopia license tag on the file:  [ ] Public Domain   [ ] CC-BY   [ ] CC0   [ ] CC-BY-SA → REJECT (§8.5 tier 3)   [ ] Non-PD US / Non-PD EU → REJECT
- Critical/urtext edition?  [ ] no [ ] yes → confirm ≥25 years since publication (EU typographical right) or use an original scan instead
- Is this a modern *arrangement* by a living/recent arranger?  [ ] no [ ] yes → REJECT as source (reference only)

## 5. Territory flags
- Launch markets (fill in): US / EU (DE, FR, ES…) / UK / CA / AU / JP / BR / IN / …
- For each: rule applied and result (life+70 / pre-1931 / longer terms: ES 80, MX 100, CO 80…).
- Any jurisdiction still restricted?  [ ] none   [ ] list + geo-gate decision:
- territory_flags_json (per §8.5.6 schema):

## 6. Arrangement & lyrics
- Our arrangement is a NEW WORK derived from the PD source (§8.5.3):  [ ] confirmed
- Any elements copied from a modern arrangement?  [ ] none (required)
- Lyrics used? lyricist + death year (e.g., Schiller 1805 / Newton 1807):  → PD? [ ] yes [ ] no (flag/omit)
- Audio: rendered from our own arrangement (§8.6), no third-party recordings:  [ ] confirmed

## 7. Verdict
- [ ] GLOBALLY PD        (rule that establishes it: _____________)
- [ ] CLEARED-WITH-NOTE  (note: _____________)
- [ ] FLAG               (reason + exclusion or geo-gate decision)
- cleared_globally: true/false  (§8.5.6 — false forces exclusion or recorded geo-gate)

## 8. Review & sign-off
- First-person review (researcher): name / date / notes
- Second-person review (required for any FLAG or CLEARED-WITH-NOTE): name / date
- **Legal sign-off (P0.8.4):** name / date / firm — required before publish
- Source links archived?  [ ] yes (print/screenshot/PDF of the work page + file page)

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