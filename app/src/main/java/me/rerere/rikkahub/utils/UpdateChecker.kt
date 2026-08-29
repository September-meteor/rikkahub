package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.UpdateSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val OFFICIAL_API_URL = "https://updates.rikka-ai.com/"
private const val FORK_API_URL = "https://api.github.com/repos/September-meteor/rikkahub/releases/latest"
private val FORK_VERSION_PATTERN = Regex("\\d+(\\.\\d+)*")

class UpdateChecker(
    private val client: OkHttpClient,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // 下载前的连通性探测用短超时，避免直连失败时长时间卡顿
    private val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val updateState: StateFlow<UiState<UpdateInfo>> = settingsStore.settingsFlow
        .map { it.displaySetting.updateSource }
        .distinctUntilChanged()
        .flatMapLatest { source -> checkUpdate(source) }
        .stateIn(
            scope = appScope,
            started = SharingStarted.Lazily,
            initialValue = UiState.Loading,
        )

    private fun checkUpdate(source: UpdateSource): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    when (source) {
                        UpdateSource.FORK -> fetchForkUpdate()
                        UpdateSource.OFFICIAL -> fetchOfficialUpdate()
                    }
                } catch (e: Exception) {
                    throw Exception("Failed to fetch update info", e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchOfficialUpdate(): UpdateInfo {
        val response = client.newCall(
            Request.Builder()
                .url(OFFICIAL_API_URL)
                .get()
                .addHeader(
                    "User-Agent",
                    "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                )
                .build()
        ).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch update info")
        }
        return json.decodeFromString<UpdateInfo>(response.body.string())
    }

    private suspend fun fetchForkUpdate(): UpdateInfo {
        val response = client.newCall(
            Request.Builder()
                .url(FORK_API_URL)
                .get()
                .addHeader(
                    "User-Agent",
                    "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                )
                .build()
        ).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch update info")
        }
        val release = json.decodeFromString<GitHubRelease>(response.body.string())
        // tag 形如 f-1.4.2，取其数字部分作为 fork 的版本号
        val version = FORK_VERSION_PATTERN.find(release.tagName)?.value
            ?: throw Exception("Failed to parse release tag: ${release.tagName}")
        return UpdateInfo(
            version = version,
            publishedAt = release.publishedAt,
            changelog = release.body,
            downloads = release.assets.map { asset ->
                UpdateDownload(
                    name = asset.name,
                    url = asset.browserDownloadUrl,
                    size = formatFileSize(asset.size),
                )
            }
        )
    }

    /**
     * 判断是否应向用户展示更新卡片
     *
     * 官方源：与 BuildConfig.VERSION_NAME（跟随上游）比较；
     * 本分支源：与 BuildConfig.FORK_VERSION_NAME（fork 自身发布版本，与 release tag 同步）比较。
     * 因为 fork 的 APK 版本号完全跟随上游，不能用上游版本号判断 fork 是否有更新。
     */
    fun shouldShowUpdate(info: UpdateInfo): Boolean {
        return when (settingsStore.settingsFlow.value.displaySetting.updateSource) {
            UpdateSource.OFFICIAL -> Version(info.version) > Version(BuildConfig.VERSION_NAME)
            UpdateSource.FORK -> Version(info.version) > Version(BuildConfig.FORK_VERSION_NAME)
        }
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        appScope.launch {
            // 优先直连（内容源最可靠）；直连不通且是 GitHub 链接时，走用户配置的镜像前缀
            val (targetUrl, viaMirror) = withContext(Dispatchers.IO) {
                val mirror = settingsStore.settingsFlow.value.displaySetting.updateDownloadMirror
                when {
                    isUrlReachable(download.url) -> download.url to false
                    mirror.isNotBlank() && download.url.contains("github.com") ->
                        mirror.trimEnd('/') + "/" + download.url to true
                    else -> download.url to false
                }
            }
            if (viaMirror) {
                Toast.makeText(context, R.string.update_card_downloading_via_mirror, Toast.LENGTH_SHORT).show()
            }
            runCatching {
                enqueueDownload(context, download, targetUrl, viaMirror)
            }.onFailure {
                Toast.makeText(context, R.string.update_card_check_failed, Toast.LENGTH_SHORT).show()
                context.openUrl(targetUrl) // 跳转到下载页面
            }
        }
    }

    private fun enqueueDownload(context: Context, download: UpdateDownload, url: String, viaMirror: Boolean) {
        val request = DownloadManager.Request(url.toUri()).apply {
            // 设置下载时通知栏的标题和描述
            setTitle(download.name)
            setDescription(
                if (viaMirror) {
                    context.getString(R.string.update_card_downloading_via_mirror_desc)
                } else {
                    context.getString(R.string.update_card_downloading)
                }
            )
            // 下载完成后通知栏可见
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 允许在移动网络和WiFi下下载
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            // 设置文件保存路径
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
            // 允许下载的文件类型
            setMimeType("application/vnd.android.package-archive")
        }
        // 获取系统的DownloadManager
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        // 你可以保存返回的downloadId到本地，以便后续查询下载进度或状态
    }

    /** 探测 URL 是否可达（短超时 GET 首字节），用于决定直连还是走镜像 */
    private fun isUrlReachable(url: String): Boolean = runCatching {
        probeClient.newCall(
            Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .get()
                .build()
        ).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("published_at") val publishedAt: String,
    val body: String,
    val assets: List<GitHubReleaseAsset>,
)

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long,
)

private fun formatFileSize(size: Long): String {
    val mb = size / 1024.0 / 1024.0
    return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", size / 1024.0)
}

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
