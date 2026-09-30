# v5.8.0 源码改动

经过完整静态走查后修复 5 个核心 bug。

## 修复的 bug

### BUG 1：onActivityResult 没调 startLoadModel
- v5.0 删除 btnLoadModel 时漏接，v5.5 加过自动加载又被 v5.4 patches 覆盖
- 修复：在 onActivityResult 中收到 RESULT_MODEL_ID 后自动调用 startLoadModel
- 加 AppLogger.i 记录 auto-load 流程

### BUG 2：isModelDownloaded 不检查完整性
- 旧代码：`File(localPath).exists()` 对目录返回 true，不验证内容
- 修复：用 v5.7 的 .yes 标记机制：
  * GGUF 单文件：检查 `<name>.gguf.yes` 存在
  * QAIRT 多文件目录：检查 `download_complete.yes` + `genie_config.json` + `.bin` 存在
- 加 AppLogger 详细日志记录检查结果

### BUG 3：QAIRT 模型 pluginId 错误
- 旧代码：QAIRT 模型 getSupportPluginIds 返回 ["npu"]，startLoadModel 设 modelDataPluginId="npu"
- 但 loadModel 中 `isQairt = pluginId == "qairt"`，"npu" != "qairt" → 走 llama_cpp 分支用错配置
- 修复：ModelData.kt 新增 resolveRuntimeId(uiPluginId) 扩展函数：
  * QAIRT 模型 + ui="npu" → "qairt"
  * 其他 → 原值
- startLoadModel 单选/对话框选 NPU 时都用 resolveRuntimeId 映射

### BUG 4：scanLocalImports 不扫 .yes + 不扫 QAIRT 子目录
- 旧代码：只扫 .gguf 单文件，QAIRT 多文件目录被忽略
- 修复：扫描时检查 .yes 标记 + 扫描子目录
  * GGUF 单文件：必须存在 `<name>.gguf.yes`
  * QAIRT 多文件目录：必须存在 `download_complete.yes` + `genie_config.json`
- 缺少标记的跳过并记录 AppLogger.w

### BUG 5：refreshSendButtonState 要求有内容才 enable
- 旧代码：`btnSend.isEnabled = hasLoadedModel() && !isGenerating && (hasText || hasAttachment)`
- 用户加载完模型但没打字时按钮 disabled，看起来像「按钮没用」
- 修复：
  * 加载后即使无文字也 enable
  * 视觉提示：有内容 alpha=1.0，无内容 alpha=0.5
  * 点击空输入时 Toast「请输入消息」而不是静默
  * isGenerating 时 Toast「正在生成中，请稍候」

## 下载完成自动写 .yes
- GGUF 单文件下载：ModelStorage.writeGgufCompletion(target, newId)
- QAIRT 多文件下载：ModelStorage.writeQairtCompletion(targetDir, model.id)
- SAF 导入 GGUF：ModelStorage.writeGgufCompletion(target, "local-...")
- 所有 ModelStorage.modelDir 替换原 File(filesDir, "local_models")

## ModelStorage.kt 新增
- writeGgufCompletion / writeQairtCompletion
- isGgufComplete / isQairtDirComplete
- migrate 原子性迁移（copy + verify + delete + 失败回滚）

## Settings + preferences + strings
- Settings.storageLocation 字段
- preferences.xml 加 storage_location ListPreference
- strings.xml 加 pref_storage_location / storage_migrate_* / storage_location_entries/values
- SettingsActivity 加 confirmStorageMigration 方法

## 用户使用流程（修复后）
1. 启动 app → 顶栏菜单 → 模型管理
2. 选模型 → Pick → 自动 startLoadModel（不再卡住）
3. 弹 NPU/GPU/CPU 选择 → 选 NPU
4. QAIRT 模型自动用 "qairt" runtime_id 加载（不再走错分支）
5. 加载完成 → 顶部状态条显示「当前模型：XXX」
6. 发送按钮变半透明（已加载无内容）
7. 输入文字 → 按钮变全亮
8. 点击发送 → AI 回复 + 性能 footer

## 手动迁移场景
1. Settings → 模型存储位置 → 外部存储
2. 弹迁移对话框 → 选「迁移」
3. USB 拷 .gguf 到 /sdcard/Android/data/com.geniex.demo/files/local_models/
4. 文件管理器新建空文件 <name>.gguf.yes
5. 重新打开 app → ModelManager → GGUF Tab → 看到模型
6. 点使用 → 自动加载

## 关键日志（用户排错用）
- `auto-load: model=XXX isReady=true localPath=YYY`
- `auto startLoadModel: XXX`
- `single-option pluginId: XXX -> qairt (runtime=qairt)`
- `isModelDownloaded dir XXX: yes=true cfg=true bin=true -> true`
- `refreshSendButtonState: loaded=true generating=false hasText=true -> enabled=true`
- `scan: GGUF 缺少 .yes 标记，跳过: XXX.gguf`
