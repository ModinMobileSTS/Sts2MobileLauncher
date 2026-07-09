# ADB 自动化调试

`tools/debug/sts2-adb-debug.sh` 用当前连接的 ADB 设备执行安装、配置、导入、启动准备、游戏启动、日志拉取和性能采集。它面向兼容包、普通 MOD、启动器状态、预加载和性能问题的本地复现。

## 基本用法

```bash
# 构建导入版 APK、安装到当前设备，并写入一次性 automation token
tools/debug/sts2-adb-debug.sh build-install

# 查看当前 launcher/profile/payload/compat/MOD 状态，并拉回结果
tools/debug/sts2-adb-debug.sh status --pull

# 只运行启动准备，常用于验证 compat dll / overlay / publish 目录 staging
tools/debug/sts2-adb-debug.sh prepare --mode compat --clear publish --pull

# 启动游戏并采集 logcat / perfetto
tools/debug/sts2-adb-debug.sh launch \
  --mode perf \
  --preload aggressive \
  --clear texture,publish \
  --logcat-duration 45 \
  --perfetto 45 \
  --pull
```

多设备时用 `-s <serial>`；需要尝试 root 时加 `--root`。默认包名是 `com.megacrit.sts2re`，可用 `--package` 覆盖。

## 精确测试

脚本会把本地文件先推入 app 私有目录 `files/automation/inbox/<run_id>/`，再由应用内普通导入逻辑处理。

```bash
# 测试某个兼容包 target
tools/debug/sts2-adb-debug.sh prepare \
  --compat dist/sts2-android-compat.zip \
  --compat-pack sts2-android-compat \
  --compat-target v0.108.0 \
  --clear publish \
  --pull

# 测试某个 MOD，只启用本次导入的 MOD
tools/debug/sts2-adb-debug.sh run \
  --mode mod \
  --mod ~/Downloads/ExampleMod.zip \
  --mods-only-imported \
  --launch \
  --pull

# 测试预加载开关组合
tools/debug/sts2-adb-debug.sh launch \
  --mode preload \
  --preload off \
  --clear texture,publish \
  --collect-logcat \
  --pull

# 直接合并 settings.save 中的 Android-only 或实验字段
tools/debug/sts2-adb-debug.sh configure \
  --settings-json '{"preload_shader_mode":"load_resources","preload_combat_code_enabled":true}' \
  --pull

# 在 Android app 进程内诊断创意工坊网络路径
tools/debug/sts2-adb-debug.sh --timeout 160 workshop-diagnostics \
  --query BaseLib \
  --collect-logcat \
  --pull
```

常用选项：

- `--payload <zip>`：推送并导入 PC payload zip。
- `--compat <zip>`：推送并导入兼容包 zip。
- `--mod <zip-or-file>`：推送并导入 MOD，可重复。
- `--profile <id>` / `--payload-id <id>`：选择特定启动配置或 payload。
- `--save-mode global|isolated`、`--mods-mode global|isolated`：调整当前 profile。
- `--mods-only`、`--mods-enable`、`--mods-disable`：精确控制 MOD 启用状态。
- `--preload default|off|aggressive|tree_warmup|vfx_full_tree|animation_full|runtime_only|startup_only`：快速切换预加载组合；`aggressive` / `tree_warmup` 会打开保护 warm cache、实战资源补全包、VFX retain cache、漏载学习、实际播放 VFX 预热、当前房间安全战斗动画预热、战斗命中特效/受击音效预热，适合“尽量全热完”的高内存测试。`vfx_full_tree` 会把 VFX 播放范围切到 `all`，逐个尝试让 `res://scenes/vfx/**/*.tscn` 全部临时进树跑帧，单项失败会记录并跳过；`animation_full` 在此基础上进一步对当前战斗房间先走原版 `SetAnimationTrigger()` 路径，再做所有 Spine clip 短帧采样，并跳过死亡、复活、逃跑、睡眠/醒来等危险 trigger。日志中的 `resource_only` / `tree_warmed` / `tree_ineligible` / `tree_failed` 可区分这些路径；战斗房间日志中的 `hit_effects` / `hit_audio` 可确认伤害数字、命中火花和 FMOD 受击事件是否已预热。VFX warmup 完成日志还会输出 `<files>/shader_cache` 前后文件数和字节数；miss 学习文件写入 `<files>/launcher/preload-learned-assets.json`。需要逐资源 miss 分类、phase enter/leave 或逐动画明细时，可额外用 `--settings-json '{"preload_debug_enabled":true}'` 打开隐藏诊断。
- `--renderer opengl_es3|vulkan`、`--log-level info|debug|very_debug|off`、`--performance-overlay true|false`：调整运行诊断开关。
- `--clear texture,publish,logs,mods,compat,payloads,automation`：清理对应 app 私有状态。
- `--logcat-duration <秒>`、`--collect-logcat`、`--perfetto <秒>`：采集设备日志和系统 trace。
- `workshop-diagnostics --query <关键词>`：在设备上的 app 进程内分别测试 Steam Community 原始路由、创意工坊兼容访问路由、published file details 和页面实际搜索路径；结果写入 `details.workshop.public_browse_original`、`public_browse_direct`、`details` 与 `catalog_search`，用于排查“同设备同网络参考项目正常但本应用超时”的问题。

完整参数见：

```bash
tools/debug/sts2-adb-debug.sh --help
```

## 产物位置

本地结果默认写入：

```text
.agent/debug/runs/<run_id>/
  result.json
  am-start.txt
  logcat-live.txt
  logcat.txt
  perfetto-trace
  app-files/automation-run/
  app-files/launcher/
  app-files/logs/
  profile-logs/
```

设备侧结果写入：

```text
<files>/automation/token.txt
<files>/automation/inbox/<run_id>/
<files>/automation/runs/<run_id>/request.json
<files>/automation/runs/<run_id>/result.json
<files>/automation/last_result.json
```

这些都是本地调试数据，不应提交。

## 安全边界

自动化入口由 `DebugAutomationActivity` 承载，`DebugAutomationReceiver` 只负责把广播转交给该 Activity。脚本安装后会用 `run-as` 写入 app 私有 token；每次 `am start` 必须携带同一 token，否则应用会写入失败结果并拒绝执行。

当前 release 构建仍是 `debuggable=true`，所以获得 ADB 授权的主机可以通过 `run-as` 读取 app 私有数据。只在可信电脑上启用 ADB，测试完成后可撤销设备授权或卸载测试 APK。
