package com.merqo.quantivue.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.merqo.quantivue.R
import com.merqo.quantivue.core.*
import com.merqo.quantivue.data.PredictionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class CaptureAnalysisService : Service() {
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: AnalysisOverlay? = null
    private lateinit var store: PredictionStore
    private val processing = AtomicBoolean(false)
    private val tracker = CandleSequenceTracker()
    private val engine = AnalysisEngine()
    private var roi: ChartROI? = null
    private var frameNumber = 0L
    private var lastFrameAt: Long? = null
    private var lastPredictionFingerprint: String? = null
    private var paused = false
    private var running = false
    private var timeframeConfirmed = false
    private var framesReceived = 0L
    private var framesProcessed = 0L
    private var framesDropped = 0L
    private var lastSnapshot = AnalysisSnapshot()

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("quantivue-capture", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        captureHandler = Handler(captureThread.looper)
        store = PredictionStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { paused = true; publish(lastSnapshot.copy(stage = AnalysisStage.PAUSED)); return START_STICKY }
            ACTION_RESUME -> { paused = false; publish(lastSnapshot.copy(stage = AnalysisStage.CAPTURING)); return START_STICKY }
            ACTION_TIMEFRAME -> { timeframeConfirmed = intent.getBooleanExtra(EXTRA_ENABLED, false); return START_STICKY }
            ACTION_START -> startCapture(intent)
        }
        return START_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (running) return
        timeframeConfirmed = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TIMEFRAME, false)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtraCompat<Intent>(EXTRA_DATA) ?: run {
            publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, errorMessage = "Screen capture permission data missing")); return
        }
        startForegroundCompat()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            logEvent("PERMISSION", "PROJECTION_REVOKED", "MediaProjection grant unavailable")
            publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, errorMessage = "Screen capture permission was revoked")); return
        }
        logEvent("CAPTURE", "STARTED", "User-approved screen capture started")
        val dm = resources.displayMetrics
        val width = dm.widthPixels.coerceAtMost(1440)
        val height = dm.heightPixels.coerceAtMost(2560)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader -> onImage(reader) }, captureHandler)
        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, errorMessage = "Android stopped screen capture"))
                stopCapture()
            }
        }, captureHandler)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "Quantivue chart capture", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, captureHandler
        )
        running = true
        tracker.reset(); roi = null; frameNumber = 0; lastPredictionFingerprint = null
        publish(AnalysisSnapshot(stage = AnalysisStage.CAPTURING))
        if (canShowOverlay()) overlay = AnalysisOverlay(this).also { it.show() }
    }

    private fun onImage(reader: ImageReader) {
        framesReceived++
        if (paused || !processing.compareAndSet(false, true)) {
            framesDropped++
            closeLatest(reader)
            return
        }
        val image = reader.acquireLatestImage()
        if (image == null) { processing.set(false); return }
        frameNumber++
        try {
            if (frameNumber % frameInterval() != 0L) return
            val frame = copyImage(image)
            processFrame(frame)
        } catch (t: Throwable) {
            logEvent("ERROR", "FRAME_PROCESSING", t.javaClass.simpleName)
            publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, errorMessage = "Frame processing failed safely"))
        } finally {
            image.close(); processing.set(false)
        }
    }

    private fun processFrame(frame: ValidatedFrame) {
        val previous = lastFrameAt
        lastFrameAt = frame.capturedAtMillis
        val valid = FrameValidator.validate(frame.width, frame.height, frame.pixels, frame.capturedAtMillis, previous) ?: return
        val needsRedetect = roi == null || frameNumber % 24L == 0L || roi!!.confidence < .42
        val detected = if (needsRedetect) ChartRoiDetector.detect(valid) else ChartRoiDetector.refine(valid, roi!!)
        val newRoi = detected.roi ?: run {
            publish(lastSnapshot.copy(stage = AnalysisStage.DETECTING_CHART, quality = detected.quality,
                framesReceived = framesReceived, framesProcessed = framesProcessed, framesDropped = framesDropped,
                errorMessage = detected.quality.reason))
            return
        }
        roi = newRoi
        val candles = CandleDetector.detect(valid, newRoi)
        val tracking = tracker.update(candles, newRoi, frame.capturedAtMillis)
        tracking.newlyConfirmed?.let { closed ->
            val actual = when { closed.bullish -> Direction.CALL; closed.bearish -> Direction.PUT; else -> Direction.NO_TRADE }
            store.recordOutcome(closed.index, actual, closed.close - closed.open, frame.capturedAtMillis)
        }
        val running = tracking.running ?: return
        framesProcessed++
        // The app does not silently infer a timeframe. A user-confirmed 1M chart is required.
        val quality = detected.quality.copy(
            timeframeConfidence = if (timeframeConfirmed) .85 else 0.0,
            enoughHistory = tracking.confirmed.size >= 4,
            reason = if (timeframeConfirmed) null else "1-minute timeframe is not verified"
        )
        val deep = frameNumber % 6L == 0L || tracking.confirmed.size >= 12 && running.state == CandleState.CLOSING
        if (deep) {
            val started = SystemClock.elapsedRealtime()
            val result = engine.analyze(tracking.confirmed, running, quality, newRoi.confidence, frame.capturedAtMillis)
            if (result.inputFingerprint != lastPredictionFingerprint || frameNumber % 30L == 0L) {
                lastPredictionFingerprint = result.inputFingerprint
                store.insertPrediction(result, quality, running, tracking.confirmed.size)
                val snapshot = AnalysisSnapshot(AnalysisStage.ANALYZING, newRoi, quality, result, running,
                    tracking.confirmed.size, framesReceived, framesProcessed, framesDropped,
                    SystemClock.elapsedRealtime() - started, null)
                publish(snapshot)
            }
        } else {
            publish(lastSnapshot.copy(stage = AnalysisStage.ANALYZING, roi = newRoi, quality = quality,
                runningCandle = running, confirmedCount = tracking.confirmed.size,
                framesReceived = framesReceived, framesProcessed = framesProcessed, framesDropped = framesDropped))
        }
    }

    private fun copyImage(image: Image): ValidatedFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val packed = IntArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
            for (x in 0 until width) {
                val offset = x * pixelStride
                if (offset + 3 >= row.size) continue
                // ImageReader RGBA_8888 bytes are R,G,B,A; store as 0xAARRGGBB.
                packed[y * width + x] = ((row[offset + 3].toInt() and 255) shl 24) or
                    ((row[offset].toInt() and 255) shl 16) or ((row[offset + 1].toInt() and 255) shl 8) or
                    (row[offset + 2].toInt() and 255)
            }
        }
        return ValidatedFrame(width, height, packed, SystemClock.elapsedRealtime())
    }

    private fun frameInterval(): Long {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
        return when {
            power.isPowerSaveMode -> 12L
            lastSnapshot.runningCandle?.state == CandleState.CLOSING -> 2L
            lastSnapshot.prediction?.let { maxOf(it.bullishProbability, it.bearishProbability) < .57 } == true -> 3L
            battery.isCharging -> 3L
            else -> 6L
        }
    }

    private fun closeLatest(reader: ImageReader) { reader.acquireLatestImage()?.close() }

    private fun publish(snapshot: AnalysisSnapshot) {
        lastSnapshot = snapshot
        val result = snapshot.prediction
        val intent = Intent(ACTION_UPDATE).setPackage(packageName).apply {
            putExtra("stage", snapshot.stage.name)
            putExtra("direction", result?.direction?.name ?: Direction.NO_TRADE.name)
            putExtra("bullishProbability", result?.bullishProbability ?: .5)
            putExtra("bearishProbability", result?.bearishProbability ?: .5)
            putExtra("uncertainty", result?.uncertainty?.name ?: Uncertainty.UNKNOWN.name)
            putExtra("gateReason", result?.gateReason ?: "Waiting for chart data")
            putExtra("regime", result?.regime?.name ?: Regime.UNKNOWN.name)
            putExtra("confirmedCount", snapshot.confirmedCount)
            putExtra("framesReceived", snapshot.framesReceived)
            putExtra("framesProcessed", snapshot.framesProcessed)
            putExtra("framesDropped", snapshot.framesDropped)
            putExtra("latency", snapshot.lastLatencyMillis)
            putExtra("roiConfidence", snapshot.roi?.confidence ?: 0.0)
            putExtra("error", snapshot.errorMessage)
        }
        sendBroadcast(intent)
        result?.let { overlay?.update(it, snapshot.quality?.score ?: 0.0) }
        updateNotification(result, snapshot.stage)
    }

    private fun startForegroundCompat() {
        val channel = NotificationChannel(CHANNEL, "Live chart analysis", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when Quantivue is using user-approved screen capture"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_quantivue).setContentTitle("Quantivue AI")
            .setContentText("Screen capture is active; analysis stays on device")
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(result: PredictionResult?, stage: AnalysisStage) {
        val text = when {
            stage == AnalysisStage.PAUSED -> "Analysis paused"
            result == null -> "Detecting a user-visible chart"
            result.direction == Direction.NO_TRADE -> "NO TRADE · ${result.gateReason}"
            else -> "Next candle ${result.direction} · ${result.displayedProbability}%"
        }
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_quantivue)
            .setContentTitle("Quantivue AI · live").setContentText(text).setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
    }

    private fun canShowOverlay(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun logEvent(category: String, code: String, detail: String) {
        Log.i("Quantivue/$category", "code=$code detail=$detail")
    }

    private fun stopCapture() {
        running = false; paused = false
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        projection?.stop()
        overlay?.hide(); overlay = null
        publish(lastSnapshot.copy(stage = AnalysisStage.IDLE))
    }

    override fun onDestroy() {
        stopCapture(); store.close(); captureThread.quitSafely(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class AnalysisOverlay(private val context: Context) {
        private val wm = context.getSystemService(WINDOW_SERVICE) as WindowManager
        private val view = TextView(context).apply {
            setTextColor(0xffe8f0ff.toInt()); setBackgroundColor(0xe6111a2d.toInt()); setPadding(24, 18, 24, 18)
            textSize = 12f; elevation = 8f; text = "QUANTIVUE AI\nANALYZING"
        }
        private var attached = false
        private var layoutParams: WindowManager.LayoutParams? = null
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        fun show() {
            if (attached) return
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 120 }
            layoutParams = params
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; true }
                    android.view.MotionEvent.ACTION_MOVE -> { params.x = startX - (event.rawX - downX).toInt(); params.y = startY + (event.rawY - downY).toInt(); try { wm.updateViewLayout(view, params) } catch (_: Throwable) {}; true }
                    else -> true
                }
            }
            try { wm.addView(view, params); attached = true } catch (_: Throwable) { attached = false }
        }
        fun update(r: PredictionResult, quality: Double) {
            if (!attached) return
            val title = if (r.direction == Direction.NO_TRADE) "NO TRADE" else "NEXT ${r.direction}"
            view.text = "QUANTIVUE AI\n$title\nCALL ${"%.0f".format(r.bullishProbability * 100)}%  PUT ${"%.0f".format(r.bearishProbability * 100)}%\n${r.uncertainty} · ${r.gateReason}"
        }
        fun hide() { if (attached) { try { wm.removeView(view) } catch (_: Throwable) {}; attached = false } }
    }

    companion object {
        const val ACTION_START = "com.merqo.quantivue.START"
        const val ACTION_STOP = "com.merqo.quantivue.STOP"
        const val ACTION_PAUSE = "com.merqo.quantivue.PAUSE"
        const val ACTION_RESUME = "com.merqo.quantivue.RESUME"
        const val ACTION_TIMEFRAME = "com.merqo.quantivue.TIMEFRAME"
        const val ACTION_UPDATE = "com.merqo.quantivue.UPDATE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "projectionData"
        const val EXTRA_ENABLED = "enabled"
        private const val CHANNEL = "quantivue-live"
        private const val NOTIFICATION_ID = 4101
        private const val PREFS = "quantivue"
        private const val KEY_TIMEFRAME = "timeframe_confirmed"
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)
