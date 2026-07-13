package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object WallpaperFileStore {
    private const val FILE_NAME = "neoreader_wallpaper.png"
    private const val DIR_NAME = "NeoReader"
    private const val MIME_TYPE = "image/png"

    data class SaveResult(
        val ok: Boolean,
        val path: String,
        val detail: String,
        val fallback: Boolean = false,
        val contentUri: String? = null
    )

    fun save(context: Context, bitmap: Bitmap, reason: String = "manual"): SaveResult {
        val errors = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { return withDevicePipeline(context, bitmap, saveWithMediaStore(context, bitmap), reason) }
                .onFailure { errors += "MediaStore ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        }
        runCatching { return withDevicePipeline(context, bitmap, saveToPublicPictures(context, bitmap), reason) }
            .onFailure { errors += "PublicPictures ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        runCatching { return withDevicePipeline(context, bitmap, saveToAppPictures(context, bitmap, errors.joinToString("；")), reason) }
            .onFailure { errors += "AppPictures ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        return SaveResult(
            ok = false,
            path = "",
            detail = errors.joinToString("；").ifBlank { "未知保存错误" },
            fallback = true
        )
    }

    private fun withDevicePipeline(
        context: Context,
        bitmap: Bitmap,
        primary: SaveResult,
        reason: String
    ): SaveResult {
        val hanvon = HanvonWallpaperPipeline.install(context, bitmap, primary.path, reason)
        if (!hanvon.active) return primary
        val allPaths = (listOf(primary.path) + hanvon.paths).filter { it.isNotBlank() }.distinct()
        return primary.copy(
            path = allPaths.joinToString("\n"),
            detail = "${primary.detail}；${hanvon.detail}",
            fallback = primary.fallback || !hanvon.ok
        )
    }

    private fun saveWithMediaStore(context: Context, bitmap: Bitmap): SaveResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_PICTURES}/$DIR_NAME/"
        val uri = findExistingMediaUri(context, collection, relativePath)
            ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })
            ?: error("insert returned null")

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("bitmap compress failed")
            } ?: error("openOutputStream returned null")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) }
            throw e
        }
        val path = "${Environment.getExternalStorageDirectory().absolutePath}/${Environment.DIRECTORY_PICTURES}/$DIR_NAME/$FILE_NAME"
        return SaveResult(ok = true, path = path, detail = "saved=MediaStore uri=$uri", contentUri = uri.toString())
    }

    private fun findExistingMediaUri(context: Context, collection: Uri, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=? AND (${MediaStore.Images.Media.RELATIVE_PATH}=? OR ${MediaStore.Images.Media.RELATIVE_PATH}=?)"
        val args = arrayOf(FILE_NAME, relativePath, relativePath.trimEnd('/'))
        context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    private fun saveToPublicPictures(context: Context, bitmap: Bitmap): SaveResult {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed: ${dir.absolutePath}")
        val file = File(dir, FILE_NAME)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("bitmap compress failed")
        }
        scan(context, file.absolutePath)
        return SaveResult(ok = true, path = file.absolutePath, detail = "saved=public", contentUri = fileProviderUri(context, file))
    }

    private fun saveToAppPictures(context: Context, bitmap: Bitmap, priorErrors: String): SaveResult {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed: ${dir.absolutePath}")
        val file = File(dir, FILE_NAME)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("bitmap compress failed")
        }
        scan(context, file.absolutePath)
        return SaveResult(
            ok = true,
            path = file.absolutePath,
            detail = "saved=app_fallback；publicSaveFailed=$priorErrors",
            fallback = true,
            contentUri = fileProviderUri(context, file)
        )
    }

    private fun fileProviderUri(context: Context, file: File): String? {
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        }.getOrNull()
    }

    private fun scan(context: Context, path: String) {
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                arrayOf(MIME_TYPE)
            ) { _, _ -> }
        }
    }
}
