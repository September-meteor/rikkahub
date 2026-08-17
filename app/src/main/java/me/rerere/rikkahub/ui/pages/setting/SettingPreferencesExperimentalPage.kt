package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.rikkahub.R
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
        }
    }
}
