// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 聊天历史持久化。把每条消息序列化为 JSON 写入 filesDir/chat_history.json。
 *
 * 设计：
 *  * 写时机：每次 AI 完成 reply 后追加一条；用户发消息时也追加。
 *  * 读时机：MainActivity.onCreate 时如果 Settings.chatHistoryEnabled 为 true
 *    就加载并刷新到 RecyclerView。
 *  * 关闭时：旧历史文件保留在磁盘上，下次用户重新开启时不丢失。
 *  * 容量上限：保留最近 200 条（防止无限增长把磁盘吃光），溢出时丢老消息。
 */
object ChatHistory {
    private const val TAG = "ChatHistory"
    private const val FILE_NAME = "chat_history.json"
    private const val MAX_ITEMS = 200
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Entry(
        val content: String,
        val type: Int, // MessageType.value
        val images: List<String> = emptyList(),
        val ttftMs: Double? = null,
        val prefillSpeed: Double? = null,
        val decodeSpeed: Double? = null,
        val promptTokens: Long? = null,
        val generatedTokens: Long? = null,
        val stopReason: String? = null,
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 追加一条消息到历史。如果超过 MAX_ITEMS，会丢弃最早的。 */
    fun append(context: Context, entry: Entry) {
        try {
            val current = loadAll(context).toMutableList()
            current.add(entry)
            while (current.size > MAX_ITEMS) current.removeAt(0)
            saveAll(context, current)
        } catch (e: Exception) {
            Log.w(TAG, "append failed: $e")
        }
    }

    fun loadAll(context: Context): List<Entry> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val text = f.readText()
            if (text.isBlank()) return emptyList()
            json.decodeFromString<List<Entry>>(text)
        } catch (e: Exception) {
            Log.w(TAG, "loadAll failed: $e")
            emptyList()
        }
    }

    fun saveAll(context: Context, items: List<Entry>) {
        try {
            val text = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Entry.serializer()),
                items,
            )
            file(context).writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "saveAll failed: $e")
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
