# 2026-06-01 版本页原型重设计

## 背景

用户提供 `~/datas/tmp/prototype_version.html`，希望 Android 启动器“版本”页面按原型重设计，减少原有多张管理卡片堆叠，改成更接近 Material 3 的分段页、列表和底部详情抽屉。

## 改动

- 重写 `android/src/com/godot/game/GameVersionManagerPage.java`：
  - 顶部保留“版本”标题，下方新增三段切换：启动配置 / 游戏本体 / 兼容包。
  - 每个分段使用列表项展示，带圆形图标、当前/已选 badge、单行摘要和右侧箭头。
  - 点击启动配置、游戏本体或兼容包列表项后打开 `BottomSheetDialog` 详情抽屉，展示路径、版本、文件统计、兼容目标、安装/更新时间等信息。
  - 操作按钮迁移到抽屉内：选择、编辑、新建配置、删除/清除本体等；本体分段顶部提供导入、Steam、网盘入口，兼容包分段顶部提供导入和安装内置入口。
  - “网盘”入口打开“关于”页“游戏下载”使用的夸克网盘链接（`ExtraSettingsUpdateChecker.GAME_DOWNLOAD_URL`）。
  - 保留 launch profile / payload store / compat pack 的现有业务逻辑，不改变私有目录模型或启动选择协议。
- 调整 `GameSettingsActivity.showLaunchProfileDialog()` 为同风格底部抽屉，让“新建配置/编辑配置”表单与原型中的表单抽屉一致；新建配置时在名称输入框下新增“游戏本体”选择按钮，可为新 profile 切换要绑定的 payload。
- 新增 `android/res/drawable/ic_cloud_sync_24.xml`，用于“网盘”入口图标。
- 更新 `android/res/values/strings_game_settings.xml` 与 `android/res/values-zh/strings_game_settings.xml`，补充版本页分段、badge、摘要、抽屉详情和预留网盘入口文案。
- 更新 `doc/architecture/project-structure.md`，记录版本页现在按三段对象管理并使用底部详情抽屉。

## 验证

- 已执行多次（重写版本页、配置表单改为底部抽屉、本次细节调整后）：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

结果：构建成功；仅出现既有 Gradle Groovy DSL deprecation warning 与 Java deprecated API 提示。

- 已按项目约定执行完整 importer APK 构建：

```bash
tools/package/build_importer_apk.sh
```

结果：构建成功，输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-importer.apk
```

最近一次 importer APK SHA256：

```text
a5bc7c7f9d94b2d29bc5ff50555398bfc3faab2da0da3fbbacac2302b98a5ca4  android/build/outputs/apk/mono/release/sts2-re.apk
```

最近一次构建刷新内置兼容包 SHA256：

```text
464fdb52d8ed720f9026d0bbfeb9f31af5d03752eb9b6affcdeeef2f1661a911  android/assets/compat_packs/sts2-android-compat-v0.103.2.zip
68b99de4b200acf637ab51e315e55fcfe0f3984ca576d635f3d1645a0f64c461  android/assets/compat_packs/sts2-android-compat-v0.106.1-beta.zip
```

## 风险 / 后续

- “网盘”入口当前只负责打开游戏下载链接，不会自动下载或导入 payload；用户仍需通过浏览器/网盘客户端下载后再走本地 zip 导入。
- 新版本页使用 `BottomSheetDialog` 而不是原型 HTML 的自绘 sheet；视觉结构与交互接近原型，但会遵循当前 Android Material 主题。
- 完整 APK 构建会重建/刷新内置兼容包和 importer APK，耗时较长，提交或发包前需要确认成功输出 `dist/sts2-re-importer.apk`。
