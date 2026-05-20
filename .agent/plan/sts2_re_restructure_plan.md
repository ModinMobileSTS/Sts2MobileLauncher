# sts2 重构移植版计划（launcher / settings first, MOD second）

日期：2026-05-20  
工作目录：`/mnt/datas/agent_workspace/s2_re`  
参考项目：

- 现有移植版：`../s2/`
- PC 原版解包/工程：`../s2_original/s21032/`
- 现有 Android Launcher/Mod Manager 参考：`../s2/.cache/StS2-Launcher_Mod_Manager/`
- 直装版内置游戏包候选：`../s2_pc/Slay the Spire 2.zip`

配套报告（已写入 `.agent/reports/`，用于实现时逐项核对）：

- `.agent/reports/source-baseline.md`：参考输入版本、hash、dirty 状态。
- `.agent/reports/extra-settings-inventory.md`：附加设置 Java/资源/Manifest/Gradle 搬运清单。
- `.agent/reports/launcher-mod-manager-scout.md`：Launcher/Mod Manager 结构、启动链路、SteamKit/patch 管线侦察。
- `.agent/reports/local-payload-import-plan.md`：本地 zip 导入、payload manifest、直装解压细化方案。
- `.agent/reports/port-diff-inventory.md`：`../s2` vs `../s2_original/s21032` 初步差异分类。

## 0. 总目标与原则

目标是在本工作目录制作一个新的、重构后的 Slay the Spire 2 Android 移植版：

1. **Android 附加设置保留**：尽量原模原样搬运 `../s2/` 的 Android 附加设置页、首次启动引导、存档/Mod/日志/文件管理能力。
2. **启动器与游戏本体分离**：不再通过“完整重新编译游戏工程”完成移植，而是让 APK/启动器负责：
   - 管理私有目录中的游戏本体；
   - 从本地 zip 导入/解压游戏；
   - 加载 Android 运行时、启动 Godot/C# 游戏；
   - 在游戏启动前注入必要的 Android 适配 MOD/patcher。
3. **适配操作 MOD 化**：通过 `../s2/` 与 `../s2_original/s21032/` 的 diff，把移植适配尽量提取为一个可维护的 Android 适配 MOD / Harmony patcher / 资源 overlay；打上后尽量与原移植版表现一致。
4. **支持直装版**：提供一种构建变体，内置一个游戏压缩包（例如 `../s2_pc/Slay the Spire 2.zip`），首次打开自动解压到 app 私有目录。
5. **可验证路径优先**：先做“附加设置 + 启动器 + 本地包导入/解压/启动”闭环，再逐步做适配 MOD；每一阶段有明确产物、验证命令/操作和 git 提交点。

非目标 / 约束：

- 不把 PC 游戏完整资源或用户提供的 zip 直接提交进 git；直装包只能作为本地构建输入或受控 release artifact。
- 不依赖 SteamCMD；本地导入 zip 是主路径。
- 不在第一阶段追求适配完全可玩；第一阶段先证明设置页、导入、私有目录、启动器状态机和运行时加载链路可独立工作。
- 游戏本体尽量保持原版文件；允许编译启动器、Android Java/Kotlin、patcher/MOD，不重新编译整份游戏源码。

---

## 1. 已确认的可复用材料

### 1.1 `../s2/` 附加设置相关

现有附加设置主要在 Godot Android build 目录内（Java + Android 资源）：

- Java 入口与页面：
  - `../s2/android/build/src/com/godot/game/GameSettingsActivity.java`
  - `../s2/android/build/src/com/godot/game/GamePage.java`
  - `../s2/android/build/src/com/godot/game/SettingsPage.java`
  - `../s2/android/build/src/com/godot/game/ModsPage.java`
  - `../s2/android/build/src/com/godot/game/AboutPage.java`
  - `../s2/android/build/src/com/godot/game/WelcomeSetupPage.java`
- 设置/文件/状态支撑：
  - `ExtraSettingsRepository.java`
  - `ExtraSettingsActions.java`
  - `ExtraSettingsUi.java`
  - `ExtraSettingsPreferences.java`
  - `RendererPreference.java`
  - `StartupHealthTracker.java`
  - `Sts2Application.java`
  - `Sts2CrashActivity.java`
  - `LogViewerActivity.java` / `LogFileViewerActivity.java`
  - `FileBrowserActivity.java` / `FileBrowserSupport.java`
  - `TextEditorActivity.java`
  - `RepeatSelectionSpinner.java`
  - `ExtraSettingsUpdateChecker.java`
  - `AppBarContentOverlapHelper.java`
- Android 资源：
  - `../s2/android/build/res/drawable/*`
  - `../s2/android/build/res/menu/*`
  - `../s2/android/build/res/values/strings_game_settings.xml`
  - `../s2/android/build/res/values-zh/strings_game_settings.xml`
  - `../s2/android/build/res/xml/file_paths.xml`
  - `../s2/android/build/res/xml/game_shortcuts.xml`
  - `../s2/android/build/res/xml/settings_shortcuts.xml`
- Manifest 中需要保留/迁移的 activity/provider：
  - `GameSettingsActivity` 作为默认 launcher 入口；
  - `GodotApp` 作为真正游戏入口；
  - crash/log/file/text editor 相关 activity；
  - `FileProvider`。

现有游戏内 C# 侧与附加设置通信点：

- `../s2/src/Core/Helpers/AndroidExternalSettingsLauncher.cs`
  - 调用 `com.godot.game.GodotApp.launchGameSettingsFromGame`
- `../s2/src/Core/Helpers/AndroidProcessRestart.cs`
  - 调用 `com.godot.game.GodotApp.restartToSettingsFromGame`
- `../s2/src/Core/Helpers/AndroidExternalSettingsApplier.cs`
  - 消费 `pending_unlock_all.flag`，应用“解锁全部”等命令。
- `../s2/src/Core/Saves/SettingsSave.cs`
  - 现有移植版新增了很多 Android/移动端字段，例如：
    - `shader_compatibility_mode`
    - `preload_enabled`
    - `global_scale`
    - `ui_font_scale_percent`
    - `show_more_hand_card_text`
    - `show_more_hand_card_text_lift_height_percent`
    - `touch_lift_preview`
    - `touch_lift_retap_action`
    - `mobile_selection_confirmation`
    - `mobile_two_finger_inspect`
    - `show_mobile_emoji_button`
    - `lan_multiplayer_enabled`
    - `quick_sl_enabled`
    - `android_volume_up_soft_keyboard`
    - `android_flip_screen_180`
    - `fullscreen_render_size`
    - `audio_compatibility_mode`
    - LAN 自定义 ID / host / port / compatibility mod names 等。

重构版必须保证这些字段继续由附加设置写入；适配 MOD 需要能读取/应用这些字段，即使 PC 原版 `SettingsSave` 本身没有对应属性。

### 1.2 `StS2-Launcher_Mod_Manager` 可复用思路

参考项目路径：`../s2/.cache/StS2-Launcher_Mod_Manager/`

核心结构：

- Android 入口：
  - `android/src/com/game/sts2launcher/modmanager/GodotApp.java`
- Patcher / Launcher C#：
  - `src/STS2Mobile/ModEntry.cs`
  - `src/STS2Mobile/Launcher/LauncherUI.cs`
  - `src/STS2Mobile/Launcher/LauncherModel.cs`
  - `src/STS2Mobile/Patches/*.cs`
  - `src/STS2Mobile/AppPaths.cs`
  - `src/STS2Mobile/Modding/SafBridge.cs`
  - `src/STS2Mobile/Modding/ModImporter.cs`
- 可借鉴能力：
  - Android `GodotApp` 在 `getFilesDir()/game` 管理游戏文件；
  - Java 侧负责 FMOD / crypto native lib / .NET assembly setup / PCK 加载；
  - C# `ModEntry` 通过 Harmony 在游戏启动前应用 patch；
  - 启动器 UI 与游戏本体分离；
  - 外置 Mods、日志、缓存、atlas wipe、运行时诊断等机制。

需要替换/删除的部分：

- Steam 登录、SteamKit2、depot manifest 下载、ownership marker、cloud save 强绑定不作为主路径；
- 本地 zip 导入/直装包解压替代 Steam depot 下载；
- 更新检查只保留启动器自身更新（可选），不做 Steam 游戏更新；
- Save/cloud 能力如果保留，必须拆成可选模块，不能阻塞本地导入启动。

### 1.3 本地 zip 结构初步确认

`../s2_pc/Slay the Spire 2.zip` 内至少包含：

- `SlayTheSpire2.pck`
- `release_info.json`
- `data_sts2_windows_x86_64/sts2.dll`
- `data_sts2_windows_x86_64/*.dll`
- 可能还有 `mods/*`、controller config、exe、crashpad 等 PC 文件。

重构启动器应从 zip 中抽取/规范化 Android 运行需要的子集，而不是盲目依赖 Steam depot 目录结构。

---

## 2. 推荐新项目结构

建议在当前目录建立如下结构：

```text
s2_re/
  .agent/
    plan/
      sts2_re_restructure_plan.md
    reports/                 # subagent / diff / 验证报告
  docs/
    inventory/               # diff 清单、搬运清单
    validation/              # 测试记录、logcat 记录、截图说明
  android/                   # 新 Android shell/launcher 工程
    app/
    gradle/
  launcher/                  # 启动器 UI / 本地包管理 / Godot bootstrap C#
  android-extra-settings/    # 从 ../s2 搬来的附加设置 Java/资源，可作为模块或源码集
  port-mod/                  # Android 适配 MOD / Harmony patcher
  runtime/                   # Android 必要 runtime/stub，禁止提交受限大二进制，按脚本生成/复制
  tools/
    diff/
    package/
    adb/
  local-inputs/              # gitignore；本地 zip / keystore / 私有依赖
  build/                     # gitignore
```

首阶段可以不一次性搭完整目录，但要先把职责拆清楚：

- `android-extra-settings`：原移植版附加设置，尽量小改包名/路径即可复用。
- `launcher`：导入、解压、校验、启动状态机。
- `port-mod`：只负责“让原版游戏在 Android 表现为移植版”的差异补丁。
- `runtime`：Android .NET/Godot/FM0D/native stubs 等非游戏本体运行层。

---

## 3. 分阶段实施清单（可验证路径）

### M0：仓库与基线清单

目标：让工作目录成为可回滚、可审查的重构仓库，并固化参考输入。

清单：

- [ ] `git init` 当前目录。
- [ ] 添加 `.gitignore`：排除 `build/`、`local-inputs/`、`*.apk`、`*.aab`、大 zip、keystore、Android/Gradle 缓存、临时解包目录。
- [ ] 提交本计划：`git add .agent/plan/sts2_re_restructure_plan.md && git commit -m "Add sts2 restructure plan"`。
- [ ] 记录参考路径和哈希：
  - [ ] `../s2` 当前 git commit / dirty 状态；
  - [ ] `../s2_original/s21032` 文件清单 hash；
  - [ ] `../s2_pc/Slay the Spire 2.zip` size/hash；
  - [ ] `../s2/.cache/StS2-Launcher_Mod_Manager` commit/hash。
- [x] 写 baseline 报告：`.agent/reports/source-baseline.md`。
- [ ] 后续如建立正式 `docs/inventory/`，把 `.agent/reports/source-baseline.md` 同步/整理过去。

验证：

- [ ] `git status --short --branch` 干净。
- [x] `.agent/reports/source-baseline.md` 能说明“我现在基于哪个原版、哪个旧移植版、哪个 zip”。

### M1：附加设置先搬通

目标：新 APK 打开后先进入与旧移植版一致的附加设置界面；能写入同样的 `settings.save` / SharedPreferences / pending flag。

清单：

- [ ] 从 `../s2/android/build/src/com/godot/game/` 搬运附加设置 Java 文件。
- [ ] 从 `../s2/android/build/res/` 搬运相关资源：drawable、menu、values、values-zh、xml shortcuts/file_paths。
- [ ] 搬运/合并 Manifest 中的 activity/provider 配置。
- [ ] 保留 `GameSettingsActivity` 作为默认 `LAUNCHER` activity。
- [ ] 保留或适配 `GodotApp.createLaunchIntent(...)`，使“启动游戏”按钮先能跳转到新启动器/游戏 Activity。
- [ ] 保留 `ExtraSettingsRepository` 写入路径语义：
  - [ ] `getFilesDir()/default/<account>/settings.save`
  - [ ] `pending_unlock_all.flag`
  - [ ] mods/save/log/full-data backup 目录。
- [ ] 明确包名策略：
  - 方案 A：继续使用 `com.godot.game`，最大化兼容旧 C# helper；
  - 方案 B：使用新包名，同时提供 Java 兼容桥或让适配 MOD patch C# 调用新类名。
- [ ] 对第一阶段尽量选择方案 A 或提供 `com.godot.game.GodotApp` 兼容入口，减少后续 MOD 工作量。

验证：

- [ ] 安装 APK 后默认进入附加设置页，而不是直接进入游戏。
- [ ] 首次启动引导可完成并持久化。
- [ ] 修改设置后，`adb shell run-as <package> ls files/default/1` 可看到 `settings.save`。
- [ ] `settings.save` 中存在旧移植版附加设置字段。
- [ ] “解锁全部”只写 pending flag，不要求本阶段游戏内立即应用。
- [ ] 日志/文件浏览页面可打开，不崩溃。

建议 git 提交：

- `Port extra settings shell from existing Android build`
- `Verify extra settings persistence`

### M2：本地游戏包导入与私有目录管理

目标：不用 SteamCMD / SteamKit 下载，从本地 zip 导入游戏到 app 私有目录，并形成可重复校验的 payload manifest。

清单：

- [ ] 设计私有目录：
  - [ ] `<files>/game/SlayTheSpire2.pck`
  - [ ] `<files>/game/release_info.json`
  - [ ] `<files>/game/data_sts2_windows_x86_64/sts2.dll`
  - [ ] `<files>/game/data_sts2_windows_x86_64/*.dll`（只复制运行需要的 managed 依赖；Windows native 可过滤）
  - [ ] `<files>/game/mods/`（可选导入 zip 中自带 mods，默认可提示/隔离）
  - [ ] `<files>/game/.payload_manifest.json`
- [ ] 在附加设置或 launcher 页面增加“导入游戏 zip”入口。
- [ ] 使用 SAF `ACTION_OPEN_DOCUMENT` 选择 zip。
- [ ] 实现安全解压：
  - [ ] 防 Zip Slip：每个 entry canonical path 必须在目标目录内；
  - [ ] 临时目录解压，成功校验后 atomic rename；
  - [ ] 进度回调；
  - [ ] 失败回滚。
- [ ] 校验规则：
  - [ ] 必须存在 `SlayTheSpire2.pck` 且前 4 字节为 `GDPC`；
  - [ ] 必须存在 `release_info.json`；
  - [ ] 必须存在 `data_sts2_windows_x86_64/sts2.dll`；
  - [ ] 记录 zip 文件名、大小、sha256、release version/commit。
- [ ] 替换 `StS2-Launcher_Mod_Manager` 里的 Steam 下载状态机：
  - [ ] `StartDownloadAsync` -> `ImportZipAsync`；
  - [ ] `CheckForUpdatesAsync` -> 本地 manifest / 重新导入；
  - [ ] ownership marker -> 本地 payload ready marker；
  - [ ] Steam credentials UI -> 本地导入/启动 UI。
- [ ] 保留“清除游戏本体/重新导入”按钮。

验证：

- [ ] 从 `../s2_pc/Slay the Spire 2.zip` 手动导入成功。
- [ ] 私有目录生成规范化文件树。
- [ ] `.payload_manifest.json` 能说明导入版本、hash、文件数量。
- [ ] 退出/重开 app 后能识别“游戏已导入”。
- [ ] 损坏 zip / 缺 pck / zip slip 测试用例能失败并回滚。

建议 git 提交：

- `Add local game zip importer`
- `Normalize imported game payload layout`
- `Add payload validation and manifest`

### M3：启动器最小闭环（不追求完全适配）

目标：在已有 payload 时，启动器能加载 bootstrap / game pck / sts2.dll，至少到达“开始游戏尝试”阶段；即使游戏因缺适配崩，也要有可诊断日志。

清单：

- [ ] 基于 `StS2-Launcher_Mod_Manager` 的 Android `GodotApp.java` 搭建最小运行链路：
  - [ ] FMOD native lib 加载；
  - [ ] `System.Security.Cryptography.Native.Android` 加载；
  - [ ] `getFilesDir()/game` 路径；
  - [ ] `setupAssemblies` / managed DLL 同步；
  - [ ] PCK 加载；
  - [ ] logcat capture（可默认关闭，由设置开启）。
- [ ] 启动器 UI：
  - [ ] 无 payload -> 显示“导入 zip / 直装解压 / 查看日志”；
  - [ ] 有 payload -> 显示版本信息、启动游戏、清除并重新导入；
  - [ ] 失败 -> 明确错误和日志入口。
- [ ] 不加载适配 MOD 时，允许游戏无法完全运行；但必须能看见失败原因。
- [ ] 保留回到附加设置页能力。

验证：

- [ ] 无 payload 启动不会黑屏，能引导导入。
- [ ] 有 payload 后点击启动，日志显示 PCK / sts2.dll / release_info 加载路径。
- [ ] 如果崩溃，`Sts2CrashActivity` 或日志页能看到异常。
- [ ] 重启后仍可回到附加设置页重新清理/导入。

建议 git 提交：

- `Add minimal launcher state machine`
- `Wire Godot runtime to imported payload`

### M4：直装版构建变体

目标：构建一个内置游戏 zip 的 APK/AAB/本地安装包，首次打开自动解压，无需用户手动导入。

清单：

- [ ] 明确直装包输入不进 git：`local-inputs/Slay the Spire 2.zip` 或构建参数 `STS2_PAYLOAD_ZIP=../s2_pc/Slay the Spire 2.zip`。
- [ ] 构建脚本复制 zip 到：
  - 小包/本地测试：`android/app/src/main/assets/payload/SlayTheSpire2.zip`；
  - 大包/release：优先评估 Play Asset Delivery / OBB / split asset pack，避免 APK size / 安装器限制。
- [ ] App 首次启动：
  - [ ] 若 `<files>/game/.payload_manifest.json` 不存在或版本不匹配，显示“正在解压内置游戏”；
  - [ ] 流式解压 assets zip 到临时目录；
  - [ ] 校验与 M2 一致；
  - [ ] 成功后写 manifest。
- [ ] 如果用户后来手动导入新 zip，要覆盖直装 payload 并更新 manifest。
- [ ] 加入“重置为内置版本”按钮（可选）。

验证：

- [ ] `pm clear <package>` 后第一次启动自动解压。
- [ ] 断点/失败重开不会留下半解压坏目录。
- [ ] 解压完成后可进入 M3 启动器 ready 状态。
- [ ] 手动导入可覆盖内置版本。

建议 git 提交：

- `Add bundled payload extraction flow`
- `Add direct-install build script`

### M5：移植差异清单与 MOD 切分

目标：在真正写适配 MOD 前，把 `../s2` 相对 `../s2_original/s21032` 的差异分层，避免把“重新编译工程产生的噪音”错误搬进 MOD。

清单：

- [ ] 写 diff 盘点脚本：
  - [ ] 忽略 `.git/`、`.godot/`、`build/`、`.gradle/`、`.cache/`、`bin/`、`obj/`、临时 import cache；
  - [ ] 单独统计 `src/`、`scenes/`、`shaders/`、`project.godot`、`android/`、`addons/`、`themes/`、`images/packed` 等。
- [ ] 产出 `docs/inventory/port-diff-inventory.md`：
  - [ ] 新增源码文件；
  - [ ] 修改源码文件；
  - [ ] 修改 scene/resource；
  - [ ] Android Java/Manifest/resource；
  - [ ] native/runtime 依赖；
  - [ ] 生成噪音/可忽略项。
- [ ] 将差异拆为四类：
  1. **Launcher/runtime 层**：Android Godot/.NET/FM0D/native/stub，不能作为普通游戏 MOD；
  2. **Harmony patch 层**：可在运行时 patch 原版 `sts2.dll` 的行为；
  3. **资源 overlay 层**：shader、scene、pck 资源需要通过 overlay pck 或资源替换注入；
  4. **附加设置数据层**：`settings.save` JSON 字段、pending flag、mod profile 等。
- [ ] 对每项差异标注：
  - [ ] “必须首版实现 / 可后续 / 可放弃”；
  - [ ] 依赖的设置字段；
  - [ ] 验证方法；
  - [ ] 是否能与原版游戏版本变更兼容。

初步重点差异区域（需要 M5 详细确认）：

- 移动设置/保存：`SettingsSave.cs`、settings migrations、`AndroidExternalSettings*`。
- 输入/触控：`AndroidControllerInputCompat.cs`、`NInputManager.cs`、`NMouseCardPlay.cs`、`NPlayerHand.cs`、selection confirmation、two-finger inspect。
- UI/布局：settings screen、main menu、card reward、merchant、event、map、top bar、timeline、font scale/global scale。
- Shader/渲染兼容：`MobileShaderCompatibility.cs`、`shaders/mobile_compat/*`、vfx shader 修改。
- 音频/FM0D：`audio_manager_proxy.gd`、FMOD Android libs、audio compatibility mode。
- Mod loader：`ModManager.cs`、Harmony Android compat、publicize/reflection compat、external Mods dir。
- LAN/多人：LAN transport / discovery / custom player id。
- 生命周期/重启/后台：quit 后回设置页、focus、mute/background、quick SL。
- Release info / path / saves：Android 私有目录、`release_info.json` 读取、settings/profile/progress 路径。

验证：

- [ ] 清单能回答：“旧移植版的每个关键行为由哪个 patch 或资源 overlay 负责？”
- [ ] 清单能区分：“哪些必须编译 launcher/runtime，哪些只是 MOD”。

建议 git 提交：

- `Add port diff inventory tooling`
- `Classify Android port changes for mod extraction`

### M6：适配 MOD / patcher 第一版

目标：把最小可玩所需适配做成内置 Android port compat MOD，而不是修改游戏源码。

设计建议：

- MOD 名称：`sts2_android_port_compat`（暂定）。
- 加载时机：游戏 `sts2.dll` 加载后、游戏主初始化前；优先于用户 mods。
- 形态：
  - Harmony patcher DLL：主要行为 patch；
  - overlay PCK：必要 shader/scene/resource 替换；
  - Java bridge：附加设置、重启、软键盘、方向、日志等 Android 方法。
- 设置读取：
  - 不依赖 PC 原版 `SettingsSave` 拥有移动字段；
  - 增加 `AndroidSettingsBridge` 直接读取 `settings.save` JSON，提供 typed accessor；
  - 原版已有设置仍走原逻辑，移动新增字段由 MOD 应用。

首版最小功能清单：

- [ ] Platform patch：禁用/替换 Steam 初始化、Sentry Android 不可用路径、release info 路径。
- [ ] Save/path patch：确保 `settings.save`、profile/progress 与附加设置同目录语义一致。
- [ ] Settings bridge：读取并应用：
  - [ ] fps/vsync/msaa/fullscreen render size；
  - [ ] shader compatibility mode；
  - [ ] global scale / font scale；
  - [ ] preload enabled；
  - [ ] mobile input flags；
  - [ ] audio compatibility；
  - [ ] LAN flags。
- [ ] Android external settings bridge：
  - [ ] 游戏内“附加设置”按钮能打开 `GameSettingsActivity`；
  - [ ] Quit 后可 hard restart 回设置页；
  - [ ] pending unlock-all flag 能被应用。
- [ ] Touch/input 核心：先实现能点、能拖牌、能选择、能返回。
- [ ] Mobile layout 核心：主菜单、战斗手牌、奖励选牌、商店至少可用。
- [ ] Shader 兼容：先 patch 已知会导致 Android 黑屏/异常的 shader/material。
- [ ] Mod loader 外置 mods dir：兼容附加设置/外置 Mods 管理。

验证：

- [ ] 导入原版 zip + 内置 compat MOD 后能进主菜单。
- [ ] 新建一局，完成至少一场普通战斗。
- [ ] 修改附加设置后重启游戏，行为变化可见：
  - [ ] 分辨率/比例/字体缩放；
  - [ ] 触控相关开关；
  - [ ] shader compatibility；
  - [ ] 音频兼容；
  - [ ] 软键盘快捷键。
- [ ] Quit 回设置页流程稳定。
- [ ] 与旧移植版同一设置组合下做截图/日志对比。

建议 git 提交：

- `Add Android port compat patcher skeleton`
- `Apply settings bridge from companion settings`
- `Patch startup/platform paths for Android payload`
- `Add first playable touch/layout patches`

### M7：逐项追平旧移植版表现

目标：按 M5 清单逐项把旧移植版行为迁移到 MOD/overlay，直到表现接近一致。

清单：

- [ ] UI/布局逐屏验证：
  - [ ] 主菜单；
  - [ ] 角色选择；
  - [ ] 战斗；
  - [ ] 选牌/奖励；
  - [ ] 商店；
  - [ ] 事件；
  - [ ] 地图；
  - [ ] 休息点；
  - [ ] 设置页；
  - [ ] 时间线/统计/牌库/遗物/药水。
- [ ] 输入验证：
  - [ ] 单指点/拖/长按；
  - [ ] 双指 inspect；
  - [ ] 手柄/虚拟键；
  - [ ] volume up 软键盘；
  - [ ] Android back 行为。
- [ ] 渲染验证：
  - [ ] OpenGL ES3 推荐；
  - [ ] Vulkan quality；
  - [ ] shader compatibility on/off；
  - [ ] Fold/宽屏/16:9/21:9。
- [ ] 音频验证：
  - [ ] FMOD 正常；
  - [ ] audio compatibility mode；
  - [ ] 后台静音/恢复。
- [ ] Mod 验证：
  - [ ] 外置 mods 扫描；
  - [ ] mod_config；
  - [ ] BaseLib/RitsuLib/已知问题记录；
  - [ ] 与原 launcher mod manager 的兼容/不兼容差异。
- [ ] LAN/多人验证（可后置）：
  - [ ] 主机发现；
  - [ ] 手动 IP 加入；
  - [ ] 自定义 player id；
  - [ ] PC `--fastmp` 兼容说明。

验证输出：

- [ ] `docs/validation/equivalence-matrix.md`：旧移植版 vs 新重构版功能矩阵。
- [ ] `docs/validation/device-matrix.md`：测试机型、Android 版本、renderer、结论。
- [ ] `docs/validation/known-issues.md`：不能 MOD 化或暂不支持项。

建议 git 提交：按功能小提交，不做“大杂烩”。

### M8：打包、发布与回归

目标：形成普通版（导入 zip）和直装版两个产物，并有稳定回归清单。

清单：

- [ ] 普通版 APK：不含游戏本体，首次引导用户选择 zip。
- [ ] 直装版 APK/AAB/asset-pack：含本地构建输入 zip，首次自动解压。
- [ ] 签名与版本号策略。
- [ ] 清晰免责声明：普通版不包含游戏资源；直装版仅本地/授权分发。
- [ ] 一键构建脚本：
  - [ ] `tools/package/build_importer_apk.sh`
  - [ ] `tools/package/build_direct_apk.sh ../s2_pc/Slay\ the\ Spire\ 2.zip`
- [ ] 一键 smoke test 脚本：
  - [ ] 安装；
  - [ ] `pm clear`；
  - [ ] 推送/导入测试包；
  - [ ] 抓 logcat；
  - [ ] 导出验证报告。

验证：

- [ ] 新安装普通版 -> 设置页 -> 导入 zip -> 启动游戏。
- [ ] 新安装直装版 -> 自动解压 -> 启动游戏。
- [ ] 升级安装不丢设置/存档。
- [ ] `pm clear` 后能重建目录。
- [ ] 崩溃能进入 crash/log 页面。

---

## 4. 关键风险与处理策略

### 4.1 “MOD 化”并不等于所有差异都能普通 MOD 化

旧移植版包含 Android runtime、Godot export、native lib、FMOD、Mono/Android 运行时、Java Activity 等层面改动。这些不能都放进普通游戏 MOD。

处理：按 M5 分类：

- Launcher/runtime 层：放在 APK/Android shell；
- Harmony patch 层：放入 `port-mod`；
- 资源 overlay 层：放 overlay pck；
- 设置数据层：保持 `settings.save` JSON 与 Java repository 兼容。

### 4.2 PC 原版 `SettingsSave` 不认识移动端字段

System.Text.Json 大概率会忽略未知字段，但游戏代码不会主动使用这些字段。

处理：适配 MOD 实现 `AndroidSettingsBridge`，直接读同一个 `settings.save` JSON；需要 patch 的行为从 bridge 获取设置，而不是要求原版类新增属性。

### 4.3 Java 类名/包名兼容

旧 C# helper 调 `com.godot.game.GodotApp`，现有 launcher manager 是 `com.game.sts2launcher.modmanager.GodotApp`。

处理：首选在新项目中保留 `com.godot.game.GodotApp` 兼容入口；如果必须改包名，则在适配 MOD 中 patch 调用目标，或提供 Java wrapper。

### 4.4 直装包体积与合规

游戏 zip 很大，直接放 APK assets 可能遇到安装器、文件系统、签名、分发限制。

处理：

- git 不提交 zip；
- 本地测试可先 assets；
- 正式直装优先研究 asset pack / OBB / split package；
- plan 和构建脚本明确输入来自本地合法副本。

### 4.5 版本变更兼容

提取出的 Harmony patch 如果依赖私有字段名/IL pattern，游戏更新后容易坏。

处理：

- 对每个 patch 写 pattern fallback 与日志；
- 尽量 patch 稳定公开方法/字符串/行为；
- 每个 patch 有“未匹配时安全降级”；
- `release_info.json` 版本写入日志和 manifest；
- 对新版本先跑 M5 inventory，再允许启动。

---

## 5. Git 与 subagent 工作方式

### Git 规则

- 每个里程碑一个分支或清晰提交序列。
- 每个可验证点提交一次，不混入大二进制。
- 每次实现前后运行：

```bash
git status --short --branch
```

- 建议提交节奏：
  1. plan / inventory；
  2. extra settings 搬运；
  3. local zip import；
  4. launcher minimal boot；
  5. direct-install extraction；
  6. diff inventory；
  7. compat mod skeleton；
  8. patch groups by feature。

### Subagent 规则

- 用 `scout` 做只读侦察：旧附加设置、launcher manager、diff 分类。
- 用 `planner` 在 M1/M2/M6 前细化实施方案。
- 用 `worker` 做单线程写入，避免多个 writer 同时改同一工作树。
- 用 fresh-context `reviewer` 做每个里程碑后的 review：
  - correctness/regression；
  - validation/test coverage；
  - simplicity/maintainability。
- 父会话负责合并结论和最终决策；subagent 不直接决定扩大范围。

---

## 6. 第一轮建议执行顺序

1. 完成 M0：初始化 git、提交本 plan、生成 source baseline。
2. 完成 M1：把附加设置页搬进新 Android shell，先不管游戏是否能跑。
3. 完成 M2：实现本地 zip 导入与 manifest。
4. 完成 M3：复用 launcher runtime 链路，做到 payload ready 后能尝试启动并产生日志。
5. 完成 M4：做直装版自动解压。
6. 再进入 M5/M6：正式 diff -> MOD 化。

这样每一步都可验证，且不会在一开始就陷入“适配 MOD 未完成所以整个项目不可测试”的状态。
