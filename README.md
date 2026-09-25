# qwq672/ai

GenieX Chat Android (debug) APK build for [qualcomm/ai-hub-apps@release/geniex_chat_android](https://github.com/qualcomm/ai-hub-apps/tree/release/geniex_chat_android).

## Latest Build

- **APK**: [v1.0.0-genieX-chat-android](https://github.com/qwq672/ai/releases/tag/v1.0.0-genieX-chat-android)
- **Size**: ~97 MB
- **Package**: `com.geniex.demo` v1.0.0
- **Source commit**: `24bc31c` (ai-hub-apps release branch, v0.37.2)

## NPU Support

APK 包含 Qualcomm QNN NPU 后端库（`libQnnHtp.so`, `libQnnHtpV79.so`, `libQnnHtpV81.so` 等），
通过 `com.qualcomm.qti:geniex-android:0.3.5`（Maven Central）引入的 GenieX SDK 内置 `geniex_qairt` runtime，
原生支持 Snapdragon 8 Elite / 8 Elite Gen 5 NPU，模型从 in-app catalog 在运行时下载。

## 安装

```bash
adb install -t geniex_chat_android-debug.apk
```

## 复现构建

仓库内的 [`build-apk.sh`](./build-apk.sh) 可在 Ubuntu 24.04 上复现完整构建流程（自动安装 JDK 17 / Gradle 9.1.0 / Android SDK 34 / NDK 27.3.13750724）：

```bash
bash build-apk.sh
```

## GitHub Actions

构建脚本最初设计为 GitHub Actions workflow（见 `scripts/build-apk.yml`），但当前 PAT 缺少 `workflow` scope 无法直接推送 workflow 文件到仓库。
若需启用 GitHub Actions 自动构建，请在 GitHub Settings 中为 PAT 添加 `workflows` 权限，或手动在 Web UI 创建 workflow。
