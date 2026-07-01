package com.dmer.neoreaderrecords

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HanvonReadingProbe {
    private val providerKeywords = listOf(
        "hanvon", "aebr", "reader", "read", "book", "bookshelf",
        "history", "note", "annotation", "stat", "statistics"
    )
    private val providerPathCandidates = listOf(
        "",
        "book",
        "books",
        "bookshelf",
        "reader",
        "read",
        "reading",
        "history",
        "record",
        "records",
        "stat",
        "stats",
        "statistics",
        "note",
        "notes",
        "annotation",
        "annotations"
    )
    private val fileKeywords = listOf(
        "book", "read", "reader", "history", "note", "annotation",
        "stat", "statistics", "shelf", "bookshelf", "koreader"
    )
    private val sqliteExtensions = setOf("db", "sqlite", "sqlite3")

    data class Result(
        val report: String,
        val providerCount: Int,
        val sqliteCandidateCount: Int,
        val readableSqliteCount: Int
    )

    fun run(context: Context): Result {
        val out = StringBuilder()
        out.append("HanvonReadingProbe\n")
        out.append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        out.append("device=").append(DevicePlatform.identityText()).append('\n')
        out.append("isHanvon=").append(DevicePlatform.isHanvonDevice()).append('\n')
        out.append("isBoox=").append(DevicePlatform.isBooxDevice()).append('\n')
        out.append('\n')

        val providers = findCandidateProviders(context)
        out.append("providers candidateCount=").append(providers.size).append('\n')
        providers.take(24).forEachIndexed { index, provider ->
            out.append("provider[").append(index).append("]=")
                .append("authority=").append(provider.authority.orEmpty())
                .append(", package=").append(provider.packageName.orEmpty())
                .append(", name=").append(provider.name.orEmpty())
                .append(", exported=").append(provider.exported)
                .append(", grantUriPermissions=").append(provider.grantUriPermissions)
                .append(", readPermission=").append(provider.readPermission.orEmpty())
                .append(", writePermission=").append(provider.writePermission.orEmpty())
                .append('\n')
            out.append(probeProvider(context, provider, index))
        }
        if (providers.size > 24) {
            out.append("providers truncated=").append(providers.size - 24).append('\n')
        }
        out.append('\n')

        val files = findCandidateFiles(context)
        val sqliteReports = mutableListOf<String>()
        var readableSqlite = 0
        files.take(60).forEachIndexed { index, file ->
            val report = probeSqliteFile(file, index)
            if (report.contains("sqlite=ok")) readableSqlite += 1
            sqliteReports += report
        }
        out.append("sqliteCandidates count=").append(files.size).append('\n')
        sqliteReports.forEach { out.append(it) }
        if (files.size > 60) {
            out.append("sqliteCandidates truncated=").append(files.size - 60).append('\n')
        }
        out.append('\n')
        out.append("hints=").append("如果 providers=0 且 sqliteCandidates=0，建议让用户在文件管理器里确认汉王阅读器数据目录，下一步再加手动目录授权探测。").append('\n')

        return Result(
            report = out.toString(),
            providerCount = providers.size,
            sqliteCandidateCount = files.size,
            readableSqliteCount = readableSqlite
        )
    }

    private fun findCandidateProviders(context: Context): List<ProviderInfo> {
        val pm = context.packageManager
        val providers = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryContentProviders(
                    null,
                    0,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryContentProviders(null, 0, PackageManager.GET_META_DATA)
            }
        }.getOrElse { emptyList() }
        return providers
            .filter { provider ->
                listOf(provider.authority, provider.packageName, provider.name)
                    .joinToString(" ")
                    .lowercase(Locale.ROOT)
                    .let { value -> providerKeywords.any { value.contains(it) } }
            }
            .sortedWith(compareBy<ProviderInfo> {
                if (it.authority.orEmpty().contains("hanvon", ignoreCase = true) ||
                    it.packageName.orEmpty().contains("hanvon", ignoreCase = true)
                ) 0 else 1
            }.thenBy { it.authority.orEmpty() })
    }

    private fun probeProvider(context: Context, provider: ProviderInfo, providerIndex: Int): String {
        val authority = provider.authority.orEmpty()
        if (authority.isBlank()) return "  providerProbe=skip emptyAuthority\n"
        val out = StringBuilder()
        var success = 0
        providerPathCandidates.forEach { path ->
            if (success >= 3) return@forEach
            val uriText = if (path.isBlank()) "content://$authority" else "content://$authority/$path"
            val uri = Uri.parse(uriText)
            val linePrefix = "  providerProbe[$providerIndex:$path]="
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val columns = cursor.columnNames.orEmpty().take(36).joinToString(",")
                    val hasRow = cursor.moveToFirst()
                    out.append(linePrefix)
                        .append("ok uri=").append(uriText)
                        .append(", columns=").append(columns)
                        .append(", rowSample=").append(if (hasRow) sampleCursorRow(cursor) else "<empty>")
                        .append('\n')
                    success += 1
                } ?: out.append(linePrefix).append("null uri=").append(uriText).append('\n')
            }.onFailure {
                out.append(linePrefix)
                    .append("fail uri=").append(uriText)
                    .append(", error=").append(it.javaClass.simpleName)
                    .append(":").append(it.message.orEmpty().take(100))
                    .append('\n')
            }
        }
        return out.toString()
    }

    private fun sampleCursorRow(cursor: android.database.Cursor): String {
        val preferred = listOf(
            "id", "_id", "bookId", "book_id", "title", "name", "author", "authors",
            "path", "filePath", "nativeAbsolutePath", "progress", "status",
            "lastReadTime", "lastAccess", "updateTime", "duration", "readTime"
        )
        val pairs = mutableListOf<String>()
        preferred.forEach { name ->
            val index = cursor.getColumnIndex(name)
            if (index >= 0 && pairs.size < 10) {
                pairs += "$name=${safeCursorValue(cursor, index)}"
            }
        }
        if (pairs.isEmpty()) {
            cursor.columnNames.orEmpty().take(8).forEachIndexed { _, name ->
                val index = cursor.getColumnIndex(name)
                if (index >= 0) pairs += "$name=${safeCursorValue(cursor, index)}"
            }
        }
        return pairs.joinToString("|").ifBlank { "<no-sample>" }
    }

    private fun safeCursorValue(cursor: android.database.Cursor, index: Int): String {
        return runCatching {
            when (cursor.getType(index)) {
                android.database.Cursor.FIELD_TYPE_NULL -> "<null>"
                android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toString()
                android.database.Cursor.FIELD_TYPE_BLOB -> "<blob:${cursor.getBlob(index)?.size ?: 0}>"
                else -> cursor.getString(index).orEmpty()
            }.replace('\n', ' ').replace('\r', ' ').take(80)
        }.getOrElse { "<${it.javaClass.simpleName}>" }
    }

    private fun findCandidateFiles(context: Context): List<File> {
        val roots = mutableListOf<File>()
        val external = Environment.getExternalStorageDirectory()
        roots += listOf(
            external,
            File(external, "hwsys"),
            File(external, "Hanvon"),
            File(external, "hanvon"),
            File(external, "Books"),
            File(external, "books"),
            File(external, "Documents"),
            File(external, "Download"),
            File(external, "koreader"),
            File(external, ".koreader"),
            File(external, ".adds/koreader")
        )
        context.getExternalFilesDir(null)?.let { roots += it }
        context.filesDir?.let { roots += it }
        val out = linkedMapOf<String, File>()
        roots.filter { it.exists() && it.isDirectory }.forEach { root ->
            scanFiles(root, depth = 0, maxDepth = 4, out = out)
        }
        return out.values.sortedBy { it.absolutePath.lowercase(Locale.ROOT) }
    }

    private fun scanFiles(root: File, depth: Int, maxDepth: Int, out: MutableMap<String, File>) {
        if (out.size >= 140 || depth > maxDepth) return
        val children = runCatching { root.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
            .take(220)
        children.forEach { file ->
            if (out.size >= 140) return
            if (file.isDirectory) {
                val name = file.name.lowercase(Locale.ROOT)
                if (depth == 0 || fileKeywords.any { name.contains(it) } || name in setOf("databases", "files", "settings")) {
                    scanFiles(file, depth + 1, maxDepth, out)
                }
            } else if (isCandidateSqlite(file)) {
                out[file.absolutePath] = file
            }
        }
    }

    private fun isCandidateSqlite(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext in sqliteExtensions) return true
        return fileKeywords.any { name.contains(it) } && file.length() in 1L..80_000_000L
    }

    private fun probeSqliteFile(file: File, index: Int): String {
        val out = StringBuilder()
        out.append("sqlite[").append(index).append("]=")
            .append("path=").append(file.absolutePath)
            .append(", bytes=").append(runCatching { file.length() }.getOrDefault(0L))
            .append(", readable=").append(file.canRead())
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrElse {
            out.append(", sqlite=fail ")
                .append(it.javaClass.simpleName).append(":").append(it.message.orEmpty().take(120))
                .append('\n')
            return out.toString()
        }
        db.use { database ->
            out.append(", sqlite=ok")
            val tables = mutableListOf<String>()
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name LIMIT 24",
                null
            ).use { c ->
                while (c.moveToNext()) tables += c.getString(0).orEmpty()
            }
            out.append(", tables=").append(tables.joinToString(","))
            out.append('\n')
            tables.take(10).forEach { table ->
                out.append("  table=").append(table).append(", columns=")
                val columns = mutableListOf<String>()
                runCatching {
                    database.rawQuery("PRAGMA table_info(${quoteIdentifier(table)})", null).use { c ->
                        while (c.moveToNext() && columns.size < 24) {
                            val name = c.getString(c.getColumnIndexOrThrow("name")).orEmpty()
                            val type = c.getString(c.getColumnIndexOrThrow("type")).orEmpty()
                            columns += if (type.isBlank()) name else "$name:$type"
                        }
                    }
                }.onFailure { columns += "probeFail:${it.javaClass.simpleName}" }
                out.append(columns.joinToString(",")).append('\n')
            }
        }
        return out.toString()
    }

    private fun quoteIdentifier(value: String): String {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
