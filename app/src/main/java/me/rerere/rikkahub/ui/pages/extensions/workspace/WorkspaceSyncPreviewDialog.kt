package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.Locale
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

/** 检测中内容：进度文字 + 横向长条进度条（外部扫描有估计总数 → 确定进度并显示 ETA） */
@Composable
private fun SyncScanningContent(progress: SyncProgress?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val text = when {
            progress == null -> stringResource(R.string.workspace_detail_sync_scanning_files, 0)
            progress.stage == SyncProgressStage.SCAN_EXTERNAL && progress.total > 0 ->
                scanningExternalText(progress)
            progress.stage == SyncProgressStage.HASH && progress.total > 0 ->
                stringResource(
                    R.string.workspace_detail_sync_checking_content,
                    progress.done,
                    progress.total,
                )
            // 内部扫描，或外部扫描但总数未知（无快照）：保持不确定文案
            else -> stringResource(R.string.workspace_detail_sync_scanning_files, progress.done)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
        val determinate = progress != null && progress.total > 0 && (
            progress.stage == SyncProgressStage.SCAN_EXTERNAL ||
                progress.stage == SyncProgressStage.HASH
            )
        if (determinate) {
            val ratio = progress.done.toFloat() / progress.total
            // 外部扫描的总数是估计值：封顶 99%，避免总数修正时从 100% 回落造成「已完成又退回」观感
            val clamped = if (progress.stage == SyncProgressStage.SCAN_EXTERNAL) {
                ratio.coerceIn(0f, 0.99f)
            } else {
                ratio.coerceIn(0f, 1f)
            }
            LinearProgressIndicator(
                progress = { clamped },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 外部扫描文案：「文件已扫描 xx/xxx（预估/更新 m:ss）」；剩余 < 5 显示「即将完成」；无法估算时无括号 */
@Composable
private fun scanningExternalText(progress: SyncProgress): String {
    val done = progress.done
    val total = progress.total
    val remaining = total - done
    val etaText = when {
        // 快完成（剩余 1..4 个文件）：显示「即将完成」，不依赖速率估计
        remaining in 1..4 -> stringResource(R.string.workspace_detail_sync_scanning_almost_done)
        progress.etaSeconds != null -> formatEta(progress.etaSeconds)
        // 刚起步（done < 5）或估计滞后（剩余 ≤ 0）：隐藏 ETA
        else -> null
    }
    return if (etaText != null) {
        if (progress.totalEstimated) {
            stringResource(R.string.workspace_detail_sync_scanning_files_eta, done, total, etaText)
        } else {
            stringResource(R.string.workspace_detail_sync_scanning_files_eta_updated, done, total, etaText)
        }
    } else {
        stringResource(R.string.workspace_detail_sync_scanning_files_no_eta, done, total)
    }
}

/** 秒 → "m:ss"；超过 59:59 显示上限 */
private fun formatEta(seconds: Long): String {
    if (seconds > 59 * 60 + 59) return ">59:59"
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
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

/**
 * 预览行：长路径智能换行（方案 A：Row 布局）。
 * - 路径按 "/" 拆段贪心装行，每行开头必为完整路径段；续行自动从 Text 左边缘开始
 *   （= 图标之后），与首行路径起点天然对齐，无需补空格
 * - 类型圆点锚定首行：`align(Top)` + 按「实际换行文本第一行行盒中心」计算的内边距，
 *   圆点与第一行文字垂直居中——精确复刻原始单行布局 CenterVertically 的效果；
 *   大小列 `align(Bottom)` 与最后一行垂直对齐
 * - path 可用宽度 = 行宽 − 图标 − 间距 − 大小实测宽度
 * - 超长单段（无 "/" 可断且超宽）：单独成行并省略号截断（保留目录尾部 "/"）
 */
@Composable
private fun SyncPreviewRow(
    item: SyncPreviewItem,
    color: Color,
) {
    val isDir = item.type == SyncPreviewType.CREATE_DIR || item.type == SyncPreviewType.DELETE_DIR
    val path = if (isDir) "${item.path}/" else item.path
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        val iconSize = 8.dp
        val spacing = 8.dp
        val measurer = rememberTextMeasurer()

        // 右侧 size 列实测宽度（px）；无大小（DELETE 等）时为 0
        val sizeWidthPx = if (item.sizeHint.isNotBlank()) {
            measureTextWidth(measurer, item.sizeHint, style, density)
        } else 0f

        // path 可用宽度 = 总宽 − 图标 − 两处间距 − size
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val pathMaxWidthPx = (totalWidthPx
            - with(density) { iconSize.toPx() }
            - with(density) { spacing.toPx() } * 2f
            - sizeWidthPx).coerceAtLeast(0f)

        // 换行结果与测量输入绑定缓存，避免 LazyColumn 滚动时反复测量
        val wrapped = remember(path, pathMaxWidthPx, style.fontSize, density.fontScale) {
            wrapPathSmart(path, pathMaxWidthPx, measurer, style, density)
        }

        // 圆点中心对齐「第一行小写字母中心」（x-height 中心），而非行盒中心
        val layoutResult = remember(wrapped, style, density) {
            measurer.measure(AnnotatedString(wrapped), style = style, density = density)
        }
        val iconTopPadPx = remember(layoutResult, style, density) {
            val baseline = layoutResult.getLineBaseline(0)

            // 用 TextPaint 实测 "x" 的 bounds，精确定位 x-height 中线
            val paint = android.text.TextPaint().apply {
                textSize = with(density) { style.fontSize.toPx() }
                // 如果你有自定义 fontFamily，建议在这里同步 typeface，否则默认字体已经足够准
            }
            val bounds = android.graphics.Rect()
            paint.getTextBounds("x", 0, 1, bounds)

            // bounds.centerY() 是相对于 baseline 的偏移（负值，在基线上方）
            val xHeightCenterY = baseline + bounds.exactCenterY()
            (xHeightCenterY - with(density) { iconSize.toPx() } / 2f).coerceAtLeast(0f)
        }


        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Top)
                    .padding(top = with(density) { iconTopPadPx.toDp() })
                    .size(iconSize)
                    .background(color, CircleShape),
            )
            Text(
                text = wrapped,
                modifier = Modifier.weight(1f),
                style = style,
                softWrap = false,
            )
            if (item.sizeHint.isNotBlank()) {
                Text(
                    text = item.sizeHint,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Bottom),
                )
            }
        }
    }
}

/** 文本自然宽度（px），不约束换行，用于与可用宽度比较 */
private fun measureTextWidth(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    density: Density,
): Float = measurer.measure(AnnotatedString(text), style = style, density = density).size.width.toFloat()

/**
 * 路径智能换行：按 "/" 拆段、贪心装入每行，每行开头必为完整路径段；
 * 断在 "/" 之后——除最后一行外每行行尾保留 "/"（换行不吞分隔符）；
 * 超长单段（单独一行仍超宽）用省略号截断。路径本身以 "/" 结尾时保留在最后一段。
 */
private fun wrapPathSmart(
    path: String,
    maxWidthPx: Float,
    measurer: TextMeasurer,
    style: TextStyle,
    density: Density,
): String {
    if (path.isEmpty() || maxWidthPx <= 0f) return path

    // 拆段；目录尾部 "/" 与最后一个非空段合并（如 "a/b/c/" → ["a", "b", "c/"]）。
    // 注意 split 会把尾部 "/" 变成末尾空串，必须用「最后一个非空段」判断，而不是 parts.lastIndex。
    val parts = path.split('/')
    val trailingSlash = path.endsWith("/")
    val lastNonEmpty = parts.indexOfLast { it.isNotEmpty() }
    val segments = buildList {
        for ((i, seg) in parts.withIndex()) {
            if (seg.isEmpty()) continue
            add(if (trailingSlash && i == lastNonEmpty) "$seg/" else seg)
        }
    }
    if (segments.isEmpty()) return path

    // 贪心装行：段能并入当前行则并入，否则换行。
    // 换行发生在 "段/段" 之间：被换下的行要在行尾补 "/"，因此测宽时对非末段
    // 的候选行额外加一个尾部 "/"（末段所在行是最后一行，行尾不需要 "/"），
    // 保证补上的 "/" 不会把行撑出可用宽度。
    val lines = mutableListOf<String>()
    var line = ""
    for ((idx, seg) in segments.withIndex()) {
        val candidate = if (line.isEmpty()) seg else "$line/$seg"
        val measured = if (idx == segments.lastIndex) candidate else "$candidate/"
        if (line.isEmpty() || measureTextWidth(measurer, measured, style, density) <= maxWidthPx) {
            line = candidate
        } else {
            lines += "$line/"
            line = seg
        }
    }
    if (line.isNotEmpty()) lines += line

    // 超长单段：单独成行后仍超宽 → 省略号截断
    return lines.joinToString("\n") { l ->
        if (measureTextWidth(measurer, l, style, density) > maxWidthPx) {
            ellipsizeLine(l, maxWidthPx, measurer, style, density)
        } else {
            l
        }
    }
}

/** 超长行省略号截断：保留开头 + "…"；目录段保留尾部 "/" */
private fun ellipsizeLine(
    line: String,
    maxWidthPx: Float,
    measurer: TextMeasurer,
    style: TextStyle,
    density: Density,
): String {
    val trailingSlash = line.endsWith("/")
    val body = if (trailingSlash) line.dropLast(1) else line
    val ellipsis = "…"
    val ellipsisWidth = measureTextWidth(measurer, ellipsis, style, density)
    val budget = maxWidthPx - ellipsisWidth
    if (budget <= 0f) return line

    // 二分查找能放下「前缀 + …」的最大前缀长度
    var lo = 0
    var hi = body.length
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (measureTextWidth(measurer, body.take(mid), style, density) <= budget) {
            lo = mid
        } else {
            hi = mid - 1
        }
    }
    return body.take(lo) + ellipsis + if (trailingSlash) "/" else ""
}
