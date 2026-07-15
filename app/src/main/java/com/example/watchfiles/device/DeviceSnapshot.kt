package com.example.watchfiles.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.util.Locale

data class DeviceSnapshot(val rows: List<Pair<String, String>>)

fun readDeviceSnapshot(context: Context): DeviceSnapshot {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val metrics = context.resources.displayMetrics
    val configuration = context.resources.configuration
    val runtime = Runtime.getRuntime()
    val storage = StatFs(Environment.getExternalStorageDirectory().absolutePath)

    return DeviceSnapshot(
        rows = listOf(
            "型号" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            "ABI" to Build.SUPPORTED_ABIS.joinToString(),
            "屏幕像素" to "${metrics.widthPixels} × ${metrics.heightPixels}",
            "布局尺寸" to "${configuration.screenWidthDp} × ${configuration.screenHeightDp} dp",
            "密度" to "${metrics.densityDpi} dpi",
            "圆屏" to if (configuration.isScreenRound) "是" else "否",
            "低内存设备" to if (activityManager.isLowRamDevice) "是" else "否",
            "应用内存等级" to "${activityManager.memoryClass} MiB",
            "大堆等级" to "${activityManager.largeMemoryClass} MiB（未启用）",
            "运行时最大堆" to formatBytes(runtime.maxMemory()),
            "存储可用" to formatBytes(storage.availableBytes),
            "存储总量" to formatBytes(storage.totalBytes),
        )
    )
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
}
