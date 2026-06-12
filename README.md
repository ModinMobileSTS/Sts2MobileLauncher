
<div align="right">
  <strong><a href="README_CN.md">🇨🇳 简体中文 (Chinese)</a></strong>
</div>

<p align="center">
  <!-- Replace with your actual app icon path or image URL -->
  <img src="doc/images/icon.png" width="128" alt="App Icon">
</p>

<h1 align="center">Slay the Spire 2 Android Launcher</h1>

<p align="center">
  An unofficial, open-source mobile compatibility layer and launcher environment for <i>Slay the Spire 2</i>, based on the Godot/Mono runtime.
</p>

<p align="center">
  <a href="https://github.com/ModinMobileSTS/Sts2MobileLauncher/blob/main/LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android_7.0+-brightgreen.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Godot-4.5_Mono-478CBF.svg" alt="Godot">
</p>

## Screenshots

<p align="center">
  <!-- Please replace the src with actual screenshot paths -->
  <img src="doc/images/screenshot_1.jpg" width="24%" alt="Home Dashboard">
  <img src="doc/images/screenshot_2.jpg" width="24%" alt="Steam Download">
  <img src="doc/images/screenshot_3.jpg" width="24%" alt="MOD Management">
  <img src="doc/images/screenshot_4.jpg" width="24%" alt="In-game Footage">
</p>

## About the Project

This project is an experimental, unofficial Android port and launcher framework for *Slay the Spire 2*. It **DOES NOT** contain any base game files. Instead, it provides an Android shell that allows players to import and run their legally owned PC game files on mobile devices, featuring support for Mod loading, local save snapshots, Steam Cloud and WebDAV save synchronization, and launcher update checks from the About page.

**The core architecture consists of three layers:**
1. **Android Launcher Shell (`android/`):** Handles game data importing, Steam login and game downloading, local save snapshots, Steam Cloud/WebDAV save syncing, and local file/MOD management. Once everything is ready, it boots up the Godot game process.
2. **Android Compatibility Pack (`port-mod/` submodule):** Acts as a low-level hook (based on Harmony), loaded at the very beginning of the game boot process. It intercepts and fixes various PC-to-Android incompatibilities (e.g., input adaptation, path redirection, PC-specific shader replacement, Mod loader bridging).
3. **Base Game (Provided by User):** Supplied by the user either by importing the PC version's `SlayTheSpire2.zip` or by legally downloading it via the SteamPipe API after logging into their Steam account within the app.

---

## Legal Disclaimer

- **Unofficial Project:** This is an open-source technical research project created by the player community. It is not affiliated with Mega Crit, *Slay the Spire 2*, or the Godot Engine, nor does it represent their views.
- **No Game Assets Provided:** This repository **ABSOLUTELY DOES NOT** contain or distribute any copyrighted commercial game assets (including but not limited to audio, images, PCK files, core logic DLLs, etc.).
- **Legal Use:** Please comply with relevant software licenses, platform rules, and local laws. You must **legally own** a PC copy of *Slay the Spire 2* to use this tool to run the game on your own device.
- **No Pirated APK Distribution:** Do not use standalone APKs bundled with commercial game assets for public release or commercial monetization.

---

## Credits & References

The creation of this project relies heavily on the explorations of the open-source community. Special thanks to the following projects for their inspiration and code references:

- **[StS2-Launcher_Mod_Manager](https://github.com/iunius612/StS2-Launcher_Mod_Manager)**
  Provided underlying concepts for stripping the Godot/Mono runtime, Android compatibility patch load orders, and design references for some build scripts.
- **[SlayTheAmethystModded](https://github.com/ModinMobileSTS/SlayTheAmethystModded)**
  An unofficial mobile launcher for STS1. The reverse-engineered integration and source code for `steam-protocol`, `steam-content` (SteamPipe game downloads), and Steam Cloud saves in this project are primarily ported/adapted from it.
- **[STS2-RitsuLib](https://github.com/BAKAOLC/STS2-RitsuLib) / [BaseLib-StS2](https://github.com/Alchyr/BaseLib-StS2)**
  Served as vital test baseline reference libraries for troubleshooting Android MOD compatibility.
- **[Google Material Symbols](https://fonts.google.com/icons)**
  Provides the official rounded icon outlines used by the launcher UI, generated into Android vector drawables from the bundled font.

*(For detailed third-party open-source licenses, please see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md))*

---

## Core Security Notes

For the safety of your device and accounts, please pay strict attention to the following when using and compiling this app:

1. **ADB & `Debuggable` Risks:**
   The current default release build configuration **keeps `debuggable=true`** (to facilitate log capture in case of crashes). This means any computer or malicious software connected to your phone with `ADB` permissions can extract data from this app (including encrypted Steam credentials). **NEVER grant ADB debugging permissions to untrusted computers or third-party app stores.**
2. **Steam Account Security:**
   - This app will **NEVER** upload your Steam account password to any third-party server. The password is only used for a one-time request to Steam servers to exchange for a `Refresh Token`.
   - The `Refresh Token` is encrypted and stored locally via Android's `EncryptedSharedPreferences`.
   - **Strongly Recommended:** Only log into Steam using an APK compiled by yourself from trusted source code or obtained from a highly trusted channel.
3. **Malicious MOD Risks:**
   Mods for *Slay the Spire 2* are essentially arbitrarily executed C# code. **Malicious Mods can bypass the sandbox to directly read local files on your phone (including configurations containing your Steam Token).** Before attempting to install unknown Mods from untrusted sources, **make sure to log out of Steam in the app settings** to prevent account theft.

---

## How to Build (APK Packaging)

> **Note:** For complete environment configuration and parameter details, please refer to [`doc/build/building-and-packaging.md`](doc/build/building-and-packaging.md).

### 1. Prerequisites
- **OS:** Linux / macOS / WSL (Windows)
- **Toolchain:** 
  - JDK 17+
  - Android SDK (API 35) & NDK
  - .NET SDK (for compiling C# compat plugins)
  - Python 3

### 2. Get the Source Code
Because it includes the compatibility pack submodule, please clone with the `--recursive` flag:
```bash
git clone --recursive https://github.com/ModinMobileSTS/Sts2MobileLauncher.git
cd Sts2MobileLauncher
```

### 3. Local Environment Setup
Copy the environment variable templates and modify the `.env` and `local.properties` files according to your actual local paths:
```bash
cp .env.example .env
cp local.properties.example local.properties
```
> **Note:** The `.env` file must configure `JAVA_HOME`, `ANDROID_HOME`, `DOTNET_BIN`, and the original PC DLL reference paths used for compiling the compatibility pack (`STS2_ORIGINAL_*_REFERENCE_DIR`).

### 4. Sync Runtimes and Dependencies
Run the following script to extract large runtime artifacts (Godot templates, FMOD, etc.) to their designated locations (these files are git-ignored and must be generated locally):
```bash
tools/android/sync-runtime-from-references.sh
```

### 5. Compile Compatibility Packs (Compat Packs)
Compile the mobile compatibility layer C# code into DLLs and package them as a ZIP into the Assets:
```bash
tools/android/stage-bundled-compat-packs.sh
```
This now builds the flattened schema-2 family pack from one checkout by default. Legacy per-version branch packs remain available for diagnostics:
```bash
COMPAT_PACK_BUILD_MODE=legacy tools/android/stage-bundled-compat-packs.sh
```

### 6. Build the Importer APK
Run the build script. This will output an "Importer APK" that **DOES NOT** contain the base game (the recommended, legally compliant distribution method):
```bash
tools/package/build_importer_apk.sh
```
Upon successful build, the APK will be output to `dist/sts2-re-importer.apk`.

Android high-refresh support is part of the normal APK: the app does not mark
itself as an Android game category, and `GodotApp` requests the highest exposed
display mode through Android `Window` / `Surface` frame-rate APIs. A disabled-by-default
performance overlay can be enabled from Extra Settings → System.

---

## More Documentation

If you want to contribute to development, understand how the compatibility packs work, or dive deeper into the architecture, please check the `doc/` directory:

- [Project Structure & Version Model](doc/architecture/project-structure.md)
- [Detailed Guide to Building & Packaging](doc/build/building-and-packaging.md)
- [Runtime Loading & Compat Pack Lifecycle](doc/runtime/compat-pack-loading-flow.md)
- [Notes on Developing MOD Compatibility Patches](doc/modding/mod-and-compat-notes.md)
