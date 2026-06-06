# Release Notes · 版本历史

## v1.0.0-plaza.1 · 2026-06-06

首个对外正式稳定版。功能与上游 1.0.13-ala.32-visiontest 完全一致，零功能回退。

**版本标识**
- versionName：`1.0.13-plaza.1`
- versionCode：`100`
- 包名：`com.localagent.plaza`
- 构建基线：`b58dc5e7c89dfa953dbf925b099e4d465a9fca91`
- APK SHA-256：`6e6a87b9dae626f2ee2e36eb1d4091b8184635dcaf65ede64410d98dfa5ef9de`
- APK 大小：约 116 MB

**新增**
- 仓库更名为 `local-agent-plaza`
- 独立的产品命名（Local Agent Plaza / 本地智能体广场）
- 独立的包名（`com.localagent.plaza`）
- README、VISION_NARRATION、RELEASE_NOTES、HANDOVER_AND_LINEAGE 文档体系
- 仓库 Topics：`on-device-ai` / `android` / `agent` / `local-llm` / `vision` / `accessibility` / `litert-lm` / `tavily` / `vision-narration` / `chinese`
- Issues 面板已开启

**变更（与上游 1.0.13-ala.32-visiontest 对比）**
- 移除 `visiontest` / `zhanglaoshi` / `张老师` 残留
- `versionName` 改为 `1.0.13-plaza.1`，匹配上游 `1_0_13.json` 模型白名单
- `versionCode` 重置为 `100`
- GitHub Actions 工作流构件名改为 `local-agent-plaza-release`
- 仓库描述替换为产品介绍

**继承自上游的能力**
- 本地 LLM 对话（CPU / GPU / NPU 多后端）
- 多模态图片问答
- 智能体工具 AUTO / NATIVE / COMPAT 三档
- Tavily / Exa / LangSearch 网络搜索
- 文件工作区
- 视觉旁白（实时相机抓帧 + TTS 播报 + 历史导出）
- HuggingFace OAuth 登录
- 模型白名单远程加载

**已知问题**
- 默认使用 debug 签名，无法在 Play Store 上架（仅供开发与个人分发）
- 部分 AICore 模型在非 Pixel 设备上不可用
- 首次启动需要联网拉取模型白名单（后续可完全离线）

---

## 内部历史（仅作记录）

以下版本曾以 `gallery-cn-*` 命名发布在旧仓库名 `gallery` 下，现已从 Release 列表中删除，仅在此处保留作为传承记录：

- `v1.0.11-cn` · 中文无障碍版
- `v1.0.12-cn` · 中文无障碍版
- `v1.0.12-cn-final` · 中文无障碍版终版

内部 Tag（`0.9.0` ~ `1.0.11`）来自上游同步，已清理。
