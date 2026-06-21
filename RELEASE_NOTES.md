# Release Notes · 版本历史

## v1.0.13-plaza.visual.1 · 2026-06-22

第二个正式版本。在 v1.0.13-plaza.1 基础上集成 **本地图像生成** 与 **VLM 对生成图后处理** 两大新功能模块，全部经真机功能测试通过，零功能回退。

**版本标识**
- versionName：`1.0.13-plaza.visual.1`
- versionCode：`101`
- 包名：`com.localagent.plaza`
- 构建基线：合并 `feature/local-visual-creation` 到 `main`，合并提交 `75d79124`；源 commit `1afa60a`
- ABI：仅 `arm64-v8a`（图像生成原生层要求）
- APK SHA-256：`823bc3c600d1be491b69213a524e905048ab0e5d7726099c49466b6343439797`
- APK 大小：约 145 MB（152128106 字节）
- APK 下载：[local-agent-plaza-1.0.13-plaza.visual.1.apk](https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/v1.0.0-plaza.visual.1/local-agent-plaza-1.0.13-plaza.visual.1.apk)

**新增功能**
- **本地视觉创作工作台**：支持 SD 1.5 / SDXL / Z-Image 三类基础图像生成模型
- **Local Dream 推理后端**：CMake 原生层（`visual_creation_jni.cpp`）+ `LocalDreamBackendService` 启动并管理本地 SDXL 后端服务
- **QNN HTP NPU 加速**：集成 Qualcomm QNN HTP 系列原生库（V68 / V69 / V73 / V75 / V79 / V81），覆盖骁龙主流 SoC NPU
- **VLM 后处理链路**：生成完成的图像可送回本地视觉模型做语义识别 / 描述，与现有视觉旁白能力打通
- **多模型注册表**：`ImageGenerationModelRegistry` 支持挂接多个图像生成模型，复用现有 LLM 的 ModelManager / DownloadWorker 下载与管理基础设施
- **release keystore 环境变量化**：通过 `ANDROID_RELEASE_KEYSTORE_PATH` 等环境变量支持正式签名构建（未配置时回退到 debug 签名）

**关键代码变更（相对 1.0.13-plaza.1）**
- 新增 Kotlin 业务代码 7 个文件，约 2655 行（VisualCreationScreen / ViewModel / Task / Models / Registry / LocalDreamBackendService / LocalDreamImageGenerationClient / NativeImageGenerationBridge）
- 新增 CMake 原生构建段（C++ JNI 桥 270 行）
- 新增 SDXL cvtbase 资产（tokenizer、unet、vae、clip_skip）
- 新增 QNN HTP 全档原生库（arm64-v8a）
- AndroidManifest.xml：新增 Local Dream 后台服务声明
- 适配 ModelManagerViewModel / DownloadWorker / DownloadRepository 以支持图像生成模型类型

**推理引擎链路**
- LLM / VLM：LiteRT-LM 0.12.0（不变，仍领先上游 0.11.0 一个小版本）
- 图像生成：Local Dream 自研后端 + QNN HTP NPU 加速（独立于 Google 官方推理引擎，上游 google-ai-edge/gallery 本身无图像生成能力）

**已知限制**
- 图像生成路径仅支持 arm64-v8a，不支持 32 位 ABI 与 x86
- QNN HTP NPU 加速需要骁龙设备，非高通 SoC 将回退到 CPU/GPU
- 默认 debug 签名（仅供开发与个人分发），如需正式签名请通过环境变量配置 keystore

---

## v1.0.13-plaza.1 · 2026-06-06

首个对外正式稳定版。功能与上游 1.0.13 完全一致，零功能回退。

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

**变更（与上游 1.0.13 对比）**
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
