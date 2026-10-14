package com.build.lotteryai.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.build.lotteryai.model.Candidate
import com.build.lotteryai.model.DrawRecord
import org.json.JSONArray
import org.json.JSONObject

class LocalLotteryDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE draws (
                issue TEXT PRIMARY KEY NOT NULL,
                draw_date TEXT NOT NULL,
                reds TEXT NOT NULL,
                blue INTEGER NOT NULL,
                source_url TEXT NOT NULL,
                fetched_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_draws_date ON draws(draw_date DESC, issue DESC)")
        db.execSQL(
            """CREATE TABLE prediction_tasks (
                id TEXT PRIMARY KEY NOT NULL,
                created_at INTEGER NOT NULL,
                history_count INTEGER NOT NULL,
                latest_issue TEXT NOT NULL,
                strategy TEXT NOT NULL,
                candidates_json TEXT NOT NULL,
                seed TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE ai_requests (
                id TEXT PRIMARY KEY NOT NULL,
                created_at INTEGER NOT NULL,
                model TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                request_json TEXT NOT NULL,
                portable_prompt TEXT NOT NULL,
                latest_issue TEXT NOT NULL,
                history_count INTEGER NOT NULL,
                status TEXT NOT NULL,
                response_text TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE external_ai_results (
                id TEXT PRIMARY KEY NOT NULL,
                request_id TEXT NOT NULL,
                provider_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                content TEXT NOT NULL,
                parsed_candidates_json TEXT NOT NULL DEFAULT '[]',
                parse_status TEXT NOT NULL DEFAULT 'UNPARSED'
            )""".trimIndent()
        )
        createBacktestRunsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve all existing records. Each migration is additive and non-destructive.
        if (oldVersion < 2) createBacktestRunsTable(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE external_ai_results ADD COLUMN parsed_candidates_json TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE external_ai_results ADD COLUMN parse_status TEXT NOT NULL DEFAULT 'UNPARSED'")
        }
    }

    private fun createBacktestRunsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS backtest_runs (
                id TEXT PRIMARY KEY NOT NULL,
                created_at INTEGER NOT NULL,
                records_available INTEGER NOT NULL,
                training_window INTEGER NOT NULL,
                requested_candidates INTEGER NOT NULL,
                first_test_issue TEXT NOT NULL,
                last_test_issue TEXT NOT NULL,
                results_json TEXT NOT NULL
            )""".trimIndent()
        )
    }

    @Synchronized
    fun upsertDraws(records: List<DrawRecord>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            records.forEach { record ->
                if (!record.isValid()) return@forEach
                val values = ContentValues().apply {
                    put("issue", record.issue)
                    put("draw_date", record.drawDate)
                    put("reds", record.reds.joinToString(","))
                    put("blue", record.blue)
                    put("source_url", record.sourceUrl)
                    put("fetched_at", record.fetchedAt)
                }
                if (db.insertWithOnConflict("draws", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun drawCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM draws", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun getRecent(limit: Int): List<DrawRecord> {
        require(limit > 0)
        val queryLimit = limit.coerceAtMost(100_000)
        return readableDatabase.rawQuery(
            "SELECT issue, draw_date, reds, blue, source_url, fetched_at FROM draws ORDER BY draw_date DESC, issue DESC LIMIT ?",
            arrayOf(queryLimit.toString())
        ).use(::readDraws)
    }

    fun getAll(): List<DrawRecord> = readableDatabase.rawQuery(
        "SELECT issue, draw_date, reds, blue, source_url, fetched_at FROM draws ORDER BY draw_date DESC, issue DESC",
        null
    ).use(::readDraws)

    private fun readDraws(cursor: android.database.Cursor): List<DrawRecord> {
        val result = ArrayList<DrawRecord>()
        while (cursor.moveToNext()) {
            val reds = cursor.getString(2).split(',').mapNotNull { it.toIntOrNull() }
            val record = DrawRecord(
                issue = cursor.getString(0),
                drawDate = cursor.getString(1),
                reds = reds,
                blue = cursor.getInt(3),
                sourceUrl = cursor.getString(4),
                fetchedAt = cursor.getLong(5)
            )
            if (record.isValid()) result += record
        }
        return result
    }

    fun savePrediction(
        id: String,
        createdAt: Long,
        historyCount: Int,
        latestIssue: String,
        strategy: String,
        candidates: List<Candidate>,
        seed: Long
    ) {
        val data = JSONArray()
        candidates.forEach { candidate ->
            data.put(JSONObject().apply {
                put("reds", JSONArray(candidate.reds))
                put("blue", candidate.blue)
                put("strategy", candidate.strategy)
                put("score", candidate.score)
                put("features", JSONObject(candidate.features))
            })
        }
        val values = ContentValues().apply {
            put("id", id)
            put("created_at", createdAt)
            put("history_count", historyCount)
            put("latest_issue", latestIssue)
            put("strategy", strategy)
            put("candidates_json", data.toString())
            put("seed", seed.toString())
        }
        writableDatabase.insertWithOnConflict("prediction_tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveAiRequest(
        id: String,
        createdAt: Long,
        model: String,
        endpoint: String,
        requestJson: String,
        portablePrompt: String,
        latestIssue: String,
        historyCount: Int,
        status: String,
        responseText: String = ""
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("created_at", createdAt)
            put("model", model)
            put("endpoint", endpoint)
            put("request_json", requestJson)
            put("portable_prompt", portablePrompt)
            put("latest_issue", latestIssue)
            put("history_count", historyCount)
            put("status", status)
            put("response_text", responseText)
        }
        writableDatabase.insertWithOnConflict("ai_requests", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateAiRequest(id: String, status: String, responseText: String) {
        val values = ContentValues().apply {
            put("status", status)
            put("response_text", responseText)
        }
        writableDatabase.update("ai_requests", values, "id=?", arrayOf(id))
    }

    fun latestAiRequest(): JSONObject? = aiRequests(1).let { rows ->
        if (rows.length() == 0) null else rows.optJSONObject(0)
    }

    fun aiRequest(id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT id, created_at, model, endpoint, request_json, portable_prompt, latest_issue, history_count, status, response_text FROM ai_requests WHERE id=? LIMIT 1",
        arrayOf(id)
    ).use { c -> if (!c.moveToFirst()) null else aiRequestFromCursor(c) }

    fun aiRequests(limit: Int = 100): JSONArray {
        val safeLimit = limit.coerceIn(1, 500)
        return JSONArray().also { rows ->
            readableDatabase.rawQuery(
                "SELECT id, created_at, model, endpoint, request_json, portable_prompt, latest_issue, history_count, status, response_text FROM ai_requests ORDER BY created_at DESC LIMIT ?",
                arrayOf(safeLimit.toString())
            ).use { c -> while (c.moveToNext()) rows.put(aiRequestFromCursor(c)) }
        }
    }

    private fun aiRequestFromCursor(c: android.database.Cursor): JSONObject = JSONObject().apply {
        put("id", c.getString(0))
        put("createdAt", c.getLong(1))
        put("model", c.getString(2))
        put("endpoint", c.getString(3))
        put("requestJson", c.getString(4))
        put("portablePrompt", c.getString(5))
        put("latestIssue", c.getString(6))
        put("historyCount", c.getInt(7))
        put("status", c.getString(8))
        put("responseText", c.getString(9))
    }

    fun saveExternalAiResult(
        requestId: String,
        providerName: String,
        content: String,
        parsedCandidatesJson: String = "[]",
        parseStatus: String = "UNPARSED"
    ) {
        val values = ContentValues().apply {
            put("id", "external-${java.util.UUID.randomUUID()}")
            put("request_id", requestId)
            put("provider_name", providerName)
            put("created_at", System.currentTimeMillis())
            put("content", content)
            put("parsed_candidates_json", parsedCandidatesJson)
            put("parse_status", parseStatus)
        }
        writableDatabase.insert("external_ai_results", null, values)
    }

    fun externalAiResults(limit: Int = 50): JSONArray {
        val safeLimit = limit.coerceIn(1, 200)
        return JSONArray().also { rows ->
            readableDatabase.rawQuery(
                "SELECT id, request_id, provider_name, created_at, content, parsed_candidates_json, parse_status FROM external_ai_results ORDER BY created_at DESC LIMIT ?",
                arrayOf(safeLimit.toString())
            ).use { c ->
                while (c.moveToNext()) rows.put(JSONObject().apply {
                    put("id", c.getString(0))
                    put("requestId", c.getString(1))
                    put("providerName", c.getString(2))
                    put("createdAt", c.getLong(3))
                    put("content", c.getString(4))
                    put("parsedCandidates", JSONArray(c.getString(5)))
                    put("parseStatus", c.getString(6))
                })
            }
        }
    }

    fun saveBacktestRun(
        id: String,
        createdAt: Long,
        recordsAvailable: Int,
        trainingWindow: Int,
        requestedCandidates: Int,
        firstTestIssue: String,
        lastTestIssue: String,
        resultsJson: String
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("created_at", createdAt)
            put("records_available", recordsAvailable)
            put("training_window", trainingWindow)
            put("requested_candidates", requestedCandidates)
            put("first_test_issue", firstTestIssue)
            put("last_test_issue", lastTestIssue)
            put("results_json", resultsJson)
        }
        writableDatabase.insertWithOnConflict("backtest_runs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recentBacktestRuns(limit: Int = 10): JSONArray {
        val safeLimit = limit.coerceIn(1, 50)
        return JSONArray().also { rows ->
            readableDatabase.rawQuery(
                "SELECT id, created_at, records_available, training_window, requested_candidates, first_test_issue, last_test_issue, results_json FROM backtest_runs ORDER BY created_at DESC LIMIT ?",
                arrayOf(safeLimit.toString())
            ).use { c ->
                while (c.moveToNext()) rows.put(JSONObject().apply {
                    put("id", c.getString(0))
                    put("createdAt", c.getLong(1))
                    put("recordsAvailable", c.getInt(2))
                    put("trainingWindow", c.getInt(3))
                    put("requestedCandidates", c.getInt(4))
                    put("firstTestIssue", c.getString(5))
                    put("lastTestIssue", c.getString(6))
                    put("results", JSONArray(c.getString(7)))
                })
            }
        }
    }


    /** Export user-owned local records. API credentials are stored elsewhere and are never included. */
    fun exportBackupJson(): JSONObject {
        val root = JSONObject().apply {
            put("format", "lottery-ai-local-backup")
            put("formatVersion", 1)
            put("exportedAt", System.currentTimeMillis())
        }
        root.put("draws", queryRows("SELECT issue, draw_date, reds, blue, source_url, fetched_at FROM draws ORDER BY draw_date DESC, issue DESC") { c ->
            JSONObject().apply {
                put("issue", c.getString(0)); put("drawDate", c.getString(1)); put("reds", c.getString(2))
                put("blue", c.getInt(3)); put("sourceUrl", c.getString(4)); put("fetchedAt", c.getLong(5))
            }
        })
        root.put("predictionTasks", queryRows("SELECT id, created_at, history_count, latest_issue, strategy, candidates_json, seed FROM prediction_tasks ORDER BY created_at DESC") { c ->
            JSONObject().apply {
                put("id", c.getString(0)); put("createdAt", c.getLong(1)); put("historyCount", c.getInt(2))
                put("latestIssue", c.getString(3)); put("strategy", c.getString(4)); put("candidatesJson", c.getString(5)); put("seed", c.getString(6))
            }
        })
        root.put("aiRequests", queryRows("SELECT id, created_at, model, endpoint, request_json, portable_prompt, latest_issue, history_count, status, response_text FROM ai_requests ORDER BY created_at DESC") { c ->
            JSONObject().apply {
                put("id", c.getString(0)); put("createdAt", c.getLong(1)); put("model", c.getString(2))
                put("endpoint", safeEndpointForExport(c.getString(3))); put("requestJson", c.getString(4))
                put("portablePrompt", c.getString(5)); put("latestIssue", c.getString(6)); put("historyCount", c.getInt(7))
                put("status", c.getString(8)); put("responseText", c.getString(9))
            }
        })
        root.put("externalAiResults", queryRows("SELECT id, request_id, provider_name, created_at, content, parsed_candidates_json, parse_status FROM external_ai_results ORDER BY created_at DESC") { c ->
            JSONObject().apply {
                put("id", c.getString(0)); put("requestId", c.getString(1)); put("providerName", c.getString(2))
                put("createdAt", c.getLong(3)); put("content", c.getString(4)); put("parsedCandidatesJson", c.getString(5)); put("parseStatus", c.getString(6))
            }
        })
        root.put("backtestRuns", queryRows("SELECT id, created_at, records_available, training_window, requested_candidates, first_test_issue, last_test_issue, results_json FROM backtest_runs ORDER BY created_at DESC") { c ->
            JSONObject().apply {
                put("id", c.getString(0)); put("createdAt", c.getLong(1)); put("recordsAvailable", c.getInt(2))
                put("trainingWindow", c.getInt(3)); put("requestedCandidates", c.getInt(4)); put("firstTestIssue", c.getString(5))
                put("lastTestIssue", c.getString(6)); put("resultsJson", c.getString(7))
            }
        })
        return root
    }

    private fun queryRows(sql: String, row: (android.database.Cursor) -> JSONObject): JSONArray = JSONArray().also { array ->
        readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) array.put(row(c)) }
    }

    data class RestoreSummary(
        val drawsAdded: Int,
        val predictionsAdded: Int,
        val requestsAdded: Int,
        val externalResultsAdded: Int,
        val backtestRunsAdded: Int
    )

    /** Merge a versioned local backup without replacing records already present on this device. */
    fun mergeBackupJson(json: String): RestoreSummary {
        val root = JSONObject(json)
        require(root.optString("format") == "lottery-ai-local-backup") { "文件不是本应用的数据备份" }
        require(root.optInt("formatVersion") == 1) { "不支持此备份版本" }
        val db = writableDatabase
        var drawsAdded = 0
        var predictionsAdded = 0
        var requestsAdded = 0
        var externalResultsAdded = 0
        var backtestRunsAdded = 0
        db.beginTransaction()
        try {
            val draws = root.optJSONArray("draws") ?: JSONArray()
            for (i in 0 until draws.length()) {
                val item = draws.optJSONObject(i) ?: continue
                val reds = item.optString("reds", "").split(',').mapNotNull { it.toIntOrNull() }
                val record = DrawRecord(
                    issue = item.optString("issue"), drawDate = item.optString("drawDate"), reds = reds,
                    blue = item.optInt("blue"), sourceUrl = item.optString("sourceUrl"), fetchedAt = item.optLong("fetchedAt", 0L)
                )
                if (!record.isValid()) continue
                val values = ContentValues().apply {
                    put("issue", record.issue); put("draw_date", record.drawDate); put("reds", record.reds.joinToString(","))
                    put("blue", record.blue); put("source_url", record.sourceUrl); put("fetched_at", record.fetchedAt)
                }
                if (db.insertWithOnConflict("draws", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) drawsAdded++
            }
            val predictions = root.optJSONArray("predictionTasks") ?: JSONArray()
            for (i in 0 until predictions.length()) {
                val item = predictions.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val candidates = item.optString("candidatesJson", "[]")
                if (id.isBlank() || !runCatching { JSONArray(candidates) }.isSuccess) continue
                val values = ContentValues().apply {
                    put("id", id); put("created_at", item.optLong("createdAt")); put("history_count", item.optInt("historyCount"))
                    put("latest_issue", item.optString("latestIssue")); put("strategy", item.optString("strategy"))
                    put("candidates_json", candidates); put("seed", item.optString("seed"))
                }
                if (db.insertWithOnConflict("prediction_tasks", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) predictionsAdded++
            }
            val requests = root.optJSONArray("aiRequests") ?: JSONArray()
            for (i in 0 until requests.length()) {
                val item = requests.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val requestJson = item.optString("requestJson", "{}")
                if (id.isBlank() || !runCatching { JSONObject(requestJson) }.isSuccess) continue
                val values = ContentValues().apply {
                    put("id", id); put("created_at", item.optLong("createdAt")); put("model", item.optString("model"))
                    put("endpoint", safeEndpointForExport(item.optString("endpoint"))); put("request_json", requestJson)
                    put("portable_prompt", item.optString("portablePrompt")); put("latest_issue", item.optString("latestIssue"))
                    put("history_count", item.optInt("historyCount")); put("status", item.optString("status"))
                    put("response_text", item.optString("responseText"))
                }
                if (db.insertWithOnConflict("ai_requests", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) requestsAdded++
            }
            val external = root.optJSONArray("externalAiResults") ?: JSONArray()
            for (i in 0 until external.length()) {
                val item = external.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val parsedJson = item.optString("parsedCandidatesJson", "[]")
                if (id.isBlank() || !runCatching { JSONArray(parsedJson) }.isSuccess) continue
                val values = ContentValues().apply {
                    put("id", id); put("request_id", item.optString("requestId")); put("provider_name", item.optString("providerName"))
                    put("created_at", item.optLong("createdAt")); put("content", item.optString("content"))
                    put("parsed_candidates_json", parsedJson); put("parse_status", item.optString("parseStatus", "UNPARSED"))
                }
                if (db.insertWithOnConflict("external_ai_results", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) externalResultsAdded++
            }
            val backtests = root.optJSONArray("backtestRuns") ?: JSONArray()
            for (i in 0 until backtests.length()) {
                val item = backtests.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val results = item.optString("resultsJson", "[]")
                if (id.isBlank() || !runCatching { JSONArray(results) }.isSuccess) continue
                val values = ContentValues().apply {
                    put("id", id); put("created_at", item.optLong("createdAt")); put("records_available", item.optInt("recordsAvailable"))
                    put("training_window", item.optInt("trainingWindow")); put("requested_candidates", item.optInt("requestedCandidates"))
                    put("first_test_issue", item.optString("firstTestIssue")); put("last_test_issue", item.optString("lastTestIssue")); put("results_json", results)
                }
                if (db.insertWithOnConflict("backtest_runs", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) backtestRunsAdded++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return RestoreSummary(drawsAdded, predictionsAdded, requestsAdded, externalResultsAdded, backtestRunsAdded)
    }

    private fun safeEndpointForExport(raw: String): String = runCatching {
        val uri = java.net.URI(raw)
        java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }.getOrDefault(raw.replace(Regex("(?i)(https?://)[^/@\\s]+@"), "\$1").substringBefore('?').substringBefore('#'))

    fun latestPredictionJson(): JSONObject? = readableDatabase.rawQuery(
        "SELECT id, created_at, history_count, latest_issue, strategy, candidates_json, seed FROM prediction_tasks ORDER BY created_at DESC LIMIT 1",
        null
    ).use { c ->
        if (!c.moveToFirst()) null else JSONObject().apply {
            put("id", c.getString(0))
            put("createdAt", c.getLong(1))
            put("historyCount", c.getInt(2))
            put("latestIssue", c.getString(3))
            put("strategy", c.getString(4))
            put("candidates", JSONArray(c.getString(5)))
            put("seed", c.getString(6))
        }
    }

    fun predictionTasks(limit: Int = 50): JSONArray {
        val safeLimit = limit.coerceIn(1, 200)
        return JSONArray().also { rows ->
            readableDatabase.rawQuery(
                "SELECT id, created_at, history_count, latest_issue, strategy, candidates_json, seed FROM prediction_tasks ORDER BY created_at DESC LIMIT ?",
                arrayOf(safeLimit.toString())
            ).use { c ->
                while (c.moveToNext()) rows.put(JSONObject().apply {
                    put("id", c.getString(0))
                    put("createdAt", c.getLong(1))
                    put("historyCount", c.getInt(2))
                    put("latestIssue", c.getString(3))
                    put("strategy", c.getString(4))
                    put("candidates", JSONArray(c.getString(5)))
                    put("seed", c.getString(6))
                })
            }
        }
    }

    companion object {
        private const val DB_NAME = "lottery_ai_local.db"
        private const val DB_VERSION = 3
    }
}
