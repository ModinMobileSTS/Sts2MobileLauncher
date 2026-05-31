# 2026-05-31 设置/版本页异步大小统计与日志等级

## 背景

进入“设置”和“版本”页时，部分卡片会递归扫描应用私有目录或 payload 目录计算文件大小；当用户数据、MOD 或已导入游戏较大时，主线程会被阻塞，导致页面打开卡顿。同时参考 `../s2` 设置页，需要在附加设置中提供运行日志等级选项。

## 改动

- 新增 `DirectoryStatsCalculator`，把递归文件统计封装为后台线程可复用的目录统计工具。
- “设置”页完整应用数据备份卡片不再在主线程计算私有数据大小：进入页面先显示“计算中”，后台线程完成后再更新为格式化大小和 MOD 清单数量。
- “版本”页不再通过 `LaunchProfileManager.readPayload()` 在主线程递归计算 payload 大小；缺少 manifest 缓存统计的旧 payload 会先显示“计算中”，后台线程完成后刷新统计行。
- 新增“日志”设置卡片：可选择 `Info`、`Debug`、`Very Debug`。设置写入当前 profile 的 `settings.save`，并同步到私有偏好以防原版游戏保存设置时丢弃未知 Android 字段。
- `GodotApp.getCommandLine()` 根据日志等级追加 STS2 原生命令行 `-log <LogType> <LogLevel>`，覆盖 `Generic / Network / Actions / GameSync / VisualSync`，下次启动游戏生效。

## 验证

- `tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac`：通过。
- `tools/package/build_importer_apk.sh`：通过，输出 `dist/sts2-re-importer.apk`。

## 注意事项

- Debug / Very Debug 会显著增加 `godot.log` 输出量，可能影响性能并占用更多存储；问题排查后建议切回普通 Info。
- 新导入 payload 的统计仍优先使用导入 manifest 中的缓存大小；只有缺少缓存统计的旧数据才需要后台递归扫描。
