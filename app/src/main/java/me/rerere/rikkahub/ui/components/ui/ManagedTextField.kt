package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldLabelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.input.rememberTextFieldState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/**
 * 统一输入框：复用消息输入框逻辑（新版 TextFieldState API）。
 *
 * 与旧版 `value/onValueChange` 的关键区别：
 * - 输入路径零变换：IME 组合态（智能配对符号）安全，光标不跳；
 * - 持久化解耦：[persistDebounceMs] > 0 时通过 snapshotFlow + debounce 单向推送，不回流覆盖输入；
 * - 规范化/校验移到失焦：[normalizeOnBlur]（如 trim）、[validateOnBlur]（返回错误文案）。
 *
 * [state] 由调用方持有（页面 VM 字段或 rememberTextFieldState），保证跨重组/滚动稳定。
 */

/**
 * 创建用于"设置表单"的 TextFieldState：页面初始状态可能异步加载（如 DataStore/Room），
 * 仅在加载出非空初始值且用户尚未输入时同步一次，绝不打断用户输入。
 */
@Composable
fun rememberSyncedTextFieldState(initialText: String): TextFieldState {
    val state = rememberTextFieldState()
    LaunchedEffect(initialText) {
        if (initialText.isNotEmpty() && state.text.isEmpty()) {
            state.setTextAndPlaceCursorAtEnd(initialText)
        }
    }
    return state
}

/**
 * 多行密钥/私钥输入框（如 PEM）。
 *
 * 为什么不用新版 TextFieldState 渲染：material3 此版本 SecureTextField 被官方锁死为单行
 * （SecureTextFieldKt 内部硬编码 TextFieldLineLimits.SingleLine），且 state 版输入框不支持
 * visualTransformation（掩码）。多行 + 掩码只能走旧视觉管线。
 *
 * 与旧代码的区别：本地草稿（不随模型回流）、输入零变换、防抖持久化、失焦规范化 ——
 * 光标/输入稳定性与新版一致；密码/密钥输入不涉及"配对符号"，无 IME 组合态问题。
 */
@OptIn(FlowPreview::class)
@Composable
fun MultilineSecretField(
    value: String,
    onPersist: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = LocalTextStyle.current,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    normalizeOnBlur: ((String) -> String)? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    var draft by remember { mutableStateOf(value) }
    var userEdited by remember { mutableStateOf(false) }

    // 外部值变化（异步加载/重置等）：仅当用户尚未编辑时同步，绝不打断输入
    LaunchedEffect(value) {
        if (!userEdited && value != draft) {
            draft = value
        }
    }

    // 防抖持久化
    LaunchedEffect(Unit) {
        snapshotFlow { draft }
            .drop(1)
            .debounce(300)
            .collect { onPersist(it) }
    }

    val focusModifier = Modifier.onFocusChanged { focusState ->
        if (!focusState.isFocused) {
            val normalized = normalizeOnBlur?.invoke(draft) ?: draft
            if (normalized != draft) draft = normalized
            onPersist(normalized)
            userEdited = false
        }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            userEdited = true
        },
        modifier = modifier.then(focusModifier),
        label = label,
        placeholder = placeholder,
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = textStyle,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        colors = colors,
    )
}

@OptIn(FlowPreview::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ManagedTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: (@Composable TextFieldLabelScope.() -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    textStyle: TextStyle = LocalTextStyle.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    textObfuscationMode: TextObfuscationMode? = null,
    textObfuscationCharacter: Char = '\u2022',
    persistDebounceMs: Long = 0L,
    onPersist: ((String) -> Unit)? = null,
    normalizeOnBlur: ((String) -> String)? = null,
    validateOnBlur: ((String) -> String?)? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    var blurError by remember { mutableStateOf<String?>(null) }
    // 用 rememberUpdatedState 保证防抖 collect 中始终读到最新 onPersist（避免过期闭包覆盖其它字段）
    val currentOnPersist by rememberUpdatedState(onPersist)

    // 持久化：观察 state 变化 → debounce 合并 → 单向推送（不回流）
    if (persistDebounceMs > 0 && currentOnPersist != null) {
        LaunchedEffect(state) {
            snapshotFlow { state.text.toString() }
                .drop(1) // 跳过初始值，避免打开页面即触发一次持久化
                .debounce(persistDebounceMs)
                .collect { currentOnPersist?.invoke(it) }
        }
    }

    // 失焦：规范化 + 校验（输入过程中绝不改动文本）
    val focusModifier = Modifier.onFocusChanged { focusState ->
        if (!focusState.isFocused) {
            normalizeOnBlur?.let { normalize ->
                val raw = state.text.toString()
                val normalized = normalize(raw)
                if (normalized != raw) {
                    state.setTextAndPlaceCursorAtEnd(normalized)
                }
            }
            blurError = validateOnBlur?.invoke(state.text.toString())
        } else {
            blurError = null
        }
    }

    // 两个渲染分支（安全 / 普通）共享的派生参数，避免同一份逻辑写两遍。
    // 分支内剩余的差异参数（textObfuscation* / readOnly / lineLimits）分属两个不同
    // 组件的独有 API，无法再共用，属不可压缩的透传。
    val effectiveModifier = modifier.then(focusModifier)
    val effectiveIsError = isError || blurError != null
    val effectiveLineLimits = if (singleLine) TextFieldLineLimits.SingleLine else lineLimits
    val supportingTextContent: @Composable () -> Unit = {
        blurError?.let { Text(it) } ?: supportingText?.invoke()
    }

    if (textObfuscationMode != null) {
        // 密码/密钥：state 版安全输入框（新 API），掩码由调用方用 textObfuscationMode 切换（如 Hidden/Visible）
        OutlinedSecureTextField(
            state = state,
            modifier = effectiveModifier,
            label = label,
            placeholder = placeholder,
            supportingText = supportingTextContent,
            isError = effectiveIsError,
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            trailingIcon = trailingIcon,
            textObfuscationMode = textObfuscationMode,
            textObfuscationCharacter = textObfuscationCharacter,
            colors = colors,
        )
    } else {
        OutlinedTextField(
            state = state,
            modifier = effectiveModifier,
            label = label,
            placeholder = placeholder,
            supportingText = supportingTextContent,
            isError = effectiveIsError,
            enabled = enabled,
            readOnly = readOnly,
            lineLimits = effectiveLineLimits,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            trailingIcon = trailingIcon,
            colors = colors,
        )
    }
}
