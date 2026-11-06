package io.github.dogkeeper886.cleancam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Range
import android.util.Size
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Runs the back camera, shows it on the phone, and streams it as H.264, or HEVC in the WiFi preset, to whoever connects
 * to [PORT], and the microphone as raw PCM to whoever connects to [AUDIO_PORT]. Plays the raw PCM a PC sends to
 * [SPEAKER_PORT] on the loudspeaker. Reports its stream to whoever connects to [DEBUG_PORT], one JSON line a second and
 * one per event, and takes commands there. Turns the app off through [onAutoOff] once the phone stays still and unused
 * for the auto-off time.
 */
class CameraController(
    private val context: Context,
    private val onChange: () -> Unit,
    private val onAutoOff: () -> Unit,
) : VideoEncoder.Listener {
    /** What focus and exposure follow: a tap holds until the next tap or a reset, then faces, then the center. */
    enum class FocusTarget(val label: String) { CENTER("Center"), FACE("Face"), TAP("Tap") }

    private class LensCamera(val lens: Lens, val physicalId: String, val focalLength: Float, val zoom: Double)

    private val main = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("cleancam.camera").apply { start() }
    private val camera = Handler(cameraThread.looper)
    private val executor = Executor { camera.post(it) }
    private val prefs = context.getSharedPreferences("cleancam", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(CameraManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    // The UI's state, read and written on the main thread.
    var status = "Starting…"; private set
    var zoom = 1.0; private set
    var zoomMin = 1.0; private set
    var zoomMax = 1.0; private set
    /** The zoom up to which the picture uses real sensor pixels; past it, the camera upscales. */
    var losslessZoom = 1.0; private set
    var isLocked = false; private set
    /** False when the microphone is not allowed or missing; the camera streams either way. */
    var hasMic = false; private set
    var isMicOn = true; private set
    var formatName: String? = null; private set
    var isHevc = false; private set
    var frameRate = 0; private set
    var bitRate = 0; private set
    var focusTarget = FocusTarget.CENTER; private set
    var preset = Preset.saved(prefs); private set
    var options = CameraOptions.saved(prefs); private set
    /** The lenses the user can hold, widest first; empty on a single-lens phone. */
    var lenses: List<Lens> = emptyList(); private set
    /** The zoom of each lens, for the chips above the zoom slider, while Auto switches lenses. */
    var lensZooms: List<Double> = emptyList(); private set
    var lensName: String? = null; private set
    var canFixWhiteBalance = false; private set
    var stabilizationName = "Stab off"; private set
    var heat = HeatLevel.NONE; private set
    /** The seconds left before the app turns itself off, in the last half minute; null before. */
    var autoOffIn: Int? = null; private set
    private var cameraDenied = false

    private val encoder = VideoEncoder(this)
    private val framesThisSecond = AtomicInteger()
    val renderer = GlRenderer { framesThisSecond.incrementAndGet() }
    private val callAudio = CallAudio(context)
    private val server = StreamServer(PORT)
    private val audioServer = StreamServer(AUDIO_PORT)
    private val speakerServer = StreamServer(SPEAKER_PORT)
    private val debugServer = StreamServer(DEBUG_PORT)
    private val motion = MotionDetector(context)
    private val debugInput = StringBuilder()
    /** Set by a touch or a changed setting; the next second restarts the auto-off count. */
    private val usedThisSecond = AtomicBoolean()

    // Used on the camera thread only.
    private var started = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var activeArray = Rect(0, 0, 4000, 3000)
    private var sensorOrientation = 90
    private var deviceOrientation = 0
    private var lensCameras: List<LensCamera> = emptyList()
    private var lensByPhysicalId: Map<String, Lens> = emptyMap()
    private var zoomRange = 1.0..1.0
    private var activePreset = preset
    private var activeOptions = options
    private var activeSize = Size(1920, 1080)
    private var aeRange = Range(24, 30)
    /** The frame-rate range the session started with; the camera picks its sensor mode by it, as a session key. */
    private var sessionRange: Range<Int>? = null
    private var zoomRatio = 1f
    private var locked = false
    private var target = FocusTarget.CENTER
    private var targetPoint = PointF(0.5f, 0.5f)
    private var lastFaceTime = 0L
    private var faceTimeoutScheduled = false
    private var stabilizationMode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    private var stabilizationChangedAt = Long.MIN_VALUE / 2
    private var stabilizationCheckScheduled = false
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var pluggedIn = true
    /** Set by the debug port's `thermal` and `battery` commands in place of the phone's own states. */
    private var thermalOverride: Int? = null
    private var pluggedOverride: Boolean? = null
    private var activeHeat = HeatLevel.NONE
    private var heatChangedAt = Long.MIN_VALUE / 2
    private var heatCheckScheduled = false
    private var lastResult: TotalCaptureResult? = null
    /** The seconds the phone has stood still and unused, toward the auto-off time. */
    private var idleSeconds = 0
    /** Until this uptime, the receiver's last report says an app on the PC uses the CleanCam webcam or microphone. */
    private var pcUsingUntil = 0L

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        updateHeat()
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            updateHeat()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            secondPassed()
            if (started) camera.postDelayed(this, 1000)
        }
    }

    init {
        server.onClientsChanged = { count ->
            camera.post {
                // A new viewer can only start decoding at a keyframe; while none watches, the encoder idles.
                renderer.encoderEnabled = count > 0
                if (count > 0) encoder.requestKeyframe()
                sendDebug(mapOf("event" to "viewers", "port" to PORT, "count" to count))
            }
            ui { if (!cameraDenied) status = if (count > 0) "Streaming to PC" else "Waiting for PC on port $PORT" }
        }
        for ((stream, port) in listOf(audioServer to AUDIO_PORT, speakerServer to SPEAKER_PORT)) {
            stream.onClientsChanged = { count -> sendDebug(mapOf("event" to "viewers", "port" to port, "count" to count)) }
        }
        callAudio.onMicPcm = { pcm -> if (audioServer.clientCount > 0) audioServer.broadcast(pcm, true) }
        speakerServer.onReceive = { data, length -> callAudio.play(data, length) }
        debugServer.onReceive = { data, length -> receiveDebug(String(data, 0, length, Charsets.UTF_8)) }
        // A new debug client learns the preset, the camera mode and the camera options at once.
        debugServer.onClientsChanged = { count ->
            if (count > 0) camera.post {
                reportMode()
                reportCamera("client")
            }
        }
    }

    /** The preset and what the auto settings and the options chose, such as "Call · 1080p · 24 fps · 8.0 Mbps". */
    val details: String
        get() {
            val format = formatName ?: return ""
            val parts = mutableListOf(preset.label, if (isHevc) "$format · HEVC" else format, "$frameRate fps",
                String.format("%.1f Mbps", bitRate / 1_000_000.0))
            lensName?.let { parts += it }
            parts += stabilizationName
            if (options.exposureBias != 0f) parts += String.format("EV %+.1f", options.exposureBias)
            options.whiteBalance?.takeIf { canFixWhiteBalance }?.let { parts += "$it K" }
            if (options.nearFocus) parts += "Near"
            heat.label?.let { parts += it }
            parts += focusTarget.label
            return parts.joinToString(" · ")
        }

    fun start() = camera.post {
        if (started) return@post
        started = true
        idleSeconds = 0
        server.start()
        audioServer.start()
        speakerServer.start()
        debugServer.start()
        motion.start(camera)
        thermalStatus = power.currentThermalStatus
        power.addThermalStatusListener(executor, thermalListener)
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, camera,
            Context.RECEIVER_NOT_EXPORTED)
        camera.postDelayed(tick, 1000)
        if (!granted(Manifest.permission.CAMERA)) {
            ui {
                cameraDenied = true
                status = "Allow camera access in Settings"
            }
            return@post
        }
        ui { cameraDenied = false }
        // The camera and the loudspeaker work whatever the microphone's answer.
        val micRunning = callAudio.start(granted(Manifest.permission.RECORD_AUDIO))
        ui { hasMic = micRunning }
        openCamera()
    }

    fun stop() = camera.post {
        if (!started) return@post
        started = false
        camera.removeCallbacks(tick)
        server.stop()
        audioServer.stop()
        speakerServer.stop()
        debugServer.stop()
        callAudio.stop()
        motion.stop()
        power.removeThermalStatusListener(thermalListener)
        try { context.unregisterReceiver(batteryReceiver) } catch (_: IllegalArgumentException) {}
        session?.close()
        session = null
        device?.close()
        device = null
        renderer.encoderEnabled = false
        renderer.setEncoder(null, 0, 0)
        encoder.release()
    }

    fun setPreview(surface: android.view.Surface?, width: Int, height: Int) = renderer.setPreview(surface, width, height)

    /** Stops drawing the preview while the screen is off; the stream keeps running. */
    fun setPreviewEnabled(enabled: Boolean) {
        renderer.previewEnabled = enabled
    }

    /** Keeps the preview upright for the screen's rotation: 0, 90, 180 or 270 degrees. */
    fun updateDisplayRotation(degrees: Int) = camera.post {
        renderer.previewRotation = (sensorOrientation - degrees + 360) % 360
    }

    /**
     * Keeps the stream upright: portrait or either landscape side, from the phone's clockwise turn in degrees. Flat and
     * upside-down positions keep the last orientation.
     */
    fun updateDeviceOrientation(degrees: Int) = camera.post {
        if (degrees < 0) return@post
        val snapped = ((degrees + 45) / 90 * 90) % 360
        if (snapped == 180) return@post
        deviceOrientation = snapped
        renderer.encoderRotation = (sensorOrientation + snapped) % 360
    }

    fun setZoom(value: Double) {
        zoom = value
        camera.post {
            zoomRatio = value.toFloat()
            applyRequest()
        }
    }

    /** Locks focus, exposure and white balance so the picture stops shifting. A fixed white balance stays fixed. */
    fun toggleLock() {
        isLocked = !isLocked
        val lock = isLocked
        onChange()
        camera.post {
            locked = lock
            applyRequest()
        }
    }

    /** Focus and exposure follow the tapped point, from 0 to 1 across the preview, while Lock is off. */
    fun focus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val point = renderer.previewToImage(x, y, viewWidth, viewHeight) ?: return
        camera.post {
            if (!locked) setTarget(FocusTarget.TAP, PointF(point.first, point.second))
        }
    }

    /** Hands focus and exposure back to faces, or to the center while no face shows. */
    fun resetFocus() = camera.post {
        if (!locked) setTarget(FocusTarget.CENTER, PointF(0.5f, 0.5f))
    }

    /** Stops or resumes sending the microphone; the PC's microphone stays and goes silent. */
    fun toggleMic() {
        isMicOn = !isMicOn
        callAudio.micEnabled = isMicOn
        onChange()
    }

    /** Restarts the auto-off count, for a touch on the screen. Called on any thread. */
    fun noteUse() = usedThisSecond.set(true)

    /** Switches to `preset`'s camera mode and frame rates, and keeps it for the next start. Called on any thread. */
    fun setPreset(preset: Preset) {
        noteUse()
        preset.save(prefs)
        ui { this.preset = preset }
        camera.post {
            if (preset == activePreset) return@post
            activePreset = preset
            applyCameraMode()
            applyStabilization()
        }
    }

    /** Applies the camera options and keeps them for the next start. Called on any thread. */
    fun setOptions(options: CameraOptions) {
        noteUse()
        options.save(prefs)
        if (Looper.myLooper() == Looper.getMainLooper()) this.options = options else ui { this.options = options }
        camera.post {
            val old = activeOptions
            if (options == old) return@post
            activeOptions = options
            if (options.lens != old.lens) applyLens()
            if (applyStabilization()) stabilizationChangedAt = SystemClock.elapsedRealtime()
            applyBitRateLimit()
            applyRequest()
            reportCamera("options")
        }
    }

    // Camera thread.

    private fun openCamera() {
        if (device != null) return
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        }
        if (id == null) {
            ui { status = "No camera found" }
            return
        }
        val info = manager.getCameraCharacteristics(id)
        characteristics = info
        activeArray = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: activeArray
        sensorOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        renderer.encoderRotation = (sensorOrientation + deviceOrientation) % 360
        val ratio = info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        zoomRange = (ratio?.lower?.toDouble() ?: 1.0)..(ratio?.upper?.toDouble() ?: 1.0)
        findLenses(info)
        val fixable = info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.contains(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT) == true
        val lensList = lensCameras.map { it.lens }
        ui {
            lenses = lensList
            canFixWhiteBalance = fixable
        }
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!started) { camera.close(); return }
                    device = camera
                    applyLens()
                    configureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device == camera) device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device == camera) device = null
                    ui { status = "Camera error $error; reopen CleanCam" }
                }
            })
        } catch (e: Exception) {
            ui { status = "Camera unavailable: ${e.message}" }
        }
    }

    /** Each lens's own camera behind the logical camera, widest first, with the zoom where the logical camera uses it. */
    private fun findLenses(info: CameraCharacteristics) {
        val physical = info.physicalCameraIds.mapNotNull { id ->
            val focal = try {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            } catch (e: Exception) { null }
            focal?.let { id to it }
        }
        val focals = physical.map { it.second }.distinctBy { (it * 10).roundToInt() }.sorted()
        if (focals.size < 2) {
            lensCameras = emptyList()
            lensByPhysicalId = emptyMap()
            return
        }
        // The widest lens is the ultra-wide, the next the wide one at zoom 1, and a longer one the tele.
        val kinds = listOf(Lens.ULTRA_WIDE, Lens.WIDE, Lens.TELE)
        val wideFocal = focals[1]
        lensCameras = focals.take(3).mapIndexed { index, focal ->
            val zoom = if (index == 0) zoomRange.start else focal.toDouble() / wideFocal
            LensCamera(kinds[index], physical.first { abs(it.second - focal) < 0.05f }.first, focal, zoom)
        }
        lensByPhysicalId = physical.associate { (id, focal) ->
            id to lensCameras.minBy { abs(it.focalLength - focal) }.lens
        }
    }

    /**
     * Streams from the lens the user holds, by keeping the logical camera's zoom in that lens's range, or in Auto lets
     * it switch lenses as the zoom changes. A new lens starts with focus and exposure on the center and Lock off.
     */
    private fun applyLens() {
        val held = lensCameras.firstOrNull { it.lens == activeOptions.lens }
        val next = held?.let { lensCameras.getOrNull(lensCameras.indexOf(it) + 1) }
        val low = held?.zoom ?: zoomRange.start
        val high = minOf(next?.zoom?.minus(0.01) ?: zoomRange.endInclusive, zoomRange.endInclusive, WIDE_ZOOM_LIMIT)
        zoomRatio = (held?.zoom ?: 1.0).coerceIn(low, high).toFloat()
        locked = false
        target = FocusTarget.CENTER
        targetPoint = PointF(0.5f, 0.5f)
        val chips = if (held == null && lensCameras.size > 1) lensCameras.map { it.zoom } else emptyList()
        val zoomNow = zoomRatio.toDouble()
        ui {
            zoomMin = low
            zoomMax = high
            zoom = zoomNow
            lensZooms = chips
            isLocked = false
            focusTarget = FocusTarget.CENTER
        }
        applyRequest()
    }

    /** Called once the preset, the heat guard or the camera changed. A new size or codec needs a new session. */
    private fun applyCameraMode() {
        val (height, range) = modeLimits()
        val size = streamSize(height)
        if (device != null && (size != activeSize || !encoder.matches(activeCodec, size.width, size.height, range.upper) ||
                range.upper != sessionRange?.upper)) {
            configureSession()
            return
        }
        aeRange = range
        encoder.setFrameRate(range.upper)
        applyRequest()
        modeChanged()
    }

    private fun configureSession() {
        val device = device ?: return
        val (height, range) = modeLimits()
        val size = streamSize(height)
        val mime = activeCodec
        aeRange = range
        session?.close()
        session = null
        if (!encoder.matches(mime, size.width, size.height, range.upper)) {
            val reconnect = encoder.surface != null && !encoder.matches(mime, size.width, size.height, encoder.frameRate)
            renderer.setEncoder(null, 0, 0)
            encoder.configure(mime, size.width, size.height, range.upper)
            renderer.setEncoder(encoder.surface, size.width, size.height)
            // The receiver detects the codec and the size as it connects, so a new codec or size reconnects it.
            if (reconnect) server.disconnectAll()
        }
        activeSize = size
        renderer.setInputSize(size.width, size.height)
        try {
            val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(renderer.inputSurface)), executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (this@CameraController.device != device) { configured.close(); return }
                        session = configured
                        applyRequest()
                        modeChanged()
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        ui { status = "Camera mode ${size.width}x${size.height} failed" }
                    }
                })
            // The frame-rate range is a session key: a 60 fps range picks the sensor's 60 fps mode as the session starts.
            configuration.sessionParameters = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }.build()
            sessionRange = range
            device.createCaptureSession(configuration)
        } catch (e: Exception) {
            ui { status = "Camera unavailable: ${e.message}" }
        }
    }

    /** Builds the repeating request from the preset, the options, Lock, the zoom and what focus follows. */
    private fun applyRequest() {
        val device = device ?: return
        val session = session ?: return
        val info = characteristics ?: return
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(renderer.inputSurface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeRange)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureSteps(info))
            set(CaptureRequest.CONTROL_AE_LOCK, locked)
            val macro = activeOptions.nearFocus && info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CameraMetadata.CONTROL_AF_MODE_MACRO) == true
            // In AUTO the lens holds still until a trigger, which Lock never sends.
            set(CaptureRequest.CONTROL_AF_MODE, when {
                locked -> CameraMetadata.CONTROL_AF_MODE_AUTO
                macro -> CameraMetadata.CONTROL_AF_MODE_MACRO
                else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            })
            val kelvins = activeOptions.whiteBalance
            if (kelvins != null) {
                set(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode(kelvins, info))
            } else {
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AWB_LOCK, locked)
            }
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilizationMode)
            if (info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
            if (info.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
                    ?.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) == true) {
                set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
            }
            if (target != FocusTarget.CENTER) {
                val region = arrayOf(meteringRegion(targetPoint))
                set(CaptureRequest.CONTROL_AF_REGIONS, region)
                set(CaptureRequest.CONTROL_AE_REGIONS, region)
            }
        }.build()
        try {
            session.setRepeatingRequest(request, captureCallback, camera)
        } catch (e: Exception) {
            // The session closed while a new one starts; the new one applies the request.
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            val active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { lensByPhysicalId[it] }
            val name = active?.label
            if (name != null) main.post {
                if (lensName != name) {
                    lensName = name
                    onChange()
                }
            }
            handleFaces(result.get(CaptureResult.STATISTICS_FACES))
        }
    }

    /** Focus and exposure follow the largest face, unless a tap or Lock holds them. */
    private fun handleFaces(faces: Array<Face>?) {
        if (target == FocusTarget.TAP || locked) return
        val face = faces?.maxByOrNull { it.bounds.width() * it.bounds.height() } ?: return
        lastFaceTime = SystemClock.elapsedRealtime()
        val point = PointF(face.bounds.exactCenterX() / activeArray.width(), face.bounds.exactCenterY() / activeArray.height())
        // Small head movements keep the last point, so focus and exposure stay steady.
        if (target != FocusTarget.FACE || hypot(point.x - targetPoint.x, point.y - targetPoint.y) > FACE_MOVE) {
            setTarget(FocusTarget.FACE, point)
        }
        scheduleFaceTimeout(FACE_TIMEOUT_MS)
    }

    /** Returns focus to the center once no face has shown for [FACE_TIMEOUT_MS]. */
    private fun scheduleFaceTimeout(delay: Long) {
        if (faceTimeoutScheduled) return
        faceTimeoutScheduled = true
        camera.postDelayed({
            faceTimeoutScheduled = false
            if (target != FocusTarget.FACE || locked) return@postDelayed
            val idle = SystemClock.elapsedRealtime() - lastFaceTime
            if (idle < FACE_TIMEOUT_MS) scheduleFaceTimeout(FACE_TIMEOUT_MS - idle)
            else setTarget(FocusTarget.CENTER, PointF(0.5f, 0.5f))
        }, delay)
    }

    private fun setTarget(target: FocusTarget, point: PointF) {
        this.target = target
        targetPoint = point
        applyRequest()
        ui { focusTarget = target }
    }

    /** A tenth of the frame around `point`, in the zoomed field of view's active-array coordinates. */
    private fun meteringRegion(point: PointF): MeteringRectangle {
        val width = activeArray.width()
        val height = activeArray.height()
        val x = (point.x * width).roundToInt()
        val y = (point.y * height).roundToInt()
        val rect = Rect(
            (x - width / 20).coerceIn(0, width - 1), (y - height / 20).coerceIn(0, height - 1),
            (x + width / 20).coerceIn(1, width), (y + height / 20).coerceIn(1, height),
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun exposureSteps(info: CameraCharacteristics): Int {
        val step = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: return 0
        val range = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return 0
        return (activeOptions.exposureBias / step).roundToInt().coerceIn(range.lower, range.upper)
    }

    /**
     * A fixed white balance by the camera's own calibrated light: lamps up to 2850 K, warm fluorescent to 3500 K,
     * fluorescent to 4600 K, daylight to 6000 K, cloudy to 7000 K, and shade above.
     */
    private fun whiteBalanceMode(kelvins: Int, info: CameraCharacteristics): Int {
        val mode = when {
            kelvins < 2850 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            kelvins < 3500 -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
            kelvins < 4600 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
            kelvins < 6000 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            kelvins < 7000 -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            else -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        }
        val available = info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: return CameraMetadata.CONTROL_AWB_MODE_AUTO
        return if (mode in available) mode else CameraMetadata.CONTROL_AWB_MODE_AUTO
    }

    /**
     * Sets the stabilization the user picked or, in Auto, the one the motion calls for: off while the phone stands
     * still, and on while it shakes, preview stabilization in OBS, whose apps don't mind its delay. The heat guard turns
     * it off. Returns whether the stabilization changed.
     */
    private fun applyStabilization(): Boolean {
        val available = characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        var wanted = when (activeOptions.stabilization) {
            Stabilization.AUTO -> when {
                motion.state != MotionDetector.State.SHAKING -> Stabilization.OFF
                activePreset == Preset.OBS -> Stabilization.CINEMATIC
                else -> Stabilization.STANDARD
            }
            else -> activeOptions.stabilization
        }
        if (!activeHeat.allowsStabilization) wanted = Stabilization.OFF
        var mode = when (wanted) {
            Stabilization.CINEMATIC -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            Stabilization.STANDARD -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        if (mode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION && mode !in available) {
            mode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        }
        if (mode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON && mode !in available) {
            mode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        if (mode == stabilizationMode) return false
        stabilizationMode = mode
        applyRequest()
        val name = stabilizationLabel(mode)
        ui { stabilizationName = name }
        return true
    }

    /** Auto stabilization follows the motion, at most once in [AUTO_CHANGE_INTERVAL_MS]. */
    private fun applyAutoStabilization() {
        if (activeOptions.stabilization != Stabilization.AUTO) return
        val wait = stabilizationChangedAt + AUTO_CHANGE_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) {
            if (stabilizationCheckScheduled) return
            stabilizationCheckScheduled = true
            camera.postDelayed({
                stabilizationCheckScheduled = false
                applyAutoStabilization()
            }, wait)
            return
        }
        if (!applyStabilization()) return
        stabilizationChangedAt = SystemClock.elapsedRealtime()
        reportCamera("motion")
    }

    /**
     * Moves the heat guard to the level the phone's heat and power call for. A higher level applies at once; a lower one
     * waits until [AUTO_CHANGE_INTERVAL_MS] after the last change.
     */
    private fun updateHeat() {
        val level = HeatLevel.of(thermalOverride ?: thermalStatus, pluggedOverride ?: pluggedIn)
        if (level == activeHeat) return
        val wait = heatChangedAt + AUTO_CHANGE_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (level < activeHeat && wait > 0) {
            if (heatCheckScheduled) return
            heatCheckScheduled = true
            camera.postDelayed({
                heatCheckScheduled = false
                updateHeat()
            }, wait)
            return
        }
        activeHeat = level
        heatChangedAt = SystemClock.elapsedRealtime()
        ui { heat = level }
        applyCameraMode()
        applyStabilization()
        applyBitRateLimit()
        reportCamera("heat")
    }

    /** Caps the encoder's bitrate by the heat guard and the user's max bitrate. */
    private fun applyBitRateLimit() {
        val limit = listOfNotNull(activeOptions.maxBitRate, activeHeat.maxBitRate).minOrNull()
        encoder.setLimit(activeHeat.bitRateShare, limit)
    }

    /** The preset's camera mode height and frame rates, within what the heat guard and the camera allow. */
    private fun modeLimits(): Pair<Int, Range<Int>> {
        val top = minOf(activePreset.maxRate, activeHeat.topFrameRate ?: activePreset.maxRate)
        val height = minOf(activePreset.height, activeHeat.maxHeight ?: activePreset.height)
        return height to frameRateRange(minOf(activePreset.minRate, top), top)
    }

    /**
     * The camera's auto-exposure range that reaches `high`, or its fastest below it, and slows the least below `low`: a
     * Pixel 8 offers 15–60 and 60–60, and 15–60 slows to 30 indoors, so OBS's 30–60 takes 60–60.
     */
    private fun frameRateRange(low: Int, high: Int): Range<Int> {
        val ranges = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.takeIf { it.isNotEmpty() } ?: return Range(low, high)
        val top = ranges.map { it.upper }.filter { it <= high }.maxOrNull() ?: ranges.minOf { it.upper }
        val reaching = ranges.filter { it.upper == top }
        return reaching.filter { it.lower >= low }.minByOrNull { it.lower } ?: reaching.maxBy { it.lower }
    }

    private val topModeRate: Int
        get() = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper } ?: 30

    private fun streamSize(height: Int): Size {
        val sizes = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        return if (height == 2160 && sizes.any { it.width == 3840 && it.height == 2160 }) Size(3840, 2160) else Size(1920, 1080)
    }

    private val activeCodec: String
        get() = if (activePreset.prefersHevc && VideoEncoder.hasHevcEncoder) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC

    /** The status line, the zoom slider and the debug port follow a new camera mode. */
    private fun modeChanged() {
        val size = activeSize
        val hevc = encoder.mime == MediaFormat.MIMETYPE_VIDEO_HEVC
        val lossless = activeArray.width().toDouble() / size.width
        ui {
            formatName = "${size.height}p"
            isHevc = hevc
            losslessZoom = lossless
        }
        reportMode()
    }

    private fun secondPassed() {
        val frames = framesThisSecond.getAndSet(0)
        val backlog = server.takePeakBacklog()
        encoder.adapt(backlog)
        val rate = encoder.bitRate
        val (bytes, encoded, keyframes) = encoder.takeOutput()
        val (rotation, changed) = motion.takeSecond()
        if (changed) applyAutoStabilization()
        countIdle(rotation)
        sendDebug(mapOf(
            "event" to "second", "fps" to frames, "topFps" to encoder.frameRate, "target" to rate,
            "pinned" to encoder.isPinned, "out" to bytes * 8, "encoded" to encoded, "keyframes" to keyframes,
            "backlog" to backlog, "rotation" to (rotation * 1000).roundToInt() / 1000.0,
            "viewers" to mapOf("video" to server.clientCount, "mic" to audioServer.clientCount,
                "speaker" to speakerServer.clientCount),
            "idle" to mapOf("seconds" to idleSeconds, "limit" to activeOptions.autoOffMinutes * 60,
                "pcUsing" to isPcUsing),
        ))
        ui {
            frameRate = frames
            bitRate = rate
        }
    }

    private val isPcUsing get() = SystemClock.elapsedRealtime() < pcUsingUntil

    /**
     * Counts the seconds the phone stands still and untouched while no app on the PC uses the CleanCam webcam or
     * microphone, and turns the app off once they reach the auto-off time. A phone on a mount in a call stands as still
     * as a forgotten one, so only the receiver's reports tell them apart.
     */
    private fun countIdle(rotation: Double) {
        val limit = activeOptions.autoOffMinutes * 60
        val used = usedThisSecond.getAndSet(false) || rotation >= MotionDetector.STILL_PEAK || isPcUsing
        idleSeconds = if (used || limit == 0) 0 else idleSeconds + 1
        val left = limit - idleSeconds
        ui { autoOffIn = if (limit > 0 && left <= AUTO_OFF_WARNING_SECONDS) left else null }
        if (limit > 0 && left <= 0) {
            idleSeconds = 0
            ui { onAutoOff() }
        }
    }

    private fun reportMode() {
        if (device == null) return
        sendDebug(mapOf(
            "event" to "mode", "preset" to activePreset.key, "codec" to VideoEncoder.codecName(encoder.mime),
            "width" to activeSize.width, "height" to activeSize.height, "fps" to listOf(aeRange.lower, aeRange.upper),
            "modeTopFps" to topModeRate, "losslessZoom" to activeArray.width().toDouble() / activeSize.width,
        ))
    }

    /** Tells the debug port the camera options, what the camera took of them, and the heat guard's level. */
    private fun reportCamera(reason: String) {
        val result = lastResult
        val info = characteristics
        val step = info?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
        val activeLens = result?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { lensByPhysicalId[it] }
        sendDebug(mapOf(
            "event" to "camera", "reason" to reason,
            "lens" to mapOf("asked" to activeOptions.lens.key, "active" to activeLens?.key, "zoom" to zoomRatio.toDouble()),
            "stabilization" to mapOf("asked" to activeOptions.stabilization.key, "motion" to motion.state.key,
                "preferred" to stabilizationKey(stabilizationMode),
                "active" to stabilizationKey(result?.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))),
            "exposureBias" to mapOf("asked" to activeOptions.exposureBias.toDouble(),
                "took" to (result?.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0) * step),
            "whiteBalance" to mapOf("asked" to (activeOptions.whiteBalance ?: "auto"),
                "mode" to awbKey(result?.get(CaptureResult.CONTROL_AWB_MODE)),
                "locked" to (result?.get(CaptureResult.CONTROL_AWB_LOCK) == true)),
            "nearFocus" to mapOf("asked" to activeOptions.nearFocus,
                "took" to (result?.get(CaptureResult.CONTROL_AF_MODE) == CameraMetadata.CONTROL_AF_MODE_MACRO)),
            "maxBitRate" to activeOptions.maxBitRate,
            "heat" to mapOf("level" to activeHeat.name.lowercase(),
                "thermal" to (THERMAL_NAMES.entries.firstOrNull { it.value == (thermalOverride ?: thermalStatus) }?.key
                    ?: "status ${thermalOverride ?: thermalStatus}"),
                "battery" to if (pluggedOverride ?: pluggedIn) "plugged" else "unplugged",
                "overridden" to (thermalOverride != null || pluggedOverride != null)),
        ))
    }

    /** Sends one JSON line, stamped with the phone's uptime, to every client of the debug port. Called on any thread. */
    private fun sendDebug(event: Map<String, Any?>) {
        if (debugServer.clientCount == 0) return
        val line = JSONObject(event + ("t" to SystemClock.elapsedRealtime() / 1000.0)).toString() + "\n"
        debugServer.broadcast(line.toByteArray(), true)
    }

    override fun onEncoded(data: ByteArray, isKeyframe: Boolean) = server.broadcast(data, isKeyframe)

    override fun onEvent(event: Map<String, Any?>) = sendDebug(event)

    /** Called on the debug client's thread with what it typed: one command per line. */
    private fun receiveDebug(text: String) {
        val lines = synchronized(debugInput) {
            debugInput.append(text)
            val complete = mutableListOf<String>()
            while (true) {
                val newline = debugInput.indexOf("\n")
                if (newline < 0) break
                complete += debugInput.substring(0, newline).trim()
                debugInput.delete(0, newline + 1)
            }
            // A client that never ends a line can't grow the buffer without bound.
            if (debugInput.length > 1024) debugInput.setLength(0)
            complete
        }
        for (line in lines) if (line.isNotEmpty()) camera.post { runDebugCommand(line) }
    }

    /**
     * Runs a debug command: `bitrate N` holds the bitrate at N bits per second, `auto` hands it back to adaptation,
     * `keyframe` asks for a keyframe, and `preset NAME` switches the preset, as the settings sheet does. `lens`, `stab`,
     * `ev`, `wb`, `near`, `maxbitrate` and `autooff` set an option as the settings sheet does; `thermal` and `battery`
     * stand in for the phone's heat and power until `auto`; `camera` reports the camera options. The receiver sends
     * `inuse on|off`.
     */
    private fun runDebugCommand(line: String) {
        val words = line.split(" ").filter { it.isNotEmpty() }
        val value = if (words.size == 2) words[1] else ""
        val number = value.toIntOrNull()
        // The receiver reports every 2 s whether an app on the PC uses the CleanCam webcam or microphone, too often to
        // answer. A report lasts PC_USING_MS, so the auto-off count resumes once the receiver stops.
        if (words.firstOrNull() == "inuse" && (value == "on" || value == "off")) {
            pcUsingUntil = if (value == "on") SystemClock.elapsedRealtime() + PC_USING_MS else 0L
            return
        }
        noteUse()
        val ok = when (words.firstOrNull()) {
            "preset" -> Preset.of(value)?.let { setPreset(it) } != null
            "bitrate" -> if (number != null && number in 1..MAX_PINNED_BIT_RATE) { encoder.pin(number); true } else false
            "auto" -> if (words.size == 1) { encoder.unpin(); true } else false
            "keyframe" -> if (words.size == 1) { encoder.requestKeyframe(); true } else false
            "lens" -> Lens.of(value)?.let { lens -> changeOptions { it.copy(lens = lens) } } != null
            "stab" -> Stabilization.of(value)?.let { stab -> changeOptions { it.copy(stabilization = stab) } } != null
            "ev" -> value.toFloatOrNull()?.takeIf { abs(it) <= CameraOptions.EXPOSURE_BIAS_LIMIT }
                ?.let { ev -> changeOptions { it.copy(exposureBias = ev) } } != null
            "wb" -> when {
                value == "auto" -> { changeOptions { it.copy(whiteBalance = null) }; true }
                number != null && number in CameraOptions.WHITE_BALANCE_RANGE -> {
                    changeOptions { it.copy(whiteBalance = number) }; true
                }
                else -> false
            }
            "near" -> if (value == "on" || value == "off") { changeOptions { it.copy(nearFocus = value == "on") }; true } else false
            "maxbitrate" -> when {
                value == "off" -> { changeOptions { it.copy(maxBitRate = null) }; true }
                number != null && number in 1..MAX_PINNED_BIT_RATE -> { changeOptions { it.copy(maxBitRate = number) }; true }
                else -> false
            }
            "autooff" -> when {
                value == "never" -> { changeOptions { it.copy(autoOffMinutes = 0) }; true }
                number != null && number in 1..CameraOptions.MAX_AUTO_OFF_MINUTES -> {
                    changeOptions { it.copy(autoOffMinutes = number) }; true
                }
                else -> false
            }
            "thermal" -> if (value == "auto" || value in THERMAL_NAMES) {
                thermalOverride = THERMAL_NAMES[value]
                updateHeat()
                true
            } else false
            "battery" -> if (value == "auto" || value in BATTERY_NAMES) {
                pluggedOverride = BATTERY_NAMES[value]
                updateHeat()
                true
            } else false
            "camera" -> if (words.size == 1) { reportCamera("command"); true } else false
            else -> false
        }
        sendDebug(mapOf("event" to "command", "command" to line, "ok" to ok))
    }

    private fun changeOptions(change: (CameraOptions) -> CameraOptions) = setOptions(change(activeOptions))

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Updates the UI's state on the main thread, then redraws the UI. */
    private fun ui(block: CameraController.() -> Unit) = main.post {
        block()
        onChange()
    }

    companion object {
        const val PORT = 5000
        const val AUDIO_PORT = 5001
        const val SPEAKER_PORT = 5002
        const val DEBUG_PORT = 5003
        /** The highest bitrate the debug port's `bitrate` command holds. */
        private const val MAX_PINNED_BIT_RATE = 100_000_000
        /** How long focus stays on a face that left the picture before it returns to the center. */
        private const val FACE_TIMEOUT_MS = 2000L
        /** How far a face moves, in the sensor's 0–1 coordinates, before focus and exposure follow it. */
        private const val FACE_MOVE = 0.1
        /** Auto stabilization, and the heat guard as the phone cools, change the camera at most once a minute. */
        private const val AUTO_CHANGE_INTERVAL_MS = 60_000L
        /** The status counts down the last half minute before auto off. */
        private const val AUTO_OFF_WARNING_SECONDS = 30
        /** How long the receiver's report that the PC uses the stream lasts; it reports every 2 s. */
        private const val PC_USING_MS = 10_000L
        /** The zoom slider reaches 10 times the wide lens. */
        private const val WIDE_ZOOM_LIMIT = 10.0
        /** The debug port's names, after iOS's thermal states, for Android's thermal statuses. */
        private val THERMAL_NAMES = mapOf(
            "nominal" to PowerManager.THERMAL_STATUS_NONE, "fair" to PowerManager.THERMAL_STATUS_LIGHT,
            "serious" to PowerManager.THERMAL_STATUS_MODERATE, "critical" to PowerManager.THERMAL_STATUS_SEVERE,
        )
        private val BATTERY_NAMES = mapOf("unplugged" to false, "charging" to true, "full" to true, "unknown" to true)

        fun stabilizationLabel(mode: Int) = when (mode) {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "Stab on"
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "Cinematic"
            else -> "Stab off"
        }

        private fun stabilizationKey(mode: Int?) = when (mode) {
            null -> "none"
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "standard"
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "cinematic"
            else -> "off"
        }

        private fun awbKey(mode: Int?) = when (mode) {
            CameraMetadata.CONTROL_AWB_MODE_AUTO -> "auto"
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "incandescent"
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warmFluorescent"
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "fluorescent"
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "daylight"
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "cloudy"
            CameraMetadata.CONTROL_AWB_MODE_SHADE -> "shade"
            null -> "none"
            else -> "mode $mode"
        }
    }
}
