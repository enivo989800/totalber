package com.build.lotteryai.core

import com.build.lotteryai.model.Candidate

/** Extracts explicitly formatted SSQ combinations from external model replies. */
object ExternalCandidateParser {
    data class ParsedCombination(
        val sourceLine: Int,
        val raw: String,
        val candidate: Candidate?,
        val error: String?
    )

    data class ParseResult(val found: List<ParsedCombination>) {
        val validCandidates: List<Candidate> get() = found.mapNotNull { it.candidate }
        val invalidCount: Int get() = found.count { it.candidate == null }
        val status: String get() = when {
            found.isEmpty() -> "未识别到明确的双色球组合"
            invalidCount > 0 -> "识别 ${found.size} 组，其中 ${validCandidates.size} 组合法、${invalidCount} 组不合法"
            else -> "识别到 ${validCandidates.size} 组合法组合"
        }
    }

    private val comboPattern = Regex(
        "(?<!\\d)((?:\\d{1,2}[\\s,，、]+){5}\\d{1,2})\\s*(?:\\+|加|蓝球\\s*[:：]?)\\s*(\\d{1,2})(?!\\d)"
    )

    fun parse(text: String): ParseResult {
        val results = ArrayList<ParsedCombination>()
        text.lineSequence().forEachIndexed { index, line ->
            comboPattern.findAll(line).forEach { match ->
                val rawReds = match.groupValues[1].split(Regex("[\\s,，、]+"))
                    .mapNotNull { it.toIntOrNull() }
                val blue = match.groupValues[2].toIntOrNull()
                val reds = rawReds.sorted()
                val invalidReason = when {
                    rawReds.size != 6 -> "红球数量不是 6 个"
                    rawReds.any { it !in 1..33 } -> "红球必须在 1 至 33 之间"
                    rawReds.distinct().size != 6 -> "红球存在重复号码"
                    blue == null || blue !in 1..16 -> "蓝球必须在 1 至 16 之间"
                    else -> null
                }
                val candidate = if (invalidReason == null && blue != null) {
                    Candidate(reds, blue, "外部 AI", 0.0, mapOf("来源" to "外部 AI 原始回复"))
                } else null
                results += ParsedCombination(
                    sourceLine = index + 1,
                    raw = match.value,
                    candidate = candidate,
                    error = invalidReason
                )
            }
        }
        return ParseResult(results.distinctBy { "${it.sourceLine}:${it.raw}" })
    }
}
