# Release Notes · 版本历史

## v1.0.14-plaza.5 · 2026-07-25

第四个 1.0.14 正式稳定版本。此版本把 `experimental` 分支中经过真机测试的第二阶段能力合并到 `main`，重点新增视频问答、工作区文档读取增强、天气、TTS、Agnes、MiniMax、多媒体工具箱、网页提取和 AnySearch，同时保留 `experimental` 作为后续实验开发分支。

**版本标识**
- versionName：`1.0.14-plaza.5`
- 稳定包名：`com.localagent.plaza`
- 实验包名：`com.localagent.plaza.mcp`
- 稳定分支：`main`
- 实验分支：`experimental`
- Android 最低版本：Android 12 / API 31
- ABI：`arm64-v8a`

**新增与稳定化能力**
- **视频问答**：新增任务入口，复用图片问答视觉语言模型；支持从相册或文件选择器选择视频，完整视频模式按时长均匀抽帧，关键帧模式支持最多 5 个指定时间点。
- **音频转写增强**：放开 30 秒固定限制，支持长音频；支持 MP3 等常见音频格式上传，并在本地转为可处理格式。
- **文件工作区增强**：支持读取 PDF、DOCX 和 XLSX；工具调用完整输出写入 `tool-audit/`，便于审计；支持 URL 下载到工作区并尽量断点续传。
- **工作区目录规范**：推荐使用 `file/`、`media/`、`download/`、`tool-audit/` 四类目录，降低文本、媒体、下载和审计文件混杂。
- **天气查询技能**：支持按当前定位或城市查询当前天气、未来 24 小时和未来一周天气。
- **Edge TTS 技能**：支持列出精简音色、直接文本转 MP3、工作区文本文件转 MP3。
- **Agnes 全模态工具箱**：支持 Agnes 图片生成和视频生成，模型与尺寸参数由技能设置页管理，输出保存到 `media/`。
- **MiniMax 全模态工具箱**：支持中国区 MiniMax Token Plan 的文本生成、图片生成、语音合成、音乐生成、图片分析、视频分析和网络搜索。
- **多媒体工具箱**：新增图片、音频、视频处理技能。图片支持信息、缩放、转格式和图转短视频；音频支持信息、格式转换、压缩、剪辑、拼接和混音；视频支持信息、转格式、缩放、压缩、拼接、剪辑、提取音轨、静音和添加音轨。
- **视频工具加固**：视频拼接会统一编码、尺寸、帧率和音轨；工具层会识别视频路径误入音频工具的情况并自动路由或拒绝，避免生成伪视频文件。
- **网页内容提取技能**：支持传入 URL 后提取标题、描述和正文内容。
- **AnySearch 技能**：支持 AnySearch 搜索、网页提取和垂直搜索子域查询。
- **视觉创作内存优化**：提示词优化模型、图像生成模型和生成图分析模型完成任务后尽量释放资源，降低连续创作时的内存累积风险。
- **模型设置入口优化**：已下载模型可在列表页进入模型参数设置，无需先加载模型进入聊天页。

**技能范围**
- 当前内置技能总数：21。
- 所有技能均遵循用户手动启用原则，未启用技能不会被注入模型上下文。
- 需要密钥、Host、模型、尺寸、音色或模式的技能均通过技能设置页配置，模型调用时只传少量必要参数。

**已知限制**
- 本地模型上下文窗口仍受设备内存和推理后端限制。大型文档或 XLSX 会按当前模型可承受上下文返回部分内容，完整工具输出可在 `tool-audit/` 审计。
- 视频问答当前不内置转码，建议使用系统可解码的 MP4。
- Agnes、MiniMax、AnySearch、Exa、Tavily、LangSearch、MCP 和模型下载需要网络。
- 手机本地 Ubuntu 构建环境可能因 AAPT2 daemon 无法启动而不能完成本地 APK 构建，正式 APK 以 GitHub Actions 云端签名构建为准。

---

## experimental · 视频问答模块 · 2026-07-16

实验分支新增视频问答任务，主分支稳定版本不受影响。

**新增**
- 首页新增“视频问答”任务入口，模型列表复用图片问答的本地视觉语言模型。
- 支持从系统相册选择视频，也支持从文件选择器选择视频文件。
- 完整视频描述模式支持按视频时长均匀抽取 1 到 20 张画面帧。
- 关键帧问答模式支持最多 5 个指定时间点，输入格式支持纯秒数、小数秒和 `mm:ss`，页面内提供示例说明。
- 支持 384、512、768、1024 四档抽帧分辨率上限，并在高帧数或高分辨率设置下提示内存风险。

**限制**
- 当前阶段只使用 Android 系统多媒体引擎抽帧，不内置转码；用户应优先选择系统可解码的 MP4 视频。

---

## v1.0.14-plaza.4 · 2026-07-15

国内下载源补强版本。延续 `v1.0.14-plaza.3` 的 APK 内置 48 模型白名单修复，并进一步补齐模型文件下载候选源。

**修复**
- 下载候选源顺序调整为：已验证 ModelScope 同名模型文件、Hugging Face 中国镜像 `hf-mirror.com`、Hugging Face 官方源 `huggingface.co`。
- 对 48 个内置模型逐项核查 ModelScope 同名仓库与同名 `.litertlm` 文件，其中 24 个能返回真实 `LITERTLM` 文件字节，因此这些模型会获得 ModelScope 下载候选。
- 未通过 ModelScope 核查的模型不会强行伪造固定 ModelScope 地址，避免把 404 作为优先下载源长期暴露给用户。
- 下载 worker 保持断点续传和多源失败回退；某一候选源失败后继续尝试下一候选源。

---

## v1.0.14-plaza.3 · 2026-07-15

紧急稳定修复版本。修复 `v1.0.14-plaza.2` 发布后清理历史 feature 分支导致应用回退到 Google 上游模型白名单的问题。

**修复**
- 将 1.0.14 的 48 模型白名单打包进 APK assets，应用启动优先读取内置白名单，飞行模式下也能立即显示完整模型列表。
- 内置白名单成功读取后会写回磁盘缓存，覆盖可能被上游 7 模型白名单污染的旧缓存。
- 远端 Plaza 白名单 URL 改为 `main` 稳定分支，不再指向会被删除的历史 feature 分支。
- 模型下载 URL 生成增加 Hugging Face 中国镜像 `hf-mirror.com` 候选，并保留 `huggingface.co` 官方源兜底；下载源失败时按候选顺序回退。
- 修复断点续传时服务端不支持 Range 却返回 200 后继续追加临时文件的风险，避免产生损坏模型文件。
- 增加回归测试，确保 APK assets 内置白名单存在且包含 48 个模型，并禁止 Plaza 白名单 URL 再依赖 `feature/` 分支。

**验证**
- 静态验证内置白名单任务数量：AI 对话 46，Prompt Lab 46，智能体 41，图片问答 8，音频问答 5。
- 手机本地 Ubuntu 仍受 AAPT2 daemon 启动失败影响，正式 APK 以 GitHub Actions 云端构建校验为准。

---

## v1.0.14-plaza.2 · 2026-07-15

第三个正式稳定版本。此版本把过去 MCP 与 AI 键盘实验分支中已经稳定的能力合并到 `main`，作为新的主线稳定发布基线。

**版本标识**
- versionName：`1.0.14-plaza.2`
- 包名：`com.localagent.plaza`
- 稳定分支：`main`
- 实验分支：`experimental`
- Android 最低版本：Android 12 / API 31
- ABI：`arm64-v8a`

**模型范围**
- 模型白名单总数：48
- AI 对话 / Prompt Lab：46 个模型
- 智能体任务：41 个模型
- 图片问答：8 个模型
- 音频问答：5 个模型
- Tiny Garden：1 个模型
- Mobile Actions：1 个模型
- 视觉创作：32 个图像生成模型
- AI 键盘语音转文字：15 个 Vosk 模型

**新增与稳定化能力**
- **MCP 集成**：智能体支持添加远程 MCP Server，读取工具列表，启用或禁用工具，并把 MCP 工具提示注入本地智能体上下文；默认预设 DeepWiki、Microsoft Learn、Context7、GitMCP: Google AI Edge Gallery。
- **智能体工具增强**：保留 AUTO / NATIVE / COMPAT 三档工具调用模式，兼容不稳定模型输出；内置技能扩展到 14 个，覆盖搜索、文件工作区、地图、二维码、日历、邮件、哈希、维基百科和长文本写作等能力。
- **模型目录扩展**：同步 1.0.14 模型白名单，增加 Qwen、Gemma、MiniCPM、JOSIE、VibeThinker、长上下文和不同量化模型条目，并按任务进行能力过滤。
- **本地视觉创作扩展**：视觉创作注册表扩展到 32 个模型，覆盖 SD1.5 QNN 8gen2、SDXL QNN 8gen3、MNN CPU 和 stable-diffusion.cpp 相关后端链路；生成图可以继续交给本地视觉语言模型处理。
- **AI 键盘**：新增系统输入法模块，支持基础键盘、标点和空格插入、离线语音输入、模型切换、流水线切换、流水线设置、翻译目标语言、自定义流水线和日志审计。
- **AI 键盘流水线**：内置 18 条预设流水线，包括润色、校对、重写、简化、专业、日常、缩写、扩写、总结、要点、邮件、聊天、Twitter、列表、表格、翻译、文本补全和自定义。
- **AI 键盘稳定性**：文本模型推理从输入法服务中隔离出来，避免大模型上下文压垮 IME 服务；已针对 2B、4B、12B 模型和同一文本框多轮流水线处理做过稳定性验证。
- **流水线日志**：记录原文、提示词、原始输出、清洗输出、提交后读回、目标编辑器、提交方式、首 token 延迟、推理耗时、提交耗时、总耗时和输出速度。
- **文本补全追加模式**：文本补全流水线使用追加提交，不替换原始未完成文本，适合补齐被截断或未完成的英文、中文和长文本。

**仓库治理**
- `main` 作为稳定发布分支。
- `experimental` 作为长期实验分支，后续新功能先在此分支验证。
- GitHub Actions 已支持 `main`、`experimental` 和 `feature/**` 构建；tag 触发时自动发布 Release。
- README 与技术文档已更新到当前完整功能范围。

**已知限制**
- 手机本地 Ubuntu 环境中 AAPT2 daemon 可能无法启动，正式发布以 GitHub Actions 云端构建和签名校验为准。
- 图像生成模型体积较大，SDXL QNN 模型建议在高内存高通设备上使用。
- MCP、搜索技能、Hugging Face 下载和部分远程工具需要网络。
- AI 键盘提交结果仍受目标文本框自身限制影响，例如某些启动器搜索框会限制可容纳文本长度。

---

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
