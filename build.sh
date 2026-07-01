#!/bin/bash
# android向けビルドスクリプト。debug/release/cancelを選択し、オプションでインストールも行う。
# これ別のプロジェクトからパクってきたので、ASan同期など不要なものは削ってあります。

while true; do
    read -p "debug?release?cancel?(d/r/c) " choice
    case "$choice" in
        d|r ) break ;;
        c ) echo "Cancelled."; exit 0 ;;
        * ) echo "Invalid input. Please enter d, r, or c." ;;
    esac
done

if [ "$choice" = "d" ]; then
    echo "Building Debug APK..."
    GRADLE_TASK=":app:assembleDebug"
    GRADLE_OPTS=""
    BUILD_TYPE="debug"
else
    echo "Building Release APK..."
    GRADLE_TASK=":app:assembleRelease"
    GRADLE_OPTS=""
    BUILD_TYPE="release"
fi

if ! ./gradlew "$GRADLE_TASK" $GRADLE_OPTS; then
    echo "Error: Build failed."
    exit 1
fi
echo "Build successful!"

echo -n "Install to device?(y/n) "
read install
if [ "$install" = "y" ]; then
    # Not using `./gradlew :app:installDebug` here -- it shells out to `adb install`, which
    # streams the APK while installing in the same step. Over this machine's flaky USB
    # passthrough (usbipd-win), a mid-stream hiccup kills the whole install. `adb push` +
    # `adb shell pm install` splits it into a plain file copy (adb's ordinary sync protocol,
    # tolerant of brief hiccups) followed by a separate, already-on-device install step.
    APK_DIR="app/build/outputs/apk/${BUILD_TYPE}"
    APK_PATH=$(find "$APK_DIR" -maxdepth 1 -name "*.apk" | head -n 1)
    if [ -z "$APK_PATH" ]; then
        echo "Error: no APK found under $APK_DIR"
        exit 1
    fi
    REMOTE_PATH="/data/local/tmp/$(basename "$APK_PATH")"

    echo "Pushing $APK_PATH..."
    if ! adb push "$APK_PATH" "$REMOTE_PATH"; then
        echo "Error: push failed."
        exit 1
    fi

    echo "Installing..."
    if adb shell pm install -r "$REMOTE_PATH"; then
        echo "Install successful!"
    else
        echo "Error: Install failed."
        adb shell rm -f "$REMOTE_PATH"
        exit 1
    fi
    adb shell rm -f "$REMOTE_PATH"
fi