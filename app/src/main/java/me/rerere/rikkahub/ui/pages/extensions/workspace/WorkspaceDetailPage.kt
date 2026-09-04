package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.sync.SyncCheckMode
import me.rerere.rikkahub.data.sync.ImportConflictMode
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.ManagedTextField
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.material3.CircularProgressIndicator

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val toaster = LocalToaster.current

    // 目录导出结果提示（成功/失败一次性 toast）
    LaunchedEffect(state.dirExportNotice) {
        state.dirExportNotice?.let { msg ->
            toaster.show(
                msg,
                type = if (state.dirExportNoticeError) ToastType.Error else ToastType.Success,
            )
            vm.clearDirExportNotice()
        }
    }

    // 新增：目录选择器（用于导入整个目录）
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val ws = state.workspace ?: return@rememberLauncherForActivityResult
        vm.importDirectory(
            context = context,
            treeUri = treeUri,
            enableGitignore = ws.enableGitignore,
            customIgnorePatterns = ws.customIgnorePatterns,
        )
    }

    // 「导回原处」：原目录权限失效后重新选择目录
    val syncSourcePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        state.syncSourceLostFor?.let { vm.onSyncSourcePicked(context, treeUri, it) }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        vm.importFile(context, uri, fileName)
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }

    // 目录导出：选好目标文件夹后直接开始复制，不再弹多余确认
    var exportDirTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val entry = exportDirTarget.also { exportDirTarget = null } ?: return@rememberLauncherForActivityResult
        if (treeUri == null) return@rememberLauncherForActivityResult
        vm.exportDirectory(context, entry, treeUri)
    }

    BackHandler(enabled = pagerState.currentPage == 1 && state.path.isNotBlank()) {
        vm.goUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == 1) {
                        // 目录导入按钮（带进度圈）
                        val importProgress = state.importProgress
                        Box(contentAlignment = Alignment.Center) {
                            if (importProgress != null) {
                                val (processed, total) = importProgress
                                if (total < 0) {
                                    // 扫描阶段：不确定进度
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(40.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    val progress = if (total > 0) processed.toFloat() / total else 0f
                                    CircularProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.size(40.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { directoryPicker.launch(null) },
                                enabled = importProgress == null,
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Folder01,
                                    contentDescription = stringResource(R.string.workspace_detail_import_directory),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                
                        IconButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            enabled = importProgress == null, // 导入中禁用
                        ) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    onToolApprovalChange = vm::setToolApproval,
                    onEnableGitignoreChange = vm::setEnableGitignore,
                    onCustomIgnoreChange = vm::setCustomIgnorePatterns,
                    customIgnoreState = vm.customIgnoreState,
                    onSyncCheckModeChange = vm::setSyncCheckMode,
                    onConflictModeChange = vm::setImportConflictMode,
                )

                1 -> WorkspaceFilesPage(
                    state = state,
                    contentPadding = PaddingValues(),
                    onSelectArea = vm::selectArea,
                    onGoUp = vm::goUp,
                    onSyncToSource = { root, scope, scopeIsFile ->
                        vm.prepareSyncPreview(context, root, scope, scopeIsFile)
                    },
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.open(entry)

                            else -> when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                                WorkspaceFileType.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                                    // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                                    previewImageUri = file.absolutePath
                                }

                                WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        file.extension.lowercase()
                                    ) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, null))
                                    }
                                }
                            }
                        }
                    },
                    onDelete = { deleteTarget = it },
                    onExport = { entry ->
                        exportTarget = entry
                        exportLauncher.launch(entry.name)
                    },
                    onExportDir = { entry ->
                        exportDirTarget = entry
                        exportDirLauncher.launch(null)
                    },
                    onShare = { entry ->
                        vm.exportToCacheFile(entry, context.cacheDir) { file ->
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
                    },
                )
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }

    // 目录导出：执行中弹窗（与「同步回原目录」执行阶段一致的进度样式）
    if (state.dirExporting) {
        WorkspaceDirExportProgressDialog(progress = state.exportProgress)
    }

    // 「导回原处」：同步弹窗（检测中 → 结果确认 → 执行中，同一弹窗内按状态机切换）
    when (state.syncPhase) {
        SyncPhase.SCANNING, SyncPhase.PREVIEW, SyncPhase.EXECUTING -> {
            WorkspaceSyncDialog(
                phase = state.syncPhase,
                preview = state.syncPreview,
                progress = state.syncProgress,
                onConfirm = {
                    state.activeSyncRoot?.let { vm.confirmSync(context, it, state.activeSyncScope) }
                },
                onCancel = vm::cancelSync,
            )
        }

        else -> Unit
    }

    // 「导回原处」：原始目录权限失效，引导重新选择
    if (state.syncSourceLostFor != null) {
        AlertDialog(
            onDismissRequest = vm::dismissSyncSourceLost,
            title = { Text(stringResource(R.string.workspace_detail_sync_source_lost_title)) },
            text = { Text(stringResource(R.string.workspace_detail_sync_source_lost)) },
            confirmButton = {
                TextButton(onClick = { syncSourcePicker.launch(null) }) {
                    Text(stringResource(R.string.workspace_detail_sync_reselect))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSyncSourceLost) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // 「导回原处」：同步失败
    state.syncError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearSyncError,
            title = { Text(stringResource(R.string.workspace_detail_sync_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::clearSyncError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    // 覆盖导入预览：展示即将执行的新增/更新/删除，等待确认
    state.importPreview?.let { preview ->
        WorkspaceImportPreviewDialog(
            preview = preview,
            onConfirm = { vm.confirmImportPreview(context) },
            onCancel = vm::cancelImportPreview,
        )
    }

    // 导入类型冲突（文件 vs 目录同名）：替换/取消
    state.importTypeConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = vm::cancelImportReplace,
            title = { Text(stringResource(R.string.workspace_detail_import_type_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        if (conflict.importingDirectory) {
                            R.string.workspace_detail_import_type_conflict_dir
                        } else {
                            R.string.workspace_detail_import_type_conflict_file
                        },
                        conflict.name,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmImportReplace(context) }) {
                    Text(stringResource(R.string.workspace_detail_import_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelImportReplace) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // 导入失败弹窗：任何导入流程异常都会弹出，不让错误被静默吞掉
    state.importError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissImportError,
            title = { Text(stringResource(R.string.workspace_detail_import_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissImportError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onEnableGitignoreChange: (Boolean) -> Unit,
    onCustomIgnoreChange: (String) -> Unit,
    customIgnoreState: TextFieldState,
    onSyncCheckModeChange: (SyncCheckMode) -> Unit,
    onConflictModeChange: (ImportConflictMode) -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Bash, contentDescription = null)
                        Text(
                            text = installButtonText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }
                }
            }
        }

        item {
            WorkspaceImportSettingsCard(
                workspace = workspace,
                onEnableGitignoreChange = onEnableGitignoreChange,
                onCustomIgnoreChange = onCustomIgnoreChange,
                customIgnoreState = customIgnoreState,
                onSyncCheckModeChange = onSyncCheckModeChange,
                onConflictModeChange = onConflictModeChange,
            )
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
)

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onSyncToSource: (syncRoot: String, scope: String, scopeIsFile: Boolean) -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
    onExportDir: (WorkspaceFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            WorkspaceAreaSelector(
                selected = state.area,
                onSelected = onSelectArea,
            )
        }

        item {
            WorkspacePathBar(
                path = state.path,
                canGoUp = state.path.isNotBlank(),
                onGoUp = onGoUp,
            )
        }

        state.error?.let { error ->
            item {
                ErrorCard(error)
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        items(state.entries, key = { "${state.area.name}:${it.path}" }) { entry ->
            val syncScope = syncScopeOf(state, entry)
            WorkspaceFileCard(
                entry = entry,
                onOpen = { onOpen(entry) },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
                onExportDir = if (entry.isDirectory && state.area == WorkspaceStorageArea.FILES) {
                    { onExportDir(entry) }
                } else {
                    null
                },
                onSyncToSource = if (syncScope != null) {
                    {
                        onSyncToSource(syncScope.first, syncScope.second, syncScope.third)
                    }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * 判断条目是否属于某个已注册同步来源的导入根目录。
 * 返回 (syncRoot, scope, scopeIsFile)：
 * - 顶层导入根目录自身：scope 为空串（走整目录完整同步）
 * - 根目录内的子目录/文件：scope = 相对根目录的路径（走子范围同步）
 */
private fun syncScopeOf(
    state: WorkspaceDetailState,
    entry: WorkspaceFileEntry,
): Triple<String, String, Boolean>? {
    if (state.area != WorkspaceStorageArea.FILES) return null
    val top = entry.path.substringBefore('/')
    if (top.isBlank() || top !in state.syncRoots) return null
    val scope = entry.path.removePrefix(top).removePrefix("/")
    return Triple(top, scope, !entry.isDirectory)
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onExportDir: (() -> Unit)? = null,
    onSyncToSource: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = {
                    menuExpanded = true
                }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_export)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.FileImport,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Share08,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                    } else if (onExportDir != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_export)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.FileImport,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onExportDir()
                            },
                        )
                    }
                    if (onSyncToSource != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_detail_sync_to_source)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.ArrowTurnBackward,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onSyncToSource()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceDirExportProgressDialog(progress: ExportProgress?) {
    val p = progress
    val total = p?.total ?: 0
    val done = p?.done ?: 0
    AlertDialog(
        onDismissRequest = { /* 导出中不可取消 */ },
        title = { Text(stringResource(R.string.workspace_dir_export_progress)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "$done / $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}

@Composable
private fun WorkspaceImportSettingsCard(
    workspace: WorkspaceEntity?,
    onEnableGitignoreChange: (Boolean) -> Unit,
    onCustomIgnoreChange: (String) -> Unit,
    customIgnoreState: TextFieldState,
    onSyncCheckModeChange: (SyncCheckMode) -> Unit,
    onConflictModeChange: (ImportConflictMode) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_transfer_settings),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_transfer_settings_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // .gitignore 开关（通用：导入 / 导出 / 同步都遵循）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_gitignore),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_gitignore_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = workspace?.enableGitignore != false,
                    onCheckedChange = onEnableGitignoreChange,
                    enabled = workspace != null,
                )
            }

            // 自定义排除模式输入框（防抖持久化，光标稳定）
            ManagedTextField(
                state = customIgnoreState,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workspace_detail_custom_ignore)) },
                placeholder = { Text(stringResource(R.string.workspace_detail_custom_ignore_hint)) },
                enabled = workspace != null,
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 4),
                persistDebounceMs = 400,
                onPersist = onCustomIgnoreChange,
            )

            // ---- 仅导入 ----
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_section_import_only),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_import_conflict_mode),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val conflictMode = ImportConflictMode.from(workspace?.importConflictMode)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(
                        ImportConflictMode.RENAME to stringResource(R.string.workspace_detail_import_conflict_copy),
                        ImportConflictMode.OVERWRITE to stringResource(R.string.workspace_detail_import_conflict_overwrite),
                    )
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = conflictMode == mode,
                            onClick = { onConflictModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            enabled = workspace != null,
                        ) {
                            Text(label)
                        }
                    }
                }
                Text(
                    text = stringResource(
                        if (conflictMode == ImportConflictMode.RENAME) {
                            R.string.workspace_detail_import_conflict_copy_desc
                        } else {
                            R.string.workspace_detail_import_conflict_overwrite_desc
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 仅同步回原目录 ----
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_section_sync_back_only),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_sync_check_mode),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val selectedMode = SyncCheckMode.from(workspace?.syncCheckMode)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(
                        SyncCheckMode.FAST to stringResource(R.string.workspace_detail_sync_check_mode_fast),
                        SyncCheckMode.ACCURATE to stringResource(R.string.workspace_detail_sync_check_mode_accurate),
                    )
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = selectedMode == mode,
                            onClick = { onSyncCheckModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            enabled = workspace != null,
                        ) {
                            Text(label)
                        }
                    }
                }
                Text(
                    text = stringResource(
                        if (selectedMode == SyncCheckMode.FAST) {
                            R.string.workspace_detail_sync_check_mode_fast_desc
                        } else {
                            R.string.workspace_detail_sync_check_mode_accurate_desc
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"