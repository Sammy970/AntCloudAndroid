package co.antcloud.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.antcloud.AntCloud
import co.antcloud.AppState
import co.antcloud.Diagnostics
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
    private val tv by lazy { isTelevision(this) }

    private companion object {
        val NAV_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER)
    }

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
            // On a TV, text is read from the sofa: every sp is 1.3x, and the layouts switch to the 10-foot versions.
            val density = LocalDensity.current
            val isTv = tv
            CompositionLocalProvider(
                LocalIsTv provides isTv,
                LocalDensity provides if (isTv) Density(density.density, density.fontScale * 1.3f) else density,
            ) {
                AntCloudTheme(dark = intent.getStringExtra("theme")?.let { it == "dark" } ?: isSystemInDarkTheme()) {
                    Root(bridge, initialTab = preview?.split(":")?.getOrNull(2))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Moonlight's stream screen: the stream is over, the cloud PC is still on.
        if (restored) AppState.session.streamEnded()
        restored = true
    }

    // The controller test (Stream → Controllers) sees input before Compose does, and keeps controller buttons.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val navigation = tv && event.keyCode in NAV_KEYS
        // On a TV the controller check must not trap a gamepad: D-pad, A and B still navigate while it records them.
        if (navigation) {
            Diagnostics.onKey(event)
            return super.dispatchKeyEvent(remapGamepad(event))
        }
        return Diagnostics.onKey(event) || super.dispatchKeyEvent(event)
    }

    /** Gamepad A is OK and B is Back for the panel; Compose and the framework only know DPAD_CENTER, ENTER and BACK. */
    private fun remapGamepad(event: KeyEvent): KeyEvent = when (event.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_BACK, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
        else -> event
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        Diagnostics.onMotion(event)
        return super.dispatchGenericMotionEvent(event)
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
        if ("network" in parts) AppState.network.preview()
        if ("sample" in parts) Diagnostics.previewPad()
        // `home:ready:stream:osc` opens the on-screen controls editor; `--es select dpad` and `--ez try true` pass through,
        // as do `--ez selected true` (face buttons selected), `--ez options true` (Appearance card open) and `--ez removed true`
        // (LB and RB removed, so the tray shows).
        if ("osc" in parts) startActivity(Intent(this, co.antcloud.osc.OscEditorActivity::class.java)
            .putExtra("select", intent.getStringExtra("select")).putExtra("try", intent.getBooleanExtra("try", false))
            .putExtra("selected", intent.getBooleanExtra("selected", false)).putExtra("options", intent.getBooleanExtra("options", false))
            .putExtra("removed", intent.getBooleanExtra("removed", false)))
    }
}

@Composable
private fun Root(bridge: MoonlightBridge, initialTab: String?) {
    val state by AppState.auth.state.collectAsState()
    val notice by AppState.session.notice.collectAsState()

    val tv = LocalIsTv.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Overscan-safe margins on a TV (5% of 1920x1080, in dp at 2x: 48 x 27); Login draws its background edge to edge.
        Crossfade(state, Modifier.then(if (tv && state == AuthStore.State.LOGGED_IN) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier), label = "root") { s ->
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
        val ok = remember { FocusRequester() }
        if (tv) LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
        AlertDialog(
            onDismissRequest = { AppState.session.dismissNotice() },
            confirmButton = { TextButton({ AppState.session.dismissNotice() }, Modifier.focusRequester(ok).tvFocus(50.dp).heightIn(min = tvOr(52.dp))) { Text("OK") } },
            title = { Text(n.title) },
            text = { Text(n.message) },
        )
    }
}

/** Opens a web page in the browser. */
fun ComponentActivity.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
