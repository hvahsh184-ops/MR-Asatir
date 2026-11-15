package com.example.domain

import kotlin.math.abs

/**
 * سیستم حذف آلارم های کاذب و کالیبراسیون نویز پس‌زمینه
 * جلوگیری از هشدارها و بوق‌های الکی طلا/نقره در محیط مسکونی و سکون کامل
 */
class AntiFalseAlarmShield {

    private var historicalNoiseFloor = 0.0f
    private val alpha = 0.95f // ضریب میانگین متحرک برای یادگیری نویز پس‌زمینه

    /**
     * فیلتر هوشمند سیگنال برای قفل کردن بوق‌های الکی زمانی که دستگاه حرکت نمی‌کند
     * @param rawSignal سیگنال دریافتی از سنسور
     * @param isMoving آیا تبلت بر اساس شتاب‌سنج در حال حرکت است؟
     * @param isIndoorMode آیا دستگاه در محیط مسکونی/خانگی روشن است؟
     */
    fun suppressFalsePositives(
        rawSignal: FloatArray,
        isMoving: Boolean,
        isIndoorMode: Boolean
    ): FloatArray {
        val output = FloatArray(rawSignal.size)
        
        // ۱. بروزرسانی داینامیک سطح نویز محیط
        if (rawSignal.isNotEmpty()) {
            val currentAverage = rawSignal.map { abs(it) }.average().toFloat()
            historicalNoiseFloor = (alpha * historicalNoiseFloor) + ((1 - alpha) * currentAverage)
        }

        // ۲. اعمال فیلتر هوشمند بر اساس فاکتور حرکت و مکان
        val minimumThreshold = if (isIndoorMode) historicalNoiseFloor * 2.2f else historicalNoiseFloor * 1.3f

        for (i in rawSignal.indices) {
            val signalVal = rawSignal[i]
            
            if (!isMoving) {
                // اگر دستگاه کاملاً بی حرکت روی زمین یا میز است، آستانه حساسیت بالا می‌رود تا نویز مغناطیسی باعث بوق کاذب نشود
                output[i] = if (abs(signalVal) > minimumThreshold * 1.8f) signalVal * 0.2f else 0.0f
            } else {
                // در حال اسکن صحرایی و فعال
                output[i] = if (abs(signalVal) > minimumThreshold) signalVal else 0.0f
            }
        }
        return output
    }
}
