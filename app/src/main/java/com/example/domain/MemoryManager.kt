package com.example.domain

import android.app.ActivityManager
import android.content.Context
import android.util.Log

class MemoryManager(private val context: Context) {
    private val TAG = "MemoryManager"
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    data class MemoryStatus(
        val totalMemoryMb: Int,
        val availableMemoryMb: Int,
        val usedMemoryMb: Int,
        val usagePercent: Int,
        val isCritical: Boolean
    )

    fun checkMemoryStatus(): MemoryStatus {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val availableMb = (memInfo.availMem / (1024 * 1024)).toInt()
        val usedMb = totalMb - availableMb
        val percent = if (totalMb > 0) ((usedMb.toFloat() / totalMb) * 100).toInt() else 0
        val isCritical = percent >= 90 || memInfo.lowMemory

        return MemoryStatus(
            totalMemoryMb = totalMb,
            availableMemoryMb = availableMb,
            usedMemoryMb = usedMb,
            usagePercent = percent,
            isCritical = isCritical
        )
    }

    fun triggerAutoCleanupIfCritical(onCleanup: () -> Unit) {
        val status = checkMemoryStatus()
        if (status.isCritical) {
            Log.w(TAG, "Critical memory usage detected: ${status.usagePercent}% (${status.usedMemoryMb}MB / ${status.totalMemoryMb}MB). Triggering GC and cleanup.")
            System.gc()
            Runtime.getRuntime().gc()
            onCleanup()
        }
    }
}
