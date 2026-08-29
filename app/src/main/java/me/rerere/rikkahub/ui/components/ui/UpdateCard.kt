package me.rerere.rikkahub.ui.components.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useThrottle
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.UpdateDownload
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import java.util.zip.ZipFile

private val LIB_ABI_PATTERN = Regex("^lib/([^/]+)/")

/**
 * 解析当前安装 APK 包含的原生库 ABI，用于在更新时沿用与已安装包相同的架构。
 * 专用包（如 arm64-v8a）返回单个 ABI；universal 包返回多个 ABI；解析失败返回空列表。
 */
private fun installedApkAbis(context: Context): List<String> = runCatching {
    ZipFile(context.applicationInfo.sourceDir).use { zip ->
        zip.entries().asSequence()
            .mapNotNull { entry -> LIB_ABI_PATTERN.find(entry.name)?.groupValues?.get(1) }
            .distinct()
            .toList()
    }
}.getOrDefault(emptyList())

@OptIn(ExperimentalTime::class)
@Composable
fun UpdateCard(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    state.onError {
        Card {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.update_card_check_failed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = it.message ?: stringResource(R.string.update_card_unknown_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    state.onSuccess { info ->
        var showDetail by remember { mutableStateOf(false) }
        var dismissed by remember { mutableStateOf(false) }
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(info) {
            visible = vm.updateChecker.shouldShowUpdate(info)
        }
        // 沿用当前安装 APK 的架构筛选下载项：优先推荐与已安装包相同类型的 APK，universal 兜底。
        // 专用包（如 arm64-v8a）解析出单个 ABI；universal 包解析出多个 ABI。
        val installedAbis = remember { installedApkAbis(context) }
        val prefersUniversal = installedAbis.size > 1
        val preferredAbis = installedAbis.ifEmpty { Build.SUPPORTED_ABIS.toList() }
        val downloads = remember(info) {
            if (prefersUniversal) {
                // 已装 universal 包：继续提供 universal，避免换架构
                info.downloads
                    .filter { item -> item.name.contains("universal") }
                    .ifEmpty { info.downloads }
            } else {
                // 已装专用包：优先匹配相同架构，universal 兜底
                info.downloads
                    .filter { item ->
                        item.name.contains("universal") || preferredAbis.any { abi -> item.name.contains(abi) }
                    }
                    .sortedByDescending { item -> if (item.name.contains("universal")) 1 else 2 }
                    .ifEmpty { info.downloads }
            }
        }
        if (visible && !dismissed) {
            Card(
                onClick = {
                    showDetail = true
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.update_card_new_version_found, info.version),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { dismissed = true }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.update_card_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (showDetail) {
            val downloadHandler = useThrottle<UpdateDownload>(500) { item ->
                vm.updateChecker.downloadUpdate(context, item)
                showDetail = false
                toaster.show(context.getString(R.string.update_card_downloading), type = ToastType.Info)
            }
            ModalBottomSheet(
                onDismissRequest = { showDetail = false },
                sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = info.version,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = Instant.parse(info.publishedAt).toJavaInstant().toLocalDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MarkdownBlock(
                        content = info.changelog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    downloads.fastForEach { downloadItem ->
                        val isRecommended = when {
                            prefersUniversal -> downloadItem.name.contains("universal")
                            else -> preferredAbis.any { abi -> downloadItem.name.contains(abi) }
                        }
                        OutlinedCard(
                            onClick = {
                                downloadHandler(downloadItem)
                            },
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = if (isRecommended) {
                                            stringResource(R.string.update_card_recommended_format, downloadItem.name)
                                        } else {
                                            downloadItem.name
                                        },
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = downloadItem.size
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = HugeIcons.Download01,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
