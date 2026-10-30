package co.antcloud.stream

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import co.antcloud.Config
import co.antcloud.Log
import co.antcloud.api.ApiClient
import co.antcloud.api.ApiException
import co.antcloud.session.SessionService
import co.antcloud.session.StreamDriver
import co.antcloud.session.StreamSettings
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/**
 * Drives the Moonlight that this app is built on: adds the cloud PC as a host, pairs with a PIN the app
 * makes up (sending it to AntCloud at the same time, so Sunshine on the VM accepts it — the same handshake
 * AntCloud's own client uses), then opens Moonlight's stream screen on the "Desktop" app.
 */
class MoonlightBridge(private val activity: Activity) : StreamDriver {
    @Volatile private var binder: ComputerManagerService.ComputerManagerBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as ComputerManagerService.ComputerManagerBinder
            // Waiting blocks, so it happens off the main thread, as in Moonlight's own PcView.
            Thread {
                local.waitForReady()
                binder = local
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    fun bind() {
        activity.bindService(Intent(activity, ComputerManagerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        runCatching { activity.unbindService(connection) }
        binder = null
    }

    override suspend fun launch(host: String, settings: StreamSettings, session: SessionService) {
        val manager = withTimeout(15_000) {
            while (binder == null) delay(100)
            binder!!
        }

        val computer = withContext(Dispatchers.IO) {
            Log.info("[moonlight] adding $host")
            val details = ComputerDetails().apply { manualAddress = ComputerDetails.AddressTuple(host, NvHTTP.DEFAULT_HTTP_PORT) }
            if (!manager.addComputerBlocking(details)) {
                throw ApiException(0, "Couldn't reach your cloud PC at $host. It may still be starting; try again in a moment.")
            }
            manager.getComputer(details.uuid) ?: details
        }

        if (computer.pairState != PairingManager.PairState.PAIRED) {
            pair(computer, manager, session.api)
        } else {
            Log.info("[moonlight] $host already paired")
        }

        val ready = withContext(Dispatchers.IO) {
            val current = manager.getComputer(computer.uuid) ?: computer
            val http = NvHTTP(ServerHelper.getCurrentAddressFromComputer(current), current.httpsPort,
                manager.uniqueId, current.serverCert, PlatformBinding.getCryptoProvider(activity))
            val apps = http.appList
            val app = apps.firstOrNull { it.appName.equals(Config.HOST_APP, ignoreCase = true) } ?: apps.firstOrNull()
                ?: throw ApiException(0, "Your cloud PC isn't offering anything to stream yet. Try again in a moment.")
            current to app
        }

        settings.applyToMoonlight(activity)
        val (current, app) = ready
        Log.info("[moonlight] streaming \"${app.appName}\" from $host at ${settings.summary}")
        ServerHelper.doStart(activity, app, current, manager)
    }

    /** Pairs with a random PIN while handing the same PIN to AntCloud until Sunshine accepts it. */
    private suspend fun pair(computer: ComputerDetails, manager: ComputerManagerService.ComputerManagerBinder, api: ApiClient) =
        coroutineScope {
            val pin = "%04d".format(Random.nextInt(1000, 10000))
            val submit = launch {
                delay(2000)
                while (isActive) {
                    if (runCatching { api.vmAuth(pin) }.getOrDefault(false)) return@launch
                    delay(3000)
                }
            }
            try {
                val state = withContext(Dispatchers.IO) {
                    Log.info("[moonlight] pairing")
                    val http = NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        manager.uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(activity))
                    val pm = http.pairingManager
                    val result = pm.pair(http.getServerInfo(true), pin)
                    if (result == PairingManager.PairState.PAIRED) {
                        // Pin this certificate for later HTTPS use, as Moonlight's own pairing does.
                        manager.getComputer(computer.uuid)?.serverCert = pm.pairedCert
                        manager.invalidateStateForComputer(computer.uuid)
                    }
                    result
                }
                Log.info("[moonlight] pairing finished: $state")
                when (state) {
                    PairingManager.PairState.PAIRED -> Unit
                    PairingManager.PairState.PIN_WRONG -> throw ApiException(0, "Your cloud PC didn't accept the pairing code. Try launching again.")
                    PairingManager.PairState.ALREADY_IN_PROGRESS -> throw ApiException(0, "Pairing is already in progress. Wait a moment and try again.")
                    else -> throw ApiException(0, "Couldn't pair with your cloud PC. Try launching again.")
                }
            } finally {
                submit.cancel()
            }
        }
}
