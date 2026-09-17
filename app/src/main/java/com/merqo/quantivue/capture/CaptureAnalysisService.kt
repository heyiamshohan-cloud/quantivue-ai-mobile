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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.merqo.quantivue.R
import com.merqo.quantivue.core.*
import com.merqo.quantivue.data.PredictionStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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
    private val lifecycleManager = SignalLifecycleManager()
    private var roi: ChartROI? = null
    private var frameNumber = 0L
    private var lastFrameAt: Long? = null
    private var lastPredictionFingerprint: String? = null
    private var lastPublishedSignalState: String? = null
    private var lastRunningIndex: Long? = null
    private var paused = false
    private var running = false
    private var timeframeConfirmed = false
    private var sessionId = "not-started"
    private var framesReceived = 0L
    private var framesProcessed = 0L
    private var framesDropped = 0L
    private var lastSnapshot = AnalysisSnapshot()
    private var pixelBuffer = IntArray(0)
    private var rowBuffer = ByteArray(0)
    private var lastImageWidth = 0
    private var lastImageHeight = 0

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("quantivue-capture", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        captureHandler = Handler(captureThread.looper)
        store = PredictionStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> {
                paused = true
                lifecycleManager.reset()
                publish(lastSnapshot.copy(stage = AnalysisStage.PAUSED, prediction = null, runningCandle = null,
                    errorMessage = "Analysis paused by user"))
                return START_STICKY
            }
            ACTION_RESUME -> {
                paused = false
                publish(lastSnapshot.copy(stage = AnalysisStage.CAPTURING, errorMessage = null))
                return START_STICKY
            }
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
            publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, errorMessage = "Screen capture permission data missing"))
            stopSelf()
            return
        }
        startForegroundCompat()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            logEvent("PERMISSION", "PROJECTION_REVOKED", "MediaProjection grant unavailable")
            publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, errorMessage = "Screen capture permission was revoked"))
            stopSelf()
            return
        }
        logEvent("CAPTURE", "STARTED", "User-approved screen capture started")
        sessionId = UUID.randomUUID().toString()
        try {
            val dm = resources.displayMetrics
            val width = dm.widthPixels.coerceAtMost(1440)
            val height = dm.heightPixels.coerceAtMost(2560)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader!!.setOnImageAvailableListener({ reader -> onImage(reader) }, captureHandler)
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, errorMessage = "Android stopped screen capture"))
                }
            }, captureHandler)
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "Quantivue chart capture", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, captureHandler
            )
            running = true
            tracker.reset(); lifecycleManager.reset(); roi = null; frameNumber = 0; lastFrameAt = null
            lastPredictionFingerprint = null; lastPublishedSignalState = null; lastRunningIndex = null
            framesReceived = 0; framesProcessed = 0; framesDropped = 0; lastImageWidth = 0; lastImageHeight = 0
            publish(AnalysisSnapshot(stage = AnalysisStage.CAPTURING))
            if (canShowOverlay()) overlay = AnalysisOverlay(this).also { it.show() }
        } catch (t: Throwable) {
            logEvent("CAPTURE", "START_FAILED", t.javaClass.simpleName)
            stopCapture()
            publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, errorMessage = "Capture could not start safely"))
            stopSelf()
        }
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
            lifecycleManager.reset()
            publish(lastSnapshot.copy(stage = AnalysisStage.ERROR, prediction = null, runningCandle = null,
                errorMessage = "Frame processing failed safely"))
        } finally {
            image.close(); processing.set(false)
        }
    }

    private fun processFrame(frame: ValidatedFrame) {
        val frameAge = SystemClock.elapsedRealtime() - frame.capturedAtMillis
        if (frameAge > 1_500L) {
            logEvent("CAPTURE", "STALE_FRAME", "ageMs=$frameAge")
            publish(lastSnapshot.copy(stage = AnalysisStage.STALE_FRAME, prediction = null,
                runningCandle = null, errorMessage = "Capture frame is stale"))
            return
        }
        if (lastImageWidth != 0 && (lastImageWidth != frame.width || lastImageHeight != frame.height)) {
            logEvent("CAPTURE", "DISPLAY_SIZE_CHANGED", "context reset")
            tracker.reset(); lifecycleManager.reset(); lastPredictionFingerprint = null; lastPublishedSignalState = null; lastRunningIndex = null; roi = null
        }
        lastImageWidth = frame.width; lastImageHeight = frame.height
        val previous = lastFrameAt
        lastFrameAt = frame.capturedAtMillis
        val valid = FrameValidator.validate(frame.width, frame.height, frame.pixels, frame.capturedAtMillis, previous) ?: return
        val needsRedetect = roi == null || frameNumber % 24L == 0L || roi!!.confidence < .42
        val detected = if (needsRedetect) ChartRoiDetector.detect(valid) else ChartRoiDetector.refine(valid, roi!!)
        val newRoi = detected.roi ?: run {
            roi = null
            tracker.reset()
            lifecycleManager.reset()
            lastPredictionFingerprint = null
            lastPublishedSignalState = null
            lastRunningIndex = null
            publish(lastSnapshot.copy(stage = AnalysisStage.ROI_LOST, roi = null, quality = detected.quality,
                prediction = null, runningCandle = null, confirmedCount = 0,
                framesReceived = framesReceived, framesProcessed = framesProcessed, framesDropped = framesDropped,
                errorMessage = detected.quality.reason))
            return
        }
        val previousRoi = roi
        if (previousRoi != null) {
            val shiftX = abs(previousRoi.left - newRoi.left).toDouble() / previousRoi.width.coerceAtLeast(1)
            val shiftY = abs(previousRoi.top - newRoi.top).toDouble() / previousRoi.height.coerceAtLeast(1)
            val scaleChange = abs(previousRoi.width - newRoi.width).toDouble() / previousRoi.width.coerceAtLeast(1)
            if (shiftX > .25 || shiftY > .25 || scaleChange > .35) {
                logEvent("ROI", "LAYOUT_CHANGED", "context reset after ROI shift")
                tracker.reset(); lifecycleManager.reset(); lastPredictionFingerprint = null; lastPublishedSignalState = null; lastRunningIndex = null
            }
        }
        roi = newRoi
        val candles = CandleDetector.detect(valid, newRoi)
        if (candles.size < 2) {
            val poorQuality = detected.quality.copy(
                candleIdentifiability = 0.0, timeframeConfidence = if (timeframeConfirmed) .85 else 0.0,
                enoughHistory = false, reason = "Candles are not identifiable in the locked chart region"
            )
            tracker.reset(); lifecycleManager.reset(); lastPredictionFingerprint = null; lastPublishedSignalState = null
            publish(lastSnapshot.copy(stage = AnalysisStage.BUILDING_CONTEXT, roi = newRoi, quality = poorQuality,
                prediction = null, runningCandle = null, confirmedCount = 0,
                framesReceived = framesReceived, framesProcessed = framesProcessed, framesDropped = framesDropped,
                errorMessage = poorQuality.reason))
            return
        }
        val tracking = tracker.update(candles, newRoi, frame.capturedAtMillis)
        tracking.newlyConfirmed?.let { closed ->
            val actual = when { closed.bullish -> Direction.CALL; closed.bearish -> Direction.PUT; else -> Direction.NO_TRADE }
            store.recordOutcome(sessionId, closed.index, actual, closed.close - closed.open)
        }
        val running = tracking.running ?: return
        if (lastRunningIndex != null && lastRunningIndex != running.index) {
            lifecycleManager.reset()
            lastPublishedSignalState = null
            logEvent("CANDLE", "TARGET_ADVANCED", "next-candle index=${running.index + 1L}")
        }
        lastRunningIndex = running.index
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
            val rawResult = engine.analyze(tracking.confirmed, running, quality, newRoi.confidence, frame.capturedAtMillis)
            val result = lifecycleManager.apply(rawResult)
            val signalState = "${result.direction}:${result.lifecycle}"
            if (result.inputFingerprint != lastPredictionFingerprint || signalState != lastPublishedSignalState || frameNumber % 30L == 0L) {
                lastPredictionFingerprint = result.inputFingerprint
                lastPublishedSignalState = signalState
                store.insertPrediction(sessionId, result, quality, running, tracking.confirmed.size)
                val snapshot = AnalysisSnapshot(AnalysisStage.ANALYZING, newRoi, quality, result, running,
                    tracking.confirmed.size, framesReceived, framesProcessed, framesDropped,
                    SystemClock.elapsedRealtime() - started, null)
                publish(snapshot)
            }
        } else {
            val retainPrediction = quality.score >= .58 && newRoi.confidence >= .42
            publish(lastSnapshot.copy(stage = AnalysisStage.ANALYZING, roi = newRoi, quality = quality,
                prediction = if (retainPrediction) lastSnapshot.prediction else null,
                runningCandle = running, confirmedCount = tracking.confirmed.size,
                framesReceived = framesReceived, framesProcessed = framesProcessed, framesDropped = framesDropped,
                errorMessage = if (retainPrediction) null else "Chart quality is insufficient for a stable result"))
        }
    }

    private fun copyImage(image: Image): ValidatedFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        if (pixelBuffer.size != width * height) pixelBuffer = IntArray(width * height)
        if (rowBuffer.size != rowStride) rowBuffer = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val bytes = minOf(rowStride, buffer.remaining())
            buffer.get(rowBuffer, 0, bytes)
            for (x in 0 until width) {
                val offset = x * pixelStride
                if (offset + 3 >= bytes) continue
                // ImageReader RGBA_8888 bytes are R,G,B,A; store as 0xAARRGGBB.
                pixelBuffer[y * width + x] = ((rowBuffer[offset + 3].toInt() and 255) shl 24) or
                    ((rowBuffer[offset].toInt() and 255) shl 16) or ((rowBuffer[offset + 1].toInt() and 255) shl 8) or
                    (rowBuffer[offset + 2].toInt() and 255)
            }
        }
        val timestampMillis = image.timestamp.takeIf { it > 0L }?.div(1_000_000L) ?: SystemClock.elapsedRealtime()
        return ValidatedFrame(width, height, pixelBuffer, timestampMillis)
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
            stage == AnalysisStage.ROI_LOST -> "Chart ROI lost; re-detecting"
            stage == AnalysisStage.STALE_FRAME -> "Capture frame stale; waiting"
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
        pixelBuffer = IntArray(0); rowBuffer = ByteArray(0); lastFrameAt = null; lastRunningIndex = null; lastImageWidth = 0; lastImageHeight = 0
        lifecycleManager.reset()
        publish(lastSnapshot.copy(stage = AnalysisStage.IDLE, roi = null, quality = null, prediction = null,
            runningCandle = null, confirmedCount = 0))
    }

    override fun onDestroy() {
        stopCapture(); store.close(); captureThread.quitSafely(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class AnalysisOverlay(private val context: Context) {
        private val wm = context.getSystemService(WINDOW_SERVICE) as WindowManager
        private val text = TextView(context).apply {
            setTextColor(0xffe8f0ff.toInt()); textSize = 12f; setPadding(24, 18, 24, 10)
            this.text = "QUANTIVUE AI\nANALYZING"
        }
        private val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xe6111a2d.toInt())
            elevation = 8f
            addView(text, LinearLayout.LayoutParams(-2, -2))
        }
        private var attached = false
        private var paused = false
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        init {
            val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            val pause = Button(context).apply {
                text = "Pause"; textSize = 10f; setTextColor(0xffe8f0ff.toInt()); setPadding(10, 0, 10, 0)
                setOnClickListener {
                    val action = if (paused) CaptureAnalysisService.ACTION_RESUME else CaptureAnalysisService.ACTION_PAUSE
                    context.startService(Intent(context, CaptureAnalysisService::class.java).setAction(action))
                    paused = !paused; text = if (paused) "Resume" else "Pause"
                }
            }
            val close = Button(context).apply {
                text = "Close"; textSize = 10f; setTextColor(0xffe8f0ff.toInt()); setPadding(10, 0, 10, 0)
                setOnClickListener { hide() }
            }
            controls.addView(pause, LinearLayout.LayoutParams(-2, 40))
            controls.addView(close, LinearLayout.LayoutParams(-2, 40))
            container.addView(controls)
        }

        fun show() {
            if (attached) return
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 120 }
            text.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = startX - (event.rawX - downX).toInt(); params.y = startY + (event.rawY - downY).toInt()
                        try { wm.updateViewLayout(container, params) } catch (_: Throwable) {}
                        true
                    }
                    else -> false
                }
            }
            try { wm.addView(container, params); attached = true } catch (_: Throwable) { attached = false }
        }

        fun update(r: PredictionResult, quality: Double) {
            if (!attached) return
            val title = if (r.direction == Direction.NO_TRADE) "NO TRADE" else "NEXT ${r.direction}"
            text.text = "QUANTIVUE AI\n$title\nCALL ${"%.0f".format(r.bullishProbability * 100)}%  PUT ${"%.0f".format(r.bearishProbability * 100)}%\n${r.uncertainty} · ROI ${"%.0f".format(quality * 100)}%\n${r.gateReason}"
        }

        fun hide() {
            if (attached) { try { wm.removeView(container) } catch (_: Throwable) {}; attached = false }
        }
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
