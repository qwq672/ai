// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralised typed accessor for the app's user preferences. Wraps the
 * default SharedPreferences so callers don't have to remember string keys
 * and can read/write settings in one line from anywhere.
 *
 * Why a singleton wrapper instead of injecting SharedPreferences directly?
 * The Activity instances get recreated on rotation, and we'd rather not
 * pass an instance through every constructor. Single-process app, single
 * prefs file, single accessor.
 */
object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    }

    // --- Generation / sampling ---
    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, 0.7f)
        set(v) = prefs.edit().putFloat(KEY_TEMPERATURE, v.coerceIn(0f, 2f)).apply()

    var topP: Float
        get() = prefs.getFloat(KEY_TOP_P, 0.9f)
        set(v) = prefs.edit().putFloat(KEY_TOP_P, v.coerceIn(0f, 1f)).apply()

    var topK: Int
        get() = prefs.getInt(KEY_TOP_K, 40)
        set(v) = prefs.edit().putInt(KEY_TOP_K, v.coerceIn(1, 200)).apply()

    var repetitionPenalty: Float
        get() = prefs.getFloat(KEY_REPETITION_PENALTY, 1.1f)
        set(v) = prefs.edit().putFloat(KEY_REPETITION_PENALTY, v.coerceIn(0.5f, 2f)).apply()

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 2048)
        set(v) = prefs.edit().putInt(KEY_MAX_TOKENS, v.coerceIn(64, 32768)).apply()

    var enableThinking: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_THINKING, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLE_THINKING, v).apply()

    // --- NPU / performance ---
    /**
     * NPU performance profile: "burst" (max throughput, most heat),
     * "sustained" (balanced), "power_saver" (lowest heat, slowest).
     * Translated to QNN HTP performance mode via env var.
     */
    var npuPowerMode: String
        get() = prefs.getString(KEY_NPU_POWER_MODE, "sustained") ?: "sustained"
        set(v) = prefs.edit().putString(KEY_NPU_POWER_MODE, v).apply()

    /** VTCM (Vector Tightly Coupled Memory) size in MB. 0 = driver default. */
    var vtcmMb: Int
        get() = prefs.getInt(KEY_VTCM_MB, 0)
        set(v) = prefs.edit().putInt(KEY_VTCM_MB, v.coerceIn(0, 64)).apply()

    /** Number of CPU threads for llama.cpp when not on NPU. */
    var cpuThreads: Int
        get() = prefs.getInt(KEY_CPU_THREADS, 4)
        set(v) = prefs.edit().putInt(KEY_CPU_THREADS, v.coerceIn(1, 16)).apply()

    /**
     * CPU 线程数选择模式："auto"（按 CPU 大核数自动）或 "custom"（手动）。
     * 当为 "auto" 时，[cpuThreads] 字段被忽略，运行时根据 Runtime.availableProcessors
     * 计算（取 max(2, available / 2) 作为大核数估计）。
     */
    var cpuThreadsMode: String
        get() = prefs.getString(KEY_CPU_THREADS_MODE, "auto") ?: "auto"
        set(v) = prefs.edit().putString(KEY_CPU_THREADS_MODE, v).apply()

    /** Resolve the actual thread count to use, honouring [cpuThreadsMode]. */
    fun resolveCpuThreads(): Int = when (cpuThreadsMode) {
        "auto" -> {
            val available = Runtime.getRuntime().availableProcessors()
            // big.LITTLE 估计：取一半作为大核；至少 2 个。
            (available / 2).coerceIn(2, 8)
        }
        else -> cpuThreads
    }

    /** When true, cap inference thread count to reduce thermal pressure. */
    var thermalThrottle: Boolean
        get() = prefs.getBoolean(KEY_THERMAL_THROTTLE, true)
        set(v) = prefs.edit().putBoolean(KEY_THERMAL_THROTTLE, v).apply()

    /** 下载源默认值。auto / huggingface / hf_mirror */
    var downloadSource: String
        get() = prefs.getString(KEY_DOWNLOAD_SOURCE, "auto") ?: "auto"
        set(v) = prefs.edit().putString(KEY_DOWNLOAD_SOURCE, v).apply()

    // --- UI ---
    /** When true, show TTFT/prefill/decoding speeds under each AI reply. */
    var showProfiling: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PROFILING, true)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_PROFILING, v).apply()

    /** When true, use the HuggingFace mirror (hf-mirror.com) for downloads. */
    var useHfMirror: Boolean
        get() = prefs.getBoolean(KEY_USE_HF_MIRROR, true)
        set(v) = prefs.edit().putBoolean(KEY_USE_HF_MIRROR, v).apply()

    /** 保留聊天历史，下次启动时恢复。 */
    var chatHistoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHAT_HISTORY, true)
        set(v) = prefs.edit().putBoolean(KEY_CHAT_HISTORY, v).apply()

    /**
     * 深色模式："system"（跟随系统）/ "light"（强制浅色）/ "dark"（强制深色）。
     * 在 Application.onCreate 中通过 AppCompatDelegate.setDefaultNightMode 应用，
     * 改变后所有 Activity 自动 recreate。
     */
    var darkMode: String
        get() = prefs.getString(KEY_DARK_MODE, "system") ?: "system"
        set(v) = prefs.edit().putString(KEY_DARK_MODE, v).apply()

    /**
     * 模型存储位置："internal"（filesDir，默认）/ "external"（getExternalFilesDir，
     * 即 /sdcard/Android/data/<package>/files/）。
     * 切换时由 SettingsActivity 询问是否迁移。
     */
    var storageLocation: String
        get() = prefs.getString(KEY_STORAGE_LOCATION, "internal") ?: "internal"
        set(v) = prefs.edit().putString(KEY_STORAGE_LOCATION, v).apply()

    // --- Logging ---
    var loggingEnabled: Boolean
        get() = prefs.getBoolean(AppLogger.KEY_LOG_ENABLED, true)
        set(v) {
            prefs.edit().putBoolean(AppLogger.KEY_LOG_ENABLED, v).apply()
            AppLogger.setEnabled(v)
        }

    // Key constants — exposed so PreferenceFragment can reference them.
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_TOP_P = "top_p"
    const val KEY_TOP_K = "top_k"
    const val KEY_REPETITION_PENALTY = "repetition_penalty"
    const val KEY_MAX_TOKENS = "max_tokens"
    const val KEY_ENABLE_THINKING = "enable_thinking"
    const val KEY_NPU_POWER_MODE = "npu_power_mode"
    const val KEY_VTCM_MB = "vtcm_mb"
    const val KEY_CPU_THREADS = "cpu_threads"
    const val KEY_THERMAL_THROTTLE = "thermal_throttle"
    const val KEY_SHOW_PROFILING = "show_profiling"
    const val KEY_USE_HF_MIRROR = "use_hf_mirror"
    const val KEY_CPU_THREADS_MODE = "cpu_threads_mode"
    const val KEY_DOWNLOAD_SOURCE = "download_source"
    const val KEY_CHAT_HISTORY = "chat_history_enabled"
    const val KEY_DARK_MODE = "dark_mode"
    const val KEY_STORAGE_LOCATION = "storage_location"
}
