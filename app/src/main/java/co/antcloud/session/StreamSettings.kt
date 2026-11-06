package co.antcloud.session

import android.content.Context
import android.preference.PreferenceManager
import co.antcloud.AntCloud
import org.json.JSONObject

enum class AspectRatio(val label: String, val serverCode: String) {
    WIDE("16:9", "N"), TALL("16:10", "W"), ULTRAWIDE("21:9", "U");

    /** Pixel size for a vertical resolution, the same sizes AntCloud's client sets on the VM. */
    fun size(height: String): Pair<Int, Int> {
        val h = height.toIntOrNull() ?: 1080
        val wide = h * 16 / 9
        return when (this) {
            WIDE -> wide to h
            TALL -> wide to wide * 10 / 16
            ULTRAWIDE -> (mapOf(720 to 1680, 1080 to 2560, 1440 to 3440, 2160 to 5120)[h] ?: wide) to h
        }
    }
}

/** Stream settings, saved in the panel's preferences and copied into Moonlight's before each stream. */
data class StreamSettings(
    val resolution: String = "1080",
    val fps: String = "60",
    val bitrate: String = "20000",
    val aspect: AspectRatio = AspectRatio.WIDE,
    val hevc: Boolean = true,
    val stretch: Boolean = false,
) {
    /** "<res> <bitrate> <fps> 10 <aspect>" — sent as the socket `stream` query and with `setstream`. */
    val serverSpec get() = "$resolution $bitrate $fps 10 ${aspect.serverCode}"

    val pixelSize: String get() = aspect.size(resolution).let { (w, h) -> "$w × $h" }

    /** "1080p · 16:9 · 60 FPS · 20 Mbps · HEVC" */
    val summary: String
        get() {
            val res = if (resolution == "2160") "4K" else "${resolution}p"
            val mbps = (bitrate.toIntOrNull() ?: 0) / 1000
            return listOf(res, aspect.label, "$fps FPS", "$mbps Mbps", if (hevc) "HEVC" else "H.264").joinToString(" · ")
        }

    /** Upper bound of data use, from the bitrate (kbit/s → GB per hour). */
    val gigabytesPerHour: String
        get() = "%.1f".format((bitrate.toIntOrNull() ?: 0) * 1000.0 * 3600 / 8 / 1e9).removeSuffix(".0")

    /**
     * Writes these settings into Moonlight's own preferences, which its stream screen reads. Moonlight takes
     * any "WIDTHxHEIGHT", so 16:10 and 21:9 stream at their real size.
     */
    fun applyToMoonlight(context: Context) {
        val (w, h) = aspect.size(resolution)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("list_resolution", "${w}x$h")
            .putString("list_fps", fps)
            .putInt("seekbar_bitrate_kbps", bitrate.toIntOrNull() ?: 20000)
            // "auto" picks HEVC when the phone decodes it well; off means H.264 only.
            .putString("video_format", if (hevc) "auto" else "neverh265")
            // Moonlight's own "Stretch video to full-screen": the surface fills the view, so touch maps over all of it.
            .putBoolean("checkbox_stretch_video", stretch)
            .apply()
    }

    fun save() {
        AntCloud.prefs.edit().putString(KEY, JSONObject()
            .put("resolution", resolution).put("fps", fps).put("bitrate", bitrate)
            .put("aspect", aspect.name).put("hevc", hevc).put("stretch", stretch).toString()).apply()
    }

    companion object {
        private const val KEY = "streamSettings"

        fun load(): StreamSettings = runCatching {
            val json = JSONObject(AntCloud.prefs.getString(KEY, null)!!)
            StreamSettings(
                resolution = json.optString("resolution", "1080"),
                fps = json.optString("fps", "60"),
                bitrate = json.optString("bitrate", "20000"),
                aspect = AspectRatio.valueOf(json.optString("aspect", "WIDE")),
                hevc = json.optBoolean("hevc", true),
                stretch = json.optBoolean("stretch", false),
            )
        }.getOrDefault(StreamSettings())
    }
}
