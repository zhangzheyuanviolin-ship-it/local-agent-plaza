# Technical Overview · 本地智能体广场技术说明

本文记录 Local Agent Plaza 在 `v1.0.14-plaza.5` 稳定线上的模块结构、模型范围、推理链路、技能系统、发布分支和验证策略。

## 版本与分支策略

- `main`：稳定发布分支，只接收已经在实验包中测试通过的功能。
- `experimental`：长期实验分支，后续新模块先在此分支迭代，实验 APK 包名为 `com.localagent.plaza.mcp`。
- tag 发布：稳定版 tag 使用 `v1.0.14-plaza.x`，GitHub Actions 会生成 `com.localagent.plaza` APK 并上传 Release 资产。

GitHub Actions 对 `main`、`experimental`、`feature/**` 和 `v*` tag 触发构建。`experimental` push 产物用于真机测试，tag 产物用于正式 Release。

## 顶层模块

应用由以下模块组成：

1. 通用本地模型管理：模型白名单、内置缓存、下载、导入、删除、任务过滤和参数配置。
2. AI 对话：多轮聊天、文本输入、语音输入、历史记录和模型性能信息。
3. Prompt Lab：单轮提示词实验、模板和参数调试。
4. Agent Chat：本地智能体、技能系统、MCP、原生工具调用和兼容工具调用。
5. Ask Image：图片理解和图片问答。
6. Ask Audio：音频理解和音频问答。
7. Video QA：视频抽帧、多图输入和基于时间轴的视频问答。
8. Vision Narration：实时视觉旁白和无障碍场景描述。
9. Visual Creation：本地图像生成、提示词优化和生成图 VLM 后处理。
10. AI Keyboard：系统输入法、离线语音输入、本地文本流水线和流水线日志。
11. Tiny Garden：上游迷你任务。
12. Mobile Actions：上游手机操作任务。
13. Benchmark：本地模型性能测试和结果展示。

## 模型白名单

当前 `model_allowlist.json` 与 `model_allowlists/1_0_14.json` 共包含 48 个模型条目。APK 同时内置 `assets/model_allowlists/1_0_14.json`，应用启动时优先读取内置白名单并写回磁盘缓存；只有内置白名单不存在时才尝试磁盘缓存和远端白名单。因此飞行模式、GitHub raw 访问失败或历史分支删除都不能影响模型条目显示。

按任务过滤后的数量为：

- `llm_chat`：46 个模型。
- `llm_prompt_lab`：46 个模型。
- `llm_agent_chat`：41 个模型。
- `llm_ask_image`：8 个模型。
- `llm_ask_audio`：5 个模型。
- `llm_tiny_garden`：1 个模型。
- `llm_mobile_actions`：1 个模型。

模型文件下载候选源由模型条目生成。当前顺序为已验证 ModelScope 同名模型文件、`hf-mirror.com`、`huggingface.co` 官方源。下载 worker 会逐个候选源尝试，并在当前源失败时继续下一源；目标文件级别保留断点续传。

模型系列包括 Gemma、Gemma 3n、Gemma 4、Qwen2.5、Qwen3、Qwen3.5、DeepSeek-R1-Distill-Qwen、MiniCPM5、SmolLM3、JOSIE、VibeThinker、TinyGarden 和 MobileActions。模型能力字段用于标识 thinking、speculative decoding、图片支持和音频支持。

## 推理运行时

文本、多模态和智能体模型主要使用 LiteRT-LM。应用保留 CPU / GPU / NPU 后端选择，并允许模型配置覆盖上下文窗口、温度、topK、topP、maxTokens 等参数。模型设置入口已放到模型列表，用户可以在加载模型前调整参数，避免上下文窗口设置过大导致加载阶段卡死。

视觉创作使用独立图像生成链路：

- `LOCAL_DREAM_QNN_MNN`：Local Dream 后端，覆盖 QNN NPU 与 MNN CPU 模型。
- `STABLE_DIFFUSION_CPP`：stable-diffusion.cpp 原生链路，作为本地图像生成后端能力保留。

视觉创作会在提示词优化、图像生成和生成图 VLM 后处理后主动释放相关模型资源，降低多轮创作时的内存累积风险。

## 智能体工具调用

Agent Chat 支持三档工具调用：

- `AUTO`：自动选择适合当前模型的工具调用方式。
- `NATIVE`：使用 LiteRT-LM 原生 Function Calling。
- `COMPAT`：使用兼容 JSON 工具调用协议。

技能遵循显式启用原则。只有用户在技能管理页面启用的技能才会被注入模型上下文；未启用技能不会被模型看到。需要 API Key、Host、模型、尺寸、音色或模式参数的技能都通过技能设置页配置，模型调用时只传少量必要参数。

## 内置技能

当前 APK 打包 21 个技能：

- `calculate-hash`：计算文本或文件哈希。
- `create-calendar-event`：创建日历事件。
- `exa-search`：Exa 联网搜索。
- `file-workspace`：本地工作区文件管理、文档读取、URL 下载和工具审计。
- `interactive-map`：地图交互。
- `kitchen-adventure`：示例交互技能。
- `langsearch-search`：LangSearch 搜索。
- `long-text-writer`：长文本写作。
- `mood-tracker`：情绪记录。
- `qr-code`：二维码生成。
- `query-wikipedia`：维基百科查询。
- `send-email`：邮件相关技能。
- `tavily-search`：Tavily 搜索。
- `text-spinner`：文本改写示例。
- `weather-query`：当前定位或城市天气查询，支持当前、未来 24 小时和未来一周。
- `edge-tts`：Microsoft Edge TTS，支持文本或工作区文本文件转 MP3。
- `agnes-omni`：Agnes 图片和视频生成。
- `minimax-omni`：MiniMax 中国区全模态能力，含文本、图片、TTS、音乐、图片分析、视频分析和网络搜索。
- `media-toolbox`：图片、音频、视频基础处理。
- `web-page-extract`：网页正文提取。
- `anysearch-search`：AnySearch 搜索、网页提取和垂直搜索子域。

## 文件工作区

用户授权一个本地文件夹作为工作区。推荐目录：

- `file/`：文本、文档、表格和写作输出。
- `media/`：图片、音频、视频、TTS、图像生成和多媒体处理结果。
- `download/`：URL 下载文件。
- `tool-audit/`：每次工具调用完整审计 JSON。

`file-workspace` 支持列目录、状态查询、读文本、写文本、追加文本、创建目录、删除、复制、移动、URL 下载和文档读取。读取支持 txt、md、csv、json、xml、log、html、pdf、docx、xlsx 等。对 PDF / DOCX / XLSX，工具会尽量抽取结构化文本；当内容超过当前模型上下文预算时，会返回可承受范围内的内容，并把完整工具输出写入 `tool-audit/`。

## 多媒体工具箱

`media-toolbox` 通过 FFmpegKit 和 Android 本地解码能力封装常用处理，不要求模型直接输出 FFmpeg 命令。

图片模式：

- `media_image_info`：读取图片宽高、MIME、分辨率和大小。
- `media_image_resize`：缩放到 512、720p、1080p、4k 或指定宽高。
- `media_image_convert`：jpg、png、webp 之间转换。
- `media_image_to_video`：把单张图片转为短 MP4。

音频模式：

- `media_audio_info`：读取音频时长、MIME、码率和音轨状态。
- `media_audio_convert`：mp3、wav、m4a、aac、ogg、flac 转换。
- `media_audio_compress`：按 1/2、1/3、1/4 档压缩。
- `media_audio_concat`：最多 5 段音频拼接。
- `media_audio_trim`：按时间点剪辑。
- `media_audio_mix`：双音轨混音，支持音量、延迟和背景循环。

视频模式：

- `media_video_info`：读取视频时长、宽高、旋转、MIME、码率和轨道状态。
- `media_video_convert`：mp4、mov、mkv、webm 转换。
- `media_video_resize`：缩放到 512、720p、1080p、2k、4k 或指定宽高。
- `media_video_compress`：按 1/2、1/3、1/4 档压缩。
- `media_video_concat`：最多 5 段视频拼接，自动统一 720p、30fps、MP4、MPEG4 视频和 AAC 音频，缺失音频时补静音轨。
- `media_video_trim`：按时间点剪辑视频片段。
- `media_video_extract_audio`：提取视频音轨。
- `media_video_mute`：移除视频音轨。
- `media_video_add_audio`：给视频添加外部音频，并可保留原音轨。

工具层会检查真实媒体轨道。视频操作需要真实视频流；若模型把视频路径误传给音频拼接、音频剪辑或音频信息工具，工具会自动路由到对应视频操作或给出明确错误，避免生成扩展名与实际内容不一致的文件。

## 视频问答

视频问答任务不新增独立模型文件，而是复用 `llm_ask_image` 的视觉语言模型。用户可以从系统相册或文件选择器选择视频。

完整视频模式会按视频时长均匀抽取画面帧。关键帧模式支持最多 5 个时间点，时间格式支持纯秒数、小数秒和 `mm:ss`。抽帧分辨率支持 384、512、768、1024 四档。高帧数和高分辨率会增加内存压力，页面会提示用户按设备能力选择。

当前实现不内置转码；系统无法解码的视频会在处理阶段返回错误。

## AI 键盘

AI 键盘由应用内设置页和 Android 输入法服务组成。

应用内设置页负责：

- 系统输入法启用与切换入口。
- Vosk 语音模型下载、选择、删除。
- 文本模型选择。
- 流水线管理、提示词编辑、翻译目标语言设置。
- 流水线日志查看、复制、导出和清理。

输入法服务负责：

- 基础键盘布局。
- 标点和空格提交。
- 当前文本框内容读取。
- 离线语音识别。
- 发起文本流水线任务。
- 将处理结果提交回目标编辑器。

文本模型推理通过应用内链路执行，不直接压在 IME 服务里。流水线日志记录原文、提示词、原始输出、清洗输出、提交后读回、目标编辑器包名、inputType、imeOptions、首 token 延迟、推理耗时、提交耗时、总耗时和输出速度。

当前预设 18 条流水线：润色、校对纠正、重写、简化、专业风格、日常风格、缩写、扩写、总结、要点、电子邮件、聊天、Twitter、列表、表格、翻译、文本补全、自定义。文本补全使用 `APPEND` 提交模式，其余流水线默认使用 `REPLACE` 提交模式。

## AI 键盘语音转文字模型

AI 键盘当前注册 15 个 Vosk 模型：

- 中文：small-cn-0.22、cn-0.22、cn-kaldi-multicn-0.15。
- 英文：small-en-us-0.15、en-us-0.22、en-us-0.22-lgraph、en-us-0.42-gigaspeech。
- 日语：small-ja-0.22。
- 韩语：small-ko-0.22。
- 法语：small-fr-0.22。
- 德语：small-de-0.15。
- 西班牙语：small-es-0.42。
- 俄语：small-ru-0.22。
- 越南语：small-vn-0.4。
- 葡萄牙语：small-pt-0.3。

中文 small-cn-0.22 随 APK 打包，其他模型按需下载。

## 视觉创作模型

视觉创作当前注册 32 个模型：

- SD1.5 QNN 8gen2：Absolute Reality、DreamShaper V8、Realistic Vision Hyper、MajicMix Realistic V7、Anything V5、MeinaMix V12、AbyssOrangeMix3。
- MNN CPU：Absolute Reality、Anything V5、ChilloutMix、CuteYukiMix、QteaMix。
- SDXL QNN 8gen3：SDXL Base、Illustrious v16 / v17、Illustrious DMD2、RealVisXL V5、Juggernaut XL、CyberRealistic V10、WAI Illustrious、IntoRealism Ultra、DreamShaper SDXL、Epic Realism、Perfect Deliberate、Perfection Realistic、Pony Diffusion、Animagine、Anikawa、MopMix、JRD Renderspec XL Turbo 等。

生成完成的图片可以回送给本地视觉语言模型做描述、问答和质量检查。

## 无障碍与隐私

视觉旁白和 AI 键盘都是围绕无障碍场景扩展的模块。视觉旁白把相机画面转成可播报文本；AI 键盘让用户在任意输入框中调用本地模型改写、补全、翻译和整理文字。

默认推理、日志、模型文件和历史记录留在本机。联网只发生在用户主动下载模型、调用搜索技能、连接 MCP Server、打开外部链接或使用需要第三方服务的技能时。

## 发布验证

手机本地 Ubuntu 环境会遇到 AAPT2 daemon 启动失败，正式 APK 以 GitHub Actions 云端构建为准。每次发布需要确认：

- Actions 构建成功。
- APK 签名校验通过。
- APK package、versionCode、versionName 符合预期。
- APK sha256 与发布资产一致。
- 本地下载必须从第一次开始使用 `curl -C -` 断点续传。
- 实验包名必须是 `com.localagent.plaza.mcp`，稳定包名必须是 `com.localagent.plaza`。
