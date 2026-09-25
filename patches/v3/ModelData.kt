// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.bean

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

/**
 * Demo-side description of a model that the Rust model manager can
 * download and resolve. Only the fields needed by `geniex_model_pull`
 * and the UI live here — file paths come from `ModelManagerWrapper
 * .getPaths(modelName)` after the pull completes.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ModelData(
    /** Stable UI key (spinner selection, SharedPreferences). */
    val id: String,
    /** Human-readable label shown in the UI. */
    val displayName: String,
    /**
     * `org/repo` as the Rust hub expects. Not an alias — keep the
     * alias layer (`resolveAlias`) out of the demo for simplicity.
     */
    val modelName: String,
    /** "chat" / "llm" / "vlm". */
    val type: String? = null,
    /**
     * Runtime that owns this model: `"llama_cpp"` (CPU/GPU/NPU via
     * llama.cpp's per-tensor scheduler) or `"qairt"` (NPU-only AI Hub
     * bundle). Drives the compute-unit picker — see [getSupportPluginIds].
     */
    val runtime: String? = null,
    /** Quantization hint passed to `geniex_model_pull`. */
    val quant: String? = null,
    /**
     * Hub name — matches [com.geniex.sdk.bean.HubSource] by enum name.
     * Default `AUTO` lets Rust pick based on `modelName`.
     */
    val hub: String? = "AUTO",
    /** AI Hub display_name; required when [hub] is `AIHUB`. */
    val aiHubDisplayName: String? = null,
    /**
     * Target chipset for AI Hub pulls (e.g. "SM8650"). On Android the
     * Rust side has no auto-detect, so `aiHubDisplayName` entries must
     * pair with an explicit `chipset`.
     */
    val chipset: String? = null,
    /**
     * Local absolute file path for user-imported GGUF models. When
     * non-null, this entry is treated as a local model: the Rust
     * model manager is bypassed (no `getPaths` / `pullFlow`) and the
     * given path is fed directly to `LlmCreateInput`/`VlmCreateInput`.
     * The `id` doubles as the in-app model name.
     */
    val localPath: String? = null,

    // --- Model metadata for the model-management UI. All optional so the
    // --- existing model_list.json keeps working without changes; we just
    // --- surface the fields when they're present.

    /** Maximum context window the model can hold (e.g. 4096, 32768). */
    val maxContext: Int? = null,
    /** KV cache quantization (e.g. "Q8_0", "F16", "Q4_0"). Null = unknown. */
    val kvCacheQuant: String? = null,
    /** On-disk weight quantization (e.g. "Q4_0", "Q4_K_M", "Q8_0"). */
    val weightsQuant: String? = null,
    /** Inference-time weight quantization when different from [weightsQuant]. */
    val inferenceQuant: String? = null,
    /** Precise parameter count, in billions (e.g. 0.6, 1.7, 4.0, 7.0). */
    val paramB: Double? = null,
    /** Approximate on-disk size in MB, shown when available. */
    val sizeMb: Long? = null,
    /** List of chipset codes (e.g. ["SM8650", "SM8750"]) this model is known to run on. */
    val compatDevices: List<String>? = null,
    /** Optional license string (e.g. "Apache 2.0", "MIT", "Gemma terms"). */
    val license: String? = null,
    /** Optional author/publisher (e.g. "Qwen", "Google", "Mistral"). */
    val author: String? = null,
    /** Free-form description shown on the model-detail page. */
    val description: String? = null,
)

/**
 * Compute units offered in the picker dialog for this model. QAIRT is
 * NPU-only; llama.cpp exposes all three.
 */
fun ModelData.getSupportPluginIds(): ArrayList<String> =
    when (runtime) {
        "qairt" -> arrayListOf("npu")
        else -> arrayListOf("npu", "gpu", "cpu")
    }

/** True when the model runs on the QAIRT NPU runtime. */
fun ModelData.isNpuModel(): Boolean = runtime == "qairt"

/** True when this entry points at a local GGUF file instead of a hub model. */
fun ModelData.isLocalModel(): Boolean = !localPath.isNullOrBlank()

/** True when this entry pulls from the HuggingFace mirror (hf-mirror.com). */
fun ModelData.isHfMirror(): Boolean = hub == "HFMIRROR"

/**
 * Human-readable one-liner summarising the model's quantitative
 * properties for the model-management list: "4.0B · Q4_0 · 32k ctx".
 * Used by the model list cells.
 */
fun ModelData.shortSpec(): String {
    val parts = mutableListOf<String>()
    paramB?.let { parts.add(String.format(java.util.Locale.US, "%.1fB", it)) }
    weightsQuant?.let { parts.add(it) }
    maxContext?.let { parts.add("${humanContext(it)} ctx") }
    return parts.joinToString(" · ").ifEmpty { "—" }
}

/** 32768 -> "32k"; 4096 -> "4k"; 1000 -> "1k". */
private fun humanContext(n: Int): String =
    if (n >= 1000) "${n / 1000}k" else n.toString()
