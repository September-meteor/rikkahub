package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Translate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.writeClipboardText
import java.util.Locale

@Composable
fun ChatMessageUserAvatar(
    message: UIMessage,
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && settings.displaySetting.showUserAvatar) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1,
            )
            UIAvatar(
                name = nickname,
                modifier = Modifier.size(28.dp),
                value = avatar,
                loading = false,
            )
        }
    }
}

@Composable
fun ChatMessageAssistantAvatar(
    message: UIMessage,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    reasoningText: String? = null,
    modifier: Modifier = Modifier,
    onToggleTranslateReasoning: (() -> Unit)? = null,
    onSelectReasoningLanguage: (() -> Unit)? = null,
    targetLanguage: Locale = Locale.getDefault(),
    showTranslated: Boolean = false,
    hasTranslation: Boolean = false,
    isTranslating: Boolean = false,   // <-- 新增参数
) {
    val settings = LocalSettings.current
    val showIcon = settings.displaySetting.showModelIcon
    val useAssistantAvatar = assistant?.useAssistantAvatar == true
    val context = LocalContext.current

    if (message.role == MessageRole.ASSISTANT && (model != null || useAssistantAvatar)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            if (useAssistantAvatar) {
                if (showIcon) {
                    UIAvatar(
                        name = assistant.name,
                        modifier = Modifier.size(28.dp),
                        value = assistant.avatar,
                        loading = loading,
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (settings.displaySetting.showModelName) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) },
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = 1,
                        )
                    }
                    // 复制思维链按钮
                    if (!reasoningText.isNullOrBlank()) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = stringResource(R.string.message_reasoning_copy),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { context.writeClipboardText(reasoningText) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    // 翻译思维链按钮  // <-- 修改
                    if (!reasoningText.isNullOrBlank() && onToggleTranslateReasoning != null) {
                        val tint = when {
                            isTranslating -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)  // 翻译中半透明
                            hasTranslation -> if (showTranslated) MaterialTheme.colorScheme.primary
                                              else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                        Icon(
                            imageVector = HugeIcons.Translate,
                            contentDescription = stringResource(R.string.translate),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onToggleTranslateReasoning() },
                            tint = tint
                        )
                    }
                    // 语言选择按钮（迷你国旗）
                    if (!reasoningText.isNullOrBlank() && onSelectReasoningLanguage != null) {
                        Text(
                            text = targetLanguage.toFlagEmoji(),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onSelectReasoningLanguage() },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else if (model != null) {
                if (showIcon) {
                    AutoAIIcon(
                        name = model.modelId,
                        modifier = Modifier.size(28.dp),
                        loading = loading
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (settings.displaySetting.showModelName) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 复制思维链按钮
                    if (!reasoningText.isNullOrBlank()) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = stringResource(R.string.message_reasoning_copy),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { context.writeClipboardText(reasoningText) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    // 翻译思维链按钮
                    if (!reasoningText.isNullOrBlank() && onToggleTranslateReasoning != null) {
                        val tint = when {
                            isTranslating -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)  // 翻译中半透明
                            hasTranslation -> if (showTranslated) MaterialTheme.colorScheme.primary
                                              else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                        Icon(
                            imageVector = HugeIcons.Translate,
                            contentDescription = stringResource(R.string.translate),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onToggleTranslateReasoning() },
                            tint = tint
                        )
                    }
                    // 语言选择按钮（迷你国旗）
                    if (!reasoningText.isNullOrBlank() && onSelectReasoningLanguage != null) {
                        Text(
                            text = targetLanguage.toFlagEmoji(),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onSelectReasoningLanguage() },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun Locale.toFlagEmoji(): String = when {
    this == Locale.SIMPLIFIED_CHINESE || this == Locale.CHINESE -> "🇨🇳"
    this == Locale.TRADITIONAL_CHINESE || toString() == "zh_TW" -> "🇨🇳"
    this == Locale.ENGLISH || language == "en" -> "🇺🇸"
    this == Locale.JAPANESE || language == "ja" -> "🇯🇵"
    this == Locale.KOREAN || language == "ko" -> "🇰🇷"
    this == Locale.FRENCH || language == "fr" -> "🇫🇷"
    this == Locale.GERMAN || language == "de" -> "🇩🇪"
    this == Locale("es", "ES") || language == "es" -> "🇪🇸"
    this == Locale.ITALIAN || language == "it" -> "🇮🇹"
    else -> "🌐"
}