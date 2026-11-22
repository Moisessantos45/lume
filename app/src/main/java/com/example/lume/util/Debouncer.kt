package com.example.lume.util

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class Debouncer(private val cooldownMs: Long = 400L) {
    private val last = mutableMapOf<String, Long>()

    fun tryAcquire(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val prev = last[key] ?: 0L
        if (now - prev < cooldownMs) return false
        last[key] = now
        return true
    }

    inline fun guard(key: String, block: () -> Unit) {
        if (tryAcquire(key)) block()
    }
}

@Composable
fun rememberDebouncer(cooldownMs: Long = 400L): Debouncer {
    return remember(cooldownMs) { Debouncer(cooldownMs) }
}
