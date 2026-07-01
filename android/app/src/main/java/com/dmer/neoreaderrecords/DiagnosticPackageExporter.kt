package com.dmer.neoreaderrecords

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticPackageExporter {
    fun export(context: Context): SafeLogStore.WriteResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "ReadTrace_logs_$stamp.zip"
        val bytes = buildZip(context)
        return SafeLogStore.writeBytes(context, name, "application/zip", bytes, append = false)
    }

    private fun buildZip(context: Context): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            addText(zip, "device_info.txt", deviceInfo(context))
            addText(zip, "log_locations.txt", logLocations(context))
            addFirstExisting(zip, "neoreader_debug_log.txt", SafeLogStore.candidates(context, SafeLogStore.DEBUG_LOG_NAME))
            addFirstExisting(zip, "neoreader_auto_refresh_log.txt", SafeLogStore.candidates(context, SafeLogStore.AUTO_REFRESH_LOG_NAME))
            addFirstExisting(zip, "neoreader_wallpaper.png", wallpaperCandidates(context))
        }
        return out.toByteArray()
    }

    private fun deviceInfo(context: Context): String {
        return buildString {
            append("package=").append(context.packageName).append('\n')
            append("version=").append(GitHubReleaseChecker.currentVersionName(context)).append('\n')
            append("sdk=").append(Build.VERSION.SDK_INT).append('\n')
            append("release=").append(Build.VERSION.RELEASE).append('\n')
            append("manufacturer=").append(Build.MANUFACTURER).append('\n')
            append("brand=").append(Build.BRAND).append('\n')
            append("model=").append(Build.MODEL).append('\n')
            append("device=").append(Build.DEVICE).append('\n')
            append("product=").append(Build.PRODUCT).append('\n')
            append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        }
    }

    private fun logLocations(context: Context): String {
        return buildString {
            listOf(SafeLogStore.DEBUG_LOG_NAME, SafeLogStore.AUTO_REFRESH_LOG_NAME).forEach { name ->
                append(name).append('\n')
                SafeLogStore.candidates(context, name).forEach { file ->
                    append("  ").append(file.absolutePath)
                        .append(" exists=").append(file.exists())
                        .append(" bytes=").append(if (file.exists()) file.length() else 0L)
                        .append('\n')
                }
            }
            append("wallpaperCandidates").append('\n')
            wallpaperCandidates(context).forEach { file ->
                append("  ").append(file.absolutePath)
                    .append(" exists=").append(file.exists())
                    .append(" bytes=").append(if (file.exists()) file.length() else 0L)
                    .append('\n')
            }
        }
    }

    private fun wallpaperCandidates(context: Context): List<File> {
        return listOfNotNull(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NeoReader/neoreader_wallpaper.png"),
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "NeoReader/neoreader_wallpaper.png") },
            File(context.filesDir, "NeoReader/neoreader_wallpaper.png")
        ).distinctBy { it.absolutePath }
    }

    private fun addFirstExisting(zip: ZipOutputStream, entryName: String, files: List<File>) {
        val file = files.firstOrNull { it.exists() && it.isFile && it.length() > 0L }
        if (file == null) {
            addText(zip, "$entryName.missing.txt", "未找到 $entryName\n候选路径：\n${files.joinToString("\n") { it.absolutePath }}\n")
            return
        }
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addText(zip: ZipOutputStream, entryName: String, text: String) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
