package co.antcloud.session

import co.antcloud.AntCloud
import co.antcloud.Config
import co.antcloud.Log
import co.antcloud.api.ApiClient
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

enum class Phase {
    IDLE, CONNECTING, QUEUED, BOOTING, READY, PAIRING, STREAMING;

    val isBusy get() = this in setOf(CONNECTING, QUEUED, BOOTING, PAIRING)
}

data class Notice(val title: String, val message: String)

/** Pairing with and streaming from the VM; `MoonlightBridge` drives Moonlight inside this app. */
interface StreamDriver {
    /** Pairs if needed and opens Moonlight's stream screen. Returns once the stream has started. */
    suspend fun launch(host: String, settings: StreamSettings, session: SessionService)
}

/**
 * Owns one cloud-PC session: the Socket.IO connection that allocates/boots the VM, VM readiness, server
 * notices and the stream. State lives in flows the UI collects; all logic runs on the main thread.
 */
class SessionService(val api: ApiClient, private val scope: CoroutineScope) {
    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase
    private val _queuePosition = MutableStateFlow<Int?>(null)
    val queuePosition: StateFlow<Int?> = _queuePosition
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice
    /** The cloud PC's address while a launch or stream is running. */
    var streamHost: String? = null
        private set

    /** Fired when the server says the token is no longer valid. */
    var onSessionExpired: (() -> Unit)? = null

    private var socket: Socket? = null
    private var intentionalDisconnect = false
    private var vmWait: Job? = null
    private var launch: Job? = null
    private var nightPlan: Job? = null

    fun showNotice(title: String, message: String) {
        _notice.value = Notice(title, message)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** Screenshot mode: forces a phase without opening a session. */
    fun previewPhase(phase: Phase, position: Int? = null) {
        _queuePosition.value = position
        _phase.value = phase
    }

    // Session lifecycle

    /**
     * Starts a session, but tells a night-plan user up front when it's outside the plan's hours, instead of
     * booting the PC only for the server to refuse it later.
     */
    suspend fun startIfAllowed(settings: StreamSettings, nightPlan: Boolean) {
        if (_phase.value != Phase.IDLE) return
        if (nightPlan && !nightPlanMonitorDisabled) {
            val now = api.serverTime() ?: OffsetDateTime.now()
            if (!isNightPlanWindow(now)) {
                Log.info("[session] night plan outside its hours; not starting")
                showNotice("Night Plan Hours", "Your night plan works between 12:30 AM and 9:30 AM IST. Come back then, or switch plans for daytime play.")
                return
            }
        }
        start(settings, nightPlan)
    }

    fun start(settings: StreamSettings, nightPlan: Boolean) {
        val token = api.accessToken
        if (_phase.value != Phase.IDLE || token == null) return
        intentionalDisconnect = false
        _phase.value = Phase.CONNECTING
        Log.info("[session] connecting, stream spec \"${settings.serverSpec}\", night plan $nightPlan")

        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val options = IO.Options.builder()
            .setPath("/socket.io/")
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(false)
            .setExtraHeaders(mapOf("Origin" to listOf(Config.ORIGIN)))
            .setQuery("type=client&game=desktop&connection=${enc(Config.CONNECTION_TYPE)}&token=${enc(token)}&stream=${enc(settings.serverSpec)}")
            .build()
        val client = IO.socket(URI.create(Config.SOCKET_URL), options)
        socket = client

        // Every handler checks it still belongs to the current socket: a torn-down one can still deliver late events.
        fun onMain(block: () -> Unit) = scope.launch(Dispatchers.Main) { if (socket === client) block() }

        client.onAnyIncoming { args -> Log.info("[socket] event ${args.joinToString()}") }
        client.on(Socket.EVENT_CONNECT) { Log.info("[socket] connected") }

        client.on("status") { args ->
            val status = (args.firstOrNull() as? String)?.trim()?.lowercase() ?: return@on
            onMain {
                if (status.startsWith("waiting")) {
                    _queuePosition.value = status.split(" ").getOrNull(1)?.toIntOrNull()
                    _phase.value = Phase.QUEUED
                } else if (status == "starting") {
                    _phase.value = Phase.BOOTING
                }
            }
        }

        client.on("new_connection") { args ->
            val clients = (args.firstOrNull() as? JSONObject)?.optInt("clients") ?: 0
            if (clients >= 2) onMain { vmJoined(nightPlan) }
        }

        client.on("inactive") { args ->
            val reason = args.firstOrNull() as? String ?: "unknown"
            onMain { handleInactive(reason) }
        }

        client.on("reset") { onMain { vmWait?.cancel() } }

        client.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args.firstOrNull()?.toString() ?: "unknown"
            onMain {
                Log.info("[socket] disconnected: $reason (intentional: $intentionalDisconnect)")
                val wasIntentional = intentionalDisconnect
                teardown()
                if (!wasIntentional) showNotice("Disconnected", "Lost connection to the AntCloud server ($reason).")
            }
        }

        client.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.firstOrNull()?.toString() ?: "unknown error"
            onMain {
                if (_phase.value != Phase.CONNECTING) return@onMain
                teardown()
                showNotice("Connection Error", "Could not reach the stream server: $error")
            }
        }

        client.connect()
    }

    /** Ends the session and shuts down the VM. */
    fun shutDown() {
        val client = socket ?: return teardown()
        intentionalDisconnect = true
        if (client.connected()) client.emit("closestream")
        client.disconnect()
        teardown()
    }

    /** Pushes new display settings to the VM (the server reconfigures the host resolution). */
    fun applySettings(settings: StreamSettings) {
        socket?.takeIf { it.connected() }?.emit("setstream", settings.serverSpec)
    }

    // Streaming

    /** Fetches the VM address, applies display settings and hands off to Moonlight. */
    fun launchStream(settings: StreamSettings, driver: StreamDriver) {
        if (_phase.value != Phase.READY) return
        _phase.value = Phase.PAIRING
        launch = scope.launch {
            try {
                val host = api.vmIp()
                streamHost = host
                applySettings(settings)
                driver.launch(host, settings, this@SessionService)
                markStreaming()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.info("[stream] launch failed: ${e.message}")
                showNotice("Stream Error", e.message ?: "Couldn't start the stream.")
                streamHost = null
                if (_phase.value == Phase.PAIRING) _phase.value = Phase.READY
            }
        }
    }

    fun markStreaming() {
        if (_phase.value == Phase.PAIRING) _phase.value = Phase.STREAMING
    }

    /** Moonlight's stream screen closed and the panel is back; the cloud PC keeps running. */
    fun streamEnded() {
        if (_phase.value != Phase.STREAMING) return
        Log.info("[stream] ended")
        streamHost = null
        _phase.value = Phase.READY
    }

    // VM readiness

    /**
     * The VM agent joined the room. Like AntCloud's client, the stream can be launched right away;
     * `checkVmConnectionStatus` only turns true once Moonlight has connected, at which point the server is
     * told to start video.
     */
    private fun vmJoined(nightPlanActive: Boolean) {
        if (_phase.value != Phase.PAIRING && _phase.value != Phase.STREAMING) _phase.value = Phase.READY
        if (vmWait != null) return
        vmWait = scope.launch {
            while (isActive) {
                if (runCatching { api.connectionStatus() }.getOrDefault(false)) break
                delay(1000)
            }
            val client = socket
            if (!isActive || client == null || !client.connected()) return@launch
            Log.info("[session] VM reports connected → control video")
            client.emit("control", "video")
            if (nightPlanActive) startNightPlanMonitor()
        }
    }

    /** Night plans are only valid 00:30–09:35 IST; end the session once the server clock passes the cutoff. */
    private fun startNightPlanMonitor() {
        if (nightPlanMonitorDisabled) {
            Log.info("[session] night-plan monitor disabled by setting; not starting")
            return
        }
        nightPlan?.cancel()
        nightPlan = scope.launch {
            while (isActive) {
                val now = (api.serverTime() ?: OffsetDateTime.now()).atZoneSameInstant(IST)
                if (now.hour * 60 + now.minute >= 9 * 60 + 35) {
                    shutDown()
                    showNotice("Night Plan Ended", "Your night plan session ended at 9:30 AM IST. The next window opens at 12:30 AM IST.")
                    return@launch
                }
                delay(30_000)
            }
        }
    }

    // Server notices

    private fun handleInactive(reason: String) {
        if (reason == "videonotstarting") {
            shutDown()
            showNotice("Stream Failed", "The VM's video did not start. Please try again.")
            return
        }
        val (title, message) = INACTIVE_MESSAGES[reason] ?: ("Connection Error" to "An unexpected error occurred: $reason")
        showNotice(title, message)
        when (reason) {
            "wrongToken", "inactive" -> {
                shutDown()
                onSessionExpired?.invoke()
            }
            "planover" -> shutDown()
        }
    }

    private fun teardown() {
        if (_phase.value != Phase.IDLE) Log.info("[session] teardown from ${_phase.value}")
        vmWait?.cancel(); vmWait = null
        launch?.cancel(); launch = null
        nightPlan?.cancel(); nightPlan = null
        socket?.off()
        socket = null
        streamHost = null
        _queuePosition.value = null
        _phase.value = Phase.IDLE
    }

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")

        /** Night plans can start between 12:30 AM and 9:30 AM IST. */
        fun isNightPlanWindow(date: OffsetDateTime): Boolean {
            val ist = date.atZoneSameInstant(IST)
            val minutes = ist.hour * 60 + ist.minute
            return minutes >= 30 && minutes < 9 * 60 + 30
        }

        /** The next 9:30 AM IST, when night plans end, in local time. */
        fun nightPlanEnd(after: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
            val ist = after.withZoneSameInstant(IST)
            var end = ist.withHour(9).withMinute(30).withSecond(0).withNano(0)
            if (!end.isAfter(ist)) end = end.plusDays(1)
            return end.withZoneSameInstant(ZoneId.systemDefault())
        }

        /**
         * When true, the client-side night-plan checks are off (for testing). This does NOT affect the server's
         * own enforcement of night-plan hours.
         */
        var nightPlanMonitorDisabled: Boolean
            get() = AntCloud.prefs.getBoolean("nightPlanMonitorDisabled", false)
            set(value) { AntCloud.prefs.edit().putBoolean("nightPlanMonitorDisabled", value).apply() }

        private val INACTIVE_MESSAGES = mapOf(
            "daytime" to ("Night Plan" to "Outside active hours. Come back between 12:30 AM and 9:30 AM."),
            "emailverification" to ("Email Verification Required" to "Please verify your email address to continue using AntCloud. Check your inbox for the verification link."),
            "dual" to ("Multiple Connections Detected" to "Another stream is already running on this account. Close the other connection and try again."),
            "inactive" to ("Account Disabled" to "Your account has been disabled. Contact support@antcloud.co for assistance."),
            "vmnotfound" to ("Profile Not Ready" to "Your streaming profile is still being prepared. Please wait 5 minutes and try again."),
            "vmissue" to ("Virtual Machine Error" to "Failed to launch your virtual machine. Please wait 2–3 minutes and try again."),
            "serversfull" to ("Servers at Capacity" to "All servers are currently busy. Please wait 5–10 minutes and try again."),
            "wrongToken" to ("Session Expired" to "Your session has expired. Please log in again."),
            "planendwarning" to ("Plan Expiring Soon" to "Your plan will expire soon. Renew to avoid interruption."),
            "planover" to ("Plan Expired" to "Your plan has expired. Renew to continue using AntCloud."),
        )
    }
}
