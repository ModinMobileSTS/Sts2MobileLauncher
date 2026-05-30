# 普通 MOD 与 Android 兼容包维护说明

## 1. 两类“MOD”不要混淆

本项目里有两类扩展：

1. **Android compat pack**
   - 由 launcher/Godot runtime 早期加载。
   - 包含 `STS2Mobile.dll` 与 `port_compat.pck`。
   - 负责 patch 平台、路径、设置、输入、shader、LAN、普通 MOD loader。
   - 不放在 `<files>/mods`，不由原版 `ModManager` 作为普通 MOD 加载。
2. **普通用户 MOD**
   - 用户安装到 `<files>/mods/`。
   - 由被 compat pack patch 后的原版 `ModManager` 扫描、排序和加载。
   - 是否启用由 `settings.save` 中的 MOD 设置/附加设置页控制。

## 2. 普通 MOD 目录和 manifest

当前普通 MOD 根目录：

```text
<files>/mods
```

`ModLoaderPatches` 会递归扫描该目录。为兼容当前 PC `ModManager` 的 manifest 规则，会把：

```text
mod_manifest.json
```

运行时规范化为：

```text
<ModId>.json
```

并添加 `android_generated_manifest_alias=true`，必要时删除重复的 `mod_manifest.json`。

## 3. MOD 启用/禁用协议

Java 附加设置页通过 `ExtraSettingsRepository` 写入：

```text
<files>/default/1/settings.save
```

兼容层中的 `AndroidSettingsBridge` / `AndroidSettingsPatches` 会读取 companion JSON，将 Android-only 字段投影到当前游戏版本的 `ModSettings`：

- `mod_settings.mods_enabled`
- `mod_list[]`
- legacy `disabled_mods[]`

修改设置 key 时必须同步：

- Java repository / UI；
- `port-mod/STS2AndroidPortCompat/Android/AndroidSettingsBridge.cs`；
- 相关 `Patches/*Settings*.cs`；
- `doc/changelog/`。

## 4. compat pack 分支维护

当前维护分支：

| 游戏版本 | 分支 | 原版引用 | ReferenceFlavor |
| --- | --- | --- | --- |
| `v0.103.2` | `compat/v0.103.2` | `../s2_original/s21032/` | `original` |
| `v0.106.1` beta | `compat/v0.106.1-beta` | `../s2_original/s201061/` | `original-v0.106.1` |

开发步骤建议：

```bash
# 1. 确认 submodule 分支
git -C port-mod status --short --branch

# 2. 编译对应 original gate
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.106.1 -v:q

# 3. 构建 fallback 或 compat pack
tools/android/build-port-mod.sh
# 或
(cd port-mod && ./tools/build-compat-pack.sh)

# 4. 构建内置包/导入版 APK
tools/android/stage-bundled-compat-packs.sh
tools/package/build_importer_apk.sh
```

对 `compat/v0.103.2` 分支，把 `ReferenceFlavor` 改为 `original`。

## 5. 新增游戏版本 checklist

新增一个目标游戏版本时，至少需要：

1. 准备对应 PC 原版/解包目录，例如 `../s2_original/<version>/`。
2. 在 `port-mod/refs/` 新增 original refs 说明/symlink，或在 `Directory.Build.props` 加入对应路径解析。
3. 新建 `port-mod` 分支，例如 `compat/vX.Y.Z`。
4. 新增/更新 `compat_manifest.*.json`：
   - `pack_id`
   - `display_name`
   - `compat_version`
   - `channel`
   - `target_game.version`
   - `target_game.source_dir`
   - `target_game.sts2_dll_sha256`
5. 用对应 `ReferenceFlavor` 做 compile gate。
6. 更新 `tools/android/bundled-compat-packs.json`。
7. 运行 `tools/android/stage-bundled-compat-packs.sh`。
8. 更新 `AGENTS.md`、`doc/architecture/project-structure.md`、本文件和 changelog。
9. 构建 `tools/package/build_importer_apk.sh` 并做至少一次导入/启动 smoke test。

## 6. patch 开发注意事项

- 优先使用 prefix/postfix 和反射兜底，谨慎使用复杂 transpiler；Android 上 MonoMod/Cecil/Godot StringName 生命周期问题更容易暴露。
- 任何直接引用游戏内部类型的 patch 都可能随游戏版本变化失效，应保留在对应 compat 分支。
- `ModEntry.Apply()` patch 顺序很重要：BaseLib/RitsuLib 与平台/路径类 patch 必须早于 ModLoader。
- Android temp 目录必须尽早配置，否则 Harmony/MonoMod 可能尝试使用不可写 `/tmp`。
- Shader/resource overlay 资源应放入 `port-mod/overlay/`，重新打包 `port_compat.pck` 后才能生效。
- 普通 MOD loader 的目标是尽量复用游戏原本的 scanner、dependency sort 和 TryLoadMod，减少与 PC 行为分叉。

## 7. 兼容包 manifest 约定

典型 manifest：

```json
{
  "schema": 1,
  "pack_id": "sts2-android-compat-v0.106.1-beta",
  "display_name": "STS2 Android Compatibility beta for v0.106.1",
  "compat_version": "0.2.0-beta.1061",
  "channel": "beta",
  "target_game": {
    "version": "v0.106.1",
    "source_dir": "../s2_original/s201061",
    "sts2_dll_sha256": "...",
    "match": "exact-preferred"
  },
  "runtime": {
    "entry_assembly": "STS2Mobile.dll",
    "entry_type": "STS2Mobile.ModEntry",
    "entry_method": "Apply"
  },
  "resources": {
    "overlay_pck": "port_compat.pck"
  },
  "notes": []
}
```

`CompatPackManager` 当前主要用 `target_game.version` 与 payload manifest 的 `version` 匹配。`sts2_dll_sha256` 记录用于诊断和未来更严格匹配。

## 8. 用户 MOD 测试建议

1. 先确认无普通 MOD 时游戏可启动。
2. 安装 BaseLib/RitsuLib 等基础库 MOD，查看 log 中 BaseLib/RitsuLib compatibility patch 是否正常。
3. 安装目标普通 MOD 到 `<files>/mods/`。
4. 在附加设置中开启 MOD 总开关并确认单 MOD 未禁用。
5. 启动后查看日志：
   - `[Mods] Android mod initialization loaded ...`
   - dependency sort / TryLoadMod 相关日志；
   - 是否出现 assembly resolve 失败。
6. 若某 MOD 只支持特定游戏版本，优先在版本页切到匹配 payload 和 compat pack。
