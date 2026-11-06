package io.github.dogkeeper886.cleancam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 or HEVC encoder fed through an input surface. Hands out each frame as an Annex-B byte stream, which
 * ffmpeg reads as raw H.264 or HEVC, with the parameter sets in front of every keyframe so a viewer can join at any
 * keyframe. The stream has the camera mode's landscape size, so the PC's webcam keeps its size as the phone turns.
 */
class VideoEncoder(private val listener: Listener) {
    interface Listener {
        fun onEncoded(data: ByteArray, isKeyframe: Boolean)
        fun onEvent(event: Map<String, Any?>)
    }

    private val thread = HandlerThread("cleancam.encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    private var codec: MediaCodec? = null
    var surface: Surface? = null
        private set
    var mime = MediaFormat.MIMETYPE_VIDEO_AVC
        private set
    var width = 1920
        private set
    var height = 1080
        private set
    /** The camera's top frame rate; rate control and keyframes plan around it. */
    var frameRate = 30
        private set
    /** The bitrate the encoder aims for now, at or below the target. */
    var bitRate = TARGET_AT_30FPS
        private set
    /** Set by the debug port's `bitrate` command; adaptation leaves the bitrate alone until [unpin]. */
    var isPinned = false
        private set
    private var limitShare = 1.0
    private var limitBitRate: Int? = null
    private var secondsWithoutBacklog = 0
    @Volatile private var parameterSets: ByteArray? = null
    // Counted on the encoder's thread, without the lock that configure holds while it stops the codec.
    private val outputBytes = AtomicLong()
    private val outputFrames = AtomicInteger()
    private val outputKeyframes = AtomicInteger()

    /** Whether a running codec already encodes `mime` at `width` x `height` and `frameRate`. */
    fun matches(mime: String, width: Int, height: Int, frameRate: Int): Boolean = synchronized(lock) {
        codec != null && mime == this.mime && width == this.width && height == this.height && frameRate == this.frameRate
    }

    /**
     * Encodes `mime` at `width` x `height` and `frameRate` from now on, in a new codec with a new input surface. Returns whether anything
     * changed; the caller hands the new [surface] to the renderer.
     */
    fun configure(mime: String, width: Int, height: Int, frameRate: Int): Boolean = synchronized(lock) {
        if (codec != null && mime == this.mime && width == this.width && height == this.height && frameRate == this.frameRate) {
            return false
        }
        // The codec plans its keyframe interval around the frame rate it starts with, so a new rate needs a new codec.
        val rateChanged = width != this.width || height != this.height || frameRate != this.frameRate
        this.mime = mime
        this.width = width
        this.height = height
        this.frameRate = frameRate
        if (rateChanged && !isPinned) bitRate = targetBitRate
        secondsWithoutBacklog = 0
        releaseCodec()
        startCodec()
        true
    }

    /** Plans rate control and keyframes around the camera's top frame rate. */
    fun setFrameRate(rate: Int) = synchronized(lock) {
        if (rate <= 0 || rate == frameRate) return
        frameRate = rate
        if (!isPinned) bitRate = targetBitRate
        secondsWithoutBacklog = 0
        applyBitRate("frameRate")
    }

    /** Aims for `share` of the usual target, at most `maxBitRate`, and restarts the bitrate at the new target. */
    fun setLimit(share: Double, maxBitRate: Int?) = synchronized(lock) {
        if (share == limitShare && maxBitRate == limitBitRate) return
        limitShare = share
        limitBitRate = maxBitRate
        if (isPinned) return
        bitRate = targetBitRate
        secondsWithoutBacklog = 0
        applyBitRate("limit")
    }

    /** Holds the bitrate at `rate` until [unpin], for the debug port. */
    fun pin(rate: Int) = synchronized(lock) {
        isPinned = true
        bitRate = rate
        applyBitRate("pin")
    }

    /** Hands the bitrate back to adaptation, from where it is. */
    fun unpin() = synchronized(lock) {
        isPinned = false
        secondsWithoutBacklog = 0
    }

    /**
     * Called once a second with the most sends any client had waiting. Steps the bitrate down while a client falls
     * behind, and back up after [RECOVERY_SECONDS] without a backlog.
     */
    fun adapt(backlog: Int) = synchronized(lock) {
        if (isPinned) return
        var next = bitRate
        if (backlog > CONGESTED_BACKLOG) {
            secondsWithoutBacklog = 0
            next = maxOf((bitRate * (1 - STEP)).toInt(), (targetBitRate * FLOOR_SHARE).toInt())
        } else if (backlog > 0) {
            secondsWithoutBacklog = 0
        } else {
            secondsWithoutBacklog++
            if (secondsWithoutBacklog < RECOVERY_SECONDS) return
            secondsWithoutBacklog = 0
            // One step up undoes one step down.
            next = minOf((bitRate / (1 - STEP)).toInt(), targetBitRate)
        }
        if (next == bitRate) return
        val reason = if (next < bitRate) "down" else "up"
        bitRate = next
        applyBitRate(reason)
    }

    fun requestKeyframe() = synchronized(lock) {
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    /** The bytes, frames and keyframes handed out since the last call. */
    fun takeOutput(): Triple<Long, Int, Int> =
        Triple(outputBytes.getAndSet(0), outputFrames.getAndSet(0), outputKeyframes.getAndSet(0))

    fun release() = synchronized(lock) { releaseCodec() }

    private val targetBitRate: Int
        get() {
            val usual = (TARGET_AT_30FPS.toLong() * frameRate / 30 * (width.toLong() * height) / PIXELS_AT_1080P).toInt()
            return minOf((usual * limitShare).toInt(), limitBitRate ?: usual)
        }

    private fun applyBitRate(reason: String) {
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate) })
        listener.onEvent(mapOf("event" to "rates", "reason" to reason, "bitrate" to bitRate, "fps" to frameRate,
            "codec" to codecName(mime), "width" to width, "height" to height))
    }

    private fun startCodec() {
        val info = hardwareEncoder(mime) ?: return
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // A keyframe every 2 s, also while the camera slows in the dark.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // No B-frames, less delay.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            val capabilities = info.getCapabilitiesForType(mime)
            if (capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (mime == MediaFormat.MIMETYPE_VIDEO_AVC &&
                capabilities.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
            ) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
        }
        val codec = MediaCodec.createByCodecName(info.name)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buffer = try { codec.getOutputBuffer(index) } catch (e: IllegalStateException) { return }
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        parameterSets = bytes
                    } else {
                        emit(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
                try { codec.releaseOutputBuffer(index, false) } catch (_: IllegalStateException) {}
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                listener.onEvent(mapOf("event" to "encoderError", "message" to e.diagnosticInfo))
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        this.codec = codec
        parameterSets = null
        listener.onEvent(mapOf("event" to "rates", "reason" to "start", "bitrate" to bitRate, "fps" to frameRate,
            "codec" to codecName(mime), "encoder" to info.name, "width" to width, "height" to height))
    }

    private fun releaseCodec() {
        codec?.let { old ->
            // The codec's callbacks run on the encoder's thread, and one may be copying an output buffer whose memory
            // release() frees: a preset switch crashed there. Released on that thread, the codec waits for the copy.
            val released = CountDownLatch(1)
            handler.post {
                try { old.stop() } catch (_: Exception) {}
                old.release()
                released.countDown()
            }
            released.await(RELEASE_WAIT_S, TimeUnit.SECONDS)
        }
        codec = null
        surface?.release()
        surface = null
    }

    /** Puts the parameter sets in front of a keyframe that lacks them. */
    private fun emit(frame: ByteArray, isKeyframe: Boolean) {
        val sets = parameterSets
        val output = if (isKeyframe && sets != null && !startsWithParameterSet(frame)) sets + frame else frame
        outputBytes.addAndGet(output.size.toLong())
        outputFrames.incrementAndGet()
        if (isKeyframe) outputKeyframes.incrementAndGet()
        listener.onEncoded(output, isKeyframe)
    }

    private fun startsWithParameterSet(frame: ByteArray): Boolean {
        val start = when {
            frame.size > 4 && frame[0].toInt() == 0 && frame[1].toInt() == 0 && frame[2].toInt() == 0 && frame[3].toInt() == 1 -> 4
            frame.size > 3 && frame[0].toInt() == 0 && frame[1].toInt() == 0 && frame[2].toInt() == 1 -> 3
            else -> return false
        }
        val header = frame[start].toInt() and 0xFF
        // H.264 SPS is NAL type 7; HEVC VPS is NAL type 32.
        return if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) (header shr 1) and 0x3F == 32 else header and 0x1F == 7
    }

    companion object {
        /** The target bitrate at 1920x1080 and 30 fps; the target scales with the frame rate and the pixels. */
        const val TARGET_AT_30FPS = 10_000_000
        private const val PIXELS_AT_1080P = 1920L * 1080
        /** A backlog past this many sends, of the server's 30, means the link carries less than the stream sends. */
        private const val CONGESTED_BACKLOG = 10
        /** Seconds without any backlog before the bitrate steps back up. */
        private const val RECOVERY_SECONDS = 10
        /** Each step changes the bitrate by this share. */
        private const val STEP = 0.2
        /** The lowest share of the target the bitrate steps down to. */
        private const val FLOOR_SHARE = 0.2
        /** How long a release waits for the encoder's thread before it goes on. */
        private const val RELEASE_WAIT_S = 3L

        fun codecName(mime: String) = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "hevc" else "h264"

        /** The phone's hardware encoder for `mime`, such as c2.exynos.hevc.encoder on a Pixel 8. */
        fun hardwareEncoder(mime: String): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstOrNull()

        val hasHevcEncoder: Boolean by lazy { hardwareEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)?.isHardwareAccelerated == true }
    }
}
