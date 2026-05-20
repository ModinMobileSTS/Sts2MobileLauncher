# 附加设置搬运清单

日期：2026-05-20  
来源：`../s2/android/build/`  
目标：新重构版 Android shell / launcher 工程

## 1. 搬运目标

旧移植版的 Android 附加设置不是普通 Godot 资源，而是 Godot Android build 目录中的 Java Activity + Android 资源 + Manifest 配置。重构版应先把这部分原样搬进新 APK，使 APK 默认入口仍然是附加设置页，游戏/启动器作为二级入口启动。

核心行为：

- 首次启动显示欢迎/预设配置流程。
- 底部导航：Game / Mods / Settings / About。
- 写入 `settings.save` 中的 Android/移动端字段。
- 管理存档导入导出、完整数据备份、mods、日志、文件浏览。
- 游戏内可通过 Java bridge 回到设置页；Quit 后可 hard restart 回设置页。

## 2. Java 文件清单

从 `../s2/android/build/src/com/godot/game/` 搬运以下文件：

```text
AboutPage.java
AppBarContentOverlapHelper.java
ExtraSettingsActions.java
ExtraSettingsPreferences.java
ExtraSettingsRepository.java
ExtraSettingsUi.java
ExtraSettingsUpdateChecker.java
FileBrowserActivity.java
FileBrowserSupport.java
GamePage.java
GameSettingsActivity.java
GodotApp.java
LogFileViewerActivity.java
LogViewerActivity.java
ModsPage.java
RendererPreference.java
RepeatSelectionSpinner.java
SettingsPage.java
StartupHealthTracker.java
Sts2Application.java
Sts2CrashActivity.java
TextEditorActivity.java
WelcomeSetupPage.java
```

建议：

- M1 阶段先保持 package `com.godot.game`，减少 `AndroidExternalSettingsLauncher.cs` / `AndroidProcessRestart.cs` 的兼容工作。
- 如果最终 app id 改为其他值，也尽量保留 `com.godot.game.GodotApp` 类或 wrapper，作为旧 C# helper 的兼容桥。

## 3. Android 资源清单

### 3.1 必搬资源目录

从 `../s2/android/build/res/` 搬运：

```text
drawable/
layout/
menu/
values/colors_sts2_crash.xml
values/strings_crash.xml
values/strings_game_settings.xml
values/themes.xml
values/themes_sts2_crash.xml
values-zh/strings_crash.xml
values-zh/strings_game_settings.xml
xml/file_paths.xml
xml/game_shortcuts.xml
xml/settings_shortcuts.xml
```

如果直接沿用旧 icon，则也搬运：

```text
mipmap*/icon*.png
mipmap-anydpi-v26/icon.xml
values*/godot_project_name_string.xml
```

### 3.2 drawable 资源

旧附加设置当前引用/携带的 drawable：

```text
bg_sts2_crash_badge.xml
bg_sts2_crash_details.xml
bg_sts2_crash_icon.xml
ic_add_circle_24.xml
ic_arrow_forward_24.xml
ic_article_24.xml
ic_aspect_ratio_24.xml
ic_auto_awesome_24.xml
ic_badge_24.xml
ic_blur_on_24.xml
ic_bolt_24.xml
ic_build_24.xml
ic_check_circle_24.xml
ic_close_24.xml
ic_code_24.xml
ic_compare_arrows_24.xml
ic_controller_24.xml
ic_dashboard_24.xml
ic_delete_24.xml
ic_desktop_windows_24.xml
ic_download_24.xml
ic_edit_24.xml
ic_error_outline_24.xml
ic_expand_less_24.xml
ic_expand_more_24.xml
ic_extension_24.xml
ic_extra_settings_gear.xml
ic_folder_24.xml
ic_gamepad_24.xml
ic_gesture_24.xml
ic_groups_24.xml
ic_high_quality_24.xml
ic_info_24.xml
ic_keyboard_24.xml
ic_layers_24.xml
ic_list_24.xml
ic_lock_open_24.xml
ic_mood_24.xml
ic_more_vert_24.xml
ic_open_in_new_24.xml
ic_person_24.xml
ic_phone_android_24.xml
ic_remove_circle_24.xml
ic_restart_alt_24.xml
ic_rocket_launch_24.xml
ic_save_24.xml
ic_search_24.xml
ic_settings_24.xml
ic_sort_24.xml
ic_speed_24.xml
ic_sync_24.xml
ic_text_fields_24.xml
ic_touch_app_24.xml
ic_tune_24.xml
ic_upload_file_24.xml
ic_volume_up_24.xml
ic_zoom_in_24.xml
```

### 3.3 layout 资源

```text
activity_file_browser.xml
activity_game_settings.xml
activity_log_file_viewer.xml
activity_log_viewer.xml
activity_sts2_crash.xml
activity_text_editor.xml
item_file_browser_entry.xml
item_log_entry.xml
```

### 3.4 menu 资源

```text
menu_extra_settings_nav.xml
menu_file_browser.xml
menu_file_browser_selection.xml
menu_log_selection.xml
menu_log_viewer.xml
menu_text_editor.xml
```

## 4. Manifest 配置要点

从 `../s2/android/build/AndroidManifest.xml` 合并：

- `application android:name=".Sts2Application"`
- `GameSettingsActivity`
  - `exported=true`
  - `screenOrientation=portrait`
  - 作为默认 `MAIN` / `LAUNCHER` 入口
  - metadata: `@xml/settings_shortcuts`
- `GodotApp`
  - 作为真正游戏/launcher runtime Activity
  - `launchMode=singleInstancePerTask`
  - `screenOrientation=landscape` 或后续改 `sensorLandscape`
  - metadata: `@xml/game_shortcuts`
- `Sts2CrashActivity`
- `LogViewerActivity`
- `LogFileViewerActivity`
- `FileBrowserActivity`
- `TextEditorActivity`
- `FileProvider` with `${applicationId}.fileprovider` and `@xml/file_paths`

权限基线：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

如果沿用 Launcher Mod Manager 的外置 Mods/Logs 路径，还需要评估：

```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

## 5. Gradle 依赖要点

旧附加设置需要的额外依赖已在 `../s2/android/build/build.gradle`：

```gradle
implementation "androidx.recyclerview:recyclerview:1.3.2"
implementation "androidx.documentfile:documentfile:1.0.1"
implementation "cat.ereza:customactivityoncrash:2.4.0"
implementation "com.google.android.material:material:1.12.0"
debugImplementation 'com.github.L-JINBIN:MTDataFilesProvider:v1.0.0'
devImplementation 'com.github.L-JINBIN:MTDataFilesProvider:v1.0.0'
releaseImplementation 'com.github.L-JINBIN:MTDataFilesProvider:v1.0.0'
```

Launcher manager 的 Android shell 目前只包含 fragment/splashscreen；合并附加设置时必须补上以上依赖，否则 Log/File/Material UI 会编译失败。

## 6. `settings.save` 字段兼容

`ExtraSettingsRepository.createDefaultSettingsJson()` / `ensureAndroidCompanionDefaults()` 会写入或补齐以下关键字段：

```text
schema_version
fullscreen
aspect_ratio
target_display
resize_windows
fps_limit
msaa
shader_compatibility_mode
vsync
window_position
window_size
fullscreen_render_size
preload_enabled
global_scale
ui_font_scale_percent
show_more_hand_card_text
show_more_hand_card_text_lift_height_percent
touch_lift_preview
touch_lift_retap_action
mobile_selection_confirmation
mobile_two_finger_inspect
show_mobile_emoji_button
lan_multiplayer_enabled
lan_compatibility_mod_names
audio_compatibility_mode
android_volume_up_soft_keyboard
android_flip_screen_180
lan_use_custom_player_id
lan_use_custom_platform_player_id
lan_custom_player_id
lan_join_host
lan_join_port
max_multiplayer_players
max_multiplayer_enabled
quick_sl_enabled
mod_settings
android_graphics_preset
android_display_preset
```

MOD 化阶段必须保证这些字段至少不会被原版游戏覆盖/删除；实际行为由适配 MOD 读取并应用。

## 7. M1 验证清单

- [ ] 新 APK 默认进入 `GameSettingsActivity`。
- [ ] 首次启动欢迎页可完成。
- [ ] 修改图形/显示/输入/系统/LAN 设置后，`settings.save` 更新。
- [ ] `adb shell run-as <package> ls files/default/1` 可看到 `settings.save`。
- [ ] Game 页点击启动能进入 Godot/launcher Activity（即使 M1 还不能启动游戏）。
- [ ] 日志页、文件页、About 页不崩。
- [ ] “解锁全部”能生成 `pending_unlock_all.flag`。

## 8. 风险

- 旧附加设置目前直接读写 Godot 游戏使用的私有目录结构；重构启动器必须保持同一目录语义，或在 repository 中集中改路径。
- 如果包名从 `com.godot.game` 改掉，旧 C# helper 调 Java 类会失效。
- `MANAGE_EXTERNAL_STORAGE` 不适合 Play 分发；若要上架，需要后续改为 SAF-only 外置内容管理。
- `ExtraSettingsUpdateChecker` 是旧移植版更新检查逻辑；重构版初期可保留但需要确认下载链接和版本语义。
