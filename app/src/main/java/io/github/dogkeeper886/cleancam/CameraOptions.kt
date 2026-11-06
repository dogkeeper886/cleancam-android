package io.github.dogkeeper886.cleancam

import android.content.SharedPreferences
import org.json.JSONObject

/** The lens the stream uses: Auto lets the logical camera switch lenses as the zoom changes; the others hold one lens. */
enum class Lens(val key: String, val label: String) {
    AUTO("auto", "Auto"), ULTRA_WIDE("ultrawide", "Ultra wide"), WIDE("wide", "Wide"), TELE("tele", "Tele");

    companion object {
        fun of(key: String): Lens? = entries.firstOrNull { it.key == key }
    }
}

/** The stabilization the user picked: Auto turns it on while the phone shakes and off while it stands still. */
enum class Stabilization(val key: String, val label: String) {
    AUTO("auto", "Auto"), OFF("off", "Off"), STANDARD("standard", "Standard"), CINEMATIC("cinematic", "Cinematic");

    companion object {
        fun of(key: String): Stabilization? = entries.firstOrNull { it.key == key }
    }
}

/** The camera options the user picked in the settings sheet. The app keeps them for the next start. */
data class CameraOptions(
    val lens: Lens = Lens.AUTO,
    val stabilization: Stabilization = Stabilization.AUTO,
    /** Exposure compensation in EV; 0 leaves auto exposure as it is. */
    val exposureBias: Float = 0f,
    /** A fixed white balance in kelvins, or null for auto white balance. */
    val whiteBalance: Int? = null,
    /** Autofocus looks for near subjects first. */
    val nearFocus: Boolean = false,
    /** The highest bitrate the stream sends, or null for no limit. */
    val maxBitRate: Int? = null,
    /** The minutes the phone stays still and unused before the app turns itself off, or 0 to stay on. */
    val autoOffMinutes: Int = DEFAULT_AUTO_OFF_MINUTES,
) {
    fun save(prefs: SharedPreferences) {
        val json = JSONObject()
            .put("lens", lens.key)
            .put("stabilization", stabilization.key)
            .put("exposureBias", exposureBias.toDouble())
            .put("nearFocus", nearFocus)
            .put("autoOffMinutes", autoOffMinutes)
        whiteBalance?.let { json.put("whiteBalance", it) }
        maxBitRate?.let { json.put("maxBitRate", it) }
        prefs.edit().putString(PREF, json.toString()).apply()
    }

    companion object {
        private const val PREF = "options"
        /** The fixed white balances the settings sheet offers, in kelvins. */
        val WHITE_BALANCE_RANGE = 2500..8000
        /** The white balance a fixed white balance starts at, in kelvins: daylight. */
        const val DEFAULT_WHITE_BALANCE = 5000
        /** The exposure compensations the settings sheet offers, in EV. */
        const val EXPOSURE_BIAS_LIMIT = 2f
        /** The highest bitrates the settings sheet offers. */
        val MAX_BIT_RATES = listOf(20_000_000, 10_000_000, 6_000_000, 3_000_000)
        const val DEFAULT_AUTO_OFF_MINUTES = 5
        /** The auto-off times the settings sheet offers, in minutes; 0 stays on. */
        val AUTO_OFF_MINUTES = listOf(1, 5, 15, 30, 0)
        /** The longest auto-off time the debug port's `autooff` command takes, in minutes. */
        const val MAX_AUTO_OFF_MINUTES = 240

        /** The options the user picked last, or the defaults. */
        fun saved(prefs: SharedPreferences): CameraOptions = try {
            val json = JSONObject(prefs.getString(PREF, null) ?: return CameraOptions())
            CameraOptions(
                lens = Lens.of(json.optString("lens")) ?: Lens.AUTO,
                stabilization = Stabilization.of(json.optString("stabilization")) ?: Stabilization.AUTO,
                exposureBias = json.optDouble("exposureBias", 0.0).toFloat(),
                whiteBalance = if (json.has("whiteBalance")) json.getInt("whiteBalance") else null,
                nearFocus = json.optBoolean("nearFocus", false),
                maxBitRate = if (json.has("maxBitRate")) json.getInt("maxBitRate") else null,
                autoOffMinutes = json.optInt("autoOffMinutes", DEFAULT_AUTO_OFF_MINUTES),
            )
        } catch (e: Exception) {
            CameraOptions()
        }
    }
}
