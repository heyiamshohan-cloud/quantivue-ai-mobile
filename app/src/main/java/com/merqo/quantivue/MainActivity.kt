package com.merqo.quantivue

import android.Manifest
import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.merqo.quantivue.capture.CaptureAnalysisService
import com.merqo.quantivue.core.*
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var dashboard: DashboardView
    private lateinit var statusText: TextView
    private lateinit var timeframeButton: Button
    private var timeframeConfirmed = false
    private var serviceStarted = false
    private var receiver: BroadcastReceiver? = null
    private val projectionRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 16, 31)
        window.navigationBarColor = Color.rgb(9, 16, 31)
        timeframeConfirmed = getSharedPreferences("quantivue", MODE_PRIVATE).getBoolean("timeframe_confirmed", false)
        setContentView(buildUi())
        registerUpdates()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(9, 16, 31)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)) }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply {
            text = "QUANTIVUE AI\nMERQO / LIVE ANALYSIS"; setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setContentDescription("Quantivue AI by MERQO")
        }
        header.addView(brand, LinearLayout.LayoutParams(0, dp(58), 1f))
        statusText = TextView(this).apply { text = "● READY"; setTextColor(Color.rgb(99, 230, 190)); textSize = 12f; gravity = Gravity.CENTER }
        header.addView(statusText, LinearLayout.LayoutParams(dp(132), dp(42)))
        column.addView(header)

        dashboard = DashboardView(this)
        column.addView(dashboard, LinearLayout.LayoutParams(-1, 0, 1f))

        val info = TextView(this).apply {
            text = "SOURCE OF TRUTH  ·  user-visible chart pixels only\nNo Quotex API • no automated trading • analysis stays on device"
            setTextColor(Color.rgb(135, 151, 180)); textSize = 11f; setPadding(dp(2), dp(10), dp(2), dp(8))
        }
        column.addView(info)

        timeframeButton = actionButton(if (timeframeConfirmed) "✓ 1M timeframe confirmed" else "Confirm visible chart is 1M")
        timeframeButton.setOnClickListener {
            timeframeConfirmed = !timeframeConfirmed
            getSharedPreferences("quantivue", MODE_PRIVATE).edit().putBoolean("timeframe_confirmed", timeframeConfirmed).apply()
            timeframeButton.text = if (timeframeConfirmed) "✓ 1M timeframe confirmed" else "Confirm visible chart is 1M"
        }
        column.addView(timeframeButton, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(7) })

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; weightSum = 4f }
        val start = actionButton("START").apply { setOnClickListener { requestCapture() } }
        val pause = actionButton("PAUSE").apply { setOnClickListener { sendServiceCommand(CaptureAnalysisService.ACTION_PAUSE) } }
        val resume = actionButton("RESUME").apply { setOnClickListener { sendServiceCommand(CaptureAnalysisService.ACTION_RESUME) } }
        val stop = actionButton("STOP").apply { setOnClickListener { sendServiceCommand(CaptureAnalysisService.ACTION_STOP) } }
        listOf(start, pause, resume, stop).forEach { controls.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }) }
        column.addView(controls)

        val secondary = LinearLayout(this).apply { gravity = Gravity.CENTER; weightSum = 2f }
        val overlay = actionButton("FLOATING OVERLAY").apply { setOnClickListener { openOverlaySettings() } }
        val reset = actionButton("RESET SESSION").apply { setOnClickListener { dashboard.reset(); sendServiceCommand(CaptureAnalysisService.ACTION_STOP) } }
        secondary.addView(overlay, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginTop = dp(7); marginEnd = dp(3) })
        secondary.addView(reset, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginTop = dp(7); marginStart = dp(3) })
        column.addView(secondary)
        return root
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4103)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    @Deprecated("Activity result API kept dependency-free for the native shell")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequest) return
        if (resultCode != RESULT_OK || data == null) {
            dashboard.setError("Screen capture permission was cancelled")
            return
        }
        val intent = Intent(this, CaptureAnalysisService::class.java).apply {
            action = CaptureAnalysisService.ACTION_START
            putExtra(CaptureAnalysisService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureAnalysisService.EXTRA_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        serviceStarted = true
    }

    private fun sendServiceCommand(action: String, enabled: Boolean? = null) {
        if (!serviceStarted) return
        val intent = Intent(this, CaptureAnalysisService::class.java).setAction(action)
        if (enabled != null) intent.putExtra(CaptureAnalysisService.EXTRA_ENABLED, enabled)
        try { startService(intent) } catch (_: Throwable) { }
        if (action == CaptureAnalysisService.ACTION_STOP) serviceStarted = false
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else Toast.makeText(this, "Overlay permission is already enabled. Start analysis to show it.", Toast.LENGTH_SHORT).show()
    }

    private fun registerUpdates() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != CaptureAnalysisService.ACTION_UPDATE) return
                val stage = intent.getStringExtra("stage") ?: AnalysisStage.IDLE.name
                val direction = intent.getStringExtra("direction") ?: Direction.NO_TRADE.name
                val error = intent.getStringExtra("error")
                dashboard.update(UiState(
                    stage = stage, direction = direction,
                    call = intent.getDoubleExtra("bullishProbability", .5), put = intent.getDoubleExtra("bearishProbability", .5),
                    uncertainty = intent.getStringExtra("uncertainty") ?: Uncertainty.UNKNOWN.name,
                    regime = intent.getStringExtra("regime") ?: Regime.UNKNOWN.name,
                    gateReason = intent.getStringExtra("gateReason") ?: "Waiting for chart data",
                    candles = intent.getIntExtra("confirmedCount", 0), latency = intent.getLongExtra("latency", 0L),
                    roi = intent.getDoubleExtra("roiConfidence", 0.0), dropped = intent.getLongExtra("framesDropped", 0L), error = error))
                statusText.text = when (stage) {
                    AnalysisStage.ANALYZING.name, AnalysisStage.CAPTURING.name -> "● CAPTURING"
                    AnalysisStage.PAUSED.name -> "Ⅱ PAUSED"
                    AnalysisStage.ERROR.name -> "● NEEDS ATTENTION"
                    else -> "● READY"
                }
                statusText.setTextColor(if (stage == AnalysisStage.ERROR.name) Color.rgb(255, 130, 130) else Color.rgb(99, 230, 190))
            }
        }
        val filter = IntentFilter(CaptureAnalysisService.ACTION_UPDATE)
        val localReceiver = receiver ?: return
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(localReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(localReceiver, filter)
    }

    override fun onDestroy() { receiver?.let { unregisterReceiver(it) }; super.onDestroy() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun actionButton(label: String): Button = Button(this).apply {
        text = label; textSize = 10f; isAllCaps = false; setTextColor(Color.rgb(225, 235, 250));
        background = GradientDrawable().apply { setColor(Color.rgb(22, 34, 57)); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.rgb(48, 69, 96)) }
        minHeight = 0; minimumHeight = 0; stateListAnimator = null; contentDescription = label
    }

    data class UiState(
        val stage: String = AnalysisStage.IDLE.name, val direction: String = Direction.NO_TRADE.name,
        val call: Double = .5, val put: Double = .5, val uncertainty: String = Uncertainty.UNKNOWN.name,
        val regime: String = Regime.UNKNOWN.name, val gateReason: String = "Waiting for chart data",
        val candles: Int = 0, val latency: Long = 0, val roi: Double = 0.0, val dropped: Long = 0, val error: String? = null
    )

    private inner class DashboardView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var state = UiState()
        private val teal = Color.rgb(99, 230, 190); private val ink = Color.rgb(232, 240, 255); private val muted = Color.rgb(135, 151, 180)
        fun update(value: UiState) { state = value; contentDescription = accessibilitySummary(); invalidate() }
        fun reset() { state = UiState(); invalidate() }
        fun setError(message: String) { state = state.copy(stage = AnalysisStage.ERROR.name, error = message); invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas); val w = width.toFloat(); val h = height.toFloat(); val pad = dp(2).toFloat()
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(16, 25, 44)
            canvas.drawRoundRect(pad, pad, w - pad, h - pad, dp(18).toFloat(), dp(18).toFloat(), paint)
            val centerX = w / 2f
            text(canvas, "LIVE DECISION SUPPORT", dp(22).toFloat(), dp(34).toFloat(), 11f, muted, false)
            text(canvas, "NEXT 1-MINUTE CANDLE", dp(22).toFloat(), dp(60).toFloat(), 13f, ink, true)
            val direction = if (state.direction == Direction.NO_TRADE.name) "NO TRADE" else state.direction
            val dirColor = when (state.direction) { Direction.CALL.name -> teal; Direction.PUT.name -> Color.rgb(255, 145, 129); else -> Color.rgb(244, 196, 92) }
            textCenter(canvas, direction, centerX, dp(120).toFloat(), 31f, dirColor, true)
            textCenter(canvas, "RAW LOCAL ESTIMATE • LIVE GATE REQUIRES CALIBRATION", centerX, dp(145).toFloat(), 10f, muted, false)
            val barLeft = dp(28).toFloat(); val barRight = w - dp(28).toFloat(); val barY = dp(173).toFloat()
            paint.color = Color.rgb(45, 57, 81); canvas.drawRoundRect(barLeft, barY, barRight, barY + dp(10), dp(5).toFloat(), dp(5).toFloat(), paint)
            paint.color = teal; canvas.drawRoundRect(barLeft, barY, barLeft + (barRight - barLeft) * state.call.toFloat(), barY + dp(10), dp(5).toFloat(), dp(5).toFloat(), paint)
            text(canvas, "CALL  ${(state.call * 100).roundToInt()}%", barLeft, dp(201).toFloat(), 12f, teal, true)
            textRight(canvas, "PUT  ${(state.put * 100).roundToInt()}%", barRight, dp(201).toFloat(), 12f, Color.rgb(255, 145, 129), true)
            val base = dp(244).toFloat(); val gap = dp(12).toFloat(); val cellW = (w - dp(44) - gap) / 2f
            metric(canvas, dp(22).toFloat(), base, cellW, "UNCERTAINTY", state.uncertainty)
            metric(canvas, dp(22).toFloat() + cellW + gap, base, cellW, "REGIME", state.regime.replace('_', ' '))
            metric(canvas, dp(22).toFloat(), base + dp(70), cellW, "CONFIRMED CANDLES", state.candles.toString())
            metric(canvas, dp(22).toFloat() + cellW + gap, base + dp(70), cellW, "ROI CONFIDENCE", "${(state.roi * 100).roundToInt()}%")
            val reasonY = h - dp(60).toFloat()
            text(canvas, "GATE", dp(22).toFloat(), reasonY, 10f, muted, true)
            text(canvas, state.error ?: state.gateReason, dp(22).toFloat(), reasonY + dp(22), 11f, if (state.error != null) Color.rgb(255, 130, 130) else ink, false)
            textRight(canvas, if (state.latency > 0) "${state.latency} ms" else "LOCAL", w - dp(22).toFloat(), reasonY, 10f, muted, false)
        }
        private fun metric(c: Canvas, x: Float, y: Float, width: Float, label: String, value: String) {
            paint.color = Color.rgb(21, 33, 55); c.drawRoundRect(x, y, x + width, y + dp(58), dp(10).toFloat(), dp(10).toFloat(), paint)
            text(c, label, x + dp(12), y + dp(20).toFloat(), 9f, muted, true); text(c, value, x + dp(12), y + dp(44).toFloat(), 13f, ink, true)
        }
        private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) { paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; paint.textSize = dp(size.toInt()).toFloat(); paint.color = color; c.drawText(value, x, y, paint) }
        private fun textCenter(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) { paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; paint.textSize = dp(size.toInt()).toFloat(); paint.color = color; c.drawText(value, x - paint.measureText(value) / 2, y, paint) }
        private fun textRight(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) { paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; paint.textSize = dp(size.toInt()).toFloat(); paint.color = color; c.drawText(value, x - paint.measureText(value), y, paint) }
        private fun accessibilitySummary() = "${if (state.direction == Direction.NO_TRADE.name) "No trade" else state.direction} for the next one minute candle. Call ${(state.call * 100).roundToInt()} percent, put ${(state.put * 100).roundToInt()} percent. Uncertainty ${state.uncertainty}. ${state.gateReason}"
    }
}
