package ai.kolai.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
//  Station row  (web .station / .wordmark / .live / .ghost-btn)
// ---------------------------------------------------------------------------

@Composable
internal fun StationRow(palette: KolaiPalette) {
    var settingsOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(shape = RoundedCornerShape(KolaiRadii.PILL.dp), shadowElevation = 10)
            .padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = WORDMARK,
                style = TextStyle(
                    brush = Brush.verticalGradient(
                        listOf(Color.White, hsl(palette.hueB, 0.80f, 0.82f)),
                    ),
                ),
                fontFamily = Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
            )
            Spacer(Modifier.width(10.dp))
            LivePill()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton(text = "🔁 תחנה חדשה", onClick = { KolaiMood.requestRetune() })
            Spacer(Modifier.width(8.dp))
            GhostIconButton(glyph = "⚙️", onClick = { settingsOpen = true })
        }
    }

    if (settingsOpen) SettingsSheet(onClose = { settingsOpen = false })
}

@Composable
private fun LivePill() {
    val inf = rememberInfiniteTransition(label = "live")
    val dotAlpha by inf.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dot",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(KolaiRadii.PILL.dp))
            .background(KolaiColors.LiveTint)
            .border(1.dp, KolaiColors.LiveBorder, RoundedCornerShape(KolaiRadii.PILL.dp))
            .padding(start = 9.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(KolaiColors.Live),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "LIVE",
            color = KolaiColors.LiveText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(KolaiRadii.PILL.dp))
            .background(KolaiColors.GlassStrong)
            .border(1.dp, KolaiColors.GlassBorderSoft, RoundedCornerShape(KolaiRadii.PILL.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = KolaiColors.TextDim, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GhostIconButton(glyph: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(KolaiRadii.PILL.dp))
            .background(KolaiColors.GlassStrong)
            .border(1.dp, KolaiColors.GlassBorderSoft, RoundedCornerShape(KolaiRadii.PILL.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = 16.sp)
    }
}

// ---------------------------------------------------------------------------
//  Mood bar  (web .moodbar / .mood-chip)
// ---------------------------------------------------------------------------

@Composable
internal fun MoodBar() {
    // Single source of truth: the chips render whatever KolaiMood holds (the
    // service + planner read the same flow), so UI and engine can never drift.
    val active by KolaiMood.mood.collectAsState()
    val context = LocalContext.current
    var toastTick by remember { mutableIntStateOf(0) }
    var toastVisible by remember { mutableStateOf(false) }

    LaunchedEffect(toastTick) {
        if (toastTick > 0) {
            toastVisible = true
            kotlinx.coroutines.delay(2400)
            toastVisible = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(KolaiRadii.PILL.dp), shadowElevation = 8)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MOODS.forEach { mood ->
                MoodChip(
                    mood = mood,
                    selected = active == mood.key,
                    onClick = {
                        if (active != mood.key) {
                            KolaiMood.setAndPersist(context, mood.key)
                            toastTick += 1
                        }
                    },
                )
            }
        }
        if (toastVisible) {
            Text(
                text = "המצב יתחלף בשירים הבאים",
                color = KolaiColors.TextFaint,
                fontSize = 11.5.sp,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

@Composable
private fun MoodChip(mood: Mood, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(KolaiRadii.PILL.dp)
    val interaction = remember { MutableInteractionSource() }
    val base = Modifier
        .pressScale(interaction, pressedScale = 0.93f)
        .clip(shape)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    val styled = if (selected) {
        base
            .background(
                Brush.linearGradient(
                    listOf(
                        hsl(ACCENT_HUE_A, 0.72f, 0.52f, 0.55f),
                        hsl(ACCENT_HUE_B, 0.68f, 0.56f, 0.40f),
                    ),
                ),
            )
            .border(1.dp, hsl(ACCENT_HUE_B, 0.75f, 0.65f, 0.45f), shape)
    } else {
        base
            .background(KolaiColors.Glass2)
            .border(1.dp, KolaiColors.GlassBorderSoft, shape)
    }
    Row(
        modifier = styled.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text = mood.emoji, fontSize = 15.sp)
        Text(
            text = mood.label,
            color = if (selected) KolaiColors.Text else KolaiColors.TextDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
//  Settings sheet  (web SettingsPanel -- minimal info sheet)
// ---------------------------------------------------------------------------

@Composable
private fun SettingsSheet(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
                .liquidGlass(shape = RoundedCornerShape(KolaiRadii.LG.dp), shadowElevation = 22)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "הגדרות", color = KolaiColors.Text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                GhostIconButton(glyph = "✕", onClick = onClose)
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(KolaiColors.Hairline))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "KOLAI", color = KolaiColors.Text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.width(10.dp))
                LivePill()
            }
            Text(
                text = "הרדיו האישי שלך — מנגן בלי הפסקה, עם תקליטן AI. בקרוב: בחירת מצב רוח שמשנה את המוזיקה, ותחנה חדשה בלחיצה.",
                color = KolaiColors.TextDim,
                fontSize = 13.sp,
            )
        }
    }
}