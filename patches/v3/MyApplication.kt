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
        // Settings first — AppLogger reads KEY_LOG_ENABLED from it.
        Settings.init(this)
        AppLogger.init(this)
        // Apply NPU performance settings as environment variables before
        // any native lib is loaded. The Rust side of GenieX reads these on
        // first LLM init, so they must be set before the SDK touches QNN.
        applyNpuEnvFromSettings()
        clearLegacyModelsDir()
    }

    /**
     * Translate the user's NPU performance preferences into the QNN HTP
     * environment variables that GenieX's Rust runtime honours.
     *
     * Known env vars (set in the qairt plugin on init):
     *  * QNN_HTP_PERFORMANCE_MODE — "burst" / "sustained" / "power_saver" / "default"
     *  * QNN_HTP_VTCM_SIZE_MB     — VTCM allocation in MB (0 = driver default)
     *
     * Thermal throttle is implemented by setting cpuThreads on top of these.
     */
    private fun applyNpuEnvFromSettings() {
        try {
            val mode = Settings.npuPowerMode
            val vtcm = Settings.vtcmMb
            // Use Os.setenv (API 21+) — survives native lib loads via dlopen.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.system.Os.setenv("QNN_HTP_PERFORMANCE_MODE", mode, true)
                android.system.Os.setenv("QNN_HTP_VTCM_SIZE_MB", vtcm.toString(), true)
                // GenieX respects LLAMA_N_THREADS for CPU paths; setting it here
                // limits CPU-bound threads regardless of the runtime picked.
                if (Settings.thermalThrottle) {
                    android.system.Os.setenv("LLAMA_N_THREADS", Settings.cpuThreads.toString(), true)
                }
            }
            AppLogger.i(TAG, "NPU env applied: mode=$mode vtcm=$vtcm thermalThrottle=${Settings.thermalThrottle}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply NPU env: $e")
        }
    }

    /**
     * Old builds downloaded models into `filesDir/models/{id}/...` with a
     * hand-rolled manifest. The Rust model manager owns its own layout
     * under `filesDir/geniex/models/{org}/{repo}/...` and cannot read the
     * old files. Wipe the legacy dir on first launch so users don't keep
     * paying for stranded gigabytes.
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
