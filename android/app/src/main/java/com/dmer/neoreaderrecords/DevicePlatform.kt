package com.dmer.neoreaderrecords

import android.os.Build

object DevicePlatform {
    fun isHanvonDevice(): Boolean {
        return listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT
        ).any { it.orEmpty().contains("HANVON", ignoreCase = true) || it.orEmpty().contains("汉王", ignoreCase = true) }
    }

    fun isHisenseDevice(): Boolean {
        return listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT
        ).any { value ->
            value.orEmpty().contains("HISENSE", ignoreCase = true) ||
                value.orEmpty().contains("海信", ignoreCase = true)
        }
    }

    fun isBooxDevice(): Boolean {
        return listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT
        ).any {
            val value = it.orEmpty()
            value.contains("ONYX", ignoreCase = true) || value.contains("BOOX", ignoreCase = true)
        }
    }

    fun identityText(): String {
        return "manufacturer=${Build.MANUFACTURER}, brand=${Build.BRAND}, model=${Build.MODEL}, device=${Build.DEVICE}, product=${Build.PRODUCT}, sdk=${Build.VERSION.SDK_INT}"
    }
}
