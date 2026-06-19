# 第三方来源与许可证说明

本文件是仓库级别的第三方来源/许可证摘要，便于维护和发布前审计；它不是法律意见。仓库不包含《Slay the Spire 2》商业游戏本体、用户 payload、完整 Godot/Mono runtime 或签名密钥。

## 项目 LICENSE 建议

建议本仓库**原创代码**采用 **MIT License**（已在 [`LICENSE`](LICENSE) 中添加）。理由：

- 直接参考的 `StS2-Launcher_Mod_Manager` 使用 MIT；Godot Android template 也是 MIT。
- 当前 Gradle/.NET 依赖大多为 MIT、Apache-2.0、BSD 系列等宽松许可证，MIT 与其兼容性较好。
- MIT 不会把本仓库没有权利再授权的内容一并改许可证；第三方源码、模板、二进制 runtime、FMOD、Steam/NexusMods 服务 API、用户游戏文件等仍遵循各自许可证/服务条款。

注意：`SlayTheAmethystModded` 仓库截至本次整理未暴露标准 SPDX 顶层 `LICENSE`，但带有 `NOTICE` / `THIRD_PARTY_LICENSES.md`。`WorkshopAndroidDownloader` 仓库截至本次整理也未检测到标准顶层 `LICENSE`。本仓库中从这些项目改编/参考的 Steam 协议、SteamPipe 与 Workshop 下载实现应继续保留出处说明；公开分发前建议向上游确认可再分发许可或保留单独授权记录。

## 直接引用资源 / 参考代码实现的仓库

| 名称 | URL | 本仓库用途 | 上游许可/注意事项 |
| --- | --- | --- | --- |
| SlayTheAmethystModded | <https://github.com/ModinMobileSTS/SlayTheAmethystModded> | Steam 登录、SteamPipe 下载、Steam Cloud 相关实现与设计参考；`android/steam-protocol/`、`android/steam-content/` 的协议/下载代码从该项目思路与源码改编。 | 顶层许可未检测到标准 SPDX；上游包含 `NOTICE` 与第三方许可证摘要。保留署名，发布前确认改编源码授权。 |
| WorkshopAndroidDownloader | <https://github.com/Apricityx/WorkshopAndroidDownloader> | Android Steam Workshop 浏览、下载、UGC manifest/chunk 下载与已下载条目更新记录流程参考；本仓库新增的 Workshop 页面、`SteamPublishedFileClient` 与 `android/steam-content/.../workshop/` 相关实现参考/裁剪自该项目。 | 顶层许可未检测到标准 SPDX。保留署名，发布前确认改编源码授权。 |
| StS2-Launcher_Mod_Manager | <https://github.com/iunius612/StS2-Launcher_Mod_Manager> | Android launcher/runtime、Godot/Mono publish 目录、兼容补丁加载顺序与构建脚本的参考来源；`.env` 可指向本地准备的 runtime/template 产物。 | MIT License。运行时二进制产物不提交到本仓库，随本地配置同步/构建。 |
| sts2-android-compat | <https://github.com/ModinMobileSTS/sts2-android-compat> | `port-mod/` submodule：Android 兼容补丁源码，按游戏版本分支构建 `STS2Mobile.dll` 与 `port_compat.pck`。 | 独立仓库；以其自身许可证/提交历史为准。 |
| godot-debug-menu | <https://github.com/godot-extended-libraries/godot-debug-menu> | `port-mod/overlay/addons/debug_menu/` 打包该 Godot 4.x debug menu add-on，作为设置页可选的游戏内性能 overlay，显示 FPS、帧时间、CPU/GPU frame graph 和硬件/渲染器信息。 | MIT License；保留上游 `LICENSE.md`。 |

## 直接资源、模板与本地同步产物

| 组件 | 本仓库位置/用途 | 许可证/注意事项 |
| --- | --- | --- |
| Godot Engine Android template | `android/` Gradle template 结构、`GodotApp.java` 等；构建时同步 Godot Android AAR / GodotSharp / Mono publish runtime 到 gitignored 路径。 | MIT。保留 Godot 文件头；完整 runtime 不入库。 |
| Material Symbols Rounded | `android/res/font/material_symbols_rounded.ttf` 作为离线生成输入；`tools/android/generate-material-symbol-vectors.py` 从字体导出 `android/res/drawable/ic_ms_*.xml` 官方轮廓 vector，Java 侧 `MaterialSymbols` 运行时加载这些 vector 统一启动器 MD3 图标。 | Google Fonts / Material Symbols，Apache-2.0；生成的 vector 资源仍按该来源审计。 |
| FMOD Android plugin | 通过 `.env` 的 `STS2_FMOD_PLUGIN_AAR` 同步到 `android/libs/`，用于音频插件兼容。 | FMOD 自身许可；本仓库不提交 AAR，分发前需确认授权。 |
| .NET / Godot Mono runtime 与 crypto native jar | 通过 `.env` / `tools/android/sync-runtime-from-references.sh` 同步到 `android/assets/dotnet_bcl/` 等 gitignored 路径。 | .NET / Godot / 上游组件各自许可证；发布包前审计实际打入 APK 的文件。 |
| Android compatibility pack zips | 构建时生成到 `android/assets/compat_packs/*.zip`，APK 打包阶段作为 assets 安装源。 | 生成产物不再由 git 跟踪；从 `port-mod` 分支源码重新构建。 |

## 主要 Gradle / JVM 依赖

| 依赖 | 用途 | 上游许可证 |
| --- | --- | --- |
| AndroidX Fragment / RecyclerView / DocumentFile / Core SplashScreen / Security Crypto | Android UI、文件访问、加密 SharedPreferences。 | Apache-2.0 |
| Material Components for Android | Material 3 UI 组件。 | Apache-2.0 |
| CustomActivityOnCrash (`cat.ereza:customactivityoncrash`) | 崩溃页入口。 | Apache-2.0 |
| OkHttp / Okio | Steam CM WebSocket、HTTP/CDN 请求、NexusMods 请求。 | Apache-2.0 |
| Protocol Buffers / protobuf Gradle plugin | Steam protobuf 消息编解码与生成。 | BSD-3-Clause |
| Kotlin stdlib / coroutines / serialization | Steam 协议与下载子模块。 | Apache-2.0 |
| JavaSteam (`in.dragonbra:javasteam`) | Steam 账号、CM、RemoteStorage 等协议支持。 | MIT |
| Bouncy Castle (`bcprov-jdk18on`) | JavaSteam 加密支持。 | Bouncy Castle License / MIT-style |
| SLF4J NOP | 日志 facade no-op backend。 | MIT |
| XZ for Java | Steam VZip/LZMA chunk 解压。 | Public Domain / 0BSD-style upstream notice |
| zstd Android Prefab (`com.fpliu.ndk.pkg.prefab.android.21:zstd`) | Steam VZstd depot chunk JNI/native 解压。 | zstd upstream BSD-3-Clause / package metadata |
| MTDataFilesProvider (`com.github.L-JINBIN:MTDataFilesProvider`) | debug/dev/release 文件访问 provider 依赖。 | 上游未检测到标准 license；发布前应单独确认。 |

## 可选参考 MOD / 排查仓库

这些仓库仅用于 MOD 兼容性排查或文档参考，不应把其源码/构建产物提交到本仓库：

| 名称 | URL | 用途 | 许可证/注意事项 |
| --- | --- | --- | --- |
| STS2-RitsuLib | <https://github.com/BAKAOLC/STS2-RitsuLib> | 常见前置 MOD 兼容性排查。 | MIT |
| BaseLib-StS2 | <https://github.com/Alchyr/BaseLib-StS2> | 常见前置 MOD 兼容性排查。 | MIT |
| sts2-quickRestart | <https://github.com/freude916/sts2-quickRestart> | 快速重开 UI/流程兼容性参考。 | 未检测到标准 license；仅作本地参考。 |

## 发布前合规检查

1. 确认 APK 不包含用户游戏 zip、解包后的商业 payload、original/reference DLL、keystore 或账号/token。
2. 重新生成并审计 `android/assets/compat_packs/*.zip`，不要把 zip 产物加入 git。
3. 对实际打入 APK 的 Godot/.NET/FMOD/native runtime 与 Gradle 依赖导出完整 license/notice 清单。
4. 对 `SlayTheAmethystModded`、`WorkshopAndroidDownloader` 改编代码和无标准 license 的依赖保留授权依据或替换为自研/明确许可实现。
5. README、`doc/` 与本文件需同步记录新增第三方来源。
