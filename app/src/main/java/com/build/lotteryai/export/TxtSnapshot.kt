package com.build.lotteryai.export

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TxtSnapshot {
    fun create(request: JSONObject): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(request.optLong("createdAt", System.currentTimeMillis())))
        val requestJson = request.optString("requestJson", "{}")
        val portablePrompt = request.optString("portablePrompt", "")
        return buildString {
            appendLine("福利彩票 AI 分析请求快照")
            appendLine("=".repeat(48))
            appendLine("请求编号：${request.optString("id")}")
            appendLine("创建时间：$time")
            appendLine("模型：${request.optString("model")}")
            appendLine("API 地址（已移除 URL 中可能存在的用户信息、查询参数与片段）：${sanitizeEndpoint(request.optString("endpoint"))}")
            appendLine("数据截止期号：${request.optString("latestIssue")}")
            appendLine("历史记录数：${request.optInt("historyCount")}")
            appendLine("请求状态：${request.optString("status")}")
            appendLine()
            appendLine("[一、实际发送给 API 的逻辑请求正文（JSON）]")
            appendLine("说明：该内容是 HTTP 请求正文快照；Authorization 等认证头刻意不导出。")
            appendLine(requestJson)
            appendLine()
            appendLine("[二、可复制到外部 AI 的通用分析文本]")
            appendLine("注意：这是为通用聊天界面准备的可移植文本，不保证与供应商 HTTP 请求逐字节相同。")
            appendLine(portablePrompt)
            appendLine()
            appendLine("[三、AI 返回内容（若已有）]")
            appendLine(request.optString("responseText", "尚无返回内容"))
            appendLine()
            appendLine("安全说明：本文件不包含 API Key、Authorization 请求头或其他认证凭据。")
        }
    }

    private fun sanitizeEndpoint(raw: String): String = runCatching {
        val uri = java.net.URI(raw)
        java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }.getOrDefault(raw.replace(Regex("(?i)(https?://)[^/@\\s]+@"), "\$1").substringBefore('?').substringBefore('#'))
}
