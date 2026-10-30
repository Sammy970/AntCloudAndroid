package co.antcloud

import co.antcloud.auth.AuthStore
import co.antcloud.session.NetworkCheck
import co.antcloud.session.SessionService
import co.antcloud.session.StreamSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The panel's state for the life of the process, so a running session (and its socket) survives the
 * activity being recreated, e.g. on rotation or while Moonlight's stream screen is in front.
 */
object AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val auth by lazy { AuthStore(scope) }
    val session by lazy { SessionService(auth.api, scope).also { it.onSessionExpired = { auth.logout() } } }
    val network by lazy { NetworkCheck() }

    private val _settings by lazy { MutableStateFlow(StreamSettings.load()) }
    val settings: StateFlow<StreamSettings> get() = _settings

    fun updateSettings(change: (StreamSettings) -> StreamSettings) {
        _settings.value = change(_settings.value).also { it.save() }
    }
}
