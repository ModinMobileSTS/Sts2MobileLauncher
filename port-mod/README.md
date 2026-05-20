# Android port compatibility MOD

This directory contains the first editable skeleton for the extracted Android
compatibility MOD / Harmony patcher. It is intentionally not a copy of the old
full game source.

Current implementation (`STS2AndroidPortCompat`):

- `ModEntry` exposes the same unmanaged entrypoints used by the reference
  launcher (`InitializeGodotSharp`, `Apply`).
- `PlatformPatches` disables desktop Steam/Sentry/platform paths.
- `ReleaseInfoPatches` reads `release_info.json` from the imported private
  payload at `OS.GetDataDir()/game/release_info.json`.
- `AndroidSettingsBridge` reads extra-settings JSON from
  `OS.GetDataDir()/default/1/settings.save` without requiring PC `SettingsSave`
  to contain Android-only fields.
- `AndroidSettingsPatches` currently applies a minimal vsync/msaa bridge; the
  full field set from `.agent/reports/extra-settings-inventory.md` is TODO for
  M6.
- `ModLoaderPatches` redirects local mods to `OS.GetDataDir()/mods` and skips
  Steam mod enumeration.

Build locally with the reference .NET SDK:

```bash
tools/android/build-port-mod.sh
```

The patched Godot runtime expects `STS2Mobile.dll` / `STS2Mobile.ModEntry`; `tools/android/build-port-mod.sh` builds this skeleton under that assembly name and copies it over the reference prebuilt in `android/assets/dotnet_bcl/`.
