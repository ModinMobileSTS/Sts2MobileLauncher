# 2026-05-31 Steam 登录、游戏下载与云存档首版

## 背景

`doc/plan/steam/steam-login-download-cloud-plan.md` 原计划仍按旧 `<files>/game` / `game-versions` 模型描述。当前工程已经切换到 payload store + launch profile 多版本模型，因此 Steam 下载和云存档必须绑定到：

- 游戏本体：`<files>/payloads/<payload_id>/game/`
- 启动配置：`<files>/instances/<profile_id>/instance.json`
- 存档根：当前 launch profile 解析出的 account root（全局或隔离）

## 改动

- 更新 Steam 计划文档，使 SteamPipe 下载安装到 payload store，并在下载后创建/选择 launch profile。
- 新增 Gradle 子模块：
  - `android/steam-protocol/`：Steam CM/auth/content protobuf 协议。
  - `android/steam-content/`：SteamPipe depot manifest/chunk 下载与校验。
- 新增 `SteamAccountActivity` Steam 中心：
  - Steam 账号密码登录、Steam Guard 交互、refresh token 加密保存和验证。
  - 从 Steam AppID `2868840` 下载 STS2 payload，安装到 payload store，并自动匹配兼容包。
  - Steam Cloud 清单刷新、拉取、上传、强制上传。
  - 云存档模式：关闭、仅手动、启动前拉取、启动前拉取 + 干净退出后上传。
- `PayloadManager` 增加 `importPayloadDirectory(...)`，供 Steam 下载目录复用 zip 导入的校验、PCK patch、manifest 写入和原子安装逻辑。
- Steam Cloud 路径映射改为当前 launch profile account root；拉取前在 `<files>/steam/cloud/<profile_id>/backups/` 创建私有备份。
- `GodotApp.restartToSettingsFromGame()` 写入干净退出 marker；`GameSettingsActivity` 回到设置页后可按设置自动上传本地变化。
- 首页和设置页增加 Steam 中心入口。
- 修复强制上传时旧 `BeginHTTPUpload` 持续返回 `DuplicateRequest` 的问题：上传实现改为 `ClientBeginFileUpload` / `ClientCommitFileUpload` block 协议，与 JavaSteam 客户端 Cloud API 保持一致。
- 为 Steam 中心内登录/验证/游戏下载/云同步，以及主界面触发的启动前拉取、干净退出后自动上传增加不可取消进度 Dialog，避免长时间网络操作看起来像卡死。

## 验证

- 已执行并通过：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
tools/package/build_importer_apk.sh
```

- 修复上传重试/进度 Dialog 后重新执行并通过：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
tools/package/build_importer_apk.sh
```

- `tools/package/build_importer_apk.sh` 已生成 `dist/sts2-re-importer.apk`。

## 注意事项

- Steam 下载/云同步需要用户自己的 Steam 账号；仓库和 APK 不包含商业游戏资源或账号凭据。
- `settings.save` 云同步默认关闭，避免 Android-only 设置污染 PC 端配置。
- 首版下载在 Steam 中心 Activity 内执行；长时间下载时建议保持页面前台，后续可再加强为 foreground service/断点续传。
