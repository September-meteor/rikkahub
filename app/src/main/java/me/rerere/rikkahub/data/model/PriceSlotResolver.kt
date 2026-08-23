package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.PriceTimeSlot
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 判断某个时间段配置是否在当前时间命中。
 *
 * 匹配规则：
 * - weekdays 为空 且 timeRanges 为空 → 该行不生效（跳过，永远不匹配）
 * - weekdays 为空 但 timeRanges 非空 → 每天该时间段命中
 * - weekdays 非空 但 timeRanges 为空 → 所选星期全天命中
 * - 两者都非空 → 同时满足才命中
 * - 时间区间：start == end 视为全天；start > end（跨天）不允许，UI 层阻止创建，此处视为不匹配
 */
fun PriceTimeSlot.matches(now: LocalDateTime): Boolean {
    // 时段全空 → 不生效
    if (weekdays.isEmpty() && timeRanges.isEmpty()) return false

    // 星期匹配
    if (weekdays.isNotEmpty() && now.dayOfWeek.value !in weekdays) return false

    // 时间匹配
    if (timeRanges.isNotEmpty()) {
        val nowMinutes = now.hour * 60 + now.minute
        val hit = timeRanges.any { range ->
            val start = range.start.toMinutesOrNull() ?: return@any false
            val end = range.end.toMinutesOrNull() ?: return@any false
            when {
                start == end -> true // 视为全天
                start > end -> false // 跨天，不匹配
                else -> nowMinutes in start..end
            }
        }
        if (!hit) return false
    }

    return true
}

/**
 * 解析当前时间命中的价格时段。
 *
 * 返回规则：
 * - priceSlots 与 defaultPriceSlot 都为空 → 返回 null（不显示价格行）
 * - priceSlots 为空但 defaultPriceSlot 非空 → 返回 defaultPriceSlot（全天一个价）
 * - priceSlots 非空 → 按列表顺序，第一个 matches 的 slot 返回
 * - 都不匹配 → 返回 defaultPriceSlot（可为 null，此时不显示）
 */
fun resolvePriceSlot(
    priceSlots: List<PriceTimeSlot>,
    defaultPriceSlot: PriceTimeSlot?,
    now: LocalDateTime,
): PriceTimeSlot? {
    priceSlots.forEach { slot ->
        if (slot.matches(now)) return slot
    }
    // 未命中任何时段：若配置了其他时间则显示之，否则不显示
    return defaultPriceSlot
}

/**
 * 自动生成文案的标签（用于「提示文字为空但填了价格」时）。
 * 由 UI 层从字符串资源构造，避免在此处硬编码语言。
 */
data class PriceAutoLabels(
    val prefix: String,      // 如 "当前价格："
    val input: String,       // 如 "输入"
    val cachedInput: String, // 如 "缓存输入"
    val output: String,      // 如 "输出"
    val separator: String = " / ",
)

/**
 * 渲染价格提示文字。
 *
 * - prompt 非空：替换模板变量后返回
 * - prompt 为空但至少填了一个价格：自动生成「prefix + 输入 X + sep + 缓存输入 Y + sep + 输出 Z」（只显示填了的项）
 * - prompt 为空且价格全空：返回空字符串（调用方应隐藏）
 *
 * 模板变量：
 * - {input_price} 输入（未命中缓存）价格
 * - {cached_input_price} 输入（命中缓存）价格
 * - {output_price} 输出价格
 * - {unit} 价格单位
 * - {model} 模型显示名
 * - {provider} 提供商名
 * - {time} 当前时间 HH:mm
 * - {weekday} 今天星期几（跟随系统语言，如「周一」/「Mon」）
 */
fun PriceTimeSlot.renderPrompt(
    model: Model,
    providerName: String,
    now: LocalDateTime,
    autoLabels: PriceAutoLabels,
): String {
    val input = inputPrice
    val cached = cachedInputPrice
    val output = outputPrice
    val unit = unit

    val template = prompt
    if (template.isBlank()) {
        if (input == null && cached == null && output == null) return ""
        // 自动生成：只显示填了的项，价格原样显示（用户填什么就显示什么），价格后附单位（单位非空时）
        fun priceWithUnit(value: String): String {
            return if (unit.isBlank()) value else "$value $unit"
        }
        val parts = buildList {
            input?.let { add("${autoLabels.input} ${priceWithUnit(it)}") }
            cached?.let { add("${autoLabels.cachedInput} ${priceWithUnit(it)}") }
            output?.let { add("${autoLabels.output} ${priceWithUnit(it)}") }
        }
        return autoLabels.prefix + parts.joinToString(autoLabels.separator)
    }

    return template
        .replace("{input_price}", input ?: "")
        .replace("{cached_input_price}", cached ?: "")
        .replace("{output_price}", output ?: "")
        .replace("{unit}", unit)
        .replace("{model}", model.displayName)
        .replace("{provider}", providerName)
        .replace("{time}", now.format(TIME_FORMATTER))
        .replace("{weekday}", now.dayOfWeek.weekdayLabel())
}

private fun String.toMinutesOrNull(): Int? {
    return runCatching {
        val parts = split(":")
        if (parts.size != 2) return null
        parts[0].toInt() * 60 + parts[1].toInt()
    }.getOrNull()
}

private fun DayOfWeek.weekdayLabel(): String {
    return this.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}
