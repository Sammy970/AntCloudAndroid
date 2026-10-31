package co.antcloud

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Constants shared by the AntCloud panel; the same values as the Mac, iPhone and Windows apps. */
object Config {
    const val API_BASE = "https://api.antcloud.co/api/"
    const val SOCKET_URL = "https://socket.antcloud.co:8000"
    const val ORIGIN = "https://antcloud.co"
    const val PRICING_URL = "https://antcloud.co/pricing"
    const val SIGNUP_URL = "https://antcloud.co/signup"

    /** Value of the `connection` socket query param; the server knows AntCloud's own client by it. */
    const val CONNECTION_TYPE = "Windows"

    /** Sunshine app name started on the VM. */
    const val HOST_APP = "Desktop"

    /**
     * This app's version: `0.<AntCloud release>.<our release>`, the same scheme as the other apps. 0.16.x
     * matches AntCloud's Windows app 0.016, whose API routes `ApiClient` uses.
     */
    const val APP_VERSION = "0.16.1"

    /** The AntCloud Windows client version this build matches, read from `APP_VERSION` (0.16.x → 0.016). */
    val matchedClientVersion: Double
        get() = APP_VERSION.split('.').getOrNull(1)?.toDoubleOrNull()?.div(1000) ?: 0.0

    const val APP_NAME = "AntCloud for Android"
    const val CREATOR_NAME = "Samyak Jain"
    const val CREATOR_GITHUB = "https://github.com/Sammy970"
    const val CREATOR_X = "https://x.com/Sammy_970"
    const val CREATOR_AVATAR = "https://github.com/Sammy970.png?size=240"
    const val MOONLIGHT_URL = "https://moonlight-stream.org"
    const val SOURCE_URL = "https://github.com/Sammy970/AntCloudAndroid"
}

/** App-wide context and preferences, set up once by the panel activity. */
object AntCloud {
    lateinit var context: Context
        private set

    val prefs: SharedPreferences by lazy { context.getSharedPreferences("antcloud", Context.MODE_PRIVATE) }

    fun init(context: Context) {
        if (!::context.isInitialized) this.context = context.applicationContext
    }
}

/**
 * Appends timestamped lines to the app's files/antcloud.log and logcat (tag "AntCloud"). Read it from a Mac
 * with `adb logcat -s AntCloud`.
 */
object Log {
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val file: File by lazy { File(AntCloud.context.filesDir, "antcloud.log") }

    fun info(message: String) {
        android.util.Log.i("AntCloud", message)
        synchronized(this) {
            runCatching {
                if (file.length() > 2_000_000) file.renameTo(File(file.path + ".1"))
                file.appendText("${format.format(Date())} $message\n")
            }
        }
    }
}
