# Code Context

## Files Retrieved
1. `../s2/project.godot` (lines 11-43, 60-75) - Godot/C# 项目入口、autoload、FMOD、移动特性与日志配置。
2. `../s2/export_presets.cfg` (lines 31-114) - Android / Android FMOD 导出预设、包名、版本、ABI、是否启用 Gradle。
3. `../s2/sts2.csproj` (lines 1-64) - Godot.NET 4.5.1 / net9.0 / dynamic loading / Harmony-MonoMod 引用。
4. `../s2/android/.build_version` (line 1) and `../s2/android/build/.build_version` (line 1) - Android build template 版本 `4.5.1.stable.mono`。
5. `../s2/android/build/AndroidManifest.xml` (lines 1-132) - 手写主 Manifest，声明 `Sts2Application`、`GameSettingsActivity`、`GodotApp`、Crash/Log/File/Text 活动与 FileProvider。
6. `../s2/android/build/src/debug/AndroidManifest.xml` (lines 1-31) and `../s2/android/build/src/release/AndroidManifest.xml` (lines 1-31) - build-type Manifest overlay，给 `GodotApp` 加 MAIN/LAUNCHER 与 Godot metadata。
7. `../s2/android/build/build/intermediates/merged_manifests/monoDebug/processMonoDebugManifest/AndroidManifest.xml` (lines 1-180) - 实际合并 Manifest；确认当前 APK 同时有 GodotApp 与 GameSettingsActivity 两个 launcher 入口。
8. `../s2/android/build/build.gradle` (lines 1-334) - Android Gradle 应用模块、依赖、sourceSets、flavor/buildTypes、输出 APK 命名。
9. `../s2/android/build/config.gradle` (lines 1-220) - AGP/Kotlin/SDK/NDK/Java 版本及 Godot export 参数读取。
10. `../s2/android/build/settings.gradle` (lines 1-17) - Gradle pluginManagement 与 install-time asset pack。
11. `../s2/android/build/gradle.properties` (lines 1-25) - AndroidX/Jetifier、JVM 内存、资源优化、路径检查。
12. `../s2/android/build/assetPackInstallTime/build.gradle` (lines 1-8) - install-time asset pack 配置。
13. `../s2/tools/local_build_android_workflow.sh` (lines 1-380) - 本地 Android 一键构建脚本、Godot/FMOD workaround、patched Mono runtime 安装。
14. `../s2/tools/local_build_android_workflow_publicize.sh` (lines 1-190) - publicize 变体脚本，改写 Android 导出 DLL/APK 以缓解 Harmony/MonoMod 访问限制。
15. `../s2/android/build/src/com/godot/game/GameSettingsActivity.java` (lines 1-463) - 附加设置主 Activity、欢迎向导、底部导航、文件导入导出、异步动作。
16. `../s2/android/build/src/com/godot/game/ExtraSettingsActions.java` (lines 1-19) - 页面调用 Activity 能力的接口。
17. `../s2/android/build/src/com/godot/game/ExtraSettingsPreferences.java` (lines 1-42) - `sts2_extra_settings` SharedPreferences：首次向导、上次 tab、自动更新检查。
18. `../s2/android/build/src/com/godot/game/RendererPreference.java` (lines 1-49) - `preferred_renderer` SharedPreferences 与 Godot 命令行参数。
19. `../s2/android/build/src/com/godot/game/ExtraSettingsRepository.java` (lines 40-253, 257-410, 410-558, 590-760, 760-920, 1000-1109, 1110-1296, 1390-1480) - 附加设置核心仓库：`settings.save`、预设、存档/MOD/备份、LAN、路径与数据模型。
20. `../s2/android/build/src/com/godot/game/ExtraSettingsUi.java` (lines 1-220) - 纯 Java Material 3 风格 UI 工具。
21. `../s2/android/build/src/com/godot/game/WelcomeSetupPage.java` (lines 1-115, 153-402) - 首次启动欢迎向导：渲染、显示、存档、操作、完成。
22. `../s2/android/build/src/com/godot/game/GamePage.java` (lines 1-187) - 附加设置的“游戏”页：状态、启动、存档、日志/文件快捷入口。
23. `../s2/android/build/src/com/godot/game/SettingsPage.java` (lines 32-180, 183-307, 379-619) - 附加设置的“设置”页：预设、图形、存档、输入、系统、LAN、完整备份。
24. `../s2/android/build/src/com/godot/game/ModsPage.java` (lines 1-190, 191-370, 362-449, 449-599, 718-908) - 附加设置的“模组”页：导入、搜索、筛选、启停、方案、批量操作。
25. `../s2/android/build/src/com/godot/game/AboutPage.java` (lines 1-144) - 关于页、作者/友链、更新检查开关。
26. `../s2/android/build/src/com/godot/game/ExtraSettingsUpdateChecker.java` (lines 1-235) - 夸克分享页更新检查实现。
27. `../s2/android/build/src/com/godot/game/GodotApp.java` (lines 1-336) - Godot Activity 模板改造：首次启动重定向、renderer 参数、软键盘、C# 静态桥、硬重启。
28. `../s2/android/build/src/com/godot/game/Sts2Application.java` (lines 1-132) - CustomActivityOnCrash 初始化与崩溃附加信息。
29. `../s2/android/build/src/com/godot/game/StartupHealthTracker.java` (lines 1-77) - 游戏启动健康标记 SharedPreferences。
30. `../s2/android/build/src/com/godot/game/Sts2CrashActivity.java` (lines 1-130) - 自定义崩溃页，打开设置/重试/复制/关闭。
31. `../s2/android/build/src/com/godot/game/FileBrowserActivity.java` (lines 66-176, 382-451, 573-697) - 私有文件浏览器入口、打开/编辑、SAF 导入导出。
32. `../s2/android/build/src/com/godot/game/FileBrowserSupport.java` (lines 1-254) - 文件复制、文本识别、FileProvider 外部打开等公共工具。
33. `../s2/android/build/src/com/godot/game/TextEditorActivity.java` (lines 1-310) - 内置文本编辑器。
34. `../s2/android/build/src/com/godot/game/LogViewerActivity.java` (lines 188-282, 326-484, 566-663) - 日志扫描、详情、复制/分享/导出。
35. `../s2/android/build/res/menu/menu_extra_settings_nav.xml` (lines 1-18) - 附加设置底部导航四个 tab。
36. `../s2/android/build/res/xml/settings_shortcuts.xml` (lines 1-25) and `../s2/android/build/res/xml/game_shortcuts.xml` (lines 1-25) - launcher shortcuts。
37. `../s2/android/build/res/xml/file_paths.xml` (lines 1-15) - FileProvider 路径白名单。
38. `../s2/android/build/res/values/themes.xml` (lines 1-20) and `../s2/android/build/res/values/themes_sts2_crash.xml` (lines 1-122) - Godot splash/main theme 与附加设置/崩溃页主题。
39. `../s2/android/build/res/values/strings_game_settings.xml` (lines 1-435) and `../s2/android/build/res/values-zh/strings_game_settings.xml` (lines 1-35) - 附加设置英文/中文资源。
40. `../s2/android/build/res/layout/activity_game_settings.xml` (lines 1-160) - 旧式 XML 设置页布局；当前 `GameSettingsActivity` 已转为程序化 UI，疑似遗留。
41. `../s2/scenes/screens/settings_screen.tscn` (lines 49-90, 898-997) - 游戏内设置页的“打开附加设置”节点与脚本绑定。
42. `../s2/src/Core/Nodes/Screens/Settings/NSettingsScreen.cs` (lines 60-300) - 游戏内设置页显示 Android 入口/Android 专属控件并调用桥接。
43. `../s2/src/Core/Nodes/Screens/Settings/NOpenAndroidExternalSettingsButton.cs` (lines 1-31) - 游戏内“Open/附加设置”按钮脚本。
44. `../s2/src/Core/Helpers/AndroidExternalSettingsLauncher.cs` (lines 1-34) - C# 调 Java `GodotApp.launchGameSettingsFromGame()`。
45. `../s2/src/Core/Helpers/AndroidProcessRestart.cs` (lines 1-43) - C# 调 Java `GodotApp.restartToSettingsFromGame()`。
46. `../s2/src/Core/Helpers/AndroidExternalSettingsApplier.cs` (lines 1-91) - 游戏启动时消费 `pending_unlock_all.flag`。
47. `../s2/src/Core/Saves/SettingsSave.cs` (lines 1-166) - `settings.save` JSON schema 与 Android 附加设置字段。
48. `../s2/src/Core/Saves/UserDataPathProvider.cs` (lines 1-60) - Godot `user://default/<playerId>` 路径规则。
49. `../s2/src/Core/Nodes/NGame.cs` (lines 300-340, 430-459, 660-779) - 启动阶段应用附加设置、显示/方向应用、退出时硬重启到设置。
50. `../s2/addons/fmod/FmodManager.gd` (lines 1-135) - Android 音频兼容模式读取 `settings.save` 并设置 FMOD DSP buffer。
51. `../s2/src/Core/Multiplayer/LanMultiplayerUtil.cs` (lines 1-170) - LAN 自定义玩家 ID、兼容 MOD 列表、保存 host/port。
52. `../s2/src/Core/Platform/Null/NullPlatformUtilStrategy.cs` (lines 70-125) - 自定义 platform player ID 接入。
53. `../s2/src/Core/Multiplayer/MultiplayerSettingsUtil.cs` (lines 1-38) - 自定义最大联机人数开关。
54. `../s2/src/Core/Modding/ModCompatibility.cs` (lines 1-90) - Android quick SL / Retry 按钮开关。
55. `../s2/src/Core/Nodes/CommonUi/NInputManager.cs` (lines 330-400) and `../s2/src/Core/Nodes/Reaction/NMobileReactionButton.cs` (lines 84-109) - 双指查看与多人表情按钮开关消费点。

## Key Code

### Android 入口/Manifest
- 手写主 Manifest 让 `GameSettingsActivity` 是导出的 portrait Activity，并附带 settings shortcuts；`GodotApp` 是 landscape Godot Activity；`Sts2Application` 负责 crash handler：`../s2/android/build/AndroidManifest.xml` lines 21-132。
- build-type overlay 又给 `GodotApp` 加 MAIN/LAUNCHER：`../s2/android/build/src/debug/AndroidManifest.xml` lines 20-31。实际 merged manifest 中：
  - `GodotApp` 带 MAIN/DEFAULT/LAUNCHER：`../s2/android/build/build/intermediates/merged_manifests/monoDebug/processMonoDebugManifest/AndroidManifest.xml` lines 54-76。
  - `GameSettingsActivity` 也带 MAIN/LAUNCHER：同文件 lines 82-101。
  - 这看起来是“双桌面入口”：游戏图标进 Godot，附加设置图标进设置。

### 附加设置 Activity 与导航
```java
// ../s2/android/build/src/com/godot/game/GameSettingsActivity.java lines 34-46
repository = new ExtraSettingsRepository(this);
repository.ensureAppDirectories();
if (!ExtraSettingsPreferences.isFirstRunSetupCompleted(this)) {
    showWelcome();
} else {
    showMainShell();
}
```
主界面是程序化 UI，不依赖 `activity_game_settings.xml`：`showMainShell()` 构造 `FrameLayout + BottomNavigationView`，菜单来自 `R.menu.menu_extra_settings_nav`，tab 映射：`GamePage` / `ModsPage` / `SettingsPage` / `AboutPage`（`GameSettingsActivity.java` lines 58-126）。

### 数据仓库/持久化
核心文件是 `../s2/android/build/src/com/godot/game/ExtraSettingsRepository.java`：
- `SETTINGS_SCHEMA_VERSION = 5`，设置文件名 `settings.save`，解锁标记 `pending_unlock_all.flag`，MOD 方案 prefs `sts2_mod_profiles`，预设键 `android_graphics_preset` / `android_display_preset`（lines 40-61）。
- `loadSettingsJson()` 不存在/空文件时创建默认 JSON；已有文件仅补齐 Android companion defaults（lines 74-179）。
- `getAccountRootDir()` 选 `getFilesDir()/default` 下第一个目录，否则 `default/1`；`getSettingsFile()` 写入该目录下 `settings.save`；`getModsRootDir()` 是 `getFilesDir()/mods`（lines 1277-1296）。
- 预设：Recommended = OpenGL ES + MSAA 2 + VSync off；Quality = Vulkan + native/unlimited/VSync on；Compatibility = OpenGL ES + MSAA off + shader compat（lines 188-224）。Display mobile = `global_scale=0.7` + `ui_font_scale_percent=165` + `fullscreen_render_size=1280x720`（lines 225-241）。Operation touch/original 只切三项触屏核心设置（lines 244-251）。
- 存档 ZIP 导出/导入只覆盖 account root；完整备份导出/恢复整个 app data root（lines 257-318）。
- MOD 导入支持 zip 解压或单文件复制到 `files/mods`，并确保 `mod_settings.mods_enabled=true`（lines 320-337）。

### 设置页模块清单（Java UI）
`SettingsPage.build()` 加载 `settings.save` 后按顺序生成这些卡片（`SettingsPage.java` lines 67-90）：
1. **Presets**：渲染预设 Recommended/Quality/Compatibility/Custom，显示预设 Original/Mobile/Custom（lines 93-154）。
2. **Graphics parameters**：aspect ratio、render resolution、renderer、MSAA、VSync、FPS、global scale、自定义 scale、font scale、shader compatibility（lines 156-180）。
3. **Save**：导出/导入存档、解锁全部、普通/MOD 存档互转（lines 183-205）。
4. **Input**：Touch optimized / Original controls 预设；mobile selection confirmation、show more hand card text、touch lift preview、two-finger inspect、volume-up soft keyboard（lines 232-255）。
5. **System**：preload、audio compatibility、multiplayer emoji button、quick SL、custom max multiplayer players、日志/文件浏览器入口（lines 258-277）。
6. **LAN**：兼容 MOD 列表、自定义 player ID / platform player ID、文本 player ID 输入（lines 279-307, 469-619）。
7. **Full app data backup**：导出/恢复完整私有数据（lines 207-223）。

### MOD 页模块清单
`ModsPage` 包含：
- 顶栏 profile/sort/filter 菜单（`ModsPage.java` lines 110-138, 210-345）。
- 工具卡：master switch `mod_settings.mods_enabled`、导入 MOD、多字段搜索（lines 139-190）。
- 列表卡：递归扫描 manifest JSON，按 enabled/disabled/libraries/missing/search 筛选，按安装时间或名称排序（lines 449-532）。
- 单卡：显示 name/id/category/version/authors/path，启停开关调用 `repository.setModDisabled()`（lines 533-599）。
- 批量底部面板：范围/反选/全选、批量 enable/disable/delete、clear（lines 362-405）。
- MOD 方案：保存当前启用 ID 集、应用方案、删除活动方案（lines 295-355, 825-861）。

### Godot Activity 与 C# 桥
`GodotApp` 是附加设置与游戏之间的关键桥：
- 如果首次向导未完成，`GodotApp.onCreate()` 会先 `super.onCreate()`，再启动 `GameSettingsActivity` 并 `finish()`（`GodotApp.java` lines 110-125）。
- `getCommandLine()` 附加 renderer 参数；OpenGL ES 3.0 时传 `--rendering-method gl_compatibility`（`GodotApp.java` lines 135-139；`RendererPreference.java` lines 27-36）。
- 暴露给 C# 的静态方法：`launchGameSettingsFromGame()` 与 `restartToSettingsFromGame()`，后者启动设置 Activity 后 `Runtime.getRuntime().exit(0)` 清理 Godot/Mono/NativeDetour 状态（`GodotApp.java` lines 293-336）。
- C# 侧 `AndroidExternalSettingsLauncher.TryOpen()` 反射调用 Java 静态方法（`AndroidExternalSettingsLauncher.cs` lines 7-34）；`AndroidProcessRestart.TryRestartToSettingsAfterQuit()` 在 `NGame.Quit()` 后请求硬重启（`AndroidProcessRestart.cs` lines 7-43；`NGame.cs` lines 729-756）。

### 游戏内设置入口
- `settings_screen.tscn` 绑定 `NOpenAndroidExternalSettingsButton`，节点默认 `visible=false`，标题 `Additional Settings`、按钮文本 `Open`（`settings_screen.tscn` lines 898-997）。
- `NSettingsScreen.ConfigureAndroidExternalSettingsEntry()` 在 Android 上显示该入口并连接焦点邻居；`OpenAndroidExternalSettings()` 调 C# 桥（`NSettingsScreen.cs` lines 99-137, 273-278）。
- `NSettingsScreen.ConfigureAndroidGraphicsEntries()` 只在 Android 显示 ScreenRotation、FontSize、GameScale、ShowMoreHandCardText、HandCardLiftHeight、TouchLiftPreview、TouchLiftRetapAction、MobileSelectionConfirmation 等游戏内高级控件（lines 139-181）。

### `settings.save` 关键字段
`../s2/src/Core/Saves/SettingsSave.cs` lines 31-162 与 Java JSON key 必须严格对齐，重点字段：
- 图形：`aspect_ratio`、`msaa`、`shader_compatibility_mode`、`global_scale`、`ui_font_scale_percent`、`fullscreen_render_size`、`android_flip_screen_180`。
- 输入/触屏：`show_more_hand_card_text`、`show_more_hand_card_text_lift_height_percent`、`touch_lift_preview`、`touch_lift_retap_action`、`mobile_selection_confirmation`、`mobile_two_finger_inspect`、`android_volume_up_soft_keyboard`。
- 系统/MOD/LAN：`preload_enabled`、`audio_compatibility_mode`、`show_mobile_emoji_button`、`max_multiplayer_enabled`、`max_multiplayer_players`、`quick_sl_enabled`、`mod_settings`、`lan_*`。

### 其他消费点
- `AndroidExternalSettingsApplier.TryApplyPendingCommands()` 在启动 stage 10b 后读取 `pending_unlock_all.flag` 并修改进度/删除当前 run（`NGame.cs` lines 300-329；`AndroidExternalSettingsApplier.cs` lines 19-91）。
- `FmodManager.gd` Android 下读取 `user://default/1/settings.save` 或 `user://settings.save`，若 `audio_compatibility_mode=true` 则设置更保守的 DSP buffer（lines 1-135）。
- `LanMultiplayerUtil` 使用 `lan_use_custom_player_id`、`lan_custom_player_id`、`lan_compatibility_mod_names`、`lan_join_host/port`（lines 1-170）；`NullPlatformUtilStrategy` 使用 `lan_use_custom_platform_player_id` 覆盖 platform player ID（lines 70-95）。
- `MultiplayerSettingsUtil.GetConfiguredHostMaxPlayers()` 使用 `max_multiplayer_enabled/max_multiplayer_players`（lines 8-38）。
- `ModCompatibility.TryApplyPauseMenuCompatibility()` 使用 `quick_sl_enabled` 注入 Android Retry 按钮（lines 19-44）。
- `NInputManager` 使用 `mobile_two_finger_inspect`（lines 330-388）；`NMobileReactionButton` 使用 `show_mobile_emoji_button`（lines 84-100）。

## Architecture

当前 `../s2` 是 Godot 4.5.1 Mono/C# 项目，Android 移植层不是独立 Android Studio 工程，而是 Godot Android Gradle build template：`../s2/android/build`。Java/Kotlin/Gradle/资源改动都放在这个模板里，Godot 导出会把项目资源与 .NET publish 产物放入 `android/build/assets`，并使用 `export_presets.cfg` 的 `Android FMOD` 预设输出 APK。

附加设置的运行路径：
1. Android 桌面入口启动 `GameSettingsActivity` 或 `GodotApp`（当前 merged manifest 两者都是 launcher）。
2. `GameSettingsActivity` 创建 `ExtraSettingsRepository`，确保 `files/default/<account>` 与 `files/mods` 存在。
3. 首次启动显示 `WelcomeSetupPage`，完成后写 `sts2_extra_settings.first_run_setup_completed=true`；之后显示底部导航四页：Game / Mods / Settings / About。
4. 绝大多数设置写入私有文件 `files/default/<account>/settings.save`；renderer、first-run、last tab、update check 写入 `SharedPreferences sts2_extra_settings`；MOD profile 写入 `SharedPreferences sts2_mod_profiles`。
5. 启动游戏时 `GodotApp` 读取 renderer prefs 构造 Godot 命令行；Godot/C# `SaveManager` 加载同一个 `settings.save`，各系统消费字段。
6. 游戏内设置屏通过 C# `JavaClassWrapper` 调用 `GodotApp.launchGameSettingsFromGame()` 可返回附加设置；游戏退出通过 `AndroidProcessRestart` 调 `restartToSettingsFromGame()`，强制重启到设置页并退出进程，避免 MOD/Harmony/Mono 状态残留。
7. Crash handler 由 `Sts2Application` 初始化；崩溃时进入 `Sts2CrashActivity`，可打开设置或重试游戏。

可搬运模块清单：
- **Java 核心**：`GameSettingsActivity`、`ExtraSettingsActions`、`ExtraSettingsPreferences`、`ExtraSettingsRepository`、`ExtraSettingsUi`、`RendererPreference`、`WelcomeSetupPage`、`GamePage`、`SettingsPage`、`ModsPage`、`AboutPage`、`ExtraSettingsUpdateChecker`。
- **Java 桥/稳定性**：`GodotApp` 改动、`Sts2Application`、`StartupHealthTracker`、`Sts2CrashActivity`。
- **Java 工具页**：`LogViewerActivity`、`LogFileViewerActivity`、`FileBrowserActivity`、`FileBrowserSupport`、`TextEditorActivity`、`AppBarContentOverlapHelper`、`RepeatSelectionSpinner`。
- **Android 资源**：`res/drawable/ic_*` 和 crash 背景、`res/layout/activity_*` 与 item layouts、`res/menu/*`、`res/xml/file_paths.xml` / shortcuts、`res/values/strings_game_settings.xml` / `strings_crash.xml` / themes/colors、`res/values-zh/*`。
- **Manifest/Gradle**：主 Manifest、debug/release overlay、Material/DocumentFile/CAOC/MTDataFilesProvider 依赖、FileProvider、sourceSets、flavor/buildTypes、release debuggable 设置。
- **Godot/C# 桥与消费点**：`AndroidExternalSettingsLauncher`、`AndroidProcessRestart`、`AndroidExternalSettingsApplier`、`SettingsSave`、`NGame`、`NSettingsScreen`、`NOpenAndroidExternalSettingsButton`、`FmodManager.gd`、LAN/Multiplayer/ModCompatibility/输入消费点。
- **构建/运行时二进制**：`android/build/libs/*/arm64-v8a`、`tools/android_launcher_runtime/arm64-v8a/libmonosgen-2.0.so`、FMOD/Spine/Godot template AAR；这些和 Godot/Mono/FMOD 版本强耦合。

构建系统重点：
- `export_presets.cfg` 的 `Android FMOD` 预设启用 Gradle build，包名 `com.megacrit.sts2`，版本 `0.103.2` / `103022`，仅 arm64-v8a，debug keystore 在项目 `.godot-home`（lines 80-114）。
- Gradle：AGP `8.6.1`、Kotlin plugin `2.1.0`、compile/target SDK 35、min SDK 24、Java 17、NDK 28.1；依赖 Material 1.12、RecyclerView、DocumentFile、customactivityoncrash、MTDataFilesProvider、Godot Android libs、FMOD plugin（`config.gradle` lines 1-63；`build.gradle` lines 38-71, 233-252）。
- `tools/local_build_android_workflow.sh` 会执行 dotnet build、设置本地 Godot/Android/JDK 环境、修正 GDExtension 顺序、安装 patched Mono runtime、导出 APK；不是单纯 Gradle build（lines 1-380）。
- `tools/local_build_android_workflow_publicize.sh` 在 APK 导出后解包、publicize `sts2.dll`、重签 APK；用于 Android Harmony/MonoMod 场景（lines 1-190）。

## Start Here

先打开 `../s2/android/build/src/com/godot/game/GameSettingsActivity.java`。它是附加设置主入口，能直接看到 Activity 生命周期、首次欢迎向导、底部导航、四个页面、导入导出回调与异步执行方式；下一步再跟到 `ExtraSettingsRepository.java` 看所有数据/文件操作。若目标是重新移植，第二个应打开 `../s2/android/build/AndroidManifest.xml` 与 `../s2/android/build/build.gradle`，因为入口、权限、依赖和 FileProvider 都在这里。

## Constraints, Risks, and Open Questions

1. **`android/build/` 是 Godot Android build template**：这里既有自定义 Java/资源，也有 Godot 导出生成/缓存内容。重新导出或换 Godot 模板可能覆盖/冲突；移植时应拆成可复用补丁或维护自定义模板，而不要只改生成产物。
2. **当前实际 APK 有两个 launcher activity**：debug/release overlay 给 `GodotApp` 加 MAIN/LAUNCHER，主 Manifest 给 `GameSettingsActivity` 加 MAIN/LAUNCHER。若新移植只想保留一个图标，需要明确删除/保留哪一个。
3. **shortcuts 硬编码包名**：`settings_shortcuts.xml` 与 `game_shortcuts.xml` 的 `android:targetPackage="com.megacrit.sts2"` 不使用 `${applicationId}`；包名变更时必须同步。
4. **FileProvider 范围较宽**：`file_paths.xml` 包含 `<files-path path="/">`、`external-path path="."`、external-cache/public paths。结合文件浏览器/分享/导出，需重新评估安全边界与 URI 暴露面。
5. **`activity_game_settings.xml` 可能是遗留资源**：主 Activity 当前全部程序化构建 UI。不要误判它是实际 UI；但 port 时仍需确认是否有旧代码或 preview 依赖。
6. **账号目录推断脆弱**：Java 仓库选择 `files/default` 下第一个子目录，否则 `default/1`；C# 侧 `UserDataPathProvider` 用 `user://default/<PlatformUtil.GetStoragePlayerId()>`。如果 Godot 存储 player ID、custom platform player ID、多账号目录策略改变，Java 可能写错 `settings.save`。
7. **`settings.save` schema 必须保持精确**：Java JSON key、C# `JsonPropertyName`、枚举字符串、迁移逻辑需要一起搬；否则图形/触屏/LAN/MOD 设置会静默失效或被默认值覆盖。
8. **renderer 不在 `settings.save`**：`RendererPreference` 使用 `SharedPreferences sts2_extra_settings.preferred_renderer`，只通过 `GodotApp.getCommandLine()` 生效。只导入/导出存档 ZIP 不会迁移 renderer；完整备份才会带 shared prefs。
9. **解锁全部是跨进程协议**：Java 只写 `pending_unlock_all.flag`；真正修改进度在 C# `AndroidExternalSettingsApplier` 启动阶段完成。只搬 Java 会让按钮无效。
10. **完整数据备份/恢复风险高**：`exportFullDataBackup`/`importFullDataBackup` 操作整个 app data root，恢复时会删除/覆盖除临时目录外的子项；需要严格测试失败回滚、版本兼容和用户确认文案。
11. **MOD/存档导入导出涉及递归删除与 zip 解压**：路径穿越、覆盖、损坏 zip、DocumentFile 权限、manifest JSON 缺失都会影响用户数据；现代码已有部分 guard，但移植前应做专项审计。
12. **Crash/health tracker 依赖链**：`Sts2Application`、CustomActivityOnCrash provider、`Sts2CrashActivity`、`StartupHealthTracker`、Manifest `application android:name` 必须一起搬。代码中 `consumePendingLaunchWarning()` 目前未发现调用，启动失败提醒可能未完整接入。
13. **Gradle 依赖必须同步**：Material/AppCompat/AndroidX/DocumentFile/customactivityoncrash/MTDataFilesProvider/FMOD/Godot template AAR 都是编译或运行依赖；少任一项可能导致 Activity、主题、FileProvider 或 crash handler 崩溃。
14. **release build 目前 `debuggable=true`**：`build.gradle` lines 170-183 显式把 release 设为 debuggable 并不混淆；这是移植版调试友好但发布风险高的选择。
15. **构建脚本强耦合本机环境**：脚本依赖本地 `.tools/Godot_v4.5.1-stable_mono_linux_x86_64/Godot_v4.5.1-stable_mono_linux.x86_64`、Android SDK/JDK、NuGet runtime 路径，并会写入 patched Mono runtime；CI/他人机器需要重做环境抽象。
16. **FMOD/Spine/MonoMod 与 Android native 层强耦合**：`tools/android_launcher_runtime/arm64-v8a/libmonosgen-2.0.so`、`android/build/libs/*`、`assets/.godot/mono/publish/arm64`、publicizer 都和 Godot 4.5.1 Mono、FMOD、Harmony/MonoMod 版本相关。`android/build/native/monomod_android_libc_shim.c` 存在但当前脚本说明“默认 APK 不再打 libc shim”，需确认是否仍需编译/加载。
17. **更新检查依赖外部网页格式**：`ExtraSettingsUpdateChecker` 解析夸克分享页中文文件名格式和 hardcoded URL；网页结构/命名变化会失效，且依赖 `INTERNET` 权限。
18. **资源本地化不完整**：`values-zh/strings_game_settings.xml` 只覆盖一部分字符串；大量 UI fallback 到英文或硬编码文本。若做中文发行，应扩充 `values-zh`。
19. **不要搬运导出 assets 当源码**：`android/build/assets` 包含导出后的 Godot 项目、`.godot/mono` publish、`assets.sparsepck`，多数应由 Godot export 再生成；真正要维护的是 Java/Gradle/Manifest/res 与 Godot/C# 源码改动。
