package com.build.lotteryai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.build.lotteryai.ai.ApiConfigStore
import com.build.lotteryai.ai.OpenAiCompatibleClient
import com.build.lotteryai.core.PredictionEngine
import com.build.lotteryai.core.BacktestEngine
import com.build.lotteryai.core.ExternalCandidateParser
import com.build.lotteryai.data.LocalLotteryDb
import com.build.lotteryai.data.OfficialLotteryRepository
import com.build.lotteryai.export.TxtSnapshot
import com.build.lotteryai.model.Candidate
import com.build.lotteryai.model.DrawRecord
import com.build.lotteryai.ui.FrequencyBarChartView
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Native Android first-stage implementation. No web view or cloud backend is used. */
class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var db: LocalLotteryDb
    private lateinit var config: ApiConfigStore
    private val predictionEngine = PredictionEngine()
    private val officialRepository = OfficialLotteryRepository()
    private val aiClient = OpenAiCompatibleClient()
    private val statePrefs by lazy { getSharedPreferences("local_app_state", MODE_PRIVATE) }

    private var screen = Screen.HOME
    private var historyWindow = 100
    private var candidateCount = 10
    private var backtestTrainingWindow = 30
    private var drawsVisibleCount = 50
    private var taskFilter = "全部"
    private var strategy = PredictionEngine.Strategy.STATISTICAL
    private var lastCandidates: List<Candidate> = emptyList()
    private var lastPredictionId: String = ""
    private var lastAiReply: String = ""
    private var lastAiStatus: String = "尚未请求"
    private var busy = false
    private var statusMessage = "数据保存在本机；首次使用请同步官方开奖数据。"
    private var endpointInput: EditText? = null
    private var modelInput: EditText? = null
    private var keyInput: EditText? = null
    private var selectedApiMode: String = ApiConfigStore.MODE_CHAT_COMPLETIONS
    private var apiModeToggleButton: Button? = null
    private var externalProviderInput: EditText? = null
    private var externalReplyInput: EditText? = null
    private var externalParsePreview: TextView? = null
    private var pendingExportText: String? = null
    private var pendingBackupText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = LocalLotteryDb(applicationContext)
        config = ApiConfigStore(applicationContext)
        selectedApiMode = config.apiMode()
        historyWindow = statePrefs.getInt("history_window", 100).let { if (it == -1 || it in 1..1000) it else 100 }
        candidateCount = statePrefs.getInt("candidate_count", 10).let { if (it in setOf(5, 10, 20)) it else 10 }
        backtestTrainingWindow = statePrefs.getInt("backtest_training_window", 30).let { if (it in setOf(30, 50, 100)) it else 30 }
        statusMessage = statePrefs.getString("last_status", statusMessage) ?: statusMessage
        window.statusBarColor = Color.rgb(18, 96, 220)
        window.navigationBarColor = Color.rgb(247, 250, 255)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        render()
    }

    override fun onDestroy() {
        worker.shutdown()
        db.close()
        super.onDestroy()
    }

    private enum class Screen(val title: String) {
        HOME("首页"), DRAWS("历史开奖"), ANALYSIS("数据分析"), PREDICT("内置预测"), BACKTEST("历史回测"), AI("AI 辅助分析"),
        REQUESTS("AI 请求记录"), EXTERNAL("外部 AI 结果"), TASKS("任务管理"), SETTINGS("设置")
    }

    private data class TaskEntry(
        val type: String,
        val id: String,
        val createdAt: Long,
        val detail: String,
        val status: String,
        val destination: Screen
    )

    private data class HomeShortcut(
        val title: String,
        val subtitle: String,
        val icon: String,
        val accent: Int,
        val background: Int,
        val action: () -> Unit
    )

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE_BG)
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams.MATCH_PARENT, dp(86))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
        }
        when (screen) {
            Screen.HOME -> buildHome(body)
            Screen.DRAWS -> buildDraws(body)
            Screen.ANALYSIS -> buildAnalysis(body)
            Screen.PREDICT -> buildPrediction(body)
            Screen.BACKTEST -> buildBacktest(body)
            Screen.AI -> buildAi(body)
            Screen.REQUESTS -> buildRequests(body)
            Screen.EXTERNAL -> buildExternal(body)
            Screen.SETTINGS -> buildSettings(body)
            Screen.TASKS -> buildTasks(body)
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomNavigation(), LinearLayout.LayoutParams.MATCH_PARENT, dp(62))
        setContentView(root)
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(14), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(17, 96, 227), Color.rgb(54, 151, 255)))
        }
        val brand = text("福", 19, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(235, 42, 55), 50)
            contentDescription = "福利彩票分析应用"
        }
        header.addView(brand, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })

        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(text(if (screen == Screen.HOME) "福利彩票 AI 号码分析预测" else screen.title, 18, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titleBox.addView(text(if (screen == Screen.HOME) "数据分析 · 内置预测 · AI 辅助" else "本地数据 · 可追溯分析", 12, Color.rgb(225, 239, 255), false).apply {
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("⚙", primary = false).apply {
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(38, 255, 255, 255), 14)
            contentDescription = "设置"
            setOnClickListener { go(Screen.SETTINGS) }
        }, LinearLayout.LayoutParams(dp(44), dp(42)))
        return header
    }

    private fun buildBottomNavigation(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(5))
            background = rounded(Color.WHITE, 0, strokeColor = BORDER)
        }
        val items = listOf(
            "⌂\n首页" to Screen.HOME,
            "◷\n开奖" to Screen.DRAWS,
            "▥\n分析" to Screen.ANALYSIS,
            "◎\n预测" to Screen.PREDICT,
            "☻\n我的" to Screen.TASKS
        )
        items.forEach { (label, destination) ->
            val active = screen == destination
            val item = text(label, 12, if (active) BLUE else MUTED, active).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(4))
                setOnClickListener { go(destination) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        return nav
    }

    private fun buildHome(body: LinearLayout) {
        val latest = db.getRecent(1).firstOrNull()
        val records = activeHistory()
        val latestPrediction = db.latestPredictionJson()
        val latestRequest = db.latestAiRequest()

        // Latest draw and synchronization state use only records actually stored locally.
        card(body) {
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(text("双色球", 22, NAVY, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            titleRow.addView(text(if (latest == null) "暂无数据" else "本机已保存", 11,
                if (latest == null) MUTED else Color.rgb(18, 150, 92), true).apply {
                background = rounded(if (latest == null) Color.rgb(240, 244, 250) else Color.rgb(227, 248, 238), 20)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            })
            addView(titleRow)
            addView(text(if (latest == null) "尚未同步有效开奖数据" else "第 ${latest.issue} 期 · ${latest.drawDate}", 14, MUTED, false).margin(top = 7))
            if (latest != null) {
                addView(buildNumberRow(latest.reds, latest.blue).margin(top = 3))
                val fetched = if (latest.fetchedAt > 0L) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(latest.fetchedAt))
                } else "时间未知"
                addView(text("最近记录采集时间：$fetched", 11, MUTED, false).margin(top = 6))
            } else {
                addView(text("首次联网后可同步官方开奖结果。当前没有预置或虚构的开奖号码。", 13, MUTED, false).margin(top = 8))
            }
            addView(text("本地已保存 ${db.drawCount()} 期 · 当前分析范围：${windowLabel()}（有效 ${records.size} 期）", 12, BLUE, true).margin(top = 8))
            addView(text(statusMessage, 12, MUTED, false).margin(top = 5))
            addView(button(if (busy) "正在同步…" else "同步最新开奖").apply {
                isEnabled = !busy
                setOnClickListener { syncOfficialData() }
            }.margin(top = 9))
            addView(button("分页补齐历史数据", primary = false).apply {
                isEnabled = !busy
                setOnClickListener { confirmBackfill() }
            }.margin(top = 6))
        }

        // Reference image calls for a six-tile shortcut grid. Two rows keep touch targets readable on phones.
        card(body) {
            addView(sectionTitle("快捷功能"))
            addView(homeShortcutRow(listOf(
                HomeShortcut("历史开奖", "记录与同步", "▤", BLUE, Color.rgb(231, 241, 255)) { go(Screen.DRAWS) },
                HomeShortcut("数据分析", "频率/遗漏/分布", "▥", Color.rgb(21, 157, 102), Color.rgb(229, 249, 239)) { go(Screen.ANALYSIS) },
                HomeShortcut("内置预测", "本地生成候选", "◎", Color.rgb(239, 139, 26), Color.rgb(255, 243, 222)) { go(Screen.PREDICT) }
            )).margin(top = 10))
            addView(homeShortcutRow(listOf(
                HomeShortcut("AI 辅助", "可选模型 API", "✳", Color.rgb(132, 74, 235), Color.rgb(242, 234, 255)) { go(Screen.AI) },
                HomeShortcut("TXT 导出", "导出请求快照", "▣", Color.rgb(0, 159, 159), Color.rgb(225, 249, 247)) { exportLatestRequest() },
                HomeShortcut("外部 AI", "导入模型回复", "⇩", Color.rgb(230, 65, 100), Color.rgb(255, 235, 241)) { go(Screen.EXTERNAL) }
            )).margin(top = 8))
        }

        card(body) {
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(sectionTitle("数据分析范围"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            titleRow.addView(text("${records.size} 期", 12, BLUE, true))
            addView(titleRow)
            addView(horizontalButtons(dataWindowOptions()) { index -> selectDataWindow(index) }.margin(top = 10))
            addView(text("分析范围仅控制本次输入；切换窗口不会删除本地保存的历史数据。", 11, MUTED, false).margin(top = 7))
        }

        card(body) {
            addView(sectionTitle("预测结果 · 项目内引擎"))
            addView(text("不需要 AI API。候选由本地算法生成，分析评分不是中奖概率。", 11, MUTED, false).margin(top = 4))
            val candidates = lastCandidates
            val savedCandidates = latestPrediction?.optJSONArray("candidates")
            if (candidates.isNotEmpty()) {
                addView(text("本次 ${candidates.size} 组 · ${strategy.label}", 12, MUTED, false).margin(top = 8))
                addView(buildNumberRow(candidates.first().reds, candidates.first().blue).margin(top = 2))
                addView(text("分析评分 ${String.format(Locale.ROOT, "%.1f", candidates.first().score)} / 100（非概率）", 13, BLUE, true).margin(top = 6))
                addView(text(candidates.first().features.entries.take(4).joinToString(" · ") { "${it.key} ${it.value}" }, 11, MUTED, false).margin(top = 4))
            } else if (savedCandidates != null && savedCandidates.length() > 0) {
                val first = savedCandidates.optJSONObject(0)
                if (first != null) {
                    addView(text("最近任务 ${latestPrediction?.optString("id")} · ${latestPrediction?.optString("strategy")}", 12, MUTED, false).margin(top = 8))
                    addView(buildNumberRow(jsonIntList(first.optJSONArray("reds")), first.optInt("blue")).margin(top = 2))
                    addView(text("已保存本机 · 历史输入 ${latestPrediction?.optInt("historyCount")} 期", 11, MUTED, false).margin(top = 5))
                }
            } else {
                addView(text("尚无候选号码。生成第一组候选后，结果会显示在这里。", 13, MUTED, false).margin(top = 8))
            }
            val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("生成候选").apply { setOnClickListener { go(Screen.PREDICT) } },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6); topMargin = dp(10) })
            actions.addView(button("历史回测", primary = false).apply { setOnClickListener { go(Screen.BACKTEST) } },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6); topMargin = dp(10) })
            addView(actions)
        }

        card(body) {
            addView(sectionTitle("历史数据概览"))
            addView(text("${windowLabel()} · 实际有效样本 ${records.size} 期", 11, MUTED, false).margin(top = 4))
            if (records.isEmpty()) {
                addView(text("同步有效开奖记录后，这里显示本地统计图。", 13, MUTED, false).margin(top = 10))
            } else {
                val redTop = predictionEngine.frequency(records.flatMap { it.reds }, 33).entries
                    .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(10)
                addView(FrequencyBarChartView(this@MainActivity, "红球历史频次 TOP 10", redTop.map { it.key.toString().padStart(2, '0') }, redTop.map { it.value }, RED),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)).apply { topMargin = dp(7) })
                val blueTop = predictionEngine.frequency(records.map { it.blue }, 16).entries
                    .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(8)
                addView(FrequencyBarChartView(this@MainActivity, "蓝球历史频次 TOP 8", blueTop.map { it.key.toString().padStart(2, '0') }, blueTop.map { it.value }, BLUE),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { topMargin = dp(6) })
                predictionEngine.summary(records).entries.take(3).forEach { (key, value) ->
                    addView(labelValue(key, value).margin(top = 5))
                }
                addView(text("历史频率仅描述已发生结果，不证明未来号码概率更高。", 11, MUTED, false).margin(top = 8))
            }
            addView(button("查看全部统计", primary = false).apply { setOnClickListener { go(Screen.ANALYSIS) } }.margin(top = 8))
        }

        card(body) {
            addView(sectionTitle("AI 分析助手"))
            addView(text("模型：${config.model().ifBlank { "尚未配置" }} · ${if (config.hasApiKey()) "API Key 已保存在本机" else "未设置 API Key"}", 12, MUTED, false).margin(top = 5))
            addView(text("本地预测独立运行。发起 AI 分析时，所选历史与提示内容会发送给你配置的模型服务商。", 11, MUTED, false).margin(top = 5))
            val aiButtons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            aiButtons.addView(button("开始 AI 分析").apply { setOnClickListener { go(Screen.AI) } },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5); topMargin = dp(8) })
            aiButtons.addView(button("导出最近 TXT", primary = false).apply { setOnClickListener { exportLatestRequest() } },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5); topMargin = dp(8) })
            addView(aiButtons)
        }

        val task = db.predictionTasks(1).optJSONObject(0)
        val backtest = db.recentBacktestRuns(1).optJSONObject(0)
        val request = latestRequest
        val recentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        recentRow.addView(homeSummaryPanel(
            "最近预测任务",
            task?.optString("id") ?: "暂无预测记录",
            task?.let { "${it.optString("strategy")} · ${it.optInt("historyCount")} 期" } ?: "使用本地引擎生成候选",
            Color.rgb(239, 246, 255)
        ) { go(Screen.TASKS) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        recentRow.addView(homeSummaryPanel(
            "AI 请求记录",
            request?.optString("id") ?: "暂无 AI 请求",
            request?.let { "${it.optString("model")} · ${it.optString("status")}" } ?: "未调用外部模型",
            Color.rgb(245, 239, 255)
        ) { go(Screen.REQUESTS) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        body.addView(recentRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        if (backtest != null) {
            card(body) {
                addView(sectionTitle("最近回测"))
                addView(text("${backtest.optString("firstTestIssue")} 至 ${backtest.optString("lastTestIssue")} · 训练窗口 ${backtest.optInt("trainingWindow")} 期", 12, MUTED, false).margin(top = 5))
                addView(button("查看回测记录", primary = false).apply { setOnClickListener { go(Screen.BACKTEST) } }.margin(top = 7))
            }
        }
        card(body, backgroundColor = Color.rgb(255, 250, 235)) {
            addView(text("理性参与提示", 14, Color.rgb(145, 95, 8), true))
            addView(text("历史统计、算法评分和 AI 分析不能保证中奖。请核对数据截止期号，理性参与。网络或 API 失败时，已保存的本地数据仍可查看。", 12, Color.rgb(125, 94, 39), false).margin(top = 6))
        }
    }

    private fun homeShortcutRow(items: List<HomeShortcut>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        items.forEachIndexed { index, item ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(9), dp(4), dp(8))
                background = rounded(item.background, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener { item.action() }
                val iconView = text(item.icon, 20, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(item.accent, 50)
                }
                addView(iconView, LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(text(item.title, 12, item.accent, true).apply { gravity = Gravity.CENTER; maxLines = 1 }.margin(top = 6))
                addView(text(item.subtitle, 9, MUTED, false).apply { gravity = Gravity.CENTER; maxLines = 2; textAlignment = View.TEXT_ALIGNMENT_CENTER }.margin(top = 2))
            }
            row.addView(tile, LinearLayout.LayoutParams(0, dp(105), 1f).apply {
                if (index > 0) marginStart = dp(4)
                if (index < items.lastIndex) marginEnd = dp(4)
            })
        }
        return row
    }

    private fun homeSummaryPanel(title: String, primary: String, detail: String, backgroundColor: Int, action: () -> Unit): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(11), dp(11), dp(11))
            minimumHeight = dp(100)
            background = rounded(backgroundColor, 13, BORDER)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        panel.addView(text(title, 13, NAVY, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        panel.addView(text(primary, 11, BLUE, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }.margin(top = 7))
        panel.addView(text(detail, 10, MUTED, false).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }.margin(top = 4))
        return panel
    }

    private fun buildDraws(body: LinearLayout) {
        val records = if (historyWindow == -1) db.getAll() else db.getRecent(historyWindow)
        card(body) {
            addView(sectionTitle("历史开奖"))
            addView(text("已保存在本机 ${db.drawCount()} 期；当前显示 ${records.size} 期。", 13, MUTED, false).margin(top = 5))
            addView(horizontalButtons(dataWindowOptions()) { index -> selectDataWindow(index) }.margin(top = 10))
            addView(button("同步最新开奖（${if (historyWindow == -1) 100 else historyWindow} 期）").apply { isEnabled = !busy; setOnClickListener { syncOfficialData() } }.margin(top = 8))
            addView(button("分页补齐较完整历史数据", primary = false).apply { isEnabled = !busy; setOnClickListener { confirmBackfill() } }.margin(top = 8))
        }
        if (records.isEmpty()) {
            card(body) { addView(text("本地暂无开奖数据。请联网后点击“同步官方源”。", 14, MUTED, false)) }
        } else {
            records.take(drawsVisibleCount).forEach { record ->
                card(body) {
                    addView(text("第 ${record.issue} 期", 15, NAVY, true))
                    addView(text(record.drawDate, 12, MUTED, false).margin(top = 3))
                    addView(buildNumberRow(record.reds, record.blue))
                }
            }
            if (records.size > drawsVisibleCount) {
                body.addView(button("加载更多（当前 ${drawsVisibleCount} / ${records.size} 条）", primary = false).apply {
                    setOnClickListener {
                        drawsVisibleCount = (drawsVisibleCount + 50).coerceAtMost(records.size)
                        render()
                    }
                }.margin(top = 2, bottom = 10))
            } else {
                body.addView(text("已显示当前范围全部 ${records.size} 条记录。", 11, MUTED, false).margin(8))
            }
        }
    }

    private fun buildAnalysis(body: LinearLayout) {
        val records = activeHistory()
        card(body) {
            addView(sectionTitle("数据分析"))
            addView(horizontalButtons(dataWindowOptions()) { index -> selectDataWindow(index) })
            if (records.isEmpty()) {
                addView(text("当前范围没有可分析的有效开奖记录。请先同步官方数据。", 13, MUTED, false).margin(top = 8))
            } else {
                predictionEngine.summary(records).forEach { (key, value) ->
                    addView(labelValue(key, value).margin(top = 7))
                }
                val redCounts = predictionEngine.frequency(records.flatMap { it.reds }, 33)
                val blueCounts = predictionEngine.frequency(records.map { it.blue }, 16)
                val redTop = redCounts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(12)
                addView(FrequencyBarChartView(this@MainActivity, "红球频率 TOP 12（${records.size} 期）", redTop.map { it.key.toString().padStart(2, '0') }, redTop.map { it.value }, RED),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(184)).apply { topMargin = dp(12) })
                val blueTop = blueCounts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(8)
                addView(FrequencyBarChartView(this@MainActivity, "蓝球频率 TOP 8（${records.size} 期）", blueTop.map { it.key.toString().padStart(2, '0') }, blueTop.map { it.value }, BLUE),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176)).apply { topMargin = dp(8) })
                addView(text("频率仅描述已发生的历史结果，不表示下一期号码的真实概率更高。", 12, MUTED, false).margin(top = 10))
            }
        }
    }

    private fun buildPrediction(body: LinearLayout) {
        val records = activeHistory()
        card(body) {
            addView(sectionTitle("项目内预测引擎"))
            addView(text("不需要 API Key。根据本机已有数据和所选策略生成合法候选组合。", 13, MUTED, false).margin(top = 5))
            addView(horizontalButtons(PredictionEngine.Strategy.values().map { it.label to (it == strategy) }) { index ->
                strategy = PredictionEngine.Strategy.values()[index]
                render()
            }.margin(top = 10))
            addView(text("分析数据：${if (historyWindow == -1) "本地全部 ${records.size} 期" else "${windowLabel()}（当前有效 ${records.size} 期）"}", 13, BLUE, true).margin(top = 10))
            addView(text("每次生成组数", 13, NAVY, true).margin(top = 12))
            addView(horizontalButtons(listOf("5 组" to (candidateCount == 5), "10 组" to (candidateCount == 10), "20 组" to (candidateCount == 20))) { index ->
                candidateCount = listOf(5, 10, 20)[index]
                statePrefs.edit().putInt("candidate_count", candidateCount).apply()
                render()
            }.margin(top = 6))
            addView(button(if (busy) "正在处理…" else "生成 $candidateCount 组候选号码").apply {
                isEnabled = !busy
                setOnClickListener { generateCandidates() }
            }.margin(top = 10))
        }
        if (lastCandidates.isNotEmpty()) {
            card(body) {
                addView(sectionTitle("本次候选组合"))
                addView(text("任务编号：$lastPredictionId", 11, MUTED, false).margin(top = 4))
                lastCandidates.forEachIndexed { index, candidate ->
                    addView(text("第 ${index + 1} 组 · ${candidate.strategy}", 13, NAVY, true).margin(top = 12))
                    addView(buildNumberRow(candidate.reds, candidate.blue))
                    addView(text("${candidate.features.entries.joinToString(" · ") { "${it.key} ${it.value}" }}", 11, MUTED, false).margin(top = 4))
                    addView(text("分析评分 ${String.format(Locale.getDefault(), "%.1f", candidate.score)} / 100（非中奖概率）", 12, BLUE, true).margin(top = 4))
                    addDivider(body = this)
                }
                addView(button("用这次预测数据进行 AI 辅助分析").apply { setOnClickListener { go(Screen.AI) } }.margin(top = 8))
            }
        }
    }

    private fun buildAi(body: LinearLayout) {
        val records = activeHistory()
        val saved = db.latestAiRequest()
        card(body) {
            addView(sectionTitle("AI 辅助分析"))
            addView(text("AI API Key、模型设置、历史数据和请求记录保存在本机；发送分析时，内容会直接发往你配置的模型服务商。", 13, MUTED, false).margin(top = 5))
            addView(labelValue("当前模型", config.model()).margin(top = 8))
            addView(labelValue("API 模式", if (config.apiMode() == ApiConfigStore.MODE_RESPONSES) "Responses API" else "Chat Completions 兼容").margin(top = 5))
            addView(labelValue("API Key", if (config.hasApiKey()) "已加密保存在本机" else "尚未设置").margin(top = 5))
            addView(button("配置 API 地址 / 模型 / Key").apply { setOnClickListener { go(Screen.SETTINGS) } }.margin(top = 10))
            addView(text("历史输入：${records.size} 期 · 截止期号：${records.firstOrNull()?.issue ?: "无数据"}", 12, MUTED, false).margin(top = 12))
            addView(button(if (busy) "AI 分析进行中…" else "生成并发送 AI 分析请求").apply {
                isEnabled = !busy
                setOnClickListener { requestAiAnalysis() }
            }.margin(top = 10))
        }
        card(body) {
            addView(sectionTitle("最近一次 AI 结果"))
            addView(text("状态：$lastAiStatus", 12, BLUE, true).margin(top = 6))
            val reply = if (lastAiReply.isNotBlank()) lastAiReply else saved?.optString("responseText", "").orEmpty()
            addView(text(reply.ifBlank { "还没有 AI 返回内容。失败的请求也会保留请求快照，以便 TXT 导出和排查。" }, 13, TEXT, false).margin(top = 8))
            addView(button("导出最近实际请求为 TXT").apply { setOnClickListener { exportLatestRequest() } }.margin(top = 10))
            addView(button("查看 AI 请求记录").apply { setOnClickListener { go(Screen.REQUESTS) } }.margin(top = 8))
        }
        card(body, backgroundColor = Color.rgb(255, 250, 235)) {
            addView(text("外发内容提示", 13, Color.rgb(145, 95, 8), true))
            addView(text("本机历史数据会随请求发送给指定服务商。请求快照不含 API Key 或 Authorization 头；不要把私密信息写入自定义提示词。", 12, Color.rgb(125, 94, 39), false).margin(top = 5))
        }
    }

    private fun buildRequests(body: LinearLayout) {
        val requests = db.aiRequests(100)
        card(body) {
            addView(sectionTitle("AI 请求记录（本机保存）"))
            addView(text("每条记录对应发送前保存的逻辑请求快照。选择单条记录即可查看或导出，不会因后续修改配置而改变。", 12, MUTED, false).margin(top = 5))
        }
        if (requests.length() == 0) {
            card(body) { addView(text("暂无请求记录。首次发起 AI 分析后，此处会保存请求内容及返回状态。", 13, MUTED, false)) }
            return
        }
        for (index in 0 until requests.length()) {
            val request = requests.optJSONObject(index) ?: continue
            card(body) {
                addView(text(request.optString("id"), 14, NAVY, true))
                addView(labelValue("创建时间", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(request.optLong("createdAt")))).margin(top = 5))
                addView(labelValue("模型", request.optString("model")).margin(top = 4))
                addView(labelValue("数据截止期号", request.optString("latestIssue")).margin(top = 4))
                addView(labelValue("历史记录数", request.optInt("historyCount").toString()).margin(top = 4))
                addView(labelValue("状态", request.optString("status")).margin(top = 4))
                addView(text("请求正文预览", 13, NAVY, true).margin(top = 10))
                addView(text(request.optString("requestJson").take(600) + if (request.optString("requestJson").length > 600) "\n……（完整内容请导出 TXT）" else "", 10, TEXT, false).margin(top = 4))
                addView(text("回复预览", 13, NAVY, true).margin(top = 10))
                val reply = request.optString("responseText").ifBlank { "尚无返回内容" }
                addView(text(reply.take(600) + if (reply.length > 600) "\n……（完整回复保存在本机）" else "", 12, TEXT, false).margin(top = 4))
                addView(button("导出此请求 TXT（完整内容）").apply { setOnClickListener { exportRequest(request) } }.margin(top = 8))
            }
        }
    }

    private fun buildTasks(body: LinearLayout) {
        val filters = listOf("全部", "预测", "回测", "AI", "外部 AI")
        card(body) {
            addView(sectionTitle("任务管理与历史记录"))
            addView(text("集中查看本机保存的预测、回测、AI 请求和外部 AI 导入任务。记录不会因切换页面而丢失。", 12, MUTED, false).margin(top = 5))
            addView(horizontalButtons(filters.map { it to (it == taskFilter) }) { index ->
                taskFilter = filters[index]
                render()
            }.margin(top = 10))
            addView(button("打开设置与本地备份", primary = false).apply { setOnClickListener { go(Screen.SETTINGS) } }.margin(top = 8))
        }

        val entries = mutableListOf<TaskEntry>()
        val predictions = db.predictionTasks(100)
        for (i in 0 until predictions.length()) {
            val item = predictions.optJSONObject(i) ?: continue
            entries += TaskEntry(
                type = "预测", id = item.optString("id"), createdAt = item.optLong("createdAt"),
                detail = "${item.optString("strategy")} · ${item.optInt("historyCount")} 期 · 截止 ${item.optString("latestIssue")}",
                status = "已保存", destination = Screen.PREDICT
            )
        }
        val backtests = db.recentBacktestRuns(50)
        for (i in 0 until backtests.length()) {
            val item = backtests.optJSONObject(i) ?: continue
            entries += TaskEntry(
                type = "回测", id = item.optString("id"), createdAt = item.optLong("createdAt"),
                detail = "训练 ${item.optInt("trainingWindow")} 期 · 测试 ${item.optString("firstTestIssue")}—${item.optString("lastTestIssue")}",
                status = "已保存", destination = Screen.BACKTEST
            )
        }
        val requests = db.aiRequests(100)
        for (i in 0 until requests.length()) {
            val item = requests.optJSONObject(i) ?: continue
            entries += TaskEntry(
                type = "AI", id = item.optString("id"), createdAt = item.optLong("createdAt"),
                detail = "${item.optString("model")} · ${item.optInt("historyCount")} 期 · ${item.optString("latestIssue")}",
                status = item.optString("status", "已记录"), destination = Screen.REQUESTS
            )
        }
        val external = db.externalAiResults(50)
        for (i in 0 until external.length()) {
            val item = external.optJSONObject(i) ?: continue
            entries += TaskEntry(
                type = "外部 AI", id = item.optString("id"), createdAt = item.optLong("createdAt"),
                detail = "${item.optString("providerName")} · ${item.optString("parseStatus")}",
                status = "已保存原文", destination = Screen.EXTERNAL
            )
        }
        val visible = entries.sortedByDescending { it.createdAt }.filter { taskFilter == "全部" || it.type == taskFilter }
        if (visible.isEmpty()) {
            card(body) {
                addView(text(if (taskFilter == "全部") "暂时没有任务记录。生成候选号码、执行回测或调用 AI 后，记录会在这里汇总。" else "当前类别暂无记录。", 13, MUTED, false))
            }
            return
        }
        visible.take(100).forEach { item ->
            card(body) {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(text(item.type, 12, BLUE, true).apply {
                    background = rounded(Color.rgb(230, 241, 255), 18)
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                })
                row.addView(text(item.status, 11, Color.rgb(22, 137, 91), true).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(row)
                addView(text(item.id, 13, NAVY, true).margin(top = 7))
                addView(text(if (item.createdAt > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt)) else "时间未知", 11, MUTED, false).margin(top = 3))
                addView(text(item.detail, 12, TEXT, false).margin(top = 5))
                addView(button("查看相关页面", primary = false).apply { setOnClickListener { go(item.destination) } }.margin(top = 7))
            }
        }
        if (visible.size > 100) body.addView(text("当前展示最近 100 条记录；各历史模块仍保存各自记录。", 11, MUTED, false).margin(6))
    }

    private fun buildExternal(body: LinearLayout) {
        card(body) {
            addView(sectionTitle("外部 AI 结果导入与比较"))
            addView(text("粘贴外部 AI 的完整回复。应用会保留原文，并尝试提取明确写出的双色球组合；不认识的格式不会被擅自改写。", 13, MUTED, false).margin(top = 6))
            externalProviderInput = editText("外部 AI 名称（例如 ChatGPT）", 1).apply { setText("外部 AI") }
            addView(externalProviderInput!!.margin(top = 10))
            externalReplyInput = editText("粘贴外部 AI 完整回复，例如：03 07 12 18 22 29 + 08", 8)
            addView(externalReplyInput!!.margin(top = 8))
            externalParsePreview = text("输入回复后，将预览可识别的合法组合。", 12, BLUE, true).margin(top = 8) as TextView
            addView(externalParsePreview!!)
            externalReplyInput!!.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    val parsedNow = ExternalCandidateParser.parse(s?.toString().orEmpty())
                    val preview = parsedNow.found.take(5).joinToString("\n") { item ->
                        item.candidate?.displayNumbers() ?: "第 ${item.sourceLine} 行无效：${item.error}"
                    }
                    externalParsePreview?.text = if (s.isNullOrBlank()) {
                        "输入回复后，将预览可识别的合法组合。"
                    } else parsedNow.status + if (preview.isBlank()) "" else "\n$preview"
                }
            })
            addView(button("解析并保存外部 AI 回复").apply {
                setOnClickListener {
                    val content = externalReplyInput?.text?.toString()?.trim().orEmpty()
                    if (content.isBlank()) {
                        toast("请先粘贴外部 AI 回复")
                    } else {
                        val requestId = db.latestAiRequest()?.optString("id", "") ?: ""
                        val provider = externalProviderInput?.text?.toString()?.trim().orEmpty().ifBlank { "外部 AI" }
                        val parsed = ExternalCandidateParser.parse(content)
                        val parsedJson = JSONArray().apply {
                            parsed.validCandidates.forEach { candidate ->
                                put(JSONObject().apply {
                                    put("reds", JSONArray(candidate.reds))
                                    put("blue", candidate.blue)
                                    put("display", candidate.displayNumbers())
                                })
                            }
                        }
                        db.saveExternalAiResult(requestId, provider, content, parsedJson.toString(), parsed.status)
                        externalReplyInput?.setText("")
                        toast("已保存原始回复；${parsed.status}")
                        render()
                    }
                }
            }.margin(top = 10))
        }
        val internal = db.latestPredictionJson()
        if (internal != null) {
            card(body) {
                addView(sectionTitle("项目内最近候选（比较基准）"))
                val candidates = internal.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) addView(text("暂无项目内候选。", 12, MUTED, false))
                else for (index in 0 until minOf(candidates.length(), 10)) {
                    val candidate = candidates.optJSONObject(index) ?: continue
                    addView(text("第 ${index + 1} 组：${jsonIntList(candidate.optJSONArray("reds")).joinToString(" ") { it.toString().padStart(2, '0') }} + ${candidate.optInt("blue").toString().padStart(2, '0')}", 12, TEXT, false).margin(top = 5))
                }
                addView(text("外部 AI 候选会单独保存，不会自动覆盖以上结果。", 11, MUTED, false).margin(top = 8))
            }
        }
        val savedResults = db.externalAiResults(30)
        card(body) {
            addView(sectionTitle("已保存的外部 AI 回复"))
            if (savedResults.length() == 0) {
                addView(text("暂无外部 AI 回复记录。", 12, MUTED, false).margin(top = 5))
            } else {
                for (index in 0 until savedResults.length()) {
                    val result = savedResults.optJSONObject(index) ?: continue
                    addView(text("${result.optString("providerName")} · ${result.optString("id")}", 13, NAVY, true).margin(top = 10))
                    addView(text("关联请求：${result.optString("requestId").ifBlank { "未关联" }}", 11, MUTED, false).margin(top = 3))
                    addView(text("解析状态：${result.optString("parseStatus")}", 12, BLUE, true).margin(top = 3))
                    val candidateArray = result.optJSONArray("parsedCandidates")
                    if (candidateArray != null && candidateArray.length() > 0) {
                        for (candidateIndex in 0 until minOf(candidateArray.length(), 10)) {
                            val candidate = candidateArray.optJSONObject(candidateIndex) ?: continue
                            addView(text("识别组合：${candidate.optString("display")}", 12, TEXT, false).margin(top = 3))
                        }
                        val latestInternal = db.latestPredictionJson()?.optJSONArray("candidates")
                        if (latestInternal != null) {
                            val internalKeys = (0 until latestInternal.length()).mapNotNull { latestInternal.optJSONObject(it) }
                                .map { jsonIntList(it.optJSONArray("reds")).joinToString(",") + "+" + it.optInt("blue") }.toSet()
                            val matched = (0 until candidateArray.length()).mapNotNull { candidateArray.optJSONObject(it) }
                                .count { obj -> (jsonIntList(obj.optJSONArray("reds")).joinToString(",") + "+" + obj.optInt("blue")) in internalKeys }
                            addView(text("与项目内最近候选完全相同：$matched 组", 11, MUTED, false).margin(top = 3))
                        }
                    }
                    addView(text(result.optString("content").take(900), 11, TEXT, false).margin(top = 5))
                    addDivider(this)
                }
            }
        }
    }

    private fun buildBacktest(body: LinearLayout) {
        val available = db.getAll()
        card(body) {
            addView(sectionTitle("历史回测 · Walk-forward"))
            addView(text("每个测试期只使用该期之前的历史记录生成候选，然后与真实开奖结果比较。随机基线与其他策略采用相同测试期和每期候选组数。结果仅描述历史回测，不保证未来表现。", 12, MUTED, false).margin(top = 6))
            addView(labelValue("当前有效历史记录", available.size.toString()).margin(top = 10))
            addView(text("每个预测点的训练窗口", 12, NAVY, true).margin(top = 10))
            addView(horizontalButtons(listOf("30 期" to (backtestTrainingWindow == 30), "50 期" to (backtestTrainingWindow == 50), "100 期" to (backtestTrainingWindow == 100))) { index ->
                backtestTrainingWindow = listOf(30, 50, 100)[index]
                statePrefs.edit().putInt("backtest_training_window", backtestTrainingWindow).apply()
                render()
            }.margin(top = 6))
            addView(text("每个测试期生成 5 组，比较随机基线、统计参考和组合结构策略。", 12, MUTED, false).margin(top = 8))
            addView(button(if (busy) "回测进行中…" else "开始回测").apply {
                isEnabled = !busy
                setOnClickListener { runBacktest() }
            }.margin(top = 10))
        }

        val runs = db.recentBacktestRuns(10)
        if (runs.length() == 0) {
            card(body) { addView(text("尚无回测记录。请先同步足够的历史开奖数据，再运行回测。", 13, MUTED, false)) }
            return
        }
        for (index in 0 until runs.length()) {
            val run = runs.optJSONObject(index) ?: continue
            card(body) {
                addView(sectionTitle("回测记录 ${run.optString("id")}"))
                addView(text("可用历史 ${run.optInt("recordsAvailable")} 期 · 训练窗口 ${run.optInt("trainingWindow")} 期 · 每期 ${run.optInt("requestedCandidates")} 组", 12, MUTED, false).margin(top = 5))
                addView(text("测试期范围：${run.optString("firstTestIssue")} 至 ${run.optString("lastTestIssue")}", 12, MUTED, false).margin(top = 4))
                val results = run.optJSONArray("results")
                if (results != null) {
                    for (resultIndex in 0 until results.length()) {
                        val result = results.optJSONObject(resultIndex) ?: continue
                        addView(text(result.optString("label"), 14, NAVY, true).margin(top = 10))
                        addView(text("测试期数 ${result.optInt("testPeriods")} · 候选组数 ${result.optInt("candidateGroups")}", 11, MUTED, false).margin(top = 3))
                        addView(text("平均红球命中 ${String.format(Locale.ROOT, "%.3f", result.optDouble("averageRedHits"))} 个/组", 12, TEXT, false).margin(top = 3))
                        addView(text("蓝球命中率 ${String.format(Locale.ROOT, "%.2f%%", result.optDouble("blueHitRate") * 100)} · 至少 3 红命中率 ${String.format(Locale.ROOT, "%.2f%%", result.optDouble("threeOrMoreRedHitRate") * 100)}", 11, TEXT, false).margin(top = 3))
                        addView(text("完整匹配次数 ${result.optInt("exactMatches")}", 11, MUTED, false).margin(top = 3))
                    }
                }
            }
        }
    }

    private fun runBacktest() {
        if (busy) return
        val history = db.getAll()
        val selectedTrainingWindow = backtestTrainingWindow
        if (history.size <= selectedTrainingWindow) {
            toast("当前有效记录 ${history.size} 期，不足以使用 ${selectedTrainingWindow} 期训练窗口并留出测试期。请同步更多历史数据或选择更小窗口。")
            return
        }
        busy = true
        statusMessage = "正在按时间顺序执行历史回测…"
        render()
        worker.execute {
            try {
                val result = BacktestEngine().run(
                    recordsNewestFirst = history,
                    trainingWindow = selectedTrainingWindow,
                    candidatesPerPeriod = 5,
                    seed = 20261014L
                )
                val resultsJson = JSONArray().apply {
                    result.strategyResults.forEach { metric ->
                        put(JSONObject().apply {
                            put("strategy", metric.strategy.name)
                            put("label", metric.strategy.label)
                            put("testPeriods", metric.testPeriods)
                            put("candidateGroups", metric.candidateGroups)
                            put("averageRedHits", metric.averageRedHits)
                            put("blueHitRate", metric.blueHitRate)
                            put("threeOrMoreRedHitRate", metric.threeOrMoreRedHitRate)
                            put("exactMatches", metric.exactMatches)
                        })
                    }
                }
                val runId = "BT-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}-${UUID.randomUUID().toString().take(6)}"
                db.saveBacktestRun(
                    id = runId,
                    createdAt = System.currentTimeMillis(),
                    recordsAvailable = result.recordsAvailable,
                    trainingWindow = result.trainingWindow,
                    requestedCandidates = result.requestedCandidatesPerPeriod,
                    firstTestIssue = result.firstTestIssue,
                    lastTestIssue = result.lastTestIssue,
                    resultsJson = resultsJson.toString()
                )
                mainHandler.post {
                    busy = false
                    statusMessage = "回测完成：${result.strategyResults.firstOrNull()?.testPeriods ?: 0} 个测试期，结果已保存在本机。"
                    statePrefs.edit().putString("last_status", statusMessage).apply()
                    toast(statusMessage)
                    render()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    busy = false
                    statusMessage = "回测失败：${error.message ?: error.javaClass.simpleName}"
                    toast(statusMessage)
                    render()
                }
            }
        }
    }

    private fun buildSettings(body: LinearLayout) {
        card(body) {
            addView(sectionTitle("本地 AI 配置"))
            addView(text("设置只保存在此设备。本应用没有自有后端；调用 AI 时由手机直接请求配置的 HTTPS API。", 13, MUTED, false).margin(top = 5))
            addView(text("API 地址（完整 HTTPS URL）", 12, NAVY, true).margin(top = 14))
            endpointInput = editText("https://api.openai.com/v1/chat/completions", 1).apply { setText(config.endpoint()) }
            addView(endpointInput!!.margin(top = 5))
            addView(text("模型 ID", 12, NAVY, true).margin(top = 12))
            modelInput = editText("例如 gpt-4.1-mini", 1).apply { setText(config.model()) }
            addView(modelInput!!.margin(top = 5))
            addView(text("API Key（留空表示保留已保存的 Key）", 12, NAVY, true).margin(top = 12))
            keyInput = editText("粘贴你自己的 API Key", 1).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            addView(keyInput!!.margin(top = 5))
            addView(text(if (config.hasApiKey()) "当前 Key 已使用 Android Keystore 加密后保存在本机。" else "尚无已保存 Key。", 12, MUTED, false).margin(top = 5))
            apiModeToggleButton = button(apiModeLabel(), primary = false).apply {
                setOnClickListener {
                    selectedApiMode = if (selectedApiMode == ApiConfigStore.MODE_CHAT_COMPLETIONS) {
                        ApiConfigStore.MODE_RESPONSES
                    } else ApiConfigStore.MODE_CHAT_COMPLETIONS
                    text = apiModeLabel()
                }
            }
            addView(apiModeToggleButton!!.margin(top = 10))
            addView(text("切换模式后，请点击“保存到本机”使配置生效。Responses API 模式需要将 API 地址填写为相应的 /v1/responses 端点。", 11, MUTED, false).margin(top = 5))
            addView(button("保存到本机").apply { setOnClickListener { saveAiConfiguration() } }.margin(top = 12))
            addView(button("删除本地 API Key").apply {
                setTextColor(RED)
                background = rounded(Color.rgb(255, 240, 242), 12)
                setOnClickListener {
                    config.clearApiKey()
                    keyInput?.setText("")
                    toast("已删除本地 API Key")
                    render()
                }
            }.margin(top = 8))
        }
        card(body) {
            addView(sectionTitle("数据与隐私"))
            addView(text("• 历史开奖记录、预测任务和 AI 请求快照使用应用私有 SQLite 数据库。\n• API Key 使用 Android Keystore AES/GCM 加密后保存在本机偏好设置中。\n• 只有同步开奖和调用 AI 时才需要网络。\n• API Key 只通过 HTTPS Authorization 头发往用户配置的服务端，不写入 TXT。\n• 卸载应用或清除应用数据会删除本机数据。", 13, TEXT, false).margin(top = 8))
            addView(button("导出本地数据备份 JSON", primary = false).apply { setOnClickListener { exportBackup() } }.margin(top = 12))
            addView(button("导入备份（合并，不覆盖）", primary = false).apply { setOnClickListener { importBackup() } }.margin(top = 8))
            addView(text("备份包含本地开奖、预测、AI 请求与回复、外部 AI 和回测记录；不包含 API Key。导入只添加本机不存在的记录，不覆盖现有数据。", 11, MUTED, false).margin(top = 6))
        }
    }

    private fun syncOfficialData() {
        if (busy) return
        busy = true
        val fetchCount = if (historyWindow == -1) 100 else historyWindow.coerceIn(1, 1000)
        statusMessage = "正在连接中国福彩网官方接口…"
        render()
        worker.execute {
            try {
                val result = officialRepository.fetchSsq(fetchCount)
                val saved = db.upsertDraws(result.records)
                mainHandler.post {
                    busy = false
                    statusMessage = "同步完成：接口返回 ${result.records.size} 期，已写入/更新 $saved 期；本地累计 ${db.drawCount()} 期。"
                    statePrefs.edit().putString("last_status", statusMessage).apply()
                    toast(statusMessage)
                    render()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    busy = false
                    statusMessage = "同步失败：${error.message ?: error.javaClass.simpleName}。本机已有数据已保留。"
                    statePrefs.edit().putString("last_status", statusMessage).apply()
                    toast(statusMessage)
                    render()
                }
            }
        }
    }

    private fun confirmBackfill() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle("分页补齐历史开奖")
            .setMessage("将通过中国福彩网分页获取较完整的双色球历史记录，最多请求约 100 页（目标上限 10000 期），每页成功后立即保存。官网可能限制分页或改变接口；过程中止时已保存的数据不会删除。是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始回补") { _, _ -> backfillOfficialHistory() }
            .show()
    }

    private fun backfillOfficialHistory() {
        if (busy) return
        busy = true
        statusMessage = "正在分页回补历史数据；每页成功后会立即写入本机…"
        render()
        worker.execute {
            var savedCount = 0
            var fetchedPages = 0
            try {
                val summary = officialRepository.fetchSsqHistory(
                    maxRecords = 10_000,
                    pageSize = 100,
                    maxPages = 100,
                    delayBetweenPagesMs = 350
                ) { page ->
                    savedCount += db.upsertDraws(page.records)
                    fetchedPages++
                }
                mainHandler.post {
                    busy = false
                    val limitNote = if (summary.limitReached) "已达到本次请求上限；不代表已覆盖全部历史。" else "接口返回已无更多记录或分页已结束。"
                    statusMessage = "历史回补结束：${summary.pagesFetched} 页，取得 ${summary.uniqueRecordsFetched} 条不同记录，本次写入/更新 $savedCount 条；本地累计 ${db.drawCount()} 期。$limitNote"
                    statePrefs.edit().putString("last_status", statusMessage).apply()
                    toast(statusMessage)
                    render()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    busy = false
                    statusMessage = "历史回补中断：已处理 $fetchedPages 页、写入/更新 $savedCount 条。本机已保存的页面数据会保留。原因：${error.message ?: error.javaClass.simpleName}"
                    statePrefs.edit().putString("last_status", statusMessage).apply()
                    toast(statusMessage)
                    render()
                }
            }
        }
    }

    private fun generateCandidates() {
        if (busy) return
        val selectedWindow = historyWindow
        val requestedCount = candidateCount
        val selectedStrategy = strategy
        busy = true
        render()
        worker.execute {
            val history = when (selectedWindow) {
                -1 -> db.getAll()
                else -> db.getRecent(selectedWindow)
            }
            val seed = System.currentTimeMillis()
            val generated = runCatching { predictionEngine.generate(history, requestedCount, selectedStrategy, seed) }
            mainHandler.post {
                busy = false
                generated.onSuccess { candidates ->
                    lastCandidates = candidates
                    strategy = selectedStrategy
                    lastPredictionId = "P-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}-${UUID.randomUUID().toString().take(6)}"
                    db.savePrediction(
                        id = lastPredictionId,
                        createdAt = System.currentTimeMillis(),
                        historyCount = history.size,
                        latestIssue = history.firstOrNull()?.issue ?: "无历史数据",
                        strategy = selectedStrategy.label,
                        candidates = candidates,
                        seed = seed
                    )
                    statusMessage = if (history.isEmpty()) "已生成候选组合；当前无有效开奖历史，未使用真实历史特征。" else "候选号码已生成并保存到本机。"
                    toast(statusMessage)
                    render()
                }.onFailure {
                    toast("生成失败：${it.message}")
                    render()
                }
            }
        }
    }

    private fun requestAiAnalysis() {
        if (busy) return
        val endpoint = config.endpoint()
        val model = config.model()
        val apiKey = runCatching { config.getApiKey() }.getOrElse {
            toast("无法读取本地 API Key：${it.message}")
            return
        }
        if (apiKey.isBlank()) {
            toast("请先进入设置填写自己的 API Key")
            go(Screen.SETTINGS)
            return
        }
        val history = activeHistory()
        if (history.isEmpty()) {
            toast("本机没有有效历史数据，请先联网同步官方开奖数据")
            go(Screen.DRAWS)
            return
        }
        val predictionJson = db.latestPredictionJson()
        val systemPrompt = "你是彩票历史数据分析助手。只能基于用户给出的数据描述统计特征和生成实验性候选建议。不得声称能确定预测随机开奖，也不得把历史频率、遗漏或评分表示为未来中奖概率。请标明不确定性、样本限制，并将候选组合描述为参考而非保证。"
        val userPrompt = buildAiPrompt(history, predictionJson)
        val requestBody = aiClient.buildRequest(model, systemPrompt, userPrompt, config.apiMode())
        val requestId = "AI-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}-${UUID.randomUUID().toString().take(6)}"
        val createdAt = System.currentTimeMillis()
        val latestIssue = history.firstOrNull()?.issue ?: ""
        val portablePrompt = "系统要求：\n$systemPrompt\n\n用户任务：\n$userPrompt\n\n请在回答中区分历史统计事实、候选组合和无法验证的推断。"
        // Persist the exact logical body before the request begins so failed requests can also be exported.
        db.saveAiRequest(
            id = requestId,
            createdAt = createdAt,
            model = model,
            endpoint = endpoint,
            requestJson = requestBody.toString(),
            portablePrompt = portablePrompt,
            latestIssue = latestIssue,
            historyCount = history.size,
            status = "SENDING"
        )
        busy = true
        lastAiStatus = "正在发送；请求快照已保存到本机"
        lastAiReply = ""
        render()
        worker.execute {
            try {
                val response = aiClient.send(endpoint, apiKey, requestBody)
                db.updateAiRequest(requestId, "SUCCEEDED", response)
                mainHandler.post {
                    busy = false
                    lastAiStatus = "请求成功"
                    lastAiReply = response
                    toast("AI 分析完成；可导出本次真实请求快照为 TXT")
                    render()
                }
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                db.updateAiRequest(requestId, "FAILED: $message", "")
                mainHandler.post {
                    busy = false
                    lastAiStatus = "请求失败：$message"
                    lastAiReply = "请求失败，但实际请求正文快照已保存在本机，可以导出 TXT 排查。\n\n$message"
                    toast("AI 请求失败；请求快照已保存")
                    render()
                }
            }
        }
    }

    private fun buildAiPrompt(history: List<DrawRecord>, prediction: JSONObject?): String {
        val builder = StringBuilder()
        builder.appendLine("任务：请基于下面的双色球历史开奖记录和项目内候选组合，提供多角度的历史数据解读，并给出 3 组实验性参考组合。")
        builder.appendLine("重要限制：双色球开奖具有随机性；不得声称候选号码更可能中奖或有确定预测能力。请不要编造缺失数据。")
        builder.appendLine("彩种规则：红球从 1 至 33 中选 6 个且不重复，蓝球从 1 至 16 中选 1 个。")
        builder.appendLine("分析窗口：${if (historyWindow == -1) "本地全部已保存数据" else "最近 $historyWindow 期"}；实际有效记录：${history.size}；数据截止期号：${history.firstOrNull()?.issue ?: "无"}。")
        builder.appendLine("记录字段顺序：期号 | 日期 | 红球六个数 | 蓝球。以下记录按新到旧排列：")
        history.forEach { builder.appendLine("${it.issue} | ${it.drawDate} | ${it.reds.joinToString(" ") { n -> n.toString().padStart(2, '0') }} | ${it.blue.toString().padStart(2, '0')}") }
        val candidatesArray = prediction?.optJSONArray("candidates")
        if (candidatesArray != null && candidatesArray.length() > 0) {
            builder.appendLine("项目内最近一次候选组合（仅供比较）：")
            for (i in 0 until candidatesArray.length()) {
                val candidate = candidatesArray.optJSONObject(i) ?: continue
                val reds = jsonIntList(candidate.optJSONArray("reds"))
                builder.appendLine("第 ${i + 1} 组：${reds.joinToString(" ") { it.toString().padStart(2, '0') }} + ${candidate.optInt("blue").toString().padStart(2, '0')}；内部分析分数 ${candidate.optDouble("score")}")
            }
        } else {
            builder.appendLine("项目内当前没有已保存的候选组合。请在解释历史统计后，另外生成 3 组符合号码规则的实验性组合。")
        }
        builder.appendLine("请输出：1) 数据范围和限制；2) 可验证的统计观察；3) 3 组候选及组合结构；4) 为什么这些特征不能证明中奖概率；5) 可供回测验证的假设。")
        return builder.toString()
    }

    private fun saveAiConfiguration() {
        val endpoint = endpointInput?.text?.toString().orEmpty()
        val model = modelInput?.text?.toString().orEmpty()
        val key = keyInput?.text?.toString().orEmpty()
        config.saveConfiguration(endpoint, model, key, selectedApiMode).onSuccess {
            keyInput?.setText("")
            selectedApiMode = config.apiMode()
            toast("配置已加密保存到本机")
            render()
        }.onFailure { toast("保存失败：${it.message}") }
    }

    private fun exportLatestRequest() {
        val request = db.latestAiRequest()
        if (request == null) {
            toast("当前没有 AI 请求快照可导出")
            return
        }
        exportRequest(request)
    }

    private fun exportRequest(request: JSONObject) {
        pendingExportText = TxtSnapshot.create(request)
        val requestId = request.optString("id", "unknown").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "lottery-ai-request-$requestId.txt")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CREATE_TXT)
    }

    private fun exportBackup() {
        if (busy) return
        busy = true
        statusMessage = "正在准备本地数据备份…"
        render()
        worker.execute {
            val content = runCatching {
                db.exportBackupJson().toString(2).also { require(it.length <= MAX_BACKUP_CHARS) { "备份内容超过 20 MB 安全上限，请先减少超长 AI 回复记录。" } }
            }
            mainHandler.post {
                busy = false
                content.onSuccess { json ->
                    pendingBackupText = json
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "lottery-ai-local-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.json")
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_CREATE_BACKUP)
                    render()
                }.onFailure { error ->
                    statusMessage = "备份准备失败：${error.message ?: error.javaClass.simpleName}"
                    toast(statusMessage)
                    render()
                }
            }
        }
    }

    private fun importBackup() {
        AlertDialog.Builder(this)
            .setTitle("导入本地数据备份")
            .setMessage("导入采用合并模式：仅添加本机不存在的记录，不覆盖现有开奖记录、预测任务或请求记录。API Key 不在备份内。请只选择本应用导出的 JSON 文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("选择文件") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_OPEN_BACKUP)
            }
            .show()
    }

    @Deprecated("Android document picker result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == REQUEST_CREATE_TXT) pendingExportText = null
            if (requestCode == REQUEST_CREATE_BACKUP) pendingBackupText = null
            return
        }
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQUEST_CREATE_TXT -> {
                val content = pendingExportText ?: return
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { it.write(content) }
                    } ?: error("无法打开目标文件")
                }.onSuccess { toast("TXT 已导出") }
                    .onFailure { toast("导出失败：${it.message}") }
                pendingExportText = null
            }
            REQUEST_CREATE_BACKUP -> {
                val content = pendingBackupText ?: return
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { it.write(content) }
                    } ?: error("无法打开备份文件")
                }.onSuccess { toast("本地数据备份已导出；不含 API Key") }
                    .onFailure { toast("备份导出失败：${it.message}") }
                pendingBackupText = null
            }
            REQUEST_OPEN_BACKUP -> restoreBackupFromUri(uri)
        }
    }

    private fun restoreBackupFromUri(uri: Uri) {
        if (busy) return
        busy = true
        statusMessage = "正在校验并合并本地备份…"
        render()
        worker.execute {
            val outcome = runCatching {
                val content = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val chars = CharArray(1024)
                    val builder = StringBuilder()
                    while (true) {
                        val n = reader.read(chars)
                        if (n < 0) break
                        builder.append(chars, 0, n)
                        require(builder.length <= MAX_BACKUP_CHARS) { "备份文件过大，超过应用安全读取上限。" }
                    }
                    builder.toString()
                } ?: error("无法读取备份文件")
                db.mergeBackupJson(content)
            }
            mainHandler.post {
                busy = false
                outcome.onSuccess { summary ->
                    statusMessage = "备份合并完成；原有记录未覆盖。"
                    toast("备份合并完成：开奖 ${summary.drawsAdded}、预测 ${summary.predictionsAdded}、AI 请求 ${summary.requestsAdded}、外部回复 ${summary.externalResultsAdded}、回测 ${summary.backtestRunsAdded} 条新增记录。")
                }.onFailure { error ->
                    statusMessage = "备份导入失败：${error.message ?: error.javaClass.simpleName}"
                    toast(statusMessage)
                }
                render()
            }
        }
    }

    private fun apiModeLabel(): String = if (selectedApiMode == ApiConfigStore.MODE_RESPONSES) {
        "API 模式：Responses API（点击切换）"
    } else "API 模式：Chat Completions 兼容（点击切换）"

    private fun activeHistory(): List<DrawRecord> = when (historyWindow) {
        -1 -> db.getAll()
        else -> db.getRecent(historyWindow.coerceIn(1, 1000))
    }

    private fun dataWindowOptions(): List<Pair<String, Boolean>> {
        val custom = historyWindow > 0 && historyWindow !in setOf(100, 1000)
        return listOf(
            "最近 100 期" to (historyWindow == 100),
            "最近 1000 期" to (historyWindow == 1000),
            "全部本地" to (historyWindow == -1),
            (if (custom) "自定义 $historyWindow" else "自定义") to custom
        )
    }

    private fun selectDataWindow(index: Int) {
        when (index) {
            0 -> applyDataWindow(100)
            1 -> applyDataWindow(1000)
            2 -> applyDataWindow(-1)
            else -> showCustomDataWindowDialog()
        }
    }

    private fun applyDataWindow(value: Int) {
        historyWindow = value
        drawsVisibleCount = 50
        statePrefs.edit().putInt("history_window", value).apply()
        render()
    }

    private fun showCustomDataWindowDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "输入 1 至 1000 期"
            setText(if (historyWindow > 0 && historyWindow !in setOf(100, 1000)) historyWindow.toString() else "200")
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
            addView(text("设置本次历史分析窗口（1–1000 期）。该设置只改变分析范围，不会删除本地历史数据。", 12, MUTED, false))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("自定义历史范围")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                val value = input.text.toString().trim().toIntOrNull()
                if (value == null || value !in 1..1000) {
                    toast("请输入 1 至 1000 之间的期数")
                } else {
                    applyDataWindow(value)
                }
            }
            .show()
    }

    private fun go(destination: Screen) {
        screen = destination
        render()
    }

    private fun windowLabel(): String = when (historyWindow) {
        -1 -> "本地全部"
        100 -> "最近 100 期"
        1000 -> "最近 1000 期"
        else -> "自定义最近 $historyWindow 期"
    }

    private fun buildNumberRow(reds: List<Int>, blue: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        reds.forEach { number ->
            val circle = text(number.toString().padStart(2, '0'), 13, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = rounded(RED, 50)
            }
            val params = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                marginEnd = dp(4)
            }
            row.addView(circle, params)
        }
        val blueView = text(blue.toString().padStart(2, '0'), 13, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(BLUE, 50)
        }
        row.addView(blueView, LinearLayout.LayoutParams(0, dp(34), 1f))
        return row
    }

    private fun card(
        parent: LinearLayout,
        backgroundColor: Int = Color.WHITE,
        content: LinearLayout.() -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(backgroundColor, 16, strokeColor = BORDER)
            content()
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
        parent.addView(box, params)
    }

    private fun sectionTitle(title: String): TextView = text(title, 17, NAVY, true)

    private fun labelValue(label: String, value: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 12, MUTED, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(value, 13, NAVY, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun horizontalButtons(items: List<Pair<String, Boolean>>, onSelected: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { index, (label, selected) ->
            val button = button(label, primary = selected).apply {
                textSize = 11f
                minHeight = dp(42)
                setPadding(dp(3), dp(5), dp(3), dp(5))
                setOnClickListener { onSelected(index) }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = if (index == items.lastIndex) 0 else dp(5) })
        }
        return row
    }

    private fun actionButton(title: String, subtitle: String, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(247, 250, 255), 10)
            setOnClickListener { action() }
        }
        box.addView(text(title, 14, BLUE, true))
        box.addView(text(subtitle, 11, MUTED, false).margin(top = 3))
        return box.margin(top = 7)
    }

    private fun editText(hintText: String, lines: Int): EditText = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(Color.rgb(249, 251, 255), 10, strokeColor = BORDER)
        minLines = lines
        maxLines = if (lines == 1) 1 else lines
        if (lines == 1) setSingleLine(true) else gravity = Gravity.TOP or Gravity.START
    }

    private fun button(label: String, primary: Boolean = true): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = dp(44)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        setTextColor(if (primary) Color.WHITE else BLUE)
        background = rounded(if (primary) BLUE else Color.rgb(239, 246, 255), 12, strokeColor = if (primary) BLUE else BORDER)
    }

    private fun text(label: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun addDivider(body: LinearLayout) {
        val divider = View(this).apply { setBackgroundColor(BORDER) }
        body.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(3)
        })
    }

    private fun View.margin(top: Int = 0, bottom: Int = 0): View {
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(top)
        params.bottomMargin = dp(bottom)
        layoutParams = params
        return this
    }

    private fun jsonIntList(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optInt(it).takeIf { n -> n > 0 } }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CREATE_TXT = 5107
        private const val REQUEST_CREATE_BACKUP = 5108
        private const val REQUEST_OPEN_BACKUP = 5109
        private const val MAX_BACKUP_CHARS = 20_000_000
        private val BLUE = Color.rgb(22, 107, 235)
        private val NAVY = Color.rgb(22, 52, 101)
        private val TEXT = Color.rgb(49, 69, 101)
        private val MUTED = Color.rgb(101, 126, 165)
        private val RED = Color.rgb(239, 55, 70)
        private val BORDER = Color.rgb(218, 232, 251)
        private val PAGE_BG = Color.rgb(245, 249, 255)
    }
}
