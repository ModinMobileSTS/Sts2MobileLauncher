# 本地配置与可复现构建

本仓库不再把开发者机器上的 `../s2`、`../s2_original`、`/home/...` 等路径写死在构建脚本里。构建所需的本机路径统一放在仓库根目录 `.env`；非环境变量的本地构建选项放在 `local.properties`。两个文件都被 `.gitignore` 排除。本地 agent/参考资料默认放在 `.agent/`，同样不进入版本控制。

## 1. 初始化

```bash
cp .env.example .env
cp local.properties.example local.properties
```

然后编辑 `.env` 中的本机路径。路径可以写绝对路径，也可以写相对仓库根目录的相对路径；包含空格时请加引号。

## 2. `.env`：本机环境变量

`.env` 只放与机器/CI 环境有关的变量，尤其是工具链、外部引用目录、私有输入和签名密钥。

必填或常用项：

| 变量 | 用途 |
| --- | --- |
| `JAVA_HOME` | 完整 JDK 路径，必须包含 `bin/javac`。 |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Android SDK 根目录，需包含 platform 35、build-tools、NDK/CMake。 |
| `DOTNET_BIN` | `.NET SDK` 可执行文件，用于编译 `STS2Mobile.dll`。 |
| `STS2_ANDROID_RUNTIME_REFERENCE_ROOT` | 参考 Android template 目录，需包含 `libs/`、`assets/dotnet_bcl/`、`gradle/wrapper/gradle-wrapper.jar`。 |
| `STS2_FMOD_PLUGIN_AAR` | FMOD Android AAR；同版本 2.03.06 arm64 native 库默认取其旁边的 `arm64/`。 |
| `STS2_FMOD_ANDROID_LIBS_DIR` | 可选：单独指定 FMOD 2.03.06 `libfmod.so`、`libfmodstudio.so` 与 `libGodotFmod.android.template_release.arm64.so` 所在目录；不设置时使用 AAR 同级的 `arm64/`。同步前严格检查三件套 SHA，不接受旧 2.02 或混搭库。 |
| `STS2_CRYPTO_NATIVE_JAR` | Godot/Mono crypto native Java wrapper jar。 |
| `STS2_ORIGINAL_V103_ROOT` 或 `STS2_ORIGINAL_V103_REFERENCE_DIR` | v0.103.x stable original compile gate 引用。 |
| `STS2_ORIGINAL_V1061_ROOT` 或 `STS2_ORIGINAL_V1061_REFERENCE_DIR` | v0.106.1 beta（旧测试）original compile gate 引用。 |
| `STS2_ORIGINAL_V1070_ROOT` 或 `STS2_ORIGINAL_V1070_REFERENCE_DIR` | v0.107.0 beta（旧测试）original compile gate 引用。 |
| `STS2_ORIGINAL_V1071_ROOT` 或 `STS2_ORIGINAL_V1071_REFERENCE_DIR` | v0.107.1 stable original compile gate 引用。 |
| `STS2_ORIGINAL_V1080_ROOT` 或 `STS2_ORIGINAL_V1080_REFERENCE_DIR` | v0.108.0 stable original compile gate 引用。 |
| `STS2_ORIGINAL_V1090_ROOT` 或 `STS2_ORIGINAL_V1090_REFERENCE_DIR` | 共享 v0.109.x target 的 original compile gate；变量名为兼容旧脚本保留，应指向最新 v0.109.1 引用。 |
| `STS2_ORIGINAL_V1100_ROOT` 或 `STS2_ORIGINAL_V1100_REFERENCE_DIR` | 共享 v0.110.x public-beta target 的 original compile gate；历史变量名，应指向 v0.110.1 引用。 |
| `STS2_ORIGINAL_V1110_ROOT` 或 `STS2_ORIGINAL_V1110_REFERENCE_DIR` | v0.111.1 stable/public 与旧 v0.111.0 beta/public-beta 两个独立 metadata target 共用的 compile gate；保留现有 v0.111.0 引用、`original-v0.111.0` flavor 与 `STS2_TARGET_1110`，不新增 V1111 变量。 |
| `STS2_ORIGINAL_V1130_ROOT` 或 `STS2_ORIGINAL_V1130_REFERENCE_DIR` | 共享 v0.113.x public-beta target 的 original compile gate；历史 V1130 名称保留，应指向最新 v0.113.1 引用。 |
| `STS2_ORIGINAL_V1140_ROOT` 或 `STS2_ORIGINAL_V1140_REFERENCE_DIR` | 当前 v0.114.0 public-beta target 的独立 original compile gate；复用 `STS2_TARGET_1130` 的 Android 补丁路径。 |
| `RELEASE_KEYSTORE_FILE` / `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEYSTORE_ALIAS` | APK 签名参数；首次本地测试可用 Android debug keystore。已发布版本的更新必须沿用原私钥与证书，不可直接换 key 或重新生成同名 keystore。签名方案与覆盖安装校验见[构建文档 §3.1](building-and-packaging.md#31-apk-签名方案与更新身份)。 |
| `COMPAT_RELEASE_SIGNING_KEY_FILE` / `COMPAT_RELEASE_SIGNING_KEY_ID` | 官方兼容包索引 Ed25519 私钥 PEM 的仓库外路径及 key id（默认 `sts2-compat-release-v1`）；与 APK 签名无关。只将 raw public key Base64 写入启动器。见[首次签名配置](building-and-packaging.md#61-本地签名与发布官方兼容包)。 |

可选快捷项：

- `STS2_REFERENCE_ROOT`：如果你的参考工程仍是一个统一目录，可设置它；未显式设置的 JDK/SDK/.NET/FMOD/launcher runtime 路径会从它派生。
- `STS2_LAUNCHER_REFERENCE_ROOT`：旧 launcher/runtime 根目录；未显式设置时 `STS2_ANDROID_RUNTIME_REFERENCE_ROOT`、`STS2_RUNTIME_REFERENCE_DIR`、`STS2_CRYPTO_NATIVE_JAR` 会从它派生。
- `STS2_PAYLOAD_ZIP`：直装版构建脚本无参数调用时使用的本地游戏 zip。不要提交该 zip。
- `STS2_DIFF_MOBILE_ROOT` / `STS2_DIFF_ORIGINAL_ROOT`：历史 diff inventory 工具输入。

## 3. `local.properties`：本地构建选项

`local.properties` 放非 secret、非机器工具链的本地选项；环境变量仍优先于这里的同名配置。常用项：

| Key | 默认值 | 说明 |
| --- | --- | --- |
| `android.gradle.task` | `assembleMonoRelease` | package 脚本执行的 Gradle task。 |
| `android.apk.output` | `android/build/outputs/apk/mono/release/sts2-re.apk` | Gradle APK 输出路径。 |
| `android.importer.dist` | `dist/sts2-re-importer.apk` | 导入版稳定副本；若已有旧 APK 则先检查签名证书一致。只发布 v1/v2/v3 APK，清除残留 `.idsig`。 |
| `android.direct.dist` | `dist/sts2-re-direct.apk` | 直装版稳定副本；若已有旧 APK 则先检查签名证书一致。只发布 v1/v2/v3 APK，清除残留 `.idsig`。 |
| `android.release_keystore_*` | debug keystore 兼容默认值 | 本地签名 fallback；更建议把密码类值放到 `.env` 或 CI secrets。 |
| `runtime.mono_memory_stats_fix` | `0` | `1` 启用严格 SHA 限定的 Ekyso ARM64 Mono 内存总量查询实验修复；只改 staged 副本。环境变量 `MONO_MEMORY_STATS_FIX` 优先，仅允许 `0` / `1`。恢复 `0` 后重新打包会同步原始参考库，不改堆上限；范围与回滚见 [构建文档](building-and-packaging.md#41-实验性-mono-内存总量修复默认关闭)。 |
| `runtime.fmod_android_libs_dir` | AAR 同级的 `arm64/` | 当 native 库与 AAR 分开放置时指定目录；环境变量 `STS2_FMOD_ANDROID_LIBS_DIR` 优先，要求经过校验的 2.03.06 三件套。 |
| `compat.default_reference_flavor` | `original-v0.114.0` | 当前 public-beta fallback 使用 v0.114.0 原版引用；v0.113.x 的 `original-v0.113.0` 仍使用 v0.113.1，旧 flavor 可显式选择。 |
| `compat.pack_build_mode` | `release` | 验签并原样内置官方 schema 2 ZIP；`matrix` 从当前 `port-mod` checkout 编译 active targets；`legacy` 为多分支 schema 1 回退诊断。环境变量：`COMPAT_PACK_BUILD_MODE`。 |
| `compat.bundled_release_tag` | `tools/android/bundled-compat-release.json`（当前 `v0.8.0`） | `release` 模式选择固定已发布 tag；显式 `latest` 用 GitHub 稳定 Release 的签名索引解析。环境变量：`COMPAT_BUNDLED_RELEASE_TAG`。 |
| `compat.bundled_release_dir` | 空 | 可选离线输入目录，必须含 ZIP、索引及签名，和在线输入走相同校验。指定后失败不回退。环境变量：`COMPAT_BUNDLED_RELEASE_DIR`。 |
| `compat.bundled_packs_config` | `tools/android/bundled-compat-packs.json` | legacy 模式的内置包分支列表。 |
| `compat.asset_dir` | `android/assets/compat_packs` | 内置兼容包输出到 APK assets 的目录；生成 zip 已 gitignore，不提交。 |
| `compat.worktree_root` | `.agent/worktrees/compat-packs` | 多分支兼容包临时 worktree 根目录；位于 ignored `.agent/`。 |
| `compat.pack_remote` | `origin` | stage 脚本解析远端分支时使用的 remote。 |
| `compat.release_signing_key_file` / `compat.release_signing_key_id` | 空 / `sts2-compat-release-v1` | 可存仓库外 PEM 的路径及 key id，不能存私钥内容。对应 `COMPAT_RELEASE_SIGNING_KEY_*` 环境变量优先。 |
| `deps.external_projects_config` | `tools/deps/external-github-projects.json` | GitHub 外部参考项目清单。 |
| `deps.external_projects_root` | `.agent/reference-repos` | 自动 clone 外部参考项目的本地根目录；位于 ignored `.agent/`。 |

这三个 bundling 环境变量的优先级为命令行环境 > `.env` > `local.properties` > 仓库默认值。`release` 模式需要 Python `cryptography`，固定 tag 的缓存位于 gitignored 的 `.agent/cache/compat-releases/<tag>/`，复用时重做签名和完整性检查。旧 `local.properties` 若仍指定 `matrix`，要构建发行 APK 时请删掉覆盖或切回 `release`。操作见[发布与打包 SOP](building-and-packaging.md#6-构建内置兼容包)。

## 4. 自动准备 GitHub 外部参考项目

```bash
# 初始化 port-mod submodule，并 clone 默认参考项目
tools/deps/prepare-external-projects.sh

# 查看当前记录的 GitHub 外部项目
tools/deps/prepare-external-projects.sh --list

# 额外准备 MOD 兼容排查用参考仓库
tools/deps/prepare-external-projects.sh --group modding-reference

# 准备所有可 clone 的项目
tools/deps/prepare-external-projects.sh --all
```

默认会处理 `port-mod` submodule，并 clone `clone_by_default=true` 的开源参考仓库到 `deps.external_projects_root`。脚本不会下载商业游戏 payload、原版 DLL、keystore 或准备好的 Godot/Mono runtime；这些仍需通过 `.env` 指向你本地合法拥有/自行构建的输入。新增/变更参考仓库时同步 `THIRD_PARTY_LICENSES.md`。

当前清单维护在 `tools/deps/external-github-projects.json`，主要包括：

| 项目 | 用途 | 准备方式 |
| --- | --- | --- |
| `ModinMobileSTS/SlayTheAmethystModded` | Steam protocol/downloader 参考来源 | 默认 clone |
| `Apricityx/WorkshopAndroidDownloader` | Android Steam Workshop 浏览、下载和更新记录参考来源 | 默认 clone |
| `iunius612/StS2-Launcher_Mod_Manager` | launcher/runtime 参考源码 | 默认 clone；runtime 产物仍需 `.env` 指向本地准备结果 |
| `BAKAOLC/STS2-RitsuLib` | MOD 兼容排查参考 | `--group modding-reference` 或 `--all` |
| `Alchyr/BaseLib-StS2` | MOD 兼容排查参考 | `--group modding-reference` 或 `--all` |
| `freude916/sts2-quickRestart` | Quick Restart MOD 兼容参考 | `--group modding-reference` 或 `--all` |
| `ModinMobileSTS/Sts2MobileLauncher` | 应用更新检查/release 仓库 | `--all` 或 `--project sts2-mobile-launcher` |
| `L-JINBIN/MTDataFilesProvider` | Gradle/JitPack 调试文件 provider 依赖 | Gradle 自动解析，通常不 clone |

## 5. 迁移旧本地路径

旧文档常见路径可按下列方式迁移到 `.env`：

```bash
STS2_REFERENCE_ROOT=/path/to/old-android-port
JAVA_HOME=/path/to/jdk
ANDROID_HOME=/path/to/android-sdk
DOTNET_BIN=/path/to/dotnet
STS2_ANDROID_RUNTIME_REFERENCE_ROOT=/path/to/reference-launcher/android
STS2_ORIGINAL_V103_REFERENCE_DIR=/path/to/s21032/.godot/mono/temp/bin/Debug
STS2_ORIGINAL_V1061_REFERENCE_DIR=/path/to/s201061/.godot/mono/temp/bin/Debug
# Separate v0.111.1/public and v0.111.0/public-beta targets reuse this existing v0.111.0 gate.
STS2_ORIGINAL_V1110_REFERENCE_DIR=/path/to/v0.111.0/data_sts2_windows_x86_64
STS2_ORIGINAL_V1130_REFERENCE_DIR=/path/to/v0.113.1/data_sts2_windows_x86_64
STS2_ORIGINAL_V1140_REFERENCE_DIR=/path/to/v0.114.0/data_sts2_windows_x86_64
```

`port-mod/refs/*/*.dll` symlink 不再是构建脚本所需输入；如需 standalone IDE 编译，可以在本地自行放置或用环境变量传 `CompatReferenceDir`，但不要提交游戏/运行时 DLL 或指向个人目录的 symlink。

## 6. 构建命令

```bash
# 导入版 APK，不内置游戏 zip
tools/package/build_importer_apk.sh

# 直装版 APK：显式传 zip
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"

# 或在 .env 设置 STS2_PAYLOAD_ZIP 后无参数构建直装版
tools/package/build_direct_apk.sh

# 仅同步 runtime
tools/android/sync-runtime-from-references.sh


# 仅做 Java/Gradle 编译检查
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac

# 仅编译当前 compat fallback
tools/android/build-port-mod.sh

# Stage 全部内置兼容包：官方 full family + 本地 offline bootstrap
tools/android/stage-bundled-compat-artifacts.sh

# 未发布的 port-mod 改动：仅重新编译并 stage 本地 full family
COMPAT_PACK_BUILD_MODE=matrix tools/android/stage-bundled-compat-packs.sh
```

## 7. CI 建议

CI 中可以不创建 `.env` 文件，直接通过 secret/environment 注入同名变量；再把 `local.properties.example` 复制为 `local.properties` 或通过环境变量覆盖需要的路径。不要把 keystore、Steam 账号、游戏 zip、原版 DLL、完整 runtime 等私有输入上传到仓库。
