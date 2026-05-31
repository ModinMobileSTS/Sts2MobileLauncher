# 2026-05-31 launcher 图标、名称与启动行为

## 背景

桌面启动图标此前使用附加设置 Activity 的齿轮图标和“附加设置 / Extra Settings”名称，用户希望改为游戏入口，并可选择点击桌面图标后直接进游戏。

## 改动

- 将主应用英文名称改为 `STS2`，中文名称改为 `杀戮尖塔2`（覆盖常见中文地区资源）。
- 将 `GameSettingsActivity` 的 launcher label/icon 改为主应用名称与 `@mipmap/icon`，避免桌面上显示附加设置齿轮。
- 使用 `~/datas/tmp/sts2_icon.png` 重新生成各密度 legacy/adaptive launcher icon 资源。
- 在“设置 → 系统”新增“桌面图标启动后”选项：
  - 默认：打开附加设置。
  - 可选：直接启动游戏。
- 直接启动仅在从系统桌面 `MAIN/LAUNCHER` 入口打开、首次向导已完成时触发；设置快捷方式、游戏内打开附加设置、崩溃恢复等入口仍保持打开设置页。

## 验证

- 已执行 `tools/package/build_importer_apk.sh`，Gradle `assembleMonoRelease` 成功。
- 已用 `aapt dump badging` 确认默认 label 为 `STS2`，中文 label 为 `杀戮尖塔2`，launcher icon 为 `res/mipmap-anydpi-v26/icon.xml`。
- 输出 APK：`dist/sts2-re-importer.apk`。

## 风险 / 后续

- 若选择直接启动但当前 payload 未导入或兼容包检查失败，会留在附加设置页并显示原有提示/对话框。
