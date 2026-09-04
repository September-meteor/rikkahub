package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.PriceTimeSlot
import me.rerere.ai.provider.TimeRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ManagedTextField
import me.rerere.rikkahub.ui.components.ui.rememberSyncedTextFieldState
import me.rerere.rikkahub.utils.insertAtCursor

private val PRESET_COLORS = listOf(
    "#FF5722",
    "#FF9800",
    "#FFEB3B",
    "#4CAF50",
    "#00BCD4",
    "#2196F3",
    "#3F51B5",
    "#9C27B0",
    "#E91E63",
    "#607D8B",
)

/**
 * 提示文字模板支持的变量及其显示名（参考助手提示词页「可用变量」的展示方式）。
 * Pair(占位符, 显示名资源)
 */
private val PRICE_VARIABLES: List<Pair<String, Int>> = listOf(
    "{input_price}" to R.string.setting_provider_page_price_slot_var_input_price,
    "{cached_input_price}" to R.string.setting_provider_page_price_slot_var_cached_input_price,
    "{output_price}" to R.string.setting_provider_page_price_slot_var_output_price,
    "{unit}" to R.string.setting_provider_page_price_slot_var_unit,
    "{model}" to R.string.setting_provider_page_price_slot_var_model,
    "{provider}" to R.string.setting_provider_page_price_slot_var_provider,
    "{time}" to R.string.setting_provider_page_price_slot_var_time,
    "{weekday}" to R.string.setting_provider_page_price_slot_var_weekday,
)

/** 校验颜色是否为 #RRGGBB 格式 */
fun isValidHexColor(value: String): Boolean {
    val trimmed = value.trim()
    if (!trimmed.startsWith("#") || trimmed.length != 7) return false
    return trimmed.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}

/** 校验时间是否为 HH:mm 格式且范围合法 */
fun isValidTime(value: String): Boolean {
    val trimmed = value.trim()
    val parts = trimmed.split(":")
    if (parts.size != 2) return false
    val hour = parts[0].toIntOrNull() ?: return false
    val minute = parts[1].toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

/**
 * 价格字符串解析：用 , 分隔三段（输入价,缓存输入价,输出价），空段跳过。
 * 返回 Triple(inputPrice, cachedInputPrice, outputPrice)，各元素 null 表示该段为空。
 * 非空段必须是合法数字，否则返回 null（整体解析失败）。
 * 注意：返回的是用户输入的原始文本（如 "0.50"），不做任何数字格式化。
 */
fun parsePriceString(value: String): Triple<String?, String?, String?>? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return Triple(null, null, null)
    val segments = trimmed.split(",").map { it.trim() }
    if (segments.size > 3) return null
    // 非空段必须是合法数字，否则整体解析失败
    segments.forEach { segment ->
        if (segment.isNotEmpty() && segment.toDoubleOrNull() == null) return null
    }
    return Triple(
        segments.getOrNull(0)?.takeIf { it.isNotEmpty() },
        segments.getOrNull(1)?.takeIf { it.isNotEmpty() },
        segments.getOrNull(2)?.takeIf { it.isNotEmpty() },
    )
}

/** 三个价格拼回输入框字符串（用 , 分隔；全部为空返回空串），原样保留用户输入 */
fun formatPriceInput(
    inputPrice: String?,
    cachedInputPrice: String?,
    outputPrice: String?,
): String {
    if (inputPrice == null && cachedInputPrice == null && outputPrice == null) return ""
    return listOf(inputPrice, cachedInputPrice, outputPrice)
        .joinToString(",") { it ?: "" }
}

/** 时间区间列表拼回输入框字符串，如 "9:00-11:00,13:00-16:00"；空列表返回空串 */
private fun formatTimeRangesInput(ranges: List<TimeRange>): String {
    if (ranges.isEmpty()) return ""
    return ranges.joinToString(",") { "${it.start}-${it.end}" }
}

/**
 * 解析时间区间输入字符串，如 "9:00-11:00,13:00-16:00"。
 * 返回 null 表示格式非法（整体解析失败）；空字符串返回空列表。
 */
private fun parseTimeRangesInput(value: String): List<TimeRange>? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return emptyList()
    val ranges = mutableListOf<TimeRange>()
    trimmed.split(",").forEach { segment ->
        val seg = segment.trim()
        if (seg.isEmpty()) return null
        val parts = seg.split("-")
        if (parts.size != 2) return null
        val start = parts[0].trim()
        val end = parts[1].trim()
        if (!isValidTime(start) || !isValidTime(end)) return null
        ranges += TimeRange(start = start, end = end)
    }
    return ranges
}

/** 星期摘要：全选/工作日/周末 合并显示，否则列出所选（允许换行） */
@Composable
private fun weekdaySummary(selected: Set<Int>): String {
    if (selected.isEmpty()) return stringResource(R.string.setting_provider_page_price_slot_weekday_none)
    val labels = stringArrayResource(R.array.setting_provider_page_price_slot_weekday_short)
    return when {
        selected == setOf(1, 2, 3, 4, 5, 6, 7) -> stringResource(R.string.setting_provider_page_price_slot_all_days)
        selected == setOf(1, 2, 3, 4, 5) -> stringResource(R.string.setting_provider_page_price_slot_workday)
        selected == setOf(6, 7) -> stringResource(R.string.setting_provider_page_price_slot_weekend)
        else -> selected.sorted().joinToString(stringResource(R.string.setting_provider_page_price_slot_weekday_separator)) { labels[it - 1] }
    }
}

@Composable
fun PriceSlotSettings(
    priceSlots: List<PriceTimeSlot>,
    defaultPriceSlot: PriceTimeSlot?,
    onUpdatePriceSlots: (List<PriceTimeSlot>) -> Unit,
    onUpdateDefaultPriceSlot: (PriceTimeSlot?) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_price_slots),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(R.string.setting_provider_page_price_slots_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 时段价格列表
        priceSlots.forEachIndexed { index, slot ->
            key(slot.id) {
                PriceSlotCard(
                    slot = slot,
                    isDefault = false,
                    slotIndex = index,
                    onUpdate = { updated ->
                        onUpdatePriceSlots(priceSlots.toMutableList().apply { this[index] = updated })
                    },
                    onDelete = {
                        onUpdatePriceSlots(priceSlots.toMutableList().apply { removeAt(index) })
                    },
                )
            }
        }

        Button(
            onClick = {
                onUpdatePriceSlots(priceSlots + PriceTimeSlot())
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.setting_provider_page_add_price_slot))
        }

        // 其他时间：跟随在时段卡片后，至少有一个时段时才显示。
        // 无删除按钮（兜底配置始终存在），清空价格/提示文字即等于不显示。
        if (priceSlots.isNotEmpty()) {
            Text(
                text = stringResource(R.string.setting_provider_page_other_times),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = stringResource(R.string.setting_provider_page_other_times_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            key("default-slot") {
                PriceSlotCard(
                    slot = defaultPriceSlot ?: PriceTimeSlot(),
                    isDefault = true,
                    onUpdate = { updated ->
                        onUpdateDefaultPriceSlot(updated)
                    },
                    onDelete = null,
                )
            }
        }
    }
}

@Composable
private fun PriceSlotCard(
    slot: PriceTimeSlot,
    isDefault: Boolean,
    onUpdate: (PriceTimeSlot) -> Unit,
    onDelete: (() -> Unit)?,
    slotIndex: Int = 0,
) {
    var showWeekdaySheet by remember { mutableStateOf(false) }
    // rememberSaveable：item 回收重建后恢复提示文字内容；promptState 随之重建
    var savedPrompt by rememberSaveable { mutableStateOf(slot.prompt) }
    val promptState = rememberTextFieldState(initialText = savedPrompt)
    // 颜色输入框本地文本：跟随外部值，但允许输入过程中的非法中间态（rememberSaveable 防回收丢失）
    var colorText by rememberSaveable(slot.id) { mutableStateOf(slot.color) }
    LaunchedEffect(slot.color) {
        if (colorText != slot.color) colorText = slot.color
    }
    // 外部 prompt 更新时同步 savedPrompt（避免用户输入被外部旧值覆盖）
    LaunchedEffect(slot.prompt) {
        if (promptState.text.toString() != slot.prompt) {
            savedPrompt = slot.prompt
            promptState.setTextAndPlaceCursorAtEnd(slot.prompt)
        }
    }

    // 提示文字内容变化时同步到外部（用 rememberUpdatedState 获取最新 slot）
    val currentSlot by rememberUpdatedState(slot)
    LaunchedEffect(Unit) {
        snapshotFlow { promptState.text.toString() }.collect { text ->
            if (text.length > 200) {
                // 限制长度：超过 200 字符截断（提示文字模板足够长）
                promptState.edit {
                    val newText = text.take(200)
                    replace(0, length, newText)
                    selection = androidx.compose.ui.text.TextRange(newText.length)
                }
                return@collect
            }
            if (text != currentSlot.prompt) {
                onUpdate(currentSlot.copy(prompt = text))
            }
        }
    }

    // key(slot.id)：编辑时 id 不变 → 输入状态保持；删除后重建（新 id）→ 状态重置
    key(slot.id) {
        CardGroup {
            item(
                headlineContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (isDefault) {
                                stringResource(R.string.setting_provider_page_other_times)
                            } else {
                                stringResource(R.string.setting_provider_page_price_slot_index, slotIndex + 1)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (onDelete != null) {
                            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.setting_provider_page_price_slot_delete),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                },
                supportingContent = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 第一行：星期 + 时间区间（仅普通卡片）
                        if (!isDefault) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 星期（点击弹窗选择，按钮显示已选摘要）
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(0.9f)
                                ) {
                                    // 与时间区间输入框 label 对齐的标题
                                    Text(
                                        text = stringResource(R.string.setting_provider_page_price_slot_weekdays),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        minLines = 1,
                                    )
                                    OutlinedButton(
                                        onClick = { showWeekdaySheet = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = weekdaySummary(slot.weekdays),
                                        )
                                    }
                                }
                                // 时间区间输入框（, 间隔，可换行显示）
                                TimeRangesInput(
                                    ranges = slot.timeRanges,
                                    onUpdate = { ranges ->
                                        onUpdate(slot.copy(timeRanges = ranges))
                                    },
                                    modifier = Modifier
                                        .weight(1.4f)
                                        .fillMaxWidth()
                                )
                            }
                        }

                        // 第二行：价格 + 单位（输入框内置 label，高度由 singleLine 统一，天然对齐）
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            PriceInput(
                                inputPrice = slot.inputPrice,
                                cachedInputPrice = slot.cachedInputPrice,
                                outputPrice = slot.outputPrice,
                                onUpdate = { input, cached, output ->
                                    onUpdate(slot.copy(inputPrice = input, cachedInputPrice = cached, outputPrice = output))
                                },
                                modifier = Modifier
                                    .weight(1.4f)
                                    .fillMaxWidth()
                            )
                            UnitInput(
                                unit = slot.unit,
                                onUpdate = { unit ->
                                    onUpdate(slot.copy(unit = unit))
                                },
                                modifier = Modifier
                                    .weight(0.9f)
                                    .fillMaxWidth()
                            )
                        }

                        // 提示文字：单独一行，内部可换行
                        OutlinedTextField(
                            state = promptState,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_provider_page_price_slot_prompt)) },
                            placeholder = { Text(stringResource(R.string.setting_provider_page_price_slot_prompt_placeholder)) },
                            lineLimits = TextFieldLineLimits.MultiLine(
                                minHeightInLines = 2,
                                maxHeightInLines = 5
                            ),
                        )

                        // 变量药丸（点击插入到提示文字光标位置），参考助手提示词页「可用变量」
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PRICE_VARIABLES.forEach { (variable, labelRes) ->
                                val displayName = stringResource(labelRes)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                                        .clickable {
                                            promptState.insertAtCursor(variable)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "$displayName: $variable",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }

                        // 颜色：一行左边 hex 输入框，右边两行色板（预览块在输入框与色板之间）
                        ColorRow(
                            color = slot.color,
                            colorText = colorText,
                            onColorTextChange = { colorText = it },
                            onUpdate = { color ->
                                onUpdate(slot.copy(color = color))
                            },
                            trailing = {
                                // 颜色预览：实时跟随输入框内容渲染（输入即刷新），为空/不合法时显示空心边框
                                ColorPreview(hex = colorText)
                            }
                        )
                    }
                }
            )
        }
    }

    // 星期选择弹窗
    if (showWeekdaySheet) {
        WeekdaySheet(
            selected = slot.weekdays,
            onSelect = { weekdays ->
                onUpdate(slot.copy(weekdays = weekdays))
            },
            onDismiss = { showWeekdaySheet = false },
        )
    }
}

@Composable
private fun WeekdaySheet(
    selected: Set<Int>,
    onSelect: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_price_slot_weekdays),
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val chipLabels = stringArrayResource(R.array.setting_provider_page_price_slot_weekday_short)
                chipLabels.forEachIndexed { index, label ->
                    val day = index + 1
                    FilterChip(
                        selected = day in selected,
                        onClick = {
                            onSelect(if (day in selected) selected - day else selected + day)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onSelect(setOf(1, 2, 3, 4, 5)) }
                ) {
                    Text(stringResource(R.string.setting_provider_page_price_slot_workday))
                }
                TextButton(
                    onClick = { onSelect(setOf(6, 7)) }
                ) {
                    Text(stringResource(R.string.setting_provider_page_price_slot_weekend))
                }
                TextButton(
                    onClick = { onSelect(setOf(1, 2, 3, 4, 5, 6, 7)) }
                ) {
                    Text(stringResource(R.string.setting_provider_page_price_slot_all_days))
                }
            }
        }
    }
}

/**
 * 时间区间输入框：单个输入框，用 , 间隔，如 "9:00-11:00,13:00-16:00"。
 * placeholder 占两行时输入框高度保持固定；失焦时才校验格式。
 */
@Composable
private fun TimeRangesInput(
    ranges: List<TimeRange>,
    onUpdate: (List<TimeRange>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 新版 TextFieldState API：输入以 state 为准，配对符号/光标稳定；失焦才校验格式
    val invalidTimeError = stringResource(R.string.setting_provider_page_price_slot_invalid_time)
    val timeRangesState = rememberTextFieldState(initialText = formatTimeRangesInput(ranges))

    ManagedTextField(
        state = timeRangesState,
        modifier = modifier,
        label = { Text(stringResource(R.string.setting_provider_page_price_slot_time_ranges)) },
        placeholder = { Text(stringResource(R.string.setting_provider_page_price_slot_time_placeholder)) },
        // 固定两行：输入内容换行也保持高度稳定（与「自定义排除模式」输入框一致）
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 2),
        persistDebounceMs = 250,
        onPersist = { value ->
            // 输入过程中不提示错误，合法则写入模型（长度>60 解析自然失败，静默跳过）
            if (value.length <= 60) {
                parseTimeRangesInput(value)?.let(onUpdate)
            }
        },
        validateOnBlur = { text ->
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && parseTimeRangesInput(trimmed) == null) {
                invalidTimeError
            } else {
                null
            }
        },
    )
}

@Composable
private fun PriceInput(
    inputPrice: String?,
    cachedInputPrice: String?,
    outputPrice: String?,
    onUpdate: (String?, String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 新版 TextFieldState API：可正常输入逗号与配对符号；失焦才校验
    val invalidPriceError = stringResource(R.string.setting_provider_page_price_slot_invalid_price)
    val priceState = rememberTextFieldState(initialText = formatPriceInput(inputPrice, cachedInputPrice, outputPrice))

    ManagedTextField(
        state = priceState,
        modifier = modifier,
        label = { Text(stringResource(R.string.setting_provider_page_price_slot_price)) },
        placeholder = { Text(stringResource(R.string.setting_provider_page_price_slot_price_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        persistDebounceMs = 250,
        onPersist = { value ->
            // 输入过程中不提示错误，合法则写入模型（>40 字符解析自然失败，静默跳过）
            if (value.length <= 40) {
                parsePriceString(value)?.let { onUpdate(it.first, it.second, it.third) }
            }
        },
        validateOnBlur = { text ->
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && parsePriceString(trimmed) == null) {
                invalidPriceError
            } else {
                null
            }
        },
    )
}

@Composable
private fun UnitInput(
    unit: String,
    onUpdate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ManagedTextField(
        state = rememberSyncedTextFieldState(unit),
        label = { Text(stringResource(R.string.setting_provider_page_price_slot_unit)) },
        placeholder = { Text(stringResource(R.string.setting_provider_page_price_slot_unit_placeholder)) },
        singleLine = true,
        modifier = modifier,
        persistDebounceMs = 250,
        onPersist = { value -> if (value.length <= 20) onUpdate(value) },
    )
}

@Composable
private fun ColorRow(
    color: String,
    colorText: String,
    onColorTextChange: (String) -> Unit,
    onUpdate: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    // 输入过程中的非法中间态标记
    var colorError by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 左边：十六进制输入框
        OutlinedTextField(
            value = colorText,
            onValueChange = { value ->
                // 限制长度（hex 颜色最多 7 字符）
                if (value.length > 7) return@OutlinedTextField
                onColorTextChange(value)
                val valid = isValidHexColor(value)
                colorError = value.isNotEmpty() && !valid
                if (valid) {
                    onUpdate(value)
                }
            },
            label = { Text(stringResource(R.string.setting_provider_page_price_slot_color)) },
            placeholder = { Text(stringResource(R.string.setting_provider_page_price_slot_color_placeholder)) },
            supportingText = if (colorError) {
                { Text(stringResource(R.string.setting_provider_page_price_slot_invalid_color)) }
            } else {
                null
            },
            isError = colorError,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )

        // 预览块（如颜色预览）
        trailing?.invoke()

        // 右边：两行色板（垂直居中与输入框对齐）
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESET_COLORS.chunked(5).forEach { rowColors ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowColors.forEach { preset ->
                        val presetColor = runCatching { Color(android.graphics.Color.parseColor(preset)) }.getOrNull()
                            ?: Color.Gray
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(presetColor)
                                .border(
                                    width = 2.dp,
                                    color = if (colorText.equals(preset, ignoreCase = true)) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .clickable {
                                    onColorTextChange(preset)
                                    colorError = false
                                    onUpdate(preset)
                                }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 颜色预览块：与标题文字同高的圆角小方块。
 * 依照 hex 文本实时渲染（输入框内容变化即刷新）；为空或不合法时显示空心边框（不渲染颜色）。
 */
@Composable
private fun ColorPreview(
    hex: String,
) {
    val validColor = runCatching {
        if (isValidHexColor(hex)) Color(android.graphics.Color.parseColor(hex)) else null
    }.getOrNull()

    Box(
        modifier = Modifier
            .size(width = 14.dp, height = 14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (validColor != null) validColor else Color.Transparent,
                shape = RoundedCornerShape(3.dp),
            )
            .border(
                width = 1.dp,
                color = if (validColor != null) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(3.dp),
            ),
    )
}
