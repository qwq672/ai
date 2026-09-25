#!/usr/bin/env bash
# ---------------------------------------------------------------------
# 本地构建 GenieX Chat Android APK 的脚本
# 复现了 GitHub Actions workflow 中预期的构建步骤
# ---------------------------------------------------------------------
set -euo pipefail

WORK_DIR="${WORK_DIR:-/home/z/my-project/build}"
TOOLS_DIR="$WORK_DIR/tools"
SDK_DIR="$WORK_DIR/android-sdk"
SRC_DIR="$WORK_DIR/ai-hub-apps"
APP_DIR="$SRC_DIR/geniex_chat_android"
OUT_DIR="${OUT_DIR:-/home/z/my-project/download}"

mkdir -p "$TOOLS_DIR" "$SDK_DIR" "$OUT_DIR"

# 1. JDK 17 (Temurin)
if [ ! -d "$TOOLS_DIR/jdk-17.0.13+11" ]; then
    echo "::step::下载 JDK 17"
    wget -q "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz" \
        -O "$TOOLS_DIR/jdk17.tar.gz"
    tar xzf "$TOOLS_DIR/jdk17.tar.gz" -C "$TOOLS_DIR"
fi

# 2. Gradle 9.1.0
if [ ! -d "$TOOLS_DIR/gradle-9.1.0" ]; then
    echo "::step::下载 Gradle 9.1.0"
    wget -q "https://services.gradle.org/distributions/gradle-9.1.0-bin.zip" -O "$TOOLS_DIR/gradle.zip"
    unzip -q "$TOOLS_DIR/gradle.zip" -d "$TOOLS_DIR"
fi

# 3. Android cmdline-tools
if [ ! -d "$SDK_DIR/cmdline-tools/latest" ]; then
    echo "::step::下载 Android cmdline-tools"
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip" \
        -O "$TOOLS_DIR/cmdline-tools.zip"
    mkdir -p "$SDK_DIR/cmdline-tools"
    unzip -q "$TOOLS_DIR/cmdline-tools.zip" -d "$SDK_DIR/cmdline-tools"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
fi

# 4. 设置环境
export JAVA_HOME="$TOOLS_DIR/jdk-17.0.13+11"
export GRADLE_HOME="$TOOLS_DIR/gradle-9.1.0"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$JAVA_HOME/bin:$GRADLE_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 5. Android SDK 组件
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;27.3.13750724"

# 6. 克隆源码
if [ ! -d "$APP_DIR" ]; then
    echo "::step::克隆 ai-hub-apps release 分支"
    mkdir -p "$SRC_DIR" && cd "$SRC_DIR"
    git init
    git remote add origin https://github.com/qualcomm/ai-hub-apps.git
    git config --local gc.auto 0
    git -c protocol.version=2 fetch --no-tags --depth=1 origin release
    git checkout FETCH_HEAD
fi

# 7. 构建 APK
cd "$APP_DIR"
echo "::step::构建 APK (gradle assembleDebug)"
gradle --no-daemon --stacktrace assembleDebug

# 8. 输出
APK="$APP_DIR/build/outputs/apk/debug/app-debug.apk"
TIMESTAMP=$(date +%Y%m%d-%H%M)
TARGET="$OUT_DIR/geniex_chat_android-debug-${TIMESTAMP}.apk"
cp "$APK" "$TARGET"
cp "$APK" "$OUT_DIR/geniex_chat_android-debug.apk"

echo "::done::APK 已生成: $TARGET"
ls -lh "$TARGET"
