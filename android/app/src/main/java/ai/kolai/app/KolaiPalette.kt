package ai.kolai.app

import androidx.compose.ui.graphics.Color

/**
 * Track-derived color palette — a direct Kotlin port of the web app's
 * `paletteFor` (frontend/src/lib/util.ts). The same track title yields the
 * same hues, so the mesh background + cover art stay in lockstep with the web.
 */

/** Deterministic 32-bit FNV-1a-ish hash, identical to the web `hashString`. */
fun kolaiHash(str: String): Int {
    var h = 2166136261L // 0x811C9DC5 as unsigned
    val s = str.ifEmpty { "radio" }
    for (c in s) {
        h = h xor c.code.toLong()
        h = (h * 16777619L) and 0xFFFFFFFFL // imul mod 2^32
    }
    return (h and 0xFFFFFFFFL).toInt()
}

/** Hues + ready-to-use Colors derived from a track title (mirrors web Palette). */
data class KolaiPalette(
    val hueA: Float,
    val hueB: Float,
    val hueC: Float,
) {
    // a: hsl(hueA 85% 62%) ; b: hsl(hueB 80% 58%) ; c: hsl(hueC 78% 55%)
    val a: Color get() = hsl(hueA, 0.85f, 0.62f)
    val b: Color get() = hsl(hueB, 0.80f, 0.58f)
    val c: Color get() = hsl(hueC, 0.78f, 0.55f)
    // soft glow color: hsl(hueA 90% 60%)
    val glow: Color get() = hsl(hueA, 0.90f, 0.60f)
}

fun paletteFor(title: String): KolaiPalette {
    // unsigned 32-bit view of the hash
    val h = kolaiHash(title).toLong() and 0xFFFFFFFFL
    val hueA = (h % 360L).toFloat()
    val hueB = (((h % 360L) + 40L + ((h shr 8) % 90L)) % 360L).toFloat()
    val hueC = (((h % 360L) + 200L + ((h shr 16) % 60L)) % 360L).toFloat()
    return KolaiPalette(hueA, hueB, hueC)
}

/**
 * HSL -> Color (matches CSS hsl()). Compose only ships Color.hsv, and the web
 * design is authored entirely in HSL, so we convert here to keep colors exact.
 * h in [0,360), s/l in [0,1].
 */
fun hsl(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = (h % 360f) / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha,
    )
}