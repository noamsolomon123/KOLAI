package ai.kolai.app

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider

// ---------------------------------------------------------------------------
//  Cover art  (web .cover / .cover__glyph / cover-wrap::before glow)
// ---------------------------------------------------------------------------

@Composable
internal fun CoverArtHero(palette: KolaiPalette, seed: String, playing: Boolean) {
    val shape = RoundedCornerShape(28.dp)
    val glowAlpha by animateFloatAsState(
        targetValue = if (playing) 0.6f else 0.32f,
        animationSpec = tween(600),
        label = "coverGlow",
    )
    // The screen column hands this slot ALL leftover vertical space (weight),
    // and the square cover sizes itself to the smaller of width/height -- so
    // the whole screen always fits with no scrolling, on any display.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        Box(modifier = Modifier.size(side), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(palette.glow.copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension * 0.62f,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize(0.9f)
                    .clip(shape)
                    .drawBehind { drawCover(palette) }
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Waveform(seed = seed, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Deterministic decorative waveform drawn from a seed string (the song title):
 * same song -> same wave, every render. Mirrored rounded bars around the
 * vertical center, like a track's amplitude view. STATIC by design (no
 * per-frame animation -- the lightweight perf rule); motion comes from the
 * hue/glow transitions around it.
 */
@Composable
private fun Waveform(seed: String, modifier: Modifier = Modifier) {
    val heights = remember(seed) { waveformHeights(kolaiHash(seed), bars = 44) }
    val color = Color.White.copy(alpha = 0.92f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val n = heights.size
        val span = w * 0.78f               // bars occupy the middle 78% width
        val slot = span / n
        val barW = slot * 0.55f
        val x0 = (w - span) / 2f
        val minHalf = h * 0.025f
        val maxHalf = h * 0.21f            // tallest bar = 42% of cover height
        for (i in 0 until n) {
            val half = minHalf + (maxHalf - minHalf) * heights[i]
            drawRoundRect(
                color = color,
                topLeft = Offset(x0 + slot * i + (slot - barW) / 2f, h / 2f - half),
                size = Size(barW, half * 2f),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/** LCG-derived pseudo-random bar heights in [0,1], neighbor-smoothed. */
private fun waveformHeights(seed: Int, bars: Int): FloatArray {
    var s = seed
    fun next(): Float {
        s = s * 1103515245 + 12345
        return ((s ushr 8) and 0xFFFF) / 65535f
    }
    val raw = FloatArray(bars) { next() }
    return FloatArray(bars) { i ->
        val a = raw[(i - 1 + bars) % bars]
        val b = raw[i]
        val c = raw[(i + 1) % bars]
        (a + b * 2f + c) / 4f
    }
}

/** Layered radial + conic gradient artwork, ported from web CoverArt.tsx. */
private fun DrawScope.drawCover(p: KolaiPalette) {
    val w = size.width
    val h = size.height
    val maxDim = maxOf(w, h)
    // deep, saturated base field — reads rich (not pastel) on the dark canvas
    drawRect(
        Brush.sweepGradient(
            colors = listOf(
                hsl(p.hueA, 0.85f, 0.26f),
                hsl(p.hueB, 0.85f, 0.22f),
                hsl(p.hueC, 0.85f, 0.24f),
                hsl(p.hueA, 0.85f, 0.26f),
            ),
            center = Offset(w / 2f, h / 2f),
        ),
    )
    // saturated color pools (richer than the palette's mesh tones)
    drawRect(
        Brush.radialGradient(
            colors = listOf(hsl(p.hueA, 0.92f, 0.50f), Color.Transparent),
            center = Offset(w * 0.18f, h * 0.12f),
            radius = maxDim * 0.72f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(hsl(p.hueB, 0.90f, 0.52f), Color.Transparent),
            center = Offset(w * 0.85f, h * 0.25f),
            radius = maxDim * 0.66f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(hsl(p.hueC, 0.88f, 0.50f), Color.Transparent),
            center = Offset(w * 0.60f, h * 1.02f),
            radius = maxDim * 0.82f,
        ),
    )
    // restrained top-left sheen (web sheen is screen-blended, not src-over)
    drawRect(
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.03f),
                Color.Transparent,
            ),
            start = Offset(0f, 0f),
            end = Offset(w * 0.5f, h * 0.5f),
        ),
    )
    // inner edge shade (web .cover__ring: inset 0 0 70px rgba(0,0,0,0.32))
    drawRect(
        Brush.radialGradient(
            colorStops = arrayOf(
                0.62f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.34f),
            ),
            center = Offset(w / 2f, h / 2f),
            radius = maxDim * 0.78f,
        ),
    )
}

// ---------------------------------------------------------------------------
//  Track meta  (web .meta__title / .meta__artist)
// ---------------------------------------------------------------------------

@Composable
internal fun MetaText(title: String, artist: String?, error: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = if (error) KolaiColors.Live else KolaiColors.Text,
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!artist.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = artist,
                color = KolaiColors.TextDim,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun TuningMeta() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "מתחבר לתחנה…",
            color = KolaiColors.Text,
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "מתכוונן לשידור החי",
            color = KolaiColors.TextDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
//  DJ "ON AIR" card  (web .djchip)
// ---------------------------------------------------------------------------

@Composable
internal fun DjOnAirCard(palette: KolaiPalette, active: Boolean, beat: String?) {
    val shape = RoundedCornerShape(KolaiRadii.MD.dp)
    val icon = if (active) (BEAT_ICONS[beat] ?: "🎙️") else "🎙️"
    val bodyText = if (active) (BEAT_LABELS[beat] ?: "שידור חי") else "מתנגן עכשיו"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(shape = shape, shadowElevation = 10)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(hsl(ACCENT_HUE_A, 0.70f, 0.55f, 0.22f))
                .border(1.dp, hsl(ACCENT_HUE_A, 0.80f, 0.70f, 0.40f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = icon, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ON AIR",
                color = hsl(ACCENT_HUE_B, 0.80f, 0.80f),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = bodyText,
                color = if (active) KolaiColors.Text else KolaiColors.TextFaint,
                fontSize = 14.5.sp,
                fontStyle = if (active) FontStyle.Normal else FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!active) {
            Spacer(Modifier.width(10.dp))
            Equalizer()
        }
    }
}

@Composable
private fun Equalizer() {
    val inf = rememberInfiniteTransition(label = "eq")
    val color = hsl(ACCENT_HUE_B, 0.85f, 0.74f)
    val durations = intArrayOf(620, 760, 540, 700)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        modifier = Modifier.height(16.dp),
    ) {
        durations.forEach { d ->
            val spec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(d), RepeatMode.Reverse)
            val frac by inf.animateFloat(0.25f, 1f, spec, label = "bar")
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((16 * frac).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  Transport  (web .transport / .tbtn / .tbtn--play)
// ---------------------------------------------------------------------------

@Composable
internal fun TransportRow(
    status: StationStatus,
    onPrimary: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val playing = status == StationStatus.PLAYING
    val busy = status == StationStatus.TUNING || status == StationStatus.READY
    val canSkip = status == StationStatus.PLAYING || status == StationStatus.PAUSED

    // Transport is physically LTR (back-left, forward-right, play-center) even
    // though the rest of the UI is RTL -- matches the web layout + the screenshot.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportCircle(enabled = canSkip, onClick = onPrev) { SkipIcon(forward = false) }
            Spacer(Modifier.width(26.dp))
            PlayPauseButton(playing = playing, busy = busy, onClick = onPrimary)
            Spacer(Modifier.width(26.dp))
            TransportCircle(enabled = canSkip, onClick = onNext) { SkipIcon(forward = true) }
        }
    }
}

@Composable
private fun TransportCircle(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(KolaiColors.GlassStrong)
            .border(1.dp, KolaiColors.GlassBorderSoft, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SkipIcon(forward: Boolean) {
    val c = KolaiColors.Text
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val bar = w * 0.10f
        if (forward) {
            val tri = Path().apply {
                moveTo(w * 0.12f, h * 0.22f)
                lineTo(w * 0.12f, h * 0.78f)
                lineTo(w * 0.62f, h * 0.50f)
                close()
            }
            drawPath(tri, c)
            drawRect(c, topLeft = Offset(w * 0.70f, h * 0.22f), size = Size(bar, h * 0.56f))
        } else {
            drawRect(c, topLeft = Offset(w * 0.20f, h * 0.22f), size = Size(bar, h * 0.56f))
            val tri = Path().apply {
                moveTo(w * 0.88f, h * 0.22f)
                lineTo(w * 0.88f, h * 0.78f)
                lineTo(w * 0.38f, h * 0.50f)
                close()
            }
            drawPath(tri, c)
        }
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, busy: Boolean, onClick: () -> Unit) {
    val glowAlpha by animateFloatAsState(
        targetValue = if (playing) 0.7f else 0.4f,
        animationSpec = tween(500),
        label = "playGlow",
    )
    val haloSize by animateDpAsState(if (playing) 112.dp else 92.dp, tween(500), label = "halo")

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(haloSize)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(
                                KolaiColors.Green.copy(alpha = glowAlpha),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f,
                        ),
                    )
                },
        )
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(KolaiColors.Green, KolaiColors.GreenDeep)))
                .border(1.dp, Color.White.copy(alpha = 0.40f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = KolaiColors.OnGreen,
                    strokeWidth = 3.dp,
                )
                playing -> PauseGlyph()
                else -> PlayGlyph()
            }
        }
    }
}

@Composable
private fun PlayGlyph() {
    Canvas(modifier = Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val tri = Path().apply {
            moveTo(w * 0.24f, h * 0.18f)
            lineTo(w * 0.24f, h * 0.82f)
            lineTo(w * 0.82f, h * 0.50f)
            close()
        }
        drawPath(tri, KolaiColors.OnGreen)
    }
}

@Composable
private fun PauseGlyph() {
    Canvas(modifier = Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val barW = w * 0.22f
        val r = CornerRadius(barW * 0.35f, barW * 0.35f)
        drawRoundRect(KolaiColors.OnGreen, topLeft = Offset(w * 0.22f, h * 0.16f), size = Size(barW, h * 0.68f), cornerRadius = r)
        drawRoundRect(KolaiColors.OnGreen, topLeft = Offset(w * 0.56f, h * 0.16f), size = Size(barW, h * 0.68f), cornerRadius = r)
    }
}