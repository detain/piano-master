package com.keyquest.scoring

/**
 * Per-note verdict (plan §6, §20 P1.5.3), mapped to the feedback colors of
 * plan §7.3:
 *
 *  - [PERFECT] — hit, |deviation| <= perfectBand: green fill + pop.
 *  - [GOOD] — hit, |deviation| within the window but beyond the perfect band:
 *    green fill (dimmer).
 *  - [MISSED] — nothing played in the window: red outline as the playhead
 *    passes the note.
 *  - [WRONG] — wrong-pitch event(s) inside the window ("miss with hint",
 *    plan §6): red key flash on the played key. The wrong-pitch event itself
 *    is NOT consumed (see Matcher) and shows up in the report's extra events.
 */
enum class Verdict { PERFECT, GOOD, MISSED, WRONG }