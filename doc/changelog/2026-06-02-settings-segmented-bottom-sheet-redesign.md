# 2026-06-02 设置页 Segmented/Bottom Sheet 重设计

## 背景

用户提供 `~/datas/tmp/prototype_settings.html` 作为布局和交互参考，希望 Android 附加设置页避免单页过长，并减少传统下拉菜单和大段说明文本带来的阅读负担。原型只作为参考，功能项仍以当前启动器原有设置为准。

## 改动

- `SettingsPage` 顶部新增“画面 / 操作 / 存档 / 系统” Segmented Button 分区：
  - 画面：渲染/显示预设、画面细项。
  - 操作：触屏/手柄相关设置。
  - 存档：本地存档、Steam 云存档、完整应用数据备份。
  - 系统：启动行为、预加载/兼容包、音频/联机/诊断、LAN、日志。
- 将原来的 `Spinner` 下拉设置改为 Bottom Sheet 单选列表：
  - 分辨率、缩放、字体、MSAA、VSync、宽高比等以紧凑文本单选呈现。
  - 渲染器、桌面图标启动后、日志等级、VFX/Shader 预热等主要项目在 Bottom Sheet 中显示图标、标题和说明。
  - 修复单选项切换后主界面数值已更新、但再次打开 Bottom Sheet 仍勾选旧值的问题；设置行现在会维护当前选中下标。
- 设置行改为更紧凑的卡片行样式，主界面只显示设置名与当前值/开关，详情说明更多放在 Bottom Sheet 或原有信息入口中。
- 预加载详细管理仍保持 Bottom Sheet，总开关 `preload_enabled` 不改写细分项目；细分里的 VFX/Shader 模式也改为 Bottom Sheet 单选。
- 渲染预设、画面预设改为类似原型的同一行小卡片样式，描述文字更短。
- 操作页拆成“操作预设”和“触控细节”两个卡片，避免预设和具体开关混在同一个卡片中。
- 补齐“启用兼容包”的中文文案，并在说明中标注实验性、关闭后可能因缺少 Steam / Steamworks 环境或移动端补丁而无法进入游戏。
- “版本 → 兼容包”列表的兼容包名称改为中间省略，保留尾部版本号，便于区分 `for v0.103.2` / `for v0.106.1` 等目标版本。
- 补充中英文字符串，并更新 `AGENTS.md`、`doc/runtime/compat-pack-loading-flow.md` 中关于设置页布局与预加载入口的说明。

## 验证

- 已执行：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
tools/package/build_importer_apk.sh
```

结果：Java 编译与 importer APK 构建成功，测试 APK 输出到 `dist/sts2-re-importer.apk`。

## 注意事项

- 本次只调整 Android launcher 设置页 UI/交互，不新增或删除 `settings.save` 协议字段。
- 日志等级仍只支持原有 `info` / `debug` / `very_debug`，未引入原型中的“静默”选项。
- 大部分画面/渲染设置仍需完整重启游戏后生效。
