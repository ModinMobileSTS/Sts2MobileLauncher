# Code Context

## Files Retrieved
1. `port-mod/STS2AndroidPortCompat/Patches/AndroidSettingsPatches.cs` (lines 32-96) - current settings bridge; `EnsureModSettings` only creates `ModSettings { PlayerAgreedToModLoading = true }` and drops companion `mods_enabled` / `mod_list`.
2. `port-mod/STS2AndroidPortCompat/Patches/AndroidSettingsMerge.cs` (lines 12-43) - preserves Android-only JSON keys after original PC `SettingsSave` serialization; notably does **not** preserve `mod_settings` as Android-only.
3. `port-mod/STS2AndroidPortCompat/Patches/ModLoaderPatches.cs` (lines 11-36) - redirects original `ModManager.Initialize` local mods scan to `OS.GetDataDir()/mods` and skips Steam Workshop enumeration.
4. `port-mod/STS2AndroidPortCompat/Android/AppPaths.cs` (lines 7-15) - private no-Steam paths: `files/game`, `files/default/1/settings.save`, `files/mods`.
5. `../s2/android/build/src/com/godot/game/ExtraSettingsRepository.java` (lines 322-337, 663-720, 769-772) - companion Mods page/import writes `mod_settings.mods_enabled`, `mod_list[].id/source/is_enabled` with source `mods_directory`.
6. `../s2_original/s21032/src/Core/Modding/ModSettings.cs` (lines 8-25), `SettingsSaveMod.cs` (lines 6-15), `ModSource.cs` (lines 3-7) - original runtime shape that the compat MOD can construct without game-source changes.
7. `../s2_original/s21032/src/Core/Modding/ModManager.cs` (lines 49-104, 306-337) - original loader consumes `ModSettings.PlayerAgreedToModLoading` and `ModList`, recursively scans local mod manifests, then rewrites `ModList`.
8. `android/src/com/godot/game/PayloadManager.java` (lines 29-47, 239-247) and `android/src/com/godot/game/GodotApp.java` (lines 156-190) - local-payload architecture: imported PC zip under `files/game`, PCK passed as `--main-pack`, assemblies copied into Godot mono publish dir.
9. `port-mod/STS2AndroidPortCompat/Patches/PlatformPatches.cs` (lines 20-24) and `ReleaseInfoPatches.cs` (lines 18-28) - current no-Steam startup: skip Steam init and read `release_info.json` from `files/game`.
10. `port-mod/STS2AndroidPortCompat/Patches/LanMultiplayerPatches.cs` (untracked worktree file, lines 98-120, 317-368, 606-699) plus dirty `ModEntry.cs` / `android/AndroidManifest.xml` - already-present LAN candidate, not in current HEAD; treat separately.

## Key Code

Current weak spot:
```csharp
// port-mod/STS2AndroidPortCompat/Patches/AndroidSettingsPatches.cs lines 89-96
private static void EnsureModSettings(SettingsSave settings)
{
    if (settings.ModSettings != null)
        return;
    if (!AndroidSettingsBridge.TryGet("mod_settings", out var element) || element.ValueKind is Null or Undefined)
        return;
    settings.ModSettings = new ModSettings { PlayerAgreedToModLoading = true };
}
```

Companion UI writes richer state:
```java
// ../s2/android/build/src/com/godot/game/ExtraSettingsRepository.java lines 663-720
modSettings.put("mods_enabled", false);
modSettings.put("mod_list", new JSONArray());
modEntry.put("id", modId);
modEntry.put("source", "mods_directory");
modEntry.put("is_enabled", !disabled);
```

Original runtime expects:
```csharp
// ../s2_original/s21032/src/Core/Modding/ModSettings.cs lines 10-15
public bool PlayerAgreedToModLoading { get; set; }
public List<SettingsSaveMod> ModList { get; set; } = new();

// SettingsSaveMod.cs lines 8-15
public string Id { get; set; } = "";
public ModSource Source { get; set; }
public bool IsEnabled { get; set; } = true;
```

## Architecture

The restructured APK already avoids Steam for the main game body: `PayloadManager` validates/imports a PC zip into `files/game`; `GodotApp` loads `files/game/SlayTheSpire2.pck` and copies payload assemblies; `PlatformPatches` skips Steam initialization; `ModLoaderPatches` redirects local mods to `files/mods` and skips Steam Workshop.

The missing high-value bridge is **companion mod settings -> original runtime `ModSettings`**. Today, the Java Mods page can import/toggle local mods, but the compat MOD only uses the presence of `mod_settings` as unconditional agreement. This can ignore the master switch and per-mod disabled/profile choices.

## Start Here

Start at `port-mod/STS2AndroidPortCompat/Patches/AndroidSettingsPatches.cs` lines 89-96. Replace `EnsureModSettings` with a parser that builds the original `ModSettings` object from companion JSON.

## Most feasible next patch group

**Patch group: local mod/settings compatibility for no-Steam payloads.**

Implement in `AndroidSettingsPatches`:
- Parse `mod_settings.mods_enabled` -> `ModSettings.PlayerAgreedToModLoading`.
- Parse `mod_settings.mod_list[]` -> `SettingsSaveMod` entries:
  - `id` -> `SettingsSaveMod.Id`
  - `source == "mods_directory"` or empty -> `ModSource.ModsDirectory`
  - `source == "steam_workshop"` should be ignored or mapped only if deliberately supporting Steam; no-Steam path should prefer ignore/log.
  - `is_enabled` -> `SettingsSaveMod.IsEnabled` default true.
- Optionally normalize legacy `disabled_mods[] { name, source }` into disabled `SettingsSaveMod` entries so old companion state is not lost on save.
- If `mod_settings` is null/missing: leave `SettingsSave.ModSettings = null` so original loader skips mods.
- If `mod_settings` exists but `mods_enabled=false`: set `PlayerAgreedToModLoading=false`, preserving master switch.

Likely files to change:
1. `port-mod/STS2AndroidPortCompat/Patches/AndroidSettingsPatches.cs` - parser/helper.
2. Maybe `port-mod/STS2AndroidPortCompat/Patches/AndroidSettingsMerge.cs` - only if testing shows original save drops required companion fields. Do **not** blindly add `mod_settings` to `AndroidOnlyKeys`, because `ModManager.Initialize` intentionally rewrites normalized `ModList` after scanning.

## Risks / constraints

- Current worktree is dirty: `android/AndroidManifest.xml` and `ModEntry.cs` are modified, and `LanMultiplayerPatches.cs` is untracked. Decide whether to isolate that LAN candidate before applying this smaller settings patch.
- `mod_settings.source` uses snake-case strings from Java (`mods_directory`); do not rely on enum names without a converter.
- Current behavior can accidentally load all detected local mods if `mod_settings` object exists with `mods_enabled=false` because it sets `PlayerAgreedToModLoading=true` and leaves `ModList` empty.
- Original `ModManager.Initialize` rewrites `ModSettings.ModList` after scanning; expect ordering/normalization changes after first game launch.
- Keep compile compatibility with original PC `sts2.dll`; avoid references to old-port-only APIs such as `LoadedMods`, `AllMods`, or source-added LAN helpers.
- If the untracked LAN patch is later kept, its custom platform-player-id behavior can move save paths away from `default/1`; keep storage/settings paths pinned to `files/default/1` unless a deliberate account migration is designed.

## Validation commands

Host/build:
```bash
git status --short
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -v:q
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original -v:q
tools/android/build-port-mod.sh
```

Android shell build:
```bash
cd android && ./gradlew :assembleMonoDebug --warning-mode all
```
Current environment note: this Gradle command is blocked here by `/usr/lib/jvm/java-21-openjdk-amd64` lacking `JAVA_COMPILER`; use a full JDK/toolchain.

Device checks after installing/importing payload:
```bash
adb shell run-as com.megacrit.sts2re cat files/default/1/settings.save | grep -n 'mod_settings' -A20
adb shell run-as com.megacrit.sts2re find files/mods -maxdepth 3 -name '*.json'
adb logcat -c
# Toggle ModsPage master switch and one mod, launch game, then:
adb logcat -d | grep -E 'STS2Mobile|Found mod manifest|Skipping loading mod|RUNNING MODDED|Loaded [0-9]+ mods'
adb shell run-as com.megacrit.sts2re cat files/default/1/settings.save | grep -n 'mods_enabled\|is_enabled\|mods_directory'
```

Expected results:
- `mods_enabled=false` prevents mod loading.
- `mods_enabled=true` loads local `files/mods` entries.
- A mod disabled in ModsPage remains skipped by original `ModManager`.
- `settings.save` keeps `mod_settings.mod_list` after the game saves settings.
- No Steam Workshop enumeration or `steam/` save root is required.
