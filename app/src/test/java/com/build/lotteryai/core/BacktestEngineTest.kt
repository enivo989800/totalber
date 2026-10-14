package com.build.lotteryai.core

import com.build.lotteryai.model.DrawRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestEngineTest {
    @Test fun walkForwardNeverUsesFutureRecordsAndIsRepeatable() {
        val records = syntheticRecords(60).sortedByDescending { it.drawDate }
        val engine = BacktestEngine()
        val first = engine.run(records, trainingWindow = 30, candidatesPerPeriod = 2, seed = 7L)
        val second = engine.run(records, trainingWindow = 30, candidatesPerPeriod = 2, seed = 7L)

        assertEquals(30, first.strategyResults.first().testPeriods)
        assertEquals(3, first.strategyResults.size)
        assertEquals(first.strategyResults, second.strategyResults)
        first.strategyResults.forEach {
            assertEquals(60, it.candidateGroups)
            assertTrue(it.averageRedHits in 0.0..6.0)
            assertTrue(it.blueHitRate in 0.0..1.0)
        }
    }

    private fun syntheticRecords(count: Int): List<DrawRecord> = (0 until count).map { index ->
        val reds = (1..33).shuffled(java.util.Random(index.toLong())).take(6).sorted()
        DrawRecord(
            issue = (2026000 + index).toString(),
            drawDate = java.time.LocalDate.of(2026, 1, 1).plusDays(index.toLong()).toString(),
            reds = reds,
            blue = (index % 16) + 1,
            sourceUrl = "test"
        )
    }
}
