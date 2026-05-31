# 2026-05-31 Steam Cloud 上传重试与进度 Dialog

## 背景

强制上传 Steam Cloud 时，旧 `BeginHTTPUpload` RPC 会持续返回 `DuplicateRequest`，即使退避重试也无法进入实际 HTTP 上传，随后 JavaSteam WebSocket 可能因等待过久断开。用户侧看到的 `slideSceneEnd unknown scene 1000 mScene:-1` 是 Oplus 系统窗口日志，不是上传失败根因。

同时 Steam 登录、下载和云同步操作耗时较长，只有 Snackbar / 页面内小进度提示时容易被误认为卡死，尤其是主界面触发的启动前拉取和干净退出后自动上传。

## 改动

- 对照 JavaSteam 内置 Cloud API 后，将上传路径从旧 `BeginHTTPUpload` / `CommitHTTPUpload` 改为 Steam 客户端使用的 `ClientBeginFileUpload` / `ClientCommitFileUpload`；该协议使用二进制 SHA-1、mtime、cell id、block upload request 和 upload batch id，避免 `BeginHTTPUpload` 一直 `DuplicateRequest`。
- 手动上传批次现在不再把全部待上传路径预填到 `BeginAppUploadBatch.files_to_upload`；实际文件由逐个 `ClientBeginFileUpload(upload_batch_id=...)` 声明，避免 Steam 把“批次里已登记的同一路径”判作重复请求。
- 若 `ClientBeginFileUpload` 返回 `DuplicateRequest` 但响应中已经包含 upload block requests，会按 JavaSteam 高层 API 的行为继续使用这些 block 上传；若没有 block details，则按“Steam 已有相同/挂起请求”视为该文件 no-op 成功，不再反复退避或调用会返回 `FileNotFound` 并拖慢连接的失败 commit 清理。
- 上传批次开始前会检查本地计划中的远端路径重复项，并写出 `steam/cloud/<profile>/diagnostics/push-plan.tsv`，便于确认 `modded/profile1/saves/current_run.save` 等路径没有在同一批次中重复提交；强制上传和普通上传都会跳过远端 SHA-1/size 已完全相同的文件，避免 Steam 将同内容上传判为重复请求；若远端 manifest 已存在同一 local path，则上传沿用 manifest 中的实际 remote path。
- Steam Cloud 拉取会先按远端 SHA-1/size 对比本地文件，已经一致的文件不再下载/覆盖；只有确实需要覆盖本地的文件才创建备份并下载，计划写入 `steam/cloud/<profile>/diagnostics/pull-plan.tsv`。
- 借鉴参考工程的处理方式：如果上传第一个文件前 Steam WebSocket/session 意外断开，会关闭当前 client 并自动重连重试一次，同时写出 `push-reconnect-retry.txt`。
- `Sts2SteamCloudSyncManager.pushLocalChanges()` 上传每个文件前后都报告进度，显示 `当前/总数 + 文件路径`。
- 新增 `SteamOperationProgressDialog`，用于长时间 Steam 操作的不可取消进度 Dialog。
- `SteamAccountActivity` 的登录、验证、SteamPipe 下载、Steam Cloud 刷新/拉取/上传/强制上传统一显示进度 Dialog。
- `GameSettingsActivity` 主界面触发的启动前 Steam Cloud 拉取、干净退出后自动上传也显示进度 Dialog。

## 验证

已执行并通过：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
tools/package/build_importer_apk.sh
```

`dist/sts2-re-importer.apk` 已重新生成。

## 注意事项

- Steam Cloud 进度 Dialog 当前不可取消，避免中断上传批次导致远端继续清理。
- 如仍失败，请关注日志中的 `ClientBeginFileUpload` / `client_file_upload block_*` / `ClientCommitFileUpload`，而不应再看到旧 `BeginHTTPUpload DuplicateRequest` 循环。
