# 项目结构与版本模型

## 1. 分层定位

本仓库把 Slay the Spire 2 Android 侧拆成三层：

1. **Android shell / launcher / 附加设置**：位于 `android/`，负责 UI、导入、版本/兼容包管理、启动 Godot、日志和文件管理。
2. **原版游戏 payload**：用户本地提供 PC zip，导入到应用私有目录；仓库不包含游戏本体。
3. **Android 兼容包**：位于 `port-mod/` submodule，按游戏版本分支编译 `STS2Mobile.dll` 和 `port_compat.pck`。

## 2. 仓库目录职责

```text
s2_re/
  AGENTS.md                        # agent/维护者总入口
  README.md                        # 普通开发/测试入口
  android/                         # Android shell + Godot Gradle 工程
  port-mod/                        # git submodule: 多分支 Android 兼容 patcher
  tools/android/                   # runtime 同步、Gradle 环境、compat pack staging
  tools/package/                   # importer/direct APK 打包、payload zip 校验
  tools/git/                       # 父仓库/submodule HEAD 巡检
  doc/                             # 新规范化文档入口
  docs/                            # 历史 diff/validation 资料
  dist/                            # 本地构建输出，gitignore
  .agent/                          # agent 临时计划/报告/工作树，gitignore
```

## 3. Android shell 主要组件

- `GameSettingsActivity`：默认 launcher，承载欢迎向导、设置页、版本页、MOD 页，负责启动前检查。
- `GodotApp`：真正的 Godot Activity，拼接 Godot 命令行，加载 imported PCK 或 bootstrap PCK，暴露 Java bridge 给 C#。
- `PayloadManager`：导入 PC zip、校验必需文件、patch 私有 PCK copy、写 `.payload_manifest.json`。
- `GameBodyVersionManager`：归档当前 payload 到 `<files>/game-versions/<id>/game/`，支持切换。
- `CompatPackManager`：安装/选择/删除兼容包，从 APK assets 安装内置包，按 payload version 匹配。
- `GameLaunchPreparationManager`：启动前准备 Mono publish 目录、兼容包 dll、overlay pck、游戏 assemblies、纹理缓存。

## 4. 版本矩阵

| 通道 | 游戏版本 | 原版目录 | port-mod 分支 | ReferenceFlavor | 兼容包 id |
| --- | --- | --- | --- | --- | --- |
| 正式/稳定 | `v0.103.2` | `../s2_original/s21032/` | `compat/v0.103.2` | `original` | `sts2-android-compat-v0.103.2` |
| Beta | `v0.106.1` | `../s2_original/s201061/` | `compat/v0.106.1-beta` | `original-v0.106.1` | `sts2-android-compat-v0.106.1-beta` |

内置兼容包列表由 `tools/android/bundled-compat-packs.json` 控制。打包脚本会用 `stage-bundled-compat-packs.sh` 为列表中的每个分支构建 zip 并复制到 `android/assets/compat_packs/`。

## 5. APK assets 与私有运行时目录

APK assets：

```text
android/assets/bootstrap.pck                 # 无 payload 时的最小 bootstrap
android/assets/port_compat.pck               # legacy fallback overlay
android/assets/compat_packs/*.zip            # 内置可安装兼容包
android/assets/dotnet_bcl/                   # 同步的大型 runtime，gitignore
android/assets/payload/SlayTheSpire2.zip     # 直装版临时 payload，gitignore
```

应用私有目录：

```text
<files>/game/                              # 当前激活 payload
<files>/game/.payload_manifest.json        # payload 身份与 patch 记录
<files>/game-versions/<id>/game/            # 已归档 payload 版本
<files>/compat-packs/<pack_id>/             # 已安装兼容包
<files>/launcher/selected_game_version.json # 当前游戏版本选择
<files>/launcher/selected_compat_pack.json  # 当前兼容包选择
<files>/default/1/settings.save             # 附加设置与游戏设置
<files>/mods/                              # 普通用户 MOD
<files>/.godot/mono/publish/arm64/          # Mono publish 目录
<files>/port_compat.pck                    # 启动前 staging 的 overlay
<files>/logs/                              # Godot/launcher 日志
```

## 6. 版本选择模型

当前实现是“激活目录 + 归档版本”的轻量模型：

- Godot 实际启动固定使用 `<files>/game/`。
- 版本页可把当前 `<files>/game/` 归档到 `<files>/game-versions/<id>/game/`。
- 切换版本时，把归档版本复制回 `<files>/game/`，并更新选择记录。
- 导入新 payload 成功后，会自动归档并尝试选择匹配兼容包。

未来如果演进为完整 instance/profile 模型，应避免直接破坏现有路径，先提供迁移层：

- 当前 `<files>/game`、`<files>/mods`、`<files>/default/1/settings.save` 仍是 Java 与 C# 默认桥接路径。
- 新 instance 路径需要同步 `GodotApp` 静态 bridge、`AppPaths`、`ModLoaderPatches`、设置/存档迁移、版本页 UI。

## 7. 不提交内容

禁止提交：

- 用户 PC 游戏 zip、解压 payload、`../s2_original/*.zip`。
- `android/assets/dotnet_bcl/`、`android/libs/`、`android/assets/payload/`。
- keystore/jks/p12 等签名私钥。
- `dist/`、APK/AAB/APKS、.NET bin/obj。

允许但需谨慎：

- `android/assets/compat_packs/*.zip`：这是脚本生成的兼容包 zip，不含游戏 payload；提交前确认 manifest、branch、hash 正确。
