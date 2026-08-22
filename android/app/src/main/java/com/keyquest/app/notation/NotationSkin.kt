package com.keyquest.app.notation

import androidx.compose.ui.graphics.Color

/**
 * The two notation skins the prototype renders from the same score data
 * (plan §7.2): beginner note-bars and traditional grand staff.
 *
 * Skins are stateless markers: all per-note geometry lives in the pre-laid-out
 * [NoteLayoutSet], so switching skins only rebuilds layout (off the main
 * thread) and changes which draw path the Canvas takes — the notes are the
 * same objects either way.
 */
sealed interface NotationSkin {

    /**
     * Beginner note-bar mode: colored rounded bars on a 5-lane-per-hand grid,
     * note letter inside the bar, length = duration (plan §7.2).
     */
    data object NoteBar : NotationSkin {
        /** Feedback-state colors for P1.6 (hit/miss/neutral); neutral only today. */
        val neutralLeft = Color(0xFF4C6EF5) // left-hand lanes
        val neutralRight = Color(0xFFF08C00) // right-hand lanes
        val hit = Color(0xFF2F9E44)
        val miss = Color(0xFFE03131)
    }

    /**
     * Staff mode: grand staff with clefs, key/time signatures, accidentals,
     * beams and ties — the scrolling subset of real engraving (plan §7.2).
     */
    data object Staff : NotationSkin {
        val lineColor = Color(0xFF212529)
        val noteColor = Color(0xFF1B1B1B)
        val accidentalColor = Color(0xFF1B1B1B)
    }
}