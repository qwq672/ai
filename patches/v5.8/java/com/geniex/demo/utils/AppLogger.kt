// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * File-backed logger that mirrors selected [android.util.Log] calls into
 * a rolling file at `<externalFilesDir>/app.log` (= /sdcard/Android/data/
 * <package>/files/app.log when the app is installed normally).
 *
 * Design notes:
 *  * No permission required — writes go to the app's own external files
 *    dir, which Android grants implicitly on API 19+.
 *  * All disk I/O happens on a single-thread executor so callers never
 *    block the UI thread even if the file system stalls.
 *  * The file is capped at [MAX_LOG_BYTES]; on overflow we keep the tail
 *    by renaming to `app.log.1` and starting fresh, matching `logrotate`
 *    semantics familiar to anyone who reads server logs.
 *  * Calls are no-ops when [enabled] is false — `SettingsActivity` flips
 *    this at runtime so users can disable logging to save space/IO.
 *
 * Why not just use `adb logcat`? Two reasons: end users on consumer
 * devices cannot run logcat without developer tools, and the demo's
 * field debugging (download failures, NPU init errors) needs a file
 * the user can attach to a bug report.
 */
object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_LOG_BYTES = 5L * 1024 * 1024 // 5 MB rolling limit
    private const val MAX_LOG_FILES = 2 // app.log + app.log.1
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger-IO").apply { isDaemon = true }
    }
    private val pending = AtomicInteger(0)
    private var logFile: File? = null
    private var enabled = true
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Bind the logger to a Context. Reads [KEY_LOG_ENABLED] from the
     * default SharedPreferences to honour the user's setting; must be
     * called once from [android.app.Application.onCreate].
     */
    fun init(context: Context) {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        enabled = prefs.getBoolean(KEY_LOG_ENABLED, true)
        logFile = File(context.getExternalFilesDir(null), "app.log")
        log("INFO", TAG, "AppLogger initialised; logFile=${logFile?.absolutePath}; enabled=$enabled")
    }

    /** Flip logging at runtime (called from SettingsActivity). */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) log("INFO", TAG, "logging enabled by user")
    }

    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = log("WARN", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("ERROR", tag, msg, t)

    /**
     * Mirror to logcat (so `adb logcat` still works) AND enqueue a disk
     * write. Returns immediately — the disk write happens on [ioExecutor].
     */
    private fun log(
        level: String,
        tag: String,
        msg: String,
        throwable: Throwable? = null,
    ) {
        // Always mirror to logcat — costs nothing and is invaluable for adb.
        when (level) {
            "DEBUG" -> Log.d(tag, msg)
            "INFO" -> Log.i(tag, msg)
            "WARN" -> if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
            "ERROR" -> if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
        }
        if (!enabled) return
        // Throttle: if we already have >50 writes queued, drop this one to
        // avoid unbounded queue growth during a hot streaming loop. The
        // streamed tokens are noisy; losing some is fine for a log file.
        if (pending.get() > 50) return
        pending.incrementAndGet()
        val ts = tsFormat.format(Date())
        val line = buildString {
            append(ts).append(' ').append(level.padEnd(5)).append(' ').append(tag).append(": ").append(msg)
            if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                append('\n').append(sw.toString())
            }
            append('\n')
        }
        ioExecutor.execute {
            try {
                pending.decrementAndGet()
                val f = logFile ?: return@execute
                if (f.exists() && f.length() > MAX_LOG_BYTES) rollOver(f)
                f.appendText(line)
            } catch (_: Exception) {
                // Logging must never break callers.
            }
        }
    }

    /** Rotate `app.log` → `app.log.1`, dropping the previous `.1`. */
    private fun rollOver(f: File) {
        runCatching {
            val backup = File(f.parentFile, "app.log.1")
            if (backup.exists()) backup.delete()
            f.renameTo(backup)
        }
    }

    /** Returns the absolute path to the log file, for sharing from Settings. */
    fun logFilePath(): String? = logFile?.takeIf { it.exists() }?.absolutePath

    /** Constant used by SettingsActivity to read/write the enable flag. */
    const val KEY_LOG_ENABLED = "log_enabled"
}
