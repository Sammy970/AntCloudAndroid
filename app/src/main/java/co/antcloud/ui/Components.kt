package co.antcloud.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.material3.SegmentedButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.antcloud.api.UserProfile
import co.antcloud.session.Phase
import kotlin.math.cos
import kotlin.math.sin

// MARK: Display hero

private fun screenState(p: Phase) = when (p) {
    Phase.IDLE -> "off"
    Phase.READY, Phase.PAIRING -> "lit"
    Phase.STREAMING -> "live"
    else -> "working"
}

private fun screenBrush(state: String) = Brush.verticalGradient(
    when (state) {
        "lit" -> listOf(AntBlue, Color(0xFF2E5CBF))
        "live" -> listOf(Color(0xFF0F1729), Color(0xFF05080F))
        "off" -> listOf(Color(0xFF171717), Color(0xFF0A0A0A))
        else -> listOf(Color(0xFF121C33), Color(0xFF080D1A))
    }
)

/**
 * A monitor whose screen shows the session state; the app's one illustration. Dark glass when off, a
 * scanning shimmer while connecting or booting, a brand-blue screen with a breathing play button when ready,
 * a live dot while streaming. It flashes on like an old monitor when the PC starts.
 */
@Composable
fun DisplayHero(phase: Phase, queuePosition: Int?, modifier: Modifier = Modifier) {
    val lit by animateFloatAsState(if (phase == Phase.READY || phase == Phase.PAIRING) 1f else 0f, tween(600), label = "lit")
    val flash = remember { Animatable(0f) }
    val flashHeight = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        if (previous == Phase.IDLE && phase != Phase.IDLE) {
            flashHeight.snapTo(0.02f)
            flash.snapTo(0.9f)
            flashHeight.animateTo(1f, tween(220))
            flash.animateTo(0f, tween(600))
        }
        previous = phase
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = (18 + 14 * lit).dp, shape = RoundedCornerShape(16.dp),
                    ambientColor = lerpColor(Color.Black, AntBlue, lit), spotColor = lerpColor(Color.Black, AntBlue, lit),
                )
                .background(Brush.verticalGradient(listOf(Color(0xFF333333), Color(0xFF1C1C1C))), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(7.dp)
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // Each state's colour is its own layer, so changes crossfade instead of snapping.
                Crossfade(screenState(phase), animationSpec = tween(500), label = "screen") { state ->
                    Box(Modifier.fillMaxSize().background(screenBrush(state)))
                }
                if (phase in setOf(Phase.CONNECTING, Phase.QUEUED, Phase.BOOTING, Phase.PAIRING)) ScanBand()
                AnimatedContent(
                    targetState = if (phase == Phase.QUEUED && queuePosition != null) "queued" else phase.name,
                    transitionSpec = { (fadeIn(tween(350)) + scaleIn(spring(dampingRatio = 0.8f), initialScale = 0.85f)) togetherWith fadeOut(tween(200)) },
                    label = "content",
                ) { key ->
                    ScreenContent(key, phase, queuePosition)
                }
                // Power-on: a bright line opens to fill the screen and fades.
                Box(Modifier.fillMaxSize().graphicsLayer { scaleY = flashHeight.value; alpha = flash.value }.background(Color.White))
                // Glass reflection.
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent), end = Offset(400f, 300f))))
            }
        }
        // Stand: neck and foot.
        Box(Modifier.size(54.dp, 26.dp).background(Brush.verticalGradient(listOf(Color(0xFF9E9E9E), Color(0xFFC7C7C7)))))
        Box(Modifier.size(120.dp, 7.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFD1D1D1), Color(0xFFA8A8A8)))))
    }
}

@Composable
private fun ScreenContent(key: String, phase: Phase, queuePosition: Int?) {
    when (key) {
        "IDLE" -> Icon(AcIcons.Power, null, Modifier.size(30.dp), tint = Color.White.copy(alpha = 0.22f))
        "queued" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("#$queuePosition", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
            Text("in queue", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        "CONNECTING", "QUEUED" -> Icon(AcIcons.Antenna, null, Modifier.size(30.dp), tint = Color.White.copy(alpha = 0.85f))
        "BOOTING" -> Icon(AcIcons.Cpu, null, Modifier.size(30.dp), tint = Color.White.copy(alpha = 0.85f))
        "READY" -> {
            // Breathes gently: the one thing on screen asking to be pressed.
            val breathe by rememberInfiniteTransition(label = "breathe")
                .animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "b")
            Box(
                Modifier.size(54.dp).scale(1f + 0.06f * breathe)
                    .shadow((6 + 12 * breathe).dp, CircleShape, ambientColor = Color.White, spotColor = Color.White)
                    .background(Color.White, CircleShape).padding(2.5.dp).background(AntBlue, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(AcIcons.Play, null, Modifier.size(22.dp).offset(x = 2.dp), tint = Color.White) }
        }
        "PAIRING" -> Icon(AcIcons.Link, null, Modifier.size(32.dp), tint = Color.White)
        "STREAMING" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(GoodGreen)
                Spacer(Modifier.width(6.dp))
                Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("Playing in Moonlight", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

/** A soft band of brand light sweeping down the screen while the PC is working. */
@Composable
private fun ScanBand() {
    val t by rememberInfiniteTransition(label = "scan")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        val h = size.height * 0.45f
        val top = size.height * (t * 1.45f - 0.45f)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, AntBlue.copy(alpha = 0.35f), Color.Transparent), startY = top, endY = top + h),
            topLeft = Offset(0f, top), size = Size(size.width, h),
        )
    }
}

@Composable
fun PulsingDot(color: Color, size: Int = 7) {
    val p by rememberInfiniteTransition(label = "pulse")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1400)), label = "p")
    Canvas(Modifier.size(size.dp)) {
        val r = this.size.minDimension / 2
        drawCircle(color.copy(alpha = 0.4f * (1 - p)), radius = r * (1 + 1.4f * p))
        drawCircle(color, radius = r)
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

// MARK: Connection track

private val STEPS = listOf("Connect", "Boot", "Ready", "Stream")

private fun completedSteps(p: Phase) = when (p) {
    Phase.BOOTING -> 1
    Phase.READY, Phase.PAIRING -> 3
    Phase.STREAMING -> 4
    else -> 0
}

private fun currentStep(p: Phase) = when (p) {
    Phase.CONNECTING, Phase.QUEUED -> 0
    Phase.BOOTING -> 1
    Phase.PAIRING -> 3
    else -> null
}

/** Connect → Boot → Ready → Stream, with the current step pulsing and the line filling segment by segment. */
@Composable
fun ConnectionTrack(phase: Phase, modifier: Modifier = Modifier) {
    val done = completedSteps(phase)
    val filled by animateFloatAsState(done.toFloat(), tween(300 * 3), label = "filled")
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Row(modifier, verticalAlignment = Alignment.Top) {
        STEPS.forEachIndexed { i, name ->
            if (i > 0) {
                Box(Modifier.weight(1f).padding(top = 8.dp, start = 4.dp, end = 4.dp).height(2.dp).clip(CircleShape).background(muted)) {
                    Box(Modifier.fillMaxWidth((filled - (i - 1)).coerceIn(0f, 1f)).height(2.dp).background(AntBlue))
                }
            }
            val isDone = i < filled - 0.5f
            val isCurrent = i == currentStep(phase)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape)
                        .background(if (isDone) AntBlue else Color.Transparent)
                        .border(1.5.dp, if (isDone || isCurrent) AntBlue else muted, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDone) Icon(AcIcons.Check, null, Modifier.size(10.dp), tint = Color.White)
                    else if (isCurrent) PulsingDot(AntBlue)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    name, fontSize = 12.sp,
                    color = if (isDone || isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: Plan

/** Time left this month as a bar, with the renewal date. */
@Composable
fun PlanUsage(profile: UserProfile, modifier: Modifier = Modifier) {
    val total = profile.minutesLeft ?: return
    val shown by animateFloatAsState(total.toFloat(), tween(1100), label = "time")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("Time left", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(UserProfile.duration(shown.toInt()), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { shown / maxOf(profile.totalTimeMonth, 1) },
            modifier = Modifier.fillMaxWidth(), color = AntBlue, drawStopIndicator = {},
        )
        if (profile.daysLeft != null) Text(profile.renewText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Initial avatar on the brand gradient. */
@Composable
fun Avatar(initial: String, size: Int = 40) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF6A9EF0), AntBlue))),
        contentAlignment = Alignment.Center,
    ) { Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size * 0.45f).sp) }
}

/** A small icon on a blue tile, for feature rows and links. */
@Composable
fun IconTile(icon: ImageVector, size: Int = 26) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.27f).dp)).background(Brush.verticalGradient(listOf(Color(0xFF6A9EF0), AntBlue))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size((size * 0.5f).dp), tint = Color.White) }
}

// MARK: Aurora

/** Slowly drifting blurred light in AntCloud blues over a faint dot grid, behind the sign-in screen. */
@Composable
fun Aurora(dark: Boolean, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "aurora")
        .animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "t")
    data class Blob(val color: Color, val size: Float, val x: Float, val y: Float, val speed: Float, val phase: Float)
    val blobs = remember {
        listOf(
            Blob(AntBlue, 760f, 0.18f, 0.25f, 0.11f, 0f),
            Blob(Color(0xFF5C5CE6), 680f, 0.85f, 0.2f, 0.08f, 2f),
            Blob(Color(0xFF40C7FA), 640f, 0.7f, 0.85f, 0.13f, 4f),
            Blob(AntBlue, 560f, 0.3f, 0.9f, 0.09f, 1f),
        )
    }
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(if (dark) listOf(Color(0xFF080F24), Color(0xFF0D1733)) else listOf(Color(0xFFEDF5FF), Color(0xFFDBE8FF))))
        for (b in blobs) {
            val r = b.size * density / 2
            val c = Offset(size.width * (b.x + 0.08f * cos(t * b.speed + b.phase)), size.height * (b.y + 0.08f * sin(t * b.speed * 1.3f + b.phase)))
            drawCircle(Brush.radialGradient(listOf(b.color.copy(alpha = if (dark) 0.6f else 0.4f), b.color.copy(alpha = 0f)), center = c, radius = r), r, c)
        }
        // Faint dot grid that fades toward the edges.
        val step = 22 * density
        val center = Offset(size.width / 2, size.height / 2)
        var x = step / 2
        while (x < size.width) {
            var y = step / 2
            while (y < size.height) {
                val d = (Offset(x, y) - center).getDistance() / density
                val fade = (1 - (d - 80) / 440).coerceIn(0f, 1f)
                if (fade > 0) drawCircle((if (dark) Color.White else Color.Black).copy(alpha = (if (dark) 0.07f else 0.06f) * fade), 0.75f * density, Offset(x, y))
                y += step
            }
            x += step
        }
    }
}

// MARK: Latency

data class LatencyZone(val name: String, val upTo: Double, val color: Color, val verdict: String, val detail: String, val icon: ImageVector)

val LATENCY_ZONES = listOf(
    LatencyZone("Excellent", 35.0, GoodGreen, "Excellent for streaming", "Feels close to a PC under your desk, even in fast shooters.", AcIcons.CheckCircle),
    LatencyZone("Good", 60.0, Color(0xFF8CCC33), "Good for streaming", "Great for most games. Competitive shooters may feel a touch behind.", AcIcons.Check),
    LatencyZone("Playable", 90.0, WarnOrange, "Playable", "Fine for slower and single-player games; fast games will feel delayed.", AcIcons.AlertCircle),
    LatencyZone("High", 150.0, BadRed, "High latency", "Expect noticeable lag. Try another network if you can.", AcIcons.Warning),
)

fun zoneFor(ms: Double) = LATENCY_ZONES.firstOrNull { ms <= it.upTo } ?: LATENCY_ZONES.last()

/** One bar per latency sample, filling in as they arrive. */
@Composable
fun SampleBars(samples: List<Double>, expected: Int, modifier: Modifier = Modifier) {
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Canvas(modifier) {
        val top = maxOf(120.0, (samples.maxOrNull() ?: 0.0) * 1.15)
        val gap = 3 * density
        val w = (size.width - gap * (expected - 1)) / expected
        for (i in 0 until expected) {
            val x = i * (w + gap)
            val v = samples.getOrNull(i)
            val h = if (v == null) 3 * density else maxOf(3 * density, (size.height * v / top).toFloat())
            drawRoundRect(v?.let { zoneFor(it).color } ?: empty, Offset(x, size.height - h), Size(w, h), CornerRadius(2 * density))
        }
    }
}

/** Excellent → Good → Playable → High, with a marker that slides to the measured value. */
@Composable
fun LatencyScale(value: Double?, modifier: Modifier = Modifier) {
    val max = LATENCY_ZONES.last().upTo
    val shown by animateFloatAsState(((value ?: 0.0).coerceAtMost(max) / max).toFloat(), spring(dampingRatio = 0.75f), label = "marker")
    val current = value?.let { zoneFor(it) }
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                var lower = 0.0
                for (zone in LATENCY_ZONES) {
                    val x0 = (size.width * lower / max).toFloat()
                    val x1 = (size.width * zone.upTo / max).toFloat() - 2 * density
                    drawRoundRect(zone.color.copy(alpha = if (zone == current) 0.9f else 0.25f), Offset(x0, size.height / 2 - 4 * density),
                        Size(x1 - x0, 8 * density), CornerRadius(4 * density))
                    lower = zone.upTo
                }
                if (value != null) {
                    val c = Offset(size.width * shown, size.height / 2)
                    drawCircle(Color.Black.copy(alpha = 0.25f), 9 * density, c + Offset(0f, density))
                    drawCircle(Color.White, 8 * density, c)
                    drawCircle(zoneFor(value).color, 4 * density, c)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            var lower = 0.0
            for (zone in LATENCY_ZONES) {
                Text(zone.name, Modifier.weight(((zone.upTo - lower) / max).toFloat()), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                lower = zone.upTo
            }
        }
    }
}


// MARK: Android TV

/** True when the panel runs on an Android TV: set once in [AntCloudActivity]. Phones leave it false and render as before. */
val LocalIsTv = staticCompositionLocalOf { false }

fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val pm = context.packageManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
}

/** Extra space for TV's 10-foot targets: [tv] on a TV, [phone] (default 0) elsewhere. */
@Composable
fun tvOr(tv: androidx.compose.ui.unit.Dp, phone: androidx.compose.ui.unit.Dp = 0.dp) = if (LocalIsTv.current) tv else phone

/**
 * Focus indicator for remote/gamepad navigation: a 3dp AntBlue ring just outside the element (so it also shows on blue
 * buttons) and a 1.04 scale. Put it first in the modifier chain of anything focusable or clickable. Does nothing off TV.
 */
@Composable
fun Modifier.tvFocus(corner: androidx.compose.ui.unit.Dp = 16.dp): Modifier {
    if (!LocalIsTv.current) return this
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "tvFocusScale")
    return this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawWithContent {
            drawContent()
            if (focused) {
                val grow = 5.dp.toPx()
                drawRoundRect(AntBlue, Offset(-grow, -grow), Size(size.width + 2 * grow, size.height + 2 * grow),
                    CornerRadius(corner.toPx() + grow), style = Stroke(3.dp.toPx()))
            }
        }
}

/** A clickable, focusable surface for TV rows and tiles: focus ring, [onClick] on OK / A / Enter. */
@Composable
fun Modifier.tvClickable(corner: androidx.compose.ui.unit.Dp = 16.dp, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    tvFocus(corner).clip(RoundedCornerShape(corner)).clickable(enabled = enabled, onClick = onClick)

/**
 * A segmented picker. Off TV it is the Material one. On TV the whole row is one focus stop: left/right changes the value,
 * OK steps to the next one (wrapping), up/down moves on.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Segments(options: List<Pair<String, String>>, selected: String, locked: Boolean, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    if (!LocalIsTv.current) {
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (label, value) ->
                SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, enabled = !locked,
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, options.size), icon = {}) { Text(label, maxLines = 1) }
            }
        }
        return
    }
    val index = options.indexOfFirst { it.second == selected }
    fun pick(i: Int) { if (!locked) onSelect(options[i].second) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().tvFocus(14.dp)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.DirectionLeft -> if (index > 0) { pick(index - 1); true } else false
                    Key.DirectionRight -> if (index in 0 until options.lastIndex) { pick(index + 1); true } else false
                    else -> false
                }
            }
            .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { pick((index + 1) % options.size) }
            .alpha(if (locked) 0.5f else 1f),
    ) {
        options.forEachIndexed { i, (label, _) ->
            val on = i == index
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp).padding(3.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (on) AntBlue else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, maxLines = 1, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * A scrolling page. Phones: full width with the given padding, as before. TV: a centred column at most 840dp wide, so rows
 * and cards don't stretch across a 1080p screen; focus scrolls it.
 */
@Composable
fun PageColumn(
    spacing: androidx.compose.ui.unit.Dp, horizontal: androidx.compose.ui.unit.Dp, vertical: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalIsTv.current) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 868.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = horizontal, vertical = vertical),
            verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}
