// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.app.Application
import android.util.Log
import com.geniex.demo.utils.AppLogger
import com.geniex.demo.utils.Settings
import java.io.File

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 安装全局未捕获异常处理器：把崩溃 stack 写到 app.log，
        // 这样用户即使不接 adb 也能在 /Android/data/com.geniex.demo/files/app.log 里看到崩溃原因。
        installCrashLogger()
        // Settings first — AppLogger reads KEY_LOG_ENABLED from it.
        Settings.init(this)
        AppLogger.init(this)
        // Apply NPU performance settings as environment variables before
        // any native lib is loaded. The Rust side of GenieX reads these on
        // first LLM init, so they must be set before the SDK touches QNN.
        applyNpuEnvFromSettings()
        // Apply saved dark mode preference before any Activity inflates.
        applyDarkModeFromSettings()
        clearLegacyModelsDir()
    }

    /**
     * Write every uncaught exception's stack trace to [AppLogger] before
     * re-throwing to the default handler. The default handler kills the
     * process and writes a tombstone, but on consumer devices the user
     * can't read tombstones — this gives them a file they can attach to
     * a bug report.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLogger.e("AndroidRuntime", "FATAL on ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // logging must never break the crash path
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Translate the user's NPU performance preferences into the QNN HTP
     * environment variables that GenieX's Rust runtime honours.
     */
    private fun applyNpuEnvFromSettings() {
        try {
            val mode = Settings.npuPowerMode
            val vtcm = Settings.vtcmMb
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.system.Os.setenv("QNN_HTP_PERFORMANCE_MODE", mode, true)
                android.system.Os.setenv("QNN_HTP_VTCM_SIZE_MB", vtcm.toString(), true)
                if (Settings.thermalThrottle) {
                    android.system.Os.setenv("LLAMA_N_THREADS", Settings.resolveCpuThreads().toString(), true)
                }
            }
            AppLogger.i(TAG, "NPU env applied: mode=$mode vtcm=$vtcm thermalThrottle=${Settings.thermalThrottle}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply NPU env: $e")
        }
    }

    /**
     * Apply the saved dark mode preference via AppCompatDelegate so it
     * takes effect app-wide without restarting the Activity.
     * Called from Application.onCreate so the very first Activity
     * inflates with the correct night mode already set.
     */
    private fun applyDarkModeFromSettings() {
        val mode = Settings.darkMode
        val nightMode = when (mode) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
        AppLogger.i(TAG, "dark mode applied: $mode")
    }

    /**
     * Old builds downloaded models into `filesDir/models/{id}/...` with a
     * hand-rolled manifest. Wipe the legacy dir on first launch.
     */
    private fun clearLegacyModelsDir() {
        val legacy = File(filesDir, "models")
        if (!legacy.exists()) return
        val ok = runCatching { legacy.deleteRecursively() }.getOrElse { false }
        Log.i(TAG, "legacy models dir cleanup: ok=$ok path=${legacy.absolutePath}")
        AppLogger.i(TAG, "legacy models dir cleanup: ok=$ok path=${legacy.absolutePath}")
    }

    companion object {
        private const val TAG = "GenieXDemo"
    }
}
