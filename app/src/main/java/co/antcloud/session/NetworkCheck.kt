package co.antcloud.session

import co.antcloud.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * Estimates how a stream would feel without starting the cloud PC.
 *
 * Latency is the TCP handshake time to AntCloud's socket server. It sits in the same network (AS "ANTPLAY",
 * 103.182.64.0/23) as the cloud PCs, so its round trip is a close stand-in for theirs. AntCloud has no
 * speed-test endpoint, so download speed is measured against Cloudflare's public one: that's your internet
 * connection, not AntCloud's.
 */
class NetworkCheck {
    enum class Stage { IDLE, LATENCY, DOWNLOAD, DONE, FAILED }

    data class Result(
        val stage: Stage = Stage.IDLE,
        /** Round-trip samples in milliseconds, in the order they were measured. */
        val samples: List<Double> = emptyList(),
        val lost: Int = 0,
        val downloadMbps: Double? = null,
        /** Live download speed while the download runs. */
        val liveMbps: Double = 0.0,
        val failure: String? = null,
    ) {
        val isRunning get() = stage == Stage.LATENCY || stage == Stage.DOWNLOAD
        val medianLatency: Double? get() = samples.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        /** Mean difference between consecutive samples, the usual definition of jitter. */
        val jitter: Double? get() = if (samples.size > 1) samples.zipWithNext { a, b -> abs(b - a) }.average() else null
        val lossPercent: Double get() = if (samples.size + lost == 0) 0.0 else lost * 100.0 / (samples.size + lost)

        /**
         * The bitrate (kbps) a stream at this resolution and frame rate needs, capped so it leaves headroom on
         * the measured connection.
         */
        fun suggestedBitrate(resolution: String, fps: String): Int? {
            val mbps = downloadMbps ?: return null
            val at60 = mapOf("720" to 10.0, "1080" to 20.0, "1440" to 40.0, "2160" to 50.0)[resolution] ?: 20.0
            val needed = at60 * when (fps) { "30" -> 0.6; "120" -> 1.5; else -> 1.0 }
            val steps = listOf(5, 10, 20, 30, 40, 50)
            val target = steps.firstOrNull { it >= needed } ?: 50
            val affordable = steps.lastOrNull { it <= mbps * 0.6 } ?: 5
            return minOf(target, affordable) * 1000
        }
    }

    private val _result = MutableStateFlow(Result())
    val result: StateFlow<Result> = _result

    /**
     * Screenshot mode: shows a finished test with sample results (the emulator's virtual network adds
     * hundreds of milliseconds, so a real run there says nothing about the app).
     */
    fun preview() {
        val samples = listOf(41.0, 43.5, 44.2, 42.8, 45.1, 43.9, 44.6, 42.1, 46.3, 44.0, 43.2, 45.7, 44.8, 42.9, 43.6, 47.2, 44.1, 43.4, 45.0, 44.3)
        _result.value = Result(stage = Stage.DONE, samples = samples, downloadMbps = 321.0)
    }

    suspend fun run() {
        if (_result.value.isRunning) return
        var r = Result(stage = Stage.LATENCY)
        _result.value = r
        handshakeTime() // warm-up: DNS lookup and route setup
        repeat(SAMPLE_COUNT) {
            r = handshakeTime()?.let { ms -> r.copy(samples = r.samples + ms) } ?: r.copy(lost = r.lost + 1)
            _result.value = r
            delay(120)
        }
        if (r.samples.isEmpty()) {
            _result.value = r.copy(stage = Stage.FAILED, failure = "Couldn't reach AntCloud's servers. Check your internet connection.")
            return
        }
        r = r.copy(stage = Stage.DOWNLOAD)
        _result.value = r
        val mbps = measureDownload { live -> _result.value = _result.value.copy(liveMbps = live) }
        r = _result.value.copy(stage = Stage.DONE, downloadMbps = mbps)
        _result.value = r
        Log.info("[network] latency ${r.medianLatency} ms, jitter ${r.jitter} ms, loss ${r.lost}, download $mbps Mbps")
    }

    companion object {
        const val SAMPLE_COUNT = 20
        private const val LATENCY_HOST = "socket.antcloud.co"
        private const val LATENCY_PORT = 8000
        private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=50000000"

        /**
         * Time from starting a TCP connection to it being ready: one network round trip. Defaults to
         * AntCloud's socket server.
         */
        suspend fun handshakeTime(host: String = LATENCY_HOST, port: Int = LATENCY_PORT): Double? = withContext(Dispatchers.IO) {
            runCatching {
                // Resolve first, so the measurement is the handshake alone.
                val address = InetAddress.getByName(host)
                Socket().use { socket ->
                    val start = System.nanoTime()
                    socket.connect(InetSocketAddress(address, port), 2000)
                    (System.nanoTime() - start) / 1_000_000.0
                }
            }.getOrNull()
        }

        /**
         * Downloads for up to 8 seconds and returns the average speed after the first half second (which is
         * mostly connection setup and TCP ramp-up).
         */
        private suspend fun measureDownload(progress: (Double) -> Unit): Double? = withContext(Dispatchers.IO) {
            var bytes = 0L
            var warmupEnd = 0L
            val start = System.nanoTime()
            withTimeoutOrNull(8_000) {
                runCatching {
                    OkHttpClient().newCall(Request.Builder().url(DOWNLOAD_URL).build()).execute().use { response ->
                        val stream = response.body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            val now = System.nanoTime()
                            if (warmupEnd == 0L) {
                                if (now - start >= 500_000_000) warmupEnd = now
                                continue
                            }
                            bytes += read
                            val elapsed = (now - warmupEnd) / 1e9
                            if (elapsed > 0.2 && now - lastReport > 150_000_000) {
                                lastReport = now
                                progress(bytes * 8 / elapsed / 1e6)
                            }
                            if (!kotlinx.coroutines.currentCoroutineContext().isActiveSafe()) break
                        }
                    }
                }
            }
            if (warmupEnd == 0L || bytes == 0L) null else bytes * 8 / ((System.nanoTime() - warmupEnd) / 1e9) / 1e6
        }

        private fun kotlin.coroutines.CoroutineContext.isActiveSafe() = this[kotlinx.coroutines.Job]?.isActive ?: true
    }
}
