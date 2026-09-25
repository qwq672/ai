// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.os.Build
import com.geniex.demo.bean.ModelData
import java.io.File

/**
 * Detects the Snapdragon SoC the app is running on, and which entries in
 * [ModelData] are a good match for it.
 *
 * Why not use [com.geniex.sdk.ModelManagerWrapper.detectChipset]? Because
 * that round-trips into Rust + JNI on a coroutine, while we need the
 * answer synchronously during UI inflation (e.g. to mark the spinner).
 * They agree on the same SMxxxx strings in practice, so we treat ours
 * as a fast-path hint and let the SDK call win later if there's a
 * disagreement.
 *
 * Detection priority:
 *  1. /sys/devices/soc0/soc_id   (actual SoC id, e.g. "QCM8550", "SM8750")
 *  2. /sys/devices/soc0/machine   (human-readable, e.g. "Qualcomm Technologies, Inc. SM8650")
 *  3. Build.SOC_MANUFACTURER + Build.SOC_MODEL (API 31+)
 *  4. Build.BOARD (legacy, less precise)
 */
object DeviceInfo {
    /** Map of known SoC identifiers to canonical SMxxxx chipset codes. */
    private val CHIPSET_MAP: List<Pair<List<String>, String>> = listOf(
        // SM8550 / Snapdragon 8 Gen 2
        listOf("SM8550", "QCM8550", "SD8GEN2") to "SM8550",
        // SM8650 / Snapdragon 8 Gen 3 — Htp V75
        listOf("SM8650", "QCM8650", "SD8GEN3") to "SM8650",
        // SM8750 / Snapdragon 8 Elite — Htp V79
        listOf("SM8750", "QCM8750", "SD8ELITE") to "SM8750",
        // SM8850 / Snapdragon 8 Elite Gen 5 — Htp V81
        listOf("SM8850", "QCM8850", "SD8ELITEG5") to "SM8850",
        // SM7675 / Snapdragon 7+ Gen 3 — Htp V73
        listOf("SM7675", "SM7635") to "SM7675",
        // SM8475 / Snapdragon 8+ Gen 1 / 8 Gen 1 — Htp V68/V69
        listOf("SM8475", "SM8450", "SM8350") to "SM8475",
    )

    data class SoCInfo(
        val chipset: String,
        val marketingName: String,
        val htpVersion: String,
        val rawMachine: String,
    )

    @Volatile
    private var cached: SoCInfo? = null

    /** Slow on first call; cached thereafter. Safe to call from main thread. */
    fun detect(): SoCInfo {
        cached?.let { return it }
        val raw = readSysFsSoc()
        val detected = match(raw) ?: fallback()
        cached = detected
        return detected
    }

    private fun readSysFsSoc(): String {
        val candidates = listOf(
            "/sys/devices/soc0/soc_id",
            "/sys/devices/soc0/machine",
            "/sys/devices/soc0/sku",
        )
        val sb = StringBuilder()
        for (path in candidates) {
            runCatching {
                File(path).takeIf { it.exists() }?.readText()?.trim()?.let {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(it)
                }
            }
        }
        // Build.SOC_MODEL is API 31+; our minSdk is 31, so safe.
        runCatching {
            val m = "${Build.SOC_MANUFACTURER ?: ""} ${Build.SOC_MODEL ?: ""}".trim()
            if (m.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(m)
            }
        }
        // Fallbacks for older devices / non-Qualcomm boards.
        runCatching {
            val b = "${Build.BOARD ?: ""} ${Build.HARDWARE ?: ""} ${Build.PRODUCT ?: ""}".trim()
            if (b.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(b)
            }
        }
        return sb.toString().uppercase()
    }

    private fun match(raw: String): SoCInfo? {
        for ((keys, chipset) in CHIPSET_MAP) {
            if (keys.any { raw.contains(it.uppercase()) }) {
                val (marketing, htp) = marketingName(chipset)
                return SoCInfo(chipset, marketing, htp, raw)
            }
        }
        return null
    }

    private fun fallback(): SoCInfo = SoCInfo(
        chipset = "UNKNOWN",
        marketingName = "Unknown / non-Snapdragon",
        htpVersion = "—",
        rawMachine = "no SoC info available",
    )

    private fun marketingName(chipset: String): Pair<String, String> = when (chipset) {
        "SM8550" -> "Snapdragon 8 Gen 2" to "V68"
        "SM8650" -> "Snapdragon 8 Gen 3" to "V75"
        "SM8750" -> "Snapdragon 8 Elite" to "V79"
        "SM8850" -> "Snapdragon 8 Elite Gen 5" to "V81"
        "SM7675" -> "Snapdragon 7+ Gen 3" to "V73"
        "SM8475" -> "Snapdragon 8 Gen 1 / 8+ Gen 1" to "V68/V69"
        else -> "Snapdragon" to "—"
    }

    /**
     * Returns true when this model's [ModelData.chipset] is unset (no
     * chipset requirement) or matches the local device — used by the
     * model-management UI to highlight "Recommended for your device".
     */
    fun isCompatible(model: ModelData): Boolean {
        val modelChipset = model.chipset ?: return true
        val local = detect().chipset
        return modelChipset.equals(local, ignoreCase = true)
    }

    /**
     * For QAIRT models, the bundle is pre-compiled for a specific chipset,
     * so a mismatch is fatal. For llama_cpp GGUF models, the chipset hint
     * is just an NPU scheduling preference — running on a non-matching
     * NPU falls back to CPU.
     */
    fun isNpuRuntimeSupported(model: ModelData): Boolean {
        val local = detect().chipset
        val modelChipset = model.chipset ?: return true
        if (modelChipset.equals(local, ignoreCase = true)) return true
        // llama_cpp runtime doesn't bundle chipset-specific .dlc, only
        // the libggml-htp-vXX.so per NPU arch; if the matching lib is
        // present, NPU still works on mismatched chipsets.
        return model.runtime == "llama_cpp"
    }
}
