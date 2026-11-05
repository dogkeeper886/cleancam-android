package io.github.dogkeeper886.cleancam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper

/**
 * Plays the PC's speaker audio on the phone's loudspeaker and records its microphone as a voice call does, so the
 * phone's echo canceller removes the loudspeaker's sound from the microphone.
 */
class CallAudio(private val context: Context) {
    /** Called on the microphone's thread with 48 kHz mono 16-bit PCM. */
    var onMicPcm: ((ByteArray) -> Unit)? = null
    @Volatile var micEnabled = true

    private val audio = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    // Guarded by lock.
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var leftover: Byte? = null
    private var running = false

    private val routes = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = chooseRoute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = chooseRoute()
    }
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        // A phone call takes the audio mode; the call's end hands it back.
        if (mode == AudioManager.MODE_NORMAL && synchronized(lock) { running }) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            chooseRoute()
        }
    }

    /** Starts the loudspeaker and, when allowed, the microphone; returns whether the microphone runs. */
    @SuppressLint("MissingPermission")
    fun start(micAllowed: Boolean): Boolean = synchronized(lock) {
        if (running) return record != null
        running = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.registerAudioDeviceCallback(routes, Handler(Looper.getMainLooper()))
        audio.addOnModeChangedListener(context.mainExecutor, modeListener)
        chooseRoute()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        framesWritten = 0
        leftover = null

        if (micAllowed) {
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                CHUNK_BYTES * 4,
            )
            val recorder = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (e: Exception) {
                null
            }
            if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                }
                recorder.startRecording()
                record = recorder
                Thread({ recordLoop(recorder) }, "cleancam.mic").start()
            } else {
                recorder?.release()
            }
        }
        record != null
    }

    fun stop() = synchronized(lock) {
        if (!running) return
        running = false
        record?.let { it.stop(); it.release() }
        record = null
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        track?.let { it.pause(); it.flush(); it.release() }
        track = null
        audio.unregisterAudioDeviceCallback(routes)
        audio.removeOnModeChangedListener(modeListener)
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }

    /**
     * Queues 48 kHz mono 16-bit PCM from the PC for the loudspeaker. Silence goes first when the queue ran dry, to
     * absorb uneven delivery, and at most [MAX_QUEUED_FRAMES] wait, so the delay stays short after a burst.
     */
    fun play(data: ByteArray, length: Int) = synchronized(lock) {
        val track = track ?: return
        // TCP can split a 16-bit sample; the odd byte waits for the next chunk.
        val bytes = leftover?.let { byteArrayOf(it) + data.copyOf(length) } ?: data.copyOf(length)
        leftover = if (bytes.size % 2 == 1) bytes.last() else null
        val received = bytes.size / 2
        if (received == 0) return
        var queued = framesWritten - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        if (queued <= 0) {
            framesWritten += track.write(ByteArray(PREBUFFER_FRAMES * 2), 0, PREBUFFER_FRAMES * 2, AudioTrack.WRITE_NON_BLOCKING) / 2
            queued = PREBUFFER_FRAMES.toLong()
        }
        // Keep the newest sound that fits; what arrives late in a burst is stale.
        val frames = minOf(received.toLong(), MAX_QUEUED_FRAMES - queued).toInt()
        if (frames <= 0) return
        val offset = (received - frames) * 2
        framesWritten += track.write(bytes, offset, frames * 2, AudioTrack.WRITE_NON_BLOCKING) / 2
    }

    private fun recordLoop(recorder: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (synchronized(lock) { record === recorder }) {
            val count = recorder.read(buffer, 0, buffer.size)
            if (count > 0 && micEnabled) onMicPcm?.invoke(buffer.copyOf(count))
        }
    }

    /** Plays on a headset where one is connected, and on the loudspeaker otherwise, not the earpiece. */
    private fun chooseRoute() {
        val devices = audio.availableCommunicationDevices
        val headset = devices.firstOrNull { it.type in HEADSETS }
        val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        (headset ?: speaker)?.let { audio.setCommunicationDevice(it) }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        /** 10 ms of microphone sound. */
        const val CHUNK_BYTES = 960
        /** Silence played before the first sound, and after the queue runs dry: 40 ms. */
        const val PREBUFFER_FRAMES = 1_920
        /** At most this much is queued: 80 ms. */
        const val MAX_QUEUED_FRAMES = 3_840L
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
    }
}
