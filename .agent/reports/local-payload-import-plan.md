# 本地游戏包导入 / 直装解压计划

日期：2026-05-20  
输入样例：`../s2_pc/Slay the Spire 2.zip`

## 1. 样例 zip 初步事实

`../s2_pc/Slay the Spire 2.zip`：

- 大小约 `1.8G`
- zip entry 数：`236`
- 已确认关键文件：

```text
SlayTheSpire2.pck
release_info.json
data_sts2_windows_x86_64/0Harmony.dll
data_sts2_windows_x86_64/GodotSharp.dll
data_sts2_windows_x86_64/sts2.deps.json
data_sts2_windows_x86_64/sts2.dll
data_sts2_windows_x86_64/sts2.runtimeconfig.json
```

还有 `mods/`、controller config、Windows exe/native dll 等内容。导入器不应盲目把所有 Windows native/工具文件都视为 Android 必需；第一版可以完整保留到私有目录，后续优化为 allowlist。

## 2. 私有目录规范

建议 app 私有目录：`<files>/game/`

```text
<files>/game/
  SlayTheSpire2.pck
  release_info.json
  data_sts2_windows_x86_64/
    sts2.dll
    sts2.deps.json
    sts2.runtimeconfig.json
    GodotSharp.dll
    0Harmony.dll
    ... managed deps ...
  mods/                  # 可选：从 zip 导入，或隔离为 imported_mods
  .payload_manifest.json
```

另设 staging：

```text
<files>/payload_import/staging-<timestamp>/
<files>/payload_import/backup-<timestamp>/
```

导入成功后：

1. 解压到 staging；
2. 校验 staging；
3. 如果已有 `<files>/game`，rename 为 backup；
4. staging atomic rename 为 game；
5. 成功后删除 backup；失败则回滚。

## 3. 导入器安全要求

必须实现：

- Zip Slip 防护：entry 解压目标 canonical path 必须位于 staging canonical root 下。
- 空 entry / 目录 entry 正常处理。
- 文件大小与总大小计数；可设置上限与剩余空间预检。
- 写入时使用 buffer，避免一次性读入大文件。
- 可取消：用户离开或点击取消后停止解压并删除 staging。
- 失败回滚：不能留下半成品 `<files>/game`。
- manifest 写入前必须完成校验。

建议校验：

- `SlayTheSpire2.pck` 存在，前 4 字节为 `GDPC`。
- `release_info.json` 存在且是 JSON。
- `data_sts2_windows_x86_64/sts2.dll` 存在且 size > 0。
- `data_sts2_windows_x86_64/sts2.deps.json` 存在。
- `data_sts2_windows_x86_64/sts2.runtimeconfig.json` 存在。
- 记录源 zip display name、size、mtime、sha256（sha256 可在导入时边 copy/边解压计算）。

## 4. `.payload_manifest.json` 建议格式

```json
{
  "schema": 1,
  "imported_at_unix": 1770000000,
  "source": {
    "kind": "saf_zip|bundled_zip",
    "display_name": "Slay the Spire 2.zip",
    "size": 1900000000,
    "sha256": "..."
  },
  "game": {
    "pck_size": 0,
    "release_info": {
      "version": "...",
      "commit": "..."
    },
    "dll_size": 0,
    "file_count": 236,
    "total_uncompressed_bytes": 0
  },
  "compat": {
    "required_port_mod_version": "0.1.0",
    "payload_layout": "pc_zip_flat_v1"
  }
}
```

## 5. UI flow

普通版：

- 无 payload：显示“导入游戏压缩包”“查看帮助”“查看日志”。
- 导入中：显示当前文件、进度、已写入大小、取消按钮。
- 导入成功：显示版本、commit、大小、启动游戏、重新导入、清除本体。
- 导入失败：显示错误、保留原 payload（如有）、提供日志入口。

直装版：

- 首次启动检测内置 assets/asset-pack payload。
- 如果 `<files>/game/.payload_manifest.json` 不存在或版本不匹配，显示自动解压进度。
- 解压成功后进入 ready 状态。
- 如果用户手动导入 zip，手动 payload 优先于内置 payload。

## 6. 替换 Launcher Mod Manager 的 Steam 下载 seam

参考 `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherModel.cs`：

需要替换/重写：

- `StartSession()`：从 credentials/ownership marker 判断，改为 payload manifest 判断。
- `StartDownloadAsync()`：改为 `ImportZipAsync()` / `ExtractBundledPayloadAsync()`。
- `CheckForUpdatesAsync()`：第一版可变为“重新导入/比较 manifest”；不做 Steam manifest。
- `ListBranchesAsync()`：删除或隐藏。
- `WipeGameFiles()`：保留，但只删 `<files>/game` 和本地 payload cache。
- `GameFilesReady()`：加强为完整 payload validation，而非仅 PCK magic。

需要保留/复用：

- `Launch()` 中 standalone 模式下载后 restart 的思路。
- Godot launcher UI 的主线程队列。
- `<files>/game/SlayTheSpire2.pck` 的路径约定。
- `GodotApp.setupAssemblies()` 对 managed DLL 的同步逻辑。

## 7. 直装版构建策略

本地测试可直接：

```text
android/app/src/main/assets/payload/SlayTheSpire2.zip
```

但正式直装版需评估：

- APK 体积限制、安装器兼容性；
- Play Asset Delivery / install-time asset pack；
- OBB 或 split APK；
- 解压时需要的临时空间：至少 zip 大小 + 解压后大小 + 旧 payload backup 空间。

构建脚本建议：

```bash
tools/package/build_direct_apk.sh "../s2_pc/Slay the Spire 2.zip"
```

脚本负责：

- 校验输入 zip 必含关键文件；
- 复制到临时 assets/asset-pack；
- 构建；
- 构建后删除临时复制；
- 不把 zip 加入 git。

## 8. 验证清单

普通导入：

- [ ] 选择样例 zip 后导入成功。
- [ ] `adb shell run-as <package> ls files/game` 可见关键文件。
- [ ] `.payload_manifest.json` 内容正确。
- [ ] 损坏 zip / 缺 pck / 缺 dll 均失败且不破坏旧 payload。
- [ ] 重启 app 后识别 payload ready。

直装：

- [ ] `pm clear <package>` 后首次启动自动解压。
- [ ] 中途杀进程后重开会清理 staging 并重新解压。
- [ ] 已有手动导入 payload 时不被内置 payload 覆盖，除非用户选择“恢复内置版本”。

启动联动：

- [ ] payload ready 后点击启动，日志显示加载 `<files>/game/SlayTheSpire2.pck`。
- [ ] 如果适配 MOD 未完成导致游戏崩溃，crash/log 页面能显示具体异常。
