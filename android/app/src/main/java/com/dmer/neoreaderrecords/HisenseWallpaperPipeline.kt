package com.dmer.neoreaderrecords

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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

        val failures = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val pngBytes = runCatching {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("bitmap compress failed")
                out.toByteArray()
            }
        }.getOrElse {
            failures += "compress ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            ByteArray(0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            }.onSuccess {
                successes += "FLAG_LOCK_BITMAP result=$it"
            }.onFailure {
                failures += "FLAG_LOCK_BITMAP ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            }

            if (pngBytes.isNotEmpty()) {
                runCatching {
                    manager.setStream(ByteArrayInputStream(pngBytes), null, true, WallpaperManager.FLAG_LOCK)
                }.onSuccess {
                    successes += "FLAG_LOCK_STREAM result=$it"
                }.onFailure {
                    failures += "FLAG_LOCK_STREAM ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                }
            }

            val lockOk = successes.any { it.startsWith("FLAG_LOCK") }
            if (!lockOk) {
                runCatching {
                    manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                }.onSuccess {
                    successes += "FLAG_SYSTEM_BITMAP result=$it"
                }.onFailure {
                    failures += "FLAG_SYSTEM_BITMAP ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                }

                if (pngBytes.isNotEmpty()) {
                    runCatching {
                        manager.setStream(ByteArrayInputStream(pngBytes), null, true, WallpaperManager.FLAG_SYSTEM)
                    }.onSuccess {
                        successes += "FLAG_SYSTEM_STREAM result=$it"
                    }.onFailure {
                        failures += "FLAG_SYSTEM_STREAM ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                    }
                }
            }
        }

        if (successes.isEmpty()) {
            runCatching {
                @Suppress("DEPRECATION")
                manager.setBitmap(bitmap)
            }.onSuccess {
                successes += "DEFAULT_BITMAP"
            }.onFailure {
                failures += "DEFAULT_BITMAP ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            }

            if (pngBytes.isNotEmpty()) {
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.setStream(ByteArrayInputStream(pngBytes))
                }.onSuccess {
                    successes += "DEFAULT_STREAM"
                }.onFailure {
                    failures += "DEFAULT_STREAM ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                }
            }
        }

        if (successes.isNotEmpty()) {
            val detail = "hisense=active ok methods=${successes.joinToString("|")} failures=${failures.joinToString("|").ifBlank { "none" }}"
            AutoRefreshLog.i(context, "Hisense pipeline $detail")
            DebugEventLog.i(context, "Hisense pipeline $detail")
            return Result(active = true, ok = true, detail = detail)
        }

        val detail = "hisense=active failed attempts=${failures.joinToString("；").ifBlank { "none" }}"
        AutoRefreshLog.i(context, "Hisense pipeline $detail")
        DebugEventLog.i(context, "Hisense pipeline $detail")
        return Result(active = true, ok = false, detail = detail)
    }
}
