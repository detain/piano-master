package com.keyquest.scoring

/**
 * The hand a note is assigned to (SongPack v1 `hand` field: `L` | `R`).
 *
 * The scorer uses `hand` — never `staff` — for matching, per the SongPack v1
 * note-record contract (docs/specs/songpack-v1.md §3.2). Enum order R, L is
 * the SongPack canonical ordering rule (startBeat asc, then R before L, then
 * pitch asc), so `compareBy { it.hand }` sorts R before L automatically.
 */
enum class Hand { R, L }