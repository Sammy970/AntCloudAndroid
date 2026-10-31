package co.antcloud.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.antcloud.AppState
import co.antcloud.Config
import co.antcloud.api.UpstreamStatus
import co.antcloud.session.AspectRatio
import co.antcloud.session.NetworkCheck
import co.antcloud.session.Phase
import co.antcloud.session.SessionService
import co.antcloud.session.StreamSettings
import co.antcloud.stream.MoonlightBridge
import com.limelight.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private enum class Tab(val title: String, val icon: ImageVector) {
    CLOUD("Cloud PC", AcIcons.Monitor), STREAM("Stream", AcIcons.Sliders), NETWORK("Network", AcIcons.Gauge), ABOUT("About", AcIcons.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(bridge: MoonlightBridge, initialTab: String?) {
    var tab by rememberSaveable { mutableStateOf(Tab.entries.firstOrNull { it.name.equals(initialTab, true) } ?: Tab.CLOUD) }
    val phase by AppState.session.phase.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.title, fontWeight = FontWeight.SemiBold) },
                actions = { if (tab == Tab.CLOUD) AccountMenu() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t }, label = { Text(t.title) },
                        icon = {
                            Box {
                                Icon(t.icon, null, Modifier.size(22.dp))
                                // Dot on Cloud PC: green when ready or streaming, orange while busy.
                                if (t == Tab.CLOUD && phase != Phase.IDLE) {
                                    Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape)
                                        .background(if (phase == Phase.READY || phase == Phase.STREAMING) GoodGreen else WarnOrange))
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(tab, Modifier.padding(padding), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
            when (t) {
                Tab.CLOUD -> CloudPcPage(bridge) { tab = Tab.STREAM }
                Tab.STREAM -> StreamPage()
                Tab.NETWORK -> NetworkPage()
                Tab.ABOUT -> AboutPage()
            }
        }
    }
}

@Composable
private fun AccountMenu() {
    val activity = LocalContext.current as ComponentActivity
    val profile by AppState.auth.profile.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Avatar(profile?.initial ?: "", 32) }
        DropdownMenu(open, { open = false }) {
            profile?.let { p ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(p.firstName, fontWeight = FontWeight.SemiBold)
                    Text(p.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            DropdownMenuItem({ Text("Refresh account") }, { open = false; scope.launch { AppState.auth.reloadProfile() } })
            DropdownMenuItem({ Text("Plans & pricing") }, { open = false; activity.openUrl(Config.PRICING_URL) })
            DropdownMenuItem({ Text("Sign out") }, { open = false; AppState.session.shutDown(); AppState.auth.logout() })
        }
    }
}

// MARK: Cloud PC

private fun statusText(phase: Phase, position: Int?) = when (phase) {
    Phase.IDLE -> "Your cloud PC is off"
    Phase.CONNECTING -> "Connecting to AntCloud…"
    Phase.QUEUED -> position?.let { "In queue — position $it" } ?: "Waiting for a server…"
    Phase.BOOTING -> "Starting your cloud PC…"
    Phase.READY -> "Cloud PC is ready"
    Phase.PAIRING -> "Pairing with your cloud PC…"
    Phase.STREAMING -> "Streaming"
}

private fun detailText(phase: Phase) = when (phase) {
    Phase.IDLE -> "Start it when you're ready to play."
    Phase.CONNECTING -> "Reaching the AntCloud servers."
    Phase.QUEUED -> "All servers are busy. Your PC starts as soon as one frees up."
    Phase.BOOTING -> "Windows is starting on your cloud PC."
    Phase.READY -> "Launch the stream to start playing."
    Phase.PAIRING -> "Pairing Moonlight with your cloud PC. No PIN needed."
    Phase.STREAMING -> "Moonlight is showing your cloud PC."
}

/** Play-first: the drawn display shows the session state, the track shows progress, one next action. */
@Composable
private fun CloudPcPage(bridge: MoonlightBridge, editSettings: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val session = AppState.session
    val phase by session.phase.collectAsState()
    val position by session.queuePosition.collectAsState()
    val settings by AppState.settings.collectAsState()
    val profile by AppState.auth.profile.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UpstreamBanner(Modifier.widthIn(max = 520.dp).padding(bottom = 16.dp))
        DisplayHero(phase, position, Modifier.widthIn(max = 340.dp).fillMaxWidth().padding(top = 8.dp, bottom = 28.dp))

        AnimatedContent(phase to position, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { (p, pos) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(statusText(p, pos), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(detailText(p), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        ConnectionTrack(phase, Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(vertical = 24.dp))

        // Actions
        when (phase) {
            Phase.IDLE -> PrimaryAction("Start Cloud PC", AcIcons.Power) {
                val p = profile
                if (p == null || !p.hasPaidPlan) {
                    session.showNotice("Plan Required", "Please buy a plan to continue.")
                    activity.openUrl(Config.PRICING_URL)
                } else {
                    scope.launch { session.startIfAllowed(settings, p.isNightPlan) }
                }
            }
            Phase.READY -> {
                PrimaryAction("Launch Stream", AcIcons.Play) { session.launchStream(settings, bridge) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ session.applySettings(settings) }) { Text("Apply Settings to PC") }
                    OutlinedButton({ session.shutDown() }) { Text("Shut Down", color = BadRed) }
                }
            }
            else -> OutlinedButton({ session.shutDown() }, Modifier.widthIn(min = 140.dp)) {
                Text(if (phase.isBusy) "Cancel" else "Shut Down", color = BadRed)
            }
        }

        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(settings.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(editSettings) { Text("Change…") }
        }

        profile?.let {
            if (it.minutesLeft != null) {
                Card(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(it.initial, 40)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.firstName, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (it.isNightPlan) AcIcons.Moon else AcIcons.Star, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(5.dp))
                                Text(it.planName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PlanUsage(it)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PrimaryAction(title: String, icon: ImageVector, onClick: () -> Unit) {
    Button(onClick, Modifier.width(240.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = AntBlue, contentColor = Color.White)) {
        Icon(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(18.dp), content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
        Column(Modifier.padding(padding)) { content() }
    }
}

/** Explains, in place, that AntCloud changed something and this app may need an update. */
@Composable
fun UpstreamBanner(modifier: Modifier = Modifier) {
    val version by UpstreamStatus.newerClientVersion.collectAsState()
    val route by UpstreamStatus.missingRoute.collectAsState()
    val tint = if (route != null) BadRed else WarnOrange
    val (title, message) = when {
        route != null -> "AntCloud changed their service" to "Part of it this app uses ($route) no longer exists, so some things won't work until this app is updated."
        version != null -> "AntCloud updated their app" to "Their Windows app is now version ${"%.3f".format(version)}; this app matches ${"%.3f".format(Config.matchedClientVersion)}. Past updates have changed sign-in and streaming, so if something stops working, this app needs updating."
        else -> return
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = tint.copy(alpha = 0.12f), border = BorderStroke(1.dp, tint.copy(alpha = 0.3f))) {
        Row(Modifier.padding(14.dp)) {
            Icon(AcIcons.Warning, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (route == null) Icon(AcIcons.Close, "Dismiss", Modifier.size(16.dp).clickable { UpstreamStatus.dismissVersionNotice() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// MARK: Stream

@Composable
private fun StreamPage() {
    val settings by AppState.settings.collectAsState()
    val phase by AppState.session.phase.collectAsState()
    val locked = phase == Phase.PAIRING || phase == Phase.STREAMING
    var skipNightPlan by remember { mutableStateOf(SessionService.nightPlanMonitorDisabled) }
    fun update(change: (StreamSettings) -> StreamSettings) { if (!locked) AppState.updateSettings(change) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (locked) Card(padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AcIcons.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("Settings are locked while you're streaming.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Section("Display", "Streams at ${settings.pixelSize}. 16:10 and 21:9 suit tablets and wide phones.")
        Card {
            Label("Resolution")
            Segments(listOf("720p" to "720", "1080p" to "1080", "1440p" to "1440", "4K" to "2160"), settings.resolution, locked) { v -> update { it.copy(resolution = v) } }
            Spacer(Modifier.height(16.dp))
            Label("Aspect ratio")
            Segments(AspectRatio.entries.map { it.label to it.name }, settings.aspect.name, locked) { v -> update { it.copy(aspect = AspectRatio.valueOf(v)) } }
        }

        Section("Performance", "Uses up to ${settings.gigabytesPerHour} GB of data per hour. Lower the bitrate if the picture stutters.")
        Card {
            Label("Frame rate")
            Segments(listOf("30" to "30", "60" to "60", "120" to "120"), settings.fps, locked) { v -> update { it.copy(fps = v) } }
            Spacer(Modifier.height(16.dp))
            Label("Bitrate")
            Segments(listOf("5", "10", "20", "30", "40", "50").map { it to "${it}000" }, settings.bitrate, locked) { v -> update { it.copy(bitrate = v) } }
            Text("Mbps", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Codec", null)
        Card {
            ToggleRow("HEVC (H.265)", "Sharper picture at the same bitrate, when your phone decodes it well.", settings.hevc, locked) { v -> update { it.copy(hevc = v) } }
        }

        Section("Controllers & typing", "To type during a stream, tap the keyboard button in the bottom corner, or tap the screen with three fingers.")
        Card { ControllersCard() }

        Section("Developer", null)
        Card {
            ToggleRow("Skip night-plan checks",
                "For testing. Skips the app's own hours check before starting and its 9:35 AM IST cutoff. AntCloud still enforces plan hours on its servers.",
                skipNightPlan, false) { skipNightPlan = it; SessionService.nightPlanMonitorDisabled = it }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, footer: String?) {
    Column(Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = AntBlue, fontWeight = FontWeight.SemiBold)
        footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Segments(options: List<Pair<String, String>>, selected: String, locked: Boolean, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (label, value) ->
            SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, enabled = !locked,
                shape = SegmentedButtonDefaults.itemShape(i, options.size), icon = {}) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, locked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = !locked)
    }
}

// MARK: Network

@Composable
private fun NetworkPage() {
    val r by AppState.network.result.collectAsState()
    val settings by AppState.settings.collectAsState()
    val phase by AppState.session.phase.collectAsState()
    val busy = phase == Phase.PAIRING || phase == Phase.STREAMING
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("How would a stream feel right now?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Measures your connection to AntCloud's servers, which share a network with the cloud PCs, so you get an idea without starting one.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Card {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Latency", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                r.medianLatency?.let { Text("${Math.round(it)} ms", color = zoneFor(it).color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(16.dp))
            SampleBars(r.samples, NetworkCheck.SAMPLE_COUNT, Modifier.fillMaxWidth().height(54.dp))
            Spacer(Modifier.height(16.dp))
            LatencyScale(r.medianLatency)
        }

        Card(padding = PaddingValues(vertical = 16.dp)) {
            Row {
                Stat("Jitter", r.jitter?.let { "${Math.round(it)} ms" })
                Stat("Packet loss", if (r.samples.isEmpty()) null else "${Math.round(r.lossPercent)}%")
                Stat("Download", r.downloadMbps?.let { "${Math.round(it)} Mbps" }
                    ?: if (r.stage == NetworkCheck.Stage.DOWNLOAD) "${Math.round(r.liveMbps)} Mbps" else null)
            }
        }

        val median = r.medianLatency
        if (r.stage == NetworkCheck.Stage.DONE && median != null) {
            val zone = zoneFor(median)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(zone.icon, null, Modifier.size(18.dp), tint = zone.color)
                    Spacer(Modifier.width(8.dp))
                    Text(zone.verdict, color = zone.color, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text(advice(r, median), color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.suggestedBitrate(settings.resolution, settings.fps)?.let { suggested ->
                    val current = settings.bitrate.toIntOrNull() ?: 0
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    val res = if (settings.resolution == "2160") "4K" else "${settings.resolution}p"
                    Text("Suggested for $res at ${settings.fps} FPS: ${suggested / 1000} Mbps", fontWeight = FontWeight.SemiBold)
                    Text(if (current == suggested) "That's what you're using." else "You're using ${current / 1000} Mbps.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (current != suggested) FilledTonalButton({ AppState.updateSettings { it.copy(bitrate = suggested.toString()) } }, Modifier.padding(top = 8.dp)) {
                        Text("Use ${suggested / 1000} Mbps")
                    }
                }
            }
        }
        if (r.stage == NetworkCheck.Stage.FAILED) Text(r.failure.orEmpty(), color = WarnOrange)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button({ scope.launch { AppState.network.run() } }, Modifier.height(50.dp), enabled = !r.isRunning && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AntBlue, contentColor = Color.White)) {
                Icon(AcIcons.Gauge, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (r.stage == NetworkCheck.Stage.IDLE) "Run Test" else "Run Again", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(14.dp))
            when {
                r.isRunning -> Text(if (r.stage == NetworkCheck.Stage.LATENCY) "Measuring latency…" else "Measuring download speed…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                busy -> Text("Available when you're not streaming.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (r.isRunning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AntBlue)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(title: String, value: String?) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "–", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun advice(r: NetworkCheck.Result, median: Double): String {
    val parts = mutableListOf(zoneFor(median).detail)
    r.jitter?.takeIf { it > 12 }?.let { parts += "Latency is jumping around (${it.toInt()} ms jitter); 5 GHz Wi-Fi closer to the router usually steadies it." }
    if (r.lossPercent >= 5) parts += "Some attempts got no answer, which shows up as stutters."
    r.downloadMbps?.takeIf { it < 15 }?.let { parts += "Your download speed is low for streaming; 720p at 10 Mbps will be smoother than 1080p." }
    return parts.joinToString(" ")
}

// MARK: About

@Composable
private fun AboutPage() {
    val activity = LocalContext.current as ComponentActivity
    var avatar by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        avatar = withContext(Dispatchers.IO) {
            runCatching { URL(Config.CREATOR_AVATAR).openStream().use { android.graphics.BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.antcloud_logo), null, Modifier.size(88.dp).clip(RoundedCornerShape(20.dp)))
            Text(Config.APP_NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Version ${Config.APP_VERSION}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = CircleShape, color = AntBlue.copy(alpha = 0.15f)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AcIcons.CheckCircle, null, Modifier.size(13.dp), tint = AntBlue)
                    Spacer(Modifier.width(6.dp))
                    Text("Matches AntCloud's Windows app ${"%.3f".format(Config.matchedClientVersion)}", color = AntBlue,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Card(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape)) {
                    Avatar(Config.CREATOR_NAME.take(1), 64)
                    avatar?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("MADE BY", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Config.CREATOR_NAME, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Designed and built this app, from reverse-engineering AntCloud to the last animation.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinkTile("GitHub", "@Sammy970", AcIcons.Code, Modifier.weight(1f)) { activity.openUrl(Config.CREATOR_GITHUB) }
                LinkTile("X", "@Sammy_970", AcIcons.At, Modifier.weight(1f)) { activity.openUrl(Config.CREATOR_X) }
            }
        }

        Card(Modifier.widthIn(max = 460.dp).fillMaxWidth(), padding = PaddingValues(4.dp)) {
            LinkRow("Source code", Config.SOURCE_URL.removePrefix("https://"), AcIcons.Braces) { activity.openUrl(Config.SOURCE_URL) }
            HorizontalDivider(Modifier.padding(start = 56.dp))
            LinkRow("Streaming by Moonlight", "Open-source game streaming (GPLv3)", AcIcons.Play) { activity.openUrl(Config.MOONLIGHT_URL) }
        }

        Text("Unofficial. Not made, endorsed or supported by AntCloud.\n\"AntCloud\" and its logo belong to their owners.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LinkTile(title: String, handle: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick, modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(handle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AcIcons.ArrowUpRight, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinkRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, 30)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(AcIcons.ArrowUpRight, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

