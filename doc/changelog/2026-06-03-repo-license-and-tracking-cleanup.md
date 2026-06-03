# 2026-06-03 仓库授权与追踪规则整理

## 背景

本次整理仓库来源、授权和生成产物追踪规则：submodule 需要使用公开 GitHub URL，compat pack zip 应作为本地构建产物而不是源码提交内容，项目文档和 agent 临时资料也需要明确边界。

## 改动

- `.gitmodules` 中 `port-mod` submodule URL 改为 `https://github.com/ModinMobileSTS/sts2-android-compat.git`。
- `android/assets/compat_packs/*.zip` 改为 gitignored 生成产物，并从 git index 移除；打包脚本仍会在构建时刷新这些 zip 并打入 APK assets。
- 新增 `LICENSE`，建议/采用 MIT 作为本仓库原创代码许可证。
- 重写 `THIRD_PARTY_LICENSES.md`，补充直接引用资源、参考实现仓库、Gradle/JVM 依赖、可选 MOD 参考仓库和发布前合规检查；README 中明确列出 `SlayTheAmethystModded` 与 `StS2-Launcher_Mod_Manager` 的引用/参考关系。
- 明确项目文档与 agent 专用资料边界：长期项目文档在 `README.md` / `doc/`，agent 操作约定在 `AGENTS.md`，本地草稿/报告/worktree/参考 clone 放入 ignored 的 `.agent/`。
- 新增 ignored 的 `.agent/agent-docs/README.md` 与 `.agent/agent-docs/changelog/` 本地 agent 文档区，并在 `AGENTS.md` / `doc/README.md` 约束：项目 changelog 仍写 `doc/changelog/`，同时镜像/摘要到 `.agent/agent-docs/changelog/`，后者不能替代项目 changelog。
- 移除根目录一次性 agent 调查/审查记录的版本追踪（本地副本已移到 `.agent/archive-root-agent-notes/`）。

## 验证

- 已执行：`tools/package/build_importer_apk.sh`。
- 构建成功，输出：
  - `android/build/outputs/apk/mono/release/sts2-re.apk`
  - `dist/sts2-re-importer.apk`
- APK SHA-256：`2d23c432c5c9c1343d09e892238295df96885bd287fe53a3220c77ae12f038ba`。
- 构建过程中重新生成了 `android/assets/compat_packs/*.zip`，但该目录已 gitignore，zip 产物未重新进入 git 跟踪。
- 已确认 `.agent/agent-docs/README.md` 与 `.agent/agent-docs/changelog/2026-06-03-repo-license-and-tracking-cleanup.md` 命中 `.gitignore`，不会进入 git 跟踪。

## 风险 / 后续

- `SlayTheAmethystModded` 顶层未检测到标准 SPDX 许可证；本仓库从其改编/参考的 Steam 相关实现发布前应保留授权依据或进一步向上游确认。
- 未来发布时需要对实际打入 APK 的 Godot/.NET/FMOD/native runtime 和 Gradle 依赖导出完整 notice/license 清单。
