package com.carehospital.entscope

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carehospital.entscope.databinding.ActivityMainBinding
import com.carehospital.entscope.databinding.SheetControlsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * CARE ENT Scope Camera - Android viewer.
 *
 * Mirrors the Windows workstation build:
 *   - the welcome panel with its Connect button is on screen only while there
 *     is no picture, and disappears the moment video starts;
 *   - Full Screen shows the video alone, with all chrome hidden;
 *   - rounded buttons and the same clinical palette throughout.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var stream: ScopeStream? = null
    private var recorder: VideoRecorder? = null
    private var recordingStartedAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val encoderExecutor = Executors.newSingleThreadExecutor()

    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val framePosted = AtomicBoolean(false)
    private val encodeBacklog = AtomicInteger(0)

    private var framesSinceTick = 0
    private var displayFps = 0.0
    private var lastFpsAt = 0L
    private var isFullscreen = false
    private var overlayShown: Boolean? = null

    companion object {
        private const val PREFS = "scope_prefs"
        private const val KEY_IP = "camera_ip"
        private const val KEY_PORT = "camera_port"
        private const val KEY_AUTO = "auto_connect"
        private const val DEFAULT_IP = "192.168.10.123"
        private const val DEFAULT_PORT = 8030
        private const val RECORD_FPS = 15
        private const val MAX_ENCODE_BACKLOG = 3
    }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled at save time */ }

    // ===================================================================
    // lifecycle
    // ===================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowInsets()
        installBackHandler()

        wireButtons()
        syncOverlay(force = true)
        syncConnectionChrome()
        startUiTicker()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (prefs.getBoolean(KEY_AUTO, true)) {
            handler.postDelayed({ connectCamera() }, 350)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopRecording(silent = true)
        stream?.stop()
        stream = null
        encoderExecutor.shutdownNow()
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) stream?.stop()
    }

    /** Back leaves full screen first, then behaves normally. */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    setFullscreen(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Android 15 draws apps edge to edge, so keep the chrome clear of the
     * status and navigation bars instead of letting it slide underneath.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(
                bars.left + dp(16), bars.top + dp(10),
                bars.right + dp(16), dp(22)
            )
            binding.deck.setPadding(
                bars.left + dp(16), dp(20),
                bars.right + dp(16), bars.bottom + dp(12)
            )
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ===================================================================
    // wiring
    // ===================================================================
    private fun wireButtons() = with(binding) {
        btnConnectBig.setOnClickListener { connectCamera() }
        btnAbout.setOnClickListener { startActivity(Intent(this@MainActivity, AboutActivity::class.java)) }
        btnFullscreen.setOnClickListener { setFullscreen(!isFullscreen) }
        btnControls.setOnClickListener { showControlSheet() }

        btnSnapshot.setOnClickListener { captureSnapshot() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnFreeze.setOnClickListener { toggleFreeze() }

        btnFit.setOnClickListener { scopeView.zoom = 1f; updateStats() }
        btnZoomIn.setOnClickListener { scopeView.zoom += 0.25f; updateStats() }
        btnZoomOut.setOnClickListener { scopeView.zoom -= 0.25f; updateStats() }
        btnMirror.setOnClickListener { scopeView.mirror = !scopeView.mirror }
        btnRotate.setOnClickListener { scopeView.rotation90 = scopeView.rotation90 + 90 }

        scopeView.onDoubleTap = { setFullscreen(!isFullscreen) }
        scopeView.onTransformChanged = { updateStats() }
    }

    // ===================================================================
    // connection
    // ===================================================================
    private fun cameraIp(): String = prefs.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
    private fun cameraPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    private fun connectCamera() {
        disconnectCamera(quiet = true)

        val scope = ScopeStream(applicationContext, cameraIp(), cameraPort())
        scope.onFrame = { bitmap -> deliverFrame(bitmap) }
        scope.onError = { message ->
            runOnUiThread { toast(message) }
        }
        scope.start()
        stream = scope

        binding.overlayTitle.setText(R.string.waiting_for_camera)
        binding.overlaySub.setText(R.string.waiting_sub)
        syncOverlay()
        syncConnectionChrome()
    }

    private fun disconnectCamera(quiet: Boolean = false) {
        stopRecording(silent = true)
        stream?.stop()
        stream = null
        pendingFrame.getAndSet(null)?.recycle()

        // Clearing the frame is what brings the welcome panel back.
        binding.scopeView.clearFrame()
        binding.overlayTitle.setText(R.string.camera_ready)
        binding.overlaySub.setText(R.string.camera_ready_sub)
        binding.statsText.text = ""
        syncOverlay()
        syncConnectionChrome()
        if (!quiet) toast(getString(R.string.disconnect))
    }

    /**
     * Frames arrive on the receive thread far faster than the UI needs them.
     * Keep only the newest one and post a single update, so the main thread
     * never builds a backlog.
     */
    private fun deliverFrame(bitmap: Bitmap) {
        pendingFrame.getAndSet(bitmap)?.recycle()
        if (framePosted.compareAndSet(false, true)) {
            handler.post {
                framePosted.set(false)
                val frame = pendingFrame.getAndSet(null) ?: return@post
                binding.scopeView.setFrame(frame)
                framesSinceTick++
                syncOverlay()
                if (recorder?.isRecording == true) feedRecorder()
            }
        }
    }

    // ===================================================================
    // welcome overlay - visible only while there is no picture
    // ===================================================================
    private fun syncOverlay(force: Boolean = false) {
        val shouldShow = !binding.scopeView.hasFrame()
        if (force || shouldShow != overlayShown) {
            overlayShown = shouldShow
            binding.connectOverlay.visibility = if (shouldShow) View.VISIBLE else View.GONE
        }
        if (shouldShow) {
            val connecting = stream?.running == true
            binding.btnConnectBig.setText(
                if (connecting) R.string.connecting else R.string.connect_camera
            )
            binding.btnConnectBig.isEnabled = !connecting
        }
    }

    private fun syncConnectionChrome() {
        val live = stream?.running == true
        val hasSignal = live && binding.scopeView.hasFrame() &&
            (System.currentTimeMillis() - (stream?.lastFrameAt ?: 0L)) < 2500

        val (label, color) = when {
            !live -> R.string.status_offline to R.color.text_muted
            hasSignal -> R.string.status_live to R.color.green
            else -> R.string.status_connecting to R.color.amber
        }
        binding.statusBadge.setText(label)
        binding.statusBadge.setTextColor(ContextCompat.getColor(this, color))
    }

    // ===================================================================
    // full screen - video only
    // ===================================================================
    private fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            binding.topBar.visibility = View.GONE
            binding.deck.visibility = View.GONE
            binding.statsText.visibility = View.GONE
            binding.btnFullscreen.setText(R.string.exit_full_screen)
            toast(getString(R.string.exit_full_screen) + ": double-tap or Back")
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            binding.topBar.visibility = View.VISIBLE
            binding.deck.visibility = View.VISIBLE
            binding.statsText.visibility = View.VISIBLE
            binding.btnFullscreen.setText(R.string.full_screen)
        }
    }

    // ===================================================================
    // capture
    // ===================================================================
    private fun captureSnapshot() {
        val bitmap = binding.scopeView.processedBitmap()
        if (bitmap == null) {
            toast(getString(R.string.no_frame))
            return
        }
        encoderExecutor.execute {
            val name = MediaSaver.saveJpeg(applicationContext, bitmap)
            bitmap.recycle()
            runOnUiThread {
                toast(
                    if (name != null) getString(R.string.snapshot_saved, name)
                    else getString(R.string.snapshot_failed)
                )
            }
        }
    }

    private fun toggleFreeze() {
        val view = binding.scopeView
        view.frozen = !view.frozen
        binding.btnFreeze.setText(if (view.frozen) R.string.resume else R.string.freeze)
    }

    private fun toggleRecording() {
        if (recorder?.isRecording == true) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val probe = binding.scopeView.processedBitmap()
        if (probe == null) {
            toast(getString(R.string.no_frame))
            return
        }
        val rec = VideoRecorder(probe.width, probe.height, RECORD_FPS)
        probe.recycle()

        if (!rec.start(cacheDir)) {
            toast(getString(R.string.recording_failed))
            return
        }
        recorder = rec
        recordingStartedAt = System.currentTimeMillis()
        binding.btnRecord.setText(R.string.stop_recording)
        binding.recBadge.visibility = View.VISIBLE
        toast(getString(R.string.recording_started))
    }

    /**
     * Frames can arrive faster than the encoder drains them. Cap how many may
     * be queued at once and drop the surplus, so a slow encoder costs frames
     * rather than growing the queue until the app runs out of memory.
     */
    private fun feedRecorder() {
        val rec = recorder ?: return
        if (encodeBacklog.get() >= MAX_ENCODE_BACKLOG) return
        val frame = binding.scopeView.processedBitmap() ?: return
        encodeBacklog.incrementAndGet()
        encoderExecutor.execute {
            try {
                rec.encode(frame)
            } finally {
                frame.recycle()
                encodeBacklog.decrementAndGet()
            }
        }
    }

    private fun stopRecording(silent: Boolean = false) {
        val rec = recorder ?: return
        recorder = null
        binding.btnRecord.setText(R.string.start_recording)
        binding.recBadge.visibility = View.GONE

        encoderExecutor.execute {
            val file = rec.stop()
            val name = file?.let { MediaSaver.publishVideo(applicationContext, it) }
            if (!silent) {
                runOnUiThread {
                    toast(
                        if (name != null) getString(R.string.recording_saved, name)
                        else getString(R.string.recording_failed)
                    )
                }
            }
        }
    }

    // ===================================================================
    // control sheet
    // ===================================================================
    private fun showControlSheet() {
        val sheetBinding = SheetControlsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        val view = binding.scopeView

        with(sheetBinding) {
            inputIp.setText(cameraIp())
            inputPort.setText(cameraPort().toString())
            switchAutoConnect.isChecked = prefs.getBoolean(KEY_AUTO, true)

            sliderBrightness.value = view.brightness.toFloat()
            sliderContrast.value = view.contrast.toFloat()
            sliderSaturation.value = view.saturation.toFloat()
            valueBrightness.text = view.brightness.toString()
            valueContrast.text = view.contrast.toString()
            valueSaturation.text = view.saturation.toString()

            switchMirror.isChecked = view.mirror
            switchFlip.isChecked = view.flipVertical
            switchGrid.isChecked = view.showGrid
            switchCrosshair.isChecked = view.showCrosshair
            switchTimestamp.isChecked = view.showTimestamp

            sliderBrightness.addOnChangeListener { _, value, _ ->
                view.brightness = value.toInt()
                valueBrightness.text = value.toInt().toString()
            }
            sliderContrast.addOnChangeListener { _, value, _ ->
                view.contrast = value.toInt()
                valueContrast.text = value.toInt().toString()
            }
            sliderSaturation.addOnChangeListener { _, value, _ ->
                view.saturation = value.toInt()
                valueSaturation.text = value.toInt().toString()
            }

            switchMirror.setOnCheckedChangeListener { _, checked -> view.mirror = checked }
            switchFlip.setOnCheckedChangeListener { _, checked -> view.flipVertical = checked }
            switchGrid.setOnCheckedChangeListener { _, checked -> view.showGrid = checked }
            switchCrosshair.setOnCheckedChangeListener { _, checked -> view.showCrosshair = checked }
            switchTimestamp.setOnCheckedChangeListener { _, checked -> view.showTimestamp = checked }
            switchAutoConnect.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_AUTO, checked).apply()
            }

            btnSheetRotate.setOnClickListener { view.rotation90 = view.rotation90 + 90 }

            btnSheetConnect.setOnClickListener {
                val ip = inputIp.text?.toString()?.trim().orEmpty()
                val port = inputPort.text?.toString()?.trim()?.toIntOrNull() ?: -1
                if (!isValidIpv4(ip) || port !in 1..65535) {
                    toast(getString(R.string.invalid_endpoint))
                    return@setOnClickListener
                }
                prefs.edit().putString(KEY_IP, ip).putInt(KEY_PORT, port).apply()
                dialog.dismiss()
                connectCamera()
            }

            btnSheetDisconnect.setOnClickListener {
                dialog.dismiss()
                disconnectCamera()
            }

            btnResetAll.setOnClickListener {
                view.resetView()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull()
            n != null && n in 0..255 && (part.length == 1 || !part.startsWith("0"))
        }
    }

    // ===================================================================
    // periodic chrome update
    // ===================================================================
    private fun startUiTicker() {
        lastFpsAt = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val elapsed = (now - lastFpsAt) / 1000.0
                if (elapsed >= 0.5) {
                    displayFps = framesSinceTick / elapsed
                    framesSinceTick = 0
                    lastFpsAt = now
                }
                updateStats()
                syncConnectionChrome()
                syncOverlay()
                updateRecordingBadge(now)
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(tick, 500)
    }

    private fun updateStats() {
        val scope = stream
        binding.statsText.text = if (scope == null) "" else String.format(
            "%.0f fps   Zoom %.1fx   Packets %d   Frames %d   Decoded %d",
            displayFps, binding.scopeView.zoom,
            scope.packetCount, scope.frameCount, scope.decodedCount
        )
    }

    private fun updateRecordingBadge(now: Long) {
        if (recorder?.isRecording != true) return
        val seconds = ((now - recordingStartedAt) / 1000).toInt()
        binding.recBadge.text = String.format("REC  %02d:%02d", seconds / 60, seconds % 60)
        binding.recBadge.setTextColor(
            if (seconds % 2 == 0) ContextCompat.getColor(this, R.color.red) else Color.TRANSPARENT
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
