package me.rerere.ai.core

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
)

/**
 * 一次 API 请求（工具循环中的一轮）的 Token 用量，以及该次请求的纯生成耗时（毫秒，不含工具执行）。
 */
@Serializable
data class UsageEntry(
    val tokens: TokenUsage,
    val durationMs: Long,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens
    )
}

/**
 * 累加两次用量，用于跨多次 API 请求（如工具循环）的累计统计。
 *
 * 与 [merge] 语义不同：[merge] 是"同一次响应流内分片组合"（逐字段取最新非零值），
 * 而 [sum] 是"跨请求累计"（各字段相加）。
 */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}

/**
 * 缓存命中占总输入 Token 的比例（0-100，四舍五入）。
 *
 * 当 [TokenUsage.promptTokens] 小于等于 0 时无法计算，返回 null（调用方应省略百分比显示）。
 */
fun TokenUsage.cachedPercent(): Int? {
    if (promptTokens <= 0) return null
    return (cachedTokens.toFloat() / promptTokens * 100).roundToInt()
}
