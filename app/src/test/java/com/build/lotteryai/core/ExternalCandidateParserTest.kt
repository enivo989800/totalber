package com.build.lotteryai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalCandidateParserTest {
    @Test fun extractsValidAndFlagsInvalidSsqCombinations() {
        val input = """
            建议组合：03 07 12 18 22 29 + 08
            第二组：01 01 17 26 32 33 + 12
            第三组：01 06 11 17 26 32 + 17
        """.trimIndent()
        val parsed = ExternalCandidateParser.parse(input)
        assertEquals(3, parsed.found.size)
        assertEquals(1, parsed.validCandidates.size)
        assertEquals(2, parsed.invalidCount)
        assertTrue(parsed.status.contains("不合法"))
    }
}
