package co.antcloud.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** AntCloud brand blue, sampled from the official icon. The only accent colour. */
val AntBlue = Color(0xFF4A86E8)
val GoodGreen = Color(0xFF2EB85C)
val WarnOrange = Color(0xFFF7A325)
val BadRed = Color(0xFFE5484D)

@Composable
fun AntCloudTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0B1E3F),
            primaryContainer = Color(0xFF1F3B6B), onPrimaryContainer = Color(0xFFD6E3FF),
            secondaryContainer = Color(0xFF263449), onSecondaryContainer = Color(0xFFD6E3FF),
            background = Color(0xFF0F1115), surface = Color(0xFF0F1115),
            surfaceContainer = Color(0xFF1A1D23), surfaceContainerHigh = Color(0xFF22262D),
            surfaceContainerLow = Color(0xFF15181D),
        )
    } else {
        lightColorScheme(
            primary = AntBlue, onPrimary = Color.White,
            primaryContainer = Color(0xFFD9E5FB), onPrimaryContainer = Color(0xFF0B2A5C),
            secondaryContainer = Color(0xFFDDE6F7), onSecondaryContainer = Color(0xFF13233F),
            background = Color(0xFFF7F8FA), surface = Color(0xFFF7F8FA),
            surfaceContainer = Color(0xFFEEF1F5), surfaceContainerHigh = Color(0xFFE6EAF0),
            surfaceContainerLow = Color(0xFFF2F4F7),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Line icons on a 24-unit grid (Lucide-style strokes), the same set as the Windows app. */
object AcIcons {
    val Monitor = icon("M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M8 21h8 M12 17v4")
    val Sliders = icon("M21 4h-7 M10 4H3 M21 12h-9 M8 12H3 M21 20h-5 M12 20H3 M14 2v4 M8 10v4 M16 18v4")
    val Gauge = icon("M12 14l4-4 M3.34 19a10 10 0 1 1 17.32 0")
    val Info = icon("M12 2a10 10 0 1 1 0 20a10 10 0 1 1 0-20z M12 16v-4 M12 8h.01")
    val Power = icon("M12 2v10 M18.4 6.6a9 9 0 1 1-12.77.04")
    val Play = icon("M7 4l13 8-13 8z", filled = true)
    val Link = icon("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71 M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    val Cpu = icon("M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M9 9h6v6H9z M15 2v2 M15 20v2 M2 15h2 M2 9h2 M20 15h2 M20 9h2 M9 2v2 M9 20v2")
    val Antenna = icon("M4.9 19.1C1 15.2 1 8.8 4.9 4.9 M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5 M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5 M19.1 4.9C23 8.8 23 15.1 19.1 19 M12 11a1 1 0 1 1 0 2a1 1 0 1 1 0-2z")
    val Check = icon("M20 6L9 17l-5-5")
    val Moon = icon("M12 3a6 6 0 0 0 9 9a9 9 0 1 1-9-9z", filled = true)
    val Star = icon("M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z", filled = true)
    val Warning = icon("M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3z M12 9v4 M12 17h.01")
    val AlertCircle = icon("M12 2a10 10 0 1 1 0 20a10 10 0 1 1 0-20z M12 8v4 M12 16h.01")
    val CheckCircle = icon("M12 2a10 10 0 1 1 0 20a10 10 0 1 1 0-20z M9 12l2 2 4-4")
    val More = icon("M12 5h.01 M12 12h.01 M12 19h.01", stroke = 3f)
    val ArrowUpRight = icon("M7 7h10v10 M7 17L17 7")
    val Code = icon("M16 18l6-6-6-6 M8 6l-6 6 6 6")
    val At = icon("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8")
    val Lock = icon("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z M7 11V7a5 5 0 0 1 10 0v4")
    val Tv = icon("M4 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z M17 2l-5 5-5-5")
    val Close = icon("M18 6L6 18 M6 6l12 12")
    val Braces = icon("M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2a2 2 0 0 1 2 2v5c0 1.1.9 2 2 2h1 M16 21h1a2 2 0 0 0 2-2v-5c0-1.1.9-2 2-2a2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1")
    val Gamepad = icon("M6 11h4 M8 9v4 M15 12h.01 M18 10h.01 M17.32 5H6.68a4 4 0 0 0-3.978 3.59c-.006.052-.01.101-.017.152C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258-.007-.05-.011-.1-.017-.151A4 4 0 0 0 17.32 5z")
    val Phone = icon("M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M12 18h.01")

    private fun icon(d: String, filled: Boolean = false, stroke: Float = 2f) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(d),
        fill = if (filled) SolidColor(Color.Black) else null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = stroke,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
}
