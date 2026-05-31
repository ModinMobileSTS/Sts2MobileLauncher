# 2026-05-31 launch profile 路径解析启动崩溃修复

## 背景

设备 smoke test 中，选择 launch profile 后进入 Godot 时，兼容层刚应用 `SavePathPatches` 即触发 Godot 原生断言：

```text
BUG: Unreferenced static string to 0: _recognize_path
JNI DETECTED ERROR IN APPLICATION: jobject is an invalid JNI transition frame reference
```

日志显示崩溃发生在兼容层早期初始化阶段，尚未完成后续 patch 应用。

## 改动

- `AppPaths` 不再在早期初始化时通过 Godot `JavaClassWrapper` / `OS.GetDataDir()` 获取路径。
- 改为从 `STS2Mobile.dll` 所在 Mono publish 目录反推 `<files>`，再读取 `<files>/launcher/selected_instance.json` 中的：
  - `selected_game_dir`
  - `selected_account_root`
  - `selected_mods_dir`
- `SavePathPatches` 的 `user://` 转换也不再调用 `ProjectSettings.LocalizePath()`；仅对 `<files>` 下路径做纯托管字符串映射，避免 Godot script bridge 尚未稳定时访问 Godot API。
- 修复 `CombineGodotPath("user://", ...)` 会退化成 `user:/...` 的边界问题。

## 验证

- `../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original-v0.106.1 -v:q`
- `tools/package/build_importer_apk.sh`
  - 输出：`dist/sts2-re-importer.apk`
  - APK sha256：`25f6c045db2ac990893f5bd4006095edb36bbe7388449edb1c255aa1226f591f`
  - 内置兼容包：
    - `android/assets/compat_packs/sts2-android-compat-v0.103.2.zip`：`f00b3a8a64f7f4abbe4b76248f2ff4592207c371dc5a1fc41ff41352ddc08887`
    - `android/assets/compat_packs/sts2-android-compat-v0.106.1-beta.zip`：`ba9a6e63e8c29d2fafa402db70bf2c3496d366a140b2637a8ec537137d2a9b33`

## 注意事项

- Java 侧仍负责在启动前写入 `selected_instance.json`；C# 侧只读取该文件。
- 如果未来新增需要兼容层极早期读取的路径，优先走 `selected_instance.json` 或纯托管推导，不要在 `ModEntry.Apply()` 阶段调用 Godot API / Java bridge。
