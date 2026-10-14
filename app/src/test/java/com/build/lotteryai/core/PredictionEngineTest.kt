package com.build.lotteryai.core

import com.build.lotteryai.model.DrawRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionEngineTest {
    private val engine = PredictionEngine()

    @Test
    fun createsUniqueLegalCandidatesForAllStrategies() {
        PredictionEngine.Strategy.values().forEach { strategy ->
            val candidates = engine.generate(emptyList(), 20, strategy, seed = 12345L)
            assertEquals(20, candidates.size)
            assertTrue(candidates.all { it.isValid() })
            assertEquals(20, candidates.map { it.displayNumbers() }.toSet().size)
        }
    }

    @Test
    fun fixedSeedReproducesResults() {
        val first = engine.generate(emptyList(), 10, PredictionEngine.Strategy.RANDOM, seed = 9L)
        val second = engine.generate(emptyList(), 10, PredictionEngine.Strategy.RANDOM, seed = 9L)
        assertEquals(first.map { it.displayNumbers() }, second.map { it.displayNumbers() })
    }

    @Test
    fun ignoresInvalidRecordsInSummary() {
        val history = listOf(
            DrawRecord("2026001", "2026-01-01", listOf(1, 2, 3, 4, 5, 6), 7, "test"),
            DrawRecord("invalid", "2026-01-02", listOf(1, 1, 1, 1, 1, 1), 0, "test")
        )
        assertEquals("1", engine.summary(history)["期数"])
        assertTrue(engine.frequency(listOf(1, 1, 3), 3)[1] == 2)
    }
}
