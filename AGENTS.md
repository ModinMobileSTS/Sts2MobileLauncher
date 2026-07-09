# AGENTS.md

面向后续编码代理/维护者的项目速览与操作约定。当前目录为本仓库根目录。
最后同步：2026-06-19。

## 0. 总原则

- 本工程是 **Slay the Spire 2 Android 重构移植/启动器工程**，不是完整游戏源码仓库。
- 仓库只维护 Android shell、导入/版本管理逻辑、兼容包构建脚本与 Android 兼容补丁源码；**不提交用户游戏 zip、解压后的完整游戏 payload、大型 Godot/Mono runtime、keystore**。
- `port-mod/` 是独立仓库 <https://github.com/ModinMobileSTS/sts2-android-compat> 的 **git submodule**。当前开发默认使用 flat matrix 模式：一个 checkout 读取 `port-mod/targets/active/*/target.json`，为多个目标版本构建 schema 2 family 兼容包；按游戏版本分支构建的 legacy 模式只作为显式回退/诊断路径保留。
- 新增或修改功能时必须同步文档：用户可见/长期维护说明优先更新 `README.md` / `doc/`；变更流水/changelog 只写入 `.agent/agent-docs/changelog/`（不提交），因为它主要服务 agent 接力，不作为公开仓库文档。历史 `docs/` 已移到 `.agent/historical-backup/docs/` 本地备份，不再作为公开文档入口。`AGENTS.md` 是 agent/维护者专用操作约定；本地 agent 草稿、报告、worktree、参考 clone、历史备份与 agent 文档放入 `.agent/`，该目录不追踪。
- 完成用户要求的修改后，请用脚本构建一个 importer 版本 APK 便于测试：

```bash
tools/package/build_importer_apk.sh
```

- 寻找原版代码和其他关键参考内容时，请从全局配置里读取信息： .env 和 local.properties

## 1. 项目定位

Android 侧拆成三层维护：

1. **Android shell / launcher / 附加设置**
   - APK 默认进入 `GameSettingsActivity`，不是直接进入游戏。
   - 负责首次向导、本地 PC 游戏 zip 导入、Steam 登录/游戏下载、本地存档快照、Steam Cloud 与 WebDAV 云存档、私有目录管理、游戏版本/兼容包管理、启动 Godot Activity、日志/文件浏览、存档备份、MOD 管理。
2. **原版游戏 payload**
   - 用户本地提供 `SlayTheSpire2.zip`，或使用自己拥有 STS2 的 Steam 账号从 SteamPipe 下载。
   - 导入/下载后安装到 `<files>/payloads/<payload_id>/game/`；版本/配置切换只切换 launch profile 指针，不再复制完整 PCK/解压目录。
   - “版本”页可为同一个 payload 创建多个 `<files>/instances/<profile_id>/instance.json` 启动配置，并分别选择兼容包、存档/设置、MOD 使用全局目录或隔离目录；删除游戏本体或兼容包不会删除启动配置，启动时再提示缺失项。
   - 直装版构建时可临时内置 zip 到 APK assets，但构建脚本退出会清理，不能提交。
3. **Android 兼容包 / Harmony patcher**
   - `port-mod/STS2AndroidPortCompat` 编译输出 `STS2Mobile.dll`。
   - `port-mod/overlay` 打包输出 `port_compat.pck`。
   - legacy schema 1 兼容包 zip 形态为：`compat_manifest.json` + `STS2Mobile.dll` + `port_compat.pck` + `SHA256SUMS`。
   - flat schema 2 family 包 zip 形态为：`compat_manifest.json` + `variants/<target_id>/STS2Mobile.dll` + `variants/<target_id>/port_compat.pck` + `SHA256SUMS`；启动配置用 `compat_pack_id` + `compat_target_id` 指向具体 variant。
   - 兼容包不是普通用户 MOD；它由 launcher/Godot runtime 在游戏早期加载，用来 patch 原版 PC 程序集并让普通 MOD 系统在 Android 上工作。

## 2. 当前支持版本矩阵

`port-mod` 当前默认跟踪 `main`，并采用 flat matrix 打包模式：不按游戏版本切开发分支，而是从当前 checkout 的 `targets/active/*/target.json` 循环编译多个 target，并输出一个 schema 2 family 包。`compat/*` 分支只作为 legacy 发布包对照、回退诊断或历史维护入口；legacy 打包模式会通过临时 worktree 同时构建多个 schema 1 内置兼容包，仅在显式设置 `COMPAT_PACK_BUILD_MODE=legacy` 时使用。

| 通道 | 游戏版本 | Steam 分支 | 原版/解包引用配置 | legacy submodule 分支 | compile gate `ReferenceFlavor` | flat target id | legacy 兼容包 id |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 正式/稳定 | `v0.103.2` / `v0.103.3` | `public` | `.env`: `STS2_ORIGINAL_V103_REFERENCE_DIR` 或 `STS2_ORIGINAL_V103_ROOT` | `compat/v0.103.2` | `original` | `v0.103.x` | `sts2-android-compat-v0.103.x` |
| Beta 旧测试 | `v0.106.1` | `public-beta` | `.env`: `STS2_ORIGINAL_V1061_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1061_ROOT` | `compat/v0.106.1-beta` | `original-v0.106.1` | `v0.106.1-beta` | `sts2-android-compat-v0.106.1-beta` |
| Beta 旧测试 | `v0.107.0` | `public-beta` | `.env`: `STS2_ORIGINAL_V1070_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1070_ROOT` | `compat/v0.107.0-beta` | `original-v0.107.0` | `v0.107.0-beta` | `sts2-android-compat-v0.107.0-beta` |
| 正式/稳定 | `v0.107.1` | `public` | `.env`: `STS2_ORIGINAL_V1071_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1071_ROOT` | — | `original-v0.107.1` | `v0.107.1` | — |
| 正式/稳定 | `v0.108.0` | `public-beta` | `.env`: `STS2_ORIGINAL_V1080_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1080_ROOT` | — | `original-v0.108.0` | `v0.108.0` | — |

关键文件：

- `.gitmodules`：`port-mod` submodule GitHub URL 与默认 branch（`main`）。
- `tools/android/bundled-compat-packs.json`：legacy 内置兼容包列表，当前包含 `compat/v0.103.2`、`compat/v0.106.1-beta` 与 `compat/v0.107.0-beta`。
- `port-mod/targets/active/*/target.json`：flat matrix target 描述，记录 target id、支持版本、Steam 分支 `steam_branch`、`ReferenceFlavor`、compile constants、原版引用来源与 dll sha。
- `port-mod/tools/build-compat-matrix.sh`：从当前 checkout 构建 schema 2 family 兼容包；`tools/android/stage-bundled-compat-packs.sh` 默认会调用它。
- `.env.example`：工具链、runtime 参考、original compile gate 引用、签名环境变量示例；复制为 `.env` 后编辑，本文件不入 git。
- `local.properties.example`：非环境变量的本地构建选项示例；复制为 `local.properties` 后编辑，本文件不入 git。
- `tools/env/load-local-config.sh`：所有 bash 构建脚本共用的 `.env` / `local.properties` loader。
- `port-mod/refs/original*/`：仅保留 README 占位；构建脚本不再依赖提交到仓库的个人 symlink。
- `port-mod/compat_manifest.*.json`：legacy schema 1 兼容包 manifest，主要供旧发布包或诊断包使用；默认 matrix 包以 `targets/active/*/target.json` 生成 schema 2 manifest。

注意：启动器按 payload manifest 的 `sts2_dll_sha256` 与 `release_info.version` 为新建启动配置自动推荐/填写兼容包；schema 1 优先匹配 `target_game.version`，也支持 manifest 中的 `target_game.supported_versions` / `compatible_versions` / `versions` 列表；schema 2 会展开 `targets[]`，优先按 dll sha 匹配具体 target，再回落到版本匹配；同等命中时优先推荐 schema 2 family 包。兼容包选择以 `<files>/instances/<profile_id>/instance.json` 中的 `compat_pack_id` 为准，schema 2 还会记录 `compat_target_id`，不再使用全局选中包作为运行时 fallback；从 legacy 内置包升级到 flat family 包时，启动器安装 bundled compat pack 后会把旧 `sts2-android-compat-v0.*` 启动配置自动迁移到 `sts2-android-compat` + 对应 `compat_target_id`，但不会覆盖用户手动选择的非 bundled 包。若当前启动配置绑定的兼容包/target 缺失或与 payload 版本不一致，会在启动前提示用户编辑启动配置或承担风险继续。当前不会仅因 `sts2.dll` SHA-256 不一致硬阻止启动，但 manifest 中仍记录 SHA 供诊断和精确匹配升级使用。

## 3. 本地配置 / 参考输入

构建脚本不再写死某个 workspace 的相邻目录。首次配置：

```bash
cp .env.example .env
cp local.properties.example local.properties
```

`.env` 统一保存机器相关环境变量：

- `JAVA_HOME`、`ANDROID_HOME`/`ANDROID_SDK_ROOT`、`DOTNET_BIN`。
- `STS2_ANDROID_RUNTIME_REFERENCE_ROOT`：参考 Android template/runtime，包含 `libs/`、`assets/dotnet_bcl/`、Gradle wrapper jar。
- `STS2_FMOD_PLUGIN_AAR`、`STS2_CRYPTO_NATIVE_JAR`。
- `STS2_ORIGINAL_V103_REFERENCE_DIR` / `STS2_ORIGINAL_V1061_REFERENCE_DIR` / `STS2_ORIGINAL_V1070_REFERENCE_DIR` / `STS2_ORIGINAL_V1071_REFERENCE_DIR` / `STS2_ORIGINAL_V1080_REFERENCE_DIR`（或对应 `*_ROOT`）：original compile gate 引用目录，需包含 `sts2.dll`、`GodotSharp.dll`、`0Harmony.dll`。
- `RELEASE_KEYSTORE_*`、可选 `STS2_PAYLOAD_ZIP`、可选 `STS2_EXTERNAL_PROJECTS_ROOT`。

`local.properties` 保存非 secret 的本地构建选项，例如 Gradle task、dist 输出路径、compat pack staging 目录、默认 `ReferenceFlavor`、外部 GitHub 参考项目 clone 目录。完整说明见 `doc/build/local-configuration.md`。

不要提交用户游戏 zip、original/reference DLL、完整 runtime、keystore 或 `.env` / `local.properties`。

## 4. 当前目录结构

```text
s2_re/
  AGENTS.md                        # 本文件，给后续 agent/维护者的操作约定；不是用户手册
  README.md                        # 面向普通开发者/测试者的入口说明
  LICENSE                          # 本仓库原创代码 MIT License
  THIRD_PARTY_LICENSES.md          # 第三方来源/许可证摘要与发布前合规检查
  android/                         # Android shell / Godot Android Gradle 工程根目录
    AndroidManifest.xml            # Activity/provider/权限；GameSettingsActivity 是默认 launcher
    build.gradle                   # Godot Android template 风格应用模块配置
    config.gradle                  # AGP/Kotlin/SDK/NDK/Java 版本与 Godot export property helpers
    gradle.properties              # applicationId、ABI、签名、构建类型等本地属性
    settings.gradle                # pluginManagement + install-time asset pack
    assetPackInstallTime/          # install-time asset pack 占位
    src/com/godot/game/            # Java/Kotlin shell、附加设置、payload/版本/兼容包管理、Steam 中心、GodotApp 桥
    steam-protocol/                # Steam CM/auth/content protobuf 协议子模块
    steam-content/                 # SteamPipe depot manifest/chunk 下载子模块
    res/                           # 附加设置/崩溃页/文件浏览器/图标/shortcut/theme 等 Android 资源
    assets/
      bootstrap.pck                # 无游戏 payload 时的最小 Godot bootstrap pack
      port_compat.pck              # legacy fallback overlay pack，脚本生成
      compat_packs/                # 构建时生成的内置兼容包 zip assets，gitignore
      # res/drawable/ic_ms_*.xml    # Material Symbols Rounded 字体离线生成的官方轮廓 vector drawable
      dotnet_bcl/                  # 大型 .NET/Godot runtime DLL，同步生成，gitignore
      payload/                     # 直装版临时内置 zip，gitignore
    libs/                          # Godot/FMOD/template AAR，同步生成，gitignore
  port-mod/                        # git submodule: ModinMobileSTS/sts2-android-compat，兼容补丁仓库
    compat_manifest.*.json         # 当前分支的兼容包 manifest
    STS2AndroidPortCompat/         # 兼容插件源码，输出 STS2Mobile.dll
      STS2Mobile.csproj            # runtime 期望的程序集名：STS2Mobile.dll
      ModEntry.cs                  # unmanaged entrypoints: InitializeGodotSharp / Apply
      Patches/                     # 平台、设置、输入、MOD、LAN、shader、生命周期等 Harmony patch
      Android/                     # Android settings/path bridge
    overlay/                       # 打包进 port_compat.pck 的 shader/resource overlay
    refs/                          # 可选本地 original compile gate 占位说明；脚本优先用 .env 的引用目录
    targets/active/*/target.json   # flat matrix 目标版本描述；移到 archived 后默认不再内置
    tools/build-compat-pack.sh     # 导出独立可安装 compat pack zip
    tools/build-compat-matrix.sh   # 单 checkout 构建 schema 2 family compat pack
  tools/
    port_mod_ast_audit.py          # 游戏版本更新后对比两版 C# 语法结构，并把变化映射到 port-mod Harmony/反射触达点
    android/
      env-from-s2.sh               # 兼容旧名称：source 后加载 .env 中的 JDK/Android SDK
      gradle-with-s2-env.sh        # 在 android/ 下带本机环境执行 Gradle
      sync-runtime-from-references.sh # 同步 Godot/FMOD/dotnet_bcl 等大型运行时产物
      build-port-mod.sh            # 编译当前 submodule checkout 并 stage legacy fallback dll/pck
      stage-bundled-compat-packs.sh # 默认构建 flat family 包；COMPAT_PACK_BUILD_MODE=legacy 时跑 legacy 多分支构建
      bundled-compat-packs.json    # legacy 内置兼容包分支列表
      make-bootstrap-pck.py        # 生成最小 bootstrap.pck
      make-port-overlay-pck.py     # 从 port-mod/overlay 生成 legacy fallback port_compat.pck
      generate-material-symbol-vectors.py # 从 Material Symbols Rounded TTF 生成 Android vector drawable
      fmod-shim/                   # 替换 FMOD Java class 的 shim 源码
    package/
      validate_payload_zip.py      # 校验 PC 游戏 zip 必需文件/PCK magic/hash
      build_android_body_zip.py    # 用匹配源码重新导入 Android 资源 PCK，并保留 PC 原版 DLL 组装优化本体 zip
      build_android_body_zip.sh    # 上述 Python 工具的环境加载 wrapper
      build_importer_apk.sh        # 构建不内置游戏 zip 的导入版 APK
      build_direct_apk.sh          # 构建临时内置游戏 zip 的直装版 APK
    debug/
      sts2-adb-debug.sh            # ADB 自动化调试：安装、推送 payload/compat/MOD、准备/启动、日志/Perfetto 采集
    diff/                          # 差异清单工具
    deps/                          # GitHub 外部参考项目清单与自动准备脚本
    git/report-heads.sh            # 输出父仓库与 submodule HEAD/branch/upstream 状态
  doc/                             # 公开项目文档入口；新增/修改用户可见说明时同步维护
    README.md                      # 文档索引与维护规则
    architecture/                  # 项目结构、目录职责、版本模型
    build/                         # 构建/打包/发布流程
    runtime/                       # 启动、兼容包、MOD 加载流程
    modding/                       # 普通 MOD 与兼容包开发维护说明
    plan/                          # 长期设计计划或已落地方案 checklist
  dist/                            # APK/兼容包输出副本，本地生成，gitignore
  .agent/                          # agent 草稿/报告/临时 worktree/参考 clone/agent-docs/历史备份，gitignore，不追踪
    debug/runs/                    # ADB 自动化调试结果、logcat、Perfetto trace、本地拉回诊断，gitignore
    agent-docs/changelog/          # agent-only changelog，本地接力用，不提交
    historical-backup/docs/        # 旧 docs/ 历史 diff/validation 本地备份，不提交
```

## 5. 文档规范

长期公开项目文档统一放入 `doc/`，agent 本地接力文档放入 ignored 的 `.agent/agent-docs/`：

- `doc/README.md`：项目文档索引、维护规则。
- `.agent/agent-docs/README.md`：本地 agent 文档索引，不提交。
- `.agent/agent-docs/changelog/`：每次修改新增一条 `YYYY-MM-DD-简短主题.md`，记录背景、改动、验证、注意事项；这是 agent-only changelog，不提交到公开仓库。
- `.agent/historical-backup/docs/`：旧 `docs/` 历史 diff/validation 资料本地备份，不提交。
- `doc/architecture/project-structure.md`：目录职责、运行时私有目录、版本/兼容包模型。
- `doc/build/building-and-packaging.md`：构建环境、脚本流程、常用命令、产物位置。
- `doc/runtime/compat-pack-loading-flow.md`：移动端兼容包与普通 MOD 的详细加载流程。
- `doc/modding/mod-and-compat-notes.md`：普通 MOD 目录、启停协议、兼容补丁开发注意事项。

维护要求：

1. 改动构建脚本、目录结构、版本矩阵、兼容包流程时，必须同步 `AGENTS.md` 和对应 `doc/` 页面。
2. 每次可见行为变化或维护规则变化都要新增 `.agent/agent-docs/changelog/` agent changelog；不要再新增 `doc/changelog/`，也不要把 changelog 提交到公开仓库。
3. `README.md` / `doc/` 是公开项目文档；`AGENTS.md` 是编码代理/维护者专用操作约定；`.agent/agent-docs/` 是本地 agent 文档，`.agent/` 其余内容是本地 scratch/历史备份，均不入库。一次性 context/review/scout 记录放 `.agent/`，长期计划才整理进 `doc/plan/`。
4. 旧 `docs/` 目录已搬到 `.agent/historical-backup/docs/`，不再公开追踪；需要公开沉淀的历史资料应整理成 `doc/` 下的长期文档。
5. 文档中不要写入用户私有 zip hash/路径之外的敏感信息，不要复制商业游戏资源内容。
6. 新增直接引用资源、改编/参考第三方仓库实现或新增依赖时，同步 `THIRD_PARTY_LICENSES.md`，并在 `README.md` 写明用户可见来源。

## 6. Android shell 关键点

- Java package 保持 `com.godot.game`，便于兼容旧 C# / patched runtime 桥；实际 `applicationId` 由 `android/gradle.properties` 设置为 `com.megacrit.sts2re`。
- `GameSettingsActivity` 是默认 `LAUNCHER`：首次进入欢迎向导/附加设置页；设置页的“桌面图标启动后”偏好可让桌面图标在向导完成且 payload 就绪后自动走 `launchGame()` 直接进游戏，默认仍打开附加设置。
- 主要页面/管理器：
  - `WelcomeSetupPage`：首次向导。
  - `GamePage` / `SettingsPage` / `ModsPage` / `GameVersionManagerPage`：主页、设置、MOD、版本/兼容包管理；启动器图标统一使用 `tools/android/generate-material-symbol-vectors.py` 从 bundled Material Symbols Rounded 字体（`android/res/font/material_symbols_rounded.ttf`）离线生成的官方轮廓 vector drawable（`android/res/drawable/ic_ms_*.xml`），运行时由 `MaterialSymbols` helper 按 glyph 名或旧 `R.drawable.ic_*` 映射加载，避免依赖系统字体 ligature；手机启动器继续锁竖屏，平板/大屏启动器 Activity 使用系统方向；launcher/工具页统一 `SystemBarInsetsHelper.enableEdgeToEdge()`（`decorFitsSystemWindows=false`）并按 scaffold 分区直接消费 `WindowInsetsCompat` 的 `systemBars|displayCutout`（顶栏 top、底栏 bottom、rail top+bottom、内容左右/底；输入页可用 `applySystemBarPaddingWithIme`），**不要**用几何 overlap 测量或 `status_bar_height` dimen；工具页顶栏优先布局内 `MaterialToolbar`/自定义 View，不再依赖 window Support ActionBar + `action_bar_container` 内部 id；横屏时主 shell 从底部导航切换为左侧 Navigation Rail，页面内容通过 `ExtraSettingsUi` 的响应式最大宽度容器居中，首页使用 hero/状态工具双栏，设置/关于页卡片可两列排列，MOD/版本/Steam/Nexus/WebDAV 页面至少保持居中限宽；`GamePage` 按 `propotype_mainpage.html` 的 MD3 深色首页原型实现：顶部 STS2 标题 + Steam 登录/云存档 chip，ready 状态使用动态渐变/光晕 hero 启动卡，未导入状态使用虚线空状态卡，MOD/存档状态卡带 150% 淡色背景大图标、按压缩放与水印放大回正微动效，维护/高级工具为 4 列快捷按钮并保留 Android ripple，其中主页高级工具入口打开“启动配置”而不是全局兼容包选择；`SettingsPage` 内部使用“画面 / 操作 / 存档 / 系统”顶部 Segmented Button 分区，并把下拉类设置改为 Bottom Sheet 单选列表，预加载详细 BottomSheet 刚打开时可通过内容区上滑完整展开，完整展开后内容滚动区不参与降下/关闭，只能下拉顶部手柄关闭；画面高级项里的“旋转模式”写入 `android_screen_rotation_mode`，默认 `user_landscape`（跟随系统横屏锁定状态旋转），也可选为 `auto`（自动旋转强转，通过重力感应忽略系统锁定在正反横屏中切换），或固定 `landscape` 与 `reverse_landscape`；首次/默认推荐图形配置为 OpenGL ES、关闭 MSAA、关闭垂直同步；旧 `android_flip_screen_180` 仅作为兼容布尔字段同步维护。`ModsPage` 顶部是 MOD 总开关、药丸搜索框和可横向滚动 Chip 操作组；Nexus 商店入口当前在 MOD 页隐藏，排序/筛选/MOD 方案入口位于 Chip 组；MOD 卡片默认折叠，展开后显示完整描述、可点击跳转文件浏览器的清单路径、作者、依赖和“选中/备注/信息/删除”图标按钮；支持本地备注显示名（备注为主标题、原名为灰色小字）；自动探查 `dependencies` / `min_game_version` / `has_pck`·`has_dll` 文件与 `settings.save` 中原版 `ModSettings.ModList` 平面手工顺序的依赖顺序问题，问题 MOD 黄色高亮，AppBar 标题后黄色感叹号+数量可打开问题 BottomSheet，顺序问题可一键按原版 `ModManager.SortModList` 规则重写平面 `mod_list`；MOD 分组只影响启动器展示/目录，不参与运行时加载顺序；支持前置库/内容模组/用户新建分组，长按左侧手柄拖拽到分组时会震动并显示半透明虚线 ghost 占位。
  - `SteamWorkshopActivity`：Steam 创意工坊页面；由 MOD 页“创意工坊”chip 打开，按 `.agent/proptype/proptype_steam.html` 的 MD3 结构实现列表、详情、已下载和设置四屏，侧栏提供“热门 / 最新发布 / 最近更新 / 最多订阅”排序（默认热门）与“本周 / 30 天 / 3 个月 / 6 个月 / 一年 / 全部”时间筛选（默认本周），侧栏内容可滚动，侧栏顶部显示 Steam 中心登录账号/SteamID64 或匿名状态，并保留应用深色配色；未登录时通过 Steam Community 公开 Workshop 页面匿名浏览塔2公开条目并用 published file details 补全大小/更新时间，已登录时优先复用 Steam 中心保存的 refresh token/SteamID64 走 Steam CM 查询，缺少 SteamID64 时会先验证 refresh token 补齐，失败则回落到公开浏览；列表预览图、详情截图和前置 MOD 来自真实 Steam 公开页面/API，不使用占位数据，列表底部不显示翻页按钮，靠近底部时自动加载下一页并追加条目；图片加载会优先使用详情页原图并在兼容访问、原始域名和强制兼容访问之间重试；默认开启“创意工坊兼容访问”，对 `steamcommunity.com`、常见 Steam 图片媒体域和 `api.steampowered.com` 使用 WorkshopAndroidDownloader 同款 `steamcommunity.rmbgame.net` / `steamstore.rmbgame.net` 转发路径，并按参考项目允许 SteamPipe 动态 HTTP CDN endpoint，解决部分网络下 Steam Community/API 与 UGC manifest/chunk 下载直连超时或被 Android 明文策略拦截；下载条目通过现有 MOD staging 导入当前 launch profile 的 MOD 根目录；创意工坊设置页提供“下载分支”：默认 `auto`，自动优先使用 Steam 下载 payload 时记录的 `source.steam.branch`，其次使用当前启动配置兼容包 manifest target 上的 `steam_branch`，两者都没有时才在下载前询问；也可固定为 `public`、`public-beta`、自定义分支或“每次询问”。下载器会先用 `PublishedFile.GetItemInfo#1` 探测 author snapshots；若该接口只返回顶层 manifest 而没有 author snapshots，则继续用 `PublishedFile.GetChangeHistory#1` 从 saved snapshot 历史中提取 branch min/max 与 manifest；固定分支或 `auto` 已能推断分支时会直接进入后台下载，manifest/depot/request code 在下载任务内部解析，避免一键队列被 UI 级分支解析串行阻塞；设置为“每次询问”或自动无法推断时才弹出分支/manifest 候选 Dialog，列表项展示 branch、manifest、depot、snapshot 时间、branch min/max、解析来源与 fallback 原因，用户选择后才开始下载；选择的 branch 会传给 `ContentServerDirectory.GetManifestRequestCode#1`；当 Steam 只暴露默认 manifest 而没有分支快照时，Dialog/自动解析会额外派生目标分支的“按分支请求默认 manifest”候选并明确标注 fallback 原因；若 CM snapshot、change history 和默认 manifest 都不可用则回退公开 WebAPI `hcontent_file` / `file_url` 候选；最终以设置中的导入分组（默认 `workshop`）下 `<branch>/<published_file_id>/` 作为单个 Workshop item 的安装边界；下载前会解析 Steam `RequiredItems` 并用 `<files>/workshop/library/index.json` + 对应 item 目录内真实存在的 MOD manifest 判断前置是否已覆盖，缺失时弹出前置列表并可一键按队列下载前置和当前条目；下载器会在无账号时尝试匿名 Steam 会话/公开 CDN 回退，部分受限条目仍可能需要登录；下载支持后台任务，列表/详情下载按钮点击后立即切换为圆形进度环和居中的方形停止按钮，已下载且当前版本按钮显示“详细信息”，点击会打开对应 item 目录内的本地 MOD 详情；后台下载线程使用低优先级，直链和 UGC 路径都会合并进度事件，UGC 分块下载默认并发 2（设置页可调 1-8）以降低下载期间 UI 卡顿；导入成功后立即静默删除原始下载 staging，启动器/创意工坊页每天最多一次静默清理残留 `<files>/workshop/downloads/` 条目；若只有下载记录但 item 目录已找不到本地 MOD，下载按钮显示“重新下载”，已下载页卡片显示本地文件已删除状态；下载页分为“下载中 / 已下载”，检查更新位于已下载页 AppBar 右上角；下载后在 `<files>/workshop/library/index.json` 按 `published_file_id@workshop_branch` 记录 PublishedFileId、分支、解析来源、resolved manifest、匹配 branch min/max、远端更新时间、导入 MOD ID、item 根目录、大小和内容 SHA-1 摘要，用于手动/自动更新检查；更新任务会固定沿用已安装记录的分支，导入完成后直接覆盖旧项并清理同一条目的 legacy 索引记录，避免分支迁移前的旧记录继续提示更新；已下载页可删除单条下载记录，并可勾选同时删除对应 `published_file_id` item 目录；设置屏搬入 Steam 状态、已下载列表入口、导入分组、兼容访问开关与 UGC 分块并发设置。实现参考 `.agent/reference-repos/workshop-android-downloader` / <https://github.com/Apricityx/WorkshopAndroidDownloader>。
  - `NexusModsStoreActivity`：实验性 NexusMods 商店 Activity 仍保留但 `ModsPage` 入口暂时隐藏；用户手动保存 Personal API Key 后可浏览热门/最新/近期更新结果、按 URL/数字 ID 精确查询、下载 ZIP 并导入到当前 launch profile 的 MOD 目录（全局 `<files>/mods/` 或隔离 `<files>/instances/<id>/mods/`）；下载导入与本地导入共用同 ID 冲突和路径覆盖确认流程。非 Premium 下载若被 NexusMods API 拒绝，可引导用户打开网页并粘贴 NXM 链接中的 `key/expires`。
  - `SteamAccountActivity`：Steam 中心；首次打开会显示带动态倒计时、5 秒后才能关闭的账号安全提示（本地保存 refresh token、可信来源、未知 MOD 风险、云存档备份、国内可能需要加速器），页面底部常驻“安全说明”按钮可再次查看；完成账号密码登录、Steam Guard、refresh token 加密保存、SteamPipe 下载 STS2 payload 到 payload store，以及当前 launch profile account root 的 Steam Cloud 手动拉取/上传和可选自动同步设置。
  - `WebDavCloudActivity`：WebDAV 云存档中心；保存 WebDAV URL、用户名、密码/应用令牌与可选远端槽位到加密偏好，只同步当前 launch profile account root 的白名单 STS2 存档文件，远端目录为用户配置 base URL 下的 `SlayTheSpire2/saves/<slot>/`，并用 `.sts2re/manifest.json` 记录 SHA-1 manifest；支持测试连接、刷新清单、拉取、上传、强制上传、启动前拉取和干净退出后上传。
  - `LocalSaveSnapshotManager`：本地存档快照管理；启动前创建 `before-launch`，游戏干净退出回设置后创建 `clean-exit`，恢复快照前创建 `before-restore`，默认保留当前 launch profile 最近 5 个 zip 快照；设置页“存档”分区可立即创建和恢复历史快照。
  - `PayloadManager`：导入/校验/安装 PC 游戏 zip 或 SteamPipe 下载目录到 payload store。
  - `LaunchProfileManager`：维护 `<files>/payloads/<payload_id>/game/` 与 `<files>/instances/<profile_id>/instance.json`，支持同一游戏本体创建多个全局/隔离存档和 MOD 的启动配置，并把 `compat_pack_id` 作为每个配置自己的选择；schema 2 family 兼容包还会保存 `compat_target_id`。从旧 schema 1 bundled 包升级到 flat matrix 内置包时会按旧 pack id / payload 匹配迁移到 `sts2-android-compat` family target。切换配置不复制 PCK。游戏本体缺失时配置仍保留且可选择/编辑，启动时提示用户重新导入、下载或重新绑定。
  - `GameBodyVersionManager`：legacy facade，版本选择委托给 `LaunchProfileManager`，不再执行 active/归档目录复制。
  - `CompatPackManager`：安装、导入、删除兼容包；从 APK assets 安装内置兼容包；支持 schema 1 单目标包和 schema 2 family 包的 `targets[]` variant；按 payload manifest 的 dll sha/version 匹配目标版本供新建/编辑启动配置使用，schema 2 family 包优先于旧 schema 1 包。安装 bundled 包后会触发旧 bundled pack id 到 flat family target 的启动配置迁移。兼容包页不再提供全局“选中”动作，删除兼容包也不静默替换各启动配置的 `compat_pack_id` / `compat_target_id`。
  - `GameLaunchPreparationManager`：启动前后台准备 Mono publish 目录、兼容包 dll、overlay pck、payload assembly 和纹理缓存清理。
  - `DebugAutomationActivity` / `DebugAutomationReceiver`：ADB 自动化调试入口；host 侧 `tools/debug/sts2-adb-debug.sh` 用 `run-as` 写入 `<files>/automation/token.txt` 后，通过 exported 调试 Activity 触发状态查询、配置修改、payload/compat/MOD 私有 inbox 导入、启动准备、启动游戏和日志/性能采集。Receiver 仅作为兼容广播入口转交 Activity；长任务不要直接放在 BroadcastReceiver 生命周期内。
- `GodotApp`：真正的 Godot 游戏 Activity。
- `GodotApp` 启动行为：
  - 首次向导未完成时会重定向回 `GameSettingsActivity`。
  - `getCommandLine()` 加 renderer/display/log 参数，并固定追加 `--force-steam off` 作为原版 Steam 初始化跳过兜底；日志等级由附加设置 `log_level`（默认 `info`，可选 `off` / `debug` / `very_debug`）转为 STS2 `-log <LogType> <LogLevel>` 命令行，覆盖 Generic/Network/Actions/GameSync/VisualSync；选择 `off` 时不配置 `godot.log` 且不追加 STS2 `-log` 参数；有当前 launch profile payload 的 `SlayTheSpire2.pck` 时传 `--main-pack <files>/payloads/<payload_id>/game/SlayTheSpire2.pck`，否则使用 `assets/bootstrap.pck`。设置页“系统”分区的 `android_performance_overlay_enabled` 默认关闭；开启后 `GodotApp` 写入 `<files>/launcher/enable_debug_menu.flag`，compat 层从 overlay 加载 `godot-debug-menu` 详细性能面板。
  - APK manifest 默认声明 `android:appCategory="game"` / `android:isGame="true"`，让 OEM 游戏/GPU 调度识别 Godot 游戏 Activity；`GodotApp` manifest 默认 `sensorLandscape`，并在 `onCreate` / `onResume` / Godot 主循环开始后按 `android_screen_rotation_mode` 原生调用 `setRequestedOrientation()`：`user_landscape` 为跟随系统横屏，`auto` 为 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` 并额外启用重力计强转，`landscape` 为普通横屏，`reverse_landscape` 为反向横屏；`HighRefreshRateController` 由设置页“系统”分区预加载下方的 `android_high_refresh_rate_enabled` 控制，默认开启时在 `onCreate` / `onResume` / 获得焦点 / Godot 主循环开始后请求当前显示尺寸下最高 display mode，并向 Godot render `SurfaceView` 发起 `Surface.setFrameRate()` / `SurfaceControl.Transaction.setFrameRate()`。
  - 暴露 `launchGameSettingsFromGame()`、`restartToSettingsFromGame()`、`getGodotDataDir()`、`getSelectedGameDir()`、`getSelectedAccountRootDir()`、`getSelectedModsDir()`、`getSelectedLaunchContextJson()`、`getSelectedCompatPackDir()`、`getSelectedCompatOverlayPck()` 等静态桥给 C# 兼容层。
  - 维护当前 profile 的 `logs/godot.log` 与 `logs/android-launch.log`；应用内 logcat 统一采集到全局 `<files>/logs/sts2.log`，每次启动游戏时像 `godot.log` 一样归档旧 `sts2.log`。`sts2.log` 使用紧凑 `level tag message` 格式并遵循附加设置 `log_level`（off/info/debug/very_debug）；选择 `off` 时完全禁用 `godot.log` 与 `sts2.log`。`sts2.log` 只能抓到普通 app 可见的自身 UID/进程相关 logcat，完整设备级日志仍需 ADB。
- 启动路径：
  1. `GameSettingsActivity.launchGame()` 检查当前启动配置绑定的 payload 是否 ready；配置存在但本体缺失时不 fallback 到旧 `<files>/game/`，而是提示用户重新导入、下载、切换或编辑启动配置。
  2. 如果兼容包开关关闭，启动前弹出风险确认；用户选择继续后，准备流程会删除 staged `STS2Mobile.dll` 与 `<files>/port_compat.pck`，真正按无兼容层路径启动。若用户选择开启，则写回 `android_compat_pack_enabled=true` 后取消本次启动。
  3. 如果兼容包开关启用，只读取当前启动配置的 `compat_pack_id` / `compat_target_id`；无包/target 已删除则阻止启动并提示编辑启动配置，版本不匹配则弹窗提示。
  4. 后台执行 `GameLaunchPreparationManager.prepareForLaunch()`。
  5. 以 `launch_prepared=true` 启动 `GodotApp`；`GodotApp` 仍保留 fallback 准备路径防止直接启动遗漏，但同样尊重兼容包开关，关闭时不会 fallback 复制 `STS2Mobile.dll`。

## 7. Payload / 版本管理

`PayloadManager` 负责本地游戏 zip 导入：

- 支持 SAF 选择 zip 与 assets 内置 `payload/SlayTheSpire2.zip`。
- zip 必需文件：
  - `SlayTheSpire2.pck`
  - `release_info.json`
  - `data_sts2_windows_x86_64/sts2.dll`
  - `data_sts2_windows_x86_64/sts2.deps.json`
  - `data_sts2_windows_x86_64/sts2.runtimeconfig.json`
- 导入流程：复制到私有临时文件并计算 sha256 → 安全解压到 staging → 校验 PCK magic 与必需文件 → 对私有 PCK copy 做 length-preserving Sentry metadata patch → 写 `.payload_manifest.json` → 按 version/commit/hash 生成 payload id → 原子安装到 `<files>/payloads/<payload_id>/game/`。
- 安全措施：Zip Slip canonical path 防护、单一顶层目录 payload zip 自动展平、backup/rollback、取消控制、旧 scratch 清理。
- 导入成功后会尝试：
  - `LaunchProfileManager.createOrSelectDefaultProfileForPayload()`：创建/选择绑定该 payload 的启动配置；默认配置使用全局存档和全局 MOD，并在创建时按版本填入推荐兼容包；用户可在“版本”页新建/编辑配置来改兼容包或改为隔离配置。
  - `CompatPackManager.findBestMatch()`：按 dll sha/version 为新建/编辑启动配置提供推荐兼容包或 schema 2 target variant；启动时不会再覆盖已有配置的 `compat_pack_id` / `compat_target_id`。
  - 旧 `<files>/game/` 与 `<files>/game-versions/<id>/game/` 会在启动器 bootstrap 时尽量通过 rename 迁移到 payload store，避免大文件复制。

应用私有目录约定：

```text
<files>/payloads/<payload_id>/game/         # 不可变导入游戏 payload
<files>/payloads/<payload_id>/game/.payload_manifest.json # 导入 manifest，含 release_info / dll sha / pck patch 记录
<files>/instances/<profile_id>/instance.json # 启动配置，绑定 payload/compat/save/mod 模式；schema 2 可含 compat_target_id
<files>/instances/<profile_id>/default/<account>/settings.save # 隔离存档/设置目录
<files>/instances/<profile_id>/mods/        # 隔离普通用户 MOD 目录
<files>/instances/<profile_id>/logs/        # profile 日志目录：godot.log / android-launch.log
<files>/steam/downloads/                    # SteamPipe 下载 staging / 任务诊断
<files>/workshop/downloads/                 # Steam Workshop 下载 staging / metadata / download.log；导入成功或每日维护会静默清理
<files>/workshop/library/index.json         # 已导入 Workshop item 的 PublishedFileId / 分支 / manifest 解析来源 / item 根目录 / 更新时间 / MOD ID 记录
<files>/steam/cloud/<profile_id>/           # Steam Cloud manifest、baseline、备份与诊断
<files>/webdav/cloud/<slot>/                # WebDAV manifest、baseline、备份与诊断
<files>/automation/                         # ADB 自动化调试 token、inbox、runs/result；本地测试数据
<files>/save-snapshots/profiles/<profile_id>/ # 本地存档快照 zip，默认保留最近 5 个
<files>/compat-packs/<pack_id>/             # 已安装 Android 兼容包；schema 2 在 variants/<target_id>/ 下放 dll/pck
<files>/launcher/selected_instance.json     # 当前启动配置解析结果
<files>/launcher/selected_game_version.json # legacy 兼容诊断记录，指向当前 payload
<files>/launcher/selected_compat_pack.json  # 当前启动配置解析出的兼容包诊断记录
<files>/default/<account>/settings.save     # 全局存档/设置目录，默认 account=1
<files>/mods/                              # 全局普通用户 MOD 目录
<files>/.godot/mono/publish/arm64/          # Godot/Mono publish 目录
<files>/port_compat.pck                    # 启动前 staging 的当前兼容包 overlay
<files>/logs/                              # legacy/global 日志 fallback 与统一应用内 logcat：sts2.log
```

## 8. 兼容包 / port-mod submodule

### 8.1 submodule main、target matrix 与工作方式

`port-mod/` 是 submodule，不要把它当父仓库普通目录直接混合提交。常用检查：

```bash
git submodule status
git -C port-mod status --short --branch
git -C port-mod branch -a
tools/git/report-heads.sh
```

切换或更新时注意：

- 修改兼容层源码时默认在 `port-mod/main` 上工作；确认当前 checkout 是 `main`，不要把普通共用功能继续落到某个 `compat/*` 版本分支。
- 兼容层改动要在 submodule 仓库内提交，再在父仓库更新 submodule 指针。
- 默认 `tools/android/stage-bundled-compat-packs.sh` 不切分支，直接从 `port-mod/main` 的 active target matrix 构建 schema 2 family 包。
- 仅在 `COMPAT_PACK_BUILD_MODE=legacy` 时，stage 脚本才会为非当前 legacy 分支创建临时 worktree 到 `.agent/worktrees/compat-packs/`，避免旧分支补丁互相污染；当前 checkout 若正好是某个 legacy 分支且有未提交改动，脚本会用 dirty worktree 构建对应 legacy 包，便于本地诊断。
- flat matrix 模式下，通用源码改动只在当前 checkout 上维护；版本差异优先放入 `port-mod/targets/active/<target_id>/target.json`、target capabilities/adapter 或极少量条件编译，不再为普通共用功能复制到多个分支。停止内置某个早期版本时，把对应 target 移到 `targets/archived/`，默认 matrix 构建会跳过它。

### 8.2 构建入口

- 当前 checkout legacy fallback 构建：

```bash
tools/android/build-port-mod.sh
```

默认 `REFERENCE_FLAVOR=original-v0.108.0`，适合快速验证当前稳定版 target；验证其他 target 时显式覆盖为对应 flavor。脚本会：

1. 使用 `.env` 中的 `DOTNET_BIN` 编译 `port-mod/STS2AndroidPortCompat/STS2Mobile.csproj`，并按 `ReferenceFlavor` 传入对应 `CompatReferenceDir`。
2. 写入 build metadata（branch/commit/dirty/timestamp）。
3. 复制输出到 `android/assets/dotnet_bcl/STS2Mobile.dll` 作为 fallback。
4. 运行 `tools/android/make-port-overlay-pck.py` 生成 `android/assets/port_compat.pck`。

- 构建当前 checkout 的 schema 1 独立兼容包（legacy/诊断用）：

```bash
cd port-mod
./tools/build-compat-pack.sh
```

- 构建全部内置兼容包并复制到 APK assets：

```bash
tools/android/stage-bundled-compat-packs.sh
```

默认 flat matrix 模式读取 `port-mod/targets/active/*/target.json`，从当前 checkout 输出 gitignored 的 `android/assets/compat_packs/sts2-android-compat.zip` schema 2 family 包：

```bash
tools/android/stage-bundled-compat-packs.sh
```

会调用 `port-mod/tools/build-compat-matrix.sh`，从当前 checkout 的 `targets/active/*/target.json` 依次用对应 `ReferenceFlavor` 编译，并输出一个 schema 2 `sts2-android-compat.zip` family 包。APK 启动时 `CompatPackManager.installBundledCompatPacks()` 会把打入 assets 的这些 zip 安装到 `<files>/compat-packs/`。

legacy 分支模式只在需要对照旧发布包或回退诊断时使用：

```bash
COMPAT_PACK_BUILD_MODE=legacy tools/android/stage-bundled-compat-packs.sh
```

可在 submodule 内单独调试：

```bash
cd port-mod
./tools/build-compat-matrix.sh --target v0.108.0
./tools/build-compat-matrix.sh
```

### 8.3 compile gate

检查是否误依赖旧 Android port 改过的 `sts2.dll`，请使用对应原版引用：

```bash
# v0.103.x 正式/稳定
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh

# v0.106.1 beta（旧测试）
REFERENCE_FLAVOR=original-v0.106.1 tools/android/build-port-mod.sh

# v0.108.0 正式/稳定（当前稳定版）
REFERENCE_FLAVOR=original-v0.108.0 tools/android/build-port-mod.sh

# v0.107.1 正式/稳定
REFERENCE_FLAVOR=original-v0.107.1 tools/android/build-port-mod.sh

# v0.107.0 beta（旧测试）
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh

# 或裸跑 dotnet 时显式传入 .env 中配置的引用目录
"$DOTNET_BIN" build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.108.0 \
  -p:CompatReferenceDir="$STS2_ORIGINAL_V1080_REFERENCE_DIR" -v:q
```

`ReferenceFlavor=runtime`（默认 MSBuild 属性）引用旧 launcher runtime，适合快速编译；正式兼容分支应通过对应 original gate。

### 8.4 runtime 加载概要

正常启动前，Java shell 会：

1. 复制 APK `dotnet_bcl` runtime 到 `<files>/.godot/mono/publish/arm64/`。
2. 兼容包开关开启时按当前启动配置的 `compat_pack_id` / `compat_target_id` 复制 `STS2Mobile.dll` 到 publish 目录；关闭兼容包开关时删除该 dll，且 `GodotApp` fallback 不会再从 selected pack 或 APK asset 强制补回。
3. 兼容包开关开启时复制当前启动配置兼容包/target 的 `port_compat.pck` 到 `<files>/port_compat.pck`；关闭时删除 `<files>/port_compat.pck`。无选择时使用 `android/assets/port_compat.pck` fallback（正常 launcher 启动会先阻止缺包场景）。
4. 复制当前 launch profile payload 目录 `<files>/payloads/<payload_id>/game/data_*/*` 到 publish 目录，但保护 BCL/System/GodotSharp 等 runtime DLL 不被 payload 覆盖；profile/payload 切换时会清理旧游戏 assembly 残留。
5. patched Godot runtime 加载 `STS2Mobile.dll` / `STS2Mobile.ModEntry`，调用 `InitializeGodotSharp` 与 `Apply`；`Apply` 会先配置 Android 私有 temp，并通过 `HarmonyAndroidCompat` 在真正的 `MonoMod.Utils` / `MonoMod.Core` 程序集上强制 Android/Mono 后端，避免 HarmonyOS 等 ROM 被 MonoMod 误判为 Posix/Linux 后在 Harmony `UpdateWrapper` 中抛 `NotImplementedException`。默认路径贴近 `../s2` 的 minimal bootstrap，不启用旧 native resolver / `DMDType=cecil` override；`monomod_android_libc_shim` 仍由 AndroidSystem 按需用于指令缓存刷新和 `/proc/self/mem` executable-page patch fallback。`HarmonyMethodReferenceImporterShim` 会在后续大量 Harmony patch 前自检 `MMReflectionImporter` 是否丢失 STS2 方法引用上的 required/optional custom modifiers，必要时仅对带 modifiers 的 `sts2` 方法导入安装极窄 postfix 原地修正，避免普通 MOD patch 原方法体时生成无法绑定的动态 `MemberRef`。`EarlyLocalizationFallbackPatches` 会在普通 MOD 加载前保护 `LocString.GetFormattedText()`：Android/Mono 若在 MOD `PatchAll` 阶段提前运行游戏 UI 类型静态构造、且 `LocManager.Initialize()` 尚未执行，只临时返回稳定 fallback 文本，初始化完成后停止吞异常，避免 `NPotionHolder` 这类类型被永久标记为 cctor 失败。`DeferredModPatchQueue` 会在普通 MOD initializer / `PatchAll` 窗口拦截 `PatchProcessor.Patch()`，把目标为 `sts2` 程序集 Godot/UI 类型（如 `MegaCrit.Sts2.Core.Nodes.*`、`MegaCrit.Sts2.addons.*`，且存在静态初始化器）的用户 MOD patch 排队，等 `ExecuteEssential` 中 `LocManager.Initialize()`、`ModelDb.Init()`、`ModelIdSerializationCache.Init()`、`ModelDb.InitIds()` 以及原版网络 `MessageTypes.Initialize()` / `ActionTypes.Initialize()` 完成后再按原顺序重放，避免 Android/Mono 在 very-early 阶段 patch 这些 UI 类型时提前执行 `.cctor`；Android 接管 `ExecuteEssential` 时不能漏掉网络类型表初始化，否则原版单人战斗结束写 `CombatReplay` 也会因 `INetAction` 无法映射 ID 而失败。若 MOD 因 Android 早期原版模型占位误判 ModelDb 已初始化并提前调用 `AbstractModel.InitId()`，兼容层只在 `ModelIdSerializationCache.Init()` 前跳过该早调用，后续 `ModelDb.InitIds()` 会统一完成排序 ID 初始化。早期初始化不得调用 Godot C# API；Harmony self-test 仅在 `<files>/launcher/enable_harmony_selftest.flag` 存在时运行，旧 bootstrap 仅在 `<files>/launcher/enable_old_harmony_compat_bootstrap.flag` 存在时作为诊断启用。
6. `ModEntry.Apply()` 先独立保护并应用保命 patch：`PlatformPatches` 跳过桌面 Steam 初始化、`SavePathPatches` 把原版 `UserDataPathProvider` 重定向到当前 launch profile 的 account root；即使后续诊断或 UI/性能 patch 在特定 ROM 上失败，也不能阻断这两组核心 patch。随后分组应用 BaseLib/RitsuLib、ModelDb/UnlockState、release/settings/display/layout/input、shader、LAN、ModLoader 等；每组单独捕获异常并写入 `[STS2Mobile]` 日志。`AppPaths` 从 publish 目录或 Android 进程包名推导 `<files>` 并读取 `<files>/launcher/selected_instance.json`（避免兼容层早期初始化调用 Godot API/Java bridge）。`DisplaySettingsPatches` 读取 `android_screen_rotation_mode`：`auto` 映射 Godot `SensorLandscape`，`user_landscape` 由 Java `GodotApp` 的 `SCREEN_ORIENTATION_USER_LANDSCAPE` 托管且不再调用 Godot `ScreenSetOrientation()` 覆盖，`landscape` 映射普通横屏，`reverse_landscape` 映射 180° 横屏；未写新字段时用旧 `android_flip_screen_180` fallback。Java `GodotApp` 同步读取同一字段并原生设置 Android Activity 方向，防止 manifest/Activity 层固定横屏吞掉 180° 横屏传感器切换；并且在选择 `auto` 强转模式时启用重力计做强转强制翻转（可在 `onPause` 安全注销），跟随系统时则退还管理权给原生锁定机制。`RenderDiagnosticPatches` 后置且仅作诊断，不得阻断核心 patch。`ShaderCompatibilityPatches` 只替换已知高风险桌面特效 shader；不要再替换 `res://shaders/blur/canvas_group_mask_blur.gdshader`，该 shader 用于卡面/先古卡遮罩，旧移动替代版会导致先古卡面纯白且不应随 overlay/兼容包发布。
7. `ModLoaderPatches` 接管原版 `ModManager.Initialize()`（`v0.107.0` 起原方法返回 `Task`，Android replacement prefix 跳过原方法时必须返回 `Task.CompletedTask`；`v0.107.1` 起原版用 `ModManager.State` 取代旧 `_initialized`，兼容层需反射写入 `Initialized` 并保留旧字段 fallback），扫描当前 launch profile 的 `AppPaths.ModsDir`（全局 `<files>/mods` 或隔离 `<files>/instances/<profile_id>/mods`），跳过 Steam Workshop，并处理 `mod_manifest.json` → `<ModId>.json` manifest alias；**加载任何 MOD 之前先预注册仅原版模型占位**（`AbstractModelSubtypes.All`），避免 Android/Mono 下 MOD initializer Harmony patch 某个 getter（如 HextechRunes patch `UnlockState.Relics`）时提前触发 `UnlockState..cctor -> ACT.OVERGROWTH`、或 MOD 静态构造引用原版模型时崩溃；原版类型不带命名空间前缀，提前算 ID 不会污染 YuWanCard/BaseLib 的 `GetEntry` 前缀缓存。**不对 MOD 模型类型提前算 ID**，MOD 占位延迟到 phase 1；若 MOD 因早期占位误判 ModelDb 已初始化而过早调用 `AbstractModel.InitId()`，`ModelDbInitPatch` 会在序列化 cache 就绪前跳过该次调用，避免 `EVENT` 等 category 尚无 net ID 时崩溃。调用每个 MOD 的 `TryLoadMod` initializer 期间会短暂开启 `ModelDb.Contains(Type)` shield：只对非原版程序集类型隐藏“早期原版占位”导致的重复命中，避免 RitsuLib/Valencina 这类 MOD 在注册前构造与原版同名模型（如 `Taunt`）时因 Android 占位和 PC 时序差异误报 `DuplicateModelException`；同一窗口也会开启 `DeferredModPatchQueue` 的用户 MOD patch 捕获，只延后 STS2 Godot/UI 类型 patch，不延后模型类 patch，保证 MOD 的 `ModelDb.Init` 前置 hook 仍按 PC 时序生效；原版类型、MOD phase 1 后和真实重复检查仍保持可见。
8. `QuickRestartPatches` 在 pause menu 提供 Android 内置“重打/Retry”按钮；快速重开会先等待当前 run save 任务，淡出后执行原版保存恢复入口（`RunManager.SetUpSavedSinglePlayer()`，`v0.107.0` 为 `SetUpSavedSingleplayer()`；返回 `Task` 的版本会等待完成）以完整初始化新 run 的 `NetService` / `MapSelectionSynchronizer` 等同步器后再调用 `NGame.LoadRun()`，并在淡出后失败时尝试 `FadeIn()` 恢复可见画面，避免关闭/跳过运行时预加载时因 async 时序竞态卡黑屏。
9. `MobileTooltipPatches` 通过 `NHoverTipSet.CreateAndShow/Remove/Clear/_Process`、owner `GuiInput` 和 `NGame._Input` 管理移动端 tooltip 显示；附加设置“设置 → 操作 → Tooltip 显示”默认 `mobile_tooltip_mode=immediate` 保持 PC 端悬停即显示，也可切换为 `long_press`（同一触点按住约 1 秒后临时显示，松手/明显拖动后隐藏）或 `hidden`。该补丁在 `CreateAndShow*` 前建立长按计时，允许原版 tooltip 创建并完成对齐后再隐藏；若原版在长按过程中频繁 `Clear()`/重建 hover tips，会保留当前 owner/计时状态，避免计时被每帧重置；游戏内设置页切换到 hidden/long_press 会立即移除已有普通 hover tooltip。inspect card/relic/potion 等显式详情页面不受隐藏策略影响。
10. `LanMultiplayerPatches` 由 `lan_multiplayer_enabled`（附加设置“设置 → 系统 → 本地联机补丁”）作为主开关；关闭后会跳过 STS2Mobile 自带的所有本地 LAN patch（固定消息 ID、无 Steam LAN join/host、最大人数可见性、多人读档 ID 修正等）。补丁延迟到主菜单后应用，若检测到普通 MOD 中已加载 `sts2_lan_connect` / STS2 Game Lobby 大厅 MOD，也会自动整组跳过，避免 Android 旧 LAN 兼容层和大厅 MOD 的 `legacy_4p` / `extended_8p` 协议 profile 或消息序列化补丁叠加冲突。启用本地 LAN patch 时，兼容层会把原版 `RunSaveManager.LoadAndCanonicalizeMultiplayerRunSave()` 的本地玩家 ID 与 `current_run_mp.save` 内的 `Players[].NetId` 对齐：优先使用当前 ID，其次隐藏稳定字段 `lan_multiplayer_save_player_id`、旧自动 LAN peer ID 或单玩家存档中的唯一 `NetId`，避免用户修改自定义平台/玩家 ID 后旧多人存档被误判为不属于本机；`lan_multiplayer_save_player_id` 是 Android-only settings key，需保留在 merge 列表中。
11. `LifecycleAndPerformancePatches` 会在 `NMainMenu._Ready` 后启动安全 deferred preload，并在需要细分或额外 warmup 时接管原版 `LoadCommonAndMainMenuAssets()`；`CombatAnimationWarmupPatches` 会在当前 `NCombatRoom._Ready` 后按需对玩家/怪物 Spine 动画先走原版 `SetAnimationTrigger()` 真实触发路径，再短帧采样 clip。总开关 `preload_enabled` 默认开启；Android 附加设置页顶部“系统”分区中它只作为总开关显示，右侧箭头打开预加载详细管理 BottomSheet，总开关开/关不改写细分项目；`preload_startup_common_enabled=true`、`preload_startup_main_menu_enabled=true`、`preload_runtime_enabled=true` 保持旧版默认资源加载；`preload_protect_warm_cache_enabled=true` 默认保护已预热缓存，compat 层会过滤原版 `AssetCache.UnloadAssets()` / `UnloadMissedCacheAssets()` 对 Android warm cache 的卸载；`preload_learned_assets_enabled=true` 默认把实战 miss 写到 `<files>/launcher/preload-learned-assets.json` 并在后续启动加入预热；`preload_menu_hotspots_enabled=false`、`preload_vfx_mode=off`、`preload_vfx_tree_warmup_enabled=false`、`preload_vfx_tree_warmup_scope=safe`、`preload_vfx_retain_cache_enabled=false`、`preload_combat_animation_warmup_mode=off`、`preload_combat_hit_effect_warmup_enabled=false`、`preload_combat_code_enabled=false`、`preload_shader_mode=off`、`preload_gameplay_assets_enabled=false` 默认为关闭，避免默认行为比旧版更重。高级开关可分别控制 CommonAssets、MainMenuSet、常用菜单实例化、VFX 场景资源 warmup、VFX 实际进树跑帧范围、VFX 缓存保留、当前战斗房间 Spine 动画 trigger/clip 预热、战斗命中特效/受击音效预热、战斗代码 warmup、已知 shader 资源加载、run/act/room 预加载、缓存保护、实战资源补全包与漏载学习，BottomSheet 的“恢复默认”只重置这些细分项目，不修改 `preload_enabled`。VFX 实际进树 warmup 的 `safe` 范围只跑安全名单，当前包含高频战斗 VFX 与猎人小刀相关 `vfx_shiv_throw` / dagger VFX；`all` 范围会逐个尝试让 `res://scenes/vfx/**/*.tscn` 全部进树跑帧，单项失败会记录并跳过，用于卸载重装后尽量填充 Godot shader cache，但不会自动执行卡牌/怪物战斗逻辑；小刀/匕首类场景会至少跑 12 帧，以覆盖 `_Ready()` 后首批粒子和约 0.15 秒后的 impact 粒子。战斗动画预热支持 `off` / `safe` / `all`：`safe` 只对当前房间实际出场的玩家/怪物触发并采样攻击、施法、受击、猎人小刀等安全动画，且恢复原动画和 `CreatureAnimator` 当前状态；`all` 会在跳过死亡、复活、逃跑、睡眠/醒来等危险 trigger 的前提下尽量走真实 trigger，并枚举当前房间所有 Spine clip，覆盖更广但更重，仅建议高内存诊断。动画预热期间会给当前战斗房间加临时全屏遮罩，背后的角色/怪物仍实际绘制以触发 GPU/Spine 热身，但不暴露采样动作。战斗命中特效预热开启后，会在同一遮罩后实例化真实伤害数字、命中火花、斩击/钝击 VFX，并以 0 音量触发当前怪物和常见敌人受击 FMOD 事件来加载 sample data，不改血量、出牌或战斗历史；完成日志会输出 `hit_effects` / `hit_audio`。摘要日志按 `resource_only` / `tree_warmed` / `tree_ineligible` / `tree_failed` 区分，并在 VFX warmup 完成时输出 `<files>/shader_cache` 前后文件数/字节数；隐藏诊断字段仅保留 `preload_debug_enabled`，显式开启后才会输出逐资源 miss 分类、phase enter/leave 和逐动画明细。Godot 渲染侧 shader 编译缓存会持久写到 `<files>/shader_cache/**.cache`，跨进程/设备重启保留；compat protected warm cache 只是内存中的 `PreloadManager.Cache` 保护，不跨进程。`tools/debug/sts2-adb-debug.sh --preload aggressive` / `tree_warmup` 会额外开启 `preload_gameplay_assets_enabled`、`preload_vfx_tree_warmup_enabled`、`preload_vfx_retain_cache_enabled`、`preload_combat_animation_warmup_mode=safe`、`preload_combat_hit_effect_warmup_enabled`；`--preload vfx_full_tree` 会启用全 VFX 场景进树探测；`--preload animation_full` 会同时启用全 VFX 场景进树探测和当前房间全 Spine clip 采样，用于“尽量全热完”的高内存诊断。需要最高细节日志时，用自动化 `--settings-json '{"preload_debug_enabled":true}'` 显式打开隐藏诊断。
12. `TransitionMaterialPatches` 会复制 `NTransition` 使用的 fade/fight `ShaderMaterial`，避免关闭预加载时原版 missed-cache 清理 dispose 掉仍在 `FadeIn()` 中使用的共享 transition 材质，从而黑屏。`v0.107.0-beta`、`v0.107.1` 与 `v0.108.0` target 另有 `MapDrawingSceneCachePatches`，让地图画笔线条从 Android 自持有的 `PackedScene` 实例化，避免 v107 原版/兼容层 preload cleanup 释放 `NMapDrawings` 缓存的 `map_line_draw` / `map_line_erase` 场景后，画笔在 `CreateLineForPlayer()` 中抛 `ObjectDisposedException: Godot.PackedScene`。
13. `ModelDbInitPatch` 分三个阶段处理模型占位：
   - **早期原版占位**（加载 MOD 前，由 `ModLoaderPatches` 触发）：仅原版，解决 MOD patch getter / MOD 静态构造引用原版模型的早访问；占位 id 会记录 owner type，供 MOD initializer shield 判断“命中的是早期原版占位还是自身真实重复”。
   - **MOD initializer shield**（每个 `TryLoadMod` 调用期间）：`ModelDb.Contains(Type)` 对非原版程序集类型、且当前 id 只命中早期原版占位时返回 `false`，还原 PC 上 MOD 初始化早于 `ModelDb.Init` 的行为；该 shield 不隐藏原版类型、不隐藏同一 type 的重复，也不在 phase 1/phase 2 后生效。
   - **phase 1**（`ExecuteEssential` 中、`ModelDb.Init()` 调用**之前**）：MOD patch 已全部应用，按最终 `ModelDb.GetId(Type)` 补齐全部模型（含 MOD 自定义类型）占位；解决 MOD 间静态构造引用（如 `wuwancients.HiddenSeaRecord..cctor -> RELIC.LONG_SNAKE_NECKLACE`），这些构造会在 MOD 的 `ModelDb.Init` prefix 期间被 Android/Mono 提前触发。
   - **phase 2**（`InitPrefix` 中，`Priority.Last`）：在占位上原地运行真实静态/实例构造器，并跳过原版 one-pass body。因部分 MOD 的 `ModelDb.Init` prefix 会自己返回 `false` 并让 Harmony 跳过后续 prefix，兼容层还安装 `Priority.First` postfix 与 `ExecuteEssential` 后置兜底，确保构造 phase 一定执行。自定义模型 ID（含 `ENCOUNTER.YUWANCARD-KILLER_ELITE` 等带前缀 ID）完全由原版 `ModelDb.Init` + MOD `GetEntry` patch 自然产生，不再人为迁移 key。用户 MOD 的 `ModelDb.Init` prefix/postfix 生命周期保留。
   - `UnlockStateCompatPatches` 在 `ModelDb` 初始化完成前让 `ModelDb.AllEncounters` 返回空列表，避免 Android/Mono 因 Harmony patch getter 提前运行 `UnlockState..cctor` 时枚举到尚未构造/注册完成的 MOD encounter；初始化完成后会修复可能提前创建的 static readonly `UnlockState.all`。

上述 MOD 初始化时序、本地 LAN patch 自动跳过大厅 MOD、预加载/tooltip 设置协议、shader 兼容排除卡面 `canvas_group_mask_blur`、快速重开 async 时序修复是 `v0.103.x`、`v0.106.1-beta`、`v0.107.0-beta`、`v0.107.1` 与 `v0.108.0` target 都应保持的相同不变式；flat matrix 模式下跨版本热修需通过所有 active target compile gate。legacy 分支模式仍在用时，跨版本热修还需同步到 `tools/android/stage-bundled-compat-packs.sh` 的 worktree 注入列表。只在特定游戏版本复现的修复应通过 target capability/条件逻辑限制，避免无条件影响其他 target。详细流程见 `doc/runtime/compat-pack-loading-flow.md`。

### 8.5 MOD 兼容性排查规范

排查普通 MOD 在 Android 上无法加载、依赖缺失、初始化顺序异常或行为与 PC 不一致时，遵循以下约定：

- 可以把常用前置/依赖 MOD 仓库 clone 到工作区外或 `.agent/reference-repos/` 等不提交的位置，并 checkout 到与目标游戏版本、目标 MOD 版本匹配的 tag/branch/commit 后对照排查；不要把这些第三方源码或构建产物提交到本仓库。
- 优先参考对应版本 PC 原版/解包代码，尤其是 `ModManager`、依赖排序、manifest 解析、assembly resolve、资源加载和初始化回调的时序；重点确认 Android 兼容层是否漏掉某一步、提前/延后某一步，或改变了原版加载顺序导致 MOD 兼容问题。
- 对没有公开源码的 MOD，可以通过反编译其程序集获取可参考信息，用于定位入口类、manifest、依赖声明、Harmony patch、资源路径和初始化假设；反编译结果只作为本地诊断依据，不要提交第三方反编译源码或违反其许可条款。
- 常见前置/依赖仓库：
  - RitsuLib: <https://github.com/BAKAOLC/STS2-RitsuLib>
  - BaseLib-StS2: <https://github.com/Alchyr/BaseLib-StS2>

## 9. 构建 / 打包环境

### 9.1 Android/Gradle 版本

来自 `android/config.gradle` / `android/gradle.properties`：

- Android Gradle Plugin：`8.6.1`
- Gradle wrapper：`8.13`
- Kotlin plugin：`2.1.20`
- Steam 相关 Gradle 子模块：`android/steam-protocol`、`android/steam-content`；主要依赖 JavaSteam `1.6.0`、OkHttp `5.3.2`、protobuf `4.31.1`、AndroidX Security Crypto、Android Prefab zstd（Steam VZstd chunk native 解压）、XZ。
- compileSdk / targetSdk：`35`
- minSdk：`24`
- buildTools：`35.0.0`
- NDK：`28.1.13356709`
- CMake：`3.22.1`（用于 `libworkshop_zstd.so` JNI wrapper；Gradle 可按 SDK license 自动安装到 `.env` 配置的 Android SDK）
- Java source/target：`17`
- flavor：`mono`
- 默认 build type：`release`（脚本执行 `assembleMonoRelease`）
- ABI：`arm64-v8a`
- applicationId：`com.megacrit.sts2re`
- versionName/versionCode：`v0.1.5` / `6`
- 默认测试签名：由 `.env` 的 `RELEASE_KEYSTORE_*` 或 `local.properties` 的 `android.release_keystore_*` 提供；示例使用 `${HOME}/.android/debug.keystore`。

注意：`release` build type 当前仍保留 `debuggable true`，便于 sideload 后使用 `run-as` 验证；正式发布前必须重新审视签名、debuggable、混淆、资源优化、FileProvider 暴露范围。

本仓库构建使用 `.env` 中配置的 JDK/Android SDK。容器系统自带 Java 可能只是 JRE，不能直接编译 Java；请使用：

```bash
tools/android/gradle-with-s2-env.sh <gradle-task>
```

或先：

```bash
source tools/android/env-from-s2.sh
```

### 9.2 运行时二进制同步

`android/assets/dotnet_bcl/`、`android/libs/`、`android/gradle/wrapper/gradle-wrapper.jar` 等大型/生成产物不应手写维护，使用：

```bash
tools/android/sync-runtime-from-references.sh
```

同步内容包括：Godot template AAR/native libs、`.NET/Godot` BCL/runtime DLL、crypto native jar、FMOD AAR（带 FMOD Java shim patch）、Gradle wrapper jar。

### 9.3 导入版 APK

导入版不内置游戏 zip，用户安装后在附加设置中选择本地 `SlayTheSpire2.zip`。

```bash
tools/package/build_importer_apk.sh
```

正式 APK 默认声明 Android 游戏分类标记，并启用高刷新兼容路径：`GodotApp` 生命周期中按 `android_high_refresh_rate_enabled` 请求最高可用刷新率；设置页“系统”分区在预加载下方提供该开关，默认开启。设置页同一分区提供默认关闭的性能 overlay 开关，开启后下次启动加载 `godot-debug-menu` 详细面板（FPS、帧时间、CPU/GPU frame graph、硬件/渲染器信息）。该 overlay 源自 `godot-extended-libraries/godot-debug-menu`，MIT license，实际打包文件位于 `port-mod/overlay/addons/debug_menu/`。

脚本流程：

1. `tools/android/sync-runtime-from-references.sh`
2. `tools/android/build-port-mod.sh`
3. `tools/android/stage-bundled-compat-packs.sh`
4. `tools/android/gradle-with-s2-env.sh assembleMonoRelease`（默认使用 debug keystore 参数签 release build）
5. 复制输出：
   - Gradle 产物：`android/build/outputs/apk/mono/release/sts2-re.apk`
   - 稳定副本：`dist/sts2-re-importer.apk`

### 9.4 直装版 APK

直装版在构建时临时把本地 PC zip 复制到 `android/assets/payload/SlayTheSpire2.zip`，启动器会按内置 zip 的 SHA-256 判断当前 APK 自带本体是否已导入；首次安装或从旧直装版升级且只导入过旧内置本体时，会自动解压/安装当前内置 zip 到 payload store `<files>/payloads/<payload_id>/game/` 并创建/选择 launch profile。zip 复制有 trap 清理，不提交。

```bash
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-direct.apk
```

### 9.5 常用检查命令

```bash
# payload zip 校验
tools/package/validate_payload_zip.py "/path/to/SlayTheSpire2.zip"

# 可选：用匹配 Godot 源码/反导出工程重新导入 Android 资源 PCK，生成优化本体 zip（DLL 仍来自 PC zip 原版）
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/SlayTheSpire2.zip" \
  --source-dir "/path/to/sts2-godot-source" \
  --out "dist/payload/sts2-vX.Y.Z-android-body.zip"
# 该脚本会在临时工程合成缺失 `.uid` sidecar，并同时 patch `project.godot` / `project.binary` 的 Sentry autoload；还会从源工程 `.godot/imported` 注入 Spine `.spatlas` / `.spskel` 与 `.atlas.import` / `.skel.import` remap 并强制校验，避免导出的 PCK 因缺 Spine 导入产物导致主菜单/战斗黑屏。不要去掉这些步骤，否则重导出的 PCK 可能出现首帧 native crash 或资源黑屏。

# 只编译 Java/Gradle 检查
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac

# 只构建当前兼容 MOD fallback
tools/android/build-port-mod.sh

# 构建全部内置兼容包（默认 flat schema 2 family 包）
tools/android/stage-bundled-compat-packs.sh

# legacy 分支模式，仅用于回退诊断
COMPAT_PACK_BUILD_MODE=legacy tools/android/stage-bundled-compat-packs.sh

# 游戏版本更新后，先对比旧/新 GDRE C# 源码并映射 port-mod 触达点
# summary.md / port_mod_refs.csv / member_changes.csv 输出到 .agent/reports/，不提交。
tools/port_mod_ast_audit.py \
  --old-source ../s2_original/s201071 \
  --new-source ../s2_original/s201080 \
  --port-mod port-mod/STS2AndroidPortCompat \
  --out .agent/reports/v108-port-mod-ast-audit

# 刷新 Android 启动器 Material Symbols 官方轮廓 vector drawable
# 需要 Python 包 fontTools；若系统 Python 禁止全局安装，可用 .agent/ 下的临时 venv。
python3 -m pip install --user fonttools
tools/android/generate-material-symbol-vectors.py
tools/android/generate-material-symbol-vectors.py --check
```

## 10. Git / 产物注意事项

- `.gitignore` 已排除：
  - `dist/`、`*.apk`、`*.aab`、`*.apks`
  - `.env`、`local.properties`
  - `.agent/`、`.pi/`
  - `local-inputs/`、用户 `*.zip`、keystore/jks/p12
  - `android/assets/compat_packs/*.zip`
  - `android/.gradle/`、`android/**/build/`
  - `android/assets/dotnet_bcl/`
  - `android/libs/`
  - `android/assets/payload/`
  - .NET `bin/` / `obj/`
- `android/assets/compat_packs/*.zip` 是脚本生成的内置兼容包 assets，随 APK 打包但不再由 git 跟踪；需要刷新时运行 `tools/android/stage-bundled-compat-packs.sh` 或完整打包脚本，提交前不要 `git add -f`。
- 不要提交用户 payload zip、original/reference DLL、完整 runtime、keystore、compat pack zip 或任何商业游戏资源；本机路径只写入 `.env` / `local.properties`。
- 修改 `port-mod/overlay` 后需要重新生成 `port_compat.pck`，并重新导出/复制内置兼容包。
- 修改 `tools/android/make-bootstrap-pck.py` 后需要重新生成 `android/assets/bootstrap.pck`。
- 修改启动器图标 glyph 映射或新增 `MaterialSymbols.drawable(..., "glyph", ...)` 字符串图标后，需要运行 `tools/android/generate-material-symbol-vectors.py` 刷新 `android/res/drawable/ic_ms_*.xml`，并用 `tools/android/generate-material-symbol-vectors.py --check` 校验；脚本依赖 Python `fontTools`，可装在 ignored 的 `.agent/` venv 中。不要恢复运行时 icon font ligature 渲染，否则 MIUI 关闭优化等 ROM 字体路径可能显示原始 glyph 名称。
- 修改 Java bridge 包名/类名要谨慎：C# helper 和 patched runtime 默认找 `com.godot.game.GodotApp`。
- flat matrix 模式下，共用兼容层热修只维护在当前 checkout，必须通过 `port-mod/tools/build-compat-matrix.sh` 的所有 active target compile gate；只在某个游戏版本复现的问题优先放入 target adapter/capabilities 或极少量条件编译。legacy 分支模式仍在用的共用热修，需要同步更新 `tools/android/stage-bundled-compat-packs.sh` 的 worktree 注入列表，确保 `v0.103.x`、`v0.106.1` 与 `v0.107.0` legacy 内置包都得到同一修复。
- 改 `applicationId` 时同步 shortcuts、FileProvider、manifest、Gradle 配置与所有 hard-coded target package。

仓库 / 子模块 HEAD 巡检：

```bash
tools/git/report-heads.sh
# 如需刷新远端引用：
tools/git/report-heads.sh --fetch
```

该脚本只读取/可选 fetch Git 信息，不修改工作区文件；适合提交前排查 `port-mod` 分支、父仓库记录的 submodule commit、dirty/upstream ahead-behind 状态。

## 11. 常用验证路径

本地构建：

```bash
tools/package/build_importer_apk.sh
# 或
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

连接 ADB 设备后的自动化验证：

```bash
# 构建 + 安装 + 写入 app 私有 automation token
tools/debug/sts2-adb-debug.sh build-install

# 查询 launcher/profile/payload/compat/MOD 状态并拉回结果到 .agent/debug/runs/<run_id>/
tools/debug/sts2-adb-debug.sh status --pull

# 只验证启动准备路径，适合 compat dll/overlay/publish/preload 排查
tools/debug/sts2-adb-debug.sh prepare --mode compat --clear publish --pull

# 启动游戏并采集 logcat / Perfetto
tools/debug/sts2-adb-debug.sh launch --mode perf --preload aggressive --logcat-duration 45 --perfetto 45 --pull
```

脚本会把本地 payload/compat/MOD 文件推送到设备 app 私有 `files/automation/inbox/<run_id>/`，再复用应用内正常导入/配置/准备逻辑。调试结果只放在 ignored 的 `.agent/debug/runs/` 和设备 `<files>/automation/`，不要提交。

安装后建议检查：

```bash
adb install -r dist/sts2-re-importer.apk
adb shell run-as com.megacrit.sts2re ls files
adb shell run-as com.megacrit.sts2re ls files/compat-packs
adb shell run-as com.megacrit.sts2re ls files/payloads
adb shell run-as com.megacrit.sts2re ls files/instances
adb shell run-as com.megacrit.sts2re cat files/launcher/selected_instance.json
adb shell run-as com.megacrit.sts2re ls files/.godot/mono/publish/arm64
```

重点 smoke test：

1. 首次打开进入欢迎向导/附加设置，而不是直接进游戏。
2. “版本”页能安装/显示内置兼容包，至少包含正式 `v0.103.x`、`v0.107.1` 与 `v0.108.0` 对应包（当前仍可内置旧 beta `v0.106.1` / `v0.107.0`）。
3. 导入版选择 PC zip 或 Steam 下载后，`files/payloads/<payload_id>/game/.payload_manifest.json` 存在，`files/payloads/<payload_id>/game/SlayTheSpire2.pck` 存在，并创建/选择 `files/instances/<profile_id>/instance.json`；切换版本不应复制回 `files/game/`。Steam 下载来源应在 manifest 中记录 `source.kind=steam_depot`。
4. 新建启动配置时按 payload 版本填入匹配兼容包；之后兼容包只能通过新建/编辑启动配置修改，不再有全局选中包。删除 payload 或 compat pack 后，相关启动配置仍保留并在列表中显示缺失，启动前弹提示。
5. 点击启动后 logcat / 当前 profile 的 `files/instances/<profile_id>/logs/android-launch.log` 能看到 selected compatibility pack 和 `Loading imported game PCK`；全局 `files/logs/sts2.log` 应包含应用内采集到的 Android logcat（如 `Sts2Re` / `GODOT` / `[STS2Mobile]`）。
6. `files/.godot/mono/publish/arm64/STS2Mobile.dll` 来自当前选择的兼容包；`files/port_compat.pck` 已 staging。
7. 修改图形/输入/MOD 设置后，当前 profile 解析出的 settings（全局 `files/default/1/settings.save` 或隔离 `files/instances/<profile_id>/default/1/settings.save`）有对应字段；新安装/新建隔离档案首次生成的默认图形设置应为 `msaa=0`、`vsync=off`；画面高级里的旋转模式默认写入 `android_screen_rotation_mode=user_landscape`，选择“自动”时写入 `auto`，选择“不旋转”/“180°”时分别写入 `landscape` / `reverse_landscape` 并同步旧 `android_flip_screen_180` 布尔值。
8. 从游戏内打开附加设置、退出回设置、crash/log/file browser 页面不崩溃。
9. MOD master switch / 单 MOD disable 能在启动日志或游戏内 MOD 状态中反映；普通 MOD 从当前 profile 的 MOD 目录扫描（全局 `files/mods` 或隔离 `files/instances/<profile_id>/mods`），不走 Steam Workshop。
10. `v0.108.0` payload 应使用 `sts2-android-compat` / `v0.108.0`，`v0.107.1` payload 应使用 `sts2-android-compat` / `v0.107.1`，旧 beta `v0.107.0` payload 应使用 `sts2-android-compat` / `v0.107.0-beta`，正式 `v0.103.2` / `v0.103.3` payload 应使用 `sts2-android-compat` / `v0.103.x`；从旧 APK 升级后，原 `sts2-android-compat-v0.107.0-beta`、`sts2-android-compat-v0.106.1-beta`、`sts2-android-compat-v0.103.x` 等 bundled schema 1 选择应自动迁移为 family pack + target。
11. Steam 中心可登录/验证 refresh token；Steam Cloud 手动刷新/拉取/上传使用当前 launch profile 的 account root，拉取前在 `files/steam/cloud/<profile_id>/backups/` 创建备份。WebDAV 中心可配置 URL/用户名/密码/槽位、测试连接，并把同一 account root 的白名单存档同步到远端 `SlayTheSpire2/saves/<slot>/`；拉取前在 `files/webdav/cloud/<slot>/backups/` 创建备份。本地存档快照在 `files/save-snapshots/profiles/<profile_id>/` 默认保留最近 5 个，启动前/干净退出后会自动创建，设置页可手动创建和恢复。

## 12. 维护提醒

- 当前工程是“Android shell + payload/version manager + compat pack”的组合，不是传统 Android Studio `app/` 子模块结构；Gradle 根就在 `android/`。
- 实际打包推荐用 `tools/package/*.sh`，不要裸跑 Gradle，除非已同步 runtime、准备好环境并理解 compat pack staging。
- 可公开 clone 的 GitHub 参考项目用 `tools/deps/prepare-external-projects.sh` 准备；清单在 `tools/deps/external-github-projects.json`。默认参考仓库包含 `SlayTheAmethystModded`、`WorkshopAndroidDownloader` 与 `StS2-Launcher_Mod_Manager`；该脚本不下载商业 payload、original DLL、keystore 或准备好的 Godot/Mono runtime。
- `settings.save` 的 Android-only key 是 Java 附加设置与 Harmony patcher/Java 启动参数的协议；改 key 要同步 `ExtraSettingsRepository`、页面 UI、`AndroidSettingsBridge` 或 `GodotApp.getCommandLine()` 等消费者、相关 patches，并记录到 `.agent/agent-docs/changelog/`。`log_level`、`android_performance_overlay_enabled` 和 `android_high_refresh_rate_enabled` 额外同步到 SharedPreferences，避免原版游戏保存 settings 时丢失这些 Android 字段。
- `<files>/default/<account>` 的账号选择逻辑与旧移植版兼容但较脆弱，多账号/自定义 platform player id 改动要同时检查 Java 与兼容 MOD。
- 当前普通 MOD 目录由 launch profile 决定：`mods_mode=global` 使用 `<files>/mods`，`mods_mode=isolated` 使用 `<files>/instances/<profile_id>/mods`；MOD 导入先进入 cache staging 并按 manifest `id` 检测同 ID 冲突，用户选择“使用新 MOD”时才删除同 ID 原 MOD 后提交，避免两个同 ID 项目开关连体；随后普通本地/Nexus 导入按 staging 到 MOD 根的实际相对路径检测文件覆盖，若将覆盖不属于本次同 ID 替换的既有 `.dll` / `.pck` / `.json` 或资源文件，必须弹窗让用户明确确认后才提交，避免 A MOD 文件被 B MOD 静默替换；Workshop 下载也先进入 staging，但下载前会弹出分支/manifest 候选，最终以设置中的导入分组下 `<branch>/<published_file_id>/` 作为 item 边界，更新同一 item 时固定沿用已安装记录分支并直接覆盖同 ID 旧项；同一分支仍整体替换该目录，详情/删除/前置判断优先按 `<files>/workshop/library/index.json` 记录的 item 根目录执行；MOD 分组通过目录和 `.sts2_mod_group` 标记维护，拖拽移动会改动 MOD 文件位置。新增路径相关功能必须同步 Java 管理页、C# `AppPaths`、ModLoader patches 和迁移/备份逻辑。
- 本地存档快照、Steam Cloud 与 WebDAV 云存档同步必须使用当前 launch profile 的 account root：`save_mode=global` 使用 `<files>/default/<account>`，`save_mode=isolated` 使用 `<files>/instances/<profile_id>/default/<account>`；不要把存档功能固定写死到全局 `<files>/default/1`。WebDAV 只同步白名单 STS2 存档文件，远端不做删除镜像；`settings.save` 默认不同步，除非用户显式开启实验性开关。
- 多版本兼容包的长期方向是 manifest 化、可安装、可诊断，并作为启动配置属性选择；不要把某一游戏版本的兼容 patch 直接写死到 Android shell，也不要恢复全局兼容包 fallback 选择。
- 对 `v0.108.0` target 改动时务必用 `ReferenceFlavor=original-v0.108.0` 编译；对 `v0.107.1` target 改动时务必用 `ReferenceFlavor=original-v0.107.1` 编译；对旧 `v0.107.0-beta` target 改动时务必用 `ReferenceFlavor=original-v0.107.0` 编译；维护旧 beta `v0.106.1-beta` target 时用 `original-v0.106.1`；对正式 `v0.103.x` target 改动时务必用 `ReferenceFlavor=original` 编译。默认验证路径是 `port-mod/tools/build-compat-matrix.sh` 的所有 active target compile gate。
- 新增兼容 target 时需要同时增加：`.env.example` 中的 original reference 配置说明或 `ReferenceFlavor` 映射、`port-mod/targets/active/<target_id>/target.json`、必要的 target adapter/capability 或条件编译、文档版本矩阵、至少一次 importer APK 构建验证。只有需要保留 schema 1 旧发布包对照时，才额外新增/维护 `compat/*` legacy 分支、`compat_manifest.*.json` 与 `tools/android/bundled-compat-packs.json` 条目。

## 修改说明

完成用户要求的修改后，请用脚本构建一个 importer 版本 APK，便于用户测试：

```bash
tools/package/build_importer_apk.sh
```
