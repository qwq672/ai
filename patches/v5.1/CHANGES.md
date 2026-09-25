# v5.1.0 源码改动

修复 v5.0.0 启动闪退。

## 根因
themes.xml 用 `Theme.MaterialComponents.DayNight.NoActionBar`，但 layout 引用 `Widget.Material3.Toolbar.Surface` 等 Material3 style — 主题中找不到，inflate 时崩溃。

## 修复

### 1. themes.xml：主题改为 Material3
```diff
- <style name="Theme.GenieXDemo" parent="Theme.MaterialComponents.DayNight.NoActionBar">
+ <style name="Theme.GenieXDemo" parent="Theme.Material3.DayNight.NoActionBar">
```

### 2. AndroidManifest.xml：用 Theme.GenieXDemo
```diff
- android:theme="@style/Theme.MaterialComponents.Light.NoActionBar">
+ android:theme="@style/Theme.GenieXDemo">
```

### 3. MyApplication.kt：最小化 + 全局崩溃日志
- `installCrashLogger()` 在所有 init 之前
- 崩溃直接写文件到 `getExternalFilesDir(null)/app.log`，不依赖 AppLogger
- 所有 init 方法 try-catch
- 任一步骤失败不阻塞启动

### 4. MainActivity.kt：onCreate try-catch
```kotlin
try {
    runCatching { immersionBar { ... } }
    initData()
    initView()
    setListeners()
} catch (t: Throwable) {
    AppLogger.e(TAG, "MainActivity.onCreate failed: ${t.message}", t)
    Toast.makeText(this, "启动失败：${t.message}\n详情见 app.log", Toast.LENGTH_LONG).show()
}
```
