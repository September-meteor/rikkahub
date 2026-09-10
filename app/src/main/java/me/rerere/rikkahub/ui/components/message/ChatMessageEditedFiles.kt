package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File

private const val DEFAULT_VISIBLE_COUNT = 3

/** 提取消息中被修改的工作区文件路径（write/edit 工具入参 + 消息级变更 metadata） */
internal fun extractEditedFilesPaths(parts: List<UIMessagePart>): List<String> =
    // 消息级 metadata：生成结束后回填到助手回复消息上的 workspaceChanges
    parts.filterIsInstance<UIMessagePart.Text>()
        .flatMap { part ->
            part.metadata?.get("workspaceChanges")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        } +
        // 工具级：write/edit 工具入参 + shell 工具输出 metadata
        parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isExecuted }
            .flatMap { tool ->
                when (tool.toolName) {
                    "workspace_write_file", "workspace_edit_file" -> {
                        // 兼容入参非 JSON 对象的情况（上游 dee88dca 防崩溃写法）
                        (tool.inputAsJson() as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull
                            ?.let { listOf(it) }
                            ?: emptyList()
                    }
                    "workspace_shell" -> {
                        tool.output.filterIsInstance<UIMessagePart.Text>()
                            .firstOrNull()
                            ?.metadata
                            ?.get("workspaceChanges")
                            ?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?: emptyList()
                    }
                    else -> emptyList()
                }
            }
            .filter { it.startsWith("/workspace") }
            .distinct()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditedFilesList(
    editedFiles: List<String>,
    assistant: Assistant?,
    showFullPath: Boolean = false,
) {
    val workspaceId = assistant?.workspaceId?.toString() ?: return
    if (editedFiles.isEmpty()) return

    // 显示路径：去掉 /workspace/，若当前消息所有文件的第二段目录一致则进一步去掉项目名段
    val displayPaths = remember(editedFiles) {
        // 1. 统一标准化相对路径：兼容 "/workspace" 和 "/workspace/"，确保不会残留前缀
        val relPaths = editedFiles.map { 
            it.removePrefix("/workspace").removePrefix("/") 
        }
        
        // 2. 提取项目段（第一段目录）
        val projectSegments = relPaths.map { it.substringBefore('/', "") }
        
        // 3. 判断是否所有文件都在同一个非空项目下，且都有子路径（包含 '/'）
        val firstSegment = projectSegments.firstOrNull()
        val allSameProject = firstSegment?.isNotEmpty() == true && 
                             projectSegments.all { it == firstSegment } &&
                             relPaths.all { it.contains('/') }
    
        editedFiles.mapIndexed { index, path ->
            val rel = relPaths[index]
            // 4. 核心防御：如果 rel 为空（即路径就是 workspace 本身），兜底显示 "workspace"
            val safeRel = rel.ifEmpty { "workspace" } 
            
            val display = if (allSameProject) {
                // 裁剪项目名后，如果变成空字符串（例如路径刚好是项目目录本身），则回退到 safeRel
                safeRel.substringAfter('/', "").ifEmpty { safeRel }
            } else {
                safeRel
            }
            path to display
        }.toMap()
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val workspaceRepository: WorkspaceRepository = koinInject()

    var selectedPath by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val visibleFiles = if (expanded) editedFiles else editedFiles.take(DEFAULT_VISIBLE_COUNT)
    val hasMore = editedFiles.size > DEFAULT_VISIBLE_COUNT

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val path = selectedPath.also { selectedPath = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val (area, relativePath) = resolveWorkspacePath(path)
                outputStream.use { output ->
                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                }
            }
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        maxItemsInEachRow = if (showFullPath) 1 else Int.MAX_VALUE,
    ) {
        visibleFiles.forEach { path ->
            val displayText = if (showFullPath) {
                displayPaths.getValue(path)
            } else {
                path.substringAfterLast('/')
            }
            // 1. Surface 移除 onClick 和 onLongClick（点击/长按统一由 Row 的 combinedClickable 处理）
            //    modifier 不加 fillMaxWidth：药丸 wrap-content，最长不超过父宽（超长由 horizontalScroll 滚动）
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                // 2. 在 Row 的 modifier 末尾添加 combinedClickable
                Row(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .then(
                            if (showFullPath) {
                                Modifier.horizontalScroll(rememberScrollState())
                            } else {
                                Modifier
                            }
                        )
                        .combinedClickable( // <--- 核心修复：统一处理点击和长按
                            onClick = { selectedPath = path },
                            onLongClick = {
                                clipboardManager.setText(AnnotatedString(displayPaths.getValue(path)))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.chat_message_copied_path),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = !showFullPath,
                        overflow = if (showFullPath) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (showFullPath) Modifier else Modifier.widthIn(max = 200.dp),
                    )
                }
            }
        }
        if (hasMore) {
            if (!expanded) {
                Surface(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "+${editedFiles.size - DEFAULT_VISIBLE_COUNT}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            } else {
                Surface(
                    onClick = { expanded = false },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "<",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    if (selectedPath != null) {
        val path = selectedPath!!
        val fileName = path.substringAfterLast('/') // 导出用真实文件名
        val sheetTitle = if (showFullPath) displayPaths.getValue(path) else fileName
        ModalBottomSheet(
            onDismissRequest = { selectedPath = null },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = sheetTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        exportLauncher.launch(p.substringAfterLast('/'))
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileImport,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_export),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        selectedPath = null
                        scope.launch {
                            runCatching {
                                val (area, relativePath) = resolveWorkspacePath(p)
                                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                val file = File(dir, p.substringAfterLast('/'))
                                file.outputStream().use { output ->
                                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                                }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Share08,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_share),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun resolveWorkspacePath(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}
