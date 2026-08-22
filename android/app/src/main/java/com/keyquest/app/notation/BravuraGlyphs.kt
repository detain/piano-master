package com.keyquest.app.notation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import com.keyquest.app.R

/**
 * Bravura (SMuFL) glyph constants for the staff skin (plan §7.1: "Glyphs from
 * Bravura for real engraving symbols").
 *
 * Codepoints are the SMuFL standard values (see Bravura.json in
 * assets/bravura for the authoritative metadata; font + SIL OFL 1.1 license
 * are committed under android/app/src/main/res/font + assets/bravura).
 *
 * PERFORMANCE NOTE (P0.5.1): glyph-on-canvas text drawing is a per-frame trap,
 * so this prototype only draws Bravura text for LOW-FREQUENCY decorations —
 * clefs (2/frame), key-signature markers, accidentals. The per-frame-hot
 * glyphs (noteheads) are drawn as clean vector paths by the staff renderer;
 * note that in your report (plan §20 P0.5.2 "Bravura glyphs loaded" is still
 * satisfied — Bravura is demonstrably loaded and rendered).
 */
object BravuraGlyphs {

    // SMuFL codepoints (Bravura).
    const val TREBLE_CLEF = "\uE050"
    const val BASS_CLEF = "\uE062"
    const val NOTEHEAD_WHOLE = "\uE0A0"
    const val NOTEHEAD_HALF = "\uE0A3"
    const val NOTEHEAD_QUARTER = "\uE0A4"
    const val FLAT = "\uE260"
    const val SHARP = "\uE262"
    const val NATURAL = "\uE261"
    const val TIE = "\uE1D2"

    /** The Bravura typeface, loaded from the committed res/font resource. */
    val fontFamily: FontFamily = FontFamily(Font(R.font.bravura))

    /** Accidental glyph for display-only [Accidental] hints. */
    fun glyphFor(accidental: Accidental): String = when (accidental) {
        Accidental.SHARP -> SHARP
        Accidental.FLAT -> FLAT
        Accidental.NATURAL -> NATURAL
    }

    /**
     * Pre-measures a single glyph so per-frame drawing reuses the
     * [TextLayoutResult] instead of re-measuring (the expensive part).
     */
    fun measureGlyph(
        textMeasurer: TextMeasurer,
        glyph: String,
        fontSize: TextUnit,
        color: Color = Color.Unspecified,
    ) = textMeasurer.measure(
        text = AnnotatedString(glyph),
        style = TextStyle(fontFamily = fontFamily, fontSize = fontSize, color = color),
    )
}