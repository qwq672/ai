# v2.0.0 源码改动

相对 `qualcomm/ai-hub-apps@release (commit 24bc31c, v0.37.2)` 的 `geniex_chat_android` 目录改动。

## 改动文件
- `ModelData.kt`：新增 `localPath` 字段 + `isLocalModel()` 扩展函数
- `MainActivity.kt`：
  - `parseModelList` 返回 MutableList
  - 新增 `reloadLocalImportedModels()` 跨进程持久化本地导入
  - 新增 `refreshModelSpinner()` 刷新下拉
  - 新增 `importLocalGgufModel(uri)` SAF 导入
  - `isModelDownloaded` 支持本地模型
  - `loadModel` 跳过 ModelManagerWrapper 直接构造 ModelPaths
  - `downloadModel` 短路本地模型
  - 注册 `importLocalModelLauncher` (ActivityResultContracts.OpenDocument)
  - 绑定 `btn_import_local_model` 点击监听
- `activity_main.xml`：新增 `btn_import_local_model` 按钮
- `model_list.json`：新增 3 个 chipset=SM8650 NPU 模型条目（V75）

## 应用方式
```bash
cp patches/* /path/to/geniex_chat_android/src/main/java/com/geniex/demo/bean/ModelData.kt
# ... 按文件路径对应放置
```
