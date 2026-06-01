# 2026-06-01 预加载详情 BottomSheet

## 背景

预加载细分开关直接展开在“系统”卡片中，项目较多，导致设置页显得分散。用户希望保留一个预加载总开关，并通过右侧箭头进入详细管理页；同时总开关的开关行为不能改写细分项目的值。

## 改动

- Android 附加设置页中，“启用预加载 / 启动预热”改为总开关 + 右侧详情箭头。
- 点击右侧箭头打开预加载详细管理 BottomSheet；默认不会自动展开或打开该界面。
- Common/MainMenu/menu hotspot/VFX/combat code/shader/runtime 等细分项目移动到 BottomSheet 内。
- 总开关 `preload_enabled` 只写入自身，不再刷新页面，也不会修改任何细分项目。
- BottomSheet 增加“恢复默认”按钮，只恢复细分项目默认值：
  - `preload_startup_common_enabled=true`
  - `preload_startup_main_menu_enabled=true`
  - `preload_menu_hotspots_enabled=false`
  - `preload_vfx_mode=off`
  - `preload_combat_code_enabled=false`
  - `preload_shader_mode=off`
  - `preload_runtime_enabled=true`
- 增加独立右箭头 vector 资源，避免使用“前进箭头”造成视觉误解。

## 验证

- `tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac`
- `tools/package/build_importer_apk.sh`
  - 输出：`dist/sts2-re-importer.apk`
  - APK sha256：`97f0616fc1982faa21e971adb94194d4b4738cbe1607f51ce65063d6934a20e3`

## 注意事项

- 本次只调整 Android launcher 附加设置 UI；兼容层实际预加载协议与默认值不变。
- “恢复默认”不修改总开关 `preload_enabled`，只重置详情页内的细分项目。
