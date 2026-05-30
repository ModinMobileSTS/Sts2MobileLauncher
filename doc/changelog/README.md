# Changelog

本目录记录本仓库每一次用户可见、维护相关或工程规范相关的修改。每条记录使用独立 Markdown 文件，文件名建议：

```text
YYYY-MM-DD-short-topic.md
```

## 必填内容

- **背景**：为什么做这次修改。
- **改动**：改了哪些文件/流程/约定。
- **验证**：执行了哪些命令，尤其是是否构建了 importer APK。
- **风险 / 后续**：已知风险、需要后续维护者注意的点。

## 注意事项

- 不记录用户私有游戏 zip、keystore 或商业资源内容。
- 如果修改涉及 `port-mod` submodule，需要记录目标分支、compile gate、父仓库 submodule 指针状态。
- 如果修改涉及构建产物，记录输出路径即可；大型产物本身由 `.gitignore` 或既定规则控制。
