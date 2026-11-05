package io.github.dogkeeper886.cleancam

import android.content.SharedPreferences

/**
 * What the user streams for. Each preset sets the frame rates auto exposure moves between, the size and the codec; the
 * encoder's bitrate and keyframes follow the top rate and the size.
 */
enum class Preset(val key: String, val label: String, val summary: String, val minRate: Int, val maxRate: Int) {
    CALL("call", "Call", "20–30 fps, for Zoom, Meet and Teams, which send 30. Slows in a dim room.", 20, 30),
    LOW_LIGHT("lowlight", "Low light", "15–30 fps. Slows further in the dark for a brighter face, but moves less smoothly.", 15, 30),
    OBS("obs", "OBS", "30–60 fps at up to 20 Mbps, for apps that keep every frame, such as OBS. A phone without a 60 fps mode stays at 30.", 30, 60),
    WIFI("wifi", "WiFi", "20–30 fps in HEVC, which carries a better picture in the same 10 Mbps over a WiFi link.", 20, 30),
    UHD("4k", "4K", "3840x2160 at 20–30 fps and up to 40 Mbps, for OBS over the USB cable. The video app has to pick the camera again after a switch to or from 4K.", 20, 30);

    /** The camera mode's height: 2160 for 4K, and 1080 otherwise. */
    val height: Int get() = if (this == UHD) 2160 else 1080

    /** HEVC instead of H.264, where the phone has an HEVC encoder; the receiver detects the codec as it connects. */
    val prefersHevc: Boolean get() = this == WIFI

    fun save(prefs: SharedPreferences) = prefs.edit().putString(PREF, key).apply()

    companion object {
        private const val PREF = "preset"

        fun of(key: String): Preset? = entries.firstOrNull { it.key == key }

        /** The preset the user picked last, or Call. */
        fun saved(prefs: SharedPreferences): Preset = prefs.getString(PREF, null)?.let { of(it) } ?: CALL
    }
}
