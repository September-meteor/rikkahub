package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {

    @Test
    fun `sum should accumulate all fields across requests`() {
        val first = TokenUsage(promptTokens = 100, completionTokens = 20, cachedTokens = 50, totalTokens = 120)
        val second = TokenUsage(promptTokens = 200, completionTokens = 30, cachedTokens = 100, totalTokens = 230)

        val result = first.sum(second)!!

        assertEquals(300, result.promptTokens)
        assertEquals(50, result.completionTokens)
        assertEquals(150, result.cachedTokens)
        assertEquals(350, result.totalTokens)
    }

    @Test
    fun `sum should handle null operands`() {
        assertNull(null.sum(null))
        assertEquals(TokenUsage(promptTokens = 1), null.sum(TokenUsage(promptTokens = 1)))
        assertEquals(TokenUsage(promptTokens = 1), TokenUsage(promptTokens = 1).sum(null))
    }

    @Test
    fun `cachedPercent should compute rounded percentage`() {
        assertEquals(50, TokenUsage(promptTokens = 200, cachedTokens = 100).cachedPercent())
        assertEquals(0, TokenUsage(promptTokens = 200, cachedTokens = 0).cachedPercent())
        assertEquals(100, TokenUsage(promptTokens = 100, cachedTokens = 100).cachedPercent())
        // 四舍五入：2/3 -> 67%
        assertEquals(67, TokenUsage(promptTokens = 300, cachedTokens = 200).cachedPercent())
    }

    @Test
    fun `cachedPercent should return null when prompt is zero`() {
        assertNull(TokenUsage(promptTokens = 0, cachedTokens = 100).cachedPercent())
        assertNull(TokenUsage(promptTokens = -1, cachedTokens = 100).cachedPercent())
    }
}
