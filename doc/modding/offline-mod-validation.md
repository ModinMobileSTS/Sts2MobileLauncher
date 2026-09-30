# 开发机离线 MOD 验证流程

本流程用于排查普通 MOD 的 Harmony patch、目标工厂和 `ModelDb` 初始化时序问题，起点是 v0.1.9 升级到 v0.1.10 后的 Loadout 故障。优先在开发机定位纯托管侧问题，再到 Godot / Android 验证实际功能。

这里的“离线”指验证时不需要 Steam 登录、运行游戏或连接 Android 设备，不是 `offline-bootstrap/` 通用离线启动层的验证。首次准备 runtime、恢复 NuGet 包或下载 MOD 仍可能需要网络。

相关背景：[MOD 维护说明](mod-and-compat-notes.md)、[兼容包加载流程](../runtime/compat-pack-loading-flow.md)、[本地配置](../build/local-configuration.md)。

## 1. 工具与验证边界

| 层级 | 入口 | 能证明什么 |
| --- | --- | --- |
| 合成 Harmony 回归 | `port-mod/tools/test-deferred-mod-patch-queue.sh` | compat 的 direct patch、PatchAll、危险目标与模型目标工厂延迟、资源静态初始化/池冻结边界、prepare/cleanup、失败隔离和重复 flush 行为 |
| 原版模型 shadow 回归 | `port-mod/tools/test-modeldb-shadow-placeholder.sh` | 使用本机原版程序集，确认 generic/Type/ID/类别查询、canonical 优先级、字典隔离、错误语义和 phase 1 对象身份 |
| 静态 IL 检查 | 本地 `.agent/probes/loadoutinspect/` | 查看选定方法及 iterator 的 IL，寻找早期模型访问、Harmony 与 Godot 调用线索；不执行 MOD |
| 真实 Loadout 工厂 smoke | 本地 `.agent/probes/loadout-runtime/` | 三个实际 Loadout 工厂在早期被延迟，模型构造后重放，并在目标上安装指定 Harmony owner |
| 实际游戏验证 | Godot / Android 与 ADB | MOD 初始化、PCK/场景、玩法、音频、输入和 Android Mono/native 行为 |

前两个脚本随 `port-mod` submodule 维护。两个 `.agent/` 探针是本次调查的本地辅助工程，**不提交，也不会出现在新 clone 中**；下面的本地命令只有在这些工程和私有输入仍存在时才能直接运行。仓库目前没有通用 `test-real-mod.sh` 或自动读取 manifest、排序并批量执行所有 MOD 的 runner。

纯 .NET 验证不能证明 PCK 资源、Godot 节点生命周期、FMOD、窗口布局、触摸输入或完整游戏流程正常，也不能替代 Android Mono / native ABI 验证。Loadout 探针不是完整的 `ModManager.Initialize()` / MOD initializer 测试。

## 2. 准备输入

所有命令从**父仓库根目录**运行，不要在 `android/` 下执行，否则相对 `.agent/` 路径可能指向另一份目录。

1. 按[本地配置文档](../build/local-configuration.md)准备 `.env` / `local.properties`，配置 .NET 9 SDK、original references 和打包所用 runtime。
2. 准备匹配游戏版本的 `sts2.dll`、`GodotSharp.dll`、`0Harmony.dll` 及相邻依赖；不要混用其他版本游戏 DLL。Harmony 回归优先使用实际 APK 打包的 Ekyso Harmony / MonoMod 组合。
3. 从用户提供的包、Steam 或尖塔补给站准备目标 MOD 和前置，保留 manifest、DLL、PCK。检查 `id`、`version`、`min_game_version`、`dependencies`，记录来源和实际内容版本。补给站默认内容的游戏分支未验证，不能把下载成功当作版本兼容证据。
4. 私有输入、MOD 包、反编译输出和报告统一放在 ignored 的 `.agent/` 或仓库外，不提交商业游戏内容、第三方 MOD 二进制或账号材料。

加载机器配置：

```bash
source tools/env/load-local-config.sh
sts2_load_dotenv
```

`sts2_load_dotenv` 会把 `.env` 中相对仓库的工具链/reference 路径规范化。已有脚本会自行加载配置；裸跑本地 probe 时需要上面的步骤。

以下三个常用 target 的映射来自 `port-mod/targets/active/*/target.json`；其他版本也以对应 target 文件为准：

| 游戏引用 | `ReferenceFlavor` | `.env` reference 变量 | compile constant |
| --- | --- | --- | --- |
| v0.111.0 | `original-v0.111.0` | `STS2_ORIGINAL_V1110_REFERENCE_DIR` | `STS2_TARGET_1110` |
| v0.110.x，共享 target 使用 v0.110.1 引用 | `original-v0.110.0` | `STS2_ORIGINAL_V1100_REFERENCE_DIR` | `STS2_TARGET_1100` |
| v0.109.x，共享 target 使用 v0.109.1 引用 | `original-v0.109.0` | `STS2_ORIGINAL_V1090_REFERENCE_DIR` | `STS2_TARGET_1090` |

本地 `Loadout` probe 依赖现代 `ModManager.State` API，不能只替换 flavor 就声称支持所有旧游戏版本；需要先检查 runner 本身触达的 API。

**安全边界：**runtime probe 会执行第三方 MOD 的静态构造器、Harmony 工厂等代码，普通 .NET 进程不是沙箱。仅使用可信输入，并与真实存档、账号凭据隔离；不要通过捕获所有异常或伪造 Godot 返回值把失败包装成通过。

## 3. 先运行 compat 回归

### 3.1 合成 Harmony 回归

先准备 `android/assets/dotnet_bcl/` 中的打包 runtime；缺失时按[构建文档](../build/building-and-packaging.md)执行 `tools/android/sync-runtime-from-references.sh`。

```bash
env LD_PRELOAD=libgcc_s.so.1 \
  port-mod/tools/test-deferred-mod-patch-queue.sh \
  --reference-dir android/assets/dotnet_bcl
```

该 fixture 不包含商业游戏代码。通过条件包括：危险 UI / 模型 `.cctor`、读取模型或消费池的资源 `.cctor` 及枚举 ModelDb 的工厂不能提前执行；安全目标和池注册仍即时生效，真正消费池后仍拒绝迟到注册；重放保留 Harmony 元数据及逐目标 prepare/cleanup；单项失败不阻断后续项；重复 flush 不重复应用。

脚本成功输出：

```text
DeferredModPatchQueue PatchAll regression test passed.
```

fixture 中有故意失败的 patch 用于验证隔离，不能只因日志出现异常文字就判定整组失败；看进程退出状态与断言结果。

### 3.2 原版 ModelDb shadow 回归

`test-modeldb-shadow-placeholder.sh` 只传 `CompatReferenceDir`，不传 target 编译常量。针对 v0.111.0，直接运行同一个 runner，显式指定 flavor、引用目录与编译常量；仅把 helper 的 `--reference-dir` 换成新版本目录，会因选用旧 API 分支而编译失败。`ReferenceFlavor` 本身也不会自动设置 `DefineConstants`。

```bash
env LD_PRELOAD=libgcc_s.so.1 \
  "$DOTNET_BIN" run \
  --project port-mod/tests/ModelDbShadowPlaceholder.Tests/Runner/Runner.csproj \
  -c Release \
  -p:ReferenceFlavor=original-v0.111.0 \
  -p:DefineConstants=STS2_TARGET_1110 \
  "-p:CompatReferenceDir=$STS2_ORIGINAL_V1110_REFERENCE_DIR"
```

检查点：

- `during_mod_initialization` 与 `before` 一致，canonical `_contentById` 没有因 early shadow 增长。
- vanilla `Get<T>()`、`Get(Type)`、`GetById<T>` / `GetByIdOrNull<T>` 与类别 getter 能读取正确 shadow，跨类别与基类查询不混淆共享泛型上下文。
- canonical 已有值优先，其他实例/泛型参数的字典不暴露 shadow；null key、缺失 ID、错误类别与非模型类型保留原版错误语义。
- phase 1 发布全部原版模型后，各查询仍返回同一对象；删除 canonical 条目后不再回落到 stale shadow。

这个回归只验证 shadow 与 phase 1 发布，**不执行完整 phase 2 构造，也不加载用户 MOD**。phase 2 与真实工厂的组合由下一节的定向 smoke 验证。

桌面 Mono 的原版模型构造器/基类 `.cctor` 可能创建 `Godot.StringName`，因此完整 phase 2 必须在已初始化 Godot native 的宿主中验证；无宿主进程在 `godotsharp_string_name_new_from_string` 等空 native 调用处崩溃，不能计为通过，也不能用 no-op 替代。early shadow、phase 1 和合成资源/池边界可独立验证，但它们不证明 Android 上的完整构造或 MOD 功能。

Linux 上 `LD_PRELOAD=libgcc_s.so.1` 用于本机 Ekyso Harmony 验证；不是 Android 配置，也不要据此修改 APK native 环境。

## 4. 静态检查目标 MOD

保留本次本地 Cecil 工程的工作区可运行：

```bash
"$DOTNET_BIN" run \
  --project .agent/probes/loadoutinspect/LoadoutInspect.csproj \
  -- .agent/probes/loadout/loadout2/Loadout.dll \
  'TargetMethods|MoveNext|Initialize|\.cctor'
```

这个工具按**方法完整名称**匹配正则，再输出命中方法的全部 IL，并遍历嵌套类型；它不是按 IL 操作数搜索全 DLL。仅用 `ModelDb|Harmony|PatchAll` 作为方法名过滤可能漏掉名称无关、但方法体实际调用这些 API 的方法。

重点查看：

- `HarmonyTargetMethods` / `HarmonyTargetMethod` 与对应 `TargetMethods` / `TargetMethod`。
- iterator 的嵌套状态机 `MoveNext`：ModelDb 访问可能发生在枚举时，而非工厂返回时。
- `Initialize()`、`.cctor`、静态字段初始化及它们调用的 helper。
- `ModelDb.AllCards` / `AllRelics` / `Get` / `GetById`、`Harmony.PatchAll()`、Godot API 等调用。

扩大检查范围时可把最后的正则改为 `'.*'`，再在保存的输出中定位调用点。静态证据只用于选择待验证路径：没有命中不代表没有风险，发现调用也不代表必然失败。

该工程的 `Mono.Cecil` 引用来自 `android/assets/dotnet_bcl/Mono.Cecil.dll`。若本地工程缺失，可在 `.agent/` 下建立只读 Cecil 检查工程，按方法/调用点和嵌套 iterator 检查上述路径；不要把这里的临时工程当作仓库承诺的一键扫描工具。

## 5. 真实 Loadout 工厂 smoke

### 5.1 本次输入与运行命令

本地工程引用当前 `port-mod/STS2AndroidPortCompat/STS2Mobile.csproj`、original `sts2` / `GodotSharp` / `0Harmony`，以及：

- `.agent/probes/loadout/baselib/BaseLib/BaseLib.dll`：manifest 版本 `v3.4.7`。
- `.agent/probes/loadout/loadout2/Loadout.dll`：manifest 版本 `v0.5.8`，要求 BaseLib `>= 3.3.5`。

BaseLib 作为 Loadout 的依赖引用存在，探针**不调用 BaseLib 或 Loadout 的完整 initializer**。

```bash
env LD_PRELOAD=libgcc_s.so.1 \
  "$DOTNET_BIN" run \
  --project .agent/probes/loadout-runtime/Probe.csproj \
  -c Release \
  -p:ReferenceFlavor=original-v0.111.0 \
  -p:DefineConstants=STS2_TARGET_1110 \
  "-p:CompatReferenceDir=$STS2_ORIGINAL_V1110_REFERENCE_DIR"
```

### 5.2 执行顺序与通过条件

1. 安装 `ModelDbInitPatch`，通过 `EnsureVanillaModelPlaceholdersPreRegistered()` 准备 early vanilla shadow。
2. 安装 `DeferredModPatchQueue`。
3. 在 `BeginModInitialization(...)` 窗口内，对三个实际 Loadout patch class 调用 `Harmony.CreateClassProcessor(type).Patch()`。
4. 确认 canonical `_contentById` 仍为空，没有因早期工厂访问被填充。
5. 探针显式将现代原版 `ModManager.State` 设为 `Initialized`，这是局部测试前提，不是执行真实 MOD loader。
6. 调用 `RunPhase1PreRegistration()`、`RunTwoPhaseModelDbInit()`，再 `FlushDeferredPatches(...)`。
7. 枚举三个工厂的目标，用 `Harmony.GetPatchInfo(target).Owners` 检查目标具有本次 smoke 的 Harmony owner；缺失时失败。

v0.111.0 与上述 MOD 版本的本次记录：

```text
AFTER: actual Loadout processor captured without early ModelDb reads
AFTER: Loadout.Keywords.LoadoutBasicKeywordGainsBlockPatch: 81 installed targets
AFTER: Loadout.Keywords.PostOnPlayKeywordDispatcher: 562 installed targets
AFTER: Loadout.Keywords.XCostOnPlayPatch: 562 installed targets
AFTER: 3 actual Loadout factories, 1205 installed target jobs
```

`1205` 是各工厂目标任务的累加，**不是去重的方法数量，也不是所有版本必须相同的常数**。更新游戏/MOD 后应保留逐目标 owner 检查、阶段断言和异常日志，而不是只看数量或“没有抛异常”。此 smoke 不调用已安装的 Loadout patch 去验证实际出牌效果。

探针还保留一个 `before` 诊断分支，它刻意不安装 deferred queue，用于演示 early factory 的失败；这个分支捕获并打印异常后退出，不能拿它的退出码当回归通过条件。

## 6. 扩展到其他 MOD 或多组 MOD

没有 `.agent/` 工程时，需要先建立针对目标路径的本地 probe；不能直接执行上述临时命令。不要只把 Loadout DLL 名替换成另一 MOD 后继续使用旧 patch class 列表。

1. 为依赖集合引用匹配版本的 DLL，确认缺失依赖与重复 assembly identity。引用 DLL 不等于已经按原版顺序执行前置 initializer。
2. 从静态检查结果选定真实 patch class / 目标工厂，按上一节的窗口、模型阶段和 flush 顺序构造局部 smoke。保留 canonical 状态与最终逐目标 patch 检查。
3. 若要验证 initializer，则按原版依赖顺序执行前置和目标 MOD 的真实入口；一旦需要 Godot native、场景或资源，转到真实 Godot / Android 验证，不用 no-op mock 跳过后宣称成功。
4. **每个 MOD 依赖集合、游戏版本与对照条件使用独立进程。**`ModelDb` 静态状态、Harmony patches 和失败的 `.cctor` 都可能污染下一组；只 `UnpatchAll` 不会恢复全部状态。
5. 把失败分类记录为依赖/程序集解析、目标 API 不匹配、早期工厂访问、重放失败或需要 Godot；这些是人工判读类别，不是现有 runner 的自动输出状态。保留最内层异常与具体 patch class / target。

建议每次报告至少包含：游戏版本与 `sts2.dll` SHA、compat 源码版本及是否有本地修改、Harmony/MonoMod 来源、MOD/前置版本与文件摘要、下载来源/分支信息、命令和退出状态、canonical 状态、逐工厂结果、异常、未覆盖项目。完整本地报告放 `.agent/reports/`；公开文档不复制商业 IL、私有路径或凭据。

## 7. 回到构建与实际游戏验证

若修改 compat 源码，仍需经过所有 active target compile gate，不能用单版本 probe 替代；完整 importer 打包会构建 active target matrix：

```bash
tools/package/build_importer_apk.sh
```

默认测试 APK 为 `dist/sts2-re-importer.apk`。具体环境、输出与打包流程见[构建文档](../build/building-and-packaging.md)。

连接设备后按 [ADB 自动化文档](../build/adb-automation-debugging.md)安装、导入目标 MOD 及全部前置、准备并启动匹配 payload/profile，检查实际入口和至少一次相关玩法行为。需要证明 Loadout 功能恢复时，还要在游戏中实际使用受影响的 keyword / 出牌路径，而不止看到 MOD 名称或启动到主菜单。

结论应分开记录为“合成回归通过”“真实目标工厂 smoke 通过”“实际 Godot / Android 功能通过”。没有设备或没有执行游戏路径时，明确写未验证，不能合并成“MOD 在 Android 上完全兼容”。
