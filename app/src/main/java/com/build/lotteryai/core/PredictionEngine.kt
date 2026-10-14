package com.build.lotteryai.core

import com.build.lotteryai.model.Candidate
import com.build.lotteryai.model.DrawRecord
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/**
 * Local candidate generator. Scores are descriptive heuristic scores, never winning probabilities.
 */
class PredictionEngine {
    enum class Strategy(val label: String) {
        RANDOM("随机基线"),
        STATISTICAL("统计参考"),
        STRUCTURE("组合结构")
    }

    fun generate(
        history: List<DrawRecord>,
        count: Int,
        strategy: Strategy,
        seed: Long = System.nanoTime()
    ): List<Candidate> {
        require(count in 1..100) { "候选组数必须在 1 至 100 之间" }
        val usable = history.filter { it.isValid() }
        val random = Random(seed)
        val redWeights = makeWeights(usable.flatMap { it.reds }, 33)
        val blueWeights = makeWeights(usable.map { it.blue }, 16)
        val output = ArrayList<Candidate>(count)
        val seen = HashSet<String>()
        var attempts = 0
        val maxAttempts = count * 40

        while (output.size < count && attempts < maxAttempts) {
            attempts++
            val reds = when (strategy) {
                Strategy.RANDOM -> uniformSample(33, 6, random).sorted()
                Strategy.STATISTICAL -> weightedSampleWithoutReplacement(redWeights, 6, random).sorted()
                Strategy.STRUCTURE -> generateStructureCandidate(redWeights, random)
            }
            val blue = when (strategy) {
                Strategy.RANDOM -> random.nextInt(16) + 1
                Strategy.STATISTICAL -> weightedSampleWithoutReplacement(blueWeights, 1, random).single()
                Strategy.STRUCTURE -> chooseBlueByDistribution(blueWeights, random)
            }
            val key = reds.joinToString(",") + "+" + blue
            if (!seen.add(key)) continue

            val features = describe(reds, blue)
            output += Candidate(
                reds = reds,
                blue = blue,
                strategy = strategy.label,
                score = score(reds, blue, redWeights, blueWeights, strategy),
                features = features
            )
        }
        return output
    }

    fun frequency(numbers: List<Int>, poolSize: Int): Map<Int, Int> {
        require(poolSize > 0)
        return (1..poolSize).associateWith { number -> numbers.count { it == number } }
    }

    fun summary(history: List<DrawRecord>): Map<String, String> {
        val usable = history.filter { it.isValid() }
        if (usable.isEmpty()) return mapOf("期数" to "0", "红球和值均值" to "—", "红球奇偶比均值" to "—")
        val sumMean = usable.map { draw -> draw.reds.sum() }.average()
        val oddMean = usable.map { draw -> draw.reds.count { it % 2 != 0 } }.average()
        return linkedMapOf(
            "期数" to usable.size.toString(),
            "最新期号" to usable.first().issue,
            "红球和值均值" to String.format(java.util.Locale.ROOT, "%.2f", sumMean),
            "红球奇数个数均值" to String.format(java.util.Locale.ROOT, "%.2f / 6", oddMean),
            "红球理论单号期望" to String.format(java.util.Locale.ROOT, "%.2f", usable.size * 6.0 / 33.0),
            "蓝球理论单号期望" to String.format(java.util.Locale.ROOT, "%.2f", usable.size / 16.0)
        )
    }

    private fun makeWeights(observed: List<Int>, poolSize: Int): DoubleArray {
        val counts = IntArray(poolSize + 1)
        observed.forEach { if (it in 1..poolSize) counts[it]++ }
        val average = observed.size.toDouble() / poolSize
        return DoubleArray(poolSize + 1) { index ->
            if (index == 0) 0.0 else {
                // Smooth noisy short-window counts toward equal weights.
                val smoothed = (counts[index] + max(1.0, average)) / (average + max(1.0, average))
                smoothed.coerceIn(0.35, 2.5)
            }
        }
    }

    private fun uniformSample(poolSize: Int, count: Int, random: Random): List<Int> {
        val values = (1..poolSize).toMutableList()
        for (index in values.lastIndex downTo 1) {
            val swapIndex = random.nextInt(index + 1)
            val current = values[index]
            values[index] = values[swapIndex]
            values[swapIndex] = current
        }
        return values.take(count)
    }

    private fun weightedSampleWithoutReplacement(weights: DoubleArray, count: Int, random: Random): List<Int> {
        val available = (1 until weights.size).toMutableList()
        val selected = ArrayList<Int>(count)
        repeat(count) {
            val total = available.sumOf { weights[it] }
            var point = random.nextDouble() * total
            var selectedNumber = available.last()
            for (number in available) {
                point -= weights[number]
                if (point <= 0.0) {
                    selectedNumber = number
                    break
                }
            }
            selected += selectedNumber
            available.remove(selectedNumber)
        }
        return selected
    }

    private fun generateStructureCandidate(weights: DoubleArray, random: Random): List<Int> {
        // Broad structural preference used only to diversify candidates, not to infer future odds.
        repeat(60) {
            val reds = weightedSampleWithoutReplacement(weights, 6, random).sorted()
            val oddCount = reds.count { it % 2 != 0 }
            val lowCount = reds.count { it <= 16 }
            val sum = reds.sum()
            if (oddCount in 2..4 && lowCount in 2..4 && sum in 75..150) return reds
        }
        return weightedSampleWithoutReplacement(weights, 6, random).sorted()
    }

    private fun chooseBlueByDistribution(weights: DoubleArray, random: Random): Int {
        return weightedSampleWithoutReplacement(weights, 1, random).single()
    }

    private fun score(
        reds: List<Int>,
        blue: Int,
        redWeights: DoubleArray,
        blueWeights: DoubleArray,
        strategy: Strategy
    ): Double {
        val meanWeight = reds.map { redWeights[it] }.average() * 0.8 + blueWeights[blue] * 0.2
        val oddCount = reds.count { it % 2 != 0 }
        val lowCount = reds.count { it <= 16 }
        val sum = reds.sum()
        val structuralPenalty = abs(oddCount - 3) * 2.0 + abs(lowCount - 3) * 1.2 +
            (if (sum !in 75..150) 3.0 else 0.0)
        val strategyAdjustment = if (strategy == Strategy.RANDOM) 0.0 else -structuralPenalty * 0.03
        // This is a normalized UI score, not a probability or evidence of improved win rate.
        return (50.0 + (meanWeight - 1.0) * 20.0 + strategyAdjustment).coerceIn(0.0, 100.0)
    }

    private fun describe(reds: List<Int>, blue: Int): Map<String, String> {
        val odd = reds.count { it % 2 != 0 }
        val low = reds.count { it <= 16 }
        val consecutivePairs = reds.zipWithNext().count { (a, b) -> b - a == 1 }
        return linkedMapOf(
            "和值" to reds.sum().toString(),
            "奇偶" to "$odd:${6 - odd}",
            "低高区" to "$low:${6 - low}",
            "跨度" to (reds.last() - reds.first()).toString(),
            "连号对" to consecutivePairs.toString(),
            "蓝球" to blue.toString().padStart(2, '0')
        )
    }
}
