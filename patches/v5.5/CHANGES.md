# v5.5.0 源码改动

## 修复 1：发送按钮不能点
- 根因：v5.0 删了 btnLoadModel，但没在 onActivityResult 中接 startLoadModel
- 修复：从 ModelManagerActivity 选完模型后自动调用 startLoadModel

## 新增：模型存储位置（内部/外部）+ 迁移
- 新增 `utils/ModelStorage.kt`：封装 modelDir/otherDir/migrate
- Settings.storageLocation 字段
- ModelManagerActivity + MainActivity 中所有 `File(filesDir, "local_models")` 替换为 `ModelStorage.modelDir(this)`
- SettingsActivity `confirmStorageMigration()` 显示迁移对话框
- preferences.xml + strings.xml 加相关配置
