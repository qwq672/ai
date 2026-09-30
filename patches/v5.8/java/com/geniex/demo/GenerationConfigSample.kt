// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import com.geniex.demo.utils.Settings
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.SamplerConfig

// Configuration sample for generation with defaults compatible with bridge
// maxTokens: 0 = no limit, generates until model's natural stopping point
//
// All sampler fields now pull from [Settings] (the user's preference store)
// so the SettingsActivity sliders take effect on the very next reply without
// restarting the app or reloading the model.
data class GenerationConfigSample(
    var maxTokens: Int = 0,
    var stopWords: List<String>? = null,
    var stopCount: Int = 0,
    var nPast: Int = 0,
    var imagePaths: List<String>? = null,
    var imageCount: Int = 0,
    var audioPaths: List<String>? = null,
    var audioCount: Int = 0,
) {
    fun toGenerationConfig(): GenerationConfig {
        val sampler = SamplerConfig(
            temperature = Settings.temperature,
            topP = Settings.topP,
            topK = Settings.topK,
            minP = 0f,
            repetitionPenalty = Settings.repetitionPenalty,
            presencePenalty = 0f,
            frequencyPenalty = 0f,
            seed = -1,
            grammarPath = null,
            grammarString = null,
        )
        return GenerationConfig(
            maxTokens = Settings.maxTokens,
            stopWords = stopWords?.toTypedArray(),
            stopCount = stopCount,
            nPast = nPast,
            samplerConfig = sampler,
            imagePaths = imagePaths?.toTypedArray(),
            imageCount = imageCount,
            audioPaths = audioPaths?.toTypedArray(),
            audioCount = audioCount,
        )
    }
}
