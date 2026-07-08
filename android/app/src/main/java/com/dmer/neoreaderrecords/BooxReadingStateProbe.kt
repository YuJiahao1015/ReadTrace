package com.dmer.neoreaderrecords

import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

object BooxReadingStateProbe {
    private val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
    private val statsUri = Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")
    private val annotationUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Annotation")

    data class Result(
        val report: String,
        val latestTitle: String?,
        val latestProgress: String?,
        val latestAccessMs: Long
    )

    fun run(context: Context, reason: String = "manual"): Result {
        val now = System.currentTimeMillis()
        val out = StringBuilder()
        out.append("BooxReadingStateProbe\n")
        out.append("time=").append(formatDateTime(now)).append('\n')
        out.append("reason=").append(reason).append('\n')
        out.append("device=").append(DevicePlatform.identityText()).append('\n')
        out.append("isBoox=").append(DevicePlatform.isBooxDevice()).append('\n')
        out.append('\n')

        val latest = probeMetadata(context, out)
        out.append('\n')
        val stats = probeStats(context, out, latest)
        out.append('\n')
        probeCurrentPageTextCandidates(context, out)
        out.append('\n')
        probeBookFileText(context, out, latest, stats)
        out.append('\n')
        out.append("说明：此探测读取文石数据库最后写回状态，并尝试从统计/批注字段寻找当前页文本候选；同时会在可访问书籍文件时，按当前进度粗略拆书估算正文片段。EPUB/TXT 候选只用于验证可能性，不代表 NeoReader 的真实分页；PDF/漫画暂不能直接抽正文。若 Metadata 变化频繁且 OnyxStatisticsModel 最新 currPage/lastPage 同步变化，说明可近实时拿到页码；若 Provider 字段候选为空，则当前接口暂未暴露整页正文。\n")
        return Result(out.toString(), latest?.title, latest?.progress, latest?.lastAccessMs ?: 0L)
    }

    private data class LatestBook(
        val title: String?,
        val progress: String?,
        val lastAccessMs: Long,
        val path: String?
    )

    private data class LatestStats(
        val currPage: Int?,
        val lastPage: Int?,
        val readingProgress: Double?,
        val eventTimeMs: Long
    )

    private fun probeMetadata(context: Context, out: StringBuilder): LatestBook? {
        out.append("[Metadata]\n")
        val cursor = runCatching {
            context.contentResolver.query(metadataUri, null, null, null, "lastAccess DESC")
        }.getOrElse {
            out.append("queryFailed=").append(it.javaClass.simpleName).append(':').append(it.message.orEmpty()).append('\n')
            return null
        }
        if (cursor == null) {
            out.append("queryResult=null\n")
            return null
        }
        cursor.use { c ->
            out.append("columns=").append(c.columnNames.joinToString(",")).append('\n')
            out.append("count=").append(c.count).append('\n')
            var latest: LatestBook? = null
            var row = 0
            while (c.moveToNext() && row < 8) {
                val title = col(c, "title") ?: col(c, "name")
                val path = col(c, "nativeAbsolutePath") ?: col(c, "filePath") ?: col(c, "path")
                val progress = col(c, "progress")
                val readingStatus = col(c, "readingStatus")
                val lastAccessRaw = col(c, "lastAccess")
                val lastAccessMs = normalizeEpochMs(lastAccessRaw?.toLongOrNull() ?: 0L)
                if (row == 0) latest = LatestBook(title, progress, lastAccessMs, path)
                out.append("row").append(row).append('=')
                    .append("title=").append(title.orEmpty().take(80))
                    .append(", progress=").append(progress.orEmpty())
                    .append(", readingStatus=").append(readingStatus.orEmpty())
                    .append(", lastAccess=").append(lastAccessRaw.orEmpty())
                    .append(", lastAccessText=").append(if (lastAccessMs > 0L) formatDateTime(lastAccessMs) else "-")
                    .append(", path=").append(path.orEmpty().take(120))
                    .append('\n')
                row += 1
            }
            return latest
        }
    }

    private fun probeStats(context: Context, out: StringBuilder, latest: LatestBook?): LatestStats? {
        out.append("[OnyxStatisticsModel]\n")
        val cursor = runCatching {
            context.contentResolver.query(statsUri, null, null, null, "eventTime DESC")
        }.getOrElse {
            out.append("query eventTime DESC failed=").append(it.javaClass.simpleName).append(':').append(it.message.orEmpty()).append('\n')
            runCatching {
                context.contentResolver.query(statsUri, null, null, null, null)
            }.getOrElse { fallback ->
                out.append("query fallback failed=").append(fallback.javaClass.simpleName).append(':').append(fallback.message.orEmpty()).append('\n')
                null
            }
        } ?: run {
            out.append("queryResult=null\n")
            return null
        }
        cursor.use { c ->
            out.append("columns=").append(c.columnNames.joinToString(",")).append('\n')
            out.append("count=").append(c.count).append('\n')
            var row = 0
            var latestStats: LatestStats? = null
            while (c.moveToNext() && row < 16) {
                val eventTimeRaw = col(c, "eventTime")
                val eventTimeMs = normalizeEpochMs(eventTimeRaw?.toLongOrNull() ?: 0L)
                val title = col(c, "title") ?: col(c, "name")
                val path = col(c, "path")
                val textCandidate = firstNonBlank(
                    col(c, "orgText"),
                    col(c, "note"),
                    col(c, "comment")
                )
                val currPage = col(c, "currPage")?.toIntOrNull()
                val lastPage = col(c, "lastPage")?.toIntOrNull()
                val readingProgress = col(c, "readingProgress")?.toDoubleOrNull()
                if (latestStats == null && (currPage != null || readingProgress != null)) {
                    latestStats = LatestStats(currPage, lastPage, readingProgress, eventTimeMs)
                }
                out.append("row").append(row).append('=')
                    .append("eventTime=").append(eventTimeRaw.orEmpty())
                    .append(", eventText=").append(if (eventTimeMs > 0L) formatDateTime(eventTimeMs) else "-")
                    .append(", title=").append(title.orEmpty().take(60))
                    .append(", path=").append(path.orEmpty().take(90))
                    .append(", readingProgress=").append(col(c, "readingProgress").orEmpty())
                    .append(", currPage=").append(col(c, "currPage").orEmpty())
                    .append(", lastPage=").append(col(c, "lastPage").orEmpty())
                    .append(", position=").append(col(c, "position").orEmpty().take(80))
                    .append(", chapter=").append(col(c, "chapter").orEmpty().take(80))
                    .append(", durationTime=").append(col(c, "durationTime").orEmpty())
                    .append(", textCandidate=").append(textCandidate.orEmpty().replace('\n', ' ').take(160))
                    .append('\n')
                row += 1
            }
            val latestTitle = latest?.title.orEmpty()
            if (latestTitle.isNotBlank()) {
                out.append("latestBookMatchHint=").append(latestTitle.take(80)).append('\n')
            }
            return latestStats
        }
    }

    private fun probeCurrentPageTextCandidates(context: Context, out: StringBuilder) {
        out.append("[CurrentPageTextCandidates]\n")
        probeRecentAnnotationText(context, out)
        probeCandidateUri(context, out, "Bookmark", Uri.parse("content://com.onyx.content.database.ContentProvider/Bookmark"))
        probeCandidateUri(context, out, "ReadingProgress", Uri.parse("content://com.onyx.content.database.ContentProvider/ReadingProgress"))
        probeCandidateUri(context, out, "ReadingRecord", Uri.parse("content://com.onyx.content.database.ContentProvider/ReadingRecord"))
    }

    private fun probeRecentAnnotationText(context: Context, out: StringBuilder) {
        out.append("Annotation\n")
        val cursor = runCatching {
            context.contentResolver.query(annotationUri, null, null, null, "updatedAt DESC")
        }.getOrElse {
            out.append("queryFailed=").append(it.javaClass.simpleName).append(':').append(it.message.orEmpty()).append('\n')
            return
        }
        if (cursor == null) {
            out.append("queryResult=null\n")
            return
        }
        cursor.use { c ->
            out.append("columns=").append(c.columnNames.joinToString(",")).append('\n')
            out.append("count=").append(c.count).append('\n')
            var row = 0
            while (c.moveToNext() && row < 8) {
                out.append("row").append(row).append('=')
                    .append("updatedAt=").append(col(c, "updatedAt").orEmpty())
                    .append(", createdAt=").append(col(c, "createdAt").orEmpty())
                    .append(", idString=").append(col(c, "idString").orEmpty().take(60))
                    .append(", objId=").append(col(c, "objId").orEmpty().take(60))
                    .append(", quote=").append(col(c, "quote").orEmpty().replace('\n', ' ').take(160))
                    .append(", note=").append(col(c, "note").orEmpty().replace('\n', ' ').take(160))
                out.append('\n')
                row += 1
            }
        }
    }

    private fun probeCandidateUri(context: Context, out: StringBuilder, label: String, uri: Uri) {
        out.append(label).append('\n')
        val cursor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)
        }.getOrElse {
            out.append("queryFailed=").append(it.javaClass.simpleName).append(':').append(it.message.orEmpty()).append('\n')
            return
        }
        if (cursor == null) {
            out.append("queryResult=null\n")
            return
        }
        cursor.use { c ->
            out.append("columns=").append(c.columnNames.joinToString(",")).append('\n')
            out.append("count=").append(c.count).append('\n')
            var row = 0
            while (c.moveToNext() && row < 3) {
                val interesting = c.columnNames.filter {
                    it.contains("text", ignoreCase = true) ||
                        it.contains("content", ignoreCase = true) ||
                        it.contains("page", ignoreCase = true) ||
                        it.contains("chapter", ignoreCase = true) ||
                        it.contains("position", ignoreCase = true) ||
                        it.contains("progress", ignoreCase = true)
                }.ifEmpty { c.columnNames.take(8).toList() }
                out.append("row").append(row).append('=')
                    .append(interesting.joinToString(", ") { name -> "$name=${col(c, name).orEmpty().replace('\n', ' ').take(80)}" })
                    .append('\n')
                row += 1
            }
        }
    }

    private fun probeBookFileText(context: Context, out: StringBuilder, latest: LatestBook?, stats: LatestStats?) {
        out.append("[BookFileTextProbe]\n")
        val path = latest?.path
        out.append("path=").append(path.orEmpty().take(160)).append('\n')
        if (path.isNullOrBlank()) {
            out.append("result=no_path\n")
            return
        }
        val file = File(path)
        out.append("exists=").append(file.exists())
            .append(", canRead=").append(file.canRead())
            .append(", length=").append(runCatching { file.length() }.getOrDefault(-1L))
            .append('\n')
        if (!file.exists() || !file.canRead()) {
            out.append("result=file_not_readable\n")
            out.append("hint=若这里不可读，说明只能通过文石 Provider 拿进度，不能直接拆书读取正文。\n")
            return
        }

        val progressRatio = resolveProgressRatio(latest, stats)
        out.append("progressRatio=").append(if (progressRatio != null) "%.4f".format(Locale.US, progressRatio) else "-")
            .append(", latestProgress=").append(latest.progress.orEmpty())
            .append(", statsCurrPage=").append(stats?.currPage ?: "")
            .append(", statsLastPage=").append(stats?.lastPage ?: "")
            .append(", statsReadingProgress=").append(stats?.readingProgress ?: "")
            .append('\n')

        when (file.extension.lowercase(Locale.US)) {
            "epub" -> probeEpubText(file, progressRatio, out)
            "txt" -> probePlainText(file, progressRatio, out)
            "pdf" -> out.append("result=pdf_no_text_extractor\n")
                .append("hint=Android PdfRenderer 只能渲染页面图片，不能直接抽文字；如要正文需要 OCR 或 PDF 文本解析库。\n")
            "cbz", "zip", "cbr", "rar" -> out.append("result=image_archive_no_text\n")
                .append("hint=漫画/图片压缩包没有可直接读取的正文，除非做 OCR。\n")
            else -> out.append("result=unsupported_extension extension=").append(file.extension).append('\n')
        }
    }

    private fun resolveProgressRatio(latest: LatestBook?, stats: LatestStats?): Double? {
        val progress = latest?.progress.orEmpty()
        Regex("""(\d+)\s*/\s*(\d+)""").find(progress)?.let { m ->
            val current = m.groupValues[1].toDoubleOrNull()
            val total = m.groupValues[2].toDoubleOrNull()
            if (current != null && total != null && total > 0.0) return (current / total).coerceIn(0.0, 1.0)
        }
        stats?.readingProgress?.takeIf { it > 0.0 }?.let {
            return (it / 100.0).coerceIn(0.0, 1.0)
        }
        val curr = stats?.currPage
        val last = stats?.lastPage
        if (curr != null && last != null && last > 0) {
            return (curr.toDouble() / last.toDouble()).coerceIn(0.0, 1.0)
        }
        return null
    }

    private fun probeEpubText(file: File, progressRatio: Double?, out: StringBuilder) {
        runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { e ->
                        val name = e.name.lowercase(Locale.US)
                        name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")
                    }
                    .sortedBy { it.name }
                    .toList()
                out.append("epubHtmlEntries=").append(entries.size).append('\n')
                if (entries.isEmpty()) {
                    out.append("result=epub_no_html_entries\n")
                    return
                }
                val textEntries = entries.mapNotNull { entry ->
                    val raw = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val text = htmlToPlainText(raw)
                    if (text.isBlank()) null else entry.name to text
                }
                out.append("epubTextEntries=").append(textEntries.size).append('\n')
                if (textEntries.isEmpty()) {
                    out.append("result=epub_no_text\n")
                    return
                }
                val totalChars = textEntries.sumOf { it.second.length }.coerceAtLeast(1)
                val targetOffset = ((progressRatio ?: 0.0).coerceIn(0.0, 1.0) * totalChars).toInt()
                var passed = 0
                val chosen = textEntries.firstOrNull { (_, text) ->
                    val hit = targetOffset <= passed + text.length
                    if (!hit) passed += text.length
                    hit
                } ?: textEntries.last().also {
                    passed = totalChars - it.second.length
                }
                val localOffset = (targetOffset - passed).coerceIn(0, chosen.second.length)
                val start = (localOffset - 180).coerceAtLeast(0)
                val end = (localOffset + 520).coerceAtMost(chosen.second.length)
                val sample = chosen.second.substring(start, end)
                    .replace('\n', ' ')
                    .replace(Regex("""\s+"""), " ")
                out.append("result=epub_text_estimated\n")
                out.append("chosenEntry=").append(chosen.first.take(120)).append('\n')
                out.append("totalChars=").append(totalChars)
                    .append(", targetOffset=").append(targetOffset)
                    .append(", localOffset=").append(localOffset)
                    .append('\n')
                out.append("textSample=").append(sample.take(700)).append('\n')
                out.append("confidence=low_approx_by_epub_text_position\n")
            }
        }.getOrElse {
            out.append("result=epub_probe_failed ")
                .append(it.javaClass.simpleName)
                .append(':')
                .append(it.message.orEmpty())
                .append('\n')
        }
    }

    private fun probePlainText(file: File, progressRatio: Double?, out: StringBuilder) {
        runCatching {
            val text = file.readText(Charsets.UTF_8)
            val offset = ((progressRatio ?: 0.0).coerceIn(0.0, 1.0) * text.length).toInt()
            val start = (offset - 180).coerceAtLeast(0)
            val end = (offset + 520).coerceAtMost(text.length)
            out.append("result=txt_text_estimated\n")
            out.append("totalChars=").append(text.length).append(", targetOffset=").append(offset).append('\n')
            out.append("textSample=").append(text.substring(start, end).replace('\n', ' ').take(700)).append('\n')
            out.append("confidence=medium_approx_by_text_position\n")
        }.getOrElse {
            out.append("result=txt_probe_failed ")
                .append(it.javaClass.simpleName)
                .append(':')
                .append(it.message.orEmpty())
                .append('\n')
        }
    }

    private fun htmlToPlainText(raw: String): String {
        return raw
            .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun col(c: Cursor, name: String): String? {
        val idx = c.getColumnIndex(name)
        if (idx < 0 || c.isNull(idx)) return null
        return runCatching { c.getString(idx) }.getOrNull()
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun normalizeEpochMs(value: Long): Long {
        return when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }
    }

    private fun formatDateTime(ms: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
    }
}
