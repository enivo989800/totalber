package com.build.lotteryai.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class OpenAiCompatibleClient {
    fun buildRequest(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        apiMode: String = ApiConfigStore.MODE_CHAT_COMPLETIONS
    ): JSONObject {
        require(model.isNotBlank()) { "模型 ID 不能为空" }
        return when (apiMode) {
            ApiConfigStore.MODE_CHAT_COMPLETIONS -> JSONObject().apply {
                put("model", model)
                put("temperature", 0.4)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                })
            }
            ApiConfigStore.MODE_RESPONSES -> JSONObject().apply {
                put("model", model)
                put("instructions", systemPrompt)
                put("input", userPrompt)
            }
            else -> throw IllegalArgumentException("不支持的 API 请求模式")
        }
    }

    fun send(endpoint: String, apiKey: String, requestBody: JSONObject): String {
        require(endpoint.startsWith("https://", ignoreCase = true)) { "API 地址必须使用 HTTPS" }
        require(apiKey.isNotBlank()) { "请先在设置中填写自己的 API Key" }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = responseStream?.use { input ->
                BufferedReader(InputStreamReader(input, Charset.forName("UTF-8"))).use { it.readText() }
            }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("AI API 返回 HTTP $status：${response.take(1200)}")
            }
            val json = JSONObject(response)
            val content = extractText(json).trim()
            if (content.isBlank()) throw IllegalStateException("AI 响应中没有识别到可显示的文本内容；请检查 API 模式或服务商响应格式。")
            return content
        } finally {
            connection.disconnect()
        }
    }

    private fun extractText(json: JSONObject): String {
        json.optString("output_text", "").takeIf { it.isNotBlank() }?.let { return it }
        val choices = json.optJSONArray("choices")
        val firstChoice = choices?.optJSONObject(0)
        val message = firstChoice?.optJSONObject("message")
        if (message != null) {
            val content = message.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        val partText = part.optString("text", "")
                        if (partText.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(partText)
                        }
                    }
                }
                else -> ""
            }
            if (text.isNotBlank()) return text
        }
        firstChoice?.optString("text", "")?.takeIf { it.isNotBlank() }?.let { return it }
        val output = json.optJSONArray("output")
        if (output != null) {
            val chunks = ArrayList<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    part.optString("text", "").takeIf { it.isNotBlank() }?.let(chunks::add)
                }
            }
            if (chunks.isNotEmpty()) return chunks.joinToString("\n")
        }
        json.optString("content", "").takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }
}
