package co.antcloud.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.antcloud.AntCloud
import co.antcloud.AppState
import co.antcloud.Log
import co.antcloud.api.UpstreamStatus
import co.antcloud.api.UserProfile
import co.antcloud.auth.AuthStore
import co.antcloud.session.Phase
import co.antcloud.stream.MoonlightBridge
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * The app's front door: AntCloud sign-in and the cloud PC panel. Streams open Moonlight's own stream
 * screen; when it closes, the panel is back with the cloud PC still running.
 */
class AntCloudActivity : ComponentActivity() {
    private lateinit var bridge: MoonlightBridge
    private var restored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AntCloud.init(this)
        enableEdgeToEdge()
        bridge = MoonlightBridge(this)
        bridge.bind()

        val preview = intent.getStringExtra("preview")
        if (preview != null) startPreview(preview)
        else if (savedInstanceState == null && AppState.auth.state.value == AuthStore.State.RESTORING) {
            Log.info("[app] AntCloud ${co.antcloud.Config.APP_VERSION}")
            AppState.scope.launch { AppState.auth.restore() }
        }

        setContent {
            AntCloudTheme(dark = intent.getStringExtra("theme")?.let { it == "dark" } ?: isSystemInDarkTheme()) {
                Root(bridge, initialTab = preview?.split(":")?.getOrNull(2))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Moonlight's stream screen: the stream is over, the cloud PC is still on.
        if (restored) AppState.session.streamEnded()
        restored = true
    }

    override fun onDestroy() {
        bridge.unbind()
        super.onDestroy()
    }

    /**
     * Screenshot mode: `adb shell am start -n <package>/co.antcloud.ui.AntCloudActivity --es preview home:ready:stream
     * --es theme dark` shows a state with a sample account, without touching stored tokens or the API.
     */
    private fun startPreview(value: String) {
        val renew = OffsetDateTime.now().plusDays(12).toString()
        AppState.auth.previewSignIn(if (value == "login") null else UserProfile(JSONObject()
            .put("firstName", "Samyak").put("email", "player@example.com").put("currentPlan", "premiumNyt")
            .put("renewDate", renew).put("totalTimeMonth", 3600).put("timeUsedMonth", 1290)))
        val parts = value.split(":")
        Phase.entries.firstOrNull { it.name.equals(parts.getOrNull(1), ignoreCase = true) }?.let {
            AppState.session.previewPhase(it, if (it == Phase.QUEUED) 3 else null)
        }
        if ("notice" in parts) AppState.session.showNotice("Servers at Capacity", "All servers are currently busy. Please wait 5–10 minutes and try again.")
        if ("upstream" in parts) UpstreamStatus.preview(0.017, null)
    }
}

@Composable
private fun Root(bridge: MoonlightBridge, initialTab: String?) {
    val state by AppState.auth.state.collectAsState()
    val notice by AppState.session.notice.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Crossfade(state, label = "root") { s ->
            when (s) {
                AuthStore.State.RESTORING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator()
                        Text("Signing in…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AuthStore.State.LOGGED_OUT -> LoginScreen()
                AuthStore.State.LOGGED_IN -> HomeScreen(bridge, initialTab)
            }
        }
    }

    notice?.let { n ->
        AlertDialog(
            onDismissRequest = { AppState.session.dismissNotice() },
            confirmButton = { TextButton({ AppState.session.dismissNotice() }) { Text("OK") } },
            title = { Text(n.title) },
            text = { Text(n.message) },
        )
    }
}

/** Opens a web page in the browser. */
fun ComponentActivity.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
