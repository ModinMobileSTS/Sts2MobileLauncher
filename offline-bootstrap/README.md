# STS2 Offline Bootstrap

This directory builds the generic offline bootstrap compatibility pack for the Android launcher.

It is intentionally separate from `port-mod/`:

- It does not read `port-mod/targets`.
- It does not accept `ReferenceFlavor`.
- It does not statically reference `sts2.dll`.
- It does not use `STS2_TARGET_*` compile branches.
- It outputs `STS2Mobile.dll` only because the patched runtime already looks for that assembly name.
- Game API access is reflection based and writes a runtime probe to `<files>/launcher/offline-bootstrap-probe.json`.
- It includes a reflection-based vanilla `ModelDb` two-phase initializer so Android/Mono does not crash on original model static constructors that reference other models before registration.

The generated pack is schema 2 with `pack_kind=offline-bootstrap`, `match_mode=offline-wildcard`, and `versions=["*"]`. The Android launcher gives this target lower priority than all exact SHA or version compatibility packs, so it is only selected automatically when an imported payload has no matching full compatibility pack installed.

Build:

```bash
offline-bootstrap/tools/build-offline-pack.sh
```

The output zip is written to `offline-bootstrap/dist/offline-bootstrap/sts2-android-offline-bootstrap.zip`.
