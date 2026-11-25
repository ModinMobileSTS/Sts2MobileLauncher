# 构建与打包流程

## 1. 本地配置入口

构建脚本通过仓库根目录的两个本地文件读取机器相关配置：

```bash
cp .env.example .env
cp local.properties.example local.properties
```

- `.env`：工具链路径、外部参考 runtime/original DLL、私有 payload zip、签名环境变量等；已加入 `.gitignore`。
- `local.properties`：非 secret 的本地构建选项，例如 Gradle task、输出 APK 路径、compat pack staging 目录；已加入 `.gitignore`。

详细字段说明见 [`local-configuration.md`](local-configuration.md)。路径可以是绝对路径，也可以是相对仓库根目录的相对路径。

如需自动准备可公开 clone 的 GitHub 参考项目，可运行：

```bash
tools/deps/prepare-external-projects.sh
```

它会初始化 `port-mod` submodule，并把默认参考仓库 clone 到 `.agent/reference-repos/`（或 `local.properties` 的 `deps.external_projects_root`）。`.agent/` 是本地 agent/参考资料目录，已 gitignore，不应提交。商业游戏 payload、original DLL、keystore 和准备好的 Godot/Mono runtime 仍需你在 `.env` 中配置本地路径。

## 2. 必需工具与本地输入

常用依赖：

- Bash
- Python 3；默认内置官方兼容包和本地签名发布都需要 `cryptography`（可安装在本机构建 venv 中）
- rsync
- JDK（必须有 `javac`，Java 17+；本地常用 JDK 21）
- Android SDK / NDK / CMake
- .NET SDK
- Godot 4.5.1 Mono 与 export templates（仅在需要重新导入资源、生成 Android 优化本体包时使用）
- arm64 Android 设备或模拟器（运行验证用）

`.env` 中至少需要配置：

```bash
JAVA_HOME=/path/to/jdk
ANDROID_HOME=/path/to/android-sdk
DOTNET_BIN=/path/to/dotnet
STS2_ANDROID_RUNTIME_REFERENCE_ROOT=/path/to/reference/android-template
STS2_FMOD_PLUGIN_AAR=/path/to/fmod-release.aar
# 可选：AAR 与对应 2.03.06 Android arm64 原生库未放在同一目录时指定。
# STS2_FMOD_ANDROID_LIBS_DIR=/path/to/fmod/android/arm64
STS2_CRYPTO_NATIVE_JAR=/path/to/libSystem.Security.Cryptography.Native.Android.jar
STS2_ORIGINAL_V103_REFERENCE_DIR=/path/to/v0.103.x/bin/Debug
STS2_ORIGINAL_V1061_REFERENCE_DIR=/path/to/v0.106.1/bin/Debug
STS2_ORIGINAL_V1070_REFERENCE_DIR=/path/to/v0.107.0/bin/Debug
STS2_ORIGINAL_V1071_REFERENCE_DIR=/path/to/v0.107.1/bin/Debug
STS2_ORIGINAL_V1080_REFERENCE_DIR=/path/to/v0.108.0/bin/Debug
# Historical V1090 name gates the shared v0.109.x target; point it at the latest v0.109.1 reference.
STS2_ORIGINAL_V1090_REFERENCE_DIR=/path/to/v0.109.1/bin/Debug
# Historical V1100 flavor; shared v0.110.x gate uses v0.110.1.
STS2_ORIGINAL_V1100_REFERENCE_DIR=/path/to/v0.110.1/bin/Debug
# Separate v0.111.1 stable/public and v0.111.0 beta/public-beta targets reuse this existing gate.
STS2_ORIGINAL_V1110_REFERENCE_DIR=/path/to/v0.111.0/bin/Debug
# Historical V1130 flavor; shared v0.113.x gate uses the latest v0.113.1 reference.
STS2_ORIGINAL_V1130_REFERENCE_DIR=/path/to/v0.113.1/data_sts2_windows_x86_64
STS2_ORIGINAL_V1140_REFERENCE_DIR=/path/to/v0.114.0/data_sts2_windows_x86_64
```

`STS2_ORIGINAL_*_REFERENCE_DIR` 目录需包含 `sts2.dll`、`GodotSharp.dll`、`0Harmony.dll`。这些是 compile gate 引用，不提交到仓库。

v0.111.1 与 v0.111.0 复用现有 V1110 引用、`ReferenceFlavor=original-v0.111.0` 和 `STS2_TARGET_1110`；无需新增 flavor 或 V1111 环境变量。这里只共享 compile gate/托管实现，实际本体导出仍必须使用同版本的原版 ZIP 与源码/反导出工程。

如果你有统一的参考工程根目录，也可以设置 `STS2_REFERENCE_ROOT` / `STS2_LAUNCHER_REFERENCE_ROOT`，让脚本派生默认子路径；详见 `.env.example`。

## 3. Gradle/Android 配置

当前配置：

- AGP：`8.6.1`
- Gradle wrapper：`8.13`
- Kotlin：`2.1.20`
- Steam 子模块：`android/steam-protocol`、`android/steam-content`（Kotlin JVM + protobuf，用于 Steam 登录/SteamPipe 下载）
- 主要依赖：JavaSteam `1.6.0`、OkHttp `5.3.2`、protobuf `4.31.1`、AndroidX Security Crypto、Android Prefab zstd、XZ、`libarchive-android` `1.1.6`；app module 启用 `desugar_jdk_libs 2.0.3` core-library desugaring，使 Steam 下载/云同步使用的 `java.time` 在 minSdk 24/25 可用。许可证速览、直接参考仓库与发布前合规检查见 `THIRD_PARTY_LICENSES.md`
- compileSdk/targetSdk：`35`
- minSdk：`24`
- buildTools：`35.0.0`
- NDK：`28.1.13356709`
- CMake：`3.22.1`（用于 `libworkshop_zstd.so` JNI wrapper）
- Java source/target：`17`
- flavor：`mono`
- 默认 build type：`release`，脚本执行 `assembleMonoRelease`
- ABI：`arm64-v8a`
- applicationId：`com.megacrit.sts2re`
- versionName/versionCode：`v0.1.14` / `116`

`release` build type 当前保留 `debuggable true`，便于本地 sideload 和 `run-as` 验证。package 脚本使用 `RELEASE_KEYSTORE_*` 或 `local.properties` 中的签名配置；本地测试默认可使用 Android debug keystore。**已发布过 APK 后，更新必须保留原 keystore 中的同一私钥和证书**；即使原来使用 debug 证书，也不能直接换成新 release key 或重新生成同名 debug keystore，否则旧用户不能正常覆盖安装。正式发布的安全策略需单独规划，不能把更换签名 key 当作补齐签名方案。

本地 payload 与 MOD 导入现在保留 ZIP 的 Java 解包路径，并通过 `libarchive-android` 支持常见非 ZIP 归档（RAR/RAR5、7z、tar/压缩 tar、gzip、bzip2、xz、zstd、lz4、cab、cpio、ar、lha 等）。归档仍先进入现有 staging，再做路径/内容校验；非 ZIP 归档的加密条目、符号链接/硬链接、设备等特殊文件、超过 200,000 个条目、单文件 8 GiB 或总展开 16 GiB 的输入会 fail closed。只有非 ZIP 导入首次使用归档解析器时加载 native library，普通 ZIP/单文件 MOD 不增加运行时解压开销；CPU、内存和临时磁盘成本只发生在导入期间。

当前 app 仅打包 `arm64-v8a`，`libarchive-android:1.1.6` AAR 本地缓存大小约 3.98 MB，其中 arm64 `libarchive-jni.so` 约 2.03 MB 未压缩；最终 APK 增量取决于 Gradle 对 native library 的打包/压缩方式。该增量换取导入格式支持，不会增加游戏运行时的解压路径。

### 3.1 APK 签名方案与更新身份

`android/build.gradle` 的 release signing config 显式启用 **v1 + v2 + v3**、关闭 v4，只交付 APK，不生成 `.idsig`。继续使用原有签名身份，不生成新 key、不做 key rotation，也不改变包名或 minSdk。v2 本身就是 Android 7.0+ 标准签名；历史构建只有 v2 不等于 APK 未签名。补齐 v1 提供 JAR / `META-INF` 签名，但不能据此认定厂商“未签名”提示都由缺 v1 导致，仍应核对用户实际收到的 APK、下载完整性和厂商安装策略。

- v1/v2/v3 均位于 APK 内，不依赖外部签名文件；v4 不参与本项目 release 打包和验签。
- importer / direct 打包脚本在覆盖稳定产物前调用 `tools/package/VerifyApkSignatures.java`，使用 SDK Build Tools `35.0.0` 自带的 `apksig` 验证 v1/v2/v3，三种方案必须全部通过。为实际检查 v1，验证下界设为 API 23；应用 minSdk 仍是 24。
- 如果稳定输出位置已存在旧 APK，先验证旧包并比较签名证书；旧包可以只有 v2。证书变化或旧包损坏时终止，保留旧稳定产物。首次构建无旧包可比时只验证新包；CI 可把上一发布 APK 放到相应稳定输出路径以启用连续性检查。
- 同 alias、同密码或同为“Android Debug”不代表同一证书，必须备份原 keystore。第三方重签名 APK 不在本签名身份的覆盖安装保证内；版本还须符合系统更新规则，不能用降级绕过签名不匹配。

完整验签（可在最后追加上一版 APK 路径，检查证书连续性）：

```bash
source tools/env/load-local-config.sh
sts2_init_env
"$JAVA_HOME/bin/java" --class-path "$ANDROID_HOME/build-tools/35.0.0/lib/apksigner.jar" \
  tools/package/VerifyApkSignatures.java dist/sts2-re-importer.apk
python3 tools/package/test_apk_signatures.py -v
```

签名回归使用临时生成的测试 key 与无业务代码 APK，覆盖旧 v2-only 包到 v1/v2/v3 包的同证书更新、不同证书拒绝、APK 篡改和新产物缺少方案的拒绝；测试 APK 不生成 sidecar。官方说明：[apksigner](https://developer.android.com/tools/apksigner)、[签名与更新身份](https://developer.android.com/studio/publish/app-signing)。

## 4. 同步大型 runtime

```bash
tools/android/sync-runtime-from-references.sh
```

同步内容：

- Godot Android template AAR/native libs
- `.NET/Godot` BCL/runtime DLL
- crypto native jar
- FMOD 2.03.06 Android 三件套：`libfmod.so`、`libfmodstudio.so`、`libGodotFmod.android.template_release.arm64.so`。默认从 `STS2_FMOD_PLUGIN_AAR` 同级的 `arm64/` 读取，也可通过 `STS2_FMOD_ANDROID_LIBS_DIR` / `runtime.fmod_android_libs_dir` 指定；同步前核对已知 2.03.06 SHA，拒绝旧 2.02 和混用版本，然后覆盖参考 runtime 中 debug/release 两套 staged native 文件并移除未使用、依赖未打包 `libfmodL` 的 debug 桥。不能只升级两个 FMOD 引擎库而保留旧 Godot 桥。
- FMOD AAR，并应用 `tools/android/fmod-shim/` 中的 Java shim；当前 2.03.06 native 使用 `getDevices`/设备名/类型 JNI 接口，仍保留旧 `getAudioDevices(int)` 接口。两条路径统一过滤 remote-submix，耳机/USB/蓝牙输出变化分别通知设备枚举与 AAudio 重连。`audio_compatibility_mode` 在 native 初始化前决定是否禁用 AAudio/低延迟路径；同步脚本替换全部生成的 `FMOD*.class` 并校验 AAR 内 `libs/fmod.jar`，缺少目标 jar 或 class 时直接终止构建。
- Godot template AAR 的输入池与有序来源修补：`tools/android/patch-godot-input-pool.py` 只修改 staged debug/release AAR，在 `InputEventRunnable.obtain()` 前过滤未处理 mouse action，并给原 pooled runnable 添加 source/epoch 旁带信息。使用 JDK 21 自带 ASM，保留原 JNI、池及回收路径；未知 ABI 拒绝构建，参考 AAR 不变。详见 §4.3。
- Gradle wrapper jar

这些产物位于 `android/assets/dotnet_bcl/`、`android/libs/` 等 gitignored 路径，不手工维护。长期源码化状态和剩余阻塞见 [`source-dependencies.md`](source-dependencies.md)。

FMOD 下载地址、参考目录布局和 SHA-256 见 [§4.4 FMOD Android 构建输入](#44-fmod-android-构建输入)。

### 4.1 实验性 Mono 内存总量修复（默认关闭）

部分 MOD（例如 Combat Solver）使用 `GC.GetGCMemoryInfo()` 计算内存条。当前 Ekyso 定制 Mono 9.0.7.0 的 `mono_determine_physical_ram_size()` 最终以空闲页计算总量，可能把启动时空闲内存误报为 GC 可用总量。匹配该定制运行库的完整源码/构建配置尚未获得，因此这里不是源码重建，而是经明确授权的最小二进制实验修复。

`MONO_MEMORY_STATS_FIX=1`（或 `local.properties` 的 `runtime.mono_memory_stats_fix=1`）让同步脚本调用 `tools/android/patch-mono-memory-stats.py`，只修改 debug/release **staged 副本**中 ARM64 文件偏移 `0x1f2444` 的一条指令：`mov w0,#99` → `mov w0,#98`，即 `_SC_AVPHYS_PAGES` → `_SC_PHYS_PAGES`。实际仅改变一个字节，不改变 ELF 布局、导出符号、原有 Harmony 访问检查改动、独立空闲量查询或显式堆上限。

| 状态 | `libmonosgen-2.0.so` SHA-256 |
| --- | --- |
| 原始 Ekyso ARM64 库 | `ccf7112d0affea2be160a43ec561e0e939b9331cf4e2dfa652035c4edeb5e482` |
| 总量查询实验修复 | `a4e4372f5b3cc8a117f0155672d52644301db5cbe8df44e1c57dfe0f2050d64c` |

修复器校验完整输入 SHA、原指令和完整输出 SHA；未知/损坏的库直接拒绝，不能按“版本相似”套用偏移。写入采用同目录临时文件替换，禁止原地修改输入、符号链接输入/输出或与输入同 inode 的输出。参考目录应始终保存原库。ELF build ID 未修改，诊断必须使用文件 SHA 区分两者。

```bash
# 回滚/对照版：原始参考库，不启用实验修复。
MONO_MEMORY_STATS_FIX=0 \
  IMPORTER_DIST_APK=dist/sts2-re-importer-mono-memory-rollback.apk \
  tools/package/build_importer_apk.sh

# 实验版：只修总量查询；与回滚版使用相同包名、版本及本机签名配置。
MONO_MEMORY_STATS_FIX=1 \
  IMPORTER_DIST_APK=dist/sts2-re-importer-mono-memory-test.apk \
  tools/package/build_importer_apk.sh

# 修复/拒绝未知库/逐字节还原回归：输入必须为本机原始库。
python3 tools/android/test-mono-memory-stats.py /path/to/original/libmonosgen-2.0.so

# 单独恢复一个已修复的副本，输出仍必须与输入分离。
python3 tools/android/patch-mono-memory-stats.py repaired.so restored.so --restore
```

关闭开关并重新执行完整打包脚本即可从未修改的参考目录恢复原库；仅裸跑 Gradle 不会重新同步 runtime。设备回滚时先退出游戏，再覆盖安装同签名回滚 APK（`adb install -r ...`），不需要卸载、清数据或重新导入游戏。先保留存档备份；若签名不匹配，不要通过卸载强行解决。

**验证边界：**安全回归覆盖真实本地原库的可逆性和拒绝路径；本次受控 ARM64 指令模拟验证了总量、GC 负载计算、16 KiB 页大小、显式堆限制及已有零值回退。它不等于 Android 启动、托管 GC 循环或 Combat Solver 实战验证。真机测试应对照同一启动配置/战斗，记录模组版本、内存条、`MEMORY_MONITOR_DISPLAY` / `GC_LATENCY`、崩溃日志与系统内存信息，并检查结束搜索/切后台再恢复。

此版**不包含 `MemAvailable` 改进**：空闲量仍来自 Android 的 `freeram + bufferram`，GC 指标也不等于实时整机内存或可继续分配额度。内存条仍可能偏保守；修复不保证速度提升或避免 OOM。不得为了让条变绿而提高堆上限、添加 `largeHeap`、关闭 GC 或伪造 MOD 统计。正式的完整修复仍需匹配源码及原有补丁后重建验证。

### 4.2 MonoMod 原生布局安全修复（默认启用）

当前 [Ekyso/Harmony 的 MonoMod helper](https://github.com/Ekyso/Harmony/blob/main/LocalMonoMod/src/MonoMod.Utils/Extensions.cs) 中，`SetMonoCorlibInternal(Assembly, bool)` 仍按旧 Mono 布局计算 `corlib_internal` 字节。已核对的 Android ARM64 Mono 9.0.7 中，该计算落在 `MonoAssembly + 0x7b`，属于 `ignores_access_checks_assembly_names` 指针内部，不是标志位。普通 Harmony/Cecil 动态补丁生成会经过该路径；这是真实的内存破坏风险，但不能单凭它认定某次玩家闪退或 Godot `StringName` 告警的直接原因。

`sync-runtime-from-references.sh` 默认调用 `patch-monomod-corlib.py`，只替换 staged `MonoMod.Utils.dll` 的这一个方法体：

- 保留 Mono runtime gate、参数检查和 `ReflectionHelper.AssemblyCache` 的锁、弱引用及 hashed/full/short 三种名称注册；保留异常时释放锁。
- 删除私有原生字段探查、旧偏移计算及原生字节写入，不换成另一个猜测偏移，也不增加运行时 Harmony 自补丁。公共方法签名和其余程序集内容保持不变。
- 配套 Ekyso Mono 的字段/方法访问检查入口已经直接允许访问，因此不再需要这个原生标志写入。构建必须核对 **debug/release 两套**原生库完整 SHA；只接受 §4.1 列出的原库或仅内存总量指令修复的副本，其他 Mono 必须重新审计，不能直接复用该策略。
- 托管输入/输出也锁定完整 SHA；未知、损坏或混搭输入终止打包。只原子发布到独立 staged 文件，不修改参考库，不提交二进制。DLL 的 assembly identity/MVID 不变，诊断必须看 SHA。

| `MonoMod.Utils.dll` | SHA-256 |
| --- | --- |
| 原始参考库 | `e085299936748e22cb8575c5522780fc22e8253d5e483d270473b0a69538b593` |
| 移除旧原生写入 | `0557563916172c0517314d669aaf1d996fb69af1710b2b9c99a481f28235b4f0` |

`GameLaunchPreparationManager` 在 BCL 缓存命中返回前比较 APK 与 publish 中的 `MonoMod.Utils.dll` 内容；即使 APK `versionCode`、兼容包、长度和时间戳没有体现更新，也会校验临时副本后原子替换旧 DLL。内容相同则不重写。此步骤不受兼容包开关控制；同签名覆盖安装不需要清数据或重新导入 MOD。

```bash
# 使用未修改的本地参考输入，验证 SHA gate、单方法修改边界和输入保护。
python3 tools/android/test-monomod-corlib.py \
  /path/to/reference/assets/dotnet_bcl/MonoMod.Utils.dll \
  /path/to/reference/libs/release/arm64-v8a/libmonosgen-2.0.so

# 同步后执行真实 staged DLL；不使用商业游戏程序集。
LD_PRELOAD=libgcc_s.so.1 "$DOTNET_BIN" run \
  --project tools/android/tests/MonoModCorlib.Tests -c Release
tools/android/gradle-with-s2-env.sh testMonoReleaseUnitTest \
  --tests com.godot.game.CompatLaunchReadinessTest
```

验证边界：托管 harness 强制 helper 的 Mono 分支，在受控原生缓冲区验证 true/false/重复调用不改字节、缓存及异常释放锁，并在宿主运行时执行真实 Cecil 私有成员/跨生成程序集调用和 Harmony patch/unpatch。它不是 Android Mono 或玩家 MOD 组合的真机验证。`LD_PRELOAD` 仅为 Linux Harmony 验证环境要求，不打入 APK。本修复与默认关闭的内存总量实验独立，不修改 native Mono、GC/堆上限、Godot `StringName`、游戏 DLL 或用户 MOD。

### 4.3 Godot Android 输入事件池与有序来源修复

Godot 4.5.1 的 `GodotInputHandler.handleMouseEvent` 会先调用 `InputEventRunnable.obtain()`，随后才过滤不转发的 `ACTION_BUTTON_PRESS` / `ACTION_BUTTON_RELEASE`。实体鼠标一次点击通常包含 DOWN、BUTTON_PRESS、BUTTON_RELEASE、UP；两个未处理事件会占用 1,200 个 pooled runnable 中的槽位而不归还，长时间使用后会出现 `Input event pool is at capacity`，触摸、鼠标和键盘输入都会停止。

`sync-runtime-from-references.sh` 对 debug/release **staged** Godot template AAR 运行 `tools/android/patch-godot-input-pool.py`。修补器保留原始处理器作为内部 base，包装类只在 `obtain()` 前过滤未处理 action；JDK 21 自带 ASM 为原 `InputEventRunnable` 的 key/mouse/touch setter 捕获来源与生命周期 epoch，并包装原 `run`。支持的事件仍走原 setter、JNI 分发与 finally/recycle，不替换 Godot 输入翻译，也不修改参考 runtime。缺少预期 class、未知 setter/run 结构、部分转换或补丁完整性不符时 fail closed；完整补丁字节级幂等。

`GodotInputSourceHost` 只有在 full compat Connect 来源信号并调用 `bindInputSource(long singletonObjectId)` 后才启用有序路由。绑定后 key/mouse/touch runnable 在 Godot render/main 队列执行，来源边界在 native 输入前同步通知 C#，使其先 flush 旧来源再更新策略；被转入 render 队列的同一个 runnable 不能提前归还池。pause/focus loss 更换 epoch，旧排队事件丢弃但仍走原回收；resume/focus regain 自动投递 Unknown 重置，无需再次 Ready。sensor/joy 不额外改路由，未绑定时保留原调度。`tools/android/input-shim/` 保存包装类、来源桥与 ASM 转换器源码；`FloatingMouseInputController` 的合成右键使用显式 Touch 入口，不能伪装成实体鼠标。运行协议与交互范围见[兼容包加载流程](../runtime/compat-pack-loading-flow.md)。

```bash
# 检查两个本地参考 AAR 的修补结果；不会修改参考输入。
tools/android/test-godot-input-pool.sh

# 完整构建会自动同步并应用修补，然后生成 importer APK。
tools/package/build_importer_apk.sh

# APK 构建已准备好 patched AAR 后，执行原 handler/plugin 的 Android 回归。
tools/android/gradle-with-s2-env.sh testMonoReleaseUnitTest \
  --tests com.godot.game.GodotInputSourceHostTest
```

`test-godot-input-pool.sh` 使用 debug/release 两套原始 AAR 的实际 runnable 与池检查消费者可见来源顺序、缓冲事件边界、重复使用后的池容量、生命周期隔离、同步 rebind、幂等和未知形状拒绝；`GodotInputSourceHostTest` 进一步走原 handler，覆盖实体键鼠/touchpad、虚拟键、浮动右键及 plugin 暂停/焦点恢复。它们不能替代蓝牙/USB 键鼠真机测试；真机仍应检查输入法组合输入、触屏与键鼠交替、跨设备竞争手势和持续点击超过 600 次后所有输入仍可用，且 logcat 不再刷 `Input event pool is at capacity`。

### 4.4 FMOD Android 构建输入

Android ARM64 的 FMOD 2.03.06 三件套和原始插件 AAR 来自 Utopia-rise `fmod-gdextension` [6.1.0-4.5.0](https://github.com/utopia-rise/fmod-gdextension/releases/tag/6.1.0-4.5.0) 的预编译发布资产 [`addons.zip`](https://github.com/utopia-rise/fmod-gdextension/releases/download/6.1.0-4.5.0/addons.zip)，不是源码快照。该 tag 对应提交 [`fda1f89`](https://github.com/utopia-rise/fmod-gdextension/commit/fda1f89a08c0048ed313b333f90b7a7258ce2e61)。本地构建输入所用的 ZIP 与 [GitHub 发布资产](https://api.github.com/repos/utopia-rise/fmod-gdextension/releases/assets/295443658) 的 SHA-256 一致：`8796f6cd1cb38bf7ff4da2648f8af2695244ffdb80a7ac4ab8a171ca44895713`。

1. 解压 `addons.zip`，将 `.env` 中的 `STS2_FMOD_PLUGIN_AAR` 指向解压后的 `fmod/libs/android/fmod-release.aar`。该 AAR 内含 `libs/fmod.jar`。同目录下 `arm64/` 已包含脚本要求的 `libfmod.so`、`libfmodstudio.so` 和 `libGodotFmod.android.template_release.arm64.so`，三者均通过 [同步脚本的 SHA-256 校验](../../tools/android/sync-runtime-from-references.sh#L45-L58)。保留此目录结构即可使用默认路径；如果配置了 `STS2_FMOD_ANDROID_LIBS_DIR` 或 `runtime.fmod_android_libs_dir`，应指向这份 `arm64/`。
2. 将同一份原始 `fmod-release.aar` 放入 `$STS2_ANDROID_RUNTIME_REFERENCE_ROOT/libs/release/fmod-release.aar`。当前脚本只用 `STS2_FMOD_PLUGIN_AAR` 覆盖 debug 的 AAR，release 的 AAR 随参考 `libs/` 同步；两份 staged AAR 都会应用 Java shim，不修改参考输入。

同步时校验的是**打包前**的桥。Gradle 的 `stripMonoReleaseDebugSymbols` 会处理 ELF；对上游桥执行 `llvm-strip --strip-unneeded` 的结果，与 v0.1.13 importer APK 中的桥逐字节一致。

| `libGodotFmod.android.template_release.arm64.so` | SHA-256 |
| --- | --- |
| `addons.zip` 内的构建输入 | `4e5c2529134ed8424a2958e2ed3825a0383ec9b33ffb853778eddd0174ea68fc` |
| v0.1.13 importer APK 内的打包产物 | `63c1455ca51f25b342bf808e41450e92091038d72560829b57f99a217b6013e9` |

不要用 APK 中已 strip 的桥作为输入，也不需要重编 FMOD 插件。此 ZIP 只提供 FMOD 部分；构建仍需配套 Godot/Mono 参考运行时（含未打补丁的 `MonoMod.Utils.dll`）、crypto native JAR 和 Gradle wrapper JAR。发布 APK 不能直接代替参考目录；第三方二进制的再分发遵循[各自许可](../../THIRD_PARTY_LICENSES.md)。

## 5. 构建当前 compat fallback

```bash
tools/android/build-port-mod.sh
```

用途：构建当前 `port-mod` checkout 分支，并 stage legacy fallback 到 APK assets：

```text
android/assets/dotnet_bcl/STS2Mobile.dll
android/assets/port_compat.pck
```

默认 `ReferenceFlavor` 来自 `local.properties` 的 `compat.default_reference_flavor`（未配置时使用 `original-v0.114.0`）。可临时覆盖：

```bash
# 保留的旧稳定版 v0.108.0
REFERENCE_FLAVOR=original-v0.108.0 tools/android/build-port-mod.sh

# 保留的 public-beta v0.113.x（历史 flavor，引用使用 v0.113.1）
REFERENCE_FLAVOR=original-v0.113.0 tools/android/build-port-mod.sh

# 当前 stable/public v0.111.1 与旧 beta/public-beta v0.111.0（共享现有 V1110 引用）
REFERENCE_FLAVOR=original-v0.111.0 tools/android/build-port-mod.sh

# 旧 public-beta v0.110.x（历史 flavor 名称）
REFERENCE_FLAVOR=original-v0.110.0 tools/android/build-port-mod.sh
# v0.109.x（历史 flavor 名，引用使用最新 v0.109.1）
REFERENCE_FLAVOR=original-v0.109.0 tools/android/build-port-mod.sh
# stable v0.107.1
REFERENCE_FLAVOR=original-v0.107.1 tools/android/build-port-mod.sh
# 旧 beta v0.107.0
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh
# 旧 beta v0.106.1
REFERENCE_FLAVOR=original-v0.106.1 tools/android/build-port-mod.sh
# stable v0.103.x
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh
```

脚本会从 `.env` 解析对应的 `STS2_ORIGINAL_*_REFERENCE_DIR`，并通过 MSBuild 属性 `CompatReferenceDir` 传入，不依赖 `port-mod/refs` 中的个人 symlink。

## 6. 构建内置兼容包

先按目的选流程：

| 目的 | 怎么做 |
| --- | --- |
| **A. 只更新兼容包，已安装的玩家走 OTA** | [发布签名兼容包 Release](#61-本地签名与发布官方兼容包)，不构建或发布新 APK。 |
| **B. 发新 APK，让新装和离线玩家开箱使用官方包** | [固定已发布的 Release tag 后打包](#62-构建带官方兼容包的-apk)。 |
| **C. 测试尚未发布的本地兼容补丁** | [显式选择 `matrix` 构建](#63-本地开发与测试)，不上传 Release。 |

完整 APK 打包使用统一 staging 入口：

```bash
tools/android/stage-bundled-compat-artifacts.sh
```

该脚本会先清理 `android/assets/compat_packs/*.zip`，再依次 stage：

```text
android/assets/compat_packs/sts2-android-compat.zip
android/assets/compat_packs/sts2-android-offline-bootstrap.zip
```

默认 `COMPAT_PACK_BUILD_MODE=release`。`sts2-android-compat.zip` 是从 [官方 Release](https://github.com/ModinMobileSTS/sts2-android-compat/releases) 验签后原样内置的 schema 2 full family，当前固定 `v0.8.0`（revision 800，11 个 target）。`matrix` 本地模式生成相同结构的开发包：

```text
android/assets/compat_packs/sts2-android-compat.zip
  compat_manifest.json
  variants/<target_id>/STS2Mobile.dll
  variants/<target_id>/port_compat.pck
  SHA256SUMS
```

`sts2-android-offline-bootstrap.zip` 由 `offline-bootstrap/tools/build-offline-pack.sh` 构建。它不静态引用 `sts2.dll`，manifest 使用 `pack_kind=offline-bootstrap`、`match_mode=offline-wildcard` 与 `versions=["*"]`，启动器只会在没有任何已安装 full compat 包按 SHA/version 匹配当前 payload 时自动推荐它。构建脚本会先运行 `offline-bootstrap/tools/test-offline-contract.sh`：合成测试覆盖可接受与必须拒绝的 `ModelDb.Init` API 形状，并对本机已配置的所有 original reference 做反射契约检查。当前离线包使用 `compat_version=0.2.0-dev`、`probe_contract=offline-bootstrap-v2`；首次用它启动某个 pack/version/SHA 组合时弹风险确认，真实 ModelDb two-phase 成功后才标记 `ready`，已知终态失败的同一组合不再自动匹配。

本地 matrix 与 offline bootstrap 构建脚本会把 Git branch / commit / commit subject 写入构建元数据和 manifest；传给 MSBuild 的 branch/subject 会先转义逗号、分号和百分号，避免提交标题中的标点被 MSBuild 当成多个 `-p:` 属性解析。`release` 模式不修改已签名 ZIP 的 metadata 或任何字节。

可单独运行离线契约验证：

```bash
offline-bootstrap/tools/test-offline-contract.sh
```

如果只需要 stage full family 包（不构建 offline bootstrap），可直接运行：

```bash
tools/android/stage-bundled-compat-packs.sh
```

当前 11 个 active metadata target：

- `v0.103.x`（支持 `v0.103.2` / `v0.103.3`，`ReferenceFlavor=original`）
- `v0.106.1-beta`（旧 beta，`ReferenceFlavor=original-v0.106.1`）
- `v0.107.0-beta`（旧 beta，`ReferenceFlavor=original-v0.107.0`）
- `v0.107.1`（stable，`ReferenceFlavor=original-v0.107.1`）
- `v0.108.0`（保留的旧稳定版，`ReferenceFlavor=original-v0.108.0`）
- `v0.109.0`（稳定 target id，显示为 v0.109.x，同时支持 v0.109.0/v0.109.1；历史 `ReferenceFlavor=original-v0.109.0` 指向最新 v0.109.1 引用）
- `v0.110.0` / `v0.110.1`（旧 public beta，共享 API/protocol target id `v0.110.0`，历史 `ReferenceFlavor=original-v0.110.0` 使用 v0.110.1 引用）
- `v0.111.0`（旧 public beta，独立 API/transport-handshake target，`ReferenceFlavor=original-v0.111.0`）
- `v0.111.1`（当前 stable / `public`，独立 metadata target，复用 `ReferenceFlavor=original-v0.111.0`、现有 V1110 引用与 `STS2_TARGET_1110`）
- `v0.113.0` / `v0.113.1`（保留的 public beta，共享稳定 target id `v0.113.0`、显示 v0.113.x；历史 `ReferenceFlavor=original-v0.113.0` 与 `STS2_TARGET_1130` 保留，引用使用 v0.113.1）
- `v0.114.0`（当前 public beta，独立 target 及 `ReferenceFlavor=original-v0.114.0`，使用 V1140 原版引用和现有 `STS2_TARGET_1130`）

同一 variant 覆盖多个 API-compatible 原版 DLL 时，target 的 `sts2_dll_sha256` 可声明为字符串数组；构建脚本会原样写入 schema 2 manifest，启动器会遍历全部元素做精确 SHA 匹配。旧单字符串 manifest 继续兼容。

v0.111.1 的 `versions=["v0.111.1"]`、`channel=stable`、`steam_branch=public`、`source=original_pc_reference_v0.111.1`，原版 `sts2.dll` SHA-256 为 `3fb1fa1bf0249087b706a5d0f062a60579e2f5b08df4ee477ddd6f2607f654d5`。旧 v0.111.0 beta / `public-beta` 记录不变。两版托管 API、运行时 IL 和依赖等价，因此不改 Java/C# 或编译映射；但归档导入没有 Steam 来源分支时，Workshop `auto` 会回落到 target 的 `steam_branch`，必须分开 .1/public 与 .0/public-beta 元数据，不能合并成 beta variant 的版本/SHA 列表。已有 Steam 来源分支仍优先。桌面审计和 native/headless ModelDb 初始化不是 Android UI/玩法、普通 MOD 或 LAN 认证，不承诺 .0/.1 跨版本联机。

输出到：

```text
android/assets/compat_packs/*.zip
```

该目录下的 zip 是构建产物，已 gitignore，不再由 git 跟踪；提交前不要 `git add -f` 这些 zip。

新版 APK 安装内置 schema 2 family 包后，启动器会把旧 bundled schema 1 包选择迁移到 `sts2-android-compat` family 包的对应 target。新建启动配置的自动匹配也会在同等版本/SHA 命中时优先使用 schema 2 family 包。内置包不会降级完好的已安装包，也不会用同 revision 的内置 ZIP 覆盖完好的在线或手动导入包；自动离线切换逻辑不受 staging 模式影响。

legacy 分支构建模式仍可显式启用，用于回退诊断或对照旧发布包：

```bash
COMPAT_PACK_BUILD_MODE=legacy tools/android/stage-bundled-compat-packs.sh
```

legacy 模式读取 `tools/android/bundled-compat-packs.json`（或 `local.properties` 的 `compat.bundled_packs_config`），非当前分支使用 `compat.worktree_root`（默认 `.agent/worktrees/compat-packs/`）临时 worktree 构建；当前分支可直接使用 dirty worktree 方便测试。stage 脚本还会把共享热修源码注入 legacy worktree；该列表必须包含 `ModEntry.cs` 新增的直接依赖，目前包括 `DevTools/*.cs`、`RunHistoryPatches.cs`、`CombatAnimationWarmupPatches.cs`、`SpineWarmupPreview.cs`、`ExtendedMultiplayerRoomPatches.cs` 和资源释放保护所需的 `AndroidAssetCacheLifecyclePatches.cs`。

也可以直接在 submodule 中局部构建 matrix 包：

```bash
cd port-mod
./tools/build-compat-matrix.sh --target v0.111.1
./tools/build-compat-matrix.sh --target v0.113.0
./tools/build-compat-matrix.sh --target v0.114.0
./tools/build-compat-matrix.sh
```

`--target` 只构建单个 target；不带参数会构建 `targets/active/` 下所有目标。未来停止维护旧版本时，将对应目录移到 `targets/archived/`，后续 matrix 不再构建它；发行 APK 需更新已发布的 tag 才会改变内置目标。需要临时导出 legacy 目标时可用 `BUILD_ARCHIVED_TARGETS=1`。

修改超过四人的房间 UI 适配后，先运行不含商业代码的合成回归，再跑完整 matrix：

```bash
port-mod/tools/test-extended-multiplayer-rooms.sh
port-mod/tools/build-compat-matrix.sh
```

### 6.1 本地签名与发布官方兼容包

只发布 schema 2 完整 family 包到 [ModinMobileSTS/sts2-android-compat](https://github.com/ModinMobileSTS/sts2-android-compat/releases)。在维护者本机用已配置的原版引用构建；不要把 `sts2.dll`、游戏 PCK / 资源或 Ed25519 私钥提交到父仓库、子模块或公开 CI。该密钥与 APK keystore 无关。需要 Python 3（含 `cryptography`）、原有 .NET/原版引用，以及正式发布时有该仓库发布权限的 `gh auth login`。

此流程只更新兼容包：在 `port-mod/main` 修改并用全部 active target 验证，通过后先在 `port-mod` 提交并推送，父仓库同步其指针；再从本机运行下方的发布命令。已有受信任公钥且满足最低 versionCode 的启动器可检查并提示更新，玩家在“版本 → 兼容包 → 在线更新”也能主动刷新并安装，不需要新 APK。

已发布的 [v0.8.0](https://github.com/ModinMobileSTS/sts2-android-compat/releases/tag/v0.8.0) 是首次 OTA 联调的兼容包 Release（revision 800），不含 launcher APK。仅满足 versionCode 115 不代表旧 APK 已包含 `sts2-compat-release-v1` 公钥。后续签同一索引历史时继续使用已配置的外部 PEM，不要再次执行 `keygen` 生成同 ID 的另一把密钥。

**首次发版前，生成密钥并将公钥编入启动器：**

```bash
port-mod/tools/compat-release-signing.py keygen \
  --out-private "$HOME/.config/sts2-re/compat-release-ed25519.pem" \
  --key-id sts2-compat-release-v1
```

1. PEM 保存在仓库外，`keygen` 写入 `0600` 且默认拒绝覆盖。仅在本机 `.env` 设置 `COMPAT_RELEASE_SIGNING_KEY_FILE="$HOME/.config/sts2-re/compat-release-ed25519.pem"`、`COMPAT_RELEASE_SIGNING_KEY_ID=sts2-compat-release-v1`；也可用 `local.properties` 的 `compat.release_signing_key_file` / `compat.release_signing_key_id` 配置外部路径。
2. 将输出的 **32 字节 raw public key 的 Base64** 与对应 `key_id` 加入 [`CompatPackReleaseTrust.java`](../../android/src/com/godot/game/CompatPackReleaseTrust.java) 的 `trustedPublicKeys()`，只提交公钥。APK 内置 Release 也只读这份公钥表，不另配信任根；当前工具识别 `Collections.singletonMap` 字面量，公钥表格式改变时须同步更新解析与测试。空表、未知 key id 或签名错误均拒绝。用 [§6.2](#62-构建带官方兼容包的-apk) 构建带这份公钥的 importer APK；首次尚无已签名 Release 时可用 [§6.3](#63-本地开发与测试) 的 `matrix` 模式验收启动器。
3. 正式上传前，可在未提交的 checkout 用独立测试密钥验证。不要把临时公钥编入发行 APK，也不要发布用它签名的资产：

```bash
python3 port-mod/tools/test-compat-release-signing.py
(
  set -e
  tmp="$(mktemp -d)"
  trap 'rm -rf -- "$tmp"' EXIT
  port-mod/tools/compat-release-signing.py keygen --out-private "$tmp/test.pem"
  port-mod/tools/publish-compat-release.sh \
    --tag v0.8.1-dry-run --revision 801 --prerelease \
    --min-launcher-version-code 115 --min-launcher-version-name v0.1.13 \
    --signing-key-file "$tmp/test.pem" --signing-key-id sts2-compat-release-v1 \
    --dry-run --allow-dirty
)
```

**正式发布（以下命令会上传）：**父仓库与 `port-mod` 均需干净；确保 submodule HEAD 已存在于目标 GitHub 仓库。`--revision` 必须高于历史发布，`--min-launcher-version-code` 按实际可安装的最低启动器设定。直接构建 matrix 可用 `COMPAT_MATRIX_REVISION` / `COMPAT_MIN_LAUNCHER_VERSION_CODE` / `COMPAT_MIN_LAUNCHER_VERSION_NAME` 或对应 CLI flags；本地开发默认 `0.7.3-dev`、revision 703、最低 code 115，不继承官方发布号。

```bash
port-mod/tools/publish-compat-release.sh \
  --tag v0.8.1 --version 0.8.1 --revision 801 \
  --min-launcher-version-code 115 --min-launcher-version-name v0.1.13 \
  --notes-file /path/to/release-notes.md
```

脚本默认运行五项 `port-mod` 回归（仅显式 `--skip-tests` 跳过）、构建所有 active target、合并最近最多 15 条已验签记录、签名并自验 ZIP 和包内文件。历史默认由 `gh` 获取；离线可传 `--previous-index PATH`，其签名默认 `PATH.sig`（可用 `--previous-index-sig PATH` 指定）。发布脚本用当前签名公钥验证历史；密钥轮换前需先部署新公钥并用旧公钥认证历史，不能把来源不明的索引重新签名。GitHub 上尚无 Release 才自动从空历史开始；已有 Release 缺签名、网络或验证出错均中止。`--prerelease` 不会移动 GitHub 的稳定 `/latest`，因此启动器从下一次正式稳定 Release 的已签名索引才可看到其保留记录。

产物归档到 ignored 的 `port-mod/dist/compat-release/<tag>/`：ZIP、`compat-index.json`、`compat-index.json.sig`、独立 `compat_manifest.json` 和独立 `SHA256SUMS`。ZIP 内的 `SHA256SUMS` 覆盖 manifest 与全部 variant 文件；独立清单额外附 ZIP 哈希。用 `compat-release-signing.py verify --index ... --sig ... --public-key-base64 ... --expected-key-id ... --zip ... --manifest ...` 可离线完整复核。干跑不调用 `gh`，不会创建 tag 或 Release。

### 6.2 构建带官方兼容包的 APK

1. 确认目标 tag 已按 [§6.1](#61-本地签名与发布官方兼容包) 发布 ZIP、`compat-index.json` 和 `compat-index.json.sig`，且签名 key id 受当前启动器信任。发布新版 APK 时把 [`tools/android/bundled-compat-release.json`](../../tools/android/bundled-compat-release.json) 中的 tag 更新到这次要随包交付的 Release（如发布后从 `v0.8.0` 改为 `v0.8.1`）；固定 tag 比依赖 `latest` 更可复现。
2. 用默认模式打包。单次构建也可以覆盖 tag，不必改仓库：

   ```bash
   tools/package/build_importer_apk.sh
   # 单次测试已发布的 tag；不改仓库 pin
   COMPAT_BUNDLED_RELEASE_TAG=v0.8.0 tools/package/build_importer_apk.sh
   ```

   显式设置 `COMPAT_BUNDLED_RELEASE_TAG=latest` 时，在线从官方 `releases/latest/download/` 获取索引和签名，从已签名索引解析最新正式 tag，再取该 tag 的 ZIP。只接受官方 GitHub HTTPS 和三个允许的重定向域名（`github.com`、`objects.githubusercontent.com`、`release-assets.githubusercontent.com`，最多五跳），沿用系统代理，不支持镜像或 URL 前缀。
3. 构建会用 APK 同一份 Ed25519 公钥验签，检查 tag、schema 2、`min_launcher_version_code <= android/gradle.properties` 的 `export_version_code`、ZIP 字节长度和 SHA-256，以及 ZIP 内 `compat_manifest.json` 和 `SHA256SUMS`；任一错误停止打包，不回退本地编译。通过后直接复制源 ZIP，不解包重打。按下面命令验收并与签名索引的 `zip_sha256` 对比：

   ```bash
   unzip -p dist/sts2-re-importer.apk assets/compat_packs/sts2-android-compat.zip | sha256sum
   ```

   APK 内还保留单独构建的 `sts2-android-offline-bootstrap.zip`。新装用户离线就有完整官方包，联网刷新时同 ZIP 的条目按 SHA-256 显示为“已安装”；覆盖安装不降级现有更高版本的包。

固定 tag 首次从 GitHub 下载后缓存到 gitignored 的 `.agent/cache/compat-releases/<tag>/`。每次用缓存仍走相同完整校验；缓存损坏时先移走该 tag 的缓存再重试。断网构建可通过 `COMPAT_BUNDLED_RELEASE_DIR=/path/to/release-assets`（或 `compat.bundled_release_dir`）明确指定带三件套的本地目录；指定后只验证该目录，失败不回退缓存或联网。不会自动读取 `port-mod/dist/compat-release/` 或其他干跑输出，用测试密钥签名的三件套不能内置到发行 APK。

### 6.3 本地开发与测试

测试未发布的 `port-mod` 更改时，不要用默认 Release 模式（它不会编译这些更改进 full family）。本地配置好对应 original 引用后，单次显式使用：

```bash
COMPAT_PACK_BUILD_MODE=matrix tools/android/stage-bundled-compat-packs.sh
COMPAT_PACK_BUILD_MODE=matrix tools/package/build_importer_apk.sh
```

第一条只编译并 stage 当前 checkout 的 active targets；第二条走完整 APK 构建并另带离线兜底。常用本地偏好可设为 `local.properties` 的 `compat.pack_build_mode=matrix`；发布 APK 时移除该覆盖或显式用 `COMPAT_PACK_BUILD_MODE=release`。`legacy` 只用于 schema 1 回退诊断。

## 7. 游戏版本更新后的 port-mod 语法树审计

游戏更新后，可先把旧/新版本的 GDRE C# 源码导出目录放在仓库外，再运行语法树级别审计工具，快速找出原版类型、方法签名、方法体和 `port-mod` Harmony/反射触达点的变化：

```bash
tools/port_mod_ast_audit.py \
  --old-source ../s2_original/s201101 \
  --new-source ../s2_original/s201110 \
  --port-mod port-mod/STS2AndroidPortCompat \
  --out .agent/reports/v111-port-mod-ast-audit
```

输出目录包含：

- `summary.md`：人工阅读入口，优先列出 `port-mod` 命中的缺失目标、签名变化和实现变化。
- `port_mod_refs.csv`：每个 Harmony/反射引用的解析结果，可按 `status` 过滤。
- `member_changes.csv`：两个源码版本之间的类型成员变化清单。
- `audit.json`：完整机器可读结果。

该工具是纯 Python，无第三方依赖；它不编译源码，也不会修改 `port-mod`。`dynamic_type_expression` 表示目标类型来自 `__instance.GetType()` 或外部 MOD/库，需要人工按调用上下文确认；`external_or_self_reference` 表示系统库、Harmony/Godot 或 `port-mod` 自身引用，通常不是游戏版本迁移风险。

## 8. 生成启动器 Material Symbols 矢量图

启动器原生 Android UI 使用 `MaterialSymbols` helper 加载 `android/res/drawable/ic_ms_*.xml`。这些文件由 bundled `android/res/font/material_symbols_rounded.ttf` 离线生成，保留 Google Material Symbols Rounded 官方轮廓，但运行时不再依赖系统字体 ligature，避免部分 ROM（例如关闭 MIUI 优化后）把图标显示成 `settings` / `play_arrow` 这类 glyph 名称。

新增或修改 `MaterialSymbols` 的 glyph 映射后，刷新并检查生成资源：

```bash
# 推荐用本地 venv；.agent/ 已 gitignore，不应提交
python3 -m venv .agent/venv-fonttools
.agent/venv-fonttools/bin/pip install fonttools
.agent/venv-fonttools/bin/python tools/android/generate-material-symbol-vectors.py
.agent/venv-fonttools/bin/python tools/android/generate-material-symbol-vectors.py --check
```

如果本机 Python 允许用户级安装，也可以直接运行：

```bash
python3 -m pip install --user fonttools
tools/android/generate-material-symbol-vectors.py
tools/android/generate-material-symbol-vectors.py --check
```

## 9. 构建导入版 APK

导入版不内置游戏 zip：

```bash
tools/package/build_importer_apk.sh
```

流程：

1. 同步 runtime。
2. 构建当前 compat fallback。
3. 按 [§6.2](#62-构建带官方兼容包的-apk) 验签并原样内置固定 tag 的 full family，另构建并 stage offline bootstrap；输出到 gitignored 的 `android/assets/compat_packs/`。测试本地补丁时改用 [§6.3](#63-本地开发与测试)。
4. 执行 `local.properties` 中的 `android.gradle.task`（默认 `assembleMonoRelease`）。
5. 验证 APK 的 v1/v2/v3 签名；稳定输出位置已有旧 APK 时比较证书，成功后复制到 `android.importer.dist`（默认 `dist/sts2-re-importer.apk`），移除输出位置残留的 `.idsig` 并输出 APK SHA-256。

正式 APK 默认声明 `android:appCategory="game"` / `android:isGame="true"`，让 OEM 游戏/GPU 调度识别 `GodotApp`。设置页“系统”分区预加载下方及游戏内 Android 设置提供三挡刷新率：默认高刷、请求 60Hz、跟随系统，对应 `android_display_refresh_rate_mode=high/60hz/system`。旧高刷开关 true/false 迁移为 high/system；新字段优先，迁移后移除旧字段。60Hz 只接受当前尺寸下的近似 60Hz（含 59.94Hz）目标，设备不暴露目标时撤销旧请求并交回系统，不保证 OEM 采纳，也不改变游戏 FPS 上限或 VSync。`HighRefreshRateController` 只在 resumed + focused + render Surface 有效时应用，失焦、pause、destroy 或 Surface 销毁时取消延迟任务，以 100/500/1500ms 有限重试等待 Surface。Android 12+ 使用 `CHANGE_FRAME_RATE_ALWAYS`，同一 Surface 未变的请求不重复投票，三挡切换允许更新/撤销 vote；精确 mode 使用 `preferredDisplayModeId`，alternative-only 清空 mode ID 并使用 `preferredRefreshRate`，随后有界验证实际 mode/Hz。不使用 `SurfaceControl` 或改 Surface 尺寸。设置页同一分区保留默认关闭的“Show performance overlay”开关；开启后下次启动加载 `godot-debug-menu`，显示 FPS、帧时间、CPU/GPU frame graph 和渲染器/硬件信息。

Java 不再把 `fullscreen_render_size` 转换为 Godot `--resolution`。根窗口始终使用 `CanvasItems`：Auto 比例取 UI scale target，固定比例取对应 fixed target，`global_scale` 独立作为 `ContentScaleFactor`。游戏内切换 `fullscreen_render_size` 后，compat 会先完成高层 `ContentScale*` setter，再只用 `RenderingServer.ViewportSetRenderDirectToScreen(false)`、`ViewportSetSize()` 与 `ViewportSetGlobalCanvasTransform()` 即时调整根 renderer RT；scene Window、输入变换与 Android Surface 均不改变，也不使用 `SurfaceHolder.setFixedSize()` 或 `ViewportAttachToScreen()`。`0x0` 恢复 native attachment 尺寸与原始 canvas transform；非零预设按当前 native attachment 比例以 Expand 语义覆盖请求矩形，例如 `2400x1080` 设备选择 `1280x720` 时实际 target 为 `1600x720`。自定义目标长边上限为 `max(4096, native 长边)`。根 Window `SizeChanged`、resume 与 ContentScale repair 后会重投 renderer 状态，因此动态切换前后内容相对大小与触控坐标保持不变，高刷 Surface 生命周期也不受分辨率切换影响。

## 10. 构建直装版 APK

直装版临时内置本地 PC zip。启动器会按内置 zip 的 SHA-256 判断当前 APK 自带本体是否已导入；如果用户从旧直装版升级且只导入过旧 APK 的本体，新 APK 首次进入主界面后会自动导入当前内置 zip，并为新 payload 创建/选择默认启动配置。

```bash
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

也可以在 `.env` 设置 `STS2_PAYLOAD_ZIP` 后无参数调用：

```bash
tools/package/build_direct_apk.sh
```

流程：

1. `validate_payload_zip.py` 校验 zip。
2. 同步 runtime。
3. 构建当前 compat fallback。
4. 用与导入版相同的[兼容包 staging](#6-构建内置兼容包)：默认验签并内置固定 tag 的 full family，另构建 offline bootstrap。
5. 临时复制 zip 到 `android/assets/payload/SlayTheSpire2.zip`。
6. 执行 Gradle task。
7. 按 §3.1 验证 v1/v2/v3 签名和已有稳定 APK 的证书连续性，复制 APK 到 `android.direct.dist`（默认 `dist/sts2-re-direct.apk`），移除输出位置残留的 `.idsig` 并输出 APK SHA-256；脚本退出时删除临时游戏 zip。

## 11. 生成 Android 优化本体 zip（可选）

如果同时拥有某个版本的 PC 原版 zip 与匹配的 Godot 源码/反导出工程，可以重新用 Android export preset 导入资源，生成更适合移动端导入的本体 zip：

```bash
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/SlayTheSpire2.zip" \
  --source-dir "/path/to/sts2-godot-source" \
  --out "dist/payload/sts2-vX.Y.Z-android-body.zip"
```

该脚本的原则：

- `data_sts2_windows_x86_64/sts2.dll`、`sts2.deps.json`、`sts2.runtimeconfig.json` 来自 PC zip 原版，不用重新编译出的 DLL，避免 IL 变化影响 MOD。
- `SlayTheSpire2.pck` 由临时 Godot 工程通过 Android `--export-pack` 重新生成，启用 ETC2/ASTC 导入，并过滤 PC 用的 BPTC/S3TC 纹理、`.godot/mono` publish、桌面 native runtime 等。
- 字体 import 会在临时工程中关闭 MSDF 大 fontdata 设置，以接近移植版体积；Sentry autoload/GDExtension 会同时在 `project.godot` 与 `project.binary` 中禁用。
- 临时工程会从 `.tscn` / `.tres` 引用合成缺失的 `.uid` sidecar，确保重导出的 `.godot/uid_cache.bin` 继续匹配原场景里的 `uid://...` 引用，避免 Android 首帧资源绑定崩溃。
- Spine `.atlas` / `.skel` 导入产物是平台无关资源；若 headless Linux export 环境没有 Spine editor importer，脚本会从源工程 `.godot/imported` 注入 `.spatlas` / `.spskel` 与对应 `.atlas.import` / `.skel.import` remap 到最终 PCK，并在缺失时失败，避免主菜单、地图节点或战斗场景黑屏。
- 产物仍满足 `validate_payload_zip.py` 与当前 launcher 导入格式，可在导入版 APK 中当普通 payload zip 导入；输入 PC zip 若包含单一顶层目录（例如 `Slay the Spire 2/...`），脚本会自动识别并在输出 zip 中展平。
- ZIP 校验、依赖读取和重打包共用归一化名称到原始 `ZipInfo` 的索引；反斜杠条目及单顶层目录包仍读取真实条目，不按归一化字符串重新查找原 ZIP。归一化后重名会明确失败，避免校验与重打包选中不同内容。回归：`python3 tools/package/test_payload_zip.py`（仅合成资源）。
- 源工程 patch 同时禁用旧 `SentryInit` 与 v0.110.0 起使用的 C# `SentryBootstrap` autoload，并移除桌面 Sentry GDExtension；managed dependency keep-list 从原版 `sts2.deps.json` 推导，因此后续版本的 `Sentry.Godot.dll` 会和原版 `sts2.dll` 一起保留。

常用本地示例：

```bash
# v0.103.3：PC zip + 对应源码/反导出工程
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/v0.103.3/SlayTheSpire2.zip" \
  --source-dir "/path/to/s201033" \
  --out "dist/payload/sts2-v0.103.3-android-body.zip"

# v0.108.0 保留的旧稳定版：PC zip + 对应源码/反导出工程
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/sts2-v0.108.0.zip" \
  --source-dir "/path/to/s201080" \
  --out "dist/payload/sts2-v0.108.0-android-body.zip"

# v0.109.1 旧 public beta
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/sts2-v0.109.1.zip" \
  --source-dir "/path/to/s201091" \
  --out "dist/payload/sts2-v0.109.1-android-body.zip"

# v0.111.1 当前 stable/public（必须使用匹配的 .1 原版 ZIP 与 s201111，不能用 .0 本体替代）
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/sts2-v0.111.1.zip" \
  --source-dir "/path/to/s201111" \
  --out "dist/payload/sts2-v0.111.1-android-body.zip"

# v0.114.0 当前 public beta（匹配该版本的 PC ZIP 与恢复工程）
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/sts2-v0.114.0-pc.zip" \
  --source-dir "/path/to/s201140" \
  --out "dist/payload/sts2-v0.114.0-android-body.zip"
```

v0.111.1 的优化本体 ZIP 保留该版本原版 DLL 与 release 身份，并使用匹配的 .1 资源工程导出 PCK；复用 .0 compile gate 不允许混用 .0 ZIP/PCK。导出成功只证明构建/归档流程完成，不是 Android UI、玩法、普通 MOD 或联机实测通过。

临时工程和 Godot 日志默认写入 `.agent/tmp/android-body-build/`，该目录不入库；生成的 zip 应留在 `dist/` 或其他本地输出目录，不要提交。

## 12. 局部检查

```bash
# Steam 可恢复认证协议单元测试
tools/android/gradle-with-s2-env.sh :steam-protocol:test

# 启动器数据安全回归（Robolectric；无需账号、商业 payload 或连接设备）
tools/android/gradle-with-s2-env.sh testMonoReleaseUnitTest \
  --tests com.godot.game.CloudSyncSafetyTest \
  --tests com.godot.game.LocalSaveSnapshotSafetyTest \
  --tests com.godot.game.ModImportSafetyTest

# GitHub Release 在线兼容包：回退保护、签名/完整性、匹配与 UI 回归
tools/android/gradle-with-s2-env.sh testMonoReleaseUnitTest \
  --tests com.godot.game.CompatPackBundledDowngradeProtectionTest \
  --tests com.godot.game.CompatPackReleaseSecurityTest \
  --tests com.godot.game.CompatPackRemoteUpdateFlowTest \
  --tests com.godot.game.AutomaticOfflineCompatSelectionTest \
  --tests com.godot.game.CompatLaunchReadinessTest \
  --tests com.godot.game.ModalScrollingTest

# full compat manifest 别名越界/符号链接回归（合成 API shape）
dotnet run --project port-mod/tests/ModManifestAlias.Tests/ModManifestAlias.Tests.csproj

# Java/Kotlin/Steam 子模块编译检查
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac

# 窗口生命周期 / ContentScale 静态回归约束
! rg 'GodotLib|dispatchImmediateGodotFocusChange' \
  android/src/com/godot/game/GodotApp.java
! rg 'NotificationWMWindowFocusIn|NotificationApplicationFocusIn' \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs
! rg 'window\.ContentScale(Mode|Aspect|Size|Factor)\s*=' \
  port-mod/STS2AndroidPortCompat/Patches/UiScalePatches.cs
! rg 'CustomRender|ContentScaleModeEnum\.Viewport' \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs
rg 'ContentScaleModeEnum\.CanvasItems' \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs
rg 'ViewportSetRenderDirectToScreen|ViewportSetSize|ViewportSetGlobalCanvasTransform' \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs
! rg -- '--resolution' \
  android/src/com/godot/game/GodotApp.java
rg 'CHANGE_FRAME_RATE_ALWAYS' \
  android/src/com/godot/game/HighRefreshRateController.java
rg 'preferredDisplayModeId' \
  android/src/com/godot/game/HighRefreshRateController.java
rg 'preferredRefreshRate' \
  android/src/com/godot/game/HighRefreshRateController.java
! rg 'SurfaceControl|setFixedSize|ViewportAttachToScreen' \
  android/src/com/godot/game/HighRefreshRateController.java \
  android/src/com/godot/game/GodotApp.java \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs

# payload zip 校验
tools/package/validate_payload_zip.py "/path/to/SlayTheSpire2.zip"

# 当前 public-beta v0.114.0 compile gate
REFERENCE_FLAVOR=original-v0.114.0 tools/android/build-port-mod.sh

# 旧 v0.110.x compile gate（历史 flavor，引用为 v0.110.1）
REFERENCE_FLAVOR=original-v0.110.0 tools/android/build-port-mod.sh

# 旧 v0.109.x compile gate（历史 flavor 名，CompatReferenceDir 指向 v0.109.1）
REFERENCE_FLAVOR=original-v0.109.0 tools/android/build-port-mod.sh

# v0.107.1 / v0.103.x / 旧 beta compile gate
REFERENCE_FLAVOR=original-v0.107.1 tools/android/build-port-mod.sh
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh
REFERENCE_FLAVOR=original-v0.106.1 tools/android/build-port-mod.sh
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh

# 查看/准备 GitHub 外部参考项目
tools/deps/prepare-external-projects.sh --list
tools/deps/prepare-external-projects.sh --group modding-reference

# 检查 Material Symbols vector drawable 是否已按当前 glyph 映射刷新
tools/android/generate-material-symbol-vectors.py --check

# standalone dotnet 编译也可显式传入 CompatReferenceDir
"$DOTNET_BIN" build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.114.0 \
  -p:DefineConstants=STS2_TARGET_1130 \
  -p:CompatReferenceDir="$STS2_ORIGINAL_V1140_REFERENCE_DIR" -v:q
```

## 13. ADB 自动化调试

连接设备后可用 `tools/debug/sts2-adb-debug.sh` 直接安装、配置、导入测试文件、执行启动准备、启动游戏并采集日志/性能 trace：

```bash
tools/debug/sts2-adb-debug.sh build-install
tools/debug/sts2-adb-debug.sh status --pull
tools/debug/sts2-adb-debug.sh prepare --mode compat --clear publish --pull
tools/debug/sts2-adb-debug.sh launch --mode perf --preload aggressive --logcat-duration 45 --perfetto 45 --pull
```

脚本默认使用 `run-as com.megacrit.sts2re` 把本地 payload/compat/MOD 文件推入 app 私有 `files/automation/inbox/<run_id>/`，再触发应用内 `DebugAutomationActivity` 复用正常导入/准备/启动逻辑。结果拉回 `.agent/debug/runs/<run_id>/`，设备侧结果保存在 `<files>/automation/runs/<run_id>/`。精确测试某个 MOD、某个 compat target、某组 preload 设置的示例见 [`adb-automation-debugging.md`](adb-automation-debugging.md)。

## 14. 构建后基本验证

```bash
adb install -r dist/sts2-re-importer.apk
adb shell run-as com.megacrit.sts2re ls files
adb shell run-as com.megacrit.sts2re ls files/compat-packs
adb shell run-as com.megacrit.sts2re ls files/.godot/mono/publish/arm64
```

首次启动应进入附加设置；导入 payload 后启动应在日志中看到：

- selected compatibility pack 信息；
- `Loading imported game PCK`；
- `STS2Mobile` build info；
- ordinary MOD scan from the current launch profile MOD root（全局 `<files>/mods` 或隔离 `<files>/instances/<profile_id>/mods`，如启用 MOD）。

Steam 相关验证可额外检查：

```bash
adb shell run-as com.megacrit.sts2re ls files/steam
adb shell run-as com.megacrit.sts2re ls files/steam/cloud
adb shell run-as com.megacrit.sts2re find files/payloads -maxdepth 3 -name .payload_manifest.json
adb shell dumpsys activity services com.megacrit.sts2re
```

Steam credential auth 必须用装有 Steam App 的实机做以下场景，不能只用“页面没崩”或小窗模式代替：

1. 输入账号密码并进入手机确认后，确认认证前台通知已出现、服务已经开始轮询；直接按 Home 或打开 Steam App 批准，再从最近任务返回启动器，应自动完成登录，不应要求先点“已批准”，也不应要求小窗/分屏。
2. 等待确认时反复前后台切换、旋转或让系统重建 `SteamAccountActivity`；`onStop()` 只应解绑 UI，通知和认证事务继续，返回后 UI 重新附着到当前状态。
3. BeginAuth 已创建 transaction handle 后切换 Wi-Fi/移动网络，或短暂断网再恢复；CM WebSocket 断开后应建立新连接并复用既有 client/request id 继续轮询，而不是要求重新输入密码。
4. 事务等待期间把应用放到后台后执行 `adb shell am kill com.megacrit.sts2re`，等待系统恢复服务或手动重新打开 Steam 中心；未过期 handle 应恢复轮询。`am force-stop` 是用户显式强停，Android 不保证立即重启服务，但重新打开应用后仍应恢复尚未过期的 handle。
5. 分别验证手机令牌动态码和邮箱验证码。Guard code 只用于当次进程内提交；若进程在提交前死亡，允许要求重新输入 code，但磁盘、Intent 和 logcat 中不得出现明文。提交成功进入轮询后，后续恢复不应再次需要密码。
6. 在等待确认/验证码时点取消，确认前台通知消失且 pending transaction 被清理，既有已登录账号不受影响；让事务超过默认 4 分钟 deadline，确认同样清理且不会继续后台轮询。
7. 取消旧登录后立刻开始新登录，并制造旧请求迟到成功；只有当前 transaction generation 可以原子提交 refresh token，旧结果不得覆盖新账号或新事务。
8. Android 13+ 拒绝通知权限后重复登录；系统可能不在通知栏展示普通通知，但认证状态和可恢复事务不应因此损坏，返回 Steam 中心仍可继续观察或取消。

验证期间同时检查 `adb logcat` 和 app 私有偏好中不存在账号密码、加密密码或本次 Guard code 明文。允许加密保存的内容只有既有登录材料与短期 transaction handle；认证成功必须在同一次 generation-matched commit 中写入 refresh token 并删除 handle，取消、过期或 handle 损坏只删除 pending 状态。
