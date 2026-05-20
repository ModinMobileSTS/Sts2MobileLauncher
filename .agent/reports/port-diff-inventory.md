# 初步移植差异清单（`../s2` vs `../s2_original/s21032`）

日期：2026-05-20  
范围：只读、source-oriented 快速 checksum 对比  
忽略：`.git/`、`.godot/`、`build/`、`.gradle/`、`.cache/`、`bin/`、`obj/`、`.local/`、`.godot-home/`、`cache/`

## 1. 总览

在关注目录内共发现约 `786` 个新增/修改/删除项：

```text
addons               ADD   74
addons               DEL    1
addons               MOD   46
android              ADD    1
export_presets.cfg   ADD    1
images               ADD   11
images               MOD  233
project.godot        MOD    1
scenes               ADD    2
scenes               MOD   11
scripts              ADD    8
shaders              ADD   18
shaders              MOD   29
src                  ADD  164
src                  MOD  162
themes               MOD   18
tools                ADD    6
```

解释：

- 很多 `images/*.import`、`.uid`、atlas `.generated` 属于 Godot import/export 噪音或资源重打包副产物，不能直接等同于“需要 MOD 化的逻辑”。
- 真正需要拆分的是：Android runtime/launcher 层、Harmony patch 层、资源 overlay 层、设置数据层。

## 2. 分类建议

### 2.1 APK / runtime 层（不能做普通游戏 MOD）

代表项：

```text
android/.build_version
export_presets.cfg
addons/fmod/libs/android/arm64/*.so
addons/fmod/libs/android/*.aar
tools/android_launcher_runtime/arm64-v8a/libmonosgen-2.0.so
tools/local_build_android_workflow*.sh
```

处理：放入新 Android shell / launcher / runtime 生成脚本，不放入 `port-mod`。

### 2.2 附加设置 / Android Java 层

旧附加设置实际位于 `../s2/android/build/src/com/godot/game/` 与 `../s2/android/build/res/`，因为本次快速 diff 忽略 build 目录所以没有计入上面的 786。详见：

- `.agent/reports/extra-settings-inventory.md`

处理：M1 原样搬运到新 Android shell。

### 2.3 Harmony patch / MOD 行为层

重点 C# 差异（部分）：

```text
src/Core/Helpers/AndroidExternalSettingsApplier.cs
src/Core/Helpers/AndroidExternalSettingsLauncher.cs
src/Core/Helpers/AndroidGameWindowFocus.cs
src/Core/Helpers/AndroidProcessRestart.cs
src/Core/Helpers/MobileSelectionConfirmation.cs
src/Core/Helpers/MobileShaderCompatibility.cs
src/Core/ControllerInput/AndroidControllerInputCompat.cs
src/Core/Modding/AndroidLauncherModCompat.cs
src/Core/Modding/HarmonyAndroidCompat.cs
src/Core/Modding/HarmonyAndroidCompatRouter.cs
src/Core/Modding/PublicizeReflectionCompat.cs
src/Core/Multiplayer/LanMultiplayerUtil.cs
src/Core/Multiplayer/MultiplayerSettingsUtil.cs
src/Core/Nodes/Reaction/NMobileReactionButton.cs
src/Core/Nodes/Screens/NStartupLoadingScreen.cs
src/Core/Nodes/Screens/Settings/N*Android/Mobile/Shader/Scale*.cs
src/Core/Settings/TouchLiftRetapAction.cs
src/Core/Settings/UiFontSizeSetting*.cs
```

重点修改文件（部分）：

```text
src/Core/Saves/SettingsSave.cs
src/Core/Saves/SaveManager.cs
src/Core/Saves/UserDataPathProvider.cs
src/Core/Nodes/NGame.cs
src/Core/Nodes/CommonUi/NInputManager.cs
src/Core/Nodes/Combat/NPlayerHand.cs
src/Core/Nodes/Combat/NMouseCardPlay.cs
src/Core/Nodes/Screens/Settings/NSettingsScreen.cs
src/Core/Nodes/Screens/MainMenu/NMainMenu.cs
src/Core/Nodes/Screens/Shops/NMerchantInventory.cs
src/Core/Nodes/Screens/CardSelection/*.cs
src/Core/Modding/ModManager.cs
src/Core/Platform/PlatformUtil.cs
src/Core/Platform/Null/NullPlatformUtilStrategy.cs
```

处理：不能直接复制这些源码重编游戏；应在 `port-mod` 中用 Harmony patch 或辅助 bridge 重现行为。

第一优先级 patch 组：

1. 平台启动：Steam/Sentry/release_info/path/save 初始化。
2. 设置 bridge：从 JSON 读取移动字段，不要求原版 `SettingsSave` 新增属性。
3. 输入/触控：点击、拖牌、长按/双指、返回键。
4. UI scale/layout：global scale、font scale、主菜单/战斗/奖励/商店基础布局。
5. Shader/渲染兼容：shader compatibility mode 与已知 Android 不兼容 shader。
6. Mod loader：外部 Mods dir、Steam mods skip、mod enable/order（旧 launcher manager 还未真正 enforce）。
7. 生命周期：Quit 回设置页、focus、后台、软键盘。

### 2.4 资源 overlay 层

场景差异：

```text
ADD scenes/screens/startup_loading_screen.tscn
ADD scenes/backgrounds/main_menu_bg_fallback.tscn
MOD scenes/game.tscn
MOD scenes/run.tscn
MOD scenes/screens/settings_screen.tscn
MOD scenes/screens/join_friend_submenu.tscn
MOD scenes/screens/main_menu.tscn
MOD scenes/screens/card_selection/choose_a_card_selection_screen.tscn
MOD scenes/screens/card_selection/card_reward_selection_screen.tscn
MOD scenes/events/default_event_layout.tscn
MOD scenes/events/custom/fake_merchant_inventory.tscn
MOD scenes/merchant/merchant_inventory.tscn
MOD scenes/creature_visuals/gas_bomb.tscn
```

Shader 差异：

```text
ADD shaders/mobile_compat/*.gdshader
MOD shaders/dark_blur.gdshader
MOD shaders/vfx/**/*.gdshader
```

处理：优先做 overlay PCK 或启动时资源替换；若 scene 修改很小，可改为 Harmony 动态调整节点，减少 overlay 对游戏版本的脆弱性。

### 2.5 Godot project / import / theme 层

代表项：

```text
MOD project.godot
MOD themes/*.tres
MOD themes/fonts/**/*.tres
MOD images/**/*.import
```

处理：

- `project.godot` 的 Android/mobile 设置应迁移到 launcher command line 或 runtime patch，而非修改原版工程。
- theme/font 可优先由 settings bridge + runtime patch 调整；必要时 overlay。
- `.import` 大多不直接 MOD 化；只在实际资源加载/压缩格式问题时处理。

## 3. 需要进一步确认的细节

- `../s2` 当前有 dirty 改动，尤其附加设置 Java/strings；M1 前需确认是否纳入。
- 旧移植版中的 `src/Core/Modding/*HarmonyAndroid*` 和 Launcher Mod Manager 中 `Patches/BaseLibCompatPatches.cs`、`ModLoaderPatches.cs` 有重叠，后续需要统一实现，避免双重 patch。
- `SettingsSave.cs` 新增字段不能假设原版可反序列化为强类型字段；MOD 应独立 JSON 读取移动字段。
- 资源 overlay 与 Harmony patch 的边界需要逐屏验证决定，不宜一次性搬所有 scene。

## 4. M5 后续脚本建议

在 `tools/diff/` 写脚本：

- `make_diff_inventory.py`：生成 csv/json/md。
- `classify_diff.py`：按规则标注 runtime/mod/overlay/noise。
- `extract_candidate_patch_files.py`：把候选 C# diff 输出到 reports，不自动应用。

输出文件：

```text
docs/inventory/port-diff-full.csv
docs/inventory/port-diff-classified.md
docs/inventory/port-mod-candidate-list.md
```

## 5. 验证矩阵草案

每个差异项最终应能映射到：

```text
old path -> new owner -> implementation type -> setting deps -> validation
```

示例：

```text
src/Core/Helpers/AndroidExternalSettingsLauncher.cs
  -> port-mod AndroidSettingsBridge
  -> Harmony/bridge
  -> none
  -> 游戏内点击附加设置按钮能打开 GameSettingsActivity

src/Core/Saves/SettingsSave.cs mobile fields
  -> android-extra-settings + port-mod MobileSettingsJson
  -> JSON field preservation + runtime accessors
  -> all mobile settings fields
  -> 修改设置后重启游戏表现变化

shaders/mobile_compat/*
  -> port-mod overlay.pck
  -> resource overlay
  -> shader_compatibility_mode
  -> compatibility on/off 对比黑屏/渲染错误
```
