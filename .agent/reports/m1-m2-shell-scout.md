# Code Context

## Files Retrieved
1. `.agent/plan/sts2_re_restructure_plan.md` (lines 221-299) - M1/M2 acceptance criteria and next-step checklist for extra settings and local zip import.
2. `.agent/reports/extra-settings-inventory.md` (lines 1-52, 175-224, 273-288) - source-of-truth copied settings files, manifest/Gradle requirements, and M1 risks.
3. `.agent/reports/local-payload-import-plan.md` (lines 31-80, 126-144) - target payload layout, staging/rollback requirements, validation rules, and launcher seams to replace.
4. `.agent/reports/source-baseline.md` (lines 1-29, 105-109) - notes that `../s2` had dirty extra-settings changes; important for copy fidelity.
5. `.gitignore` (lines 1-33, 41-45) - confirms large payloads/Gradle outputs/assets payloads are ignored.
6. `android/AndroidManifest.xml` (lines 1-138) - current shell manifest, launcher activity, Godot activity, FileProvider, permissions.
7. `android/build.gradle` (lines 34-125, 226-253) - current Gradle dependencies, namespace/app id, asset pack, flavors/source sets.
8. `android/gradle.properties` (lines 28-42) - package id/version/ABI/export flavor configuration.
9. `android/assetPackInstallTime/build.gradle` (lines 1-9) - empty install-time asset pack placeholder.
10. `android/src/com/godot/game/GameSettingsActivity.java` (lines 1-26, 120-260) - settings entry Activity, SAF handlers currently only for saves/full backup/mod import.
11. `android/src/com/godot/game/GamePage.java` (lines 1-125) - Game tab/status card, currently always reports ready and launches `GodotApp`.
12. `android/src/com/godot/game/ExtraSettingsActions.java` (lines 1-16) - action interface lacks game-payload import/clear actions.
13. `android/src/com/godot/game/ExtraSettingsRepository.java` (lines 69-185, 320-367, 1009-1043, 1277-1293) - settings defaults/path semantics, mod import unzip helper, pending flag, files dirs.
14. `android/src/com/godot/game/GodotApp.java` (lines 67-145, 292-336) - compatibility bridge methods exist, but no imported-payload/launcher runtime wiring.
15. `android/res/xml/game_shortcuts.xml` (lines 1-25) and `android/res/xml/settings_shortcuts.xml` (lines 1-25) - hardcoded dynamic shortcut target package.
16. `android/res/xml/file_paths.xml` (lines 1-17) and `android/res/xml/godot_provider_paths.xml` (lines 1-20) - FileProvider path resources.
17. `../s2/.cache/StS2-Launcher_Mod_Manager/android/src/com/game/sts2launcher/modmanager/GodotApp.java` (lines 92-156, 557-667, 698-724, 922-1016) - reference for `files/game`, setup assemblies, PCK/bootstrap selection, and SAF bridge.
18. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Launcher/LauncherModel.cs` (lines 81-103, 217-240, 408-424, 512-526) - reference Steam-dependent seams to replace.
19. `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/Modding/SafBridge.cs` (lines 1-75) and `ModImporter.cs` (lines 112-146) - reusable SAF polling/safe extraction pattern.

## Key Code

### Current Android shell shape
- The new shell is a flat Godot Android export project under `android/`, not the planned `android/app/` module layout yet.
- `android/AndroidManifest.xml` keeps `.GameSettingsActivity` as the only `MAIN` / `LAUNCHER` entry (lines 37-55) and declares `.GodotApp` as a secondary exported landscape Activity (lines 57-74). Crash/log/file/text activities and FileProvider are present (lines 76-134).
- `android/build.gradle` uses `namespace = 'com.godot.game'` but `applicationId getExportPackageName()` (lines 101-119); `android/gradle.properties` sets `export_package_name=com.megacrit.sts2re` (lines 31-35). Java package compatibility is therefore preserved while public app id is new.
- Extra-settings dependencies are present: Material, RecyclerView, DocumentFile, CustomActivityOnCrash, MTDataFilesProvider (build.gradle lines 38-46).

### Copied extra settings fidelity
A quick checksum comparison against `../s2/android/build` found:
- Java/resources copied: 104 identical files.
- Only intended resource changes: `android/res/xml/game_shortcuts.xml` and `android/res/xml/settings_shortcuts.xml` hardcode `com.megacrit.sts2re` instead of old target package.
- Extra file: `android/res/xml/godot_provider_paths.xml`.
- Gradle/config differ because this shell was adapted.

Relevant fidelity gap: baseline report says `../s2` had dirty changes in extra-settings Java/resources (`source-baseline.md` lines 18-29). The current copy matches the dirty working tree now, but this source state is not a stable committed baseline unless recorded.

Critical current settings behavior:
```java
// android/src/com/godot/game/ExtraSettingsRepository.java:69-103
public void ensureAppDirectories() {
    ensureDirectory(getAccountRootDir());
    ensureDirectory(getModsRootDir());
}
...
public void saveSettingsJson(JSONObject settings) throws Exception {
    ensureDirectory(getAccountRootDir());
    writeTextFile(getSettingsFile(), settings.toString(2));
}
```
```java
// android/src/com/godot/game/ExtraSettingsRepository.java:1277-1293
public File getAccountRootDir() {
    File defaultDirectory = new File(context.getFilesDir(), "default");
    ...
    return new File(defaultDirectory, "1");
}
public File getSettingsFile() { return new File(getAccountRootDir(), SETTINGS_FILE_NAME); }
public File getModsRootDir() { return new File(context.getFilesDir(), "mods"); }
```
Defaults include all mobile/Android fields (`ExtraSettingsRepository.java` lines 105-185). `queueUnlockAll()` writes `pending_unlock_all.flag` under the account root (lines 366-367).

### Manifest/package compatibility
Current bridge is favorable for M1:
```java
// android/src/com/godot/game/GodotApp.java:140-145
public static Intent createLaunchIntent(Context context, boolean forceNewLaunch) {
    Intent intent = new Intent(context, GodotApp.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
```
```java
// android/src/com/godot/game/GodotApp.java:297-322
public static boolean launchGameSettingsFromGame() { ... new Intent(activity, GameSettingsActivity.class) ... }
public static boolean restartToSettingsFromGame() { ... }
```
This preserves the `com.godot.game.GodotApp` class expected by old C# helpers. The shortcuts currently hardcode `android:targetPackage="com.megacrit.sts2re"` in `game_shortcuts.xml` and `settings_shortcuts.xml`, so any app id change must update these or replace with manifest placeholders/generated shortcuts.

### Gradle/build validation
I ran `cd android && ./gradlew :assembleMonoDebug --no-daemon`. It did not reach javac/resource errors because this environment has a JRE without `javac`:
```text
Could not create task ':compileMonoDebugJavaWithJavac'.
Failed to calculate ... property 'javaCompiler'.
Toolchain installation '/usr/lib/jvm/java-21-openjdk-amd64' does not provide the required capabilities: [JAVA_COMPILER]
```
`java -version` works but `javac` is missing. Gradle did configure far enough to produce `android/build/outputs/logs/manifest-merger-mono-debug-report.txt`; that report shows `GameSettingsActivity` and `GodotApp` merged as `com.godot.game.*`, and FileProvider merge from the Godot AAR. This is not full build validation.

Other build risks:
- `android/build.gradle` uses AGP/Groovy syntax that Gradle 8.13 warns is deprecated in many places; not a blocker today but noisy.
- `assetPacks = [":assetPackInstallTime"]` is enabled (build.gradle line 101), but `android/assetPackInstallTime` contains only `build.gradle`; no payload asset path or extraction code exists.
- `android/assets/dotnet_bcl/` and `android/libs/` exist locally but are ignored by `.gitignore` (lines 41-45). A clean checkout/build will need a documented generation/copy step or these artifacts will be absent.

### Local zip importer integration seams
No M2 importer exists yet in the current shell:
- `GameSettingsActivity` SAF request codes cover save import/export, full data backup, and mod import only (lines 24-28, 147-206). There is no `REQUEST_IMPORT_GAME_ZIP`.
- `ExtraSettingsActions` has no methods for import payload / clear payload / show payload state (lines 3-15).
- `GamePage` has no payload status. It always adds a `status_ready` row and launch buttons (lines 74-93), regardless of `<files>/game/.payload_manifest.json` or PCK existence.
- `ExtraSettingsRepository` has a private `unzipIntoDirectory()` helper with Zip Slip protection (lines 1009-1043), but it extracts directly into save/mod target dirs and has no staging, rollback, pck/release/dll validation, manifest writing, progress, cancellation, source hash, or `<files>/game` semantics.
- `GodotApp.getCommandLine()` only adds renderer args (lines 133-138); it does not add `--main-pack <files>/game/SlayTheSpire2.pck` or fallback bootstrap pck.
- `GodotApp.onCreate()` only redirects first-run incomplete users and starts Godot (lines 110-130); it does not set `gameDir`, call FMOD init, run `setupAssemblies()`, expose SAF picker bridge methods, or display launcher/import UI.
- No C# launcher/patcher source exists in this repo yet (`find . -name '*.cs'` returned none). Only `STS2Mobile.dll` exists as an ignored asset under `android/assets/dotnet_bcl/`, so `LauncherModel` seams are not currently editable here.

Reference seams to port/rewrite:
```java
// reference GodotApp.java:100-146, 557-667, 698-715
instance = this;
gameDir = new File(getFilesDir(), "game").getAbsolutePath();
FMOD.init(this);
setupAssemblies();
...
if (new File(gameDir, "SlayTheSpire2.pck").exists()) {
    commands.add("--main-pack");
    commands.add(pckFile.getAbsolutePath());
}
```
```csharp
// reference LauncherModel.cs:81-103, 217-240, 408-424
StartSession() currently gates on credentials + ownership marker + PCK magic.
StartDownloadAsync() currently calls Steam DepotDownloader.
GameFilesReady() checks only SlayTheSpire2.pck magic.
```
The local-payload plan says these should become manifest-driven payload readiness and `ImportZipAsync()` / `ExtractBundledPayloadAsync()` (`local-payload-import-plan.md` lines 126-144).

## Architecture

Current flow:
1. Android launches `GameSettingsActivity`.
2. `GameSettingsActivity` creates `ExtraSettingsRepository`, ensures `<files>/default/1` and `<files>/mods`, then shows welcome or bottom-nav shell.
3. Settings pages write `settings.save` under `<files>/default/<account>/settings.save`; mod import writes into `<files>/mods`; pending unlock writes `<files>/default/<account>/pending_unlock_all.flag`.
4. Launch buttons call `GodotApp.createLaunchIntent()` and start `GodotApp`.
5. Current `GodotApp` is still old-port Godot Activity glue: first-run redirect, renderer args, keyboard shortcut, and Java methods for game-to-settings/restart. It does not yet manage imported game payloads.

Target M2/M3 flow per plan:
- Add a payload manager/importer, likely Java-side first because current UI is Java. It should select zip with SAF, stream-extract to `<files>/payload_import/staging-*`, validate, atomically promote to `<files>/game`, and write `<files>/game/.payload_manifest.json`.
- Update `GamePage`/actions to show no-payload/import/ready/clear states.
- Later wire `GodotApp` runtime to `<files>/game`: load `SlayTheSpire2.pck`, copy managed assemblies to `.godot/mono/publish/arm64`, and run bootstrap/launcher if no payload.

## Start Here
Open `android/src/com/godot/game/GameSettingsActivity.java` first. It is the M1 entry point and the smallest M2 integration seam: add a new SAF request/action for game zip import, then route to repository/payload-manager methods and update `GamePage` state.

Concrete blockers/gaps and smallest recommended next actions:
1. **Build validation blocked by missing JDK compiler.** Install/use a full JDK with `javac` or point Gradle toolchain to one, then rerun `./gradlew :assembleMonoDebug` before code changes.
2. **M1 copy fidelity source is unstable.** Record the dirty `../s2` extra-settings snapshot/hash in this repo or get it committed upstream; otherwise “copied exactly” is not reproducible.
3. **No M2 payload model/importer.** Add a small `PayloadRepository`/`GamePayloadImporter` Java class, plus `ExtraSettingsActions.requestImportGameZip()` and `GameSettingsActivity` request code.
4. **Game page lies about readiness.** Replace unconditional `status_ready` with checks for `<files>/game/.payload_manifest.json` and critical files; show Import/Reimport/Clear actions.
5. **Current unzip helper is insufficient for game payloads.** Reuse its canonical-path guard, but implement staging, rollback, GDPC/release/sts2.dll validation, manifest writing, progress/cancel, and source metadata.
6. **Runtime bridge not ready for imported payload.** Later M3 should port the reference `gameDir`, `setupAssemblies()`, `--main-pack`, and bootstrap behavior into `android/src/com/godot/game/GodotApp.java` while keeping `com.godot.game` methods.
7. **Shortcuts hardcode app id.** If `export_package_name` changes, update `android/res/xml/*shortcuts.xml` or generate/use placeholders.
8. **Ignored runtime artifacts need generation docs.** Because `android/assets/dotnet_bcl/` and `android/libs/` are ignored, add/verify a setup script before expecting CI/clean builds.
