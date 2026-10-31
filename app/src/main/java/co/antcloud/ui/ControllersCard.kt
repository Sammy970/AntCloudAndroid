package co.antcloud.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.antcloud.Diagnostics
import com.limelight.preferences.StreamSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stream → Controllers: what Android reports for each connected input device and whether Moonlight will
 * send it to the cloud PC as a controller, a live button test, the diagnostics report and Moonlight's own
 * controller settings.
 */
@Composable
fun ControllersCard(modifier: Modifier = Modifier) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(Diagnostics.devices()) }
    var testing by remember { mutableStateOf(false) }
    val inputs by Diagnostics.inputs.collectAsState()

    // Controllers come and go (Bluetooth sleep, plugging in), so the list refreshes while it's on screen.
    LaunchedEffect(Unit) {
        while (true) {
            devices = Diagnostics.devices()
            delay(2000)
        }
    }
    DisposableEffect(testing) {
        Diagnostics.listening = testing
        onDispose { Diagnostics.listening = false }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val controllers = devices.filter { it.moonlightController }
        if (devices.isEmpty()) {
            Text("No controllers connected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        devices.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (d.moonlightController) AcIcons.CheckCircle else AcIcons.AlertCircle, null, Modifier.size(18.dp),
                    tint = when (d.kind) {
                        Diagnostics.Kind.CONTROLLER -> GoodGreen
                        Diagnostics.Kind.BUTTONS_ONLY -> WarnOrange
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontWeight = FontWeight.Medium)
                    Text(
                        d.kind.summary +
                            " · ${d.sources.joinToString(", ").ifEmpty { "no input types" }} · ${d.ids}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (devices.any { it.kind != Diagnostics.Kind.KEYBOARD } && controllers.isEmpty()) {
            Text(
                "Android isn't reporting any of these as a controller with sticks, so your cloud PC won't get a controller. " +
                    "If your controller has a mode switch, try its Android or X-input mode, then check again.",
                style = MaterialTheme.typography.bodySmall, color = WarnOrange,
            )
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Test buttons", style = MaterialTheme.typography.bodyLarge)
                Text("Press buttons and move the sticks to see what reaches this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(testing, { testing = it; if (it) Diagnostics.clearInputs() })
        }
        if (testing) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(12.dp).height(150.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (inputs.isEmpty()) Text("Waiting for input…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                inputs.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ scope.launch { Diagnostics.share(activity) } }) { Text("Share diagnostics") }
            OutlinedButton({ activity.startActivity(Intent(activity, StreamSettings::class.java)) }) { Text("Moonlight settings") }
        }
        Text(
            "Share diagnostics right after a stream where the controller didn't work: it includes Moonlight's log from that stream. " +
                "Moonlight settings has its controller options, like the Xbox USB driver and button layout.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
