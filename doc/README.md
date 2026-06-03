# doc/ 文档索引

`doc/` 是本仓库新的长期项目文档入口。以后新增功能、修改构建流程、调整兼容包/版本模型时，应优先更新这里，并在 `doc/changelog/` 记录项目 changelog。编码代理专用的操作约定保留在根目录 `AGENTS.md`；本地 agent 草稿、计划、报告、临时 worktree、外部参考 clone 与 agent 文档放入 `.agent/`，该目录不进入版本控制。本次及后续 changelog 也应在 `.agent/agent-docs/changelog/` 保留 agent 本地镜像/接力摘要，但它不能替代可提交的 `doc/changelog/`。

## 文档地图

- [`changelog/`](changelog/)：每一次用户可见或维护相关修改的记录。
- [`architecture/project-structure.md`](architecture/project-structure.md)：仓库结构、目录职责、运行时私有目录、版本/兼容包模型。
- [`build/building-and-packaging.md`](build/building-and-packaging.md)：本地环境、构建脚本、导入版/直装版 APK、兼容包打包。
- [`build/local-configuration.md`](build/local-configuration.md)：`.env` / `local.properties` 本地配置、路径迁移和 CI 配置建议。
- [`runtime/compat-pack-loading-flow.md`](runtime/compat-pack-loading-flow.md)：Android 兼容包、`STS2Mobile.dll`、`port_compat.pck` 与普通 MOD 的详细加载流程。
- [`modding/mod-and-compat-notes.md`](modding/mod-and-compat-notes.md)：普通用户 MOD 管理、兼容补丁开发、分支/compile gate 注意事项。

历史目录 `docs/` 仍保留旧阶段的 diff inventory / validation 报告；新文档和后续维护说明不要继续扩散到 `docs/`，除非是在迁移旧资料。长期设计计划可以放入 `doc/plan/`；一次性 agent 调查上下文、review/scout 记录不要放在仓库根目录，应放入 ignored 的 `.agent/`。

## 维护规则

1. **每次修改写 changelog**：新增 `doc/changelog/YYYY-MM-DD-主题.md`，说明背景、改动、验证、风险；同时把同一条或 agent 视角摘要镜像到 ignored 的 `.agent/agent-docs/changelog/`，便于后续 agent 本地接力。
2. **构建流程变化同步两处**：更新 `AGENTS.md` 和 `doc/build/building-and-packaging.md`。
3. **目录/版本/兼容包模型变化同步两处**：更新 `AGENTS.md` 和 `doc/architecture/project-structure.md`。
4. **MOD/兼容层加载流程变化同步两处**：更新 `doc/runtime/compat-pack-loading-flow.md` 与 `doc/modding/mod-and-compat-notes.md`。
5. **不要写入商业游戏内容**：文档可以记录本地路径、版本号、hash 摘要与构建命令，但不能复制游戏资源、完整 payload 或私钥。
6. **构建验证**：完成用户要求的改动后，按 `AGENTS.md` 要求执行 `tools/package/build_importer_apk.sh`，除非用户明确要求不构建或环境缺失。
7. **第三方来源同步**：直接引用资源、改编/参考开源仓库实现或新增依赖时，同步更新根目录 `THIRD_PARTY_LICENSES.md`；用户可见说明同步到 `README.md`。
8. **生成产物不入库**：`android/assets/compat_packs/*.zip`、`.agent/`、runtime、payload、APK 等均为本地生成/私有内容，不应提交；`.agent/agent-docs/` 也是本地 agent 文档区，不应 `git add -f`。

## 推荐 changelog 模板

```md
# YYYY-MM-DD 主题

## 背景

## 改动

## 验证

## 风险 / 后续
```
