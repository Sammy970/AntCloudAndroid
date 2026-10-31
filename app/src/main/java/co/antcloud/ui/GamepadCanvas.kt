package co.antcloud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import co.antcloud.Diagnostics.Button
import co.antcloud.Diagnostics.PadState

/** Which labels the face buttons get. The drawing keeps one layout; the glyphs follow the controller. */
enum class PadStyle { XBOX, PLAYSTATION, NINTENDO }

/**
 * A drawn controller showing live input, the same design as the Mac app's: pressed buttons light up in
 * AntCloud blue, the sticks move with the real ones and the triggers fill as they're pulled. One Xbox-style
 * body for every controller; the face-button labels and colours follow its family.
 *
 * Everything is laid out on a 480 × 340 grid and scaled to fit.
 */
@Composable
fun GamepadCanvas(state: PadState, style: PadStyle, dark: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.aspectRatio(480f / 340f)) {
        val k = size.width / 480f
        scale(k, k, pivot = Offset.Zero) {
            drawGamepad(state, style, dark) { text, center, sizeSp, color, weight ->
                // Font sizes are in grid units, like everything else, so labels scale with the drawing.
                val layout = measurer.measure(text, TextStyle(fontSize = (sizeSp * k / density / fontScale).sp, fontWeight = weight, color = color))
                // Text is measured in real pixels; undo the grid scale so it lands at grid size.
                val w = layout.size.width / k
                val h = layout.size.height / k
                scale(1 / k, 1 / k, pivot = center) {
                    drawText(layout, topLeft = Offset(center.x - w * k / 2, center.y - h * k / 2))
                }
            }
        }
    }
}

private typealias Label = DrawScope.(text: String, center: Offset, sizeSp: Float, color: Color, weight: FontWeight) -> Unit

private val LEFT_STICK = Offset(152f, 142f)
private val RIGHT_STICK = Offset(290f, 200f)
private val DPAD = Offset(190f, 200f)
private val FACE = Offset(328f, 142f)

private fun DrawScope.drawGamepad(s: PadState, style: PadStyle, dark: Boolean, label: Label) {
    val control = if (dark) Color(0xFF171717) else Color(0xFF333333)
    val track = if (dark) Color(0xFF1F1F1F) else Color(0xFFD1D1D1)
    val secondary = if (dark) Color(0xFF9A9A9A) else Color(0xFF6B6B6B)
    fun fill(on: Boolean) = if (on) AntBlue else control

    // Triggers, as fill bars above the shoulders.
    val (lt, rt) = when (style) { PadStyle.PLAYSTATION -> "L2" to "R2"; PadStyle.NINTENDO -> "ZL" to "ZR"; PadStyle.XBOX -> "LT" to "RT" }
    listOf(Triple(Offset(138f, 14f), s.lt, lt), Triple(Offset(342f, 14f), s.rt, rt)).forEach { (c, v, name) ->
        drawRoundRect(track, Offset(c.x - 48, c.y - 5), Size(96f, 10f), CornerRadius(5f))
        drawRoundRect(AntBlue, Offset(c.x - 48, c.y - 5), Size(96f * v.coerceIn(0f, 1f), 10f), CornerRadius(5f))
        label("$name ${(v * 100).toInt()}%", Offset(c.x, c.y + 18), 10f, secondary, FontWeight.SemiBold)
    }

    // Bumpers, tucked behind the shoulders.
    listOf(Triple(Offset(140f, 84f), -7f, Button.LB), Triple(Offset(340f, 84f), 7f, Button.RB)).forEach { (c, angle, b) ->
        val on = b in s.pressed
        rotate(angle, c) {
            if (on) drawRoundRect(AntBlue.copy(alpha = 0.35f), Offset(c.x - 60, c.y - 21), Size(120f, 42f), CornerRadius(20f))
            drawRoundRect(fill(on), Offset(c.x - 52, c.y - 15), Size(104f, 30f), CornerRadius(14f))
            val name = when (style) {
                PadStyle.PLAYSTATION -> if (b == Button.LB) "L1" else "R1"
                PadStyle.NINTENDO -> if (b == Button.LB) "L" else "R"
                PadStyle.XBOX -> if (b == Button.LB) "LB" else "RB"
            }
            label(name, Offset(c.x, c.y - 7), 10f, Color.White.copy(alpha = if (on) 1f else 0.6f), FontWeight.Bold)
        }
    }

    // Body: one moulded outline with a soft shadow, a top-to-bottom shade and a highlight on the top edge.
    val body = controllerPath()
    drawIntoNative { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = (if (dark) Color(0xFF262626) else Color(0xFFE0E0E0)).toArgb()
            setShadowLayer(16f, 0f, 10f, Color.Black.copy(alpha = if (dark) 0.55f else 0.2f).toArgb())
        }
        canvas.drawPath(body.asAndroid(), paint)
    }
    drawPath(body, Brush.verticalGradient(
        if (dark) listOf(Color(0xFF454545), Color(0xFF262626)) else listOf(Color(0xFFFCFCFC), Color(0xFFDBDBDB)), startY = 80f, endY = 330f))
    drawPath(body, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.18f else 0.9f), Color.Transparent), startY = 80f, endY = 170f), style = Stroke(1.5f))
    drawPath(body, Color.Black.copy(alpha = if (dark) 0.5f else 0.1f), style = Stroke(0.5f))

    // Shallow wells around the sticks and D-pad.
    listOf(LEFT_STICK to 38f, RIGHT_STICK to 38f, DPAD to 36f).forEach { (c, r) ->
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (dark) 0.35f else 0.09f), Color.Black.copy(alpha = if (dark) 0.14f else 0.03f)), c, r), r, c)
    }

    // Sticks: a base the stick pivots in, and a concave cap that follows it.
    fun stick(c: Offset, x: Float, y: Float, pressed: Boolean) {
        if (pressed) drawCircle(AntBlue.copy(alpha = 0.35f), 40f, c)
        drawCircle(control, 31f, c)
        val cap = Offset(c.x + x * 12f, c.y - y * 12f)
        drawCircle(Color.Black.copy(alpha = 0.4f), 23f, cap + Offset(0f, 2f))
        drawCircle(Brush.radialGradient(if (pressed) listOf(AntBlue.copy(alpha = 0.75f), AntBlue) else listOf(Color(0xFF2B2B2B), Color(0xFF454545)), cap, 24f), 23f, cap)
        drawCircle(Color.White.copy(alpha = 0.12f), 23f, cap, style = Stroke(1f))
    }
    stick(LEFT_STICK, s.lx, s.ly, Button.L3 in s.pressed)
    stick(RIGHT_STICK, s.rx, s.ry, Button.R3 in s.pressed)

    // D-pad: a cross with each arm lighting up on its own.
    drawRoundRect(control, Offset(DPAD.x - 11, DPAD.y - 29), Size(22f, 58f), CornerRadius(6f))
    drawRoundRect(control, Offset(DPAD.x - 29, DPAD.y - 11), Size(58f, 22f), CornerRadius(6f))
    listOf(Button.UP to Offset(0f, -16f), Button.DOWN to Offset(0f, 16f), Button.LEFT to Offset(-16f, 0f), Button.RIGHT to Offset(16f, 0f))
        .forEach { (b, d) ->
            val c = DPAD + d
            val on = b in s.pressed
            if (on) {
                drawCircle(AntBlue.copy(alpha = 0.35f), 16f, c)
                drawRoundRect(AntBlue, Offset(c.x - 10, c.y - 10), Size(20f, 20f), CornerRadius(5f))
            }
            val arrow = Path().apply {
                moveTo(0f, -3.5f); lineTo(3.5f, 2.5f); lineTo(-3.5f, 2.5f); close()
            }
            val angle = when (b) { Button.DOWN -> 180f; Button.LEFT -> 270f; Button.RIGHT -> 90f; else -> 0f }
            translate(c.x, c.y) { rotate(angle, Offset.Zero) { drawPath(arrow, Color.White.copy(alpha = if (on) 1f else 0.35f)) } }
        }

    // Face buttons, with the letters coloured as printed on the real controllers.
    listOf(Button.Y to Offset(0f, -28f), Button.A to Offset(0f, 28f), Button.X to Offset(-28f, 0f), Button.B to Offset(28f, 0f))
        .forEach { (b, d) ->
            val c = FACE + d
            val on = b in s.pressed
            if (on) drawCircle(AntBlue.copy(alpha = 0.4f), 22f, c)
            drawCircle(Color.Black.copy(alpha = 0.35f), 14f, c + Offset(0f, 1f))
            drawCircle(fill(on), 14f, c)
            drawCircle(Color.White.copy(alpha = 0.1f), 14f, c, style = Stroke(1f))
            label(faceLabel(style, b), c, if (style == PadStyle.PLAYSTATION) 14f else 13f,
                if (on) Color.White else faceColor(style, b), FontWeight.ExtraBold)
        }

    // View, Menu and the home button.
    listOf(Triple(Button.VIEW, Offset(214f, 150f), 9f), Triple(Button.MENU, Offset(266f, 150f), 9f), Triple(Button.HOME, Offset(240f, 102f), 16f))
        .forEach { (b, c, r) ->
            val on = b in s.pressed
            if (on) drawCircle(AntBlue.copy(alpha = 0.4f), r + 7, c)
            drawCircle(fill(on), r, c)
            drawCircle(Color.White.copy(alpha = 0.15f), r, c, style = Stroke(1f))
        }
}

private fun faceLabel(style: PadStyle, b: Button) = when (style) {
    PadStyle.PLAYSTATION -> mapOf(Button.A to "✕", Button.B to "○", Button.X to "□", Button.Y to "△")[b]
    PadStyle.NINTENDO -> mapOf(Button.A to "B", Button.B to "A", Button.X to "Y", Button.Y to "X")[b]
    PadStyle.XBOX -> b.name
}.orEmpty()

private fun faceColor(style: PadStyle, b: Button) = when (style) {
    PadStyle.XBOX -> mapOf(Button.A to Color(0xFF6BCC45), Button.B to Color(0xFFED4D45), Button.X to Color(0xFF408CF2), Button.Y to Color(0xFFFFC733))[b]
    PadStyle.PLAYSTATION -> mapOf(Button.A to Color(0xFF80A6FF), Button.B to Color(0xFFFF7373), Button.X to Color(0xFFF28CD9), Button.Y to Color(0xFF59D9B3))[b]
    PadStyle.NINTENDO -> Color.White.copy(alpha = 0.85f)
} ?: Color.White

/** The controller's outline on the 480 × 340 grid: the left half as curves, mirrored for the right. */
private fun controllerPath(): Path {
    // Each entry: end point, then its two control points, from the top centre around the left grip.
    val left = listOf(
        floatArrayOf(118f, 92f, 196f, 80f, 150f, 80f),
        floatArrayOf(52f, 166f, 82f, 102f, 60f, 126f),
        floatArrayOf(50f, 288f, 42f, 212f, 34f, 256f),
        floatArrayOf(124f, 304f, 66f, 322f, 106f, 326f),
        floatArrayOf(194f, 252f, 142f, 282f, 162f, 254f),
        floatArrayOf(240f, 250f, 214f, 250f, 228f, 250f),
    )
    val path = Path()
    path.moveTo(240f, 80f)
    left.forEach { (x, y, c1x, c1y, c2x, c2y) -> path.cubicTo(c1x, c1y, c2x, c2y, x, y) }
    // Mirror back up the right side: reversed order, with each curve's control points swapped.
    val starts = listOf(floatArrayOf(240f, 80f)) + left.map { floatArrayOf(it[0], it[1]) }
    for (i in left.indices.reversed()) {
        val seg = left[i]
        path.cubicTo(480 - seg[4], seg[5], 480 - seg[2], seg[3], 480 - starts[i][0], starts[i][1])
    }
    path.close()
    return path
}

private operator fun FloatArray.component6() = this[5]

private fun DrawScope.drawIntoNative(block: (android.graphics.Canvas) -> Unit) =
    drawContext.canvas.nativeCanvas.let(block)

private fun Path.asAndroid(): android.graphics.Path = (this as androidx.compose.ui.graphics.AndroidPath).internalPath
