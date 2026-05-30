# doc/ 文档索引

`doc/` 是本仓库新的长期维护文档入口。以后新增功能、修改构建流程、调整兼容包/版本模型时，应优先更新这里，并在 `doc/changelog/` 记录变更。

## 文档地图

- [`changelog/`](changelog/)：每一次用户可见或维护相关修改的记录。
- [`architecture/project-structure.md`](architecture/project-structure.md)：仓库结构、目录职责、运行时私有目录、版本/兼容包模型。
- [`build/building-and-packaging.md`](build/building-and-packaging.md)：本地环境、构建脚本、导入版/直装版 APK、兼容包打包。
- [`runtime/compat-pack-loading-flow.md`](runtime/compat-pack-loading-flow.md)：Android 兼容包、`STS2Mobile.dll`、`port_compat.pck` 与普通 MOD 的详细加载流程。
- [`modding/mod-and-compat-notes.md`](modding/mod-and-compat-notes.md)：普通用户 MOD 管理、兼容补丁开发、分支/compile gate 注意事项。

历史目录 `docs/` 仍保留旧阶段的 diff inventory / validation 报告；新文档和后续维护说明不要继续扩散到 `docs/`，除非是在迁移旧资料。

## 维护规则

1. **每次修改写 changelog**：新增 `doc/changelog/YYYY-MM-DD-主题.md`，说明背景、改动、验证、风险。
2. **构建流程变化同步两处**：更新 `AGENTS.md` 和 `doc/build/building-and-packaging.md`。
3. **目录/版本/兼容包模型变化同步两处**：更新 `AGENTS.md` 和 `doc/architecture/project-structure.md`。
4. **MOD/兼容层加载流程变化同步两处**：更新 `doc/runtime/compat-pack-loading-flow.md` 与 `doc/modding/mod-and-compat-notes.md`。
5. **不要写入商业游戏内容**：文档可以记录本地路径、版本号、hash 摘要与构建命令，但不能复制游戏资源、完整 payload 或私钥。
6. **构建验证**：完成用户要求的改动后，按 `AGENTS.md` 要求执行 `tools/package/build_importer_apk.sh`，除非用户明确要求不构建或环境缺失。

## 推荐 changelog 模板

```md
# YYYY-MM-DD 主题

## 背景

## 改动

## 验证

## 风险 / 后续
```
