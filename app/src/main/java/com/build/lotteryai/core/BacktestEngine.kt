package com.build.lotteryai.core

import com.build.lotteryai.model.DrawRecord
import java.util.Locale

/**
 * Walk-forward evaluation for local SSQ candidate strategies.
 * Every target draw is evaluated only against records that precede it chronologically.
 * Metrics are descriptive backtest results, not evidence of future winning odds.
 */
class BacktestEngine(private val predictionEngine: PredictionEngine = PredictionEngine()) {
    data class StrategyResult(
        val strategy: PredictionEngine.Strategy,
        val testPeriods: Int,
        val candidateGroups: Int,
        val averageRedHits: Double,
        val blueHitRate: Double,
        val threeOrMoreRedHitRate: Double,
        val exactMatches: Int
    ) {
        fun displayLines(): List<String> = listOf(
            "策略：${strategy.label}",
            "测试期数：$testPeriods · 候选组数：$candidateGroups",
            "平均红球命中：${String.format(Locale.ROOT, "%.3f", averageRedHits)} 个/组",
            "蓝球命中率：${String.format(Locale.ROOT, "%.2f%%", blueHitRate * 100)}",
            "每组命中至少 3 个红球：${String.format(Locale.ROOT, "%.2f%%", threeOrMoreRedHitRate * 100)}",
            "完整匹配次数：$exactMatches"
        )
    }

    data class RunResult(
        val recordsAvailable: Int,
        val trainingWindow: Int,
        val requestedCandidatesPerPeriod: Int,
        val firstTestIssue: String,
        val lastTestIssue: String,
        val strategyResults: List<StrategyResult>
    ) {
    }

    fun run(
        recordsNewestFirst: List<DrawRecord>,
        trainingWindow: Int = 100,
        candidatesPerPeriod: Int = 5,
        seed: Long = 20261014L
    ): RunResult {
        require(trainingWindow >= 10) { "回测训练窗口至少需要 10 期" }
        require(candidatesPerPeriod in 1..20) { "每期候选组数必须在 1 至 20 之间" }

        val chronological = recordsNewestFirst.filter { it.isValid() }
            .distinctBy { it.issue }
            .sortedWith(compareBy<DrawRecord> { it.drawDate.take(10) }.thenBy { it.issue })
        require(chronological.size > trainingWindow) {
            "有效记录为 ${chronological.size} 期，训练窗口为 $trainingWindow 期；至少还需要 1 期作为回测目标。"
        }

        val strategyResults = PredictionEngine.Strategy.values().map { strategy ->
            var redHits = 0L
            var blueHits = 0L
            var threeOrMoreHits = 0L
            var exactMatches = 0
            var groupCount = 0
            val testCount = chronological.size - trainingWindow

            for (targetIndex in trainingWindow until chronological.size) {
                val training = chronological.subList(targetIndex - trainingWindow, targetIndex)
                val actual = chronological[targetIndex]
                val periodSeed = seed xor (targetIndex.toLong() * 1_000_003L) xor
                    (strategy.ordinal.toLong() * 97_409L)
                val candidates = predictionEngine.generate(training, candidatesPerPeriod, strategy, periodSeed)
                candidates.forEach { candidate ->
                    val hits = candidate.reds.intersect(actual.reds.toSet()).size
                    redHits += hits
                    if (candidate.blue == actual.blue) blueHits++
                    if (hits >= 3) threeOrMoreHits++
                    if (hits == 6 && candidate.blue == actual.blue) exactMatches++
                    groupCount++
                }
            }
            StrategyResult(
                strategy = strategy,
                testPeriods = testCount,
                candidateGroups = groupCount,
                averageRedHits = if (groupCount == 0) 0.0 else redHits.toDouble() / groupCount,
                blueHitRate = if (groupCount == 0) 0.0 else blueHits.toDouble() / groupCount,
                threeOrMoreRedHitRate = if (groupCount == 0) 0.0 else threeOrMoreHits.toDouble() / groupCount,
                exactMatches = exactMatches
            )
        }

        return RunResult(
            recordsAvailable = chronological.size,
            trainingWindow = trainingWindow,
            requestedCandidatesPerPeriod = candidatesPerPeriod,
            firstTestIssue = chronological[trainingWindow].issue,
            lastTestIssue = chronological.last().issue,
            strategyResults = strategyResults
        )
    }
}
