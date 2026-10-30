package co.antcloud.api

import co.antcloud.AntCloud
import co.antcloud.Config
import co.antcloud.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class UserProfile(json: JSONObject) {
    val firstName: String = json.optString("firstName").ifEmpty { "User" }
    val email: String = json.optString("email")
    val currentPlan: String = json.optString("currentPlan").ifEmpty { "basic" }
    val renewDate: OffsetDateTime? = runCatching { OffsetDateTime.parse(json.optString("renewDate")) }.getOrNull()
    val totalTimeMonth: Int = json.optInt("totalTimeMonth", 0)
    val timeUsedMonth: Int = json.optInt("timeUsedMonth", 0)

    /** "basic" means no paid plan; AntCloud's own client refuses to start a stream for it. */
    val hasPaidPlan get() = !currentPlan.equals("basic", ignoreCase = true)
    /** Night plans are identified by a plan code ending in "Nyt". */
    val isNightPlan get() = currentPlan.endsWith("nyt", ignoreCase = true)

    /** Readable plan name: "premiumNyt" → "Premium Night". */
    val planName: String
        get() {
            val base = if (isNightPlan) currentPlan.dropLast(3) else currentPlan
            val name = base.replaceFirstChar { it.uppercase() }
            return if (isNightPlan) "$name Night" else name
        }

    val initial get() = firstName.take(1).uppercase()
    val minutesLeft: Int? get() = if (totalTimeMonth > 0) maxOf(0, totalTimeMonth - timeUsedMonth) else null
    val daysLeft: Long? get() = renewDate?.let { ChronoUnit.DAYS.between(OffsetDateTime.now(), it) }

    val renewText: String
        get() = when (val d = daysLeft) {
            null -> ""
            in Long.MIN_VALUE..0 -> "Renews today"
            1L -> "Renews in 1 day"
            else -> "Renews in $d days"
        }

    companion object {
        fun duration(minutes: Int) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}

/**
 * Notices when AntCloud changes its service under this app: a newer AntCloud Windows client (which has meant
 * renamed routes), or a route the server says no longer exists. Either one shows the upstream banner.
 */
object UpstreamStatus {
    private val _newerClientVersion = MutableStateFlow<Double?>(null)
    val newerClientVersion: StateFlow<Double?> = _newerClientVersion
    private val _missingRoute = MutableStateFlow<String?>(null)
    val missingRoute: StateFlow<String?> = _missingRoute

    private const val DISMISSED_KEY = "dismissedUpstreamVersion"

    suspend fun check(api: ApiClient) {
        val latest = api.windowsClientVersion() ?: return
        val dismissed = AntCloud.prefs.getFloat(DISMISSED_KEY, 0f).toDouble()
        if (latest > Config.matchedClientVersion + 0.0001 && kotlin.math.abs(latest - dismissed) > 0.00001) {
            Log.info("[upstream] AntCloud's Windows client is $latest; this app matches ${Config.matchedClientVersion}")
            _newerClientVersion.value = latest
        }
    }

    fun markRouteMissing(route: String) {
        _missingRoute.value = route
    }

    /** Hides the version notice until AntCloud releases another one. A missing route stays visible. */
    fun dismissVersionNotice() {
        _newerClientVersion.value?.let { AntCloud.prefs.edit().putFloat(DISMISSED_KEY, it.toFloat()).apply() }
        _newerClientVersion.value = null
    }

    /** Screenshot mode can show the banner. */
    fun preview(version: Double?, route: String?) {
        _newerClientVersion.value = version
        _missingRoute.value = route
    }
}
