package io.github.dogkeeper886.cleancam

import android.os.PowerManager

/**
 * How far the heat guard lowers the stream. On battery it caps the frame rate; as the phone heats up it also drops 4K,
 * the bitrate and stabilization, which all heat the phone.
 */
enum class HeatLevel(val label: String?) {
    NONE(null), BATTERY("Battery"), HOT("Hot"), CRITICAL("Too hot");

    /** The highest frame rate the camera may run at, or null for the preset's. */
    val topFrameRate: Int? get() = if (this == NONE) null else 30
    /** The tallest camera mode, or null for the preset's. */
    val maxHeight: Int? get() = if (this >= HOT) 1080 else null
    /** The share of its usual target the bitrate aims for. */
    val bitRateShare: Double get() = if (this == HOT) 0.7 else 1.0
    /** The highest bitrate, or null for no limit. */
    val maxBitRate: Int? get() = if (this == CRITICAL) 6_000_000 else null
    val allowsStabilization: Boolean get() = this < HOT

    companion object {
        /** Android's moderate throttling stands for iOS's serious state, and severe or worse for critical. */
        fun of(thermalStatus: Int, pluggedIn: Boolean): HeatLevel = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> CRITICAL
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> HOT
            !pluggedIn -> BATTERY
            else -> NONE
        }
    }
}
