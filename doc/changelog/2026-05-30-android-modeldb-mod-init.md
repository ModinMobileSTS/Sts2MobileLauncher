# 2026-05-30 Android MOD ModelDb 初始化时序对齐修复

## 背景

测试 `test2` MOD 组合（YuWanCard / Wuwancients / ExtraTFTCombat / 海克斯大乱斗）在 Android 端启动时显示“致命错误”。多轮日志排查后，最终崩溃点稳定为进入主菜单后打开单人游戏入口时：

```
System.TypeInitializationException: UnlockState
 ---> KeyNotFoundException: 'ENCOUNTER.YUWANCARD-KILLER_ELITE' was not present in the dictionary.
   at ModelDb.Get<KillerElite>()
   at ModelDb.Encounter<KillerElite>()
   at YuWanCard.Patches.GloryKillerPatch.Postfix(...)
```

## 根因

通过反编译 YuWanCard 与对照 BaseLib 源码确认：

- `KillerElite` 是 `EncounterModel` 子类，继承自 `YuWanEncounterModel`，实现 `IYuWanContent`。
- YuWanCard 用 `IdPrefixPatch`（`ModelDb.GetEntry` 的 Harmony postfix）给实现 `IYuWanContent` 的类型加命名空间前缀，BaseLib 的 `PrefixIdPatch` 对 `ICustomModel` 做同样的事。所以 `KillerElite` 的最终 entry 是 `YUWANCARD-KILLER_ELITE`。
- 关键：`IdPrefixPatch` 内部有 `IdCache`，每个 type **第一次** 调用 `GetEntry` 的结果会被**永久缓存**。

PC 上的时序保证（见 `OneTimeInitialization` / `ModManager`）：

1. `ExecuteVeryEarly` → `ModManager.Initialize` 中每个 MOD `TryLoadMod` 时立即 `PatchAll`，所有 MOD 的 `GetEntry` 前缀 patch 全部就位。
2. 之后 `ExecuteEssential` → `ModelDb.Init` 才第一次对各 type 调用 `GetEntry`，返回带前缀的 `YUWANCARD-KILLER_ELITE`，注册到正确 key。

旧兼容层 bug：在 MOD DLL 初始化**之前**（甚至在 `ModManager.Initialize` 内部）就调用 `ModelDb.GetId`/占位预注册。此时 MOD 的 `GetEntry` patch 还没应用，`IdPrefixPatch.IdCache` 缓存了无前缀的 `KILLER_ELITE`，从此终身错误；模型也被注册到错误 key。之后再多的事后 reconcile / 动态兜底都救不回，因为 MOD 内部缓存已污染。

## 改动（接近 PC 的根因修复）

不再做事后反应式修补，改为严格对齐 PC 时序：

- `port-mod/STS2AndroidPortCompat/Patches/ModLoaderPatches.cs`
  - 移除 MOD DLL 初始化前的 `EnsureModelPlaceholdersPreRegistered` 调用。在 MOD Harmony patch 全部应用前，绝不触碰 `ModelDb`（`GetId`/`GetEntry`/占位注册）。
- `port-mod/STS2AndroidPortCompat/Patches/ModelDbInitPatch.cs`（整体重写）
  - 删除所有早期 MOD 类型占位、assembly-load 占位、`ReconcileModelDbWithCurrentIds`、`GetId`/`GetId<T>`/`Get<T>` 动态兜底等反应式逻辑。
  - `ModelDb.Init` 仍替换为干净的 two-phase，并分两层处理占位：
    - 早期原版占位：`ExecuteEssential` 在 `LocManager.Initialize` 之前预注册仅原版 `AbstractModelSubtypes.All` 占位，解决 BaseLib post-mod-init act patching 在 `LocManager.Initialize` 期间提前触发 `BowlbugsNormal..cctor -> MONSTER.BOWLBUG_EGG`、`UnlockState..cctor -> ACT.OVERGROWTH`。原版类型不带前缀，提前 `GetId` 不会污染 MOD 的 `GetEntry` 缓存。
    - phase 1：进入 `ModelDb.Init` 后（MOD patch 已全部应用），按**最终** `ModelDb.GetId(type)`（GetEntry 前缀此时已生效，与 PC 一致）补齐全部模型（含 MOD 自定义类型）占位；已有原版占位跳过。
    - phase 2：在占位之上原地运行真实的静态/实例构造器。
  - 保留对用户/BaseLib/RitsuLib 的 `ModelDb.Init` prefix/postfix 生命周期（prefix `Priority.Last`，跑完后返回 false 跳过原版 one-pass body，postfix 照常运行），与 PC 顺序一致。
  - 保留构造前后清理 `ModelDb` 派生缓存与模型实例 `_all*` 缓存、BaseLib `CustomTargetType` enum 移动端兜底。
- `port-mod/STS2AndroidPortCompat/Patches/UnlockStateCompatPatches.cs`
  - 保留早期 `UnlockState` 静态初始化保护（`ModelDb.Acts` / `AllEncounters` 在 init 完成前对 `UnlockState..cctor` 提供空列表），作为防御性兜底。
  - 删除不再使用的 `TryGetCurrentlyRegisteredActs`。
- 更新 `AGENTS.md` 与 `doc/runtime/compat-pack-loading-flow.md` 中的运行时加载说明。

## 验证

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.106.1 -v:minimal
```

构建成功；保留既有 `QuickRestartPatches.cs` CS4014 warning。

```bash
tools/package/build_importer_apk.sh
```

构建成功，输出 `dist/sts2-re-importer.apk`；Gradle 保留既有 Groovy DSL deprecation warnings。

## 排查过程要点（供后续参考）

- 第一轮：原版 `MONSTER.BOWLBUG_EGG` / `ACT.OVERGROWTH` 早访问 → two-phase 占位。
- 第二/三/四/五轮：反复尝试 assembly-load 占位、postfix reconcile、`GetId`/`Get<T>` 动态兜底，均无法稳定修复 `ENCOUNTER.YUWANCARD-KILLER_ELITE`，反而越来越复杂。
- 第六轮：反编译 YuWanCard + 对照 BaseLib 源码，定位到根因是 `GetEntry` 前缀 patch 的 `IdCache` 被过早调用污染。改为对齐 PC 时序：MOD patch 全部应用后再做任何 id 相关工作。
- 第七轮（log7）：完全去掉早期占位后，连主菜单都进不了。日志显示 `BowlbugsNormal..cctor -> MONSTER.BOWLBUG_EGG`、`UnlockState..cctor -> ACT.OVERGROWTH` 在 `ModelDb.Init` 之前、`LocManager.Initialize` 期间由 BaseLib post-mod-init act patching 提前触发。区分关键：poisoning 只影响带前缀 patch 的 MOD 类型，原版类型提前 `GetId` 完全安全。改为两层：早期仅原版占位（`LocManager.Initialize` 之前），MOD 类型仍延迟到 `ModelDb.Init` phase 1。
- 第八轮（log8）：原版占位 1623 个成功，但出现两个新早访问，且都在我们的 `ModelDb.Init` 之前：
  - HextechRunes 的 initializer（`ExecuteVeryEarly` 的 MOD 加载阶段）用 Harmony patch `UnlockState.Relics` getter；Android/Mono 下 patch 一个 getter 会提前运行声明类型的静态构造，触发 `UnlockState..cctor -> Act<Overgrowth>` → `ACT.OVERGROWTH` 缺失（PC/CoreCLR 不会这么早触发）。此时原版占位（原在 `ExecuteEssential`）还未注册。
  - `wuwancients.HiddenSeaRecord..cctor` 构造静态 `UpgradeMap` 引用 `ModelDb.Relic<LongSnakeNecklace>()`（MOD 遗物），在 MOD 的 `ModelDb.Init` prefix（运行在我们 `Priority.Last` phase 2 之前）期间被提前触发，而 phase 1 还未补齐 MOD 占位。
  - 修正：（1）原版占位提前到**加载任何 MOD 之前**（`ModLoaderPatches`）；（2）把 phase 1（全部含 MOD 占位）从 `InitPrefix` 提前到 `ExecuteEssential` 调用 `ModelDb.Init()` **之前**，phase 2（构造器）仍在 `InitPrefix` 中运行。
- 第九轮（log9）：确认原版占位已在 MOD 加载前执行，但日志没有出现 phase 1；代码中残留了一个旧的 `ModelDb.Init()` 调用，导致仍然先进入 MOD `ModelDb.Init` prefixes，再触发 `HiddenSeaRecord..cctor -> RELIC.LONG_SNAKE_NECKLACE`。同时，YuWanCard 比 HextechRunes 更早加载时，HextechRunes patch `UnlockState.Relics` 提前触发 `UnlockState..cctor`，`ModelDb.AllEncounters` 会走 YuWanCard 的 `GloryKillerPatch` 并访问尚未注册的 `ENCOUNTER.YUWANCARD-KILLER_ELITE`；原来的 stack-trace-only guard 未生效。修正：删除残留的早期 `ModelDb.Init()`，确保 phase 1 先跑；`UnlockStateCompatPatches` 在 `ModelDb` 初始化完成前让 `AllEncounters` 直接返回空列表，并在初始化完成后重建可能提前创建的 `UnlockState.all`。由于 `UnlockState.all` 是 static readonly，修复包含 DynamicMethod `stsfld` 兜底以绕过 `FieldInfo.SetValue` 的 initonly 限制。
- 第十轮（log10）：已经能进主菜单、打开单人游戏并选择角色，但选择 YuWanCard 猪猪进入新 run 后黑屏。日志显示 `Player.PopulateStartingDeck -> CardModel.ToMutable -> CardModel.DeepCloneFields -> CardModel.Keywords` 中 `HashSet<CardKeyword>.UnionWith(CanonicalKeywords)` 参数为 null。结合日志发现 phase 1 已跑，但没有 phase 2 构造日志；YuWanCard 的 `ModelDb.Init` prefix 输出 `2398 skipped` 后可能返回 `false`，Harmony 因此前缀链跳过了我们的 `Priority.Last` `InitPrefix`，占位对象未运行实例构造器，BaseLib/YuWanCard card model 的构造器字段仍为 null。修正：给 `ModelDb.Init` 增加 `Priority.First` postfix 安全网，并在 `ExecuteEssential` 调用 `ModelDb.Init()` 后再次 idempotent 调用 `RunTwoPhaseModelDbInit()`，确保即使 MOD prefix 返回 `false`，phase 2 真实构造器仍会执行。另修复 `LifecycleAndPerformancePatches` 中 `Callable.From(async () => ...)` 返回 `Task` 导致 Godot Variant conversion 报错的问题，改为 void callable fire-and-forget。

## 注意事项

- 核心不变式：**在 MOD 的 Harmony patch 全部应用之前，不得对任何 MOD 模型类型调用 `ModelDb.GetId`/`GetEntry`**（会污染前缀缓存）。原版类型不带前缀，提前算 ID 安全。
- Android/Mono 与 PC/CoreCLR 的关键差异：Harmony patch 一个 member 会提前触发声明类型的静态构造。因此 MOD initializer patch 原版 getter 时，原版模型占位必须已经就位；这是原版占位提前到加载 MOD 之前的原因。
- 该修复只把 vanilla / MOD 间静态构造早访问问题用占位解决，自定义模型 ID 完全交给原版 `ModelDb.Init` + MOD 的 `GetEntry` patch 自然产生，不再人为迁移 key。
- 若后续仍出现致命错误，需要继续观察日志中是否有更深层的 MOD 版本不匹配、BaseLib patch 差异、依赖 MOD 被禁用或 MOD 加载顺序与 PC 不一致的问题；优先抓取从点击启动到进入菜单全程的日志。
