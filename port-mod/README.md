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
- `AndroidSettingsPatches` maps companion JSON fields that also exist in the PC
  `SettingsSave` (`aspect_ratio`, `vsync`, `msaa`, `fps_limit`, `fullscreen`),
  maps companion `mod_settings.mods_enabled` / `mod_list` / legacy
  `disabled_mods` into the runtime `ModSettings`, and merges Android-only JSON
  keys back after PC `SettingsSave` serialization would drop them.
- `DisplaySettingsPatches` applies Android-only companion fields for FPS, custom
  fullscreen render size, global content scale, UI font scale, and 180° landscape
  orientation.
- `MobileHandLayoutPatches` applies the companion `show_more_hand_card_text` /
  `show_more_hand_card_text_lift_height_percent` hand lift as a Harmony
  post-layout offset without rebuilding the game body.
- `QuickRestartPatches` adds the built-in Android retry button on the pause menu
  when `quick_sl_enabled` is true and no external Quick Restart UI mod is loaded.
- `ExternalSettingsPatches` adds a fallback in-game settings row that opens the
  Java companion settings shell, redirects game Quit back to the settings shell,
  and applies the companion `pending_unlock_all.flag` command.
- `ModLoaderPatches` redirects local mods to `OS.GetDataDir()/mods` and skips
  Steam mod enumeration.
- `ShaderCompatibilityPatches` loads `port_compat.pck` and applies the mobile
  shader replacements copied from the old port when
  `shader_compatibility_mode` is enabled.
- `TouchInputPatches` adds the first touch-friendly card-play cancellation path
  for releases outside the play zone / untargeted releases.
- `LanMultiplayerPatches` bridges companion LAN settings by using stable ENet
  message IDs, adding configured compatibility mod names to multiplayer mod
  checks, honoring persistent/custom LAN player IDs, replacing the no-Steam join
  screen with host/port input, and hosting ENet games with the configured player
  capacity.

Build locally with the reference .NET SDK:

```bash
tools/android/build-port-mod.sh
```

The patched Godot runtime expects `STS2Mobile.dll` / `STS2Mobile.ModEntry`; `tools/android/build-port-mod.sh` builds this skeleton under that assembly name and copies it over the reference prebuilt in `android/assets/dotnet_bcl/`.
