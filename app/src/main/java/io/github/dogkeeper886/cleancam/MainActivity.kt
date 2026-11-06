package io.github.dogkeeper886.cleancam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** The preview with the stream's status and controls over it, a black Screen off, and the settings sheet. */
class MainActivity : Activity() {
    private lateinit var camera: CameraController
    private lateinit var preview: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var zoomRow: LinearLayout
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var lockButton: Button
    private lateinit var muteButton: Button
    private lateinit var screenOff: FrameLayout
    private lateinit var screenOffText: TextView
    private lateinit var orientation: OrientationEventListener
    private var isScreenOff = false
    private var askedPermissions = false
    private var zoomTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The volume buttons set the loudness of the call's sound on the loudspeaker.
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        camera = CameraController(applicationContext, onChange = { refresh() }, onAutoOff = { finish() })
        setContentView(buildLayout())
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees != ORIENTATION_UNKNOWN) camera.updateDeviceOrientation(degrees)
            }
        }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        orientation.enable()
        camera.updateDisplayRotation(displayDegrees())
        val needed = listOfNotNull(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 37) Manifest.permission.ACCESS_LOCAL_NETWORK else null,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty() && !askedPermissions) {
            askedPermissions = true
            requestPermissions(needed.toTypedArray(), 1)
        } else {
            camera.start()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        camera.start()
    }

    override fun onStop() {
        // Give the screen its brightness back before leaving the app.
        setScreenOff(false)
        orientation.disable()
        camera.stop()
        askedPermissions = false
        super.onStop()
    }

    /** Any touch, on the preview, a button or Screen off, restarts the auto-off count. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        camera.noteUse()
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A system panel, such as the notification shade, covers the app; the stream keeps running.
        if (!hasFocus) setScreenOff(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        camera.updateDisplayRotation(displayDegrees())
    }

    private fun displayDegrees() = (display?.rotation ?: 0) * 90

    private fun refresh() {
        val status = camera.autoOffIn?.let { "${camera.status} · Off in $it s" } ?: camera.status
        statusText.text = status
        detailsText.text = camera.details
        detailsText.visibility = if (camera.details.isEmpty()) View.GONE else View.VISIBLE
        screenOffText.text = "$status · Tap to turn the screen on"

        // Each chip jumps to a lens's zoom.
        chipsRow.removeAllViews()
        chipsRow.visibility = if (camera.lensZooms.size > 1) View.VISIBLE else View.GONE
        for (zoom in camera.lensZooms) {
            chipsRow.addView(button(zoomLabel(zoom)) { setZoom(zoom) }.apply {
                setTextColor(if (abs(camera.zoom - zoom) < 0.05) Color.YELLOW else Color.WHITE)
            })
        }
        zoomRow.visibility = if (camera.zoomMin < camera.zoomMax) View.VISIBLE else View.GONE
        if (!zoomTracking) zoomBar.progress = zoomToProgress(camera.zoom)
        zoomLabel.text = String.format("%.1fx", camera.zoom)
        // Yellow past the lossless zoom, where the camera upscales.
        zoomLabel.setTextColor(if (camera.zoom > camera.losslessZoom) Color.YELLOW else Color.WHITE)

        lockButton.text = if (camera.isLocked) "Unlock" else "Lock"
        lockButton.backgroundTintList = ColorStateList.valueOf(if (camera.isLocked) ORANGE else GRAY)
        muteButton.visibility = if (camera.hasMic) View.VISIBLE else View.GONE
        muteButton.text = if (camera.isMicOn) "Mute" else "Unmute"
        muteButton.backgroundTintList = ColorStateList.valueOf(if (camera.isMicOn) GRAY else Color.RED)
    }

    private fun setZoom(zoom: Double) {
        camera.setZoom(zoom)
        refresh()
    }

    /** "Screen off" is a black screen at the lowest brightness, since the camera stops when the screen really turns off. */
    private fun setScreenOff(off: Boolean) {
        if (off == isScreenOff) return
        isScreenOff = off
        window.attributes = window.attributes.apply {
            screenBrightness = if (off) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        screenOff.visibility = if (off) View.VISIBLE else View.GONE
        camera.setPreviewEnabled(!off)
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = SurfaceView(this)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                camera.setPreview(holder.surface, width, height)
            override fun surfaceDestroyed(holder: SurfaceHolder) = camera.setPreview(null, 0, 0)
        })
        // Tap: focus and exposure follow that point. Long press: back to faces, or the center.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                camera.focus(e.x / preview.width, e.y / preview.height, preview.width, preview.height)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                camera.resetFocus()
            }
        })
        preview.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        root.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))

        statusText = label()
        detailsText = label()
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zoomBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) setZoom(progressToZoom(progress))
                }
                override fun onStartTrackingTouch(bar: SeekBar) { zoomTracking = true }
                override fun onStopTrackingTouch(bar: SeekBar) { zoomTracking = false }
            })
        }
        zoomLabel = label()
        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(zoomBar, LinearLayout.LayoutParams(dp(260), WRAP))
            addView(zoomLabel, LinearLayout.LayoutParams(dp(48), WRAP))
        }
        lockButton = button("Lock") { camera.toggleLock() }
        muteButton = button("Mute") { camera.toggleMic() }
        // The buttons get their own row so all four fit an upright phone.
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(lockButton)
            addView(muteButton)
            addView(button("Screen off") { setScreenOff(true) })
            addView(button("⚙") { showSettings() }.apply { contentDescription = "Settings" })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(128, 0, 0, 0))
                cornerRadius = dp(12).toFloat()
            }
            for (view in listOf(statusText, detailsText, chipsRow, zoomRow, buttons)) {
                addView(view, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6) })
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })
        panel.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (view.layoutParams as FrameLayout.LayoutParams).setMargins(
                dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }

        screenOffText = label().apply {
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        screenOff = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(screenOffText, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { setScreenOff(false) }
        }
        root.addView(screenOff, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    /** Picks the preset and the camera options. The camera follows each change at once, and the app keeps them. */
    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun header(text: String) = content.addView(TextView(this).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        fun footer(text: String) = content.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            alpha = 0.7f
        })
        fun <T> choices(items: List<T>, selected: T, name: (T) -> String, pick: (T) -> Unit) {
            val group = RadioGroup(this)
            items.forEachIndexed { index, item ->
                group.addView(RadioButton(this).apply {
                    id = index + 1
                    text = name(item)
                    isChecked = item == selected
                })
            }
            group.setOnCheckedChangeListener { _, id -> pick(items[id - 1]) }
            content.addView(group)
        }
        fun update(change: (CameraOptions) -> CameraOptions) = camera.setOptions(change(camera.options))

        header("Preset")
        choices(Preset.entries, camera.preset, { "${it.label}\n${it.summary}" }) { camera.setPreset(it) }

        header("Camera")
        if (camera.lenses.isNotEmpty()) {
            choices(listOf(Lens.AUTO) + camera.lenses, camera.options.lens, { "Lens: ${it.label}" }) { lens ->
                update { it.copy(lens = lens) }
            }
        }
        choices(Stabilization.entries, camera.options.stabilization, { "Stabilization: ${it.label}" }) { stab ->
            update { it.copy(stabilization = stab) }
        }
        content.addView(Switch(this).apply {
            text = "Near focus"
            isChecked = camera.options.nearFocus
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean -> update { it.copy(nearFocus = on) } }
        })
        footer("Auto lets the camera switch lenses as you zoom. Auto stabilization turns on while the phone shakes and off while it stands still; Cinematic steadies most but adds delay. Near focus looks for close subjects first.")

        header("Picture")
        val exposureLabel = TextView(this)
        fun showExposure(ev: Float) { exposureLabel.text = String.format("Exposure: %+.1f EV", ev) }
        showExposure(camera.options.exposureBias)
        content.addView(exposureLabel)
        content.addView(SeekBar(this).apply {
            max = 40
            progress = ((camera.options.exposureBias + 2f) * 10).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val ev = progress / 10f - 2f
                    showExposure(ev)
                    if (fromUser) update { it.copy(exposureBias = ev) }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        val kelvinLabel = TextView(this)
        val kelvinBar = SeekBar(this).apply { max = 55 }
        fun showKelvins(kelvins: Int?) {
            kelvinLabel.text = "White balance: ${kelvins ?: CameraOptions.DEFAULT_WHITE_BALANCE} K"
            kelvinLabel.visibility = if (kelvins == null) View.GONE else View.VISIBLE
            kelvinBar.visibility = kelvinLabel.visibility
            kelvinBar.progress = ((kelvins ?: CameraOptions.DEFAULT_WHITE_BALANCE) - 2500) / 100
        }
        content.addView(Switch(this).apply {
            text = "Auto white balance"
            isChecked = camera.options.whiteBalance == null
            isEnabled = camera.canFixWhiteBalance
            setOnCheckedChangeListener { _: CompoundButton, auto: Boolean ->
                val kelvins = if (auto) null else CameraOptions.DEFAULT_WHITE_BALANCE
                update { it.copy(whiteBalance = kelvins) }
                showKelvins(kelvins)
            }
        })
        content.addView(kelvinLabel)
        content.addView(kelvinBar)
        kelvinBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val kelvins = 2500 + progress * 100
                kelvinLabel.text = "White balance: $kelvins K"
                update { it.copy(whiteBalance = kelvins) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        showKelvins(camera.options.whiteBalance)
        footer("Lower kelvins turn the picture blue, for warm lamps; higher ones turn it yellow, for shade.")

        header("Stream")
        choices(listOf<Int?>(null) + CameraOptions.MAX_BIT_RATES, camera.options.maxBitRate,
            { if (it == null) "Max bitrate: no limit" else "Max bitrate: ${it / 1_000_000} Mbps" }) { rate ->
            update { it.copy(maxBitRate = rate) }
        }
        footer("On battery the camera runs at 30 fps at most. As the phone heats up, it also drops 4K, the bitrate and stabilization.")

        header("Auto off")
        choices(CameraOptions.AUTO_OFF_MINUTES, camera.options.autoOffMinutes,
            { if (it == 0) "Never" else "After $it min" }) { minutes -> update { it.copy(autoOffMinutes = minutes) } }
        footer("The app turns itself off once the phone stands still and untouched this long while no app on the PC uses the CleanCam webcam or microphone.")

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun zoomToProgress(zoom: Double): Int {
        val span = camera.zoomMax - camera.zoomMin
        return if (span <= 0) 0 else ((zoom - camera.zoomMin) / span * 1000).roundToInt().coerceIn(0, 1000)
    }

    private fun progressToZoom(progress: Int) = camera.zoomMin + (camera.zoomMax - camera.zoomMin) * progress / 1000

    private fun label() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.MONOSPACE
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(GRAY)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val GRAY = Color.rgb(90, 90, 90)
        val ORANGE = Color.rgb(230, 140, 0)

        /** A lens chip's label, such as "0.5x" or "2x". */
        fun zoomLabel(zoom: Double): String {
            val rounded = (zoom * 10).roundToInt() / 10.0
            return if (rounded == rounded.toInt().toDouble()) "${rounded.toInt()}x" else String.format("%.1fx", rounded)
        }
    }
}
