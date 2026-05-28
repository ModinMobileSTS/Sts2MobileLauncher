# AGENT.md

面向后续编码代理/维护者的项目速览。当前目录：`/mnt/datas/agent_workspace/s2_re`。

## 1. 项目定位

本项目是 **Slay the Spire 2 Android 重构移植版**，目标不是维护一份完整重编译的游戏源码，而是把 Android 侧拆成三层：

1. **Android shell / launcher / 附加设置**：APK 默认进入附加设置页，负责导入/解压本地 PC 游戏 zip、管理私有目录、启动 Godot Activity、查看日志/文件、备份存档、管理 MOD。
2. **原版游戏 payload**：用户本地提供 `SlayTheSpire2.zip`，导入到 app 私有目录 `<files>/game/`；直装版可在构建时临时内置 zip。
3. **Android 适配 MOD / Harmony patcher**：`port-mod/STS2AndroidPortCompat` 编译成 `STS2Mobile.dll`，由 patched Godot runtime 在启动时加载，对 PC 原版程序集做 Android 适配。

核心原则：**不提交用户游戏 zip、完整游戏 payload、大型运行时二进制或 keystore**；这些通过脚本从参考项目同步或在本地构建时临时放入。

## 2. 参考项目/输入

主要参考输入如下，均在当前 workspace 的相邻目录：

- `../s2/`
  - 现有完整 Android 移植版/旧工程。
  - 复用了其中的附加设置 Java/资源、Godot Android build template 配置、FMOD 资源、local JDK/Android SDK/.NET SDK 等本机环境。
  - 构建环境脚本会读取：
    - `../s2/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64`
    - `../s2/.godot-home/Android/Sdk`
    - `../s2/.local/dotnet/dotnet`
    - `../s2/addons/fmod/libs/android/fmod-release.aar`
- `../s2_original/s21032/`
  - PC 原版/解包工程基线，用于 diff 与兼容 MOD 的 original compile gate。
  - `port-mod/refs/original/*.dll` 是指向这里 `.godot/mono/temp/bin/Debug/` 的本地 symlink。
- `../s2/.cache/StS2-Launcher_Mod_Manager/`
  - 旧 Android Launcher/Mod Manager 参考。
  - `tools/android/sync-runtime-from-references.sh` 会从其中同步 Godot template AAR、`dotnet_bcl`、Gradle wrapper jar 等大型运行时产物。
- `../s2_pc/Slay the Spire 2.zip`
  - 本地测试用 PC 游戏 zip 样例，不提交。
  - 已知版本：`v0.103.2`，commit `89765e1e`，sha256 记录见 `.agent/reports/source-baseline.md`。

相关报告/计划：

- `.agent/plan/sts2_re_restructure_plan.md`：重构计划与分阶段清单。
- `.agent/reports/source-baseline.md`：参考输入版本、hash、dirty 状态。
- `.agent/reports/local-payload-import-plan.md`：本地 zip 导入/直装解压方案。
- `docs/inventory/port-diff-classified.md`：旧移植版 vs PC 原版差异分类。
- `docs/validation/m1-m2-android-shell.md`：Android shell / payload 导入阶段验证快照。
- `context.md`：旧 `../s2` 附加设置与构建系统的长上下文摘要。

## 3. 当前目录结构

```text
s2_re/
  AGENT.md                         # 本文件，给后续 agent 的项目说明
  android/                         # Android shell / Godot Android Gradle 工程根目录
    AndroidManifest.xml            # Activity/provider/权限；GameSettingsActivity 是默认 launcher
    build.gradle                   # Godot Android template 风格应用模块配置
    config.gradle                  # AGP/Kotlin/SDK/NDK/Java 版本与 Godot export property helpers
    gradle.properties              # applicationId、ABI、签名、构建类型等本地属性
    settings.gradle                # pluginManagement + install-time asset pack
    assetPackInstallTime/          # install-time asset pack 占位
    src/com/godot/game/            # Java shell、附加设置、payload 导入、GodotApp 桥
    res/                           # 附加设置/崩溃页/文件浏览器/图标/shortcut/theme 等 Android 资源
    assets/
      bootstrap.pck                # 无游戏 payload 时的最小 Godot bootstrap pack
      port_compat.pck              # Android 适配 overlay pack，脚本生成
      dotnet_bcl/                  # 大型 .NET/Godot runtime DLL，同步生成，gitignore
      payload/                     # 直装版临时内置 zip，gitignore
    libs/                          # Godot/FMOD/template AAR，同步生成，gitignore
  port-mod/                        # Android 适配 MOD / Harmony patcher
    README.md
    STS2AndroidPortCompat/
      STS2Mobile.csproj            # 运行时实际加载的程序集名：STS2Mobile.dll
      STS2AndroidPortCompat.csproj # IDE/描述性项目名
      ModEntry.cs                  # unmanaged entrypoints: InitializeGodotSharp / Apply
      Patches/                     # 平台、设置、输入、MOD、LAN、shader、生命周期等 Harmony patch
      Android/                     # Android settings/path bridge
    overlay/                       # 打包进 port_compat.pck 的 shader/resource overlay
    refs/original/                 # 指向 PC 原版 DLL 的本地 symlink，只用于 compile gate
  tools/
    android/
      env-from-s2.sh               # source 后使用 ../s2 的 JDK/Android SDK
      gradle-with-s2-env.sh        # 在 android/ 下带本机环境执行 Gradle
      sync-runtime-from-references.sh # 同步 Godot/FMOD/dotnet_bcl 等大型运行时产物
      build-port-mod.sh            # 编译 STS2Mobile.dll 并生成 port_compat.pck
      make-bootstrap-pck.py        # 生成最小 bootstrap.pck
      make-port-overlay-pck.py     # 从 port-mod/overlay 生成 port_compat.pck
      fmod-shim/                   # 替换 FMOD Java class 的 shim 源码
    package/
      validate_payload_zip.py      # 校验 PC 游戏 zip 必需文件/PCK magic/hash
      build_importer_apk.sh        # 构建不内置游戏 zip 的导入版 APK
      build_direct_apk.sh          # 构建临时内置游戏 zip 的直装版 APK
    diff/
      make_diff_inventory.py       # diff 清单工具
    git/
      report-heads.sh              # 输出当前仓库与子模块各分支 HEAD 状态
  docs/
    inventory/                     # 差异/迁移清单
    validation/                    # 验证记录
  dist/                            # APK 输出副本，gitignore
  .agent/                          # agent 计划/报告/本地状态，gitignore
```

## 4. Android shell 关键点

- Java package 保持 `com.godot.game`，便于兼容旧 C# / runtime 桥；实际 `applicationId` 由 `android/gradle.properties` 设置为 `com.megacrit.sts2re`。
- `GameSettingsActivity` 是默认 `LAUNCHER`：首次进入附加设置/欢迎向导，而不是直接进游戏。
- `GodotApp` 是真正的 Godot 游戏 Activity：
  - 首次向导未完成时会重定向回 `GameSettingsActivity`。
  - `getCommandLine()` 加 renderer/display 参数；有 `<files>/game/SlayTheSpire2.pck` 时传 `--main-pack`，否则使用 `bootstrap.pck`。
  - `setupAssemblies()` 会把 APK assets 中的 `dotnet_bcl` 与导入 payload 的 `data_*/*.dll` 同步到 `<files>/.godot/mono/publish/arm64`。
  - 暴露 `launchGameSettingsFromGame()`、`restartToSettingsFromGame()`、`getGodotDataDir()` 给兼容 MOD/游戏侧调用。
- `PayloadManager` 负责本地游戏 zip 导入：
  - 支持 SAF 选择 zip 与 assets 内置 `payload/SlayTheSpire2.zip`。
  - 解压到 staging，校验后原子替换 `<files>/game`。
  - 校验关键文件：`SlayTheSpire2.pck`、`release_info.json`、`data_sts2_windows_x86_64/sts2.dll`、`.deps.json`、`.runtimeconfig.json`。
  - 写入 `<files>/game/.payload_manifest.json`。
  - 有 Zip Slip canonical path 防护、backup/rollback、取消控制。
- 附加设置写入路径仍与旧移植版保持兼容：
  - `settings.save`：`<files>/default/<account>/settings.save`，默认 `<account>=1`。
  - MOD 目录：`<files>/mods`。
  - 游戏 payload：`<files>/game`。
  - renderer/first-run/last-tab/update-check 等保存在 SharedPreferences。

## 5. 兼容 MOD 关键点

- 运行时期望：`android/assets/dotnet_bcl/STS2Mobile.dll`，类型 `STS2Mobile.ModEntry`。
- 构建入口：`port-mod/STS2AndroidPortCompat/STS2Mobile.csproj`。
- 默认编译引用来自旧 Launcher runtime：
  - `../s2/.cache/StS2-Launcher_Mod_Manager/upstream/godot-export/.godot/mono/publish/arm64`
- original compile gate：
  - `-p:ReferenceFlavor=original` 使用 `port-mod/refs/original` symlink 到 PC 原版 DLL，避免误依赖旧移植版改过的 `sts2.dll`。
- `tools/android/build-port-mod.sh` 会：
  1. 用 `../s2/.local/dotnet/dotnet` 编译 `STS2Mobile.csproj`。
  2. 复制输出到 `android/assets/dotnet_bcl/STS2Mobile.dll`。
  3. 运行 `tools/android/make-port-overlay-pck.py` 生成 `android/assets/port_compat.pck`。

## 6. 构建/打包环境

### 6.1 Android/Gradle 版本

来自 `android/config.gradle` / `android/gradle.properties`：

- Android Gradle Plugin：`8.6.1`
- Gradle wrapper：`8.13`
- Kotlin plugin：`2.1.20`
- compileSdk / targetSdk：`35`
- minSdk：`24`
- buildTools：`35.0.0`
- NDK：`28.1.13356709`
- Java source/target：`17`
- flavor：`mono`
- build type：`release`
- ABI：`arm64-v8a`
- applicationId：`com.megacrit.sts2re`
- versionName/versionCode：`0.1.0` / `1`
- debug keystore：`/home/wsdx233/.android/debug.keystore`

注意：本仓库的 shell 构建复用 `../s2` 准备好的 JDK/Android SDK。容器系统 `/usr/lib/jvm/java-21-openjdk-amd64` 可能只是 JRE，不能直接编译 Java；请使用 `tools/android/gradle-with-s2-env.sh` 或先 `source tools/android/env-from-s2.sh`。

### 6.2 运行时二进制同步

`android/assets/dotnet_bcl/`、`android/libs/`、`android/gradle/wrapper/gradle-wrapper.jar` 等大型/生成产物不应手写维护，使用：

```bash
tools/android/sync-runtime-from-references.sh
```

该脚本会从 Launcher 参考项目和旧移植版同步：

- Godot template AAR / native libs
- `.NET/Godot` BCL/runtime DLL
- crypto native jar
- FMOD AAR，并用 `tools/android/fmod-shim/org/fmod/FMOD.java` patch AAR 内的 FMOD class
- Gradle wrapper jar

## 7. 打包方式

### 7.1 导入版 APK（不内置游戏 zip）

用户安装后在附加设置里选择本地 `SlayTheSpire2.zip` 导入。

```bash
tools/package/build_importer_apk.sh
```

脚本流程：

1. `tools/android/sync-runtime-from-references.sh`
2. `tools/android/build-port-mod.sh`
3. `tools/android/gradle-with-s2-env.sh assembleMonoRelease`
4. 复制输出：
   - Gradle 产物：`android/build/outputs/apk/mono/release/sts2-re.apk`
   - 稳定副本：`dist/sts2-re-importer.apk`

### 7.2 直装版 APK（临时内置游戏 zip）

构建时把本地 PC 游戏 zip 临时复制到 `android/assets/payload/SlayTheSpire2.zip`，首次启动自动解压到 `<files>/game`。zip 复制有 trap 清理，不提交。

```bash
tools/package/build_direct_apk.sh "../s2_pc/Slay the Spire 2.zip"
```

脚本流程：

1. `tools/package/validate_payload_zip.py <zip>` 校验必需文件、PCK magic、输出 sha256/release_info。
2. 同步 runtime。
3. 编译并 stage `STS2Mobile.dll` / `port_compat.pck`。
4. 临时复制 zip 到 `android/assets/payload/SlayTheSpire2.zip`。
5. `assembleMonoRelease`。
6. 复制输出：
   - Gradle 产物：`android/build/outputs/apk/mono/release/sts2-re.apk`
   - 稳定副本：`dist/sts2-re-direct.apk`
7. 自动删除临时内置 zip。

### 7.3 只编译 Java/Gradle 检查

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

### 7.4 只编译兼容 MOD

默认引用 runtime 参考：

```bash
tools/android/build-port-mod.sh
```

或直接：

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -v:q
```

检查是否误依赖旧移植版专属 API：

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original -v:q
```

### 7.5 payload zip 校验

```bash
tools/package/validate_payload_zip.py "../s2_pc/Slay the Spire 2.zip"
```

## 8. Git / 产物注意事项

### 8.1 仓库 / 子模块 HEAD 巡检

查看当前仓库 HEAD，以及每个子模块的当前 checkout、本地分支 HEAD、远端分支 HEAD、父仓库记录的 submodule commit、dirty/upstream ahead-behind 状态：

```bash
tools/git/report-heads.sh
```

如需先刷新远端引用再输出报告：

```bash
tools/git/report-heads.sh --fetch
```

该脚本只读取/可选 fetch Git 信息，不修改工作区文件；适合在提交前、同步子模块前后、排查 `port-mod` 分支/HEAD 不一致时使用。

### 8.2 产物与大文件注意事项

- `.gitignore` 已排除：
  - `dist/`、`*.apk`、`*.aab`、`*.apks`
  - `local-inputs/`、`*.zip`、keystore/jks/p12
  - `android/.gradle/`、`android/**/build/`
  - `android/assets/dotnet_bcl/`
  - `android/libs/`
  - `android/assets/payload/`
  - .NET `bin/` / `obj/`
- 不要把用户 PC 游戏 zip、解压后的 `<files>/game` 内容、完整 Godot/Mono runtime、大型 AAR/DLL 当源码提交。
- 如果构建前缺少 `android/assets/dotnet_bcl` 或 `android/libs`，先跑 `tools/android/sync-runtime-from-references.sh`。
- 如果修改 `port-mod/overlay`，需要重新生成 `port_compat.pck`；`tools/android/build-port-mod.sh` 会自动做。
- 如果修改 `tools/android/make-bootstrap-pck.py`，需要重新生成 `android/assets/bootstrap.pck`。
- 修改 Java package/class bridge 名称要谨慎：旧 C# helper 与兼容 MOD 默认找 `com.godot.game.GodotApp`。

## 9. 常用验证路径

本地构建：

```bash
tools/package/build_importer_apk.sh
# 或
tools/package/build_direct_apk.sh "../s2_pc/Slay the Spire 2.zip"
```

安装后建议检查：

```bash
adb install -r dist/sts2-re-importer.apk
adb shell run-as com.megacrit.sts2re ls files
adb shell run-as com.megacrit.sts2re ls files/default/1
adb shell run-as com.megacrit.sts2re ls files/game
```

重点 smoke test：

1. 首次打开进入欢迎向导/附加设置，而不是直接进游戏。
2. 修改图形/输入/MOD 设置后，`files/default/1/settings.save` 有对应字段。
3. 导入版选择 PC zip 后，`files/game/.payload_manifest.json` 存在，`files/game/SlayTheSpire2.pck` 存在。
4. payload ready 后点击启动，logcat 能看到 `Loading imported game PCK`。
5. 从游戏内打开附加设置、退出回设置、crash/log/file browser 页面不崩溃。
6. MOD master switch / 单 MOD disable 能在启动日志中反映。

## 10. 维护提醒

- 当前工程是“重构 shell + payload + compat MOD”的组合，不是传统 Android Studio `app/` 子模块结构；Gradle 根就在 `android/`。
- `android/build.gradle` 仍保留 Godot Android template 的导出辅助 task/属性；实际打包推荐用 `tools/package/*.sh`，不要只裸跑 Gradle，除非你已同步 runtime 并准备好环境。
- `release` build type 当前保留 `debuggable true`，默认脚本仍打 release APK 以获得 release 优化，同时保留 `run-as` 便于 sideload 验证；正式发布前需要重新审视签名、debuggable、混淆/资源优化、FileProvider 暴露范围。
- `settings_shortcuts.xml` / `game_shortcuts.xml` 的 `targetPackage` 与 `applicationId` 强相关；改包名时必须同步。
- `<files>/default/<account>` 的账号选择逻辑与旧移植版兼容但较脆弱，多账号/自定义 platform player id 改动要同时检查 Java 与兼容 MOD。
- `settings.save` 的 Android-only key 是 Java 附加设置与 Harmony patcher 的协议，改 key 要同步 `ExtraSettingsRepository`、页面 UI、`AndroidSettingsBridge`、相关 patches。

## 修改说明
完成用户要求的修改后，请用脚本构建一个importer版本apk，便于用户测试。
