package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.util.ImageCompressionConfig
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_REASONING_TRANSLATE_SEPARATOR
import me.rerere.rikkahub.data.datastore.ReasoningTranslateFallbackMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesExperimentalPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current


    // 页面打开时同步一次
    LaunchedEffect(Unit) {
        ImageCompressionConfig.enabled = settings.enableImageCompression
        ImageCompressionConfig.maxDimension = settings.imageMaxDimension
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_experimental)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                },
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.image_compression_title)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.ImageUpload, null) },
                        headlineContent = { Text(stringResource(R.string.image_compression_enable)) },
                        supportingContent = { Text(stringResource(R.string.image_compression_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.enableImageCompression,
                                onCheckedChange = { checked ->
                                    ImageCompressionConfig.enabled = checked
                                    vm.updateSettings(settings.copy(enableImageCompression = checked))
                                }
                            )
                        }
                    )
                    if (settings.enableImageCompression) {
                        item(
                            leadingContent = { Icon(HugeIcons.ImageUpload, null) },
                            headlineContent = { Text(stringResource(R.string.image_compression_max_dimension)) },
                            supportingContent = { Text("范围: 512 ~ 8192") },
                            trailingContent = {
                                var inputText by remember { mutableStateOf(settings.imageMaxDimension.toString()) }

                                // 外部设置变化时同步（比如从其他设备同步回来）
                                LaunchedEffect(settings.imageMaxDimension) {
                                    if (inputText != settings.imageMaxDimension.toString()) {
                                        inputText = settings.imageMaxDimension.toString()
                                    }
                                }

                                fun confirmValue() {
                                    val number = inputText.toIntOrNull()
                                    if (number != null && number in 512..8192) {
                                        if (number != settings.imageMaxDimension) {
                                            ImageCompressionConfig.maxDimension = number
                                            vm.updateSettings(settings.copy(imageMaxDimension = number))
                                        }
                                        inputText = number.toString()
                                    } else {
                                        // 非法或为空，恢复原值，绝不强行变 512
                                        inputText = settings.imageMaxDimension.toString()
                                    }
                                }

                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = { newValue ->
                                        // 只保留数字，最多4位，输入过程中绝不自动修正
                                        inputText = newValue.filter { it.isDigit() }.take(4)
                                    },
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            confirmValue()
                                            focusManager.clearFocus()
                                        }
                                    ),
                                    modifier = Modifier
                                        .width(110.dp)
                                        .onFocusChanged { focusState ->
                                            if (!focusState.isFocused) {
                                                confirmValue()
                                            }
                                        },
                                    singleLine = true
                                )
                            }
                        )
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.reasoning_translate_title)) },
                ) {
                    // 显示方式：并入第一条（模式 A）
                    item(
                        leadingContent = { Icon(HugeIcons.Idea, null) },
                        headlineContent = { Text(stringResource(R.string.reasoning_translate_mode_a)) },
                        supportingContent = { Text(stringResource(R.string.reasoning_translate_mode_a_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.reasoningTranslateFallbackMode == ReasoningTranslateFallbackMode.FIRST,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        vm.updateSettings(settings.copy(reasoningTranslateFallbackMode = ReasoningTranslateFallbackMode.FIRST))
                                    }
                                }
                            )
                        }
                    )
                    // 显示方式：按卡片拆分（模式 B）
                    item(
                        leadingContent = { Icon(HugeIcons.Idea, null) },
                        headlineContent = { Text(stringResource(R.string.reasoning_translate_mode_b)) },
                        supportingContent = { Text(stringResource(R.string.reasoning_translate_mode_b_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.reasoningTranslateFallbackMode == ReasoningTranslateFallbackMode.EVEN,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        vm.updateSettings(settings.copy(reasoningTranslateFallbackMode = ReasoningTranslateFallbackMode.EVEN))
                                    }
                                }
                            )
                        }
                    )
                    // 发送方式：打包 / 逐条（标题 + 描述在上，选项居中在下，仿同步扫描样式）
                    item(
                        leadingContent = { Icon(HugeIcons.Idea, null) },
                        headlineContent = { Text(stringResource(R.string.reasoning_translate_send_mode)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.reasoning_translate_send_separately_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val enabled = settings.reasoningTranslateFallbackMode == ReasoningTranslateFallbackMode.EVEN
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    SegmentedButton(
                                        selected = !settings.reasoningTranslateSendSeparately,
                                        onClick = {
                                            if (enabled) vm.updateSettings(settings.copy(reasoningTranslateSendSeparately = false))
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                                        enabled = enabled,
                                    ) { Text(stringResource(R.string.reasoning_translate_send_bundled)) }
                                    SegmentedButton(
                                        selected = settings.reasoningTranslateSendSeparately,
                                        onClick = {
                                            if (enabled) vm.updateSettings(settings.copy(reasoningTranslateSendSeparately = true))
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                                        enabled = enabled,
                                    ) { Text(stringResource(R.string.reasoning_translate_send_separately)) }
                                }
                            }
                        },
                    )
                    // 分隔符：只填中间标记（输入框显示去首尾空行版，提交时自动补回）
                    item(
                        leadingContent = { Icon(HugeIcons.Idea, null) },
                        headlineContent = { Text(stringResource(R.string.reasoning_translate_separator)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.reasoning_translate_separator_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                var separatorInput by remember {
                                    mutableStateOf(settings.reasoningTranslateSeparator.trim('\n'))
                                }
                                LaunchedEffect(settings.reasoningTranslateSeparator) {
                                    if (separatorInput != settings.reasoningTranslateSeparator.trim('\n')) {
                                        separatorInput = settings.reasoningTranslateSeparator.trim('\n')
                                    }
                                }
                                fun confirmSeparator() {
                                    // 去掉首尾空行后存储，发送时由 ChatService 补 \n\n 包装
                                    val trimmed = separatorInput.trim('\n')
                                    if (trimmed != settings.reasoningTranslateSeparator.trim('\n')) {
                                        vm.updateSettings(settings.copy(reasoningTranslateSeparator = trimmed))
                                    }
                                }
                                OutlinedTextField(
                                    value = separatorInput,
                                    onValueChange = { separatorInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    keyboardActions = KeyboardActions(onDone = { confirmSeparator() }),
                                )
                                TextButton(onClick = {
                                    vm.updateSettings(
                                        settings.copy(reasoningTranslateSeparator = DEFAULT_REASONING_TRANSLATE_SEPARATOR.trim('\n'))
                                    )
                                }) {
                                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                                }
                            }
                        },
                    )
                    // 展开范围
                    item(
                        leadingContent = { Icon(HugeIcons.Idea, null) },
                        headlineContent = { Text(stringResource(R.string.reasoning_translate_expand_all)) },
                        supportingContent = { Text(stringResource(R.string.reasoning_translate_expand_all_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.reasoningTranslateExpandAll,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(settings.copy(reasoningTranslateExpandAll = checked))
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}
