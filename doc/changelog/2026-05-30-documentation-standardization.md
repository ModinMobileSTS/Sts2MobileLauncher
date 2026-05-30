# 2026-05-30 文档规范化与 AGENTS 同步

## 背景

项目已经从单一 Android 兼容 MOD 逐步演进为：

- `port-mod/` 独立多分支 submodule；
- 同时维护正式/稳定 `v0.103.2` 与 beta `v0.106.1` 兼容包；
- Android shell 增加游戏版本、兼容包安装/选择、内置兼容包 staging 等能力。

旧 `AGENTS.md` 仍停留在早期单版本、单 `port_compat.pck`/`STS2Mobile.dll` 模式，因此需要同步最新版并建立规范化文档入口。

## 改动

- 重写 `AGENTS.md`：
  - 增加当前版本矩阵：`v0.103.2` 正式/稳定、`v0.106.1` beta。
  - 记录 `port-mod` 多分支 submodule、`ReferenceFlavor`、refs symlink、内置兼容包构建方式。
  - 更新 Android shell、payload/version manager、compat pack manager、launch preparation、构建脚本流程。
  - 明确新增 `doc/` 为长期文档入口，并要求每次修改写 changelog。
- 新增 `doc/` 目录：
  - `doc/README.md`
  - `doc/changelog/README.md`
  - `doc/architecture/project-structure.md`
  - `doc/build/building-and-packaging.md`
  - `doc/runtime/compat-pack-loading-flow.md`
  - `doc/modding/mod-and-compat-notes.md`
- 更新 `.gitignore`，允许跟踪 `doc/build/` 文档目录，避免被通用 `build/` 忽略规则误伤。
- 保留旧 `docs/` 目录作为历史 diff/validation 资料，不再作为新文档入口。

## 验证

本次修改属于文档和工程规范更新，不改 Java/C# runtime 逻辑。已按项目约定执行：

```bash
tools/package/build_importer_apk.sh
```

首次执行时发现参考缓存 `../s2/.cache/StS2-Launcher_Mod_Manager/android/libs` 缺失；已用本仓库现有本地 runtime 产物临时补齐该本机参考缓存后重试。第二次构建成功，输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-importer.apk
```

APK sha256：

```text
d10a91ef8847086404948f67fc14304b020aa25b4ad68a8ca3a5a7b9a6c7a69b  android/build/outputs/apk/mono/release/sts2-re.apk
```

构建期间仅有既有警告：FMOD shim Java deprecation、`QuickRestartPatches.cs` CS4014、Gradle 8.13/未来 Gradle 10 的 Groovy DSL deprecation。

## 风险 / 后续

- 当前普通用户 MOD 目录仍是全局 `<files>/mods`，尚未按 instance 隔离；未来如果推进 instance/profile，需要同步 Java 与 C# 路径桥接。
- `android/assets/compat_packs/*.zip` 是允许提交的内置兼容包产物，但必须确认不含游戏 payload。
- 如果新增更多游戏版本，需要同步 `tools/android/bundled-compat-packs.json`、`port-mod` 分支/manifest、original refs、`AGENTS.md` 与 `doc/` 版本矩阵。
