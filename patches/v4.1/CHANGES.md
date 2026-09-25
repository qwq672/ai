# v4.1.0 源码改动

修复 v4.0.0 启动闪退，新增深色模式。

## 修复

### 1. 闪退
- **MainActivity.onCreate** 中显式 `toolbar.inflateMenu(R.menu.menu_main)`
  - 之前依赖 `app:menu` 属性自动 inflate，在某些主题下不触发
- **restoreChatHistory** 加 try-catch
  - 反序列化失败时不阻止 app 启动，自动清空损坏历史
- 清理已不用的 `AdapterView` / `SimpleAdapter` / `Spinner` import

### 2. 全局崩溃日志
- **MyApplication.installCrashLogger** 安装 `Thread.setDefaultUncaughtExceptionHandler`
  - 崩溃 stack trace 写入 `app.log`
  - 用户无需 adb 即可查看崩溃原因
  - 写完日志后转给原 handler，保持正常崩溃行为

## 新增：深色模式

### Settings
- `Settings.darkMode` 字段：system / light / dark
- `MyApplication.applyDarkModeFromSettings()` 在 onCreate 中应用
- `SettingsActivity` 改动后立即 `setDefaultNightMode`，所有 Activity 自动 recreate

### 资源
- 新增 `values-night/colors.xml`：定义深色背景/文本/气泡配色
- 浅色不变，深色：
  - bg_app: #1A1A1A
  - bg_card: #2A2A2A
  - bg_input: #3A3A3A
  - text_primary: #EDEDED
  - bubble_ai: #2A2A2A（保持品牌色 bubble_user）

### preferences.xml
- 新增 `ListPreference` key=`dark_mode`
- 三档：跟随系统 / 强制浅色 / 强制深色

### strings.xml
- 新增 `pref_dark_mode` / `pref_dark_mode_summary`
- 新增 string-array `dark_mode_entries` / `dark_mode_values`
