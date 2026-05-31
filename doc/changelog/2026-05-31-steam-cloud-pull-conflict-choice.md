# 2026-05-31 Steam Cloud 拉取冲突选择

## 背景

启动前自动拉取 Steam Cloud 时，如果本地与云端同一路径的存档文件都在上次同步后发生变化，原逻辑会直接按云端覆盖本地，缺少用户选择。

## 改动

- `Sts2SteamCloudSyncManager.pullAll()` 新增非强制冲突检测：本地和云端相对 baseline 都变化且内容不同的文件会抛出结构化 `CloudConflictException`。
- 新增 `pullAll(true, ...)` 强制拉取路径，用于用户明确选择“保留云端”。
- 启动前自动拉取遇到冲突时弹出选择框：
  - **保留本地（上传）**：强制上传本地版本到 Steam Cloud，然后继续启动游戏。
  - **保留云端（下载）**：强制拉取云端版本覆盖本地，然后继续启动游戏。
  - 取消：不启动、不改动文件。
- Steam 中心手动“云端拉取到本地”也改为遇到冲突时弹出同样选择。
- 已有上传冲突弹窗的“保留云端”改为强制拉取，避免再次触发同一冲突。

## 验证

已执行：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

随后使用 `tools/package/build_importer_apk.sh` 重新生成导入版 APK。
