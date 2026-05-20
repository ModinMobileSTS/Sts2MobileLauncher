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
