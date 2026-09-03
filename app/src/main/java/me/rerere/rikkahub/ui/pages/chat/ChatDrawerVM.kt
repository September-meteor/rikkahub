package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

// 同时只缓存最近的几个（助手+分类）会话流，超出自动淘汰最久未用的
private const val MAX_CACHED_CONVERSATION_STREAMS = 6

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // 按（助手+分类）缓存的会话分页流，LRU 只保留最近几个。
    // 注意：Paging 的 Pager.flow 只能被收集一次，必须用 cachedIn 常驻共享才能反复订阅
    // （shareIn/WhileSubscribed 会重启上游导致 "collect twice" 崩溃）。
    // 因此每个条目持有自己的 CoroutineScope：LRU 淘汰或切换助手时 cancel 该 scope，
    // 才能真正停止上游查询、释放资源。
    private class CachedConversations(
        val flow: Flow<PagingData<ConversationListItem>>,
        val scope: CoroutineScope
    )

    private val conversationsCache = object :
        LinkedHashMap<String, CachedConversations>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedConversations>?
        ): Boolean {
            if (size > MAX_CACHED_CONVERSATION_STREAMS) {
                eldest?.value?.scope?.cancel()
                return true
            }
            return false
        }
    }

    private val assistantIdFlow = settingsStore.settingsFlow
        .map { it.assistantId }
        .distinctUntilChanged()

    // 当前选中的文件夹筛选，null 表示「未归类」视图
    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 指定（助手 + 分类）的会话列表分页流，按（助手+分类）缓存。
     * - 首次访问某分类时才真正从 Room 加载；
     * - 之后切回该分类时返回缓存流：LazyPagingItems 会用缓存数据直接初始化，
     *   秒开、不闪加载、不闪空态；
     * - UI 端每次进入该分类都会基于返回的流实例重建 LazyPagingItems，
     *   不同分类的实例互不复用，内部锚点不串位。
     */
    fun conversationsOf(assistantId: Uuid, folderId: Uuid?): Flow<PagingData<ConversationListItem>> {
        val cacheKey = "${assistantId}_${folderId?.toString() ?: "unfiled"}"
        conversationsCache[cacheKey]?.let { return it.flow }
        val base: Flow<PagingData<Conversation>> = if (folderId == null) {
            conversationRepo.getUnfiledConversationsOfAssistantPaging(assistantId)
        } else {
            conversationRepo.getConversationsOfFolderPaging(folderId)
        }
        val pagingFlow: Flow<PagingData<ConversationListItem>> = base.map { pagingData ->
            pagingData
                .map { ConversationListItem.Item(it) }
                .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                    when {
                        before == null && after is ConversationListItem.Item -> {
                            if (after.conversation.isPinned) {
                                ConversationListItem.PinnedHeader
                            } else {
                                val afterDate = after.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                ConversationListItem.DateHeader(
                                    date = afterDate,
                                    label = getDateLabel(afterDate)
                                )
                            }
                        }

                        before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                            if (before.conversation.isPinned && !after.conversation.isPinned) {
                                val afterDate = after.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                ConversationListItem.DateHeader(
                                    date = afterDate,
                                    label = getDateLabel(afterDate)
                                )
                            } else if (!after.conversation.isPinned) {
                                val beforeDate = before.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                val afterDate = after.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()

                                if (beforeDate != afterDate) {
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }

                        else -> null
                    }
                }
        }
        val entryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cached = pagingFlow.cachedIn(entryScope)
        conversationsCache[cacheKey] = CachedConversations(cached, entryScope)
        return cached
    }

    override fun onCleared() {
        conversationsCache.values.forEach { it.scope.cancel() }
        super.onCleared()
    }

    // 会话列表滚动位置按「助手 + 文件夹视图」分别保存（未分类用 unfiled 键）：
    // 切换助手或分类时互不串用，各自只恢复自己上次的位置
    private fun scrollKey(assistantId: Uuid, folderId: Uuid?): String =
        "${assistantId}_${folderId?.toString() ?: "unfiled"}"

    fun savedScrollIndex(assistantId: Uuid, folderId: Uuid?): Int =
        savedStateHandle["scrollIndex_${scrollKey(assistantId, folderId)}"] ?: 0

    fun savedScrollOffset(assistantId: Uuid, folderId: Uuid?): Int =
        savedStateHandle["scrollOffset_${scrollKey(assistantId, folderId)}"] ?: 0

    /** 该（助手+分类）是否曾保存过滚动位置（用于区分「从未滚过」与「滚回顶部」） */
    fun hasSavedScrollPosition(assistantId: Uuid, folderId: Uuid?): Boolean =
        savedStateHandle.contains("scrollIndex_${scrollKey(assistantId, folderId)}")

    init {
        // 助手切换时恢复该助手记忆中的文件夹筛选（文件夹是助手内分组），
        // 若记忆的文件夹已被删除则回退「未归类」视图
        viewModelScope.launch {
            assistantIdFlow.collect { assistantId ->
                // 助手切换后，取消并清掉上一个助手的全部分页流缓存
                val it = conversationsCache.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (!entry.key.startsWith("${assistantId}_")) {
                        entry.value.scope.cancel()
                        it.remove()
                    }
                }
                _selectedFolderId.value = resolvePersistedFolderId(assistantId)
            }
        }
    }

    /**
     * 读取该助手持久化的「新会话默认文件夹」，并校验其仍然存在且归属于该助手。
     * 未设置、文件夹已不存在或属于其他助手时返回 null（未归类视图）。
     */
    private suspend fun resolvePersistedFolderId(assistantId: Uuid): Uuid? {
        val persisted = settingsStore.settingsFlowRaw.first()
            .selectedFolderIds[assistantId.toString()]
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return null
        return folderRepo.getFolderById(persisted)
            ?.takeIf { it.assistantId == assistantId }
            ?.let { persisted }
    }

    fun saveScrollPosition(assistantId: Uuid, folderId: Uuid?, index: Int, offset: Int) {
        // 必须显式传入该滚动状态所属的助手与分类：切换瞬间实时值已变化，
        // 若按实时值取 key，会把上一个列表的位置写进新的（助手+分类）记忆
        val key = scrollKey(assistantId, folderId)
        savedStateHandle["scrollIndex_$key"] = index
        savedStateHandle["scrollOffset_$key"] = offset
    }

    fun selectFolder(folderId: Uuid?) {
        _selectedFolderId.value = folderId
        viewModelScope.launch {
            // 助手 ID 从原始 DataStore 读取，避免 settingsFlow 尚未加载（dummy）时
            // 把文件夹记录到错误的助手键下
            val assistantId = settingsStore.settingsFlowRaw.first().assistantId
            settingsStore.updateSelectedFolder(assistantId, folderId)
        }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed)
        }
    }

    fun renameFolder(folderId: Uuid, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     * 删除文件夹。若文件夹内有正在生成回复的会话，拒绝删除并返回 false（UI 层据此提示用户）。
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
                val assistantId = assistantIdFlow.first()
                settingsStore.updateSelectedFolder(assistantId, null)
            }
        }
        return true
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
