// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context
import java.io.File

/**
 * 模型文件存储位置管理。支持两种位置：
 *
 *  - **internal** (默认)：filesDir/local_models — 应用私有，卸载时一起删除，
 *    不能用文件管理器直接访问，但不需要任何权限。
 *
 *  - **external**：getExternalFilesDir(null)/local_models — 同样应用私有，
 *    但可以通过 USB 文件管理器 / Android/data/<package>/files/ 直接访问。
 *    方便用户从电脑拷大模型进去，也方便检查下载进度。
 *
 * 两种位置都不需要运行时权限（API 19+ AppDirExternal 自动授权）。
 *
 * 切换存储位置时：
 *  - 如果用户选择迁移，调用 [migrate]
 *  - 已下载/导入的模型搬到新位置
 *  - 重新解析 model_list.json 后 localPath 自动指向新位置
 */
object ModelStorage {
    const val LOCATION_INTERNAL = "internal"
    const val LOCATION_EXTERNAL = "external"

    /** 返回当前选定的存储位置。 */
    fun currentLocation(): String = Settings.storageLocation

    /** 返回当前存储位置对应的本地模型目录。 */
    fun modelDir(context: Context): File {
        return if (currentLocation() == LOCATION_EXTERNAL) {
            File(context.getExternalFilesDir(null), "local_models")
        } else {
            File(context.filesDir, "local_models")
        }
    }

    /** 返回另一存储位置对应的目录（用于迁移时读源）。 */
    fun otherDir(context: Context): File {
        return if (currentLocation() == LOCATION_EXTERNAL) {
            File(context.filesDir, "local_models")
        } else {
            File(context.getExternalFilesDir(null), "local_models")
        }
    }

    /** 迁移：把另一位置的模型搬到当前位置。返回 (成功文件数, 总字节数)。 */
    fun migrate(context: Context): Pair<Int, Long> {
        val from = otherDir(context)
        val to = modelDir(context)
        if (!from.exists()) return 0 to 0L
        to.mkdirs()
        var count = 0
        var bytes = 0L
        from.walkBottomUp().forEach { f ->
            if (f.isFile) {
                val target = File(to, f.relativeTo(from).path)
                target.parentFile?.mkdirs()
                if (f.renameTo(target) || f.copyTo(target, overwrite = true).let { f.delete(); true }) {
                    count++
                    bytes += target.length()
                }
            }
        }
        // 删除空源目录
        if (from.walkTopDown().none { it.isFile }) {
            from.deleteRecursively()
        }
        return count to bytes
    }

    /** 返回旧版 SDK 模型目录（geniex）— 仅用于迁移展示。 */
    fun sdkModelDir(context: Context): File = File(context.filesDir, "geniex/models")
}
