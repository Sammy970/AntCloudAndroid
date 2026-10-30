package co.antcloud.api

import co.antcloud.Config
import co.antcloud.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : Exception(message)

/**
 * Thin client for https://api.antcloud.co/api. Responses are loosely shaped, so JSON is handled as
 * `JSONObject` rather than strict models.
 *
 * Routes match AntCloud's Windows client v0.016 (`Config.matchedClientVersion`); that release renamed every
 * route, see re/FINDINGS.md. A route AntCloud removes answers Express's "Cannot GET /api/…" 404, which
 * `send` reports as a service change rather than a plain error.
 */
class ApiClient {
    @Volatile var accessToken: String? = null
    /** Called once on a 401 to obtain a new access token; returns null if refresh failed. */
    var refreshHandler: (suspend () -> String?)? = null

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    // Auth

    suspend fun login(email: String, password: String) =
        tokens(send("POST", "login", JSONObject().put("user", email).put("auth", password)))

    suspend fun sendOtp(phone: String) {
        send("POST", "phoneNumber/otp", JSONObject().put("mobile", phone).put("register", false))
    }

    suspend fun phoneLogin(phone: String, otp: String) =
        tokens(send("POST", "phoneLogin", JSONObject().put("mobile", phone).put("verificationToken", otp)))

    suspend fun forgotPassword(email: String) {
        send("POST", "users/forgot-password", JSONObject().put("email", email))
    }

    suspend fun refresh(refreshToken: String): Tokens {
        val json = send("GET", "users/refreshToken", headers = mapOf("refresh" to "JWT $refreshToken")) as? JSONObject
        val access = json?.optString("accessToken").orEmpty()
        if (access.isEmpty()) throw ApiException(0, "Session expired. Please log in again.")
        return Tokens(access, json?.optString("refreshToken")?.ifEmpty { null })
    }

    suspend fun me(): UserProfile {
        val json = send("GET", "myself", authorized = true) as? JSONObject
        // An expired or unknown token gets 200 {"user": null} rather than an error.
        val user = json?.optJSONObject("user") ?: throw ApiException(401, "Session expired. Please log in again.")
        return UserProfile(user)
    }

    // VM

    suspend fun vmIp(): String {
        val ip = (send("GET", "vm/getControllVmIp", authorized = true) as? JSONObject)?.optString("vmip").orEmpty()
        if (ip.isEmpty()) throw ApiException(0, "VM IP not available yet.")
        return ip
    }

    /** Hands the Moonlight pairing PIN to the backend, which enters it into Sunshine on the VM. */
    suspend fun vmAuth(pin: String): Boolean {
        return when (val json = send("POST", "vm/vmAuthentication", JSONObject().put("code", pin), authorized = true)) {
            is Boolean -> json
            is JSONObject -> {
                // Current server shape: {"message":"success","data":{"status":true|false}}
                json.optJSONObject("data")?.opt("status") as? Boolean
                    ?: listOf("success", "accepted", "result", "ok").firstNotNullOfOrNull { json.opt(it) as? Boolean }
                    ?: false
            }
            else -> false
        }
    }

    suspend fun connectionStatus(): Boolean =
        (send("GET", "vm/checkVmConnectionStatus", authorized = true) as? JSONObject)?.optBoolean("connected") ?: false

    /** Latest version of AntCloud's Windows client; a rise past ours has so far meant renamed API routes. */
    suspend fun windowsClientVersion(): Double? = runCatching {
        val json = send("GET", "windows/version") as? JSONObject
        when (val v = json?.opt("version")) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }.getOrNull()

    /** Server clock (IST), used for night-plan windows. */
    suspend fun serverTime(): OffsetDateTime? = runCatching {
        val s = (send("GET", "realtime") as? JSONObject)?.optString("time")
        OffsetDateTime.parse(s)
    }.getOrNull()

    // Transport

    private suspend fun send(
        method: String, path: String, body: JSONObject? = null,
        headers: Map<String, String> = emptyMap(), authorized: Boolean = false, isRetry: Boolean = false,
    ): Any? {
        val (status, text) = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(Config.API_BASE + path).apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (authorized) accessToken?.let { header("Authorization", "JWT $it") }
                method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            }.build()
            http.newCall(request).execute().use { it.code to it.body.string() }
        }
        val json = runCatching { JSONTokener(text).nextValue() }.getOrNull()

        // Poll endpoints are logged only when something is off; auth bodies are never logged.
        // checkVmConnectionStatus answers 400 {"connected":false} until Moonlight connects — expected, so not logged.
        if (path != "vm/checkVmConnectionStatus") {
            val preview = if (AUTH_PATHS.any { path.startsWith(it) }) "" else " " + text.take(300)
            Log.info("[api] $method $path → $status$preview")
        }

        if (status == 404 && text.contains("<pre>Cannot $method /api/")) {
            Log.info("[api] $method $path no longer exists on AntCloud's server")
            UpstreamStatus.markRouteMissing("$method $path")
            throw ApiException(status, "AntCloud changed their service, so this no longer works. This app needs an update.")
        }

        val refresh = refreshHandler
        if (status == 401 && authorized && !isRetry && refresh != null) {
            refresh()?.let { fresh ->
                accessToken = fresh
                return send(method, path, body, headers, authorized = true, isRetry = true)
            }
        }
        if (status !in 200..299) throw ApiException(status, errorMessage(json) ?: "Request failed ($status).")
        return json
    }

    data class Tokens(val access: String, val refresh: String?)

    private fun tokens(json: Any?): Tokens {
        val root = json as? JSONObject
        val user = root?.optJSONObject("user")
        val access = root?.optString("token")?.ifEmpty { null } ?: user?.optString("token")?.ifEmpty { null }
            ?: throw ApiException(0, "Login response did not include a token.")
        val refresh = root?.optString("refreshToken")?.ifEmpty { null } ?: user?.optString("refreshToken")?.ifEmpty { null }
        return Tokens(access, refresh)
    }

    private fun errorMessage(json: Any?): String? {
        val dict = json as? JSONObject ?: return null
        (dict.opt("errors") as? JSONArray)?.let { errors ->
            val messages = (0 until errors.length()).mapNotNull { errors.optJSONObject(it)?.optString("message")?.ifEmpty { null } }
            if (messages.isNotEmpty()) return messages.joinToString("\n")
        }
        // {"error":"Validation Error!","message":"Please Enter Valid Phone Number"}: the message is the useful part.
        return dict.optString("message").ifEmpty { null } ?: dict.optString("error").ifEmpty { null }
    }

    private companion object {
        /** Requests whose bodies (tokens, profile) are never logged. */
        val AUTH_PATHS = listOf("login", "phoneLogin", "phoneNumber/", "myself", "users/")
    }
}
