// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.geniex.demo.R
import com.geniex.demo.bean.ModelData
import com.geniex.demo.bean.isLocalModel
import com.geniex.demo.bean.shortSpec
import com.geniex.demo.utils.AppLogger
import com.geniex.demo.utils.DeviceInfo
import com.geniex.demo.utils.Settings
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型管理页面。采用 TabLayout + ViewPager2 把模型按 runtime 类型分两栏：
 *
 *   1. GGUF 通用模型 — 不绑定设备，所有兼容 Snapdragon 都能跑（CPU/GPU/NPU 都可）。
 *      此 tab 不显示设备兼容徽章，因为没有"必须匹配"的硬约束。
 *
 *   2. QAIRT NPU 专属 — 预编译 .dlc bundle，强绑定 chipset。不兼容本机的模型
 *      灰显并标红章"不兼容本机"，但仍可点击下载（用户可能在准备换机/迁移数据）。
 *
 * 两个 tab 共用一份 model_list.json + 本地导入列表，只是按 runtime 过滤。
 * 主操作（下载 / 导入 / 删除 / 详情 / Pick）都集中在本 Activity，主聊天界面
 * 不再单独暴露 Download/Import 按钮。
 */
class ModelManagerActivity : AppCompatActivity() {
    private lateinit var tvDevice: TextView
    private lateinit var btnImport: Button
    private lateinit var btnClose: Button
    private lateinit var llLoading: LinearLayout
    private lateinit var tvLoadingTitle: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvLoadingPct: TextView
    private lateinit var btnCancelLoading: Button

    /** 共享给两个 Fragment 的模型列表。Fragment 通过 Activity 拿。 */
    private val modelList: MutableList<ModelData> = mutableListOf()
    private var loadingJob: kotlinx.coroutines.Job? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLocalGguf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)
        setSupportActionBar(findViewById(R.id.toolbar_model_manager))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.model_manager_title)
        }

        tvDevice = findViewById(R.id.tv_device_info)
        btnImport = findViewById(R.id.btn_import)
        btnClose = findViewById(R.id.btn_close)
        llLoading = findViewById(R.id.ll_loading)
        tvLoadingTitle = findViewById(R.id.tv_loading_title)
        pbLoading = findViewById(R.id.pb_loading)
        tvLoadingPct = findViewById(R.id.tv_loading_pct)
        btnCancelLoading = findViewById(R.id.btn_cancel_loading)

        val soc = DeviceInfo.detect()
        tvDevice.text = getString(
            R.string.device_info_fmt,
            soc.marketingName,
            soc.chipset,
            soc.htpVersion,
        )

        // 先加载模型列表
        loadModelList()

        // 设置 ViewPager2 + TabLayout
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.vp_models)
        pager.adapter = ModelsPagerAdapter(this)

        val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tl_models)
        TabLayoutMediator(tabLayout, pager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_gguf)
                else -> getString(R.string.tab_qairt)
            }
        }.attach()

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        btnClose.setOnClickListener { finish() }
        btnCancelLoading.setOnClickListener {
            loadingJob?.cancel()
            loadingJob = null
            hideLoading()
            Toast.makeText(this, R.string.cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // -------------------------------------------------------------------------
    // List management
    // -------------------------------------------------------------------------

    fun getModelList(): List<ModelData> = modelList

    private fun loadModelList() {
        val base = try {
            assets.open("model_list.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "read model_list.json failed: $e")
            "[]"
        }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        modelList.clear()
        modelList.addAll(json.decodeFromString<List<ModelData>>(base))
        scanLocalImports()
    }

    private fun scanLocalImports() {
        val localDir = com.geniex.demo.utils.ModelStorage.modelDir(this)
        if (!localDir.exists()) return
        val existingIds = modelList.map { it.id }.toMutableSet()
        localDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".gguf") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val id = "local-" + file.nameWithoutExtension
                if (id in existingIds) return@forEach
                modelList.add(
                    ModelData(
                        id = id,
                        displayName = "本地：" + file.name,
                        modelName = id,
                        type = "chat",
                        runtime = "llama_cpp",
                        hub = "LOCALFS",
                        localPath = file.absolutePath,
                        author = "用户导入",
                        sizeMb = file.length() / 1024 / 1024,
                    ),
                )
                existingIds.add(id)
            }
    }

    /** 通知两个 Fragment 刷新列表。 */
    fun notifyModelListChanged() {
        supportFragmentManager.fragments.forEach { f ->
            if (f is ModelListFragment) f.refreshList()
        }
    }

    // -------------------------------------------------------------------------
    // Pick result
    // -------------------------------------------------------------------------

    fun returnPicked(model: ModelData) {
        if (!model.isLocalModel()) {
            lifecycleScope.launch {
                val paths = withContext(Dispatchers.IO) {
                    runCatching { ModelManagerWrapper.getPaths(model.modelName) }.getOrNull()
                }
                if (paths == null) {
                    Toast.makeText(this@ModelManagerActivity, R.string.need_download_first, Toast.LENGTH_SHORT).show()
                } else {
                    deliverResult(model)
                }
            }
        } else {
            deliverResult(model)
        }
    }

    private fun deliverResult(model: ModelData) {
        val data = Intent().putExtra(RESULT_MODEL_ID, model.id)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    // -------------------------------------------------------------------------
    // Download path (shared by both tabs)
    // -------------------------------------------------------------------------

    fun startDownload(model: ModelData) {
        if (loadingJob?.isActive == true) {
            Toast.makeText(this, R.string.already_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        if (model.isLocalModel()) {
            val file = File(model.localPath!!)
            Toast
                .makeText(
                    this,
                    getString(R.string.local_ready, file.name),
                    Toast.LENGTH_SHORT,
                )
                .show()
            return
        }
        // 选择下载源：优先 Settings，否则按 model.hub 字段
        val sourcePref = Settings.downloadSource
        // HF 镜像源优先：QAIRT 模型走多文件下载，GGUF 走单文件下载
        val useHfMirror = sourcePref == "hf_mirror" ||
            (sourcePref == "auto" && Settings.useHfMirror &&
                (model.hub == "HUGGINGFACE" || model.hub == "HFMIRROR" || model.hub == "AUTO"))
        if (useHfMirror) {
            startHfMirrorDownload(model)
        } else {
            startGenieXPull(model)
        }
    }

    private fun startGenieXPull(model: ModelData) {
        showLoading(model.displayName)
        val hub = runCatching { HubSource.valueOf(model.hub ?: "AUTO") }
            .getOrDefault(HubSource.AUTO)
        val input = ModelPullInput(
            model_name = model.modelName,
            precision = model.quant,
            hub = hub,
            chipset = model.chipset,
            display_name = model.aiHubDisplayName,
        )
        loadingJob = lifecycleScope.launch {
            try {
                ModelManagerWrapper.pullFlow(input).collect { event ->
                    when (event) {
                        is ModelManagerWrapper.PullEvent.Progress -> {
                            val received = event.files.sumOf { it.downloaded_bytes }
                            val total = event.files.sumOf { it.total_bytes }
                            val pct = if (total > 0) (received * 100 / total).toInt() else -1
                            updateLoadingProgress(pct)
                        }
                        is ModelManagerWrapper.PullEvent.Completed -> {
                            hideLoading()
                            Toast.makeText(this@ModelManagerActivity, getString(R.string.download_complete, model.displayName), Toast.LENGTH_SHORT).show()
                            AppLogger.i(TAG, "download complete: ${model.id}")
                            notifyModelListChanged()
                        }
                        is ModelManagerWrapper.PullEvent.Error -> {
                            hideLoading()
                            Toast.makeText(this@ModelManagerActivity, getString(R.string.download_failed, event.message ?: "code ${event.code}"), Toast.LENGTH_LONG).show()
                            AppLogger.e(TAG, "download failed: ${model.id} code=${event.code} msg=${event.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                hideLoading()
                AppLogger.e(TAG, "pullFlow threw: $e", e)
                Toast.makeText(this@ModelManagerActivity, "下载错误：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startHfMirrorDownload(model: ModelData) {
        val repo = model.modelName
        // QAIRT 模型：下载 repo 中所有文件，包括 .bin 分片、genie_config.json、tokenizer 等
        // GGUF 模型：只下载单个 .gguf 文件
        if (model.runtime == "qairt") {
            startHfMirrorMultiFileDownload(model)
        } else {
            startHfMirrorSingleFileDownload(model)
        }
    }

    /** GGUF 单文件下载 — 旧逻辑。 */
    private fun startHfMirrorSingleFileDownload(model: ModelData) {
        val repo = model.modelName
        val quantSuffix = model.quant?.lowercase()?.replace(".", "_") ?: "q4_0"
        val candidate = "${repo.substringAfterLast('/').replace("-GGUF", "")}-$quantSuffix.gguf"
        val urlStr = "https://hf-mirror.com/$repo/resolve/main/$candidate"

        showLoading(model.displayName)
        val targetDir = com.geniex.demo.utils.ModelStorage.modelDir(this).apply { mkdirs() }
        val target = File(targetDir, candidate)

        loadingJob = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                downloadFile(urlStr, target) { received, total ->
                    val pct = if (total > 0) (received * 100 / total).toInt() else -1
                    runOnUiThread { updateLoadingProgress(pct) }
                }
            }
            hideLoading()
            if (ok && target.exists() && target.length() > 0) {
                val newId = "local-" + target.nameWithoutExtension
                modelList.removeAll { it.id == newId }
                modelList.add(
                    ModelData(
                        id = newId,
                        displayName = "本地：" + target.name,
                        modelName = newId,
                        type = model.type,
                        runtime = "llama_cpp",
                        hub = "LOCALFS",
                        localPath = target.absolutePath,
                        author = model.author,
                        paramB = model.paramB,
                        maxContext = model.maxContext,
                        weightsQuant = model.weightsQuant,
                        compatDevices = model.compatDevices,
                        sizeMb = target.length() / 1024 / 1024,
                    ),
                )
                notifyModelListChanged()
                Toast
                    .makeText(
                        this@ModelManagerActivity,
                        getString(R.string.download_complete_mb, target.name, target.length() / 1024 / 1024),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                AppLogger.i(TAG, "hf-mirror download ok: ${target.absolutePath}")
            } else {
                target.delete()
                Toast.makeText(this@ModelManagerActivity, getString(R.string.hf_mirror_hint, urlStr), Toast.LENGTH_LONG).show()
                AppLogger.w(TAG, "hf-mirror download failed: $urlStr")
            }
        }
    }

    /**
     * QAIRT 多文件下载：通过 HF API 列出 repo 所有文件，全部下载到
     * filesDir/local_models/<model_id>/，然后用 ModelManagerWrapper.pullFlow
     * 配合 hub=LOCALFS 注册模型（SDK 读 genie_config.json）。
     */
    private fun startHfMirrorMultiFileDownload(model: ModelData) {
        val repo = model.modelName
        val modelDirId = model.id
        val targetDir = File(com.geniex.demo.utils.ModelStorage.modelDir(this), modelDirId).apply { mkdirs() }

        showLoading(model.displayName)

        loadingJob = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // 1. 列出 repo 所有文件
                val listUrl = "https://hf-mirror.com/api/models/$repo"
                val listJson = runCatching {
                    val conn = (java.net.URL(listUrl).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 30_000
                        readTimeout = 60_000
                    }
                    val status = conn.responseCode
                    if (status != 200) {
                        AppLogger.w(TAG, "HF API HTTP $status for $listUrl")
                        return@withContext false
                    }
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    text
                }.getOrNull() ?: return@withContext false

                // 解析 siblings 文件名列表
                val files: List<String> = runCatching {
                    val json = org.json.JSONObject(listJson)
                    val arr = json.getJSONArray("siblings")
                    (0 until arr.length()).map { arr.getJSONObject(it).getString("rfilename") }
                }.getOrNull().orEmpty()
                if (files.isEmpty()) {
                    AppLogger.w(TAG, "repo $repo 无文件")
                    return@withContext false
                }
                AppLogger.i(TAG, "QAIRT 模型 ${model.id} 待下载 ${files.size} 个文件: $files")

                // 2. 下载每个文件（跳过 .gitattributes）
                var totalBytes = 0L
                var totalExpected = 0L
                var fileIndex = 0
                for (filename in files) {
                    if (filename == ".gitattributes") continue
                    val target = File(targetDir, filename)
                    // 跳过已存在且大小相符的文件（断点续传）
                    if (target.exists() && target.length() > 0) {
                        AppLogger.i(TAG, "跳过已存在：$filename")
                        totalBytes += target.length()
                        fileIndex++
                        continue
                    }
                    target.parentFile?.mkdirs()
                    val url = "https://hf-mirror.com/$repo/resolve/main/$filename"
                    val ok = downloadFile(url, target) { received, total ->
                        // 进度按文件计
                        val pct = if (total > 0) (received * 100 / total).toInt() else -1
                        runOnUiThread {
                            if (pct >= 0) {
                                updateLoadingProgress(pct)
                                tvLoadingTitle.text = "(${fileIndex + 1}/${files.size}) $filename"
                            } else {
                                updateLoadingProgress(-1)
                            }
                        }
                    }
                    if (!ok) {
                        AppLogger.w(TAG, "下载失败：$filename")
                        return@withContext false
                    }
                    totalBytes += target.length()
                    fileIndex++
                }

                // 3. 注册到 ModelManagerWrapper
                AppLogger.i(TAG, "全部下载完成，注册模型 model_name=${model.modelName} local_path=${targetDir.absolutePath}")
                try {
                    val pullInput = com.geniex.sdk.bean.ModelPullInput(
                        model_name = model.modelName,
                        precision = model.quant,
                        hub = com.geniex.sdk.bean.HubSource.LOCALFS,
                        local_path = targetDir.absolutePath,
                        chipset = model.chipset,
                        display_name = model.displayName,
                    )
                    kotlinx.coroutines.runBlocking {
                        ModelManagerWrapper.pullFlow(pullInput).collect { ev ->
                            when (ev) {
                                is ModelManagerWrapper.PullEvent.Completed -> {
                                    AppLogger.i(TAG, "QAIRT 注册成功")
                                }
                                is ModelManagerWrapper.PullEvent.Error -> {
                                    AppLogger.e(TAG, "QAIRT 注册失败: code=${ev.code} msg=${ev.message}")
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "pullFlow LOCALFS 异常: $e", e)
                }
                true
            }
            hideLoading()
            if (ok) {
                Toast.makeText(this@ModelManagerActivity, getString(R.string.download_complete, model.displayName), Toast.LENGTH_SHORT).show()
                AppLogger.i(TAG, "QAIRT 下载完成：${model.id}")
            } else {
                Toast.makeText(this@ModelManagerActivity, "QAIRT 模型下载失败，请查看日志", Toast.LENGTH_LONG).show()
                AppLogger.w(TAG, "QAIRT 模型下载失败：${model.id}")
            }
            notifyModelListChanged()
        }
    }

    private fun downloadFile(
        urlStr: String,
        target: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Boolean {
        return runCatching {
            var url = URL(urlStr)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                }
                val status = conn.responseCode
                if (status in 300..399 && redirects < 10) {
                    val loc = conn.getHeaderField("Location") ?: return false
                    url = URL(url, loc)
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (status != 200) {
                    AppLogger.w(TAG, "download $urlStr HTTP $status")
                    conn.disconnect()
                    return false
                }
                break
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
            }
            conn.disconnect()
            true
        }.getOrElse {
            AppLogger.e(TAG, "download exception: $it", it)
            false
        }
    }

    // -------------------------------------------------------------------------
    // Local import
    // -------------------------------------------------------------------------

    private fun importLocalGguf(uri: Uri) {
        val displayName =
            runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment ?: "local-model.gguf"

        val lower = displayName.lowercase()
        if (!lower.endsWith(".gguf") && !lower.contains(".gguf")) {
            Toast.makeText(this, R.string.only_gguf, Toast.LENGTH_LONG).show()
            return
        }
        val targetDir = com.geniex.demo.utils.ModelStorage.modelDir(this).apply { mkdirs() }
        var target = File(targetDir, displayName)
        if (target.exists()) {
            val base = target.nameWithoutExtension
            var n = 1
            while (target.exists()) {
                target = File(targetDir, "$base ($n).gguf")
                n++
            }
        }
        showLoading(displayName)
        loadingJob = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: false
                }.getOrNull() == true
            }
            hideLoading()
            if (ok && target.exists() && target.length() > 0) {
                val newId = "local-" + target.nameWithoutExtension
                modelList.removeAll { it.id == newId }
                modelList.add(
                    ModelData(
                        id = newId,
                        displayName = "本地：" + target.name,
                        modelName = newId,
                        type = "chat",
                        runtime = "llama_cpp",
                        hub = "LOCALFS",
                        localPath = target.absolutePath,
                        author = "用户导入",
                        sizeMb = target.length() / 1024 / 1024,
                    ),
                )
                notifyModelListChanged()
                Toast
                    .makeText(
                        this@ModelManagerActivity,
                        getString(R.string.imported_mb, target.name, target.length() / 1024 / 1024),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                AppLogger.i(TAG, "import ok: ${target.absolutePath}")
            } else {
                Toast.makeText(this@ModelManagerActivity, R.string.import_failed, Toast.LENGTH_LONG).show()
                AppLogger.w(TAG, "import failed: $displayName")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    fun deleteModel(model: ModelData) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.confirm_delete_msg, model.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (model.isLocalModel()) {
                    val f = File(model.localPath!!)
                    if (f.exists() && f.delete()) {
                        modelList.remove(model)
                        notifyModelListChanged()
                        Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
                        AppLogger.i(TAG, "deleted local: ${f.absolutePath}")
                    } else {
                        Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    lifecycleScope.launch {
                        val n = withContext(Dispatchers.IO) {
                            runCatching { ModelManagerWrapper.remove(model.modelName) }.getOrNull() ?: -1
                        }
                        if (n >= 0) {
                            Toast.makeText(this@ModelManagerActivity, R.string.deleted, Toast.LENGTH_SHORT).show()
                            AppLogger.i(TAG, "deleted hub: ${model.modelName}")
                        } else {
                            Toast.makeText(this@ModelManagerActivity, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    // -------------------------------------------------------------------------
    // Detail
    // -------------------------------------------------------------------------

    fun showDetail(model: ModelData) {
        val sb = StringBuilder()
        sb.append(getString(R.string.field_id)).append(": ").append(model.id).append('\n')
        sb.append(getString(R.string.field_source)).append(": ").append(model.hub ?: "AUTO").append('\n')
        sb.append(getString(R.string.field_runtime)).append(": ").append(model.runtime ?: "—").append('\n')
        sb.append(getString(R.string.field_type)).append(": ").append(model.type ?: "—").append('\n')
        if (model.author != null) sb.append(getString(R.string.field_author)).append(": ").append(model.author).append('\n')
        if (model.license != null) sb.append(getString(R.string.field_license)).append(": ").append(model.license).append('\n')
        if (model.paramB != null) sb.append(getString(R.string.field_params)).append(": ").append(model.paramB).append("B\n")
        if (model.weightsQuant != null) sb.append(getString(R.string.field_weights_quant)).append(": ").append(model.weightsQuant).append('\n')
        if (model.kvCacheQuant != null) sb.append(getString(R.string.field_kv_quant)).append(": ").append(model.kvCacheQuant).append('\n')
        if (model.inferenceQuant != null) sb.append(getString(R.string.field_inference_quant)).append(": ").append(model.inferenceQuant).append('\n')
        if (model.maxContext != null) sb.append(getString(R.string.field_max_context)).append(": ").append(model.maxContext).append(" ").append(getString(R.string.tokens_unit)).append('\n')
        if (model.sizeMb != null) sb.append(getString(R.string.field_size)).append(": ").append(model.sizeMb).append(" MB\n")
        if (model.chipset != null) sb.append(getString(R.string.field_chipset)).append(": ").append(model.chipset).append('\n')
        if (!model.compatDevices.isNullOrEmpty()) {
            sb.append(getString(R.string.field_compat_devices)).append(": ").append(model.compatDevices.joinToString(", ")).append('\n')
        }
        if (model.isLocalModel()) {
            sb.append(getString(R.string.field_local_path)).append(": ").append(model.localPath).append('\n')
            val f = File(model.localPath!!)
            if (f.exists()) {
                sb.append(getString(R.string.field_file_size)).append(": ").append(Formatter.formatFileSize(this, f.length())).append('\n')
            }
        }
        if (model.description != null) {
            sb.append('\n').append(getString(R.string.field_description)).append(":\n").append(model.description).append('\n')
        }
        AlertDialog.Builder(this).setTitle(model.displayName).setMessage(sb.toString()).setPositiveButton(android.R.string.ok, null).show()
    }

    // -------------------------------------------------------------------------
    // Loading UI helpers
    // -------------------------------------------------------------------------

    private fun showLoading(title: String) {
        tvLoadingTitle.text = getString(R.string.downloading_x, title)
        pbLoading.progress = 0
        tvLoadingPct.text = "0%"
        llLoading.visibility = View.VISIBLE
    }

    private fun updateLoadingProgress(pct: Int) {
        if (pct < 0) {
            pbLoading.isIndeterminate = true
            tvLoadingPct.text = "…"
        } else {
            pbLoading.isIndeterminate = false
            pbLoading.progress = pct
            tvLoadingPct.text = "$pct%"
        }
    }

    private fun hideLoading() {
        llLoading.visibility = View.GONE
    }

    // -------------------------------------------------------------------------
    // Pager + Fragment
    // -------------------------------------------------------------------------

    private inner class ModelsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = ModelListFragment.newInstance(
            filter = if (position == 0) "llama_cpp" else "qairt",
        )
    }

    /**
     * 单 tab 的模型列表 Fragment。持有自己的 RecyclerView 和 adapter，
     * 但模型数据从 [ModelManagerActivity.getModelList] 拿（保证和导入/下载后状态同步）。
     */
    class ModelListFragment : Fragment() {
        private lateinit var rv: RecyclerView
        private lateinit var adapter: ModelAdapter
        private val filtered = mutableListOf<ModelData>()
        private var filter: String = "llama_cpp"

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            filter = arguments?.getString("filter") ?: "llama_cpp"
            val v = inflater.inflate(R.layout.fragment_model_list, container, false)
            rv = v.findViewById(R.id.rv_models_fragment)
            rv.layoutManager = LinearLayoutManager(requireContext())
            adapter = ModelAdapter(
                models = filtered,
                localChipset = com.geniex.demo.utils.DeviceInfo.detect().chipset,
                onPick = { model -> (requireActivity() as ModelManagerActivity).returnPicked(model) },
                onDownload = { model -> (requireActivity() as ModelManagerActivity).startDownload(model) },
                onDelete = { model -> (requireActivity() as ModelManagerActivity).deleteModel(model) },
                onShowDetail = { model -> (requireActivity() as ModelManagerActivity).showDetail(model) },
                filter = filter,
            )
            rv.adapter = adapter
            refreshList()
            return v
        }

        fun refreshList() {
            if (!isAdded) return
            val act = requireActivity() as? ModelManagerActivity ?: return
            val source = act.getModelList()
            filtered.clear()
            filtered.addAll(source.filter { it.runtime == filter })
            adapter.notifyDataSetChanged()
        }

        companion object {
            fun newInstance(filter: String): ModelListFragment {
                return ModelListFragment().apply {
                    arguments = Bundle().apply { putString("filter", filter) }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private class ModelAdapter(
        private val models: List<ModelData>,
        private val localChipset: String,
        private val onPick: (ModelData) -> Unit,
        private val onDownload: (ModelData) -> Unit,
        private val onDelete: (ModelData) -> Unit,
        private val onShowDetail: (ModelData) -> Unit,
        private val filter: String,
    ) : RecyclerView.Adapter<ModelAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tv_model_name)
            val tvSpec: TextView = v.findViewById(R.id.tv_model_spec)
            val tvStatus: TextView = v.findViewById(R.id.tv_model_status)
            val ivCompat: ImageView = v.findViewById(R.id.iv_compat_badge)
            val tvIncompat: TextView = v.findViewById(R.id.tv_incompat_badge)
            val btnPick: Button = v.findViewById(R.id.btn_pick)
            val btnDownload: Button = v.findViewById(R.id.btn_download_model)
            val btnDelete: Button = v.findViewById(R.id.btn_delete_model)
            val btnDetail: Button = v.findViewById(R.id.btn_detail)
            val itemViewRoot: View = v.findViewById(R.id.ll_model_item_root)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_manager, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = models[position]
            h.tvName.text = m.displayName
            h.tvSpec.text = m.shortSpec()

            // GGUF tab：不显示设备徽章（通用）
            // QAIRT tab：检查 chipset 匹配，不兼容则灰显 + 标红章
            if (filter == "qairt") {
                val isCompat = m.chipset.isNullOrBlank() ||
                    m.compatDevices?.any { it.equals(localChipset, ignoreCase = true) } != false
                h.ivCompat.visibility = if (isCompat) View.VISIBLE else View.GONE
                h.tvIncompat.visibility = if (isCompat) View.GONE else View.VISIBLE
                h.itemViewRoot.alpha = if (isCompat) 1.0f else 0.5f
            } else {
                h.ivCompat.visibility = View.GONE
                h.tvIncompat.visibility = View.GONE
                h.itemViewRoot.alpha = 1.0f
            }

            // 状态文本：本地 vs hub
            if (m.isLocalModel()) {
                val f = File(m.localPath!!)
                val ctx = h.itemView.context
                h.tvStatus.text = if (f.exists()) ctx.getString(R.string.ready_local) else ctx.getString(R.string.file_missing)
                h.btnDownload.visibility = View.GONE
                h.btnDelete.visibility = View.VISIBLE
            } else {
                h.tvStatus.text = "来源：${m.hub ?: "AUTO"}"
                h.btnDownload.visibility = View.VISIBLE
                h.btnDelete.visibility = View.GONE
            }

            h.btnPick.setOnClickListener { onPick(m) }
            h.btnDownload.setOnClickListener { onDownload(m) }
            h.btnDelete.setOnClickListener { onDelete(m) }
            h.btnDetail.setOnClickListener { onShowDetail(m) }
            h.itemView.setOnClickListener { onShowDetail(m) }
        }

        override fun getItemCount(): Int = models.size
    }

    companion object {
        const val RESULT_MODEL_ID = "result_model_id"
        private const val TAG = "ModelManager"
    }
}
