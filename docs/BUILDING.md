# 构建与验证

当前构建平台为 Linux x86-64；Windows 可使用 WSL2。Android 输出 ARM64，最低 API 27。无需游戏数据、私有依赖或发布签名。

## 工具链

- JDK 17（设置 `JAVA_HOME`，不要使用默认 JDK 25）。
- Rust 1.97.1，由根目录 `rust-toolchain.toml` 固定；需要 rustup 和 `aarch64-linux-android` target。
- Android SDK platform 36、Build Tools 34.0.0、NDK 27.3.13750724（r27d）、SDK CMake 3.22.1。
- C / C++ 编译器、libclang、CMake、Python 3；Linux GUI 另需 Tk，GUI 测试需 Xvfb / xauth。
- Gradle 8.9 使用仓库 wrapper，Android Gradle Plugin 8.5.2、Kotlin 2.0.20；Cargo 依赖由两个 `Cargo.lock` 固定。

Ubuntu 示例，在自己的开发环境中运行：

```bash
sudo apt-get update
sudo apt-get install openjdk-17-jdk build-essential clang libclang-dev cmake ninja-build python3 python3-tk xvfb xauth curl
# 使用 Android 官方 command-line tools 安装 SDK，并把 sdkmanager 加入 PATH。
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
sdkmanager 'platforms;android-36' 'build-tools;34.0.0' 'ndk;27.3.13750724' 'cmake;3.22.1' 'platform-tools'
rustup toolchain install 1.97.1 --profile minimal --target aarch64-linux-android
./scripts/build.sh assembleDebug testDebugUnitTest lintDebug
```

需提前安装 rustup 并接受 Android SDK 许可。`ANDROID_SDK_ROOT` 也可指定 SDK；NDK 默认使用 `$ANDROID_HOME/ndk/27.3.13750724`，非标准安装可显式设置 `ANDROID_NDK_ROOT`。SDK 和签名路径保留在环境变量或被忽略的本地配置中。

SteamOS 的构建和依赖安装统一在现有 `dev` Distrobox 内运行，保留容器名称；不要在不可变宿主机安装工具链。可通过 `distrobox enter dev` 进入后配置 `JAVA_HOME`、`ANDROID_HOME` 和可选的 `ANDROID_NDK_ROOT`，再执行上述构建命令。入口脚本在 SteamOS 宿主机启动时会自动进入 `dev`。

APK：`app/build/outputs/apk/debug/app-debug.apk`。Debug 使用本机调试签名；`assembleRelease` 产生待自行签名的 release 包。发布者的签名私钥不属于源码，同功能不代表 APK 字节或签名相同。

## Rust 与原创示例

从仓库根目录运行：

```bash
cargo test --locked --manifest-path rust-core/rpf-archive/Cargo.toml --lib --tests
cargo test --locked --manifest-path rust-core/jni-bridge/Cargo.toml --features desktop,fixtures --all-targets
cargo run --locked --manifest-path rust-core/jni-bridge/Cargo.toml --features fixtures --example generate_demo -- /tmp/rpf-demo.rpf
cmp app/src/main/assets/studio.rpf /tmp/rpf-demo.rpf
./linux/build.sh
xvfb-run -a python3 linux/test_gui.py --tool build/linux-astc/bin/rpf-tool
python3 scripts/check-public.py
```

`studio.rpf` 来自公开的程序生成器，不含游戏资产。新增的密钥测试也只用人工数据，覆盖无密钥拒绝、AES 目录、NG 特殊目录和加密 payload 跨缓冲区保存。

设备集成测试：连接并解锁 Android 设备后运行 `./scripts/test-device.sh`。`RPF_TEST_SERIAL` 指定设备，`RPF_TEST_SUFFIX=.integration` 使用独立测试包。真实游戏包测试为显式 opt-in，用户须自行准备资源和对应密钥；CI 不运行这些测试。

GitHub Actions 执行公共内容检查、Rust 测试、示例重建比对、Android 编译 / 单元测试 / Lint，并提供调试 APK。构建不会下载游戏密钥或读取私有仓库。
