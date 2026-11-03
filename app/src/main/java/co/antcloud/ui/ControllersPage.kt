package co.antcloud.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.antcloud.Diagnostics
import com.limelight.preferences.StreamSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controls tab (controller check half): check a controller before playing. A drawn controller mirrors every press, stick and
 * trigger; the card says whether Moonlight will send it to the cloud PC, with a vibration test, the
 * diagnostics report and Moonlight's own controller settings. The same check as the Mac app's Controllers pane.
 */
@Composable
fun ControllersPage(header: @Composable () -> Unit = {}) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var devices by remember { mutableStateOf(Diagnostics.devices()) }
    val pad by Diagnostics.pad.collectAsState()
    val inputs by Diagnostics.inputs.collectAsState()

    // Controllers come and go (Bluetooth sleep, plugging in), so the list refreshes while the tab is open.
    LaunchedEffect(Unit) {
        while (true) {
            devices = Diagnostics.devices()
            delay(2000)
        }
    }
    // While this tab is open the activity hands controller input here, and keeps buttons from acting as Back.
    DisposableEffect(Unit) {
        Diagnostics.listening = true
        Diagnostics.clearInputs()
        onDispose { Diagnostics.listening = false }
    }

    val controller = devices.firstOrNull { it.kind == Diagnostics.Kind.CONTROLLER } ?: devices.firstOrNull { it.kind != Diagnostics.Kind.KEYBOARD }
    val others = devices.filter { it != controller }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        header()
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("Controller check", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Press its buttons and move the sticks: everything that reaches this device lights up here, so you know it'll work before you start the cloud PC.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (controller == null) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    GamepadCanvas(Diagnostics.PadState(), PadStyle.XBOX, dark, Modifier.widthIn(max = 340.dp).fillMaxWidth().alpha(0.45f))
                    Text("No controller connected", fontWeight = FontWeight.SemiBold)
                    Text("Turn on your controller and connect it with Bluetooth or a USB cable. It shows up here the moment this device sees it.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    OutlinedButton({ activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Open Bluetooth settings") }
                }
            }
        } else {
            val ready = controller.kind == Diagnostics.Kind.CONTROLLER
            val style = when (controller.vendorId) { 0x054c -> PadStyle.PLAYSTATION; 0x057e -> PadStyle.NINTENDO; else -> PadStyle.XBOX }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconTile(AcIcons.Gamepad, 36)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(controller.name, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(controller.ids, if (controller.external == false) "Built in" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Verdict(ready)
                    }

                    GamepadCanvas(pad, style, dark, Modifier.widthIn(max = 460.dp).fillMaxWidth().align(Alignment.CenterHorizontally))

                    // The exact numbers, for checking stick drift and how far the triggers travel.
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 10.dp)) {
                        Readout("Left stick", "%+.2f  %+.2f".format(pad.lx, pad.ly))
                        Readout("Right stick", "%+.2f  %+.2f".format(pad.rx, pad.ry))
                        Readout("Triggers", "%.2f  %.2f".format(pad.lt, pad.rt))
                    }

                    if (!ready) {
                        Text(
                            "Android isn't reporting this as a controller with sticks, so your cloud PC may not get a controller. " +
                                "If it has a mode switch, try its X-input or Xbox mode; a USB cable or its own dongle often helps too.",
                            style = MaterialTheme.typography.bodySmall, color = WarnOrange,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({
                            if (!Diagnostics.vibrate(controller.id)) {
                                Toast.makeText(activity, "This controller has no vibration Android can use.", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(AcIcons.Antenna, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test vibration")
                        }
                    }
                }
            }
        }

        if (others.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Also connected", style = MaterialTheme.typography.titleSmall, color = AntBlue, fontWeight = FontWeight.SemiBold)
                    others.forEach { d ->
                        Column {
                            Text(d.name, fontWeight = FontWeight.Medium)
                            Text("${d.kind.summary} · ${d.sources.joinToString(", ").ifEmpty { "no input types" }} · ${d.ids}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Troubleshooting: what reached the device, the report and Moonlight's own controller settings.
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Troubleshooting", style = MaterialTheme.typography.titleSmall, color = AntBlue, fontWeight = FontWeight.SemiBold)
                Text(inputs.firstOrNull()?.let { "Last input: $it" } ?: "Last input: none yet",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ scope.launch { Diagnostics.share(activity) } }) { Text("Share diagnostics") }
                    OutlinedButton({ activity.startActivity(Intent(activity, StreamSettings::class.java)) }) { Text("Moonlight settings") }
                }
                Text(
                    "If a controller doesn't work in a stream, play with it for a moment, come back here and share diagnostics: " +
                        "the report includes Moonlight's log from that stream. Moonlight settings has its controller options, like the Xbox USB driver and button layout.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Verdict(ready: Boolean) {
    val color = if (ready) GoodGreen else WarnOrange
    Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ready) AcIcons.CheckCircle else AcIcons.Warning, null, Modifier.size(13.dp), tint = color)
            Spacer(Modifier.width(5.dp))
            Text(if (ready) "Ready to play" else "May not work", color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Readout(title: String, value: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

