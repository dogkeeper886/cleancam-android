package io.github.dogkeeper886.cleancam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.sqrt

/**
 * Tells a phone that stands still from one that shakes, with the gyro, for auto stabilization. Each second counts as
 * still or shaking by its fastest rotation, and 5 such seconds in a row change the state.
 */
class MotionDetector(context: Context) : SensorEventListener {
    enum class State(val key: String) { UNKNOWN("unknown"), STILL("still"), SHAKING("shaking") }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val lock = Any()
    private var peak = 0.0
    private var runState = State.UNKNOWN
    private var runSeconds = 0
    private var active = false

    var state = State.UNKNOWN
        private set

    /** Reads the gyro ten times a second on [handler]. */
    fun start(handler: Handler) {
        if (active || gyro == null) return
        active = sensors.registerListener(this, gyro, 100_000, handler)
    }

    fun stop() {
        sensors.unregisterListener(this)
        active = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        val rate = sqrt(x * x + y * y + z * z)
        synchronized(lock) { if (rate > peak) peak = rate }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Called once a second. Returns the fastest rotation since the last call, and whether [state] changed. */
    fun takeSecond(): Pair<Double, Boolean> {
        val second = synchronized(lock) { peak.also { peak = 0.0 } }
        val kind = when {
            second < STILL_PEAK -> State.STILL
            second > SHAKING_PEAK -> State.SHAKING
            else -> State.UNKNOWN
        }
        if (kind == runState) runSeconds++ else { runState = kind; runSeconds = 1 }
        if (!active || kind == State.UNKNOWN || runSeconds < SECONDS || kind == state) return second to false
        state = kind
        return second to true
    }

    companion object {
        /** A second whose fastest rotation stays under this, in radians per second, counts as still: about 3° a second. */
        const val STILL_PEAK = 0.05
        /** A second whose fastest rotation passes this counts as shaking: about 9° a second, as in a hand. */
        private const val SHAKING_PEAK = 0.15
        private const val SECONDS = 5
    }
}
