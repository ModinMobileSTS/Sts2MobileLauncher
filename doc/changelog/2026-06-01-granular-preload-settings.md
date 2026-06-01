# 2026-06-01 预加载细分设置

## 背景

原有附加设置只有一个 `preload_enabled` 总开关。它会影响启动后 Common/MainMenu 资源预加载，也会影响进入 run、act、room 时的原版资源预加载，用户无法单独控制资源、菜单、VFX、shader 或战斗代码 warmup。

## 改动

- 保留 `preload_enabled` 作为总开关，默认仍为 `true`。
- 新增细分设置并写入默认值 / 迁移默认值：
  - `preload_startup_common_enabled=true`：启动后加载 `AssetSets.CommonAssets`。
  - `preload_startup_main_menu_enabled=true`：启动后加载 `AssetSets.MainMenuSet`。
  - `preload_runtime_enabled=true`：保留 run/act/room 的原版预加载。
  - `preload_menu_hotspots_enabled=false`：额外实例化单人/多人常用子菜单。
  - `preload_vfx_mode=off`：可选 `off` / `hot` / `full`，分别关闭、只预热高频 VFX、递归预热全部 VFX 场景。
  - `preload_combat_code_enabled=false`：额外预热攻击/伤害/VFX 托管方法。
  - `preload_shader_mode=off`：可选 `off` / `load_resources`，仅加载已知 shader 资源。
- Android 设置页在总开关开启时显示高级预加载选项。
- 游戏内 Android 设置页同步暴露同一组高级选项。
- 兼容层 `LifecycleAndPerformancePatches` 在需要细分或额外 warmup 时接管原版 `LoadCommonAndMainMenuAssets()`，按细分开关串行加载 Common/MainMenu/shader 资源；默认组合保持本次改动前的行为，不额外启用 VFX、菜单 hotspot、shader 或战斗代码 warmup。
- `AndroidSettingsMerge` 保留新增 Android-only 字段，避免 PC 原版 settings 序列化覆盖。
- `tools/android/stage-bundled-compat-packs.sh` 的跨分支注入列表新增预加载相关 patch 文件，确保内置 `v0.103.2` 与 `v0.106.1-beta` 包获得相同设置协议。

## 验证

- `../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original-v0.106.1 -v:q`
- `tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac`
- `tools/package/build_importer_apk.sh`
  - 输出：`dist/sts2-re-importer.apk`
  - APK sha256：`97f0616fc1982faa21e971adb94194d4b4738cbe1607f51ce65063d6934a20e3`
  - 内置兼容包：
    - `android/assets/compat_packs/sts2-android-compat-v0.103.2.zip`：`cb43008e676afbe886ddf2f1f5dd9eb6117858a62d1c30ab94c0939f4298c55c`
    - `android/assets/compat_packs/sts2-android-compat-v0.106.1-beta.zip`：`a74faf94439e60cd796b0fbd7a91fe79976e3181b9a23e94c256bdcefb2ffd8f`

## 注意事项

- `preload_shader_mode=load_resources` 只提前加载已知 shader 资源，不等同于完整 GPU shader pipeline 编译；真正渲染 warmup 仍应视为后续实验项。
- `preload_vfx_mode=full` 会递归实例化大量 VFX 场景，可能增加启动耗时和内存/VRAM 峰值，建议只作为排查或高配设备选项。
- 默认值刻意保持旧版行为：Common/MainMenu/runtime preload 开启，其余额外 warmup 关闭。
