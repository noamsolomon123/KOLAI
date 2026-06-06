package ai.kolai.app

import androidx.compose.ui.graphics.Color

/**
 * KOLAI design tokens — ported from the web "liquid-glass" design system
 * (frontend/src/styles/global.css :root vars). Midnight glass canvas,
 * low-alpha white frosted panels, Spotify-green transport, track-derived
 * hue palette. Kept as plain constants so the UI stays lightweight.
 */
object KolaiColors {
    // base canvas (--bg / --bg-2)
    val Bg = Color(0xFF06060D)
    val Bg2 = Color(0xFF0B0A16)

    // glass surfaces (--glass / --glass-2 / --glass-strong)
    val Glass = Color.White.copy(alpha = 0.055f)
    val Glass2 = Color.White.copy(alpha = 0.028f)
    val GlassStrong = Color.White.copy(alpha = 0.10f)

    // glass borders (--glass-border / --glass-border-soft)
    val GlassBorder = Color.White.copy(alpha = 0.14f)
    val GlassBorderSoft = Color.White.copy(alpha = 0.08f)
    val Hairline = Color.White.copy(alpha = 0.06f)

    // top specular highlight on glass (--glass ::before)
    val Specular = Color.White.copy(alpha = 0.16f)
    val BorderHi = Color.White.copy(alpha = 0.32f)

    // text (--text / --text-dim / --text-faint)
    val Text = Color.White.copy(alpha = 0.96f)
    val TextDim = Color.White.copy(alpha = 0.60f)
    val TextFaint = Color.White.copy(alpha = 0.38f)

    // live / on-air accent (--live)
    val Live = Color(0xFFFF5A6E)
    val LiveTint = Color(0xFFFF5A6E).copy(alpha = 0.12f)
    val LiveBorder = Color(0xFFFF5A6E).copy(alpha = 0.32f)
    val LiveText = Color(0xFFFFD2D8)

    // transport accent — Spotify green (--green / --green-deep)
    val Green = Color(0xFF1ED760)
    val GreenDeep = Color(0xFF14B150)
    val OnGreen = Color(0xFF04130A)
}

/** Corner radii (--r-lg / --r-md / --r-sm / --r-pill). */
object KolaiRadii {
    const val LG = 32f
    const val MD = 22f
    const val SM = 16f
    const val PILL = 999f
}