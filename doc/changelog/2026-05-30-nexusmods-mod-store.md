# 2026-05-30 NexusMods MOD 商店

## 背景

MOD 页原本只支持从本地文件导入 MOD。为降低移动端安装 MOD 的门槛，新增一个移动友好的 NexusMods 商店入口，用于浏览、搜索和下载 NexusMods 上的 Slay the Spire 2 MOD。

## 改动

- 在 `ModsPage` 的“导入 MOD”按钮下方新增“NexusMods 商店”入口。
- 新增 `NexusModsStoreActivity`，使用现有 Material 3 深色卡片风格：
  - 保存 / 验证 / 清除用户手动输入的 NexusMods Personal API Key；
  - 内置教程：打开 `https://www.nexusmods.com/settings/api-keys`，滑到页面底部，点击 “Request Personal API Key”；
  - 支持 Discover、关键词筛选、Nexus URL / 数字 MOD ID 精确查询；
  - 支持查看 MOD 文件列表并下载导入到 `<files>/mods/`。
- 新增 `NexusModsApiClient` 与 `NexusModsStorePreferences`：
  - API Key 存在应用私有 `SharedPreferences`；
  - 默认 NexusMods 游戏域名为 `slaythespire2`；
  - 下载链接生成失败时提示 Premium / 非 Premium 限制，并支持粘贴 `nxm://...key=...&expires=...` 链接重试。
- 扩展 `ExtraSettingsRepository.importDownloadedModFile()`，允许商店下载后的本地文件复用现有 MOD 导入、解压与 manifest alias 规范化流程。
- 更新 `AGENTS.md` 与 `doc/modding/mod-and-compat-notes.md`，记录商店入口、API Key 保存位置、搜索限制与下载导入流程。

## 验证

- 已运行 Java/资源编译检查：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

- 已按项目约定构建导入版 APK：

```bash
tools/package/build_importer_apk.sh
```

产物：`dist/sts2-re-importer.apk`。

## 风险 / 后续

- NexusMods 官方 API 没有完整全站文本搜索接口；当前关键词搜索聚合并筛选 trending/latest/updated feed，精确查找建议输入 Nexus MOD URL 或数字 MOD ID。
- NexusMods 对非 Premium 用户可能不允许直接通过 API 生成下载链接；界面已提供网页打开与 NXM 链接粘贴兜底，但实际下载能力仍受 NexusMods 账号权限和站点策略影响。
- API Key 仅保存在应用私有偏好中，没有做额外硬件级加密；不要在日志、截图或提交中泄露 Key。
