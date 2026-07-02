package com.dmer.neoreaderrecords

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build

object HisenseWallpaperPipeline {
    data class Result(
        val active: Boolean,
        val ok: Boolean,
        val detail: String
    )

    fun install(context: Context, bitmap: Bitmap, reason: String): Result {
        if (!DevicePlatform.isHisenseDevice()) {
            return Result(active = false, ok = true, detail = "hisense=inactive")
        }
        if (reason.startsWith("screen_on_prewarm") || reason.startsWith("user_present_prewarm")) {
            val detail = "hisense=active skippedPrewarm reason=$reason"
            AutoRefreshLog.i(context, "Hisense pipeline $detail")
            DebugEventLog.i(context, "Hisense pipeline $detail")
            return Result(active = true, ok = true, detail = detail)
        }

        val manager = runCatching { WallpaperManager.getInstance(context) }.getOrElse {
            val detail = "hisense=active managerFail ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            AutoRefreshLog.e(context, "Hisense pipeline manager failed", it)
            DebugEventLog.i(context, "Hisense pipeline $detail")
            return Result(active = true, ok = false, detail = detail)
        }

        val attempts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            }.onSuccess {
                val detail = "hisense=active ok method=FLAG_LOCK result=$it"
                AutoRefreshLog.i(context, "Hisense pipeline $detail")
                DebugEventLog.i(context, "Hisense pipeline $detail")
                return Result(active = true, ok = true, detail = detail)
            }.onFailure {
                attempts += "FLAG_LOCK ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            }

            runCatching {
                manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            }.onSuccess {
                val detail = "hisense=active ok method=FLAG_SYSTEM result=$it lockFailed=${attempts.joinToString("|")}"
                AutoRefreshLog.i(context, "Hisense pipeline $detail")
                DebugEventLog.i(context, "Hisense pipeline $detail")
                return Result(active = true, ok = true, detail = detail)
            }.onFailure {
                attempts += "FLAG_SYSTEM ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            manager.setBitmap(bitmap)
        }.onSuccess {
            val detail = "hisense=active ok method=setBitmapDefault prior=${attempts.joinToString("|")}"
            AutoRefreshLog.i(context, "Hisense pipeline $detail")
            DebugEventLog.i(context, "Hisense pipeline $detail")
            return Result(active = true, ok = true, detail = detail)
        }.onFailure {
            attempts += "setBitmapDefault ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
        }

        val detail = "hisense=active failed attempts=${attempts.joinToString("；").ifBlank { "none" }}"
        AutoRefreshLog.i(context, "Hisense pipeline $detail")
        DebugEventLog.i(context, "Hisense pipeline $detail")
        return Result(active = true, ok = false, detail = detail)
    }
}
