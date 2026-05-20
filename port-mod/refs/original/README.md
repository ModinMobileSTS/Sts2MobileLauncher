# Original PC reference symlinks

These symlinks point at the local original PC build under
`../s2_original/s21032/.godot/mono/temp/bin/Debug/` and are used only for the
compat MOD compile gate:

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original -v:q
```

That gate catches accidental dependencies on classes/properties that exist only
in the old Android port's rebuilt `sts2.dll`. The files are symlinks to local
reference artifacts, not committed game payloads.
