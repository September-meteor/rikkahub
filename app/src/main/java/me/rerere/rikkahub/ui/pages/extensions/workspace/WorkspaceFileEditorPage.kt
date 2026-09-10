package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.BinaryPreviewException
import me.rerere.rikkahub.data.repository.OversizePreviewException
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File

/**
 * 工作区文本文件编辑/预览页.
 *
 * 可编辑性按路径判定（[WorkspaceRepository.canEditText]）：
 * - FILES 区：可编辑并保存；
 * - LINUX (rootfs) 区：除根/各挂载点本身与内核伪文件系统（/dev、/proc、/sys）外，
 *   一律放行编辑——含 /workspace、bind mount 内与普通 rootfs 文件，保存时按绝对路径解析写回。
 * 尽力而为：二进制 / 超大等无法以文本方式打开时，提供「用其它应用打开 / 分享」兜底，
 * 不再把这类文件静默交给系统应用。
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val supportsPreview = extension in setOf("html", "htm", "svg")
    var showPreview by rememberSaveable(id, area, path) { mutableStateOf(supportsPreview) }

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 可编辑性取决于路径解析（LINUX 区大部分路径放行），异步判定后驱动 Save 按钮与 readOnly
    var editable by remember { mutableStateOf(false) }

    LaunchedEffect(id, area, path) {
        loading = true
        loadError = null
        editable = repository.canEditText(id, area, path)
        runCatching {
            repository.readTextForPreview(id, area, path)
        }.onSuccess { content ->
            textState.setTextAndPlaceCursorAtEnd(content)
            loading = false
        }.onFailure { error ->
            loadError = when (error) {
                is OversizePreviewException ->
                    context.getString(R.string.workspace_file_editor_too_large, error.sizeBytes.fileSizeToString())
                is BinaryPreviewException ->
                    context.getString(R.string.workspace_file_editor_binary_not_text)
                else -> error.message ?: context.getString(R.string.workspace_file_editor_read_failed)
            }
            loading = false
        }
    }

    /** 把文件导出到 cache 并交给系统应用：ACTION_VIEW（打开）或 ACTION_SEND（分享） */
    fun handOverToSystemApp(share: Boolean) {
        scope.launch {
            runCatching {
                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, fileName)
                file.outputStream().use { output ->
                    repository.exportFile(id, area, path, output)
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                val intent = if (share) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                context.startActivity(Intent.createChooser(intent, null))
            }.onFailure { error ->
                toaster.show(error.message ?: context.getString(R.string.workspace_file_editor_open_failed), type = ToastType.Error)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (supportsPreview && !loading && loadError == null) {
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Text(if (showPreview) "源码" else "预览")
                        }
                    }
                    if (editable && !loading && loadError == null) {
                        TextButton(
                            onClick = {
                                if (saving) return@TextButton
                                saving = true
                                scope.launch {
                                    runCatching {
                                        repository.writeText(
                                            id = id,
                                            area = area,
                                            path = path,
                                            text = textState.text.toString(),
                                            overwrite = true,
                                        )
                                    }.onSuccess {
                                        toaster.show("已保存", type = ToastType.Success)
                                    }.onFailure {
                                        toaster.show(it.message ?: "保存失败", type = ToastType.Error)
                                    }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                        ) {
                            Text("Save")
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { handOverToSystemApp(share = false) }) {
                        Text(stringResource(R.string.workspace_file_editor_open_external))
                    }
                    TextButton(onClick = { handOverToSystemApp(share = true) }) {
                        Text(stringResource(R.string.common_share))
                    }
                }
            }

            showPreview -> WorkspaceWebPreview(
                content = textState.text.toString(),
                isSvg = extension == "svg",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )

            else -> TextField(
                state = textState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                readOnly = !editable,
                lineLimits = TextFieldLineLimits.MultiLine(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = JetbrainsMono,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

@Composable
private fun WorkspaceWebPreview(
    content: String,
    isSvg: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = content,
        baseUrl = "https://workspace-preview.invalid/",
        mimeType = if (isSvg) "image/svg+xml" else "text/html",
        settings = {
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )
    WebView(state = state, modifier = modifier)
}
