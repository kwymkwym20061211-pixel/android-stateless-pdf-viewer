#!/bin/bash
# android向けビルドスクリプト。debug/release/cancelを選択し、オプションでインストールも行う。

NDK_PATH="/home/user/android-sdk/ndk/28.2.13676358"
LIB_NAME="libclang_rt.asan-aarch64-android.so"
DEST="app/src/main/jniLibs/arm64-v8a"

while true; do
    read -p "debug?release?cancel?(d/r/c) " choice
    case "$choice" in
        d|r ) break ;;
        c ) echo "Cancelled."; exit 0 ;;
        * ) echo "Invalid input. Please enter d, r, or c." ;;
    esac
done

./gen_cmakelists.sh

if [ "$choice" = "d" ]; then
    #echo "Starting ASan lib sync."
    #mkdir -p "$DEST"
    #find "$NDK_PATH" -name "$LIB_NAME" -exec cp {} "$DEST/" \;
    #echo "ASan library synced."
    #echo "Building Debug APK..."
    GRADLE_TASK="assembleDebug"
    #GRADLE_OPTS="-PuseAsan=true"
    BUILD_TYPE="debug"
else
    echo "Building Release APK..."
    GRADLE_TASK="assembleRelease"
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
    INSTALL_TASK="install${BUILD_TYPE^}"
    echo "Installing..."
    if ./gradlew "$INSTALL_TASK"; then
        echo "Install successful!"
    else
        echo "Error: Install failed."
        exit 1
    fi
fi