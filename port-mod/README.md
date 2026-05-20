# Android port compatibility MOD (planned)

This directory is reserved for the extracted Android compatibility MOD / Harmony
patcher. It is intentionally **not** a copy of the old full game source.

Current state:

- M1/M2 focus is the Android shell and private payload importer.
- The editable MOD implementation is still pending M5/M6 diff extraction.
- Reference material lives in `.agent/reports/port-diff-inventory.md` and
  `../s2/.cache/StS2-Launcher_Mod_Manager/src/STS2Mobile/`.

Constraints for the first real implementation:

- No SteamCMD or Steam body download path.
- Read Android-only settings directly from `files/default/<account>/settings.save`.
- Load before user mods and patch the imported PC `sts2.dll` at runtime.
- Keep launcher/runtime responsibilities in `android/`; keep game behavior patches
  here.
