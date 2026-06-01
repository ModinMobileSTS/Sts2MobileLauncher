# 2026-06-01 Steam 首次打开安全提示

## 背景

Steam 中心会处理用户自己的 Steam 登录、refresh token、SteamPipe 下载和 Steam Cloud 同步。为降低账号与存档风险，需要在用户第一次打开 Steam 页面时显式提示关键注意事项，并强制保留阅读时间。

## 改动

- `SteamAccountActivity` 首次打开时弹出不可点外部取消的安全提示 Dialog。
- Dialog 使用 HTML 文案渲染重点词的加粗、斜体和下划线效果。
- 关闭按钮前 5 秒保持禁用，并显示动态倒计时“关闭(%d秒) / Close(%ds)”；倒计时结束后按钮改为“关闭 / Close”。
- 用户点击关闭后在 `SteamSettings` 的 `sts2_steam_settings` 偏好中记录已读状态，后续打开不再重复弹出。
- Steam 页面底部常驻“安全说明 / Safety notice”按钮，可随时重新打开同一安全提示；常驻入口不再强制倒计时。
- 提示内容覆盖：
  - 本应用不会上传任何 Steam 账号信息，仅本地保存 refresh token。
  - 只在可信来源下载的 APK 中输入 Steam 账号信息。
  - 安装未知 MOD 后建议退出登录，避免潜在恶意 MOD 读取本地 RT 的账号风险。
  - 使用 Steam 云存档前建议先备份。
  - 国内使用 Steam 相关服务可能需要开启加速器。
- 同步更新 `README.md`、`AGENTS.md` 和 Steam 计划文档中的安全提示说明。

## 验证

已执行并通过：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
tools/package/build_importer_apk.sh
```

`tools/package/build_importer_apk.sh` 已生成 `dist/sts2-re-importer.apk`。

倒计时按钮和常驻入口追加后已重新执行上述两条命令并通过，`dist/sts2-re-importer.apk` 已刷新。

## 风险 / 后续

- 已读状态按应用私有 SharedPreferences 保存；清除应用数据或重装后会再次提示。
- 该提示是 launcher 侧 UI 提醒，不改变 MOD 沙箱/文件读取模型；未知 MOD 风险仍需要用户通过可信来源、退出登录和备份来规避。
