# 2026-05-31 launch profile DataDir 误解析为根目录修复

## 背景

设备测试进入主菜单后，日志显示兼容层把 profile 路径解析成了根目录下的 `/default/1`、`/mods`：

```text
Failed to ensure Android account root '/default/1': Read-only file system : '/default'
[Mods] Failed to prepare Android mods directory '/mods': Read-only file system : '/mods'
File `release_info.json` not found in any of the expected locations.
```

说明 C# 侧 `AppPaths.DataDir` 在部分 Android/Mono 环境中没有从 assembly location 成功反推出 `<files>`，随后错误接受了 `/` 或类似根目录候选，导致所有 fallback 路径都变成 `/default`、`/mods`、`/game`。

## 改动

- `AppPaths.ResolveDataDir()` 现在会拒绝空路径和 `/` 根目录候选。
- 增加纯托管 Android 私有目录候选：
  - 从 `/proc/self/cmdline` 读取当前进程包名，尝试 `/data/user/0/<package>/files` 与 `/data/data/<package>/files`。
  - 保留 `com.megacrit.sts2re` 作为已知包名 fallback。
- 只有候选目录满足以下条件之一才会被接受：
  - 存在 `launcher/selected_instance.json`；
  - 是存在的 Android app-private `.../files` 目录；
  - 包含 `.godot/mono` publish 树。
- 增加日志：成功解析时输出 `Android data dir resolved for launch profile paths: ...`，便于设备端确认 C# 与 Java 使用同一个 `<files>` 根。

## 验证

- `../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original-v0.106.1 -v:q`
- `tools/package/build_importer_apk.sh`
  - 输出：`dist/sts2-re-importer.apk`
  - APK sha256：`25f6c045db2ac990893f5bd4006095edb36bbe7388449edb1c255aa1226f591f`
  - 内置兼容包：
    - `android/assets/compat_packs/sts2-android-compat-v0.103.2.zip`：`f00b3a8a64f7f4abbe4b76248f2ff4592207c371dc5a1fc41ff41352ddc08887`
    - `android/assets/compat_packs/sts2-android-compat-v0.106.1-beta.zip`：`ba9a6e63e8c29d2fafa402db70bf2c3496d366a140b2637a8ec537137d2a9b33`

## 注意事项

- 期望设备日志中的 account root / mods 路径恢复为 `/data/user/0/com.megacrit.sts2re/files/...`，不应再出现 `/default`、`/mods`、`/game` 这类根目录 fallback。
- 如果未来修改 `applicationId`，应同步 `AppPaths.KnownPackageName` 或改为从 Java/manifest 生成该常量。
