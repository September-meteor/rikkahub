package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry

/**
 * 工作区文件的粗略分类, 用于决定点击文件时的行为:
 * - TEXT: 应用内文本编辑/预览（尽力而为：无扩展名/未知扩展名也先进编辑器，
 *   编辑器读到二进制内容时会提示改用其它应用打开）
 * - IMAGE: 应用内可缩放图片预览
 * - OTHER: 明确非文本的类型（音视频/压缩包/文档/二进制等）交给系统应用打开
 */
enum class WorkspaceFileType { TEXT, IMAGE, OTHER }

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

/** 明确不按文本处理的扩展名：媒体 / 压缩包 / 办公文档 / 二进制可执行等 */
private val NON_TEXT_EXTENSIONS = setOf(
    // 音频
    "mp3", "wav", "ogg", "oga", "flac", "m4a", "aac", "opus", "mid", "midi", "wma", "amr",
    // 视频
    "mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "m4v", "3gp", "ts", "mpg", "mpeg", "rmvb",
    // 压缩包 / 镜像
    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "jar", "apk", "aab", "iso", "dmg", "deb", "rpm",
    // 办公文档
    "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "odt", "ods", "odp", "epub", "mobi", "azw3",
    // 二进制 / 可执行
    "exe", "dll", "so", "dylib", "dex", "class", "bin", "dat", "db", "sqlite", "sqlite3", "ttf", "otf",
    "woff", "woff2", "wasm", "a", "o", "obj",
)

fun WorkspaceFileEntry.detectFileType(): WorkspaceFileType {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXTENSIONS -> WorkspaceFileType.IMAGE
        ext in NON_TEXT_EXTENSIONS -> WorkspaceFileType.OTHER
        // 文本、无扩展名或未知扩展名：一律先进应用内编辑器（尽力而为）
        else -> WorkspaceFileType.TEXT
    }
}
