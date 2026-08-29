package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_UPDATE_MIRROR
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.UpdateSource
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

private val UPDATE_PAUSE_DAY_OPTIONS = listOf(7, 14, 21)
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1_000L

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var showUpdatePauseDialog by remember { mutableStateOf(false) }
    var selectedUpdatePauseDays by remember { mutableStateOf(UPDATE_PAUSE_DAY_OPTIONS.first()) }
    var pauseForever by remember { mutableStateOf(false) }
    var showUpdateSourceDialog by remember { mutableStateOf(false) }
    var showUpdateMirrorDialog by remember { mutableStateOf(false) }
    var mirrorDraft by remember { mutableStateOf("") }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val updateChecksEnabled =
        displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_notification))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 更新提醒：显示更新（暂停/永久）+ 实时通知更新
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_update_group_reminder)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = {
                            selectedUpdatePauseDays = UPDATE_PAUSE_DAY_OPTIONS.first()
                            pauseForever = displaySetting.updateCheckPermanentlyDisabled
                            showUpdatePauseDialog = true
                        },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                        supportingContent = {
                            Text(
                                when {
                                    displaySetting.updateCheckPermanentlyDisabled ->
                                        stringResource(R.string.setting_update_reminder_disabled_forever)
                                    updateChecksEnabled ->
                                        stringResource(R.string.setting_update_reminder_enabled)
                                    else ->
                                        stringResource(
                                            R.string.setting_update_reminder_paused_until,
                                            Instant.ofEpochMilli(displaySetting.updateCheckDisabledUntilEpochMillis)
                                                .toLocalDateTime(),
                                        )
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableLiveUpdateNotification,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                                }
                            )
                        },
                    )
                }
            }
            // 更新下载：更新源 + 下载镜像
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_update_group_download)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = { showUpdateSourceDialog = true },
                        headlineContent = { Text(stringResource(R.string.setting_update_source_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.updateSource == UpdateSource.FORK) {
                                    stringResource(R.string.setting_update_source_fork)
                                } else {
                                    stringResource(R.string.setting_update_source_official)
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        onClick = {
                            mirrorDraft = displaySetting.updateDownloadMirror
                            showUpdateMirrorDialog = true
                        },
                        headlineContent = { Text(stringResource(R.string.setting_update_mirror_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.updateDownloadMirror.isBlank()) {
                                    stringResource(R.string.setting_update_mirror_direct)
                                } else {
                                    displaySetting.updateDownloadMirror
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                }
            }
            // 消息通知：生成消息后启用通知
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_update_group_message)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    if (it && !permissionState.allPermissionsGranted) {
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                                }
                            )
                        },
                    )
                }
            }
        }
    }

    if (showUpdatePauseDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatePauseDialog = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        UPDATE_PAUSE_DAY_OPTIONS.forEachIndexed { index, days ->
                            SegmentedButton(
                                selected = !pauseForever && selectedUpdatePauseDays == days,
                                onClick = {
                                    pauseForever = false
                                    selectedUpdatePauseDays = days
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = UPDATE_PAUSE_DAY_OPTIONS.size + 1,
                                ),
                            ) {
                                Text(stringResource(R.string.setting_update_reminder_pause_days, days))
                            }
                        }
                        SegmentedButton(
                            selected = pauseForever,
                            onClick = { pauseForever = true },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = UPDATE_PAUSE_DAY_OPTIONS.size,
                                count = UPDATE_PAUSE_DAY_OPTIONS.size + 1,
                            ),
                        ) {
                            Text(stringResource(R.string.setting_update_reminder_pause_forever))
                        }
                    }
                    if (!updateChecksEnabled || displaySetting.updateCheckPermanentlyDisabled) {
                        TextButton(
                            onClick = {
                                updateDisplaySetting(
                                    displaySetting.copy(
                                        updateCheckDisabledUntilEpochMillis = 0L,
                                        updateCheckPermanentlyDisabled = false,
                                    )
                                )
                                showUpdatePauseDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_update_reminder_resume_now))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDisplaySetting(
                            displaySetting.copy(
                                updateCheckDisabledUntilEpochMillis = if (pauseForever) {
                                    0L
                                } else {
                                    System.currentTimeMillis() + selectedUpdatePauseDays * MILLIS_PER_DAY
                                },
                                updateCheckPermanentlyDisabled = pauseForever,
                            )
                        )
                        showUpdatePauseDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePauseDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showUpdateSourceDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateSourceDialog = false },
            title = { Text(stringResource(R.string.setting_update_source_title)) },
            text = {
                Column {
                    UpdateSource.entries.forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    updateDisplaySetting(displaySetting.copy(updateSource = source))
                                    showUpdateSourceDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = displaySetting.updateSource == source,
                                onClick = {
                                    updateDisplaySetting(displaySetting.copy(updateSource = source))
                                    showUpdateSourceDialog = false
                                }
                            )
                            Text(
                                text = stringResource(
                                    when (source) {
                                        UpdateSource.FORK -> R.string.setting_update_source_fork
                                        UpdateSource.OFFICIAL -> R.string.setting_update_source_official
                                    }
                                ),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUpdateSourceDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showUpdateMirrorDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateMirrorDialog = false },
            modifier = Modifier.imePadding(),
            title = { Text(stringResource(R.string.setting_update_mirror_title)) },
            text = {
                OutlinedTextField(
                    value = mirrorDraft,
                    onValueChange = { mirrorDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_update_mirror_title)) },
                    placeholder = { Text(DEFAULT_UPDATE_MIRROR) },
                    supportingText = { Text(stringResource(R.string.setting_update_mirror_desc)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDisplaySetting(
                            displaySetting.copy(updateDownloadMirror = mirrorDraft.trim())
                        )
                        showUpdateMirrorDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateMirrorDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
