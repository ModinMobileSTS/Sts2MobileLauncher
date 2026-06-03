# doc/ 文档索引

`doc/` 是本仓库的长期、公开项目文档入口。新增功能、修改构建流程、调整兼容包/版本模型时，应优先更新这里和根目录 `README.md` 中的用户可见说明。

changelog 主要用于记录 agent 修改过程、验证流水和本地接力信息，不再放在公开仓库中；已迁移到 ignored 的 `.agent/agent-docs/changelog/`。旧 `docs/` 历史 diff/validation 资料也已迁移到 ignored 的 `.agent/historical-backup/docs/`。

## 文档地图

- [`architecture/project-structure.md`](architecture/project-structure.md)：仓库结构、目录职责、运行时私有目录、版本/兼容包模型。
- [`build/building-and-packaging.md`](build/building-and-packaging.md)：本地环境、构建脚本、导入版/直装版 APK、兼容包打包。
- [`build/local-configuration.md`](build/local-configuration.md)：`.env` / `local.properties` 本地配置、路径迁移和 CI 配置建议。
- [`runtime/compat-pack-loading-flow.md`](runtime/compat-pack-loading-flow.md)：Android 兼容包、`STS2Mobile.dll`、`port_compat.pck` 与普通 MOD 的详细加载流程。
- [`modding/mod-and-compat-notes.md`](modding/mod-and-compat-notes.md)：普通用户 MOD 管理、兼容补丁开发、分支/compile gate 注意事项。
- [`plan/`](plan/)：长期设计计划或已落地方案的维护 checklist；一次性 agent 调查上下文、review/scout 记录不要放入这里，应放入 ignored 的 `.agent/`。

## 维护规则

1. **用户可见说明进公开文档**：新增/调整功能时，同步更新 `README.md` 和对应 `doc/` 页面。
2. **agent changelog 不入库**：每次修改的 changelog 写到 `.agent/agent-docs/changelog/YYYY-MM-DD-主题.md`；不要新建 `doc/changelog/`，也不要 `git add -f` `.agent/`。
3. **构建流程变化同步两处**：更新 `AGENTS.md` 和 `doc/build/building-and-packaging.md`。
4. **目录/版本/兼容包模型变化同步两处**：更新 `AGENTS.md` 和 `doc/architecture/project-structure.md`。
5. **MOD/兼容层加载流程变化同步两处**：更新 `doc/runtime/compat-pack-loading-flow.md` 与 `doc/modding/mod-and-compat-notes.md`。
6. **第三方来源同步**：直接引用资源、改编/参考开源仓库实现或新增依赖时，同步更新根目录 `THIRD_PARTY_LICENSES.md`；用户可见说明同步到 `README.md`。
7. **不要写入商业游戏内容**：文档可以记录本地路径类别、版本号、hash 摘要与构建命令，但不能复制游戏资源、完整 payload、账号 token 或私钥。
8. **生成产物不入库**：`android/assets/compat_packs/*.zip`、`.agent/`、runtime、payload、APK 等均为本地生成/私有内容，不应提交。
9. **构建验证**：完成用户要求的改动后，按 `AGENTS.md` 要求执行 `tools/package/build_importer_apk.sh`，除非用户明确要求不构建或环境缺失。
