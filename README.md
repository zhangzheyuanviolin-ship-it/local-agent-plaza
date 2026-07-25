# Local Agent Plaza · 本地智能体广场

Local Agent Plaza 是一款面向 Android 手机和平板的本地 AI 应用。它 Fork 自 Google AI Edge Gallery，并在端侧对话、本地智能体、MCP、视觉理解、音频理解、视频问答、本地视觉创作、实时视觉旁白、系统 AI 键盘和可扩展技能工具箱方向做了系统扩展。

当前稳定线：`v1.0.14-plaza.5`
当前稳定包名：`com.localagent.plaza`
长期实验分支：`experimental`
实验包名后缀：`.mcp`，完整包名为 `com.localagent.plaza.mcp`，用于和稳定版并行安装测试。

## 核心定位

本项目不是单一聊天应用，而是一个端侧 AI 能力集合：

- 本地 AI 对话和 Prompt Lab：46 个可用于聊天和提示词实验的文本模型。
- 本地智能体：41 个可用于智能体任务的模型，支持 AUTO、NATIVE、COMPAT 三档工具调用。
- 技能系统：21 个内置技能，覆盖文件工作区、联网搜索、网页提取、天气、TTS、图片视频生成、MiniMax 全模态能力和多媒体处理。
- MCP：支持远程 MCP Server，默认预设 DeepWiki、Microsoft Learn、Context7 和 GitMCP: Google AI Edge Gallery。
- 图片问答：8 个视觉语言模型，支持图片描述、图片问答和视觉信息抽取。
- 音频问答与转写：5 个音频理解模型，音频转写支持长音频和常见音频格式转 WAV 后处理。
- 视频问答：从相册或文件选择器选择 MP4 视频，按完整视频或指定关键帧抽图，交给本地视觉语言模型分析。
- 本地视觉创作：32 个图像生成模型，覆盖 SD1.5、SDXL、QNN NPU、MNN CPU 和 stable-diffusion.cpp 相关链路。
- 实时视觉旁白：相机抓帧、本地视觉语言模型理解、TTS 播报和历史导出，面向视障场景重点优化。
- AI 键盘：Android 系统输入法，支持离线语音输入、本地模型流水线、预设和自定义流水线、日志审计、文本补全追加和任意文本框处理。
- 上游 Gallery 模块：Tiny Garden、Mobile Actions、Benchmark、模型管理和性能测试。

## 主要功能

### AI 对话、Prompt Lab 与模型管理

APK 内置 1.0.14 模型白名单，共 48 个模型条目。飞行模式下也会显示完整模型列表，网络只影响模型文件下载，不影响模型条目存在。模型覆盖 Gemma、Gemma 3n、Gemma 4、Qwen、DeepSeek Distill、MiniCPM、SmolLM、JOSIE、VibeThinker 等系列，包含 1B 到 14B 级别、长上下文和多种量化形态。

模型下载支持多源候选和失败回退，优先尝试已验证 ModelScope 同名模型文件，其次尝试 Hugging Face 中国镜像，再回退到 Hugging Face 官方源。下载链路保留断点续传能力。

模型列表页支持下载、删除、任务过滤、参数设置、上下文窗口配置和 CPU / GPU / NPU 后端选择。模型参数可以在进入聊天前调整，避免错误上下文设置导致大模型加载卡死。

### 本地智能体与技能

智能体支持三种工具调用模式：

- `AUTO`：按模型能力自动选择工具调用方式。
- `NATIVE`：使用 LiteRT-LM 原生 Function Calling。
- `COMPAT`：使用兼容 JSON 工具协议，适配原生工具调用不稳定或不支持的模型。

所有技能均遵循用户手动启用原则：只有用户在技能管理页面启用的技能才会被注入模型上下文。未启用的技能不会被模型看到。需要 API Key 或参数的技能也在技能页面统一配置。

当前内置 21 个技能：

- `calculate-hash`：计算文本或文件哈希。
- `create-calendar-event`：创建日历事件。
- `exa-search`：Exa 联网搜索。
- `file-workspace`：用户授权工作区文件管理，支持列目录、读写文本、读取 PDF / DOCX / XLSX、下载 URL 到工作区和工具审计日志。
- `interactive-map`：地图交互。
- `kitchen-adventure`：示例交互技能。
- `langsearch-search`：LangSearch 联网搜索。
- `long-text-writer`：长文本写作辅助。
- `mood-tracker`：情绪记录示例。
- `qr-code`：二维码生成。
- `query-wikipedia`：维基百科查询。
- `send-email`：邮件相关能力。
- `tavily-search`：Tavily 联网搜索。
- `text-spinner`：文本改写示例。
- `weather-query`：按当前定位或城市查询当前天气、未来 24 小时和未来一周天气。
- `edge-tts`：Microsoft Edge TTS 语音合成，支持直接文本或工作区文本文件转 MP3。
- `agnes-omni`：Agnes 图片和视频生成，输出保存到工作区 `media/`。
- `minimax-omni`：MiniMax 中国区 Token Plan，全模态能力包括文本生成、图片生成、语音合成、音乐生成、图片分析、视频分析和网络搜索。
- `media-toolbox`：多媒体工具箱，封装图片、音频、视频基础处理。
- `web-page-extract`：提取网页正文为模型易读内容。
- `anysearch-search`：AnySearch 搜索、网页提取和垂直搜索子域查询。

### 文件工作区

用户授权一个本地文件夹作为智能体工作区。推荐目录结构：

- `file/`：文本、文档、表格和模型写作输出。
- `media/`：图片、音频、视频、TTS、图像生成和多媒体处理结果。
- `download/`：模型或智能体下载的外部文件。
- `tool-audit/`：每次工具调用的完整审计 JSON。

文件读取支持纯文本、Markdown、CSV、JSON、HTML、PDF、DOCX 和 XLSX。工具返回会根据当前模型上下文预算做压缩和截断，完整工具输出保留在 `tool-audit/` 以便审计和复核。

### 多媒体工具箱

`media-toolbox` 把常用多媒体处理封装成简单工具调用，不要求模型直接编写 FFmpeg 命令。

图片模式支持图片信息、尺寸缩放、格式转换和图片转短视频。音频模式支持音频信息、格式转换、压缩、片段剪辑、最多 5 段拼接和双音轨混音。视频模式支持视频信息、格式转换、尺寸缩放、压缩、最多 5 段拼接、片段剪辑、音轨提取、视频静音和给视频添加外部音轨。

视频拼接会自动把输入统一到 720p、30fps、MP4、MPEG4 视频和 AAC 音频，并给缺失音频的视频补静音轨，降低不同尺寸和编码导致拼接失败的概率。工具层还会识别视频路径误入音频工具的情况并自动路由或明确拒绝，避免生成“后缀是视频、内容是音频”的伪文件。

### MCP 集成

应用集成 Model Context Protocol 客户端能力。用户可以添加远程 MCP Server、查看工具列表、启用或禁用工具，并把 MCP 工具提示注入智能体上下文。默认预设包括 DeepWiki、Microsoft Learn、Context7 和 GitMCP: Google AI Edge Gallery。

### 图片、音频和视频问答

图片问答支持一次多图输入。音频问答支持音频理解。视频问答复用图片问答的视觉语言模型，通过 Android 多媒体引擎抽取视频画面帧：

- 完整视频模式：按视频总时长均匀抽帧，用户可调整帧数和分辨率。
- 关键帧模式：用户指定最多 5 个时间点，支持秒数、小数秒和 `mm:ss`。
- 上传入口同时支持系统相册和文件选择器。

当前视频问答不内置转码，建议使用系统可正常解码的 MP4 文件。

### 实时视觉旁白

视觉旁白面向视障用户和移动场景理解。应用可以从相机抓帧，送入本地视觉语言模型生成场景描述，再用系统 TTS 播报。用户可调整抓帧间隔、提示词和播报策略，并导出历史记录。

### 本地视觉创作

视觉创作注册 32 个图像生成模型，覆盖 SD1.5 QNN、SDXL QNN、MNN CPU 和 stable-diffusion.cpp 相关链路。用户可以先用本地文本模型优化提示词，再生成图片，生成结果还能继续交给本地视觉语言模型做描述、质量检查和二次问答。视觉创作流程会在文本模型、图像模型和视觉模型完成各自任务后尽量释放模型占用，降低连续生成时的内存累积风险。

### AI 键盘

AI 键盘把本地智能体广场作为 Android 系统输入法使用，在任意文本框中调用本地模型处理文本。当前能力包括：

- 基础键盘输入、标点和空格插入。
- Vosk 离线语音输入，15 个语音转文字模型覆盖中文、英文、日语、韩语、法语、德语、西班牙语、俄语、越南语、葡萄牙语。
- 本地文本模型选择和单选列表切换。
- 18 条预设流水线：润色、校对纠正、重写、简化、专业风格、日常风格、缩写、扩写、总结、要点、电子邮件、聊天、Twitter、列表、表格、翻译、文本补全、自定义。
- 翻译目标语言配置。
- 预设提示词查看和编辑。
- 自定义流水线新增、编辑和删除。
- 流水线日志复制、导出和清理。

AI 键盘的模型推理通过应用内链路执行，避免输入法服务直接持有大模型上下文造成系统卡死。文本补全流水线使用追加提交模式，不替换原始未完成文本。

## 隐私与离线

默认对话、推理、图片问答、音频问答、视频抽帧、视觉旁白、AI 键盘流水线、语音识别和视觉创作都在设备本地运行。联网只发生在用户主动下载模型、调用搜索技能、连接 MCP、访问网页、下载 URL 或使用第三方 API 技能时。

仓库保留 Firebase 依赖声明，但 `google-services` 插件默认 `apply false`，仓库内不包含 `google-services.json`，默认构建不会启用真实 Firebase 实例。

## 安装

前往 GitHub Releases 下载最新稳定 APK。稳定版包名为 `com.localagent.plaza`。实验分支产物包名为 `com.localagent.plaza.mcp`，用于和稳定版并行安装测试。

推荐 Android 14 或更新系统，以及 12GB 以上内存设备。运行 12B 文本模型、SDXL QNN 图像生成、视频问答高分辨率抽帧或连续视觉创作时，建议 16GB 到 24GB 内存设备。

## 文档

- [TECHNICAL_OVERVIEW.md](docs/TECHNICAL_OVERVIEW.md)：模块、模型、推理链路、技能和发布策略。
- [RELEASE_NOTES.md](RELEASE_NOTES.md)：版本历史。
- [Function_Calling_Guide.md](Function_Calling_Guide.md)：智能体工具调用与技能扩展说明。
- [VISION_NARRATION.md](VISION_NARRATION.md)：实时视觉旁白说明。
- [HANDOVER_AND_LINEAGE.md](HANDOVER_AND_LINEAGE.md)：项目来源、分支和交接记录。

## 上游与许可

本项目 Fork 自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache License 2.0。感谢 Google AI Edge 团队在端侧 GenAI、LiteRT-LM 和 Android 示例应用上的开源工作。
