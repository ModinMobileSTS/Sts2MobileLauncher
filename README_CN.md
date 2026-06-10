<p align="center">
  <img src="doc/images/icon.png" width="128" alt="App Icon">
</p>

<h1 align="center">Slay the Spire 2 Android 启动器</h1>

<p align="center">
  基于 Godot/Mono 运行时的非官方开源《杀戮尖塔2》移动端兼容层与启动器环境。
</p>

<p align="center">
  <a href="https://github.com/ModinMobileSTS/Sts2MobileLauncher/blob/main/LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android_7.0+-brightgreen.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Godot-4.5_Mono-478CBF.svg" alt="Godot">
</p>

## 应用截图


<p align="center">
  <img src="doc/images/screenshot_1.jpg" width="24%" alt="首页仪表盘">
  <img src="doc/images/screenshot_2.jpg" width="24%" alt="Steam 下载">
  <img src="doc/images/screenshot_3.jpg" width="24%" alt="MOD 管理">
  <img src="doc/images/screenshot_4.jpg" width="24%" alt="游戏内画面">
</p>

## 项目简介

本项目是一个实验性的、非官方的《杀戮尖塔2》Android 移植与启动器框架。它**不包含**任何游戏本体文件，而是提供了一个 Android 运行外壳，让玩家能在手机上导入并运行自己合法拥有的 PC 版游戏文件，同时支持 MOD 加载、本地存档快照、Steam Cloud/WebDAV 云存档同步，以及关于页的启动器更新检查。

**应用核心架构分为三层：**
1. **Android 启动器外壳 (`android/`)：** 负责游戏数据的导入、Steam 账号登录与本体下载、Steam 云存档同步、本地文件/MOD 管理，并在准备就绪后拉起 Godot 游戏进程。
2. **Android 兼容包 (`port-mod/` 子模块)：** 作为底层 Hook（基于 Harmony），在游戏启动的最早期加载，用于拦截并修复 PC 版在 Android 上的各种水土不服（如输入适配、路径重定向、PC 专有 Shader 替换、MOD 加载器桥接等）。
3. **游戏本体 (用户提供)：** 由用户通过导入 PC 版 `SlayTheSpire2.zip` 或在应用内登录 Steam 账号通过 SteamPipe 接口合法下载。

---

## 法律声明

- **非官方作品：** 本项目为玩家社区开源的技术研究工程，不隶属于 Mega Crit、Slay the Spire 2 或 Godot 引擎官方，也不能代表其任何立场。
- **不提供游戏资产：** 本仓库**绝对不包含**、也不分发任何受版权保护的商业游戏资源（包含但不限于音频、图像、PCK文件、DLL核心逻辑等）。
- **合法使用：** 请遵守相关软件许可、平台规则和当地法律。您必须**合法拥有**《Slay the Spire 2》的 PC 副本，才能使用此工具在您自己的设备上运行游戏。
- **禁止分发侵权 APK：** 请勿将打包了商业游戏资产的直装版 APK 用于公开发布或商业牟利。

---

## 鸣谢与参考项目

本项目的诞生离不开开源社区的探索，特别感谢以下项目的启发与代码参考：

- **[StS2-Launcher_Mod_Manager](https://github.com/iunius612/StS2-Launcher_Mod_Manager)**
  提供了底层的 Godot/Mono 运行时剥离思路、Android 兼容补丁加载顺序以及部分构建脚本的设计参考。
- **[SlayTheAmethystModded](https://github.com/ModinMobileSTS/SlayTheAmethystModded)**
  STS1 的非官方移动端启动器。本作的 `steam-protocol` (Steam 协议)、`steam-content` (SteamPipe 游戏下载) 以及 Steam Cloud 云存档的逆向接入方案与源码主要移植/改编自该项目。
- **[STS2-RitsuLib](https://github.com/BAKAOLC/STS2-RitsuLib) / [BaseLib-StS2](https://github.com/Alchyr/BaseLib-StS2)**
  作为 Android 端 MOD 兼容性排查的重要测试基准参考库。
- **[Google Material Symbols](https://fonts.google.com/icons)**
  提供启动器 UI 使用的官方 Rounded 图标轮廓，并由 bundled 字体离线生成 Android vector drawable。

*(详细的第三方开源协议见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md))*

---

## 核心安全说明

为了您的设备与账号安全，使用及编译本应用时请务必注意：

1. **ADB 与 Debuggable 风险：**
   当前默认的 release 构建选项中**保留了 `debuggable=true`**（为了便于发生崩溃时抓取日志排查）。这意味着任何连接到您的手机并获得 `ADB` 权限的电脑或恶意软件，都能提取此应用的数据（包含加密保存的 Steam 凭据）。**请绝对不要将手机的 ADB 调试权限授予不可信的电脑或第三方应用市场。**
2. **Steam 账号安全：**
   - 本应用**绝不会**将您的 Steam 账号密码上传至任何第三方服务器。密码仅用于单次向 Steam 服务器请求并换取 `Refresh Token`。
   - `Refresh Token` 通过 Android 的 `EncryptedSharedPreferences` 加密保存在本地。
   - **强烈建议：** 只使用从可信源码自行编译或可信渠道获取的 APK 登录 Steam。
3. **恶意 MOD 风险：**
   《杀戮尖塔2》的 MOD 本质上是任意执行的 C# 代码。**恶意 MOD 可以绕过沙盒直接读取您手机上的本地文件（包括存有 Steam Token 的配置）**。在尝试安装来源不明的未知 MOD 之前，**请务必在设置中退出 Steam 登录**，以防账号被盗。

---

## 如何构建本项目 (APK打包)

> **提示:** 完整的环境配置与参数说明请查阅 [`doc/build/building-and-packaging.md`](doc/build/building-and-packaging.md)。

### 1. 环境准备
- **操作系统:** Linux / macOS / WSL (Windows)
- **工具链:** 
  - JDK 17+
  - Android SDK (API 35) & NDK
  - .NET SDK (用于编译 C# 兼容插件)
  - Python 3

### 2. 获取源码
因为包含了兼容包子模块，克隆时请带上 `--recursive` 参数：
```bash
git clone --recursive https://github.com/ModinMobileSTS/Sts2MobileLauncher.git
cd Sts2MobileLauncher
```

### 3. 配置本地环境
复制环境变量模板，并根据你的本机实际路径修改 `.env` 文件：
```bash
cp .env.example .env
cp local.properties.example local.properties
```
> **注意：** `.env` 文件中必须配置好 `JAVA_HOME`、`ANDROID_HOME`、`DOTNET_BIN`，以及用于编译兼容包的原版 PC DLL 引用路径 (`STS2_ORIGINAL_*_REFERENCE_DIR`)。

### 4. 同步运行时与依赖
运行以下脚本，将大型的运行时产物（Godot模板、FMOD等）提取到指定位置（这些文件被 git ignore，需本地生成）：
```bash
tools/android/sync-runtime-from-references.sh
```

### 5. 编译兼容包 (Compat Packs)
将移动端兼容层 C# 代码编译为 DLL 并打包为 ZIP 放入 Assets：
```bash
tools/android/stage-bundled-compat-packs.sh
```

### 6. 构建导入版 APK
执行构建脚本。这将会输出一个**不包含**游戏本体的 “导入版 APK”（推荐的合规分发方式）：
```bash
tools/package/build_importer_apk.sh
```
构建成功后，APK 将输出至 `dist/sts2-re-importer.apk`。

---

## 更多文档

如果您想参与开发、了解兼容包的运作原理或更深入的架构设计，请查阅 `doc/` 目录：

- [项目结构与版本模型](doc/architecture/project-structure.md)
- [构建与打包详细指南](doc/build/building-and-packaging.md)
- [运行时加载与兼容包生命周期](doc/runtime/compat-pack-loading-flow.md)
- [MOD 兼容补丁开发注意事项](doc/modding/mod-and-compat-notes.md)
