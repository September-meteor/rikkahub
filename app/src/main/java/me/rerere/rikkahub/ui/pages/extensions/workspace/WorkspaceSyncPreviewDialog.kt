package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.SyncPreviewItem
import me.rerere.rikkahub.data.sync.SyncPreviewType

private val CreateColor = Color(0xFF2E7D32)
private val ModifyColor = Color(0xFFF9A825)
private val DeleteColor = Color(0xFFC62828)

/**
 * 「导回原处」同步弹窗：同一个弹窗内按状态机切换内容（不关窗再开）。
 * - [SyncPhase.SCANNING]：标题「正在扫描变更…」，进度条 + 文字，底部仅「取消」
 * - [SyncPhase.PREVIEW]：顶部统计 + 三组彩色列表 + 「确认同步」/「取消」；无变更时确认置灰
 * - [SyncPhase.EXECUTING]：标题「正在同步…」，进度条 + 文字，无按钮（不可中途取消）
 */
@Composable
fun WorkspaceSyncDialog(
    phase: SyncPhase,
    preview: List<SyncPreviewItem>?,
    progress: SyncProgress?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (phase != SyncPhase.EXECUTING) onCancel() },
        title = {
            Text(
                stringResource(
                    when (phase) {
                        SyncPhase.SCANNING -> R.string.workspace_detail_sync_scanning_title
                        SyncPhase.EXECUTING -> R.string.workspace_detail_sync_executing_title
                        else -> R.string.workspace_detail_sync_preview_title
                    }
                )
            )
        },
        text = {
            when (phase) {
                SyncPhase.SCANNING -> SyncScanningContent(progress)
                SyncPhase.EXECUTING -> SyncExecutingContent(progress)
                else -> SyncPreviewContent(preview.orEmpty())
            }
        },
        confirmButton = {
            if (phase == SyncPhase.PREVIEW) {
                TextButton(
                    onClick = onConfirm,
                    enabled = preview?.isNotEmpty() == true,
                ) {
                    Text(stringResource(R.string.workspace_detail_sync_confirm))
                }
            }
        },
        dismissButton = {
            if (phase == SyncPhase.SCANNING || phase == SyncPhase.PREVIEW) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

/** 检测中内容：进度文字 + 横向长条进度条（总数未知时不确定循环） */
@Composable
private fun SyncScanningContent(progress: SyncProgress?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val text = when {
            progress == null -> stringResource(R.string.workspace_detail_sync_scanning_files, 0)
            progress.stage == SyncProgressStage.HASH && progress.total > 0 ->
                stringResource(
                    R.string.workspace_detail_sync_checking_content,
                    progress.done,
                    progress.total,
                )
            progress.total > 0 ->
                stringResource(
                    R.string.workspace_detail_sync_scanning_files_of,
                    progress.done,
                    progress.total,
                )
            else -> stringResource(R.string.workspace_detail_sync_scanning_files, progress.done)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (progress != null && progress.stage == SyncProgressStage.HASH && progress.total > 0) {
            LinearProgressIndicator(
                progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 执行中内容：进度文字 + 横向长条进度条 */
@Composable
private fun SyncExecutingContent(progress: SyncProgress?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val (done, total) = progress?.let { it.done to it.total } ?: (0 to 0)
        Text(
            text = stringResource(R.string.workspace_detail_sync_progress, done, total),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 结果确认内容：顶部统计 + 按类型分组的三色列表；空预览显示「没有需要同步的变更」 */
@Composable
private fun SyncPreviewContent(preview: List<SyncPreviewItem>) {
    val createCount = preview.count {
        it.type == SyncPreviewType.CREATE || it.type == SyncPreviewType.CREATE_DIR
    }
    val modifyCount = preview.count { it.type == SyncPreviewType.MODIFY }
    val deleteCount = preview.count {
        it.type == SyncPreviewType.DELETE || it.type == SyncPreviewType.DELETE_DIR
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(
                R.string.workspace_detail_sync_stats,
                createCount,
                modifyCount,
                deleteCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (preview.isEmpty()) {
            Text(
                text = stringResource(R.string.workspace_detail_sync_no_changes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (createCount > 0) {
                    item(key = "header_create") {
                        SyncSectionHeader(
                            label = stringResource(R.string.workspace_detail_sync_section_new),
                            color = CreateColor,
                        )
                    }
                    items(
                        preview.filter {
                            it.type == SyncPreviewType.CREATE || it.type == SyncPreviewType.CREATE_DIR
                        },
                        key = { "${it.type.name}:${it.path}" },
                    ) { item ->
                        SyncPreviewRow(item, CreateColor)
                    }
                }
                if (modifyCount > 0) {
                    item(key = "header_modify") {
                        SyncSectionHeader(
                            label = stringResource(R.string.workspace_detail_sync_section_modified),
                            color = ModifyColor,
                        )
                    }
                    items(
                        preview.filter { it.type == SyncPreviewType.MODIFY },
                        key = { it.path },
                    ) { item ->
                        SyncPreviewRow(item, ModifyColor)
                    }
                }
                if (deleteCount > 0) {
                    item(key = "header_delete") {
                        SyncSectionHeader(
                            label = stringResource(R.string.workspace_detail_sync_section_deleted),
                            color = DeleteColor,
                        )
                    }
                    items(
                        preview.filter {
                            it.type == SyncPreviewType.DELETE || it.type == SyncPreviewType.DELETE_DIR
                        },
                        key = { "${it.type.name}:${it.path}" },
                    ) { item ->
                        SyncPreviewRow(item, DeleteColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncSectionHeader(
    label: String,
    color: Color,
) {
    Text(
        text = label,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

@Composable
private fun SyncPreviewRow(
    item: SyncPreviewItem,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = if (item.type == SyncPreviewType.CREATE_DIR || item.type == SyncPreviewType.DELETE_DIR) {
                "${item.path}/"
            } else {
                item.path
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.sizeHint.isNotBlank()) {
            Text(
                text = item.sizeHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
