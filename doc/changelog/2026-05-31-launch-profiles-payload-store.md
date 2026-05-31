# 2026-05-31 launch profile 与 payload store 多版本模型

## 背景

旧版本管理使用固定 `<files>/game/` active 目录和 `<files>/game-versions/<id>/game/` 归档目录。导入和切换版本都需要复制完整游戏本体，且存档、设置、MOD 目录全局共享，无法为同一个游戏本体准备多套互相隔离的测试环境。

## 改动

- 新增 `LaunchProfileManager`：
  - 游戏本体安装到 `<files>/payloads/<payload_id>/game/`。
  - 启动配置保存到 `<files>/instances/<profile_id>/instance.json`。
  - 每个启动配置绑定 payload、兼容包，并分别记录存档/设置与 MOD 使用 `global` 或 `isolated`。
- `PayloadManager` 导入 zip 后直接安装到 payload store，不再原子替换 `<files>/game/`，导入完成后创建/选择默认 launch profile。
- “版本”页改为同时管理：
  - 当前选择的启动配置；
  - 已安装游戏本体；
  - 可为同一游戏本体新建多个启动配置；
  - 每个配置可选择全局或隔离的存档/设置目录、MOD 目录；
  - 兼容包选择会写入当前启动配置。
- `GodotApp`、`GameLaunchPreparationManager`、`ExtraSettingsRepository` 改为从当前 launch profile 动态解析：
  - PCK / assemblies 源目录；
  - settings/save 根目录；
  - MOD 根目录；
  - 日志目录。
- C# 兼容层 `AppPaths` 从 Mono publish 目录推导 `<files>` 后读取 `<files>/launcher/selected_instance.json` 获取当前 profile 的 game/settings/mods 路径；新增 `SavePathPatches` 把原版 `UserDataPathProvider` 重定向到当前 profile 的 account root，使游戏内 SaveManager 与 Java 设置/备份路径一致。内置多分支 compat pack staging 会把这些路径桥同步到临时 worktree，保证 v0.103.2 与 v0.106.1-beta 内置包都支持 launch profile 路径。
- profile/payload 切换时，Mono publish 目录会清理旧 payload 遗留的游戏 DLL/JSON，再复制当前 payload assemblies。
- 旧 `<files>/game/` 与 `<files>/game-versions/<id>/game/` 会在启动器 bootstrap 时尽量通过 rename 迁移到 payload store，避免大文件复制。

## 目录约定

```text
<files>/payloads/<payload_id>/game/          # 导入游戏本体
<files>/instances/<profile_id>/instance.json # 启动配置
<files>/instances/<profile_id>/default/1/    # 隔离存档/设置
<files>/instances/<profile_id>/mods/         # 隔离 MOD
<files>/instances/<profile_id>/logs/         # profile 日志
<files>/default/1/                           # 全局存档/设置
<files>/mods/                                # 全局 MOD
<files>/launcher/selected_instance.json      # 当前启动上下文
```

## 验证

- `tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac`
- `../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original-v0.106.1 -v:q`
- `tools/package/build_importer_apk.sh`
  - 输出：`dist/sts2-re-importer.apk`
  - APK sha256：`25f6c045db2ac990893f5bd4006095edb36bbe7388449edb1c255aa1226f591f`

## 注意事项

- 删除 launch profile 会删除该 profile 下的隔离存档/MOD/日志目录；共享 payload 仍保留。
- 删除 game body 会删除指向它的 launch profiles；全局存档和全局 MOD 不会被删除。
- 新建 launch profile 默认选择隔离存档/设置和隔离 MOD，导入 payload 自动创建的默认 profile 保持全局模式以兼容旧行为。
