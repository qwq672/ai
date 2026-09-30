// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.geniex.demo.utils.AppLogger
import com.geniex.demo.utils.Settings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 极简 Application：onCreate 只做最小初始化，所有重活延迟到第一次访问。
 *
 * 这是为了诊断启动闪退：先把 onCreate 缩到最小，确认 app 能起来，
 * 再逐步恢复功能定位问题。
 */
class MyApplication : Application() {
    override fun onCreate() {
        // 第一步：装全局崩溃捕获，把 stack 写入外部存储（不依赖任何其他 init）
        installCrashLogger()
        super.onCreate()
        try {
            // 第二步：Settings + AppLogger（轻量）
            Settings.init(this)
            AppLogger.init(this)
            AppLogger.i(TAG, "MyApplication.onCreate start")
            // 第三步：环境变量（NPU 性能）— 包在 try-catch 中
            applyNpuEnvFromSettings()
            // 第四步：深色模式（AppCompat 静态调用，不会崩）
            applyDarkModeFromSettings()
            // 第五步：清理 legacy 目录（可能很慢，但不崩）
            clearLegacyModelsDir()
            AppLogger.i(TAG, "MyApplication.onCreate done")
        } catch (t: Throwable) {
            // 不能让 Application onCreate 抛异常 — 会触发 ANR + 闪退循环
            AppLogger.e(TAG, "MyApplication init failed: ${t.message}", t)
            Log.e(TAG, "MyApplication init failed", t)
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 直接写文件，避免依赖 AppLogger（AppLogger 可能还没初始化）
            try {
                val logFile = File(getExternalFilesDir(null), "app.log")
                logFile.parentFile?.mkdirs()
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
                logFile.appendText("FATAL on ${thread.name}: ${throwable.message}\n$sw\n---\n")
                android.util.Log.e("AndroidRuntime", "FATAL on ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // 完全静默 — 不能让日志记录阻塞崩溃路径
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

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

    private fun applyDarkModeFromSettings() {
        try {
            val mode = Settings.darkMode
            val nightMode = when (mode) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
            AppLogger.i(TAG, "dark mode applied: $mode")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dark mode: $e")
        }
    }

    private fun clearLegacyModelsDir() {
        try {
            val legacy = File(filesDir, "models")
            if (!legacy.exists()) return
            val ok = runCatching { legacy.deleteRecursively() }.getOrElse { false }
            AppLogger.i(TAG, "legacy models dir cleanup: ok=$ok path=${legacy.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "legacy models dir cleanup failed: $e")
        }
    }

    companion object {
        private const val TAG = "GenieXDemo"
    }
}
