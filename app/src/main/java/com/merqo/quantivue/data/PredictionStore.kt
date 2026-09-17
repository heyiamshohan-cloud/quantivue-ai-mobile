package com.merqo.quantivue.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.merqo.quantivue.core.*

class PredictionStore(context: Context) : SQLiteOpenHelper(context, "quantivue.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE predictions (
                prediction_id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                target_index INTEGER NOT NULL,
                direction TEXT NOT NULL,
                bullish_probability REAL NOT NULL,
                bearish_probability REAL NOT NULL,
                uncertainty TEXT NOT NULL,
                confidence REAL NOT NULL,
                setup_quality TEXT NOT NULL,
                regime TEXT NOT NULL,
                lifecycle TEXT NOT NULL,
                gate_reason TEXT NOT NULL,
                model_version TEXT NOT NULL,
                calibration_version TEXT NOT NULL,
                roi_confidence REAL NOT NULL,
                confirmed_count INTEGER NOT NULL,
                input_fingerprint TEXT NOT NULL UNIQUE,
                actual_direction TEXT,
                actual_return REAL,
                outcome_timestamp INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_predictions_time ON predictions(timestamp)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }

    fun insertPrediction(result: PredictionResult, quality: ChartQuality, running: RunningCandle, confirmedCount: Int) {
        val values = ContentValues().apply {
            put("timestamp", result.timestampMillis); put("target_index", result.targetCandleIndex)
            put("direction", result.direction.name); put("bullish_probability", result.bullishProbability)
            put("bearish_probability", result.bearishProbability); put("uncertainty", result.uncertainty.name)
            put("confidence", result.confidence); put("setup_quality", result.setupQuality)
            put("regime", result.regime.name); put("lifecycle", result.lifecycle.name)
            put("gate_reason", result.gateReason); put("model_version", result.modelVersion)
            put("calibration_version", result.calibrationVersion); put("roi_confidence", quality.score)
            put("confirmed_count", confirmedCount); put("input_fingerprint", result.inputFingerprint)
        }
        writableDatabase.insertWithOnConflict("predictions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun recordOutcome(targetIndex: Long, actual: Direction, actualReturn: Double, timestamp: Long): Int {
        val v = ContentValues().apply { put("actual_direction", actual.name); put("actual_return", actualReturn); put("outcome_timestamp", timestamp) }
        return writableDatabase.update("predictions", v, "target_index = ? AND actual_direction IS NULL", arrayOf(targetIndex.toString()))
    }

    fun recent(limit: Int = 100): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        readableDatabase.query("predictions", arrayOf("timestamp", "target_index", "direction", "bullish_probability", "bearish_probability", "uncertainty", "regime", "actual_direction", "gate_reason"), null, null, null, null, "timestamp DESC", limit.toString()).use { c ->
            while (c.moveToNext()) out += mapOf(
                "timestamp" to c.getString(0), "target" to c.getString(1), "direction" to c.getString(2),
                "call" to c.getString(3), "put" to c.getString(4), "uncertainty" to c.getString(5),
                "regime" to c.getString(6), "actual" to (if (c.isNull(7)) "PENDING" else c.getString(7)), "reason" to c.getString(8))
        }
        return out
    }
}
