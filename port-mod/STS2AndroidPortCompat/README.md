# STS2AndroidPortCompat

The patched Godot Android runtime currently looks for assembly
`STS2Mobile.dll` and type `STS2Mobile.ModEntry`. For compatibility, the build
entrypoint project is `STS2Mobile.csproj`, which compiles this source tree into
that assembly name. `STS2AndroidPortCompat.csproj` is kept as a descriptive
project name for local IDE use, but it is not the runtime assembly name.

Build and stage into the Android shell:

```bash
tools/android/build-port-mod.sh
```

Compile against the old launcher/runtime reference DLLs (default):

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -v:q
```

Compile against the original PC `sts2.dll` to catch accidental dependencies on
old-port-only game-source additions:

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original -v:q
```


Overlay resource pack:

```bash
tools/android/make-port-overlay-pck.py
```

`tools/android/build-port-mod.sh` runs this automatically and stages
`android/assets/port_compat.pck`, which `GodotApp` extracts to
`OS.GetDataDir()/port_compat.pck` before the compat DLL loads it.

LAN multiplayer compatibility is implemented as Harmony patches in
`Patches/LanMultiplayerPatches.cs`. It reads `lan_multiplayer_enabled`,
`lan_compatibility_mod_names`, `lan_use_custom_player_id`,
`lan_use_custom_platform_player_id`, `lan_custom_player_id`, `lan_join_host`,
`lan_join_port`, `max_multiplayer_enabled`, and `max_multiplayer_players` from
companion settings instead of adding Android-only fields to the imported PC game
assembly.

Local mod enable/disable compatibility is handled in
`Patches/AndroidSettingsPatches.cs`: companion `mod_settings.mods_enabled`,
`mod_list[]`, and legacy `disabled_mods[]` are projected into the runtime
`ModSettings` shape used by the current reference DLL and the original PC DLL.

Mobile hand text visibility compatibility now has a small first patch in
`Patches/MobileHandLayoutPatches.cs`: after the original hand layout runs, it
lifts visible hand cards by the companion `show_more_hand_card_text` percentage
instead of relying on Android-only `SettingsSave` fields in the game body.

Quick-save/load compatibility now has a built-in retry patch in
`Patches/QuickRestartPatches.cs`: when companion `quick_sl_enabled` is true, the
pause menu gets an Android retry button unless an external Quick Restart UI mod
is already loaded.

Touch preview compatibility now has a first-pass patch in
`Patches/MobileTapPreviewPatches.cs`: when companion `touch_lift_preview` is
true, tapping a playable hand card pins its hover preview; a second tap follows
`touch_lift_retap_action` (`put_down`, `play`, or `none`).

Android input compatibility now has a first-pass patch in
`Patches/AndroidInputCompatPatches.cs`: it maps the Android back action to game
cancel/pause, emits synthetic right-clicks for two-finger inspect when
`mobile_two_finger_inspect` is enabled, and normalizes trigger-axis controller
input on Android.
