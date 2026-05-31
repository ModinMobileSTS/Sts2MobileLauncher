# 2026-05-31 Steam Cloud 冲突选择弹窗

## 背景

普通上传本地变化时，如果同一个存档文件自上次同步后在本地和 Steam Cloud 都发生变化，原逻辑会直接抛出冲突错误，用户需要手动决定拉取还是强制上传。

## 改动

- `Sts2SteamCloudSyncManager` 新增结构化 `CloudConflictException`，记录冲突文件数量与路径摘要。
- Steam 中心的“上传本地变化”遇到冲突时，会弹出对话框，让用户选择：
  - **保留本地（上传）**：执行强制上传本地版本到 Steam Cloud。
  - **保留云端（下载）**：执行拉取 Steam Cloud 到本地。
  - 取消：不做改动。
- 干净退出后的自动上传如果遇到冲突，也会在主设置界面弹出同样的选择对话框，而不是只显示错误。
- 冲突选择仍复用现有的备份/增量/诊断流程：保留云端会在需要覆盖本地文件时创建备份；保留本地仍会跳过远端 SHA-1/size 相同的文件。

## 验证

已执行：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

随后使用 `tools/package/build_importer_apk.sh` 重新生成导入版 APK。
