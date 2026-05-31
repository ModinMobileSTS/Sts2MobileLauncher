# 2026-05-31 关闭预加载后主菜单黑屏修复

## 背景

用户反馈 `v0.103.2` 与 `v0.106.1` 两个内置兼容包在关闭预加载后启动会卡黑屏。日志中主菜单已经创建完成，但随后出现：

```text
System.ObjectDisposedException: Cannot access a disposed object.
Object name: 'Godot.ShaderMaterial'.
  at Godot.ShaderMaterial.SetShaderParameter(...)
  at MegaCrit.Sts2.Core.Nodes.NTransition.FadeIn(...)
```

## 原因

原版 `NTransition.FadeIn/FadeOut` 会从 `PreloadManager.Cache.GetMaterial("res://materials/transitions/fade_transition_mat.tres")` 取得共享缓存材质，并把该同一个 `ShaderMaterial` 赋给 transition 节点。

当关闭预加载时，主菜单 ready 后原版 deferred startup 仍会调用 `PreloadManager.LoadCommonAndMainMenuAssets()`。该流程一开始会执行 `Cache.UnloadMissedCacheAssets()`，把启动过程中 miss 的缓存资源从 cache 移除并 deferred dispose。`fade_transition_mat.tres` 正好可能被标记为 missed-cache，此时正在执行主菜单 `NGame.Instance.Transition.FadeIn(3f)` 的 async task 仍持有同一个共享 `ShaderMaterial`，于是下一帧 `SetShaderParameter()` 命中已 dispose 对象，transition 黑幕无法淡出。

## 改动

- 新增 `port-mod/STS2AndroidPortCompat/Patches/TransitionMaterialPatches.cs`：
  - `NTransition._Ready` 后复制场景默认 `ShaderMaterial`，避免节点持有可被其他流程共享/释放的材质实例。
  - patch `AssetCache.GetMaterial()`，当请求 `fade_transition_mat.tres` 或 `fight_transition_mat.tres` 时返回缓存材质的副本，而不是共享实例。
- 在 `ModEntry` 中启用该 patch，位置放在 shader overlay 之后、输入/UI patch 之前。
- 更新 `tools/android/stage-bundled-compat-packs.sh` 的跨分支注入列表，让 `v0.103.2` 临时 worktree 构建内置包时也获得同一修复。
- 更新运行时/构建文档和 `AGENTS.md`。

## 验证

- `dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original-v0.106.1 -v:q`
- `dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original -v:q`
- `tools/package/build_importer_apk.sh`

## 注意

该修复不改变普通资源预加载开关语义：关闭预加载时仍跳过 deferred preload，只是 transition 节点不再复用可能被 missed-cache 清理 dispose 的共享材质。两个内置兼容包都需要随 APK 重新构建/安装后才会生效。
