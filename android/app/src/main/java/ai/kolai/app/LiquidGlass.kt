package ai.kolai.app

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Lightweight liquid-glass primitives for KOLAI. Reproduces the web look
 * (frontend/src/styles/global.css) using only translucency + gradient + thin
 * border + soft shadow — NO real-time backdrop blur (the explicit perf rule).
 */

/**
 * The living gradient-mesh background, ported from the web `.mesh`.
 *
 * The web draws 4 big blurred radial blobs (hsl from --hue-*) over a midnight
 * canvas, blended like "screen", slowly drifting. To keep this 60fps-friendly
 * and idle-cheap we:
 *  - draw the blobs as static radial Brushes (drawBehind, no per-frame blur),
 *  - animate only the HUE, SLOWLY, and only when the track changes
 *    (animateFloatAsState with a multi-second tween) — not a continuous loop.
 *
 * Brightness calibration: on the web each blob renders at
 * (gradient alpha x 0.6 element opacity) with mix-blend-mode:screen over
 * #06060d, and the `.grain` overlay then crushes it with a rgba(0,0,0,0.6)
 * vignette + top scrim. Net effect: a near-black canvas with soft edge glows.
 * We bake that down here as low blob alphas + a global black scrim + a strong
 * edge vignette (naive src-over alphas at the CSS values read FAR too bright).
 */
@Composable
fun MeshBackground(
    palette: KolaiPalette,
    modifier: Modifier = Modifier,
) {
    // Ease each hue toward the new track/mood over ~2.4s. Idle = no animation
    // frames -- these only run on a state change, never continuously.
    val hueA by animateFloatAsState(palette.hueA, tween(2400), label = "hueA")
    val hueB by animateFloatAsState(palette.hueB, tween(2400), label = "hueB")
    val hueC by animateFloatAsState(palette.hueC, tween(2400), label = "hueC")
    // Mood saturation drifts even slower, so a chip tap feels like the room
    // light changing, not a repaint.
    val sat by animateFloatAsState(palette.sat, tween(3200), label = "meshSat")

    val a = hsl(hueA, (0.92f * sat).coerceIn(0f, 1f), 0.60f, 0.44f)
    val b = hsl(hueB, (0.90f * sat).coerceIn(0f, 1f), 0.58f, 0.40f)
    val c = hsl(hueC, (0.88f * sat).coerceIn(0f, 1f), 0.55f, 0.36f)
    val a2 = hsl(hueA, (0.80f * sat).coerceIn(0f, 1f), 0.64f, 0.26f)

    Box(
        modifier
            .fillMaxSize()
            .background(KolaiColors.Bg)
            .drawBehind {
                val w = size.width
                val h = size.height
                val dim = maxOf(w, h)
                // Soft radial blobs. Radii sized like the web circles
                // (62/56/54/40 vmax + 64px blur) — deliberately NOT
                // full-screen, so the screen edges stay near-black.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(a, Color.Transparent),
                        center = Offset(w * 0.06f, h * 0.10f),
                        radius = dim * 0.52f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(b, Color.Transparent),
                        center = Offset(w * 1.00f, h * 0.30f),
                        radius = dim * 0.46f,
                    ),
                )
                // hue-c glow sits LOWER-RIGHT (faint + warm in the idle
                // palette), the echo of hue-a fades in bottom-left.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(c, Color.Transparent),
                        center = Offset(w * 0.84f, h * 0.92f),
                        radius = dim * 0.42f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(a2, Color.Transparent),
                        center = Offset(w * 0.12f, h * 1.00f),
                        radius = dim * 0.34f,
                    ),
                )
                // Global darkening — stands in for screen-blend math over
                // near-black; keeps the whole field midnight-dark.
                drawRect(Color.Black.copy(alpha = 0.32f))
                // Edge vignette (web .grain): ellipse anchored above top
                // center; bottom + side edges fall to deep black.
                drawRect(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.38f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.72f),
                        ),
                        center = Offset(w * 0.5f, h * -0.05f),
                        radius = dim * 1.12f,
                    ),
                )
                // top scrim for status-bar legibility (web .grain linear part)
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent),
                        startY = 0f,
                        endY = h * 0.26f,
                    ),
                )
            },
    )
}

/**
 * Frosted-glass surface modifier — the web `.glass` primitive, cheaply.
 * soft shadow + translucent fill + thin light border + a faint top specular
 * highlight (drawn as a gradient, not a blur).
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(KolaiRadii.LG.dp),
    fill: Color = KolaiColors.Glass,
    borderColor: Color = KolaiColors.GlassBorder,
    shadowElevation: Int = 18,
): Modifier = this
    .shadow(shadowElevation.dp, shape, clip = false)
    .clip(shape)
    .background(
        Brush.verticalGradient(
            colors = listOf(fill, KolaiColors.Glass2),
        ),
    )
    // top specular highlight sweep (web .glass::before), fades out by ~30%
    .drawBehind {
        drawRect(
            Brush.verticalGradient(
                colors = listOf(KolaiColors.Specular, Color.Transparent),
                startY = 0f,
                endY = size.height * 0.30f,
            ),
        )
    }
    .border(BorderStroke(1.dp, borderColor), shape)

/**
 * Springy press feedback for tappable glass elements. Pass the SAME
 * interaction source to `clickable(interactionSource = ...)`. Cheap: a single
 * spring that only runs on press/release; scale applies in the draw layer
 * (graphicsLayer), so no relayout per frame.
 */
@Composable
fun Modifier.pressScale(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.94f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}