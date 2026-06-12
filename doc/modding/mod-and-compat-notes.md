# 普通 MOD 与 Android 兼容包维护说明

## 1. 两类“MOD”不要混淆

本项目里有两类扩展：

1. **Android compat pack**
   - 由 launcher/Godot runtime 早期加载。
   - 包含 `STS2Mobile.dll` 与 `port_compat.pck`。
   - 负责 patch 平台、路径、设置、输入、shader、LAN、普通 MOD loader。
   - 不放在 `<files>/mods`，不由原版 `ModManager` 作为普通 MOD 加载。
2. **普通用户 MOD**
   - 用户安装到当前 launch profile 解析出的 MOD 目录：全局模式为 `<files>/mods/`，隔离模式为 `<files>/instances/<profile_id>/mods/`。
   - 由被 compat pack patch 后的原版 `ModManager` 扫描、排序和加载。
   - 是否启用由当前 profile 的 `settings.save` 中的 MOD 设置/附加设置页控制。

## 2. 普通 MOD 目录和 manifest

当前普通 MOD 根目录由“版本”页选中的 launch profile 决定：

```text
# mods_mode=global
<files>/mods

# mods_mode=isolated
<files>/instances/<profile_id>/mods
```

`ModLoaderPatches` 会通过 `AppPaths.ModsDir` 递归扫描该目录。为兼容当前 PC `ModManager` 的 manifest 规则，会把：

```text
mod_manifest.json
```

运行时规范化为：

```text
<ModId>.json
```

并添加 `android_generated_manifest_alias=true`，必要时删除重复的 `mod_manifest.json`。

## 3. MOD 管理界面与导入冲突处理

`ModsPage` 采用紧凑 Material 3 顶栏：顶部为 MOD 总开关和药丸搜索框，导入、分组、排序、筛选、MOD 方案入口统一放在可横向滚动的 Chip 操作组中。NexusMods 商店 Activity 仍保留在工程内，但主 MOD 页入口暂时隐藏。

MOD 卡片默认折叠，只显示左侧拖拽手柄、名称、版本/作者和启用开关；展开后显示完整描述、分类/路径、作者、依赖，以及右下角图标按钮：选中、信息、删除；超长描述默认截断到 10 行并提供“显示更多”。MOD 列表按“前置库 / 内容模组 / 用户新建分组”分区，分组右侧可收起/展开。长按卡片左侧手柄可跨分组或组内拖拽排序，长按分组 header 可移动整个分组；拖拽开始会触发一次轻微震动反馈，列表中会插入半透明虚线 ghost 占位并用 LayoutTransition 平滑让位。用户新建分组会在对应 MOD 根目录下写入 `.sts2_mod_group` 标记文件，便于空分组也能被识别；不要把该标记误认为游戏 MOD manifest。组顺序与组内 MOD 顺序保存在本地 `sts2_mod_profiles` SharedPreferences，不影响游戏运行时 manifest 语义。

导入 MOD 时，Android shell 会先把选择的 zip/文件解包到 cache staging 目录并解析 manifest。如果发现新导入 manifest 的 `id` 与已安装 MOD 相同，会弹出冲突 Dialog，说明“连体现象”：界面可能显示两个项目，但任何一个开关都会按同 ID 同时影响两个。Dialog 会用信息卡分别展示原 MOD 和新 MOD，用户可选择保留原 MOD（丢弃本次 staging）或使用新 MOD（删除同 ID 原 MOD 后提交 staging）。该规则同样服务 Nexus 下载导入路径，避免同 ID manifest 在 `<files>/mods` 或隔离 MOD 根中长期并存。

同 ID 冲突处理后，导入流程还会按 staging 到当前 MOD 根目录的实际相对路径预检文件覆盖。如果新导入内容会写入已经存在的 `.dll` / `.pck` / `.json` 或其它资源文件，且这些文件不属于用户刚确认替换的同 ID 旧 MOD，会再次弹出“文件覆盖”警告，列出会被覆盖的相对路径和可推断的现有归属；默认取消/保留已安装文件，只有用户明确选择替换时才继续。底层提交接口默认不允许未确认的路径覆盖，Nexus 下载导入也会先 staging 并复用这两级确认流程，避免把 A MOD 的 DLL/PCK 静默替换成 B MOD 的文件。

## 4. NexusMods 商店导入

`NexusModsStoreActivity` 仍作为实验性页面保留，但 `ModsPage` 暂时不展示入口。后续重新开放时需继续遵循：

- 用户必须手动输入并保存自己的 NexusMods Personal API Key；获取教程入口指向 <https://www.nexusmods.com/settings/api-keys>，提示用户滑到页面底部并点击 “Request Personal API Key”。
- API Key 存在 Android 私有 `SharedPreferences`（`sts2_nexus_mod_store`）中，不写入仓库、不导出到构建产物。
- 默认游戏域名为 `slaythespire2`。官方 API 没有完整全站文本搜索接口，因此关键词搜索会聚合并筛选 trending/latest/updated feed；输入 Nexus MOD URL 或数字 MOD ID 会走精确查询。
- 下载流程会获取 NexusMods 文件列表，选择文件后尝试生成下载链接并把下载到的 ZIP 先交给 `ExtraSettingsRepository.prepareDownloadedModImport()` 解包到 staging；若触发同 ID 或路径覆盖冲突，会复用本地导入弹窗，确认后再提交到当前 launch profile 的 MOD 目录并启用 MOD 总开关。
- NexusMods 对非 Premium 用户可能要求先访问网页；此时界面会引导打开网页并支持粘贴 `nxm://...key=...&expires=...` 链接重试下载。

## 5. MOD 启用/禁用协议

Java 附加设置页通过 `ExtraSettingsRepository` 写入当前 launch profile 的 settings：

```text
# save_mode=global
<files>/default/1/settings.save

# save_mode=isolated
<files>/instances/<profile_id>/default/1/settings.save
```

兼容层中的 `AppPaths` / `SavePathPatches` 会把原版 `UserDataPathProvider` 指向当前 profile 的 account root；`AndroidSettingsBridge` / `AndroidSettingsPatches` 通过 `AppPaths.SettingsPath` 读取 companion JSON，将 Android-only 字段投影到当前游戏版本的 `ModSettings`：

- `mod_settings.mods_enabled`
- `mod_list[]`
- legacy `disabled_mods[]`

修改设置 key 时必须同步：

- Java repository / UI；
- `port-mod/STS2AndroidPortCompat/Android/AndroidSettingsBridge.cs`；
- 相关 `Patches/*Settings*.cs`；
- `.agent/agent-docs/changelog/`（agent-only，不提交）。

## 6. compat pack 分支维护

当前维护分支：

| 游戏版本 | 分支 | 原版引用配置 | ReferenceFlavor |
| --- | --- | --- | --- |
| `v0.103.2` / `v0.103.3` | `compat/v0.103.2` | `.env`: `STS2_ORIGINAL_V103_REFERENCE_DIR` 或 `STS2_ORIGINAL_V103_ROOT` | `original` |
| `v0.106.1` beta（旧测试） | `compat/v0.106.1-beta` | `.env`: `STS2_ORIGINAL_V1061_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1061_ROOT` | `original-v0.106.1` |
| `v0.107.0` beta | `compat/v0.107.0-beta` | `.env`: `STS2_ORIGINAL_V1070_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1070_ROOT` | `original-v0.107.0` |

开发步骤建议：

```bash
# 1. 确认 submodule 分支
git -C port-mod status --short --branch

# 2. 编译对应 original gate（引用目录从 .env 解析）
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh

# 3. 构建 fallback 或 compat pack
tools/android/build-port-mod.sh
# 或
(cd port-mod && ./tools/build-compat-pack.sh)

# 4. 构建内置包/导入版 APK
tools/android/stage-bundled-compat-packs.sh
tools/package/build_importer_apk.sh
```

对 `compat/v0.103.2` 分支，把 `ReferenceFlavor` 改为 `original`；维护旧 beta `compat/v0.106.1-beta` 时使用 `original-v0.106.1`。

## 7. 新增游戏版本 checklist

新增一个目标游戏版本时，至少需要：

1. 准备对应 PC 原版/解包目录或 DLL 引用目录，并在 `.env.example` / 文档中增加对应 `STS2_ORIGINAL_*_REFERENCE_DIR` 说明。
2. 为新版本定义 `ReferenceFlavor` 到 `CompatReferenceDir` 的解析方式（脚本映射或显式环境变量），不要提交指向个人 workspace 的 symlink。
3. 新建 `port-mod` 分支，例如 `compat/vX.Y.Z`。
4. 新增/更新 `compat_manifest.*.json`：
   - `pack_id`
   - `display_name`
   - `compat_version`
   - `channel`
   - `target_game.version`
   - 可选 `target_game.supported_versions` / `compatible_versions` / `versions`（一个兼容包覆盖多个游戏 patch 版本时使用）
   - `target_game.source`（描述性来源，不写个人本地路径）
   - `target_game.sts2_dll_sha256`
5. 用对应 `ReferenceFlavor` 做 compile gate。
6. 更新 `tools/android/bundled-compat-packs.json`。
7. 运行 `tools/android/stage-bundled-compat-packs.sh`。
8. 更新 `AGENTS.md`、`doc/architecture/project-structure.md`、本文件，并在 `.agent/agent-docs/changelog/` 写 agent changelog。
9. 构建 `tools/package/build_importer_apk.sh` 并做至少一次导入/启动 smoke test。

## 8. patch 开发注意事项

- 优先使用 prefix/postfix 和反射兜底，谨慎使用复杂 transpiler；Android 上 MonoMod/Cecil/Godot StringName 生命周期问题更容易暴露。
- 任何直接引用游戏内部类型的 patch 都可能随游戏版本变化失效，应保留在对应 compat 分支。
- `ModEntry.Apply()` patch 顺序很重要：BaseLib/RitsuLib 与平台/路径类 patch 必须早于 ModLoader。
- Android temp 目录必须尽早配置，否则 Harmony/MonoMod 可能尝试使用不可写 `/tmp`。
- Shader/resource overlay 资源应放入 `port-mod/overlay/`，重新打包 `port_compat.pck` 后才能生效。
- 普通 MOD loader 的目标是尽量复用游戏原本的 scanner、dependency sort 和 TryLoadMod，减少与 PC 行为分叉。
- `v0.103.x`、`v0.106.1` beta 与 `v0.107.0` beta 分支应保持同一套 Android/Mono MOD 初始化不变式：加载任何 MOD 前只允许预注册原版模型占位；每个 MOD initializer 期间只允许短暂隐藏“非原版类型命中早期原版占位”的 `ModelDb.Contains(Type)` 结果，避免与原版同名的 MOD 模型因 Android 提前占位误报重复；若 MOD 因早期占位误判 ModelDb 已初始化并调用 `AbstractModel.InitId()`，兼容层只在 `ModelIdSerializationCache.Init()` 完成前跳过该次早调用，后续正常 `ModelDb.InitIds()` 会统一设置排序 ID，不得提前动态分配 net ID；MOD 自定义模型占位必须等到所有 MOD Harmony patch 应用后、`ModelDb.Init()` 前再按最终 ID 注册。用户 MOD 在 initializer / `PatchAll` 中 patch STS2 Godot/UI 类型时，`DeferredModPatchQueue` 可将带静态初始化器的 UI patch 延后到 `ExecuteEssential` 完成后重放；不要把普通模型类 patch 一并延后，否则会破坏 MOD 依赖的 `ModelDb.Init` 前置 hook 时序。

## 9. MOD 兼容性排查规范

排查普通 MOD 在 Android 上无法加载、依赖缺失、初始化顺序异常或行为与 PC 不一致时，建议按以下方式收集参照信息：

- 可以把常用前置/依赖 MOD 仓库 clone 到工作区外或 `.agent/reference-repos/` 等不提交的位置，并 checkout 到与目标游戏版本、目标 MOD 版本匹配的 tag/branch/commit 后对照排查；不要把这些第三方源码或构建产物提交到本仓库。
- 优先参考对应版本 PC 原版/解包代码，尤其是 `ModManager`、依赖排序、manifest 解析、assembly resolve、资源加载和初始化回调的时序；重点确认 Android 兼容层是否漏掉某一步、提前/延后某一步，或改变了原版加载顺序导致 MOD 兼容问题。
- 对没有公开源码的 MOD，可以通过反编译其程序集获取可参考信息，用于定位入口类、manifest、依赖声明、Harmony patch、资源路径和初始化假设；反编译结果只作为本地诊断依据，不要提交第三方反编译源码或违反其许可条款。
- 常见前置/依赖仓库：
  - RitsuLib: <https://github.com/BAKAOLC/STS2-RitsuLib>
  - BaseLib-StS2: <https://github.com/Alchyr/BaseLib-StS2>

## 10. 兼容包 manifest 约定

schema 1 legacy 单目标 manifest：

```json
{
  "schema": 1,
  "pack_id": "sts2-android-compat-v0.107.0-beta",
  "display_name": "STS2 Android Compatibility beta for v0.107.0",
  "compat_version": "0.3.1-beta.1070",
  "channel": "beta",
  "target_game": {
    "version": "v0.107.0",
    "supported_versions": ["v0.107.0"],
    "source": "original_pc_reference_v0.107.0",
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

schema 2 family manifest：

```json
{
  "schema": 2,
  "pack_id": "sts2-android-compat",
  "display_name": "STS2 Android Compatibility",
  "compat_version": "0.4.0",
  "channel": "mixed",
  "targets": [
    {
      "target_id": "v0.107.0-beta",
      "versions": ["v0.107.0"],
      "source": "original_pc_reference_v0.107.0",
      "sts2_dll_sha256": "...",
      "artifacts": {
        "dll": "variants/v0.107.0-beta/STS2Mobile.dll",
        "overlay_pck": "variants/v0.107.0-beta/port_compat.pck"
      }
    }
  ]
}
```

`CompatPackManager` 对 schema 1 优先用 `target_game.version` 与 payload manifest 的 `version` 精确匹配；对 schema 2 会把 `targets[]` 展开成可选 variant，并优先按 payload 的 `sts2_dll_sha256` 精确匹配，再回落到 `version` / `versions` 列表。启动配置保存 `compat_pack_id`；schema 2 还保存 `compat_target_id`，因此一个 family 包可以覆盖多个游戏版本，也可以在停止维护旧 target 后把它拆成独立 legacy 包。

## 11. 用户 MOD 测试建议

1. 先确认无普通 MOD 时游戏可启动。
2. 安装 BaseLib/RitsuLib 等基础库 MOD，查看 log 中 BaseLib/RitsuLib compatibility patch 是否正常。
3. 安装目标普通 MOD 到当前 launch profile 的 MOD 目录（全局 `<files>/mods/` 或隔离 `<files>/instances/<profile_id>/mods/`）。
4. 在附加设置中开启 MOD 总开关并确认单 MOD 未禁用。
5. 启动后查看日志：
   - `[Mods] Android mod initialization loaded ...`
   - dependency sort / TryLoadMod 相关日志；
   - 是否出现 assembly resolve 失败。
6. 若某 MOD 只支持特定游戏版本，优先在版本页切到匹配 payload 和 compat pack；需要同一本体多套 MOD/存档时，在版本页为同一个 game body 新建多个隔离 launch profile。
