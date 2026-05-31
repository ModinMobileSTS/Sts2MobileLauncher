# 2026-05-31 Android deferred preload Callable 返回值修复

## 背景

设备日志中主菜单后出现 Godot C# Variant 转换错误：

```text
System.InvalidOperationException: The type is not supported for conversion to/from Variant: 'System.Threading.Tasks.Task'
```

原因是 `LifecycleAndPerformancePatches.StartSafeDeferredPreload()` 使用了表达式体 lambda：

```csharp
Callable.From(() => _ = RunSafeDeferredPreloadAsync(reason, owner)).CallDeferred();
```

虽然看起来是 fire-and-forget，但该表达式仍会被推断为返回 `Task`，Godot `Callable` 尝试把 `Task` 转成 Variant 时会报错。

## 改动

- 改为 statement-bodied void lambda：

```csharp
Callable.From(() =>
{
    _ = RunSafeDeferredPreloadAsync(reason, owner);
}).CallDeferred();
```

- `tools/android/stage-bundled-compat-packs.sh` 同步 `LifecycleAndPerformancePatches.cs` 到非当前分支临时 worktree，确保 v0.103.2 与 v0.106.1-beta 内置兼容包都带有该修正。

## 验证

- `../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original-v0.106.1 -v:q`
- `tools/package/build_importer_apk.sh`
  - 输出：`dist/sts2-re-importer.apk`
  - APK sha256：`25f6c045db2ac990893f5bd4006095edb36bbe7388449edb1c255aa1226f591f`
  - 内置兼容包：
    - `android/assets/compat_packs/sts2-android-compat-v0.103.2.zip`：`f00b3a8a64f7f4abbe4b76248f2ff4592207c371dc5a1fc41ff41352ddc08887`
    - `android/assets/compat_packs/sts2-android-compat-v0.106.1-beta.zip`：`ba9a6e63e8c29d2fafa402db70bf2c3496d366a140b2637a8ec537137d2a9b33`

## 注意事项

- 这是日志错误修复，不改变 deferred preload 的实际资源加载顺序。
- 设备端应不再出现 `Task` 到 Variant 的转换错误。
