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
import ai.kolai.app.wiring.CoverArt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.PI
import kotlin.math.sin

// ---------------------------------------------------------------------------
//  Cover art  (web .cover / .cover__glyph / cover-wrap::before glow)
// ---------------------------------------------------------------------------

@Composable
internal fun CoverArtHero(
    palette: KolaiPalette,
    seed: String,
    playing: Boolean,
    title: String? = null,
    artist: String? = null,
) {
    val shape = RoundedCornerShape(28.dp)
    // Real album cover for the current song (Deezer, free/keyless lookup).
    // State only flips AFTER a lookup completes, so on a song change the
    // previous cover stays up until the new one (or a null miss) arrives.
    var coverUrl by remember { mutableStateOf<String?>(null) }
    var prevCoverUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(title, artist) {
        if (title.isNullOrBlank()) {
            prevCoverUrl = null
            coverUrl = null
        } else {
            val url = CoverArt.coverUrl(artist.orEmpty(), title)
            prevCoverUrl = coverUrl
            coverUrl = url
        }
    }
    val glowAlpha by animateFloatAsState(
        targetValue = if (playing) 0.6f else 0.32f,
        animationSpec = tween(600),
        label = "coverGlow",
    )
    // Ambient glow eases toward the mood-tinted track hue -- no palette
    // extraction, just the cheap radial gradient recolored slowly.
    val glowColor by animateColorAsState(palette.glow, tween(1800), label = "coverGlowColor")
    // Entrance: a small scale+fade every time the song changes. One-shot
    // animation on state change, nothing runs while a song plays.
    val entrance = remember { Animatable(1f) }
    LaunchedEffect(title) {
        if (!title.isNullOrBlank()) {
            entrance.snapTo(0f)
            entrance.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }
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
                        colors = listOf(glowColor.copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension * 0.62f,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize(0.9f)
                    .graphicsLayer {
                        val e = entrance.value
                        val sc = 0.96f + 0.04f * e
                        scaleX = sc
                        scaleY = sc
                        alpha = 0.5f + 0.5f * e
                    }
                    .clip(shape)
                    .drawBehind { drawCover(palette) }
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Waveform(seed = seed, modifier = Modifier.fillMaxSize())
                // Real cover, crossfaded over the waveform once a URL is known.
                // The waveform stays composed behind it and doubles as the live
                // placeholder while a (new) image loads; Coil's own crossfade +
                // the previous image as memory-cache placeholder keep song-to-
                // song transitions smooth.
                Crossfade(
                    targetState = coverUrl != null,
                    animationSpec = tween(500),
                    label = "coverSwap",
                ) { hasCover ->
                    if (hasCover) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(coverUrl)
                                .crossfade(true)
                                .placeholderMemoryCacheKey(prevCoverUrl)
                                .build(),
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Deterministic decorative waveform drawn from a seed string (the song title):
 * same song -> same wave, every render. Mirrored rounded bars around the
 * vertical center, like a track's amplitude view. A single slow infinite
 * phase gently breathes the bar heights (one transition, no per-frame
 * allocation -- the lightweight perf rule) so the fallback never looks dead.
 */
@Composable
private fun Waveform(seed: String, modifier: Modifier = Modifier) {
    val heights = remember(seed) { waveformHeights(kolaiHash(seed), bars = 44) }
    val color = Color.White.copy(alpha = 0.92f)
    val wave = rememberInfiniteTransition(label = "wave")
    val phase by wave.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "wavePhase",
    )
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
            // subtle breath: each bar sways +/-15% on a slow traveling sine
            val sway = 1f + 0.15f * sin(phase + i * 0.45f)
            val half = (minHalf + (maxHalf - minHalf) * heights[i]) * sway
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
        // Single line + marquee: long Hebrew/English titles glide instead of
        // clipping (the marquee only animates when text actually overflows;
        // short titles stay static and centered by the column).
        Text(
            text = title,
            color = if (error) KolaiColors.LiveText else KolaiColors.Text,
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 29.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
        )
        if (!artist.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = artist,
                // error detail reads calm: faint + small, never alarming red
                color = if (error) KolaiColors.TextFaint else KolaiColors.TextDim,
                fontSize = if (error) 12.5.sp else 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "מתכוונן לשידור החי",
                color = KolaiColors.TextDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(8.dp))
            BreathingDots()
        }
    }
}

/**
 * Three softly breathing dots for the tuning state -- a traveling sine over a
 * single infinite float; the phase is read only inside the draw lambda, so
 * each frame is a redraw, never a recomposition.
 */
@Composable
private fun BreathingDots() {
    val inf = rememberInfiniteTransition(label = "tuningDots")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "dotPhase",
    )
    val color = KolaiColors.TextDim
    Canvas(modifier = Modifier.width(26.dp).height(8.dp)) {
        val r = size.height * 0.32f
        val step = size.width / 3f
        for (i in 0 until 3) {
            val t = sin(2f * PI.toFloat() * (phase - i * 0.18f))
            val a = 0.25f + 0.55f * (0.5f + 0.5f * t)
            drawCircle(
                color = color.copy(alpha = a),
                radius = r,
                center = Offset(step * (i + 0.5f), size.height / 2f),
            )
        }
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

    // Broadcast feel: the chip leans live-red while the DJ is actually
    // speaking and relaxes back to the cool accent between links -- colors
    // crossfade on the state change instead of snapping.
    val tileBg by animateColorAsState(
        targetValue = if (active) KolaiColors.LiveTint else hsl(ACCENT_HUE_A, 0.70f, 0.55f, 0.22f),
        animationSpec = tween(600),
        label = "djTileBg",
    )
    val tileBorder by animateColorAsState(
        targetValue = if (active) KolaiColors.LiveBorder else hsl(ACCENT_HUE_A, 0.80f, 0.70f, 0.40f),
        animationSpec = tween(600),
        label = "djTileBorder",
    )
    val onAirColor by animateColorAsState(
        targetValue = if (active) KolaiColors.LiveText else hsl(ACCENT_HUE_B, 0.80f, 0.80f),
        animationSpec = tween(600),
        label = "djOnAirColor",
    )

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
                .background(tileBg)
                .border(1.dp, tileBorder, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = icon, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = active,
                    enter = fadeIn(tween(400)) + expandHorizontally(tween(400)),
                    exit = fadeOut(tween(300)) + shrinkHorizontally(tween(300)),
                ) { OnAirDot() }
                Text(
                    text = "ON AIR",
                    color = onAirColor,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                )
            }
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
        AnimatedVisibility(
            visible = !active,
            enter = fadeIn(tween(450)) + expandHorizontally(tween(450)),
            exit = fadeOut(tween(300)) + shrinkHorizontally(tween(300)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(10.dp))
                Equalizer()
            }
        }
    }
}

/** Gently pulsing live dot -- only composed while the DJ is on air. */
@Composable
private fun OnAirDot() {
    val inf = rememberInfiniteTransition(label = "onAir")
    val pulse by inf.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "onAirPulse",
    )
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(7.dp)
            .graphicsLayer { alpha = pulse }
            .clip(CircleShape)
            .background(KolaiColors.Live),
    )
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
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(56.dp)
            .pressScale(interaction)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(KolaiColors.GlassStrong)
            .border(1.dp, KolaiColors.GlassBorderSoft, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
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
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(78.dp)
                .pressScale(interaction, pressedScale = 0.92f)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(KolaiColors.Green, KolaiColors.GreenDeep)))
                .border(1.dp, Color.White.copy(alpha = 0.40f), CircleShape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
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