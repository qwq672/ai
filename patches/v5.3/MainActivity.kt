// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.geniex.demo.bean.ModelData
import com.geniex.demo.bean.getSupportPluginIds
import com.geniex.demo.bean.isLocalModel
import com.geniex.demo.bean.isNpuModel
import com.geniex.demo.databinding.ActivityMainBinding
import com.geniex.demo.databinding.DialogSelectPluginIdBinding
import com.geniex.demo.listeners.CustomDialogInterface
import com.geniex.demo.utils.ExecShell
import com.geniex.demo.utils.GgufVisionConfig
import com.geniex.demo.utils.GgufVisionReader
import com.geniex.demo.utils.ImgUtil
import com.geniex.demo.utils.AppLogger
import com.geniex.demo.utils.Settings
import com.geniex.demo.utils.inflate
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPaths
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : androidx.appcompat.app.AppCompatActivity() {
    private val binding: ActivityMainBinding by inflate()
    private var downloadJob: Job? = null
    private var downloadingModelData: ModelData? = null
    private lateinit var llDownloading: LinearLayout
    private lateinit var tvDownloadProgress: TextView
    private lateinit var pbDownloading: ProgressBar
    private lateinit var btnUnloadModel: Button
    private lateinit var btnStop: Button
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAddImage: Button

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter

    private lateinit var scrollImages: HorizontalScrollView
    private lateinit var topScrollContainer: LinearLayout
    private lateinit var llLoading: LinearLayout
    private lateinit var vTip: View

    private lateinit var llmWrapper: LlmWrapper
    private lateinit var vlmWrapper: VlmWrapper
    private val modelScope = CoroutineScope(Dispatchers.IO)

    private val chatList = arrayListOf<ChatMessage>()
    private val vlmChatList = arrayListOf<VlmChatMessage>()
    private lateinit var modelList: MutableList<ModelData>
    private var selectModelId = ""

    private var isLoadLlmModel = false
    private var isLoadVlmModel = false

    /**
     * SAF launcher for importing a local .gguf model file. The picked
     * URI is copied into [filesDir]/local_models and registered in
     * [modelList] as a `runtime=llama_cpp` chat model so the user can
     * select NPU/GPU/CPU when loading. Persists across process restarts
     * via [reloadLocalImportedModels].
     */
    private val importLocalModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            // 转交给 ModelManagerActivity 处理（避免重复实现）
            startActivity(Intent(this, com.geniex.demo.activity.ModelManagerActivity::class.java))
        }

    /**
     * Vision geometry of the currently loaded VLM, read from its mmproj GGUF.
     * Null for LLM-only models, and when the mmproj declares nothing usable —
     * image preprocessing then falls back to [FALLBACK_VLM_IMAGE_SIZE].
     */
    private var vlmVisionConfig: GgufVisionConfig? = null

    private var enableThinking: Boolean
        get() = Settings.enableThinking
        set(_) {}
    private var isGenerating = false

    private val savedImageFiles = mutableListOf<File>()
    private val messages = arrayListOf<Message>()
    private var loadingMessageIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 每个步骤独立 try-catch + log，定位 bug 用
        runCatching {
            immersionBar {
                statusBarColorInt(Color.WHITE)
                statusBarDarkFont(true)
            }
        }.onFailure { AppLogger.w(TAG, "immersionBar failed: $it") }

        runCatching {
            initData()
            AppLogger.i(TAG, "initData ok")
        }.onFailure {
            AppLogger.e(TAG, "initData FAILED: ${it.message}", it)
            showErrorToUser("initData: ${it.message}")
        }

        runCatching {
            initView()
            AppLogger.i(TAG, "initView ok")
        }.onFailure {
            AppLogger.e(TAG, "initView FAILED: ${it.message}", it)
            showErrorToUser("initView: ${it.message}")
        }

        runCatching {
            setListeners()
            AppLogger.i(TAG, "setListeners ok")
        }.onFailure {
            AppLogger.e(TAG, "setListeners FAILED: ${it.message}", it)
            showErrorToUser("setListeners: ${it.message}")
        }
    }

    /** 在屏幕中央用 AlertDialog 显示错误，让用户看见（Toast 太短易错过）。 */
    private fun showErrorToUser(msg: String) {
        runOnUiThread {
            try {
                androidx.appcompat.app.AlertDialog
                    .Builder(this)
                    .setTitle("启动错误")
                    .setMessage(msg + "\n\n详情见 /Android/data/com.geniex.demo/files/app.log")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (_: Throwable) {
                Toast.makeText(this, "Error: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resetLoadState() {
        isLoadLlmModel = false
        isLoadVlmModel = false
        // Stale geometry would size preprocessing for the previous model.
        vlmVisionConfig = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 用 setSupportActionBar 时，menu 必须由 onCreateOptionsMenu 显式 inflate
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_models -> {
                startActivityForResult(
                    Intent(this, com.geniex.demo.activity.ModelManagerActivity::class.java),
                    REQ_MODEL_MANAGER,
                )
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, com.geniex.demo.activity.SettingsActivity::class.java))
                true
            }
            R.id.action_clear_chat -> {
                clearHistory()
                if (Settings.chatHistoryEnabled) {
                    com.geniex.demo.utils.ChatHistory.clear(this)
                    Toast.makeText(this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_unload -> {
                btnUnloadModel.performClick()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun initView() {
        try {
            adapter = ChatAdapter(messages)
            binding.rvChat.adapter = adapter
            AppLogger.i(TAG, "initView: rvChat adapter set")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "initView: rvChat adapter FAILED", t)
            throw t
        }

        try {
            llDownloading = findViewById(R.id.ll_downloading)
            tvDownloadProgress = findViewById(R.id.tv_download_progress)
            pbDownloading = findViewById(R.id.pb_downloading)
            btnUnloadModel = findViewById(R.id.btn_unload_model)
            btnStop = findViewById(R.id.btn_stop)
            etInput = findViewById(R.id.et_input)
            btnAddImage = findViewById(R.id.btn_add_image)
            btnSend = findViewById(R.id.btn_send)
            scrollImages = findViewById(R.id.scroll_images)
            topScrollContainer = findViewById(R.id.ll_images_container)
            llLoading = findViewById(R.id.ll_loading)
            vTip = findViewById<View>(R.id.v_tip)
            btnSend.isEnabled = false
            etInput.doAfterTextChanged { refreshSendButtonState() }
            AppLogger.i(TAG, "initView: all findViewById done")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "initView: findViewById FAILED", t)
            throw t
        }

        try {
            // 用 setSupportActionBar 让 menu 走标准 onCreateOptionsMenu 流程
            val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_main)
            setSupportActionBar(toolbar)
            toolbar.title = getString(R.string.app_name)
            // 同时设 listener，setSupportActionBar 之后 listener 也生效
            toolbar.setOnMenuItemClickListener { item ->
                AppLogger.i(TAG, "menu click: ${item.itemId}")
                when (item.itemId) {
                    R.id.action_models -> {
                        AppLogger.i(TAG, "menu -> models")
                        startActivityForResult(
                            Intent(this, com.geniex.demo.activity.ModelManagerActivity::class.java),
                            REQ_MODEL_MANAGER,
                        )
                        true
                    }
                    R.id.action_settings -> {
                        AppLogger.i(TAG, "menu -> settings")
                        startActivity(Intent(this, com.geniex.demo.activity.SettingsActivity::class.java))
                        true
                    }
                    R.id.action_clear_chat -> {
                        AppLogger.i(TAG, "menu -> clear_chat")
                        clearHistory()
                        if (Settings.chatHistoryEnabled) {
                            com.geniex.demo.utils.ChatHistory.clear(this)
                            Toast.makeText(this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_unload -> {
                        AppLogger.i(TAG, "menu -> unload")
                        btnUnloadModel.performClick()
                        true
                    }
                    else -> false
                }
            }
            AppLogger.i(TAG, "initView: toolbar set, supportActionBar done")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "initView: toolbar FAILED", t)
            throw t
        }

        // 在状态条上显示 init 结果，让用户直观看到每步成功
        try {
            updateModelStatus()
        } catch (t: Throwable) {
            AppLogger.e(TAG, "updateModelStatus FAILED", t)
        }

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            Thread {
                val exeFile = File(filesDir, "geniex_test_llm")
                val chmodProcess = Runtime.getRuntime().exec("chmod 755 " + exeFile.absolutePath)
                chmodProcess.waitFor()
                ExecShell()
                    .executeCommand(arrayOf("cat", "/sys/devices/soc0/sku"))
                    .forEach { AppLogger.d(TAG, "cmd:$it") }
            }.start()
        }

        findViewById<View>(R.id.v_tip).setOnClickListener {
            Toast.makeText(this, R.string.toast_unload_first, Toast.LENGTH_SHORT).show()
        }

        // 从本地存储恢复聊天历史
        if (Settings.chatHistoryEnabled) {
            try {
                restoreChatHistory()
                AppLogger.i(TAG, "initView: restoreChatHistory ok")
            } catch (t: Throwable) {
                AppLogger.e(TAG, "restoreChatHistory threw (caught)", t)
            }
        }
    }

    /** 把 ChatHistory 加载的消息反映到 RecyclerView。 */
    private fun restoreChatHistory() {
        try {
            val history = com.geniex.demo.utils.ChatHistory.loadAll(this)
            if (history.isEmpty()) return
            history.forEach { entry ->
                val type = MessageType.from(entry.type)
                val profile = entry.ttftMs?.let {
                    ProfileSummary(
                        ttftMs = it,
                        prefillSpeed = entry.prefillSpeed ?: 0.0,
                        decodeSpeed = entry.decodeSpeed ?: 0.0,
                        promptTokens = entry.promptTokens ?: 0,
                        generatedTokens = entry.generatedTokens ?: 0,
                        stopReason = entry.stopReason,
                    )
                }
                messages.add(Message(entry.content, type, profile = profile))
            }
            adapter.notifyDataSetChanged()
            if (messages.isNotEmpty()) {
                binding.rvChat.scrollToPosition(messages.size - 1)
                Toast.makeText(this, getString(R.string.toast_history_loaded, messages.size), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "restoreChatHistory failed: $e", e)
            // 历史恢复失败不应阻止 app 启动 — 清掉坏数据。
            runCatching { com.geniex.demo.utils.ChatHistory.clear(this) }
            messages.clear()
            adapter.notifyDataSetChanged()
        }
    }

    /** 在顶部状态条显示当前选中的模型。 */
    private fun updateModelStatus() {
        val tv = findViewById<TextView>(R.id.tv_current_model)
        if (selectModelId.isEmpty()) {
            tv.text = getString(R.string.tip_load_model_first)
        } else {
            val m = modelList.firstOrNull { it.id == selectModelId }
            tv.text = if (m != null) "当前模型：${m.displayName}" else getString(R.string.tip_load_model_first)
        }
    }

    private fun parseModelList() {
        try {
            val baseJson = assets.open("model_list.json").bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            modelList = json.decodeFromString<List<ModelData>>(baseJson).toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "parseModelList: $e")
            modelList = mutableListOf()
        }
        // Also reload any locally-imported GGUF models that survived a process restart.
        reloadLocalImportedModels()
    }

    /**
     * Scan [filesDir]/local_models for previously imported GGUF files and
     * re-register them in [modelList]. Idempotent: existing ids are skipped.
     */
    private fun reloadLocalImportedModels() {
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
                    ),
                )
                existingIds.add(id)
            }
    }

    /**
     * Refresh the model status line. Called after [modelList] changes (e.g.
     * after importing a local model or returning from ModelManagerActivity).
     * The old spinner is gone — we just update the top status TextView.
     */
    private fun refreshModelSpinner() {
        updateModelStatus()
    }

    /**
     * Copy [uri] (a user-picked .gguf via SAF) into [filesDir]/local_models
     * and append it to [modelList] + refresh the spinner. Runs on IO so a
     * multi-GB copy does not block the UI thread.
     */
    private fun importLocalGgufModel(uri: Uri) {
        // Resolve display name from the SAF cursor; fall back to URI last path segment.
        val displayName =
            runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment ?: "local-model.gguf"

        // Reject anything that doesn't look like a GGUF file — saves a multi-GB
        // copy that would only fail later in llama.cpp's loader.
        val lower = displayName.lowercase()
        if (!lower.endsWith(".gguf") && !lower.contains(".gguf")) {
            Toast
                .makeText(this, "Only .gguf files are supported: $displayName", Toast.LENGTH_LONG)
                .show()
            return
        }

        val targetDir = File(filesDir, "local_models").apply { mkdirs() }
        // De-duplicate filename so a second import doesn't overwrite the first.
        var target = File(targetDir, displayName)
        if (target.exists()) {
            val base = target.nameWithoutExtension
            val ext = target.extension.ifBlank { "gguf" }
            var n = 1
            while (target.exists()) {
                target = File(targetDir, "$base ($n).$ext")
                n++
            }
        }

        // Toast + copy on background thread; refresh UI on main.
        Toast
            .makeText(this, "Importing ${target.name}…", Toast.LENGTH_SHORT)
            .show()
        modelScope.launch {
            val ok =
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: false
                }.getOrNull() == true

            withContext(Dispatchers.Main) {
                if (!ok || !target.exists() || target.length() == 0L) {
                    Toast
                        .makeText(this@MainActivity, "Import failed: $displayName", Toast.LENGTH_LONG)
                        .show()
                    return@withContext
                }
                val id = "local-" + target.nameWithoutExtension
                // Avoid duplicate id (e.g. two imports with same name).
                modelList.removeAll { it.id == id }
                modelList.add(
                    ModelData(
                        id = id,
                        displayName = "Local: " + target.name,
                        modelName = id,
                        type = "chat",
                        runtime = "llama_cpp",
                        hub = "LOCALFS",
                        localPath = target.absolutePath,
                    ),
                )
                refreshModelSpinner()
                // Auto-select the newly imported model so the user can immediately load it.
                val newIdx = modelList.indexOfFirst { it.id == id }
                if (newIdx >= 0) selectModelId = id
                Toast
                    .makeText(
                        this@MainActivity,
                        "Imported: ${target.name} (${target.length() / 1024 / 1024} MB). Tap Load to run.",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    /**
     * Step 0. Parse the model list and initialise the SDK. Model presence
     * is queried from the Rust model manager, not tracked client-side.
     */
    private fun initData() {
        parseModelList()
        initGenieXSdk()
    }

    /**
     * Step 1. initGenieXSdk environment
     */
    private fun initGenieXSdk() {
        GenieXSdk.getInstance().init(
            this,
            object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                }

                override fun onFailure(reason: String) {
                    Log.e(TAG, "GenieXSdk init failed: $reason")
                }
            },
        )
    }

    private fun onLoadModelSuccess(tip: String) {
        runOnUiThread {
            Toast
                .makeText(
                    this@MainActivity,
                    tip,
                    Toast.LENGTH_SHORT,
                ).show()
            // change UI
            btnAddImage.visibility = View.INVISIBLE
            if (isLoadVlmModel) {
                btnAddImage.visibility = View.VISIBLE
            }
            btnUnloadModel.visibility = View.VISIBLE
            llLoading.visibility = View.INVISIBLE
            btnStop.visibility = View.VISIBLE
            refreshSendButtonState()
        }
    }

    private fun onLoadModelFailed(tip: String) {
        runOnUiThread {
            vTip.visibility = View.GONE
            Toast.makeText(this@MainActivity, tip, Toast.LENGTH_SHORT).show()
            // change UI
            btnAddImage.visibility = View.INVISIBLE
            btnUnloadModel.visibility = View.GONE
            llLoading.visibility = View.INVISIBLE
        }
    }

    private fun hasLoadedModel(): Boolean = isLoadLlmModel || isLoadVlmModel

    /**
     * Send is enabled only when (a) a model is loaded, (b) no inference
     * is in flight, and (c) there is something to send — text or an
     * attached image (VLM only).
     */
    private fun refreshSendButtonState() {
        runOnUiThread {
            val hasText = etInput.text?.isNotBlank() == true
            val hasAttachment = savedImageFiles.isNotEmpty()
            btnSend.isEnabled = hasLoadedModel() && !isGenerating && (hasText || hasAttachment)
        }
    }

    /**
     * Checks the Rust model manager's cache for [modelData]. Uses
     * `getPaths`, which canonicalises the name (so `ai-hub-models/<repo>`
     * and `qualcomm/<repo>` map to the same on-disk entry) and returns
     * null while the pull is still in `.inflight/`.
     */
    private suspend fun isModelDownloaded(modelData: ModelData): Boolean {
        // Local-imported models are always "downloaded" as long as the file exists.
        if (modelData.isLocalModel()) {
            return File(modelData.localPath!!).exists()
        }
        return ModelManagerWrapper.getPaths(modelData.modelName) != null
    }

    private fun loadModel(
        selectModelData: ModelData,
        modelDataPluginId: String,
        nGpuLayers: Int,
        deviceId: String? = null,
    ) {
        modelScope.launch {
            resetLoadState()
            // Local-imported GGUF bypasses the Rust model manager: build a
            // synthetic ModelPaths directly from the imported file path.
            val isLocal = selectModelData.isLocalModel()
            val localPath = selectModelData.localPath
            val paths =
                if (isLocal && !localPath.isNullOrBlank()) {
                    val f = File(localPath)
                    if (!f.exists()) {
                        onLoadModelFailed("local model file no longer exists: $localPath")
                        return@launch
                    }
                    ModelPaths(
                        model_path = localPath,
                        model_dir = f.parent ?: localPath,
                        model_name = selectModelData.id,
                        runtime_id = "", // let pluginId fall back to user selection
                        model_type = ModelType.LLM,
                        mmproj_path = "",
                        tokenizer_path = "",
                    )
                } else {
                    ModelManagerWrapper.getPaths(selectModelData.modelName)
                }
            if (paths == null) {
                onLoadModelFailed("model paths unavailable — pull it first")
                return@launch
            }
            // Manifest-written runtime_id wins when present; fall back to
            // the user's UI selection for GGUF models that skip the manifest.
            val pluginId = paths.runtime_id.ifEmpty { modelDataPluginId }
            val resolvedDeviceId = deviceId
            when (selectModelData.type) {
                "chat", "llm" -> {
                    // QAIRT rejects non-zero n_ctx / n_gpu_layers (both fixed at compile
                    // time in the AI Hub bundle) — and the Kotlin ModelConfig defaults
                    // are non-zero, so zero them explicitly for the qairt path.
                    val isQairt = pluginId == "qairt"
                    val conf =
                        if (isQairt) {
                            // QAIRT: SDK picks all internal knobs; only expose the
                            // thinking flag and (when thermal throttle is on) thread cap.
                            val nThreads = if (Settings.thermalThrottle) Settings.cpuThreads else 0
                            ModelConfig(
                                nCtx = 0,
                                nGpuLayers = 0,
                                nThreads = nThreads,
                                enable_thinking = enableThinking,
                            )
                        } else {
                            // llama.cpp: cap nGpuLayers under thermal throttle so NPU
                            // layers don't run flat-out and the phone thermal-trips.
                            // nGpuLayers=999 means "all layers offloaded"; cap to a
                            // sane middle (e.g. 64) when throttling.
                            val effectiveGpuLayers =
                                if (Settings.thermalThrottle && nGpuLayers > 64) 64 else nGpuLayers
                            val nThreads = if (Settings.thermalThrottle) Settings.cpuThreads else 0
                            ModelConfig(
                                nCtx = 1024,
                                nGpuLayers = effectiveGpuLayers,
                                nThreads = nThreads,
                                enable_thinking = enableThinking,
                            )
                        }
                    LlmWrapper
                        .builder()
                        .llmCreateInput(
                            LlmCreateInput(
                                model_name = paths.model_name,
                                model_path = paths.model_path,
                                tokenizer_path = paths.tokenizer_path,
                                config = conf,
                                runtime_id = pluginId,
                                compute_unit = resolvedDeviceId ?: ComputeUnitValue.NPU.value,
                            ),
                        ).build()
                        .onSuccess { wrapper ->
                            isLoadLlmModel = true
                            llmWrapper = wrapper
                            onLoadModelSuccess("llm model loaded")
                        }.onFailure { error ->
                            onLoadModelFailed(error.message.toString())
                        }
                }

                "multimodal", "vlm" -> {
                    val isNpuVlm = pluginId == "qairt"
                    // Size image preprocessing from the tower this model actually
                    // ships, not from a constant: Qwen3.5-VL is 768/16 (576
                    // tokens) but Qwen2.5-VL is 560/14 (1600), so one hardcoded
                    // number mis-sizes every other model in the catalog.
                    vlmVisionConfig =
                        paths.mmproj_path?.takeIf { it.isNotEmpty() }?.let { GgufVisionReader.read(File(it)) }
                    vlmVisionConfig?.let {
                        Log.d(
                            TAG,
                            "vision tower: ${it.imageSize}px, patch ${it.patchSize}, " +
                                "merge ${it.spatialMergeSize} -> ${it.tokenCount} image tokens",
                        )
                    } ?: Log.w(TAG, "no vision config from mmproj; preprocessing at $FALLBACK_VLM_IMAGE_SIZE")
                    val config =
                        if (isNpuVlm) {
                            // QAIRT rejects non-zero n_ctx / n_gpu_layers for VLM too.
                            ModelConfig(nCtx = 0, nGpuLayers = 0, nThreads = 8, enable_thinking = enableThinking)
                        } else {
                            ModelConfig(
                                // One image costs tokenCount tokens (576 on
                                // Qwen3.5-VL, 1600 on Qwen2.5-VL). nCtx = 1024
                                // left too little room for the prompt plus a
                                // reply, and a second image turn died inside
                                // mtmd_tokenize with "failed to initialize
                                // batch". Leave room for an image, its answer and
                                // a follow-up turn.
                                nCtx = vlmContextSize(vlmVisionConfig),
                                nThreads = 4,
                                nBatch = 1,
                                nUBatch = 1,
                                nGpuLayers = nGpuLayers,
                                enable_thinking = enableThinking,
                            )
                        }
                    VlmWrapper
                        .builder()
                        .vlmCreateInput(
                            VlmCreateInput(
                                model_name = paths.model_name,
                                model_path = paths.model_path,
                                mmproj_path = paths.mmproj_path,
                                config = config,
                                runtime_id = pluginId,
                                compute_unit = resolvedDeviceId ?: "HTP0",
                            ),
                        ).build()
                        .onSuccess {
                            isLoadVlmModel = true
                            vlmWrapper = it
                            onLoadModelSuccess("vlm model loaded")
                        }.onFailure { error ->
                            onLoadModelFailed(error.message.toString())
                        }
                }

                else -> {
                    onLoadModelFailed("model type error")
                }
            }
        }
    }

    private fun downloadModel(selectModelData: ModelData) {
        if (hasLoadedModel()) {
            Toast.makeText(this@MainActivity, "unload the current model first", Toast.LENGTH_SHORT).show()
            return
        }
        if (downloadJob?.isActive == true) {
            Toast
                .makeText(
                    this@MainActivity,
                    "${downloadingModelData?.displayName ?: "a model"} is already downloading",
                    Toast.LENGTH_SHORT,
                ).show()
            return
        }
        // Local-imported models are already on disk — skip the Rust pull flow.
        if (selectModelData.isLocalModel()) {
            val exists = File(selectModelData.localPath!!).exists()
            Toast
                .makeText(
                    this@MainActivity,
                    if (exists) "Local model ready — tap Load." else "Local file missing: ${selectModelData.localPath}",
                    Toast.LENGTH_LONG,
                )
                .show()
            return
        }

        downloadingModelData = selectModelData
        llDownloading.visibility = View.VISIBLE
        tvDownloadProgress.text = "0%"

        val hub =
            runCatching { HubSource.valueOf(selectModelData.hub ?: "AUTO") }
                .getOrDefault(HubSource.AUTO)
        // AI Hub pulls route through chipset-matched assets. The Rust side
        // can auto-detect the host only on Windows-on-Snapdragon, so on
        // Android we must pass an explicit chipset for anything that ends
        // up on the AI Hub path — whether hub is AIHUB or AUTO + ai-hub-models/*
        // (or its canonical alias qualcomm/*).
        val name = selectModelData.modelName
        val isAiHubName =
            name.startsWith("ai-hub-models/", ignoreCase = true) ||
                name.startsWith("qualcomm/", ignoreCase = true)
        val willUseAiHub =
            hub == HubSource.AIHUB ||
                (hub == HubSource.AUTO && isAiHubName)
        if (willUseAiHub && selectModelData.chipset.isNullOrBlank()) {
            llDownloading.visibility = View.GONE
            Toast.makeText(this@MainActivity, "AI Hub models require a chipset. Update model_list.json.", Toast.LENGTH_SHORT).show()
            return
        }
        val input =
            ModelPullInput(
                model_name = selectModelData.modelName,
                precision = selectModelData.quant,
                hub = hub,
                chipset = selectModelData.chipset,
                display_name = selectModelData.aiHubDisplayName,
            )

        val wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geniex:model_download")
        wakeLock.acquire()
        downloadJob =
            modelScope.launch {
                try {
                    // Short-circuit if already cached — the manager filters .inflight/
                    // models out of list(), so this only matches a complete pull.
                    if (isModelDownloaded(selectModelData)) {
                        runOnUiThread {
                            llDownloading.visibility = View.GONE
                            Toast.makeText(this@MainActivity, "model already downloaded", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    ModelManagerWrapper.pullFlow(input).collect { event ->
                        when (event) {
                            is ModelManagerWrapper.PullEvent.Progress -> {
                                val total = event.files.sumOf { if (it.total_bytes > 0) it.total_bytes else 0L }
                                val done = event.files.sumOf { it.downloaded_bytes }
                                val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                                runOnUiThread { tvDownloadProgress.text = "$percent%" }
                            }

                            is ModelManagerWrapper.PullEvent.Completed -> {
                                runOnUiThread {
                                    llDownloading.visibility = View.GONE
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            "${selectModelData.displayName} downloaded",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }

                            is ModelManagerWrapper.PullEvent.Error -> {
                                Log.e(TAG, "pull failed rc=${event.code}: ${event.message}")
                                runOnUiThread {
                                    llDownloading.visibility = View.GONE
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            "Download failed. Please check your network connection and try again.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                }
                            }
                        }
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
    }

    private fun setListeners() {
        btnAddImage.setOnClickListener {
            openGallery()
        }

        /*
         * Step 3. download model. Cancelling the coroutine triggers the
         * flow's awaitClose which flips the Rust progress callback to
         * return false — partial files stay on disk for a resumed pull.
         * Use the Retry button to kick off a fresh pull that resumes.
         */
        binding.btnCancelDownload.setOnClickListener {
            downloadJob?.cancel()
            downloadJob = null
            tvDownloadProgress.text = "0%"
            binding.llDownloading.visibility = View.GONE
        }
        binding.btnRetryDownload.setOnClickListener {
            downloadJob?.cancel()
            downloadJob = null
            downloadingModelData?.let { downloadModel(it) }
        }
        /*
         * Step 5. send message
         */
        btnSend.setOnClickListener {
            if (!hasLoadedModel()) {
                Toast
                    .makeText(this@MainActivity, "please load model first", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            // Guard against re-entry: a second click while a previous
            // generate() is still running would race on the native handle
            // and crash the app.
            if (isGenerating) return@setOnClickListener
            isGenerating = true
            refreshSendButtonState()

            if (savedImageFiles.isNotEmpty()) {
                messages.add(Message("", MessageType.IMAGES, savedImageFiles.map { it }))
                reloadRecycleView()
            }

            val inputString = etInput.text.trim().toString()
            etInput.setText("")
            etInput.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etInput.windowToken, 0)

            if (inputString.isNotEmpty()) {
                messages.add(Message(inputString, MessageType.USER))
                reloadRecycleView()
            }

            showLoadingIndicator()

            val supportFunctionCall = false
            var tools: String? = null
            if (supportFunctionCall) {
                // if this model support 'function call'
                tools =
                    "[{\"type\":\"function\",\"function\":{\"name\": \"campaign_investigation\",\"description\": \"Check campaign limits and determine appropriate action. If customer has reached limit, return a message (hardcoded or generated by model). If limit not reached, contact support.\",\"parameters\": {\"type\": \"object\", \"properties\":{\"campaign_name\":{\"type\": \"string\",\"description\": \"The name of the campaign to investigate\"}}, \"required\":[\"campaign_name\"]}}}]"
            }

            if (!hasLoadedModel()) {
                Toast.makeText(this@MainActivity, "model not loaded", Toast.LENGTH_SHORT).show()
                isGenerating = false
                refreshSendButtonState()
                return@setOnClickListener
            }

            modelScope.launch {
                try {
                    val selectModelData = modelList.first { it.id == selectModelId }
                    val isNpu = ModelManagerWrapper.getPaths(selectModelData.modelName)?.runtime_id == "qairt"
                    Log.d(TAG, "isNpu: $isNpu")

                    val sb = StringBuilder()
                    if (isLoadVlmModel) {
                        val contents =
                            savedImageFiles
                                .map {
                                    VlmContent("image", it.absolutePath)
                                }.toMutableList()
                        contents.add(VlmContent("text", inputString))
                        clearImages()
                        val sendMsg = VlmChatMessage(role = "user", contents = contents)
                        vlmChatList.add(sendMsg)

                        Log.d(TAG, "before apply chat template:$vlmChatList")
                        vlmWrapper
                            .applyChatTemplate(vlmChatList.toTypedArray(), tools, enableThinking)
                            .onSuccess { result ->
                                Log.d(TAG, "vlm chat template:${result.formattedText}")
                                val baseConfig =
                                    GenerationConfigSample().toGenerationConfig()
                                // Only inject the current turn's media: SDK tokenizes
                                // incrementally, so re-passing history bitmaps breaks
                                // mtmd_tokenize (markers/bitmaps mismatch).
                                val configWithMedia =
                                    vlmWrapper.injectMediaPathsToConfig(
                                        arrayOf(sendMsg),
                                        baseConfig,
                                    )

                                Log.d(TAG, "Config has ${configWithMedia.imageCount} images")

                                vlmWrapper
                                    .generateStreamFlow(
                                        result.formattedText,
                                        configWithMedia,
                                    ).collect { handleResult(sb, it) }
                            }.onFailure {
                                runOnUiThread {
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            it.message,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                    } else {
                        chatList.add(ChatMessage(role = "user", inputString))
                        // Apply chat template and generate
                        llmWrapper
                            .applyChatTemplate(
                                chatList.toTypedArray(),
                                tools,
                                enableThinking,
                            ).onSuccess { templateOutput ->
                                Log.d(TAG, "chat template:${templateOutput.formattedText}")
                                llmWrapper
                                    .generateStreamFlow(
                                        templateOutput.formattedText,
                                        GenerationConfigSample().toGenerationConfig(),
                                    ).collect { streamResult ->
                                        handleResult(sb, streamResult)
                                    }
                            }.onFailure { error ->
                                runOnUiThread {
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            error.message,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                    }

                    clearImages()
                } finally {
                    removeLoadingIndicator()
                    isGenerating = false
                    refreshSendButtonState()
                }
            }
        }

        /*
         * Step 6. others
         */
        btnUnloadModel.setOnClickListener {
            if (!hasLoadedModel()) {
                Toast.makeText(this@MainActivity, "model not loaded", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Unload model and cleanup
            val handleUnloadResult = fun(result: Int) {
                resetLoadState()
                chatList.clear()
                vlmChatList.clear()
                runOnUiThread {
                    vTip.visibility = View.GONE
                    btnUnloadModel.visibility = View.GONE
                    btnStop.visibility = View.GONE
                    btnAddImage.visibility = View.INVISIBLE
                    messages.clear()
                    clearImages()
                    reloadRecycleView()
                    Toast
                        .makeText(
                            this@MainActivity,
                            if (result == 0) {
                                "unload success"
                            } else {
                                "unload failed and error code: $result"
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    refreshSendButtonState()
                }
            }
            modelScope.launch {
                if (isLoadVlmModel) {
                    vlmWrapper.stopStream()
                    vlmWrapper.destroy()
                    vlmChatList.clear()
                    handleUnloadResult(0)
                } else if (isLoadLlmModel) {
                    llmWrapper.stopStream()
                    llmWrapper.destroy()
                    chatList.clear()
                    handleUnloadResult(0)
                } else {
                    handleUnloadResult(0)
                }
            }
        }
        btnStop.setOnClickListener {
            if (!hasLoadedModel()) {
                Toast
                    .makeText(
                        this@MainActivity,
                        "model not loaded",
                        Toast.LENGTH_SHORT,
                    ).show()
                return@setOnClickListener
            }
            // Stop streaming
            modelScope.launch {
                if (isLoadVlmModel) {
                    vlmWrapper.stopStream()
                } else if (isLoadLlmModel) {
                    llmWrapper.stopStream()
                }
            }
        }
    }

    private fun startLoadModel(selectModelData: ModelData) {
        vTip.visibility = View.VISIBLE
        llLoading.visibility = View.VISIBLE

        val supportPluginIds = selectModelData.getSupportPluginIds()
        Log.d(TAG, "support plugin_id:$supportPluginIds")
        var modelDataPluginId = "llama_cpp"
        var nGpuLayers = 0
        if (supportPluginIds.size > 1) {
            val dialogBinding = DialogSelectPluginIdBinding.inflate(layoutInflater)
            val isGgufLlmModel =
                !selectModelData.isNpuModel() &&
                    (selectModelData.type == "chat" || selectModelData.type == "llm")
            supportPluginIds.forEach {
                when (it) {
                    "cpu" -> {
                        dialogBinding.rbCpu.visibility = View.VISIBLE
                        dialogBinding.rbCpu.isChecked = true
                    }

                    "gpu" -> {
                        dialogBinding.rbGpu.visibility = View.VISIBLE
                    }

                    "npu" -> {
                        dialogBinding.rbNpu.visibility = View.VISIBLE
                        dialogBinding.rbNpu.isChecked = true
                    }
                }
            }
            if (isGgufLlmModel) {
                dialogBinding.rbNpu.visibility = View.VISIBLE
            }
            dialogBinding.rgSelectPluginId.setOnCheckedChangeListener { _, checkedId ->
                dialogBinding.llGpuLayers.visibility =
                    if (checkedId == R.id.rb_gpu) View.VISIBLE else View.GONE
            }

            val dialogOnClickListener =
                object : CustomDialogInterface.OnClickListener() {
                    override fun onClick(
                        dialog: DialogInterface?,
                        which: Int,
                    ) {
                        nGpuLayers = 0
                        var ggufLlmDeviceId: String? = null
                        val checkedId = dialogBinding.rgSelectPluginId.checkedRadioButtonId
                        if (checkedId == R.id.rb_gpu) {
                            if (dialogBinding.llGpuLayers.visibility == View.VISIBLE) {
                                nGpuLayers =
                                    dialogBinding.etGpuLayers.text
                                        .toString()
                                        .toInt()
                                if (nGpuLayers == 0) {
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            "nGpuLayers min value is 1",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    return
                                }
                            }
                            ggufLlmDeviceId = ComputeUnitValue.GPU.value
                        } else if (checkedId == R.id.rb_npu) {
                            nGpuLayers = 999
                            ggufLlmDeviceId = ComputeUnitValue.NPU.value
                        } else if (checkedId == R.id.rb_cpu) {
                            ggufLlmDeviceId = ComputeUnitValue.CPU.value
                        }
                        when (which) {
                            DialogInterface.BUTTON_POSITIVE -> {
                                dialog?.dismiss()
                                loadModel(selectModelData, modelDataPluginId, nGpuLayers, ggufLlmDeviceId)
                            }

                            DialogInterface.BUTTON_NEGATIVE -> {
                                llLoading.visibility = View.INVISIBLE
                                vTip.visibility = View.GONE
                            }
                        }
                    }
                }
            val alertDialog =
                AlertDialog
                    .Builder(this)
                    .setView(dialogBinding.root)
                    .setNegativeButton("Cancel", dialogOnClickListener)
                    .setPositiveButton("OK", dialogOnClickListener)
                    .setCancelable(false)
                    .create()
            alertDialog.show()
            dialogOnClickListener.resetPositiveButton(alertDialog)
        } else {
            if ("npu" == supportPluginIds[0]) {
                modelDataPluginId = "npu"
            }
            loadModel(selectModelData, modelDataPluginId, nGpuLayers)
        }
    }

    fun handleResult(
        sb: StringBuilder,
        streamResult: LlmStreamResult,
    ) {
        when (streamResult) {
            is LlmStreamResult.Token -> {
                removeLoadingIndicator()
                runOnUiThread {
                    sb.append(streamResult.text)
                    Message(sb.toString(), MessageType.ASSISTANT).let { lastMsg ->
                        val size = messages.size
                        messages[size - 1].let { msg ->
                            if (msg.type != MessageType.ASSISTANT) {
                                messages.add(lastMsg)
                            } else {
                                messages[size - 1] = lastMsg
                            }
                        }
                    }
                    adapter.notifyDataSetChanged()
                }
                AppLogger.d(TAG, "Token: ${streamResult.text}")
            }

            is LlmStreamResult.Completed -> {
                removeLoadingIndicator()
                if (isLoadVlmModel) {
                    vlmChatList.add(
                        VlmChatMessage(
                            "assistant",
                            listOf(VlmContent("text", sb.toString())),
                        ),
                    )
                } else {
                    chatList.add(ChatMessage("assistant", sb.toString()))
                }

                // Attach the profiling snapshot to the just-completed AI message
                // instead of emitting a separate PROFILE row — keeps the chat
                // transcript compact while still surfacing TTFT/prefill/decode.
                val profileSummary = ProfileSummary(
                    ttftMs = streamResult.profile.ttftMs,
                    prefillSpeed = streamResult.profile.prefillSpeed,
                    decodeSpeed = streamResult.profile.decodingSpeed,
                    promptTokens = streamResult.profile.promptTokens,
                    generatedTokens = streamResult.profile.generatedTokens,
                    stopReason = streamResult.profile.stopReason,
                )
                runOnUiThread {
                    val content = sb.toString()
                    val size = messages.size
                    messages[size - 1] = Message(content, MessageType.ASSISTANT, profile = profileSummary)
                    reloadRecycleView()
                }
                AppLogger.i(
                    TAG,
                    "Completed: ttft=${streamResult.profile.ttftMs}ms prefill=${streamResult.profile.prefillSpeed}tok/s decode=${streamResult.profile.decodingSpeed}tok/s prompt=${streamResult.profile.promptTokens} gen=${streamResult.profile.generatedTokens} stop=${streamResult.profile.stopReason}",
                )
            }

            is LlmStreamResult.Error -> {
                removeLoadingIndicator()
                runOnUiThread {
                    val reason = streamResult.throwable.message ?: streamResult.throwable.toString()
                    messages.add(Message("Error: $reason", MessageType.PROFILE))
                    reloadRecycleView()
                }
                AppLogger.e(TAG, "stream error: ${streamResult.throwable}", streamResult.throwable)
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, null)
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        startActivityForResult(intent, 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 0) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openGallery()
            } else {
                Toast.makeText(this, "Not allow", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 2001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                Toast.makeText(this, "Camera not allow", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        // ModelManager returns the picked model id. Refresh local list +
        // spinner to surface the new entry, and auto-select it.
        if (requestCode == REQ_MODEL_MANAGER && resultCode == Activity.RESULT_OK) {
            val id = data?.getStringExtra(com.geniex.demo.activity.ModelManagerActivity.RESULT_MODEL_ID)
            if (id != null) {
                // Re-parse catalog + re-scan local imports so a freshly
                // imported model shows up in the spinner.
                parseModelList()
                refreshModelSpinner()
                val idx = modelList.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    selectModelId = id
                    updateModelStatus()
                    Toast
                        .makeText(this, getString(R.string.toast_model_picked, modelList[idx].displayName), Toast.LENGTH_SHORT)
                        .show()
                }
            }
            return
        }

        var bitmap: Bitmap? = null
        if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val inputStream = contentResolver.openInputStream(data.data!!)
                bitmap = BitmapFactory.decodeStream(inputStream)
            }
        } else if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            photoFile?.let {
                bitmap = BitmapFactory.decodeFile(it.absolutePath)
            }
        }

        bitmap?.let {
            try {
                val file = File(filesDir, "chat_${System.currentTimeMillis()}.jpg")
                val success = saveBitmapToFile(it, file)
                if (success) {
                    Log.d(TAG, "Save success: ${file.absolutePath}")
                    savedImageFiles.add(file)
                    refreshTopScrollContainer()
                } else {
                    Toast.makeText(this, "Save Image failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "save image failed", e)
            }
        }
    }

    private fun saveBitmapToFile(
        bitmap: Bitmap,
        file: File,
    ): Boolean =
        try {
            val tempDir = File(this.filesDir, "tmp").apply { if (!exists()) mkdirs() }

            val tempFile =
                File(
                    tempDir,
                    "tmp_${System.currentTimeMillis()}.jpg",
                )
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }

            // Crop straight from the full-size temp file. Pre-downscaling on the
            // *longest* edge first would leave the shorter edge under the target
            // (e.g. 448x355), forcing squareCrop to upscale it back — two lossy
            // resamples for a softer result. squareCrop samples down internally.
            ImgUtil.squareCrop(
                imageFile = tempFile,
                outFile = file,
                size = vlmVisionConfig?.imageSize ?: FALLBACK_VLM_IMAGE_SIZE,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapToFile failed", e)
            false
        }

    private fun clearHistory() {
        if (isLoadLlmModel) {
            chatList.clear()
            modelScope.launch {
                llmWrapper.reset()
            }
        }
        if (isLoadVlmModel) {
            vlmChatList.clear()
            modelScope.launch {
                vlmWrapper.reset()
            }
        }
        messages.clear()
        clearImages()
        reloadRecycleView()
    }

    private var popupWindow: PopupWindow? = null

    private fun showPopupMenu(anchorView: View) {
        if (popupWindow?.isShowing == true) {
            popupWindow?.dismiss()
            return
        }

        val popupView = LayoutInflater.from(this).inflate(R.layout.menu_layout, null)

        popupWindow =
            PopupWindow(
                popupView,
                anchorView.width * 2,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            )

        popupWindow?.isOutsideTouchable = true
        popupWindow?.elevation = 10f

        val btnCamera = popupView.findViewById<Button>(R.id.btn_camera)
        val btnPhoto = popupView.findViewById<Button>(R.id.btn_photo)

        btnCamera.setOnClickListener {
            popupWindow?.dismiss()
            checkAndOpenCamera()
        }
        btnPhoto.setOnClickListener {
            popupWindow?.dismiss()
            openGallery()
        }

        popupView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED,
        )
        val popupHeight = popupView.measuredHeight
        popupWindow?.showAsDropDown(anchorView, 0, -anchorView.height - popupHeight)
    }

    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private fun checkAndOpenCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                2001,
            )
        } else {
            openCamera()
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoFile =
            File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "photo_${System.currentTimeMillis()}.jpg",
            )
        photoUri =
            FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                photoFile!!,
            )

        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, 1001)
    }

    private fun clearImages() {
        savedImageFiles.clear()
        refreshTopScrollContainer()
    }

    private fun refreshTopScrollContainer() {
        refreshSendButtonState()
        runOnUiThread {
            topScrollContainer.removeAllViews()
            if (savedImageFiles.isEmpty()) {
                scrollImages.visibility = View.GONE
                return@runOnUiThread
            }

            scrollImages.visibility = View.VISIBLE

            for (file in savedImageFiles) {
                val itemView =
                    LayoutInflater
                        .from(this)
                        .inflate(R.layout.item_image_scroll, topScrollContainer, false)
                val ivImage = itemView.findViewById<ImageView>(R.id.iv_image)
                val btnRemove = itemView.findViewById<ImageButton>(R.id.btn_remove)

                ivImage.setImageURI(Uri.fromFile(file))

                btnRemove.setOnClickListener {
                    savedImageFiles.remove(file)
                    refreshTopScrollContainer()
                }
                topScrollContainer.addView(itemView)
            }
        }
    }

    private fun reloadRecycleView() {
        adapter.notifyDataSetChanged()
        binding.rvChat.scrollToPosition(messages.size - 1)
    }

    private fun showLoadingIndicator() {
        runOnUiThread {
            if (loadingMessageIndex >= 0) return@runOnUiThread
            messages.add(Message("", MessageType.LOADING))
            loadingMessageIndex = messages.size - 1
            reloadRecycleView()
        }
    }

    private fun removeLoadingIndicator() {
        runOnUiThread {
            val idx = loadingMessageIndex
            if (idx < 0 || idx >= messages.size) {
                loadingMessageIndex = -1
                return@runOnUiThread
            }
            if (messages[idx].type == MessageType.LOADING) {
                messages.removeAt(idx)
                adapter.notifyItemRemoved(idx)
            }
            loadingMessageIndex = -1
        }
    }

    companion object {
        private const val TAG = "GenieXDemo"
        /** Request code for [com.geniex.demo.activity.ModelManagerActivity]. */
        private const val REQ_MODEL_MANAGER = 9001

        /**
         * Square edge length used for image preprocessing when the mmproj GGUF
         * does not declare one. Only a fallback — the real value is read per
         * model by [GgufVisionReader], since feeding a tower a smaller square
         * than it was trained on silently discards detail.
         */
        private const val FALLBACK_VLM_IMAGE_SIZE = 448

        /** Room for an image, its answer, and a follow-up turn, over the image cost. */
        private const val VLM_CTX_HEADROOM = 2048

        /** nCtx must be at least this regardless of image cost. */
        private const val VLM_MIN_CTX = 4096

        /**
         * Context size that fits one image of [vision]'s token cost plus room to
         * answer and ask again. Rounded up to a power of two, which is what
         * llama.cpp KV-cache allocation prefers.
         */
        private fun vlmContextSize(vision: GgufVisionConfig?): Int {
            val needed = (vision?.tokenCount ?: 0) + VLM_CTX_HEADROOM
            var ctx = VLM_MIN_CTX
            while (ctx < needed) ctx *= 2
            return ctx
        }
    }
}
