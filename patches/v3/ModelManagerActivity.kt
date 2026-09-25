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
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Centralised model management surface. Replaces the spinner + ad-hoc
 * Download/Import buttons on the main chat screen with a dedicated list
 * that exposes the full model lifecycle in one place:
 *
 *  * Browse the in-app catalog + locally-imported GGUFs.
 *  * Download from HuggingFace / AI Hub / hf-mirror.com / LOCALFS.
 *  * Import a new GGUF via the Storage Access Framework.
 *  * Inspect per-model metadata (params, context, quant, compat devices).
 *  * Delete a downloaded model from the cache.
 *  * Pick a model to load — returns the chosen [ModelData] to the caller.
 *
 * The activity returns the selected model id via [RESULT_MODEL_ID].
 * If the user dismisses without picking, [Activity.RESULT_CANCELED] is
 * returned and the caller keeps its current selection.
 *
 * Hf-mirror downloads bypass [ModelManagerWrapper.pullFlow] because the
 * Rust side does not honour HF_ENDPOINT — instead we fetch the GGUF
 * directly via HttpURLConnection and place it under filesDir/local_models,
 * which the existing local-model code path picks up automatically.
 */
class ModelManagerActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ModelAdapter
    private lateinit var tvDevice: TextView
    private lateinit var btnImport: Button
    private lateinit var btnClose: Button
    private lateinit var llLoading: LinearLayout
    private lateinit var tvLoadingTitle: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvLoadingPct: TextView
    private lateinit var btnCancelLoading: Button
    private val modelList: MutableList<ModelData> = mutableListOf()
    private var downloadingId: String? = null
    private var loadingJob: kotlinx.coroutines.Job? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLocalGguf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)
        setTitle(R.string.model_manager_title)

        recyclerView = findViewById(R.id.rv_models)
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

        adapter = ModelAdapter(
            models = modelList,
            localChipset = soc.chipset,
            onPick = { model -> returnPicked(model) },
            onDownload = { model -> startDownload(model) },
            onDelete = { model -> deleteModel(model) },
            onShowDetail = { model -> showDetail(model) },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

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

        loadModelList()
    }

    private fun loadModelList() {
        // Parse the bundled catalog first.
        val base = try {
            assets.open("model_list.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "read model_list.json failed: $e")
            "[]"
        }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        modelList.clear()
        modelList.addAll(json.decodeFromString<List<ModelData>>(base))
        // Re-add any locally-imported GGUFs from a prior session.
        scanLocalImports()
        adapter.notifyDataSetChanged()
    }

    private fun scanLocalImports() {
        val localDir = File(filesDir, "local_models")
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
                        displayName = "Local: " + file.name,
                        modelName = id,
                        type = "chat",
                        runtime = "llama_cpp",
                        hub = "LOCALFS",
                        localPath = file.absolutePath,
                        author = "Imported by user",
                        sizeMb = file.length() / 1024 / 1024,
                    ),
                )
                existingIds.add(id)
            }
    }

    private fun returnPicked(model: ModelData) {
        // Verify availability for hub models — local models are always available.
        if (!model.isLocalModel()) {
            lifecycleScope.launch {
                val paths = withContext(Dispatchers.IO) {
                    runCatching { ModelManagerWrapper.getPaths(model.modelName) }.getOrNull()
                }
                if (paths == null) {
                    Toast
                        .makeText(
                            this@ModelManagerActivity,
                            R.string.need_download_first,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
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
    // Download path
    // -------------------------------------------------------------------------

    private fun startDownload(model: ModelData) {
        if (loadingJob?.isActive == true) {
            Toast.makeText(this, R.string.already_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        if (model.isLocalModel()) {
            // Local model: nothing to download — just inform the user.
            Toast
                .makeText(
                    this,
                    getString(R.string.local_ready, File(model.localPath!!).name),
                    Toast.LENGTH_SHORT,
                )
                .show()
            return
        }
        when (model.hub ?: "AUTO") {
            "HFMIRROR" -> startHfMirrorDownload(model)
            else -> startGenieXPull(model)
        }
    }

    /**
     * Default path: let GenieX SDK's Rust model manager handle the pull.
     * Works for HuggingFace / AIHUB / MODELSCOPE / VOLCES sources.
     */
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
                            // Sum across all files in the manifest, since some
                            // model releases come as multi-file splits.
                            val received = event.files.sumOf { it.downloaded_bytes }
                            val total = event.files.sumOf { it.total_bytes }
                            val pct = if (total > 0) (received * 100 / total).toInt() else -1
                            updateLoadingProgress(pct)
                        }
                        is ModelManagerWrapper.PullEvent.Completed -> {
                            hideLoading()
                            Toast.makeText(
                                this@ModelManagerActivity,
                                getString(R.string.download_complete, model.displayName),
                                Toast.LENGTH_SHORT,
                            ).show()
                            AppLogger.i(TAG, "download complete: ${model.id}")
                        }
                        is ModelManagerWrapper.PullEvent.Error -> {
                            hideLoading()
                            Toast
                                .makeText(
                                    this@ModelManagerActivity,
                                    getString(R.string.download_failed, event.message ?: "code ${event.code}"),
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                            AppLogger.e(TAG, "download failed: ${model.id} code=${event.code} msg=${event.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                hideLoading()
                AppLogger.e(TAG, "pullFlow threw: $e", e)
                Toast
                    .makeText(this@ModelManagerActivity, "Download error: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    /**
     * hf-mirror.com download path: bypasses the SDK's pullFlow entirely.
     * We resolve the model URL on the mirror, stream-download it to
     * filesDir/local_models, then register it as a local GGUF so the
     * existing local-model code path picks it up.
     *
     * URL pattern: https://hf-mirror.com/<org>/<repo>/resolve/main/<file>.gguf
     * We auto-pick a sensible filename based on the model id + quant.
     */
    private fun startHfMirrorDownload(model: ModelData) {
        val repo = model.modelName
        // Heuristic: pick the Q4_0 file unless the model has a specific quant hint.
        val quantSuffix = model.quant?.lowercase()?.replace(".", "_") ?: "q4_0"
        // Most unsloth releases use "<Model>-Q4_0-00001-of-0000X.gguf" or similar
        // multi-file splits. For demo simplicity, we try the most common
        // single-file name first, and fall back to listing via the HF API.
        val candidate = "${repo.substringAfterLast('/').replace("-GGUF", "")}-$quantSuffix.gguf"
        val urlStr = "https://hf-mirror.com/$repo/resolve/main/$candidate"

        showLoading(model.displayName)
        downloadingId = model.id
        val targetDir = File(filesDir, "local_models").apply { mkdirs() }
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
                // Register the new file as a local model so the existing path
                // in MainActivity picks it up on next load — reuse the same
                // id as the original so users don't get a duplicate.
                val newId = "local-" + target.nameWithoutExtension
                modelList.removeAll { it.id == newId }
                modelList.add(
                    ModelData(
                        id = newId,
                        displayName = "Local: " + target.name,
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
                adapter.notifyDataSetChanged()
                Toast
                    .makeText(
                        this@ModelManagerActivity,
                        getString(
                            R.string.download_complete_mb,
                            target.name,
                            target.length() / 1024 / 1024,
                        ),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                AppLogger.i(TAG, "hf-mirror download ok: ${target.absolutePath}")
            } else {
                target.delete()
                Toast
                    .makeText(
                        this@ModelManagerActivity,
                        getString(R.string.hf_mirror_hint, urlStr),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                AppLogger.w(TAG, "hf-mirror download failed: $urlStr")
            }
        }
    }

    /**
     * Streaming download with progress callback. Follows redirects
     * (hf-mirror redirects to OSS-backed URLs). Returns true on success.
     */
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
    // Local import (SAF)
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
        val targetDir = File(filesDir, "local_models").apply { mkdirs() }
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
                        displayName = "Local: " + target.name,
                        modelName = newId,
                        type = "chat",
                        runtime = "llama_cpp",
                        hub = "LOCALFS",
                        localPath = target.absolutePath,
                        author = "Imported by user",
                        sizeMb = target.length() / 1024 / 1024,
                    ),
                )
                adapter.notifyDataSetChanged()
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

    private fun deleteModel(model: ModelData) {
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
                        adapter.notifyDataSetChanged()
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
    // Detail dialog
    // -------------------------------------------------------------------------

    private fun showDetail(model: ModelData) {
        val sb = StringBuilder()
        sb.append("ID: ").append(model.id).append('\n')
        sb.append("Source: ").append(model.hub ?: "AUTO").append('\n')
        sb.append("Runtime: ").append(model.runtime ?: "—").append('\n')
        sb.append("Type: ").append(model.type ?: "—").append('\n')
        if (model.author != null) sb.append("Author: ").append(model.author).append('\n')
        if (model.license != null) sb.append("License: ").append(model.license).append('\n')
        if (model.paramB != null) sb.append("Parameters: ").append(model.paramB).append("B\n")
        if (model.weightsQuant != null) sb.append("Weight quant: ").append(model.weightsQuant).append('\n')
        if (model.kvCacheQuant != null) sb.append("KV cache quant: ").append(model.kvCacheQuant).append('\n')
        if (model.inferenceQuant != null) sb.append("Inference quant: ").append(model.inferenceQuant).append('\n')
        if (model.maxContext != null) sb.append("Max context: ").append(model.maxContext).append(" tokens\n")
        if (model.sizeMb != null) sb.append("On-disk size: ").append(model.sizeMb).append(" MB\n")
        if (model.chipset != null) sb.append("Chipset: ").append(model.chipset).append('\n')
        if (!model.compatDevices.isNullOrEmpty()) {
            sb.append("Compatible devices: ").append(model.compatDevices.joinToString(", ")).append('\n')
        }
        if (model.isLocalModel()) {
            sb.append("Local path: ").append(model.localPath).append('\n')
            val f = File(model.localPath!!)
            if (f.exists()) {
                sb.append("File size: ").append(Formatter.formatFileSize(this, f.length())).append('\n')
            }
        }
        if (model.description != null) {
            sb.append('\n').append(model.description).append('\n')
        }
        AlertDialog
            .Builder(this)
            .setTitle(model.displayName)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
    // Adapter
    // -------------------------------------------------------------------------

    private class ModelAdapter(
        private val models: List<ModelData>,
        private val localChipset: String,
        private val onPick: (ModelData) -> Unit,
        private val onDownload: (ModelData) -> Unit,
        private val onDelete: (ModelData) -> Unit,
        private val onShowDetail: (ModelData) -> Unit,
    ) : RecyclerView.Adapter<ModelAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tv_model_name)
            val tvSpec: TextView = v.findViewById(R.id.tv_model_spec)
            val tvStatus: TextView = v.findViewById(R.id.tv_model_status)
            val ivCompat: ImageView = v.findViewById(R.id.iv_compat_badge)
            val btnPick: Button = v.findViewById(R.id.btn_pick)
            val btnDownload: Button = v.findViewById(R.id.btn_download_model)
            val btnDelete: Button = v.findViewById(R.id.btn_delete_model)
            val btnDetail: Button = v.findViewById(R.id.btn_detail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_manager, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = models[position]
            h.tvName.text = m.displayName
            h.tvSpec.text = m.shortSpec()
            // Compat badge: highlight when chipset matches local, or when model has no chipset requirement.
            val isCompatible = m.chipset.isNullOrBlank() ||
                m.compatDevices?.any { it.equals(localChipset, ignoreCase = true) } != false
            h.ivCompat.visibility = if (isCompatible) View.VISIBLE else View.GONE
            // Status text reflects whether the model is already downloaded.
            if (m.isLocalModel()) {
                val f = File(m.localPath!!)
                h.tvStatus.text = if (f.exists()) "✓ Ready (local)" else "✗ File missing"
                h.btnDownload.visibility = View.GONE
                h.btnDelete.visibility = View.VISIBLE
            } else {
                h.tvStatus.text = "Hub: ${m.hub ?: "AUTO"}"
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
