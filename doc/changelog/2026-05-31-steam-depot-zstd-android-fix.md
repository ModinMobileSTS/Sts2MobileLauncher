# 2026-05-31 Steam Depot VZstd Android 解压修复

## 背景

真机使用“从 Steam 下载游戏本体”时，Steam depot chunk 进入 `VSZa` / VZstd 解压路径后失败：

```text
java.lang.UnsatisfiedLinkError: dlopen failed: library "libzstd-jni-1.5.6-9.so" not found
Unsupported OS/arch, cannot find /linux/aarch64/libzstd-jni-1.5.6-9.so
```

`com.github.luben:zstd-jni` 的普通 JVM artifact 不是 Android JNI 包，APK 中没有可被 Android linker 加载的 `arm64-v8a` native library，导致下载到 VZstd chunk 时崩溃。

## 改动

- Steam depot VZstd 解压从 JVM `zstd-jni` 切换为 Android native zstd bridge。
- 新增 `libworkshop_zstd.so` JNI wrapper，链接 Android Prefab zstd 包，并由 `steam-content` 中的 `AndroidZstdBridge` 调用。
- 移除 launcher/app 与 `steam-content` 模块中的 JVM `zstd-jni` 依赖，避免 APK 带入无法在 Android 上加载的 JVM native resource。
- 下载进度文本增加当前文件 chunk 计数，避免大 DLL/大 chunk 下载时看起来完全卡死。
- CDN server pool 去重，并限制单个 chunk 每轮最多尝试 8 个服务器，避免确定性解压/校验错误时把同一 chunk 在大量重复 CDN entry 上反复下载很久。
- chunk 失败错误现在包含文件路径、chunk 序号和底层异常链，便于从设备日志中定位是 CDN、native bridge 还是 checksum 问题。
- 更新第三方依赖说明与构建文档。

## 验证

已执行：

```bash
tools/android/gradle-with-s2-env.sh :steam-content:compileKotlin :steam-content:compileJava :externalNativeBuildMonoDebug
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
tools/package/build_importer_apk.sh
git diff --check
```

最终 APK 已确认包含：

```text
lib/arm64-v8a/libworkshop_zstd.so
lib/arm64-v8a/libzstd.so
```

仍需在真机重新尝试 SteamPipe 游戏本体下载。预期不会再出现 `libzstd-jni-1.5.6-9.so not found`；若仍失败，新的错误会包含具体文件、chunk 序号和底层异常链。
