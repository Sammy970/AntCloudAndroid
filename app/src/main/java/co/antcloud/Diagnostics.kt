package co.antcloud

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

/**
 * Helps find out why a controller doesn't work, on a phone we can't plug in: what Android reports for each
 * input device, whether Moonlight will treat it as a controller, live input from it, and a report with
 * Moonlight's own log that a tester can share.
 */
object Diagnostics {
    data class Device(
        val id: Int,
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val sources: List<String>,
        val external: Boolean?,
        val kind: Kind,
    ) {
        val ids get() = "%04x:%04x".format(vendorId, productId)
        val moonlightController get() = kind == Kind.CONTROLLER
    }

    enum class Kind(val summary: String) {
        /** Joystick axes: Moonlight counts it when the stream starts and the cloud PC gets a controller. */
        CONTROLLER("Sent to the cloud PC as a controller"),
        /** Gamepad buttons but no stick axes: Moonlight doesn't count it at stream start. */
        BUTTONS_ONLY("Buttons only, no sticks: the cloud PC may not get a controller"),
        KEYBOARD("Keyboard"),
        OTHER("Not a controller"),
    }

    /**
     * Connected controllers and keyboards, as Android reports them. The phone's own inputs (screen, volume
     * keys, fingerprint sensor) are left out, unless they claim to be a gamepad.
     */
    fun devices(): List<Device> = previewDevices ?: InputDevice.getDeviceIds().toList().mapNotNull { id ->
        val dev = InputDevice.getDevice(id) ?: return@mapNotNull null
        if (dev.isVirtual) return@mapNotNull null
        fun has(source: Int) = dev.sources and source == source
        val gamepadLike = has(InputDevice.SOURCE_GAMEPAD) || has(InputDevice.SOURCE_JOYSTICK)
        val external = if (Build.VERSION.SDK_INT >= 29) dev.isExternal else !has(InputDevice.SOURCE_TOUCHSCREEN)
        if (!gamepadLike && !external) return@mapNotNull null
        Device(
            id = id,
            name = dev.name,
            vendorId = dev.vendorId,
            productId = dev.productId,
            sources = sourceNames(dev.sources),
            external = if (Build.VERSION.SDK_INT >= 29) dev.isExternal else null,
            kind = kind(dev),
        )
    }.sortedBy { it.kind.ordinal }

    /** The same test Moonlight uses to count controllers when a stream starts (ControllerHandler.hasJoystickAxes). */
    private fun kind(dev: InputDevice): Kind {
        val s = dev.sources
        fun axis(a: Int) = dev.getMotionRange(a, InputDevice.SOURCE_JOYSTICK) ?: dev.getMotionRange(a, InputDevice.SOURCE_GAMEPAD)
        return when {
            s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && axis(MotionEvent.AXIS_X) != null && axis(MotionEvent.AXIS_Y) != null -> Kind.CONTROLLER
            s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD -> Kind.BUTTONS_ONLY
            dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC -> Kind.KEYBOARD
            else -> Kind.OTHER
        }
    }

    private fun sourceNames(sources: Int) = listOfNotNull(
        "gamepad".takeIf { sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD },
        "joystick".takeIf { sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK },
        "dpad".takeIf { sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD },
        "keyboard".takeIf { sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD },
        "mouse".takeIf { sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE },
        "touchpad".takeIf { sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD },
        "touchscreen".takeIf { sources and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN },
    )

    // Live input

    enum class Button { A, B, X, Y, LB, RB, L3, R3, UP, DOWN, LEFT, RIGHT, VIEW, MENU, HOME }

    /** What the drawn controller shows: buttons held down, stick positions (-1…1, up positive) and triggers (0…1). */
    data class PadState(
        val pressed: Set<Button> = emptySet(),
        val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f,
        val lt: Float = 0f, val rt: Float = 0f,
    )

    private val _pad = MutableStateFlow(PadState())
    /** The controller's live state while the Controllers tab is open. */
    val pad: StateFlow<PadState> = _pad

    /** D-pad and buttons held as key presses, and D-pad directions held on the hat axis; the drawing shows both. */
    private var heldKeys = emptySet<Button>()
    private var hatButtons = emptySet<Button>()

    private val keyButtons = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to Button.A, KeyEvent.KEYCODE_BUTTON_B to Button.B,
        KeyEvent.KEYCODE_BUTTON_X to Button.X, KeyEvent.KEYCODE_BUTTON_Y to Button.Y,
        KeyEvent.KEYCODE_BUTTON_L1 to Button.LB, KeyEvent.KEYCODE_BUTTON_R1 to Button.RB,
        KeyEvent.KEYCODE_BUTTON_THUMBL to Button.L3, KeyEvent.KEYCODE_BUTTON_THUMBR to Button.R3,
        KeyEvent.KEYCODE_DPAD_UP to Button.UP, KeyEvent.KEYCODE_DPAD_DOWN to Button.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to Button.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to Button.RIGHT,
        KeyEvent.KEYCODE_BUTTON_SELECT to Button.VIEW, KeyEvent.KEYCODE_BUTTON_START to Button.MENU,
        KeyEvent.KEYCODE_BUTTON_MODE to Button.HOME,
    )

    /** The last few inputs seen while the controller test is open, newest first. */
    private val _inputs = MutableStateFlow<List<String>>(emptyList())
    val inputs: StateFlow<List<String>> = _inputs

    /** Set while the controller test is on screen; the activity then reports key and stick events here. */
    @Volatile var listening = false

    fun clearInputs() {
        if (previewing) return
        _inputs.value = emptyList()
        heldKeys = emptySet()
        hatButtons = emptySet()
        _pad.value = PadState()
    }

    /**
     * Records a key press during the test. Returns true for controller buttons, which the test keeps for itself:
     * otherwise B would act as Back and close the app mid-test.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (!listening) return false
        val fromController = KeyEvent.isGamepadButton(event.keyCode) ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        keyButtons[event.keyCode]?.let { button ->
            heldKeys = if (event.action == KeyEvent.ACTION_DOWN) heldKeys + button else heldKeys - button
            _pad.value = _pad.value.copy(pressed = heldKeys + hatButtons)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            record("${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")} from ${source(event.device, event.deviceId)}")
        }
        return fromController
    }

    private var lastMotion = 0L

    fun onMotion(event: MotionEvent) {
        if (!listening) return
        val joystick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!joystick || event.action != MotionEvent.ACTION_MOVE) return
        // The drawing follows every event; the D-pad on most controllers arrives as a hat axis.
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatButtons = buildSet {
            if (hatX < -0.5f) add(Button.LEFT)
            if (hatX > 0.5f) add(Button.RIGHT)
            if (hatY < -0.5f) add(Button.UP)
            if (hatY > 0.5f) add(Button.DOWN)
        }
        _pad.value = _pad.value.copy(
            pressed = heldKeys + hatButtons,
            lx = event.getAxisValue(MotionEvent.AXIS_X), ly = -event.getAxisValue(MotionEvent.AXIS_Y),
            rx = event.getAxisValue(MotionEvent.AXIS_Z), ry = -event.getAxisValue(MotionEvent.AXIS_RZ),
            // Controllers report triggers on one of two pairs of axes.
            lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
            rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
        )
        // Sticks send a stream of events; one line every half second is enough to see them working.
        val now = System.currentTimeMillis()
        if (now - lastMotion < 500) return
        lastMotion = now
        val axes = listOf(
            "LX" to MotionEvent.AXIS_X, "LY" to MotionEvent.AXIS_Y, "RX" to MotionEvent.AXIS_Z, "RY" to MotionEvent.AXIS_RZ,
            "LT" to MotionEvent.AXIS_LTRIGGER, "RT" to MotionEvent.AXIS_RTRIGGER, "HatX" to MotionEvent.AXIS_HAT_X, "HatY" to MotionEvent.AXIS_HAT_Y,
        ).mapNotNull { (name, axis) -> event.getAxisValue(axis).takeIf { kotlin.math.abs(it) > 0.15f }?.let { "$name %.2f".format(it) } }
        if (axes.isNotEmpty()) record("Stick ${axes.joinToString(" ")} from ${source(event.device, event.deviceId)}")
    }

    /** A short buzz on the controller, to check its motors. Returns false if it has none Android can drive. */
    fun vibrate(deviceId: Int): Boolean {
        val dev = InputDevice.getDevice(deviceId) ?: return false
        val vibrator = if (Build.VERSION.SDK_INT >= 31) dev.vibratorManager.defaultVibrator else @Suppress("DEPRECATION") dev.vibrator
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(450, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        return true
    }

    /** Screenshot mode: a sample controller mid-press, without hardware. */
    private var previewDevices: List<Device>? = null

    fun previewPad() {
        previewDevices = listOf(Device(-2, "Xbox Wireless Controller", 0x045e, 0x02e0, listOf("gamepad", "joystick", "keyboard"), true, Kind.CONTROLLER))
        _pad.value = PadState(pressed = setOf(Button.A, Button.RB), lx = 0.55f, ly = 0.35f, rt = 0.7f)
    }

    /** Keeps the screenshot sample's input on screen. */
    val previewing get() = previewDevices != null

    private fun source(device: InputDevice?, id: Int) = "${device?.name ?: "unknown"} (id $id)"

    private fun record(line: String) {
        Log.info("[controller-test] $line")
        _inputs.value = (listOf(line) + _inputs.value).take(8)
    }

    // Report

    /**
     * Writes a report (phone, input devices, the AntCloud log and this app's logcat, which includes Moonlight's
     * controller messages from the last stream) and opens the share sheet for it.
     */
    suspend fun share(context: Context) {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "diagnostics").apply { mkdirs() }.resolve("antcloud-diagnostics.txt").apply { writeText(report(context)) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "AntCloud for Android diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
    }

    private fun report(context: Context): String = buildString {
        appendLine("AntCloud for Android ${Config.APP_VERSION} diagnostics, ${LocalDateTime.now()}")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        appendLine("== Input devices ==")
        devices().forEach { d ->
            appendLine("${d.name} [${d.ids}] id=${d.id} sources=${d.sources.joinToString(",")} external=${d.external} kind=${d.kind} keyboardType=${InputDevice.getDevice(d.id)?.keyboardType}")
        }
        appendLine()
        appendLine("== Controller test ==")
        inputs.value.reversed().forEach { appendLine(it) }
        appendLine()
        appendLine("== AntCloud log (last 300 lines) ==")
        runCatching { File(context.filesDir, "antcloud.log").readLines().takeLast(300).forEach { appendLine(it) } }
        appendLine()
        appendLine("== App logcat (Moonlight and AntCloud) ==")
        runCatching {
            val args = mutableListOf("logcat", "-d", "-v", "time", "-t", "4000")
            if (Build.VERSION.SDK_INT >= 24) args += listOf("--pid", Process.myPid().toString())
            val output = java.lang.Runtime.getRuntime().exec(args.toTypedArray()).inputStream.bufferedReader().readText()
            // Strip anything that looks like a token, in case a library logged one.
            append(output.replace(Regex("(token=|JWT )[A-Za-z0-9._-]+"), "$1<redacted>"))
        }.onFailure { appendLine("logcat unavailable: ${it.message}") }
    }
}
