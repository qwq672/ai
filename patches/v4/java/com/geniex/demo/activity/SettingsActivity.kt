// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.geniex.demo.BuildConfig
import com.geniex.demo.R
import com.geniex.demo.utils.AppLogger
import com.geniex.demo.utils.ChatHistory
import com.geniex.demo.utils.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页面。基于 PreferenceFragmentCompat，所有偏好都直接写入默认
 * SharedPreferences，Settings 单例封装了读写。
 *
 * 旁路效应（Settings 静态访问器做不到的）：
 *  * 日志开关实时调 AppLogger.setEnabled
 *  * 清除模型缓存：删除 filesDir/geniex + filesDir/local_models
 *  * 清空聊天历史：删除 chat_history.json
 *  * 版本号点击展示构建详情
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar_settings))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.settings_title)
        }
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // ---------- 日志 ----------
            findPreference<SwitchPreferenceCompat>(AppLogger.KEY_LOG_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
                Settings.loggingEnabled = newValue as Boolean
                true
            }
            findPreference<Preference>("share_log")?.setOnPreferenceClickListener {
                val path = AppLogger.logFilePath()
                if (path == null) {
                    Toast.makeText(requireContext(), R.string.log_empty, Toast.LENGTH_SHORT).show()
                } else {
                    val uri = Uri.parse("file://$path")
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "GenieX 聊天 app.log")
                    }
                    startActivity(Intent.createChooser(share, getString(R.string.share_log)))
                }
                true
            }
            findPreference<Preference>("clear_log")?.setOnPreferenceClickListener {
                val path = AppLogger.logFilePath()
                if (path != null) {
                    File(path).writeText("")
                    Toast.makeText(requireContext(), R.string.log_cleared, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.log_empty, Toast.LENGTH_SHORT).show()
                }
                true
            }

            // ---------- NPU ----------
            findPreference<ListPreference>(Settings.KEY_NPU_POWER_MODE)?.apply {
                summary = entry
                setOnPreferenceChangeListener { pref, newValue ->
                    val idx = findIndexOfValue(newValue.toString())
                    pref.summary = entries[idx]
                    Settings.npuPowerMode = newValue.toString()
                    true
                }
            }
            findPreference<ListPreference>(Settings.KEY_CPU_THREADS_MODE)?.apply {
                summary = entry
                setOnPreferenceChangeListener { pref, newValue ->
                    val idx = findIndexOfValue(newValue.toString())
                    pref.summary = entries[idx]
                    Settings.cpuThreadsMode = newValue.toString()
                    true
                }
            }
            findPreference<ListPreference>(Settings.KEY_DOWNLOAD_SOURCE)?.apply {
                summary = entry
                setOnPreferenceChangeListener { pref, newValue ->
                    val idx = findIndexOfValue(newValue.toString())
                    pref.summary = entries[idx]
                    Settings.downloadSource = newValue.toString()
                    true
                }
            }

            // ---------- 下载 ----------
            findPreference<Preference>("clear_models")?.setOnPreferenceClickListener {
                confirmClearModels()
                true
            }
            findPreference<Preference>("disk_usage")?.setOnPreferenceClickListener {
                showDiskUsage()
                true
            }

            // ---------- UI ----------
            findPreference<Preference>("clear_history_now")?.setOnPreferenceClickListener {
                ChatHistory.clear(requireContext())
                Toast.makeText(requireContext(), R.string.toast_history_cleared, Toast.LENGTH_SHORT).show()
                true
            }

            // ---------- 关于 ----------
            findPreference<Preference>("version")?.apply {
                val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                summary = version
                setOnPreferenceClickListener {
                    AlertDialog
                        .Builder(requireContext())
                        .setTitle(R.string.pref_version)
                        .setMessage(
                            """
                            版本：$version
                            包名：${requireContext().packageName}
                            构建：${com.geniex.demo.BuildConfig.DEBUG.let { if (it) "Debug" else "Release" }}

                            源：qualcomm/ai-hub-apps@release (v0.37.2, commit 24bc31c)
                            GenieX SDK：com.qualcomm.qti:geniex-android:0.3.5
                            """.trimIndent(),
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    true
                }
            }

            // 实时更新 SeekBar summary
            listOf(
                Settings.KEY_TEMPERATURE to "温度",
                Settings.KEY_TOP_P to "Top-P",
                Settings.KEY_TOP_K to "Top-K",
                Settings.KEY_REPETITION_PENALTY to "重复惩罚",
                Settings.KEY_MAX_TOKENS to "最大生成长度",
                Settings.KEY_VTCM_MB to "VTCM",
                Settings.KEY_CPU_THREADS to "CPU 线程数",
            ).forEach { (key, label) ->
                findPreference<SeekBarPreference>(key)?.let { sp ->
                    sp.summary = "$label: ${sp.value}"
                    sp.setOnPreferenceChangeListener { pref, newValue ->
                        (pref as SeekBarPreference).summary = "$label: $newValue"
                        true
                    }
                }
            }
        }

        private fun confirmClearModels() {
            AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.pref_clear_models)
                .setMessage(getString(R.string.confirm_delete_msg, "所有已下载模型"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch {
                        val result: Pair<Int, Int> = withContext(Dispatchers.IO) {
                            clearDownloadedModelsBlocking()
                        }
                        Toast
                            .makeText(
                                requireContext(),
                                getString(R.string.toast_models_cleared, result.first, result.second),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
                .show()
        }

        private fun clearDownloadedModelsBlocking(): Pair<Int, Int> {
            val ctx = requireContext().applicationContext
            var count = 0
            var bytes = 0L
            // GenieX 拉取的模型
            val geniexDir = File(ctx.filesDir, "geniex")
            if (geniexDir.exists()) {
                geniexDir.walkBottomUp().forEach { f ->
                    if (f.isFile) { count++; bytes += f.length() }
                }
                geniexDir.deleteRecursively()
            }
            // 本地导入的 GGUF
            val localDir = File(ctx.filesDir, "local_models")
            if (localDir.exists()) {
                localDir.walkBottomUp().forEach { f ->
                    if (f.isFile) { count++; bytes += f.length() }
                }
                localDir.deleteRecursively()
            }
            // 让 SDK 也清理其缓存
            runCatching {
                kotlinx.coroutines.runBlocking {
                    com.geniex.sdk.ModelManagerWrapper.clean()
                }
            }
            return count to (bytes / 1024 / 1024).toInt()
        }

        private fun showDiskUsage() {
            val ctx = requireContext().applicationContext
            val geniex = dirSize(File(ctx.filesDir, "geniex"))
            val local = dirSize(File(ctx.filesDir, "local_models"))
            val log = dirSize(File(ctx.getExternalFilesDir(null), "app.log"))
            val total = geniex + local + log
            AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.pref_disk_usage)
                .setMessage(
                    """
                    已下载模型（SDK）：${human(geniex)}
                    本地导入 GGUF：${human(local)}
                    日志文件：${human(log)}
                    
                    总占用：${human(total)}
                    """.trimIndent(),
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun dirSize(dir: File): Long {
            if (!dir.exists()) return 0
            return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }

        private fun human(bytes: Long): String =
            when {
                bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
                bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024)
                bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
    }
}
