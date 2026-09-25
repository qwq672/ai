# v3.0.0 源码改动

相对 `qualcomm/ai-hub-apps@release (commit 24bc31c, v0.37.2)` 的 `geniex_chat_android` 目录改动。

## 文件清单

### Kotlin 源码
- `MyApplication.kt`：在 onCreate 中初始化 AppLogger + Settings，调用 `Os.setenv` 注入 NPU 性能环境变量
- `MainActivity.kt`：使用 Settings 取代 enableThinking 字段；新加 btnModels/btnSettings 按钮；集成 ModelManagerActivity 回调；handleResult 把 ProfilingData 附在 Message.profile 上；loadModel 在 thermal_throttle 开启时降级 nGpuLayers/nThreads
- `ChatAdapter.kt`：新增 ProfileSummary 数据类；AiViewHolder 渲染速度 footer
- `GenerationConfigSample.kt`：从 Settings 读 temperature/top_p/top_k/repetition_penalty/max_tokens 构造 SamplerConfig
- `bean/ModelData.kt`：新增 9 个元数据字段 + `isHfMirror()` + `shortSpec()` 扩展
- `utils/AppLogger.kt`：文件日志系统（5MB 滚动）
- `utils/DeviceInfo.kt`：SoC 识别（SM8550/SM8650/SM8750/SM8850/SM7675/SM8475）
- `utils/Settings.kt`：SharedPreferences 单例封装
- `activity/ModelManagerActivity.kt`：模型管理 Activity（下载/导入/删除/详情/Pick）
- `activity/SettingsActivity.kt`：PreferenceFragmentCompat 设置页

### 资源
- `res/layout/activity_main.xml`：新增 Models/Settings 按钮
- `res/layout/activity_model_manager.xml`：模型管理布局
- `res/layout/activity_settings.xml`：设置页布局
- `res/layout/item_ai_message.xml`：新增 tv_profile TextView
- `res/layout/item_model_manager.xml`：模型列表 cell
- `res/values/strings.xml`：新增全部字符串
- `res/values/arrays.xml`：NPU power mode 选项
- `res/xml/preferences.xml`：PreferenceScreen 配置
- `res/xml/file_paths.xml`：增加 external_root 以支持 share log
- `assets/model_list.json`：所有条目增加 metadata（paramB/quant/context/compatDevices/...）
- `AndroidManifest.xml`：注册 ModelManagerActivity 和 SettingsActivity

### 构建配置
- `build.gradle`：新增 `androidx.preference:preference-ktx:1.2.1`

## 应用方式

把这些文件按对应路径覆盖到 `geniex_chat_android/src/main/`，然后 `gradle assembleDebug`。
