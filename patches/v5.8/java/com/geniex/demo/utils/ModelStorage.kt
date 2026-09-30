// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context
import com.geniex.demo.bean.ModelData
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 模型文件存储位置管理 + 完整性标记（v5.7）。
 *
 *  - internal (默认)：filesDir/local_models
 *  - external：getExternalFilesDir(null)/local_models — /sdcard/Android/data 可访问
 *
 * 完整性标记（v5.7）：
 *  - GGUF 单文件：旁边写一个 `<name>.gguf.yes` 文件作为完成标记
 *  - QAIRT 多文件目录：目录下 `download_complete.yes` 作为完成标记
 *  - 用户也可手动拷贝模型 + 创建 .yes 文件让 app 识别
 *
 * migrate：跨位置迁移用 copy + verify + delete 三步，失败回滚，保证原子性。
 */
object ModelStorage {
    const val LOCATION_INTERNAL = "internal"
    const val LOCATION_EXTERNAL = "external"

    data class MigrateResult(
        val successCount: Int,
        val failedCount: Int,
        val totalBytes: Long,
        val failedFiles: List<String>,
    )

    fun currentLocation(): String = Settings.storageLocation

    fun modelDir(context: Context): File {
        return if (currentLocation() == LOCATION_EXTERNAL) {
            File(context.getExternalFilesDir(null), "local_models")
        } else {
            File(context.filesDir, "local_models")
        }
    }

    fun otherDir(context: Context): File {
        return if (currentLocation() == LOCATION_EXTERNAL) {
            File(context.filesDir, "local_models")
        } else {
            File(context.getExternalFilesDir(null), "local_models")
        }
    }

    fun migrate(context: Context): MigrateResult {
        val from = otherDir(context)
        val to = modelDir(context)
        if (!from.exists() || !from.isDirectory) {
            return MigrateResult(0, 0, 0L, emptyList())
        }
        to.mkdirs()

        val sourceFiles = from.walkTopDown().filter { it.isFile }.toList()
        if (sourceFiles.isEmpty()) {
            from.deleteRecursively()
            return MigrateResult(0, 0, 0L, emptyList())
        }

        val copied = mutableListOf<File>()
        val failed = mutableListOf<String>()
        var totalBytes = 0L

        for (src in sourceFiles) {
            val relPath = src.relativeTo(from).path
            val target = File(to, relPath)
            target.parentFile?.mkdirs()
            try {
                if (src.renameTo(target)) {
                    copied.add(target)
                    totalBytes += target.length()
                    continue
                }
                val tmp = File(target.parentFile, "${target.name}.tmp")
                if (tmp.exists()) tmp.delete()
                copyFile(src, tmp)
                if (!tmp.exists() || tmp.length() != src.length()) {
                    AppLogger.e(TAG, "migrate verify failed: $relPath src=${src.length()} tmp=${tmp.length()}")
                    tmp.delete()
                    failed.add(relPath)
                    continue
                }
                if (!tmp.renameTo(target)) {
                    AppLogger.e(TAG, "migrate rename tmp->target failed: $relPath")
                    tmp.delete()
                    failed.add(relPath)
                    continue
                }
                if (!src.delete()) {
                    AppLogger.w(TAG, "migrate: failed to delete source after copy: $relPath")
                }
                copied.add(target)
                totalBytes += target.length()
            } catch (e: Exception) {
                AppLogger.e(TAG, "migrate exception on $relPath: $e", e)
                failed.add(relPath)
            }
        }

        if (failed.isNotEmpty()) {
            AppLogger.w(TAG, "migrate: ${failed.size} files failed, rolling back ${copied.size} copied files")
            for (t in copied) {
                runCatching { t.delete() }
            }
            return MigrateResult(0, failed.size, 0L, failed)
        }

        runCatching { from.deleteRecursively() }
        AppLogger.i(TAG, "migrate ok: ${copied.size} files, $totalBytes bytes")
        return MigrateResult(copied.size, 0, totalBytes, emptyList())
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
        }
    }

    fun refreshLocalPaths(context: Context, modelList: MutableList<ModelData>) {
        val from = otherDir(context).absolutePath
        val to = modelDir(context).absolutePath
        var changed = 0
        for (i in modelList.indices) {
            val m = modelList[i]
            val lp = m.localPath ?: continue
            if (lp.startsWith(from)) {
                val rel = lp.removePrefix(from).removePrefix("/")
                val newPath = File(to, rel).absolutePath
                modelList[i] = m.copy(localPath = newPath)
                changed++
                AppLogger.i(TAG, "refreshLocalPaths: $lp -> $newPath")
            }
        }
        if (changed > 0) AppLogger.i(TAG, "refreshLocalPaths: updated $changed entries")
    }

    // v5.7 完整性标记
    fun writeGgufCompletion(modelFile: File, modelId: String) {
        val yesFile = File(modelFile.parentFile, "${modelFile.name}.yes")
        yesFile.writeText("download_complete\nmodel_id=$modelId\ndownloaded_at=${System.currentTimeMillis()}\n")
        AppLogger.i(TAG, "wrote ${yesFile.absolutePath}")
    }

    fun writeQairtCompletion(modelDir: File, modelId: String) {
        val yesFile = File(modelDir, "download_complete.yes")
        yesFile.writeText("download_complete\nmodel_id=$modelId\ndownloaded_at=${System.currentTimeMillis()}\n")
        AppLogger.i(TAG, "wrote ${yesFile.absolutePath}")
    }

    fun isGgufComplete(ggufFile: File): Boolean {
        if (!ggufFile.exists()) return false
        val yesFile = File(ggufFile.parentFile, "${ggufFile.name}.yes")
        return yesFile.exists()
    }

    fun isQairtDirComplete(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val yesFile = File(dir, "download_complete.yes")
        if (!yesFile.exists()) return false
        if (!File(dir, "genie_config.json").exists()) return false
        return dir.walkBottomUp().any { it.name.endsWith(".bin") }
    }

    fun sdkModelDir(context: Context): File = File(context.filesDir, "geniex/models")

    private const val TAG = "ModelStorage"
}
