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
