package com.dmer.neoreaderrecords

import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BooxReadingStateProbe {
    private val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
    private val statsUri = Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")

    data class Result(
        val report: String,
        val latestTitle: String?,
        val latestProgress: String?,
        val latestAccessMs: Long
    )

    fun run(context: Context): Result {
        val now = System.currentTimeMillis()
        val out = StringBuilder()
        out.append("BooxReadingStateProbe\n")
        out.append("time=").append(formatDateTime(now)).append('\n')
        out.append("device=").append(DevicePlatform.identityText()).append('\n')
        out.append("isBoox=").append(DevicePlatform.isBooxDevice()).append('\n')
        out.append('\n')

        val latest = probeMetadata(context, out)
        out.append('\n')
        probeStats(context, out)
        out.append('\n')
        out.append("说明：此探测读取文石数据库最后写回状态，不保证每翻一页都实时更新。建议打开一本书、翻页、切回 App 连续点几次，对比 progress/lastAccess 是否变化。\n")
        return Result(out.toString(), latest?.title, latest?.progress, latest?.lastAccessMs ?: 0L)
    }

    private data class LatestBook(
        val title: String?,
        val progress: String?,
        val lastAccessMs: Long
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
                if (row == 0) latest = LatestBook(title, progress, lastAccessMs)
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

    private fun probeStats(context: Context, out: StringBuilder) {
        out.append("[OnyxStatisticsModel]\n")
        val cursor = runCatching {
            context.contentResolver.query(statsUri, null, null, null, null)
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
                val interesting = c.columnNames.filter {
                    it.contains("time", ignoreCase = true) ||
                        it.contains("duration", ignoreCase = true) ||
                        it.contains("path", ignoreCase = true) ||
                        it.contains("book", ignoreCase = true) ||
                        it.contains("page", ignoreCase = true) ||
                        it.contains("progress", ignoreCase = true)
                }.ifEmpty { c.columnNames.take(8).toList() }
                out.append(interesting.joinToString(", ") { name -> "$name=${col(c, name).orEmpty().take(80)}" })
                out.append('\n')
                row += 1
            }
        }
    }

    private fun col(c: Cursor, name: String): String? {
        val idx = c.getColumnIndex(name)
        if (idx < 0 || c.isNull(idx)) return null
        return runCatching { c.getString(idx) }.getOrNull()
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
