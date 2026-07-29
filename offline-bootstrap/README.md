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
- `ModelDbRuntimeContract` resolves API shapes instead of branching on the release version. It currently accepts parameterless `ModelDb.Init()` and `ModelDb.Init(Type[]? injectedModelTypes = null)`, preserves explicit injected type sets, and rejects unknown parameter/return semantics.
- The resolver preflights the model type source, `GetId(Type)`, writable content dictionary, model ID storage and base constructor before Harmony takes over `ModelDb.Init`; placeholder dictionary insertion is staged and committed atomically.

The generated pack is schema 2 with `pack_kind=offline-bootstrap`, `match_mode=offline-wildcard`, and `versions=["*"]`. The Android launcher gives this target lower priority than all exact SHA or version compatibility packs, so it is only selected automatically when an imported payload has no matching full compatibility pack installed. The wildcard means that an unknown payload may try the fallback; it is not a compatibility certification.

Probe contract v2 separates `patches_installed` from a completed runtime check. Normal initialization advances through `modeldb_initializing` to `ready`; unsupported reflection shapes, patch failures and constructor failures end in `unsupported_api`, `apply_failed` or `runtime_failed`. The launcher keys the result to the offline pack version, target, installed source zip SHA, payload version and exact `sts2.dll` SHA. A known failed tuple is no longer auto-matched, while a user may explicitly retry it from the launch warning.

Build and contract verification:

```bash
# Synthetic unsafe/known API shapes plus every locally configured original reference.
offline-bootstrap/tools/test-offline-contract.sh

# Runs the same contract verification before building the pack.
offline-bootstrap/tools/build-offline-pack.sh
```

The contract test does not compile against `sts2.dll`; it loads configured references only for reflection inspection, preserving the offline-bootstrap boundary. The output zip is written to `offline-bootstrap/dist/offline-bootstrap/sts2-android-offline-bootstrap.zip`.
