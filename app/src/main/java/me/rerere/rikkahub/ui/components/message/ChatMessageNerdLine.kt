package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.cachedPercent
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.totalCompletionTokens
import me.rerere.ai.ui.totalGenerationDurationMs
import me.rerere.ai.ui.totalUsage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
    cumulativeUsage: TokenUsage? = null,
    isGenerating: Boolean = false,
) {
    val settings = LocalSettings.current.displaySetting

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (settings.showTokenUsage && usage != null) {
                    // 累计口径：生成中显示"本次消息消耗"（随工具轮次实时增长），
                    // 生成结束后切换为"对话累计"（截至本条消息，匹配官方控制台口径）。
                    // 未传入累计值（其他调用方）或历史消息无轮次明细时，回退为本条消息自己的累计/单次 usage。
                    val displayUsage = when {
                        !settings.showCumulativeTokenUsage -> usage
                        isGenerating -> message.totalUsage() ?: usage
                        else -> cumulativeUsage ?: message.totalUsage() ?: usage
                    }
                    // Input tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${displayUsage.promptTokens.formatNumber()} tokens")
                            // Cached tokens（附缓存命中占总输入的比例；输入为 0 时无法计算则省略百分比）
                            val percent = displayUsage.cachedPercent()
                            if (percent != null) {
                                Text(
                                    text = "(${displayUsage.cachedTokens.formatNumber()} cached $percent%)"
                                )
                            } else if (displayUsage.cachedTokens > 0) {
                                Text(
                                    text = "(${displayUsage.cachedTokens.formatNumber()} cached)"
                                )
                            }
                        }
                    )
                    // Output tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Download04,
                                contentDescription = "Output",
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${displayUsage.completionTokens.formatNumber()} tokens")
                        }
                    )
                    // TPS
                    if (message.finishedAt != null) {
                        val duration = Duration.between(
                            message.createdAt.toJavaLocalDateTime(),
                            message.finishedAt!!.toJavaLocalDateTime()
                        )
                        // 有轮次明细时使用"本次消息总输出 / 纯生成耗时"（排除工具执行时间），
                        // 避免多轮工具调用把速度压得过低；历史消息无明细时回退旧逻辑
                        val tps = if (message.usageEntries.isNotEmpty()) {
                            val generationMs = message.totalGenerationDurationMs()
                            if (generationMs > 0) {
                                message.totalCompletionTokens().toFloat() / generationMs * 1000
                            } else {
                                0f
                            }
                        } else {
                            usage.completionTokens.toFloat() / duration.toMillis() * 1000
                        }
                        val seconds = (duration.toMillis() / 1000f).toFixed(1)
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = "Speed",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${tps.toFixed(1)} tok/s")
                            }
                        )

                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Clock02,
                                    contentDescription = "Duration",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${seconds}s")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
