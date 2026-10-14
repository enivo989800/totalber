package com.build.lotteryai.data

import com.build.lotteryai.model.DrawRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** Fetches public results directly on the device. No project-owned proxy server is used. */
class OfficialLotteryRepository {
    fun fetchSsq(limit: Int): FetchResult {
        require(limit in 1..1000) { "单次最新开奖同步范围为 1 至 1000 期" }
        val cookies = acquireSessionCookies()
        val url = buildUrl(issueCount = limit, pageNo = 1, pageSize = limit)
        return fetchPage(url, cookies)
    }

    /**
     * Best-effort paginated backfill. Each successful page is yielded immediately so callers
     * can persist it before a later page fails. The official page/API may change or restrict
     * pagination; callers must report partial completion rather than claiming full history.
     */
    fun fetchSsqHistory(
        maxRecords: Int = 10_000,
        pageSize: Int = 100,
        maxPages: Int = 100,
        delayBetweenPagesMs: Long = 350,
        onPageFetched: (FetchResult) -> Unit
    ): HistoryFetchSummary {
        require(maxRecords in 1..100_000) { "历史回补上限不合法" }
        require(pageSize in 10..1000) { "每页期数必须在 10 至 1000 之间" }
        require(maxPages in 1..1000) { "最大页数不合法" }
        require(delayBetweenPagesMs in 0..10_000) { "分页间隔不合法" }

        val cookies = acquireSessionCookies()
        val allIssues = LinkedHashSet<String>()
        var totalSavedCandidateRecords = 0
        var pagesFetched = 0
        var lastMessage = ""
        var lastUrl = ""
        var endedOnEmptyOrRepeatedPage = false

        for (pageNo in 1..maxPages) {
            if (allIssues.size >= maxRecords) break
            if (pageNo > 1 && delayBetweenPagesMs > 0) Thread.sleep(delayBetweenPagesMs)
            val requestedSize = minOf(pageSize, maxRecords - allIssues.size)
            val url = buildUrl(issueCount = null, pageNo = pageNo, pageSize = requestedSize)
            val result = fetchPage(url, cookies)
            pagesFetched++
            lastMessage = result.responseMessage
            lastUrl = result.sourceUrl
            val newRecords = result.records.filter { allIssues.add(it.issue) }
            if (newRecords.isNotEmpty()) {
                totalSavedCandidateRecords += newRecords.size
                onPageFetched(result.copy(records = newRecords))
            }
            if (result.records.isEmpty() || newRecords.isEmpty()) {
                endedOnEmptyOrRepeatedPage = true
                break
            }
        }
        return HistoryFetchSummary(
            uniqueRecordsFetched = totalSavedCandidateRecords,
            pagesFetched = pagesFetched,
            sourceUrl = lastUrl,
            responseMessage = lastMessage,
            limitReached = totalSavedCandidateRecords >= maxRecords || (pagesFetched >= maxPages && !endedOnEmptyOrRepeatedPage)
        )
    }

    private fun buildUrl(issueCount: Int?, pageNo: Int, pageSize: Int): String {
        val count = issueCount?.toString().orEmpty()
        return "$OFFICIAL_ENDPOINT?name=ssq&issueCount=$count&issueStart=&issueEnd=&dayStart=&dayEnd=" +
            "&pageNo=$pageNo&pageSize=$pageSize&week=&systemType=PC"
    }

    private fun fetchPage(requestUrl: String, sessionCookies: String): FetchResult {
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
            connection.setRequestProperty("Referer", OFFICIAL_SSQ_PAGE)
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (sessionCookies.isNotBlank()) connection.setRequestProperty("Cookie", sessionCookies)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charset.forName("UTF-8"))).use { it.readText() }
            }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("官方数据接口返回 HTTP $code：${body.take(240)}")
            val root = JSONObject(body)
            // Do not treat a malformed/non-API JSON response as a successful official result.
            if (!root.has("state") || root.isNull("state")) {
                throw IllegalStateException("官方数据响应缺少 state 字段；可能返回了网页防护或接口格式已变化。")
            }
            val state = root.optInt("state", Int.MIN_VALUE)
            if (state != 0) throw IllegalStateException("官方数据接口返回状态 $state：${root.optString("message", "无说明")}")
            val resultArray = findResultArray(root)
                ?: throw IllegalStateException("官方响应中没有找到开奖列表；网站接口结构可能已变化。响应片段：${body.take(240)}")
            val records = ArrayList<DrawRecord>(resultArray.length())
            for (index in 0 until resultArray.length()) {
                val item = resultArray.optJSONObject(index) ?: continue
                parseRecord(item, requestUrl)?.let(records::add)
            }
            if (resultArray.length() > 0 && records.isEmpty()) {
                throw IllegalStateException("官方响应包含记录，但没有解析到有效双色球记录。可能是网站防护、接口字段变化或字段缺失；未写入虚构数据。")
            }
            return FetchResult(
                records = records.distinctBy { it.issue }
                    .sortedWith(compareByDescending<DrawRecord> { it.drawDate }.thenByDescending { it.issue }),
                sourceUrl = requestUrl,
                responseMessage = root.optString("message", "官方响应成功")
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Get a short-lived official site session first; cookies are memory-only and never exported. */
    private fun acquireSessionCookies(): String {
        val connection = URL(OFFICIAL_SSQ_PAGE).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Referer", OFFICIAL_HOME)
            val status = connection.responseCode
            if (status !in 200..399) {
                throw IllegalStateException("无法建立官方开奖页面会话（HTTP $status）；请稍后重试。")
            }
            return connection.headerFields.entries
                .filter { (key, _) -> key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value.orEmpty() }
                .mapNotNull { raw -> raw.substringBefore(';').trim().takeIf { it.contains('=') } }
                .distinct()
                .joinToString("; ")
        } finally {
            connection.disconnect()
        }
    }

    private fun findResultArray(root: JSONObject): JSONArray? {
        root.optJSONArray("result")?.let { return it }
        root.optJSONArray("data")?.let { return it }
        root.optJSONObject("data")?.optJSONArray("result")?.let { return it }
        root.optJSONObject("data")?.optJSONArray("list")?.let { return it }
        root.optJSONArray("list")?.let { return it }
        return null
    }

    private fun parseRecord(item: JSONObject, requestUrl: String): DrawRecord? {
        val issueKeys = listOf("issue", "issueNo", "issueNum", "expect", "period", "periodNum", "drawNo", "code")
        var issue = issueKeys.asSequence().map { item.optString(it, "").trim() }
            .firstOrNull { it.matches(Regex("\\d{5,10}")) }.orEmpty()
        val detailLink = item.optString("detailsLink", "")
        if (issue.isBlank()) {
            issue = Regex("(?:issue|period|draw)?/?(20\\d{5,6})").find(detailLink)?.groupValues?.getOrNull(1).orEmpty()
        }
        if (issue.isBlank()) return null

        val date = listOf("date", "drawDate", "openDate", "开奖日期")
            .asSequence().map { item.optString(it, "").trim() }
            .firstOrNull { it.matches(Regex("20\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}.*")) }
            ?.replace('/', '-').orEmpty()
        if (date.isBlank()) return null

        val redRaw = listOf("red", "reds", "redball", "redBalls", "red_num", "redNumber")
            .asSequence().map { item.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val blueRaw = listOf("blue", "blueball", "blueBall", "blue_num", "blueNumber")
            .asSequence().map { item.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val redValues = redRaw.split(Regex("[,，;\\s]+"))
            .mapNotNull { token -> token.trim().toIntOrNull() }
        val blue = blueRaw.toIntOrNull() ?: return null
        val record = DrawRecord(
            issue = issue,
            drawDate = date,
            reds = redValues.sorted(),
            blue = blue,
            sourceUrl = requestUrl
        )
        return record.takeIf { it.isValid() }
    }

    data class FetchResult(
        val records: List<DrawRecord>,
        val sourceUrl: String,
        val responseMessage: String
    )

    data class HistoryFetchSummary(
        val uniqueRecordsFetched: Int,
        val pagesFetched: Int,
        val sourceUrl: String,
        val responseMessage: String,
        val limitReached: Boolean
    )

    companion object {
        const val OFFICIAL_HOME = "https://www.cwl.gov.cn/"
        const val OFFICIAL_SSQ_PAGE = "https://www.cwl.gov.cn/ygkj/wqkjgg/ssq/"
        const val OFFICIAL_ENDPOINT = "https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    }
}
