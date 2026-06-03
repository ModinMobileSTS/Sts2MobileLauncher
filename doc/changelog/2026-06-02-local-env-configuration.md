# 2026-06-02 本地环境配置集中化

## 背景

构建脚本和 compat compile gate 过去直接引用开发者本机的相邻目录或绝对路径，影响他人复现，也容易把个人路径写进文档和脚本。

## 改动

- 新增 `.env.example`，统一声明 JDK、Android SDK、.NET、runtime 参考、FMOD/crypto artifact、original compile gate 引用、签名和可选 payload zip 等环境变量。
- 新增 `local.properties.example`，保存 Gradle task、APK 输出路径、compat pack staging、默认 `ReferenceFlavor` 等非 secret 本地选项；`.env` 与 `local.properties` 均加入 `.gitignore`。
- 新增 `tools/env/load-local-config.sh`，作为 bash 构建脚本共用的配置 loader。
- 更新 Android/package 脚本，改为从 `.env` / `local.properties` 解析路径和选项，不再硬编码参考工程路径或个人 debug keystore 路径。
- 更新 `port-mod` 构建脚本和 `Directory.Build.props`，通过 `CompatReferenceDir` 使用 `.env` 配置的 original/runtime DLL 引用；删除已跟踪的个人 workspace symlink，仅保留 refs README 占位。
- 更新 diff inventory 工具，移除默认本地相邻目录，改为要求参数或 `.env` 中的输入路径。
- 新增 `tools/deps/external-github-projects.json` 与 `tools/deps/prepare-external-projects.sh`，可初始化 `port-mod` submodule 并 clone 开源 GitHub 参考项目到 `.agent/reference-repos/`。
- 新增 `doc/build/local-configuration.md` 并同步 README、AGENTS、build/architecture/modding 文档。

## 验证

- `bash -n tools/env/load-local-config.sh tools/android/env-from-s2.sh tools/android/gradle-with-s2-env.sh tools/android/sync-runtime-from-references.sh tools/android/build-port-mod.sh tools/android/stage-bundled-compat-packs.sh tools/package/build_importer_apk.sh tools/package/build_direct_apk.sh port-mod/tools/build-compat-pack.sh`
- `python3 -m py_compile tools/diff/make_diff_inventory.py`
- `tools/deps/prepare-external-projects.sh --list`
- 未配置 `.env` 时运行 `tools/android/gradle-with-s2-env.sh --version` 会明确提示缺少 `JAVA_HOME`。
- 配置本机 `.env` 后运行 `tools/package/build_importer_apk.sh`：成功，输出 `dist/sts2-re-importer.apk`，最终 Gradle APK sha256 为 `ad4671b27dcf1405b111c70211bebdcaacab50086d9545817d35423abb36c8ac`。

## 风险 / 后续

- 旧 shell 环境如果没有 `.env` 且也没有导出必要变量，脚本会明确报缺少配置；首次构建需复制并编辑 example 文件。
- `stage-bundled-compat-packs.sh` 对新兼容分支的 `ReferenceFlavor` 自动映射目前覆盖 v0.103.2 和 v0.106.1；新增版本时需要同步脚本映射和文档。
