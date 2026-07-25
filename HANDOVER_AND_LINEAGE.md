# 项目传承与上游关系 · Handover & Lineage

这份文档给未来接手 Local Agent Plaza 的工程师或 AI 使用，记录项目来源、当前稳定基线、分支策略和发布注意事项。

## 一句话介绍

Local Agent Plaza 是一款基于 Google AI Edge Gallery 扩展的 Android 端侧 AI 应用。它把本地对话、本地智能体、MCP、图片问答、音频问答、视频问答、实时视觉旁白、本地视觉创作、AI 键盘、工作区文件管理、联网搜索、TTS、多媒体处理和多模态 API 技能整合到同一个移动端应用中。

## 上游关系

```
google-ai-edge/gallery
        |
        | Fork
        v
zhangzheyuanviolin-ship-it/gallery
        |
        | Rename
        v
zhangzheyuanviolin-ship-it/local-agent-plaza
```

本项目遵循上游 Apache License 2.0。上游提供了 AI Edge Gallery 的基础 Android 架构、LiteRT-LM 示例、本地对话、多模态任务、Mobile Actions 和 Tiny Garden 等能力。本项目在此基础上形成独立产品命名、独立包名、稳定发布分支、实验分支和端侧智能体扩展。

## 当前稳定基线

- 稳定分支：`main`
- 稳定 tag：`v1.0.14-plaza.5`
- 稳定包名：`com.localagent.plaza`
- 长期实验分支：`experimental`
- 实验包名：`com.localagent.plaza.mcp`
- Android 最低版本：API 31
- ABI：`arm64-v8a`

`main` 只接收已经在 `experimental` 真机测试通过的能力。`experimental` 保留为下一阶段新模块开发分支，发布稳定版本后不要删除。

## 关键里程碑

- 2026-06-06：发布 `v1.0.13-plaza.1`，确立 Local Agent Plaza 命名、仓库名和稳定包名。
- 2026-06-22：视觉创作稳定化，加入本地图像生成和生成图 VLM 后处理。
- 2026-07-15：发布 `v1.0.14-plaza.2` 到 `v1.0.14-plaza.4`，稳定化 MCP、AI 键盘、48 模型内置白名单和国内模型下载源。
- 2026-07-16 至 2026-07-22：在 `experimental` 分支完成视频问答、工作区文档读取、工具审计、天气、Edge TTS、Agnes、MiniMax、多媒体工具箱、网页提取、AnySearch 和媒体工具加固。
- 2026-07-25：发布 `v1.0.14-plaza.5`，把上述实验能力合并到稳定主分支。

## 当前能力范围

当前稳定版本包含：

- 48 个内置模型条目，其中 AI 对话 / Prompt Lab 46 个，智能体 41 个，图片问答 8 个，音频问答 5 个。
- 32 个视觉创作模型。
- 15 个 AI 键盘 Vosk 语音转文字模型。
- 21 个内置智能体技能。
- MCP 客户端和 4 个默认 MCP 预设。
- 视频问答任务，支持完整视频抽帧和指定关键帧。
- 文件工作区，支持 PDF、DOCX、XLSX 读取和完整工具审计。
- 多媒体工具箱，支持图片、音频、视频常用处理。

## 发布流程

1. 在 `experimental` 完成真机测试。
2. 更新 README、技术总览、版本历史和相关说明。
3. 合并 `experimental` 到 `main`。
4. 创建 `v1.0.14-plaza.x` tag 并推送。
5. 等 GitHub Actions 构建完成。
6. 校验 Release APK 的包名、版本、签名和 sha256。

稳定 tag 构建的 APK 必须是 `com.localagent.plaza`。实验分支构建的 APK 必须是 `com.localagent.plaza.mcp`。

## 本地环境注意事项

手机本地 Ubuntu 环境可能无法启动 AAPT2 daemon，因此本地 Gradle 不能作为正式发布依据。正式 APK 以 GitHub Actions 的签名构建和包名校验为准。

下载 GitHub Actions artifact 或 Release APK 时必须从第一次下载开始使用 `curl -L -C -` 断点续传。

## 上游同步建议

不要机械地把上游大版本直接合入 `main`。建议先在单独分支同步上游，逐项检查：

- `versionName` 与模型白名单文件名的对应关系。
- LiteRT-LM、Function Calling 和模型配置接口是否变更。
- Gallery 上游任务 ID、模型任务过滤和资源路径是否变更。
- 本项目新增的视觉创作、AI 键盘、视频问答、技能系统和工作区工具是否受到影响。

## 交接优先阅读

- `README.md`
- `docs/TECHNICAL_OVERVIEW.md`
- `RELEASE_NOTES.md`
- `Function_Calling_Guide.md`
- `VISION_NARRATION.md`

## 许可

本项目遵循 Apache License 2.0。完整许可条款见 `LICENSE`。
