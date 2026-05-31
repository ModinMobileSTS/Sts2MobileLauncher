# MOD 兼容性排查规范补充

日期：2026-05-30

## 背景

需要为后续维护者补充普通 MOD 兼容性排查时的参照来源和操作约定，特别是前置 MOD、PC 原版加载时序以及无源码 MOD 的诊断方式。

## 改动

- 在 `AGENTS.md` 新增 “MOD 兼容性排查规范” 小节。
- 在 `doc/modding/mod-and-compat-notes.md` 同步新增对应长期文档说明。
- 记录常见依赖仓库：
  - `https://github.com/BAKAOLC/STS2-RitsuLib`
  - `https://github.com/Alchyr/BaseLib-StS2`

## 验证

- 已运行 `tools/package/build_importer_apk.sh`，构建成功并输出 `dist/sts2-re-importer.apk`。
- 构建过程中仅出现既有 Java/Gradle/.NET 警告，未出现错误。

## 注意事项

- 第三方 MOD 源码、构建产物和反编译结果只作为本地诊断参考，不应提交到本仓库。
