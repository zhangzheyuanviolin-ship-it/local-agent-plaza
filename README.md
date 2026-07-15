# Local Agent Plaza · 本地智能体广场

Local Agent Plaza 是一款面向 Android 手机和平板的本地 AI 应用，把对话、智能体、视觉理解、音频理解、视觉创作、系统输入法和可扩展工具调用尽量放在设备端运行。它继承 Google AI Edge Gallery 的端侧推理基础，并在本地智能体、视觉创作、无障碍视觉旁白和 AI 键盘方向做了系统扩展。

当前稳定线：`v1.0.14-plaza.4`
当前稳定包名：`com.localagent.plaza`
当前实验线：`experimental`，实验包名后缀为 `.experimental`，可与稳定版并行安装。历史 MCP 实验包使用过 `.mcp` 后缀。

## 核心定位

本项目不是单一聊天应用，而是一个端侧 AI 能力集合：

- 本地文本对话：46 个可用于 AI 对话和提示词实验室的文本模型。
- 本地智能体：41 个可用于智能体任务的模型，支持工具调用、技能系统和 MCP。
- 多模态理解：8 个图片问答模型、5 个音频问答模型，支持图片、语音和文本混合任务。
- 本地视觉创作：32 个图像生成模型，覆盖 SD 1.5、SDXL、QNN NPU 模型和 MNN CPU 模型。
- 实时视觉旁白：相机抓帧、本地视觉语言模型理解、TTS 播报和历史导出，面向视障场景重点优化。
- AI 键盘：系统输入法形态，支持离线语音输入、本地文本模型处理任意文本框内容、预设流水线、自定义流水线和审计日志。
- 传统 Google AI Edge Gallery 模块：Tiny Garden、Mobile Actions、Prompt Lab、模型管理、性能测试和模型下载管理。

## 功能模块

### 1. AI 对话与提示词实验室

应用内的 AI 对话和 Prompt Lab 共享模型管理基础设施。当前模型白名单共 48 个模型，其中 46 个可用于常规文本对话和提示词实验室。模型列表随 APK 离线内置，飞行模式下也会显示；网络只影响模型文件下载，不影响模型条目存在。模型文件下载会按已验证候选源回退，优先尝试可用的 ModelScope 同名模型文件和 Hugging Face 中国镜像，再回退到 Hugging Face 官方源。模型覆盖 Gemma、Qwen、DeepSeek Distill、MiniCPM、SmolLM、JOSIE、VibeThinker 等系列，并包含 1B 到 14B 级别、长上下文、INT8 / Q4 / WI4 / WI8 等不同量化形态。

运行时支持 LiteRT-LM，本地模型文件下载、导入、删除、选择、参数配置、上下文窗口配置和 CPU / GPU / NPU 后端切换。部分模型支持 thinking 能力或 speculative decoding，并在模型列表中按任务能力进行过滤。

### 2. 本地智能体与工具调用

智能体任务支持 41 个本地模型。工具调用提供三档模式：

- AUTO：自动选择适合当前模型的工具调用方式。
- NATIVE：使用 LiteRT-LM 原生 Function Calling。
- COMPAT：面向不稳定或不支持原生 Function Calling 的模型，使用兼容 JSON 工具调用协议。

智能体工具系统包含技能加载、JS 技能运行、本地文件工作区、联网搜索、地图、二维码、哈希、日历、邮件、长文本写作、维基百科查询等能力。应用内置 14 个可直接打包的技能：

- calculate-hash
- create-calendar-event
- exa-search
- file-workspace
- interactive-map
- kitchen-adventure
- langsearch-search
- long-text-writer
- mood-tracker
- qr-code
- query-wikipedia
- send-email
- tavily-search
- text-spinner

智能体也支持用户导入本地技能、从 URL 添加技能、配置技能密钥、测试技能，并通过诊断日志追踪工具调用过程。

### 3. MCP 集成

应用集成 Model Context Protocol 客户端能力，支持添加远程 MCP Server、读取工具列表、启用或禁用工具，并把 MCP 工具提示注入智能体上下文。默认预设包含：

- DeepWiki：围绕公开 GitHub 仓库文档和代码结构提问。
- Microsoft Learn：检索 Microsoft Learn 官方文档。
- Context7：检索开源库文档和代码示例。
- GitMCP: Google AI Edge Gallery：把上游 Gallery 仓库转换为 MCP 文档工具。

MCP 与传统技能系统可以并存，适合把本地模型扩展为能查询外部文档、工具和知识源的端侧智能体。

### 4. 图片问答、音频问答和语音输入

图片问答当前可选择 8 个具备视觉能力的模型，用于图片描述、图片问答、视觉信息抽取和生成图复核。音频问答当前可选择 5 个支持音频能力的模型，用于语音或音频片段理解。

普通聊天输入区保留文字和语音输入组件。AI 键盘模块另行集成 Vosk 语音转文字模型，支持在输入法内部离线识别语音并提交到当前文本框。

### 5. 实时视觉旁白

视觉旁白是本项目面向无障碍场景的重点模块。它可以使用相机实时抓帧，把画面送入本地视觉语言模型，并通过自定义提示词生成场景描述，再用系统 TTS 播报。用户可以控制抓帧间隔、提示词、播报策略，并导出历史记录为文本或 Markdown。

这个模块适合视障用户进行环境感知、物体识别、场景解释、文字辅助理解，也适合普通用户把手机相机变成本地视觉助手。

### 6. 本地视觉创作

视觉创作模块把本地文生图引入 Android 应用。目前注册 32 个图像生成模型，覆盖：

- Local Dream SD1.5 QNN：适合 Snapdragon 8 Gen 2 等高通设备上的 512 x 512 文生图。
- Local Dream SDXL QNN：适合 Snapdragon 8 Gen 3 / Elite 级设备上的 1024 x 1024 高质量图像生成。
- MNN CPU：面向不具备 QNN NPU 环境时的 CPU 后备路径。
- stable-diffusion.cpp：作为本地图像生成原生推理链路的一部分保留。

模型包含写实、人像、摄影、动漫、插画、通用 SDXL、Turbo 和 DMD2 蒸馏候选模型。生成后的图片可以继续交给本地视觉语言模型处理，用于图片描述、质量检查、内容理解和二次问答。这使应用不只是“生成图片”，还可以把生成结果重新纳入本地多模态理解链路。

### 7. AI 键盘

AI 键盘是最新稳定版本的重点功能。它把本地智能体广场作为 Android 系统输入法使用，在任意可输入文本框里提供：

- 基础输入法键盘。
- 标点和空格手动插入。
- Vosk 离线语音输入。
- 15 个语音转文字模型，覆盖中文、英文、日语、韩语、法语、德语、西班牙语、俄语、越南语、葡萄牙语。
- 本地文本模型选择。
- 流水线选择和单选菜单切换。
- 18 条预设流水线：润色、校对纠正、重写、简化、专业风格、日常风格、缩写、扩写、总结、要点、电子邮件、聊天、Twitter、列表、表格、翻译、文本补全、自定义。
- 翻译目标语言设置。
- 预设提示词查看和编辑。
- 自定义流水线新增、编辑、删除。
- 流水线日志：记录原文、提示词、原始输出、清洗输出、提交后文本、模型、耗时、首 token 延迟、输出速度、提交方式和目标编辑器信息。

为了避免输入法服务直接承受大模型推理压力，AI 键盘的文本处理通过应用内模型运行链路隔离执行，稳定性已经过 2B、4B、12B 模型和重复流水线调用测试。文本补全流水线使用追加提交逻辑，适合把被截断或未完成的文本继续补齐，而不是替换原文。

### 8. Tiny Garden 与 Mobile Actions

Tiny Garden 是上游保留的本地迷你交互任务，适合演示小模型在游戏化任务中的状态驱动能力。Mobile Actions 是上游移动端操作任务，用于探索模型理解手机操作意图、执行动作规划和移动端交互的能力。

### 9. 模型管理与下载

模型管理器负责统一展示模型、下载状态、任务适配关系、模型删除和配置入口。模型白名单跟随上游结构扩展，并额外加入本项目验证过的 LiteRT-LM、QNN、MNN 和视觉创作模型。

## 隐私和离线能力

默认对话、推理、视觉旁白、AI 键盘流水线、语音识别和视觉创作都在设备本地运行。只有用户主动使用联网搜索、MCP 远程服务、Hugging Face 下载、模型白名单下载、外部链接或需要第三方 API 的技能时，才会访问网络。

仓库保留 Firebase 依赖声明，但 `google-services` 插件默认 `apply false`，仓库内不包含 `google-services.json`，默认构建不会启用真实 Firebase 实例。

## 安装

前往 Releases 下载最新稳定 APK。稳定版包名为 `com.localagent.plaza`。实验分支产物使用 `.experimental` 后缀，可与稳定版并行安装，便于测试新功能。

推荐 Android 14 或更新系统，以及 12GB 以上内存设备；如果要运行 12B 级文本模型、SDXL QNN 图像生成或长时间 AI 键盘流水线，建议 16GB 到 24GB 内存设备。

## 文档

- [TECHNICAL_OVERVIEW.md](docs/TECHNICAL_OVERVIEW.md)：模块、模型、推理链路和发布策略说明。
- [RELEASE_NOTES.md](RELEASE_NOTES.md)：版本历史。
- [VISION_NARRATION.md](VISION_NARRATION.md)：实时视觉旁白说明。
- [HANDOVER_AND_LINEAGE.md](HANDOVER_AND_LINEAGE.md)：项目来源和交接记录。
- [Function_Calling_Guide.md](Function_Calling_Guide.md)：工具调用说明。

## 上游与许可

本项目 Fork 自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache License 2.0。感谢 Google AI Edge 团队在端侧 GenAI、LiteRT-LM 和 Android 示例应用上的开源工作。
