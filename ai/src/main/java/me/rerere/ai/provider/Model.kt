package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // 实时 Token 价格：时段价格列表（按顺序第一个命中生效）
    val priceSlots: List<PriceTimeSlot> = emptyList(),
    // 实时 Token 价格：默认行（未命中任何时段时显示，weekdays/timeRanges 固定为空）
    val defaultPriceSlot: PriceTimeSlot? = null,
)

/**
 * 一天内的时间区间，HH:mm 格式，如 "09:00" - "11:00"。
 * start == end 视为全天；start > end（跨天）不允许，UI 层阻止创建。
 */
@Serializable
data class TimeRange(
    val id: Uuid = Uuid.random(),
    val start: String = "00:00",
    val end: String = "23:59",
)

/**
 * 一条时段价格配置。
 *
 * @param weekdays 命中的星期（1=周一 .. 7=周日）；为空表示每天
 * @param timeRanges 一天内命中的时间区间列表；为空表示全天
 * @param inputPrice 输入（未命中缓存）价格，null 表示未填（按用户输入原样保存，如 "0.50"）
 * @param cachedInputPrice 输入（命中缓存）价格，null 表示未填
 * @param outputPrice 输出价格，null 表示未填
 * @param unit 价格单位（如 "元/1K tokens"），可空
 * @param prompt 提示文字模板，支持 {input_price} {cached_input_price} {output_price} {unit} {model} {provider} {time} {weekday}
 * @param color 高亮背景色（十六进制，如 #FF5722）
 */
@Serializable
data class PriceTimeSlot(
    val id: Uuid = Uuid.random(),
    val weekdays: Set<Int> = emptySet(),
    val timeRanges: List<TimeRange> = emptyList(),
    val inputPrice: String? = null,
    val cachedInputPrice: String? = null,
    val outputPrice: String? = null,
    val unit: String = "",
    val prompt: String = "",
    val color: String = "#FF5722",
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}
