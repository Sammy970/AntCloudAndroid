package co.antcloud.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import co.antcloud.AntCloud
import co.antcloud.Log
import co.antcloud.api.ApiClient
import co.antcloud.api.UpstreamStatus
import co.antcloud.api.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the refresh token, encrypted with an AES key that lives in the Android Keystore and never leaves it.
 * The ciphertext sits in the app's private preferences.
 */
object SecretStore {
    private const val ALIAS = "antcloud-secrets"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun set(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        AntCloud.prefs.edit().putString("secret.$name", Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? = runCatching {
        val sealed = Base64.decode(AntCloud.prefs.getString("secret.$name", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }.getOrNull()

    fun delete(name: String) {
        AntCloud.prefs.edit().remove("secret.$name").apply()
    }
}

class AuthStore(private val scope: CoroutineScope) {
    enum class State { RESTORING, LOGGED_OUT, LOGGED_IN }

    private val _state = MutableStateFlow(State.RESTORING)
    val state: StateFlow<State> = _state
    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile

    val api = ApiClient()

    init {
        api.refreshHandler = { refreshAccessToken() }
    }

    /** Screenshot mode: shows the signed-in UI with a fake profile, without touching stored tokens. */
    fun previewSignIn(profile: UserProfile?) {
        _profile.value = profile
        _state.value = if (profile == null) State.LOGGED_OUT else State.LOGGED_IN
    }

    suspend fun restore() {
        // Checks in the background whether AntCloud has shipped a newer client (and maybe new API routes).
        scope.launch { UpstreamStatus.check(api) }
        if (SecretStore.get(REFRESH) == null || refreshAccessToken() == null) {
            _state.value = State.LOGGED_OUT
            return
        }
        finishLogin()
    }

    suspend fun login(email: String, password: String) = adopt(api.login(email, password))

    suspend fun sendOtp(phone: String) = api.sendOtp(normalizedPhone(phone))

    suspend fun verifyOtp(phone: String, otp: String) = adopt(api.phoneLogin(normalizedPhone(phone), otp))

    suspend fun forgotPassword(email: String) = api.forgotPassword(email)

    suspend fun reloadProfile() {
        runCatching { api.me() }
            .onSuccess { _profile.value = it }
            .onFailure { Log.info("[auth] profile reload failed: ${it.message}") }
    }

    fun logout() {
        SecretStore.delete(REFRESH)
        api.accessToken = null
        _profile.value = null
        _state.value = State.LOGGED_OUT
    }

    private suspend fun adopt(tokens: ApiClient.Tokens) {
        api.accessToken = tokens.access
        tokens.refresh?.let { SecretStore.set(REFRESH, it) }
        finishLogin()
    }

    private suspend fun finishLogin() {
        reloadProfile()
        _state.value = State.LOGGED_IN
    }

    private suspend fun refreshAccessToken(): String? {
        val refresh = SecretStore.get(REFRESH) ?: return null
        return runCatching { api.refresh(refresh) }
            .onFailure { Log.info("[auth] refresh failed: ${it.message}") }
            .getOrNull()
            ?.let { tokens ->
                api.accessToken = tokens.access
                tokens.refresh?.let { SecretStore.set(REFRESH, it) }
                tokens.access
            }
    }

    companion object {
        private const val REFRESH = "refreshToken"

        /** The API expects E.164; like AntCloud's client, bare Indian numbers get a +91 prefix. */
        fun normalizedPhone(input: String): String {
            val trimmed = input.filter { !it.isWhitespace() && it != '-' }
            if (trimmed.startsWith("+")) return trimmed
            var digits = trimmed
            if (digits.length == 11 && digits.startsWith("0")) digits = digits.drop(1)
            if (digits.length == 12 && digits.startsWith("91")) digits = digits.drop(2)
            return "+91$digits"
        }
    }
}
