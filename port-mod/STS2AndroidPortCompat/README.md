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
