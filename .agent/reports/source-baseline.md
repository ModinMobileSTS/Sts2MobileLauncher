# Source Baseline

日期：2026-05-20  
工作目录：`/mnt/datas/agent_workspace/s2_re`

## 1. 参考输入

### 1.1 现有移植版

路径：`../s2/`

```text
git commit: dbb70a45
branch: main...origin/main [领先 1]
```

当前 `../s2` 有未提交改动（本项目只读参考，不能直接依赖 dirty 状态作为稳定 baseline）：

```text
 M android/build/res/values-zh/strings_game_settings.xml
 M android/build/res/values/strings_game_settings.xml
 M android/build/src/com/godot/game/ExtraSettingsActions.java
 M android/build/src/com/godot/game/ExtraSettingsRepository.java
 M android/build/src/com/godot/game/GameSettingsActivity.java
 M android/build/src/com/godot/game/SettingsPage.java
?? "1、"
```

建议后续先请用户确认这些 dirty 改动是否属于“最新附加设置”必须搬运内容；若是，应先在 `../s2` 提交或在本项目记录 patch。

### 1.2 PC 原版解包/工程

路径：`../s2_original/s21032/`

目录中未见 `release_info.json`，但从命名与 zip 可推断对应 `s21032 / v0.103.2`。

文件数量粗略统计：

```text
../s2_original/s21032: 26645 files
```

### 1.3 现有 Launcher / Mod Manager 参考

路径：`../s2/.cache/StS2-Launcher_Mod_Manager/`

```text
git commit: d0a1f19
branch: main...origin/main
status: clean
files: 1626
```

已完成只读侦察报告：

- `.agent/reports/launcher-mod-manager-scout.md`
- `.agent/reports/launcher-mod-manager-progress.md`

### 1.4 直装版/导入版本地 zip

路径：`../s2_pc/Slay the Spire 2.zip`

```text
size: 1872326043 bytes (~1.8G)
mtime: 2026-05-20 16:10:48 +0800
sha256: 86175dd93c69dc75b40c6f5dae9d2cffabeff10052bc12acd4a0256115a1eb03
entry count: 236
```

`release_info.json` 内容：

```json
{
  "commit": "89765e1e",
  "version": "v0.103.2",
  "date": "2026-04-16T14:53:23-07:00",
  "branch": "v0.103.2",
  "main_assembly_hash": 1832700724
}
```

已确认 zip 关键文件：

```text
SlayTheSpire2.pck
release_info.json
data_sts2_windows_x86_64/0Harmony.dll
data_sts2_windows_x86_64/GodotSharp.dll
data_sts2_windows_x86_64/sts2.deps.json
data_sts2_windows_x86_64/sts2.dll
data_sts2_windows_x86_64/sts2.runtimeconfig.json
```

## 2. 当前工作目录产物

```text
.agent/plan/sts2_re_restructure_plan.md
.agent/reports/launcher-mod-manager-scout.md
.agent/reports/launcher-mod-manager-progress.md
.agent/reports/extra-settings-inventory.md
.agent/reports/local-payload-import-plan.md
.agent/reports/source-baseline.md
```

## 3. 注意事项

- 本 baseline 只记录引用输入，不把任何游戏 zip / APK / 大二进制纳入 git。
- `../s2` dirty 状态需要在 M1 搬运前处理，否则“原模原样搬运”的来源不唯一。
- `../s2_original/s21032` 是工程解包目录，不等同于 zip 内 runtime payload；MOD diff 和 runtime 导入要分别处理。
