# Code Context

## Files Retrieved
1. `../s2/.cache/StS2-Launcher_Mod_Manager/README.md` (lines 189-289) - high-level runtime model, project structure, build/runtime notes.
2. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/ModEntry.cs` (lines 1-122) - managed entry point, patch registration, standalone-launcher fallback.
3. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/AppPaths.cs` (lines 1-88) - external storage roots and Android permission bridge.
4. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherUI.cs` (lines 1-199) - programmatic Godot launcher shell and main-thread dispatch.
5. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherView.cs` (lines 1-293) - UI layout/sections; Save Manager button replaces Mod Manager navigation.
6. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/Sections/ActionSection.cs` (lines 1-181) - launch/update/cloud action buttons and busy locking.
7. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/Sections/DownloadSection.cs` (lines 1-62) - game download button/progress UI.
8. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/Sections/LoginSection.cs` (lines 1-57) - Steam username/password UI.
9. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/Sections/CodeSection.cs` (lines 1-48) - Steam Guard 2FA UI.
10. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/Sections/ModManagerSection.cs` (lines 1-341) - WIP mod manager, SAF zip import pipeline, UI state.
11. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherController.cs` (lines 1-879) - launcher UI state machine; download/update/login/cloud actions.
12. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherModel.cs` (lines 1-545) - session/auth/download/update/body-file state.
13. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/CacheStamp.cs` (lines 1-84) - downloaded payload metadata stamp.
14. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/SessionState.cs` (lines 1-25) - launcher state enums.
15. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/DepotDownloader.cs` (lines 1-946) - SteamKit2 CDN depot downloader, manifest/cache, PCK patching.
16. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/SteamAuth.cs` (lines 1-203) - interactive Steam login / refresh token / 2FA.
17. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/SteamConnection.cs` (lines 1-338) - WebSocket SteamKit2 connection lifecycle and Cloud RPC bridge.
18. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/SteamCredentialStore.cs` (lines 1-138) - encrypted credential persistence via Android Keystore bridge.
19. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/OwnershipVerifier.cs` (lines 1-106) - app ownership check and encrypted marker.
20. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/SteamBranchInfo.cs` (lines 1-12) - branch picker data type.
21. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/SteamKit2CloudSaveStore.cs` (lines 1-495) - Steam Cloud save store, upload/download, batching.
22. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/CloudFileCache.cs` (lines 1-250) - Steam Cloud metadata cache and safety gate.
23. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/CloudSyncCoordinator.cs` (lines 1-438) - auto/manual cloud sync and backups.
24. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/CloudSyncDecisions.cs` (lines 1-412) - pre-PLAY local/cloud conflict classification.
25. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/AppUpdateChecker.cs` (lines 1-145) - launcher APK GitHub release check.
26. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/AppUpdateInstaller.cs` (lines 1-122) - launcher APK download/install bridge.
27. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Modding/ModImporter.cs` (lines 1-209) - mod zip safe extraction/normalization/delete.
28. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Modding/SafBridge.cs` (lines 1-112) - C# bridge to Android SAF picker.
29. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Modding/ModScanner.cs` (lines 1-55) - installed mod folder scanner.
30. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Modding/ModConfig.cs` (lines 1-124) - mod enable/order config persistence.
31. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Modding/ModManifest.cs` (lines 1-62) - `mod_manifest.json` schema parsed by launcher.
32. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/LauncherPatches.cs` (lines 1-582) - intercepts game startup, cloud save injection, save conflict dialog flow.
33. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/ModLoaderPatches.cs` (lines 1-67) - redirects game mod loader to external mods dir.
34. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/PlatformPatches.cs` (lines 1-78) - disables desktop Steam/Sentry/platform features.
35. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/AppLifecyclePatches.cs` (lines 1-270) - background/quit cloud flush and app restart hook.
36. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/BaseLibCompatPatches.cs` (lines 1-77) - mobile BaseLib workaround; disables async hook emitter.
37. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/ReleaseInfoPatches.cs` (lines 1-111) - Android fallback for `release_info.json`.
38. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/PatchHelper.cs` (lines 1-98) - Harmony patch helper / logging.
39. `../s2/.cache/StS2-Launcher_Mod_Manager/android/src/com/game/sts2launcher/modmanager/GodotApp.java` (lines 1-1203) - Android Activity bridge, PCK selection, assembly setup, SAF, permissions, APK install.
40. `../s2/.cache/StS2-Launcher_Mod_Manager/android/AndroidManifest.xml` (lines 1-71) - permissions/package/activity config.
41. `../s2/.cache/StS2-Launcher_Mod_Manager/android/build.gradle` (lines 1-292) - Godot Android/Mono Gradle build, assets/libs packaging.
42. `../s2/.cache/StS2-Launcher_Mod_Manager/android/gradle.properties` (lines 1-42) - package id, ABI, version/signing settings.
43. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/STS2Mobile.csproj` (lines 1-24) - patcher references (`0Harmony`, `GodotSharp`, `sts2.dll`) and SteamKit2 package.
44. `../s2/.cache/StS2-Launcher_Mod_Manager/scripts/setup-deps.sh` (lines 1-185) - build-time dependency harvesting from APK/templates/game files.
45. `../s2/.cache/StS2-Launcher_Mod_Manager/scripts/build.sh` (lines 1-66) - publish patcher, copy assets, build APK.
46. `../s2/.cache/StS2-Launcher_Mod_Manager/scripts/make-bootstrap-pck.py` (lines 1-111) - minimal bootstrap PCK generator.
47. `../s2/.cache/StS2-Launcher_Mod_Manager/src/stubs/steam_stub.c` (lines 1-160) - no-op Steamworks native symbols for Android linking.
48. `../s2/.cache/StS2-Launcher_Mod_Manager/src/stubs/sentry_stub.c` (lines 1-12) - no-op Sentry GDExtension stub.
49. `../s2/.cache/StS2-Launcher_Mod_Manager/src/stubs/build_stubs.sh` (lines 1-12) - NDK build commands for native stubs.

## Key Code

### 1) Runtime entry and launcher modes
`README.md` says the launcher is loaded at startup by the custom Godot/Mono path and intercepts game startup: `STS2Mobile.dll` is loaded via `coreclr_create_delegate`; no game files => minimal `bootstrap.pck`; game files present => normal mode with patches against `sts2.dll` (`README.md` lines 189-194).

`ModEntry.Apply()` is the managed root. It always applies game-independent diagnostics, then tries game patches; if `sts2.dll`/game types are unavailable, it schedules a standalone launcher:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/ModEntry.cs:51-102
[UnmanagedCallersOnly]
public static void Apply()
{
    if (_applied)
        return;
    _applied = true;

    PatchHelper.Log("Initializing STS2Mobile...");
    _harmony = new Harmony("com.sts2mobile");
    RenderDiagnosticPatches.Apply(_harmony);

    try
    {
        BaseLibCompatPatches.Apply(_harmony);
        ...
        ModLoaderPatches.Apply(_harmony);
        LauncherPatches.Apply(_harmony);
        ...
    }
    catch (Exception ex)
    {
        PatchHelper.Log($"Game patches skipped (files not present): {ex.Message}");
        ScheduleStandaloneLauncher();
    }
}
```

Android selects which PCK Godot boots:

```java
// ../s2/.cache/StS2-Launcher_Mod_Manager/android/src/com/game/sts2launcher/modmanager/GodotApp.java:698-715
public List<String> getCommandLine() {
    List<String> commands = new ArrayList<>(super.getCommandLine());
    File pckFile = new File(gameDir, PCK_FILE);
    if (pckFile.exists()) {
        commands.add("--main-pack");
        commands.add(pckFile.getAbsolutePath());
    } else {
        String bootstrapPck = extractBootstrapPck();
        if (bootstrapPck != null) {
            commands.add("--main-pack");
            commands.add(bootstrapPck);
        }
    }
    return commands;
}
```

### 2) 本体管理 / download/update state
There is **no SteamCMD implementation** in this repo; runtime body management is in-app SteamKit2. Grep found `SteamCMD/steamcmd` only absent except task wording; the actual path is `LauncherModel` -> `DepotDownloader`.

Session fast path is based on saved refresh token, encrypted ownership marker, and a minimal PCK magic check:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherModel.cs:81-103
public FastPathResult StartSession()
{
    OfflineMode = false;
    ConnectionResolved = false;
    _credentialStore.Load();
    ...
    var hasMarker = verifier?.HasMarker() ?? false;
    if (_credentialStore.HasCredentials && hasMarker && GameFilesReady())
        return FastPathResult.ReadyToLaunch;
    if (_credentialStore.HasCredentials)
        return FastPathResult.AutoConnect;
    return FastPathResult.ShowLogin;
}
```

Downloads instantiate `DepotDownloader`, persist a branch/build stamp, and fire progress events:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherModel.cs:217-240
public async Task StartDownloadAsync(string branch = null)
{
    await EnsureConnectedAsync();
    ...
    _downloader = new DepotDownloader(_connection, _dataDir);
    _downloader.LogMessage += msg => DownloadLogReceived?.Invoke(msg);
    _downloader.ProgressChanged += p => DownloadProgressChanged?.Invoke(p);
    ...
    await Task.Run(() => _downloader.DownloadAsync(resolvedBranch, _downloadCts.Token));
    WriteCacheStampAfterDownload(resolvedBranch, _downloader.LastDownloadedBuildId);
    DownloadCompleted?.Invoke();
}
```

`GameFilesReady()` only validates the `SlayTheSpire2.pck` magic bytes:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherModel.cs:408-424
public static bool GameFilesReady()
{
    var pckPath = Path.Combine(OS.GetDataDir(), "game", "SlayTheSpire2.pck");
    ...
    return magic[0] == 0x47 && magic[1] == 0x44 && magic[2] == 0x50 && magic[3] == 0x43;
}
```

Branch switching wipes only `game/`, `download_state/`, and `.cache_stamp`:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherModel.cs:512-526
public void WipeGameFiles()
{
    var gameDir = Path.Combine(_dataDir, "game");
    var stateDir = Path.Combine(_dataDir, "download_state");
    if (Directory.Exists(gameDir)) Directory.Delete(gameDir, recursive: true);
    if (Directory.Exists(stateDir)) Directory.Delete(stateDir, recursive: true);
    CacheStamp.Delete();
}
```

`DepotDownloader` replaces a SteamCMD-style body manager:
- fetch app info via PICS (`DepotDownloader.cs` lines 206-226),
- parse Windows depots and branch manifest IDs (`DepotDownloader.cs` lines 229-293),
- load CDN servers and download each depot (`DepotDownloader.cs` lines 93-139),
- obtain depot key and manifest request code (`DepotDownloader.cs` lines 390-435),
- diff old/new manifests, delete removed files, download changed/missing/corrupt files (`DepotDownloader.cs` lines 440-516, 686-742),
- verify SHA-1 for chunks/files and commit temp files (`DepotDownloader.cs` lines 520-654),
- persist cached manifest IDs under `download_state` (`DepotDownloader.cs` lines 758-791),
- patch `SlayTheSpire2.pck` in-place to disable Sentry (`DepotDownloader.cs` lines 817-918).

Critical constants:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/DepotDownloader.cs:22-29
private const uint AppId = 2868840;
private const int MaxRetries = 5;
private const int MaxConcurrentDownloads = 8;
```

### 3) Steam auth, ownership, credential storage
Steam auth is direct SteamKit2, not shelling out:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Steam/SteamAuth.cs:76-110
public async Task<AuthResult> LoginWithCredentialsAsync(string username, string password, string guardData)
{
    ...
    var authSession = await _client.Authentication.BeginAuthSessionViaCredentialsAsync(
        new AuthSessionDetails { Username = username, Password = password, IsPersistentSession = true, GuardData = guardData, Authenticator = new AuthAuthenticator(this) });
    var pollResponse = await authSession.PollingWaitForResultAsync();
    return new AuthResult(pollResponse.AccountName, pollResponse.RefreshToken, newGuardData);
}
```

Connection uses SteamKit2 WebSocket (`SteamConnection.cs` lines 90-145). Ownership is checked by `PICSGetAccessTokens` for app `2868840` and cached as an encrypted marker (`OwnershipVerifier.cs` lines 41-55). Credentials are encrypted/decrypted via Java `GodotApp.encryptString/decryptString` and saved in `steam_credentials.enc` (`SteamCredentialStore.cs` lines 24-80). Java uses Android Keystore AES-256-GCM (`GodotApp.java` lines 826-887).

### 4) Android files, assemblies, and external paths
External paths are hardcoded:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/AppPaths.cs:15-19
public const string ExternalRoot = "/storage/emulated/0/StS2LauncherMM";
public const string ExternalModsDir = ExternalRoot + "/Mods";
public const string ExternalSaveBackupsDir = ExternalRoot + "/Saves";
public const string ExternalLogsDir = ExternalRoot + "/Logs";
public const string ExternalModConfigFile = ExternalModsDir + "/mod_config.json";
```

Android requires broad external storage for mods/backups/logs (`AndroidManifest.xml` lines 12-22) and exposes Java bridge methods `hasStoragePermission()` / `requestStoragePermission()` (`GodotApp.java` lines 889-916).

`GodotApp.setupAssemblies()` copies APK `dotnet_bcl` assets and then copies downloaded game assemblies from `files/game/data_*` into `files/.godot/mono/publish/arm64`, BCL-protecting `System.*`/Mono files and refreshing game DLLs by size+mtime:

```java
// ../s2/.cache/StS2-Launcher_Mod_Manager/android/src/com/game/sts2launcher/modmanager/GodotApp.java:557-647
private void setupAssemblies() {
    File srcDir = findAssembliesDir();
    File destDir = new File(getFilesDir(), ".godot/mono/publish/arm64");
    ...
    String[] bclFiles = getAssets().list("dotnet_bcl");
    ...
    if (!srcDir.exists() || !srcDir.isDirectory()) return;
    ...
    if (dest.exists() && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) {
        skipped++;
        continue;
    }
    copyFile(src, dest);
}
```

It finds the assembly dir by first child starting with `data_`, falling back to `data_sts2_windows_x86_64` (`GodotApp.java` lines 653-667).

### 5) 导入/解压 / mod install pipeline
The mod manager UI is present but currently not reachable from the main launcher button: `LauncherView` labels it `SAVE MANAGER`, and `LauncherController.OnModManagerPressed()` opens cloud Save Manager; original `_view.ShowModManager()` is commented out (`LauncherView.cs` lines 119-129; `LauncherController.cs` lines 250-272).

When wired, the import pipeline is:
1. UI button runs `RunImportPipelineAsync()` on a worker thread (`ModManagerSection.cs` lines 193-235).
2. `SafBridge.PickZipsToCacheAsync()` calls Java `openZipPicker`, polls `isPickerActive`, then consumes newline-separated cached zip paths (`SafBridge.cs` lines 11-90).
3. Java `ACTION_OPEN_DOCUMENT` copies selected URI(s) into app cache as `mod_import_<ts>_<i>.zip` (`GodotApp.java` lines 917-1016).
4. `ModImporter.ImportZipAsync()` extracts, normalizes, validates, copies to `ExternalModsDir/<id>`, and updates `mod_config.json` (`ModImporter.cs` lines 23-88).

Key extraction safety:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Modding/ModImporter.cs:112-142
private static void SafeExtract(string zipPath, string destRoot)
{
    var fullRoot = Path.GetFullPath(destRoot);
    using var archive = ZipFile.OpenRead(zipPath);
    foreach (var entry in archive.Entries)
    {
        var target = Path.GetFullPath(Path.Combine(fullRoot, entry.FullName));
        AssertWithin(fullRoot, target);
        ...
        entry.ExtractToFile(target, overwrite: true);
    }
}
```

Accepted mod root layouts: manifest at zip root, one wrapper folder, or first recursive `mod_manifest.json` found (`ModImporter.cs` lines 91-109). Valid mod IDs allow letters/digits/`_-.` only (`ModImporter.cs` lines 154-164). Delete removes the folder and config entry (`ModImporter.cs` lines 166-184).

`ModManifest` schema includes `id`, `name`, `author`, `description`, `version`, `has_pck`, `has_dll`, `dependencies`, `affects_gameplay` (`ModManifest.cs` lines 8-39). `ModScanner` only scans subfolders with parseable `mod_manifest.json` (`ModScanner.cs` lines 6-31). `ModConfig` stores enabled/order state under external mods dir (`ModConfig.cs` lines 10-124).

Important risk: `ModConfig.Enabled/Order` appears **not consumed by the runtime loader** in this repo. Search shows only UI/import/delete use it; `ModLoaderPatches` redirects the game’s own scan to the whole external directory without filtering disabled mods.

### 6) 启动 / patch / mod loader pipeline
`LauncherPatches` is the core launch interception:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/LauncherPatches.cs:32-58
public static void Apply(Harmony harmony)
{
    PatchHelper.PatchCritical(harmony, typeof(NGame), "GameStartupWrapper", prefix: ...GameStartupWrapperPrefix);
    PatchHelper.Patch(harmony, typeof(SaveManager), "ConstructDefault", prefix: ...ConstructDefaultPrefix);
    PatchHelper.PatchCritical(harmony, typeof(CloudSaveStore), "SyncCloudToLocal", prefix: ...SyncCloudToLocalPrefix);
}
public static bool GameStartupWrapperPrefix(object __instance, ref Task __result)
{
    __result = RunLauncherThenGame(__instance);
    return false;
}
```

`RunLauncherThenGame()` creates launcher UI inside the game scene, waits for PLAY, disposes launcher, preloads cloud cache / handles conflict, warms shaders, calls `SaveManager.Instance.InitSettingsData()`, then reflects and invokes the original private `GameStartup()` (`LauncherPatches.cs` lines 160-253).

Cloud save injection happens in `ConstructDefaultPrefix()` only when credentials exist and cloud file cache is proven loaded; otherwise it falls back local-only to avoid destructive default-save uploads (`LauncherPatches.cs` lines 62-143). `SyncCloudToLocalPrefix()` redirects each file to `CloudSyncCoordinator.AutoSyncFileAsync()` (`LauncherPatches.cs` lines 146-156). Manual Save Manager uses `OpenSaveSyncDialogAsync()` and `CloudSyncDecisions.DetermineAsync()` (`LauncherPatches.cs` lines 266-345).

Mod loader patch is small and fragile by design:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/ModLoaderPatches.cs:17-67
public static void Apply(Harmony harmony)
{
    PatchHelper.Patch(harmony, typeof(ModManager), "Initialize", transpiler: ...InitializeTranspiler);
    PatchHelper.Patch(harmony, typeof(ModManager), "ReadSteamMods", prefix: ...ReadSteamModsPrefix);
}
...
// Rewrites `Path.Combine(directoryName, "mods")` to literal AppPaths.ExternalModsDir.
public static bool ReadSteamModsPrefix() => false;
```

Platform patch disables desktop-only Steam/Sentry initialization (`PlatformPatches.cs` lines 13-55). `ReleaseInfoPatches` loads `release_info.json` from `OS.GetDataDir()/game` if the original Android executable-dir lookup fails (`ReleaseInfoPatches.cs` lines 24-111). `AppLifecyclePatches` flushes cloud writes on background/quit and restarts the app on in-game quit (`AppLifecyclePatches.cs` lines 50-270).

BaseLib workaround:

```csharp
// ../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Patches/BaseLibCompatPatches.cs:34-77
AppDomain.CurrentDomain.AssemblyLoad += OnAssemblyLoad;
...
_harmony.Patch(createMethod, prefix: new HarmonyMethod(prefix));
...
__result = code.ToList();
return false;
```

This lets BaseLib load but disables async hook state-machine surgery.

## Architecture

### Repository structure / roles
- `src/STS2Mobile/`: C# patcher loaded by custom Godot .NET runtime.
  - `Launcher/`: MVC-ish Godot UI (`LauncherUI` shell, `LauncherView`, `LauncherController`, `LauncherModel`, UI sections/components).
  - `Steam/`: SteamKit2 auth, depot CDN downloader, Steam Cloud sync, launcher self-update check/install.
  - `Modding/`: SAF bridge, zip importer, mod scanner/config/manifest types.
  - `Patches/`: Harmony patches against `sts2.dll` and related game assemblies.
- `android/`: Godot Android Gradle project, `GodotApp.java` bridge/activity, native libs/assets.
- `scripts/`: build/bootstrap helpers; runtime downloader is not here.
- `src/stubs/`: native no-op libraries for missing desktop-only libraries (`libsteam_api.so`, Sentry).

### End-to-end boot flow
1. Android `GodotApp.onCreate()` sets `gameDir = <files>/game`, starts diagnostics/logging, optionally wipes atlas cache, runs `setupAssemblies()`, extracts FMOD logo, then calls `super.onCreate()` (`GodotApp.java` lines 100-156).
2. `getCommandLine()` loads `<files>/game/SlayTheSpire2.pck` if present; otherwise extracts and loads `<files>/bootstrap.pck` (`GodotApp.java` lines 698-732).
3. Custom Godot/Mono loads `STS2Mobile.dll`; `ModEntry.InitializeGodotSharp()` initializes GodotSharp interop (`ModEntry.cs` lines 20-48); `ModEntry.Apply()` applies Harmony patches or schedules standalone launcher if game assemblies are absent (`ModEntry.cs` lines 51-122).
4. Standalone launcher mode: after a successful download, `LauncherModel.Launch()` calls `GodotApp.restartApp()` because the full PCK and assemblies need a fresh process (`LauncherModel.cs` lines 344-358; `GodotApp.java` lines 816-824).
5. Game mode: `LauncherPatches.GameStartupWrapperPrefix()` shows launcher UI before the real game startup. PLAY resolves a TCS and `RunLauncherThenGame()` continues to `GameStartup()` (`LauncherPatches.cs` lines 56-253).

### 本体管理 flow
- Login: `LauncherController.OnLoginPressed()` -> `LauncherModel.LoginAsync()` -> `SteamAuth` -> save encrypted refresh token -> `OwnershipVerifier.VerifyAsync()` (`LauncherController.cs` lines 367-372; `LauncherModel.cs` lines 134-179; `SteamAuth.cs` lines 76-110; `OwnershipVerifier.cs` lines 41-55).
- Download: `DownloadSection` button -> `LauncherController.OnDownloadPressed()` shows Steam branch picker -> `LauncherModel.StartDownloadAsync()` -> `DepotDownloader.DownloadAsync()` -> writes `<files>/game` + `<files>/download_state` -> writes `.cache_stamp` -> UI prompts restart if standalone (`LauncherController.cs` lines 381-416; `LauncherModel.cs` lines 217-240; `DepotDownloader.cs` lines 93-139).
- Update: `CHECK GAME UPDATE` -> branch picker -> if branch changed, `WipeGameFiles()` and full redownload; otherwise `DepotDownloader.CheckForUpdatesAsync()` compares cached manifest IDs (`LauncherController.cs` lines 418-534; `LauncherModel.cs` lines 287-312; `DepotDownloader.cs` lines 64-90).
- Launcher self-update is separate GitHub APK flow (`LauncherController.cs` lines 536-682; `AppUpdateChecker.cs` lines 1-145; `AppUpdateInstaller.cs` lines 1-122).

### mod/patch flow
- Installed mod files live under `/storage/emulated/0/StS2LauncherMM/Mods/<ModId>/` (`AppPaths.cs` lines 15-19).
- Import flow copies SAF-selected zips to app cache, safe-extracts to a temp cache dir, locates `mod_manifest.json`, then copies to external mods dir (`GodotApp.java` lines 917-1016; `SafBridge.cs` lines 11-90; `ModImporter.cs` lines 23-142).
- At game runtime, the game’s built-in `MegaCrit.Sts2.Core.Modding.ModManager.Initialize` is transpiled so its `Path.Combine(..., "mods")` local mods folder becomes the external mods dir; `ReadSteamMods` is skipped (`ModLoaderPatches.cs` lines 17-67).
- Patch order is centralized in `ModEntry.Apply()`; this is the checklist for any port refactor because game updates may break individual type/method assumptions (`ModEntry.cs` lines 74-92).

### 启动器可复用设计点
1. **Bootstrap PCK pattern**: keep a tiny PCK in APK so Godot/.NET can boot without game assets, then switch to downloaded `SlayTheSpire2.pck` after restart (`make-bootstrap-pck.py` lines 1-111; `GodotApp.java` lines 698-732).
2. **Private payload + external user content split**: game body in app-private `<files>/game`; mods/backups/logs in external `/storage/emulated/0/StS2LauncherMM` (`AppPaths.cs` lines 15-19; `GodotApp.java` lines 653-667).
3. **In-app body manager abstraction candidate**: `LauncherModel` already centralizes session/download/update decisions; `DepotDownloader` is a replaceable backend if refactoring away from SteamKit2/SteamCMD (`LauncherModel.cs` lines 217-312; `DepotDownloader.cs` lines 64-139).
4. **Godot UI MVC**: `LauncherUI` has a main-thread queue for worker callbacks; `LauncherController` owns state transitions; views are pure Godot controls (`LauncherUI.cs` lines 1-199; `LauncherController.cs` lines 1-187).
5. **SAF import bridge**: Java copies picked content URI(s) into cache, C# polls/consumes paths, importer owns validation/extraction (`GodotApp.java` lines 917-1016; `SafBridge.cs` lines 11-90; `ModImporter.cs` lines 23-142).
6. **Patch isolation by concern**: each mobile adaptation is a separate Harmony patch file, with `PatchHelper` logging non-critical failures and `PatchCritical` for startup/cloud safety (`PatchHelper.cs` lines 12-98; `Patches/` files listed above).
7. **Cloud-safety gate**: cache preloading before `SaveManager` construction is a reusable pattern for any remote-save backend; if remote state cannot be trusted, fallback local-only (`LauncherPatches.cs` lines 62-143; `CloudFileCache.cs` lines 116-156).
8. **Native stubs for unsupported desktop libs**: `src/stubs/` prevents dynamic linker failure where functionality can be safely no-op’d, but should be paired with compatibility warnings (`src/stubs/steam_stub.c` lines 1-160).

### 需要替换 SteamCMD 的位置 / integration points
There is no SteamCMD call site to replace in this codebase. If the refactor plan currently assumes SteamCMD, the equivalent seams are:

1. **Runtime game body download/update backend**
   - Replace or wrap `src/STS2Mobile/Steam/DepotDownloader.cs` entirely.
   - Callers to update: `LauncherModel.StartDownloadAsync()`, `CheckForUpdatesAsync()`, `ListBranchesAsync()`, `WipeGameFiles()` (`LauncherModel.cs` lines 217-240, 287-326, 512-526).
   - UI call sites to preserve/adapt: `LauncherController.OnDownloadPressed()` and `OnCheckGameUpdatePressed()` branch picker/update flow (`LauncherController.cs` lines 381-534).
   - Required output layout if keeping the rest: populate `<files>/game/SlayTheSpire2.pck`, `<files>/game/release_info.json`, and a `data_*` dir containing game managed assemblies so `GodotApp.setupAssemblies()` and `getCommandLine()` still work (`GodotApp.java` lines 557-715).

2. **Steam auth / ownership if SteamCMD was only used to authenticate**
   - Current equivalents are `SteamAuth`, `SteamConnection`, `OwnershipVerifier`, `SteamCredentialStore` (`SteamAuth.cs` lines 76-110; `SteamConnection.cs` lines 90-145; `OwnershipVerifier.cs` lines 41-55; `SteamCredentialStore.cs` lines 24-80).
   - If using manual import or non-Steam distribution, remove/replace login UI (`LoginSection`, `CodeSection`), fast-path marker checks (`LauncherModel.StartSession()`), and ownership marker logic.

3. **Manifest/cache/update state**
   - Steam manifest IDs live under `<files>/download_state/*.id/*.manifest`; `.cache_stamp` stores branch/buildId/commit/version for diagnostics (`DepotDownloader.cs` lines 758-791; `CacheStamp.cs` lines 1-84).
   - A non-SteamCMD/non-Steam backend needs its own integrity metadata; otherwise `CheckForUpdatesAsync()` and branch switching semantics should be rewritten.

4. **PCK post-processing**
   - Regardless of download source, the repo relies on post-processing `SlayTheSpire2.pck` to disable Sentry (`DepotDownloader.PatchGamePck`, lines 817-918). If body files are imported/extracted elsewhere, run an equivalent patch step or handle Sentry another way.

5. **Steam Cloud is separate from SteamCMD**
   - `SteamKit2CloudSaveStore`, `CloudFileCache`, `CloudSyncCoordinator`, and `LauncherPatches.ConstructDefaultPrefix()` are not part of body download, but they depend on the same saved Steam refresh token. If removing Steam login entirely, replace or disable the cloud-sync buttons and SaveManager injection (`LauncherPatches.cs` lines 62-156; `SteamKit2CloudSaveStore.cs` lines 1-495).

6. **Build-time scripts are not runtime SteamCMD but do assume user-supplied game files**
   - `scripts/setup-deps.sh` expects `req_files/data_sts2_windows_x86_64/sts2.dll` and an Ekyso APK/templates to create compile-time references and Android assets (`setup-deps.sh` lines 9-33, 146-149). Refactor plan may need a new dependency bootstrap independent of SteamCMD.

### 风险点 / open questions
- **Legal / distribution**: README explicitly says no game assets are shipped and a valid Steam account is required (`README.md` lines 3-7). Any refactor that bundles/imports assets must re-check licensing and user flow.
- **No actual SteamCMD**: current runtime uses SteamKit2 CDN APIs. Replacing “SteamCMD” means deciding whether to keep SteamKit2, introduce a new import backend, or abstract both behind a body-manager interface.
- **Android storage policy**: mods/logs/backups require `MANAGE_EXTERNAL_STORAGE` and hardcoded `/storage/emulated/0/StS2LauncherMM` (`AndroidManifest.xml` lines 12-22; `AppPaths.cs` lines 15-19). This is risky for Play distribution and user permission denial; SAF-only mod storage would need loader changes.
- **Payload completeness check is weak**: `GameFilesReady()` only checks PCK magic (`LauncherModel.cs` lines 408-424). A partial/corrupt payload with a valid PCK can reach launch and fail later. `DepotDownloader` verifies Steam files, but manual/import backend must add equivalent validation.
- **Hardcoded game layout**: `SlayTheSpire2.pck`, `release_info.json`, and `data_*` / `data_sts2_windows_x86_64` are assumed (`GodotApp.java` lines 653-715; `ReleaseInfoPatches.cs` lines 44-88). Game packaging changes can break boot.
- **PCK in-place patch fragility**: `PatchGamePck()` parses Godot PCK internals and byte-edits specific strings (`DepotDownloader.cs` lines 817-918). Encrypted/compressed/new-format PCKs or changed Sentry paths may bypass it.
- **Steam depot downloader resource risks**: concurrent chunk downloads allocate chunk buffers and write temp files; no obvious free-space preflight; interrupted downloads rely on `.downloading` cleanup under `gameDir` (`DepotDownloader.cs` lines 440-654).
- **Branch switch data loss scope**: `WipeGameFiles()` deletes the full body and download cache but preserves credentials/saves (`LauncherModel.cs` lines 512-526). UI warns, but any backend must preserve this separation.
- **Mod manager currently WIP/unreachable**: main button opens Save Manager, not the mod UI (`LauncherController.cs` lines 250-272). If refactor promises in-launcher mod import, this must be re-enabled and tested.
- **Mod enable/order not enforced**: `ModConfig` is only used by UI/import/delete. Runtime patch redirects the whole folder scan and does not filter disabled mods (`ModLoaderPatches.cs` lines 33-66). Need patch/bridge if enable/order should affect actual load.
- **Mod manifest mismatch**: code requires `mod_manifest.json` (`ModImporter.cs` lines 40-47; `ModScanner.cs` lines 16-24), while README manual install text mentions a `<ModId>.json` manifest in one place (`README.md` lines 162-164). Verify real game schema before relying on docs.
- **Mod zip safety is incomplete**: Zip Slip is handled, but zip bombs/huge files/double storage (SAF cache copy + extraction + external copy) are not mitigated (`GodotApp.java` lines 997-1012; `ModImporter.cs` lines 112-142).
- **Steamworks.NET mods**: native stubs satisfy linker but do not implement Steamworks. README flags QuickReload/RitsuLib and similar mods as incompatible; stubs can return misleading defaults (`README.md` lines 82-84; `src/stubs/steam_stub.c` lines 1-160).
- **BaseLib degraded mode**: BaseLib loads, but async hooks are disabled; mods depending on hook callbacks may silently misbehave (`BaseLibCompatPatches.cs` lines 1-77).
- **Harmony patch fragility**: many patches target private methods/fields/type names from `sts2.dll`; `ModLoaderPatches` specifically matches `ldstr "mods"`. Game updates can silently disable non-critical patches or, for critical patches, prevent normal game mode (`PatchHelper.cs` lines 12-98; `ModEntry.cs` lines 74-102).
- **Cloud sync complexity / destructive potential**: save sync intentionally blocks on cloud file cache and falls back local-only when unsafe, but it is complex and has prior bug history (`LauncherPatches.cs` lines 62-156; `CloudFileCache.cs` lines 116-156; `CloudSyncDecisions.cs` lines 1-412). If not required, remove before refactor; if required, add tests around current_run/profiles/modded paths.
- **Custom engine/dependency burden**: build requires custom Godot 4.5.1 Mono, Ekyso APK artifacts, FMOD, Spine, Harmony, Android NDK, and game DLL references (`README.md` lines 221-245; `scripts/setup-deps.sh` lines 9-33, 86-149; `STS2Mobile.csproj` lines 1-24).
- **Credentials/security**: refresh tokens are Keystore-encrypted, but the launcher handles raw Steam passwords during login and stores a permanent ownership marker with no revocation check (`SteamAuth.cs` lines 76-110; `OwnershipVerifier.cs` lines 13-20, 41-55).
- **Launcher self-update not body update**: APK self-update checks GitHub releases and installs via FileProvider (`LauncherController.cs` lines 536-682; `AppUpdateChecker.cs` lines 1-145; `AppUpdateInstaller.cs` lines 1-122). Keep separate from body/update backend.

## Start Here
Start with `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherModel.cs` because it is the seam between UI/session state and body management. From there open `src/STS2Mobile/Steam/DepotDownloader.cs` for the current SteamKit2 replacement of any SteamCMD-style flow, then `android/src/com/game/sts2launcher/modmanager/GodotApp.java` to see the required on-disk payload layout (`game/SlayTheSpire2.pck` + `game/data_*`) and boot mode switching.

## Supervisor coordination
No blocker or decision request. Read-only scouting completed; no repository files were modified.
