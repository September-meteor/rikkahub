package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.rememberSyncedTextFieldState
import me.rerere.rikkahub.ui.components.ui.ManagedTextField
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.plus
import me.rerere.search.DoubaoSearchMode
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingSearchDetailPage(
    serviceId: Uuid,
    vm: SettingVM = koinViewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val nav = LocalNavController.current

    val service = settings.searchServices.find { it.id == serviceId } ?: return
    val serviceIndex = settings.searchServices.indexOf(service)
    var options by remember(service) { mutableStateOf(service) }

    fun save(updated: SearchServiceOptions) {
        options = updated
        val newServices = settings.searchServices.toMutableList()
        newServices[serviceIndex] = updated
        vm.updateSettings(settings.copy(searchServices = newServices))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(options.displayName)
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (settings.searchServices.size > 1) {
                        IconButton(
                            onClick = {
                                val newServices = settings.searchServices.toMutableList()
                                newServices.removeAt(serviceIndex)
                                vm.updateSettings(settings.copy(searchServices = newServices))
                                nav.popBackStack()
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("config") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .animateContentSize()
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_search_config),
                            style = MaterialTheme.typography.titleMedium
                        )

                        SearchServiceOptionsEditor(
                            options = options,
                            onUpdateOptions = { save(it) }
                        )

                        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                            SearchService.getService(options).Description()
                        }
                    }
                }
            }

            item("test") {
                SearchTestSection(
                    options = options,
                    commonOptions = settings.searchCommonOptions
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun SearchServiceOptionsEditor(
    options: SearchServiceOptions,
    onUpdateOptions: (SearchServiceOptions) -> Unit
) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> {
            TavilyOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.ExaOptions -> {
            ExaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.ZhipuOptions -> {
            ZhipuOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.DoubaoOptions -> {
            DoubaoOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.SearXNGOptions -> {
            SearXNGOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.LinkUpOptions -> {
            SearchLinkUpOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BraveOptions -> {
            BraveOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.MetasoOptions -> {
            MetasoOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.OllamaOptions -> {
            OllamaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.PerplexityOptions -> {
            PerplexityOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BingLocalOptions -> {}
        is SearchServiceOptions.FirecrawlOptions -> {
            FirecrawlOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.JinaOptions -> {
            JinaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BochaOptions -> {
            BochaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.RikkaHubOptions -> {
            RikkaHubOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.GrokOptions -> {
            GrokOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.TinyfishOptions -> {
            TinyfishOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.SerperOptions -> {
            SerperOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.CustomJsOptions -> {
            CustomJsOptions(options) { onUpdateOptions(it) }
        }
    }
}

@Composable
private fun SearchTestSection(
    options: SearchServiceOptions,
    commonOptions: SearchCommonOptions
) {
    var query by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SearchResult>?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_test),
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.setting_page_search_test_query_hint)) },
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        if (query.isNotBlank() && !testing) {
                            testing = true
                            result = null
                            scope.launch {
                                val service = SearchService.getService(options)
                                val params = JsonObject(
                                    mapOf("query" to JsonPrimitive(query))
                                )
                                result = service.search(params, commonOptions, options)
                                testing = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = stringResource(R.string.setting_page_search_test_run)
                        )
                    }
                }
            }

            result?.let { res ->
                res.onSuccess { searchResult ->
                    searchResult.answer?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    searchResult.items.forEachIndexed { index, item ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${index + 1}. ${item.title}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.text.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                res.onFailure { error ->
                    Text(
                        text = error.message ?: stringResource(R.string.search_detail_unknown_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
internal fun TavilyOptions(
    options: SearchServiceOptions.TavilyOptions,
    onUpdateOptions: (SearchServiceOptions.TavilyOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("basic", "advanced")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun ExaOptions(
    options: SearchServiceOptions.ExaOptions,
    onUpdateOptions: (SearchServiceOptions.ExaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun ZhipuOptions(
    options: SearchServiceOptions.ZhipuOptions,
    onUpdateOptions: (SearchServiceOptions.ZhipuOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun DoubaoOptions(
    options: SearchServiceOptions.DoubaoOptions,
    onUpdateOptions: (SearchServiceOptions.DoubaoOptions) -> Unit
) {
    FormItem(label = { Text(stringResource(R.string.search_detail_api_key)) }) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(label = { Text("Mode") }) {
        val modes = DoubaoSearchMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    onClick = { onUpdateOptions(options.copy(mode = mode)) },
                    selected = options.mode == mode
                ) {
                    Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                }
            }
        }
    }
}

@Composable
internal fun SearXNGOptions(
    options: SearchServiceOptions.SearXNGOptions,
    onUpdateOptions: (SearchServiceOptions.SearXNGOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_url))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.url),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(url = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_engines))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.engines),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(engines = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_language))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.language),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(language = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_username))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.username),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(username = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_password))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.password),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(password = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun SearchLinkUpOptions(
    options: SearchServiceOptions.LinkUpOptions,
    onUpdateOptions: (SearchServiceOptions.LinkUpOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun BraveOptions(
    options: SearchServiceOptions.BraveOptions,
    onUpdateOptions: (SearchServiceOptions.BraveOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun SerperOptions(
    options: SearchServiceOptions.SerperOptions,
    onUpdateOptions: (SearchServiceOptions.SerperOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun MetasoOptions(
    options: SearchServiceOptions.MetasoOptions,
    onUpdateOptions: (SearchServiceOptions.MetasoOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun OllamaOptions(
    options: SearchServiceOptions.OllamaOptions,
    onUpdateOptions: (SearchServiceOptions.OllamaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun PerplexityOptions(
    options: SearchServiceOptions.PerplexityOptions,
    onUpdateOptions: (SearchServiceOptions.PerplexityOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.maxTokens?.takeIf { it > 0 }?.toString() ?: ""),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            persistDebounceMs = 250,
            onPersist = { text -> onUpdateOptions(options.copy(maxTokens = text.trim().toIntOrNull()?.takeIf { it > 0 })) },
            normalizeOnBlur = { text -> text.trim().toIntOrNull()?.takeIf { it > 0 }?.toString() ?: "" },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens_per_page))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.maxTokensPerPage?.takeIf { it > 0 }?.toString() ?: ""),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            persistDebounceMs = 250,
            onPersist = { text -> onUpdateOptions(options.copy(maxTokensPerPage = text.trim().toIntOrNull()?.takeIf { it > 0 })) },
            normalizeOnBlur = { text -> text.trim().toIntOrNull()?.takeIf { it > 0 }?.toString() ?: "" },
        )
    }
}

@Composable
internal fun FirecrawlOptions(
    options: SearchServiceOptions.FirecrawlOptions,
    onUpdateOptions: (SearchServiceOptions.FirecrawlOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun JinaOptions(
    options: SearchServiceOptions.JinaOptions,
    onUpdateOptions: (SearchServiceOptions.JinaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_url))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.searchUrl),
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://s.jina.ai/")
            },
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(searchUrl = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_url))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.scrapeUrl),
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://r.jina.ai/")
            },
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(scrapeUrl = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun BochaOptions(
    options: SearchServiceOptions.BochaOptions,
    onUpdateOptions: (SearchServiceOptions.BochaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_summary))
        },
        description = {
            Text(stringResource(R.string.search_detail_summary_desc))
        },
        tail = {
            Switch(
                checked = options.summary,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(summary = checked))
                }
            )
        }
    )
}

@Composable
internal fun RikkaHubOptions(
    options: SearchServiceOptions.RikkaHubOptions,
    onUpdateOptions: (SearchServiceOptions.RikkaHubOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun TinyfishOptions(
    options: SearchServiceOptions.TinyfishOptions,
    onUpdateOptions: (SearchServiceOptions.TinyfishOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun GrokOptions(
    options: SearchServiceOptions.GrokOptions,
    onUpdateOptions: (SearchServiceOptions.GrokOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.apiKey),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(apiKey = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_model))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.model),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(model = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_custom_url))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.customUrl),
            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(customUrl = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_system_prompt))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.systemPrompt),

            modifier = Modifier.fillMaxWidth(),
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(systemPrompt = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }
}

@Composable
internal fun CustomJsOptions(
    options: SearchServiceOptions.CustomJsOptions,
    onUpdateOptions: (SearchServiceOptions.CustomJsOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_name))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.name),
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_detail_custom_search_placeholder)) },
            persistDebounceMs = 250,
            onPersist = { onUpdateOptions(options.copy(name = it)) },
            normalizeOnBlur = { it.trim() },
        )
    }


    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_script))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.searchScript),
            modifier = Modifier.fillMaxWidth(),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 8, maxHeightInLines = 20),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
            persistDebounceMs = 250,
            onPersist = {
                onUpdateOptions(options.copy(searchScript = it))
            },
            normalizeOnBlur = { it.trim() },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_script))
        },
        description = {
            Text(stringResource(R.string.search_detail_scrape_script_desc))
        }
    ) {
        ManagedTextField(
            state = rememberSyncedTextFieldState(options.scrapeScript),
            modifier = Modifier.fillMaxWidth(),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 20),
            placeholder = {
                Text(
                    text = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT.trimIndent(),
                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
            persistDebounceMs = 250,
            onPersist = {
                onUpdateOptions(options.copy(scrapeScript = it))
            },
            normalizeOnBlur = { it.trim() },
        )
    }
}
