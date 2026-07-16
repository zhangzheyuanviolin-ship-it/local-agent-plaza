# Technical Overview · 本地智能体广场技术说明

本文记录 Local Agent Plaza 在 `v1.0.14-plaza.4` 稳定线上的模块结构、模型范围、推理链路和发布分支策略。

## 版本与分支策略

- `main`：稳定发布分支，只接收已经在实验包中测试通过的功能。
- `experimental`：长期实验分支，后续所有新模块先在此分支迭代，产物使用 `.experimental` 包名后缀，可与稳定版并行安装。
- 历史 `feature/*` 分支仅保留到确认合并和归档完成后删除。

GitHub Actions 对 `main`、`experimental` 和 `feature/**` 触发构建。tag 触发时会自动生成 GitHub Release 并上传 APK 与 `.sha256` 文件。

## 顶层模块

应用由以下模块组成：

1. 通用本地模型管理：模型白名单、下载、导入、删除、任务过滤和参数配置。
2. AI 对话：多轮聊天、文本输入、语音输入、历史记录和模型性能信息。
3. Prompt Lab：单轮提示词实验、模板和参数调试。
4. Agent Chat：本地智能体、技能系统、MCP、原生工具调用和兼容工具调用。
5. Ask Image：图片理解和图片问答。
6. Ask Audio：音频理解和音频问答。
7. Vision Narration：实时视觉旁白和无障碍场景描述。
8. Visual Creation：本地图像生成和生成图 VLM 后处理。
9. AI Keyboard：系统输入法、离线语音输入、本地文本流水线和流水线日志。
10. Tiny Garden：上游迷你任务。
11. Mobile Actions：上游手机操作任务。
12. Benchmark：本地模型性能测试和结果展示。

## 模型白名单

当前 `model_allowlist.json` 与 `model_allowlists/1_0_14.json` 共包含 48 个模型条目。Android APK 同时内置 `assets/model_allowlists/1_0_14.json`，应用启动时优先读取内置白名单并写回磁盘缓存；只有内置白名单不存在时才尝试磁盘缓存和远端 main 分支白名单。因此飞行模式、GitHub raw 访问失败或历史分支删除都不能影响模型条目显示。按任务过滤后的数量为：

模型文件下载候选源由同一模型条目生成。当前版本会优先加入已验证可用的 ModelScope 同名模型文件候选，其次使用 `hf-mirror.com`，最后回退到 `huggingface.co` 官方源。下载 worker 会逐个候选源尝试，并在当前源失败时继续下一源；断点续传仍按目标文件级别保留。

- `llm_chat`：46 个模型。
- `llm_prompt_lab`：46 个模型。
- `llm_agent_chat`：41 个模型。
- `llm_ask_image`：8 个模型。
- `llm_ask_audio`：5 个模型。
- `llm_tiny_garden`：1 个模型。
- `llm_mobile_actions`：1 个模型。

实验分支的视频问答任务不新增独立模型文件，而是复用 `llm_ask_image` 的视觉语言模型集合。用户可从系统相册或文件选择器选择视频，应用通过 Android `MediaMetadataRetriever` 从视频中抽取缩放后的画面帧，再按时间顺序作为多图输入发送给 LiteRT-LM。当前实现不内置 FFmpeg 或 Media3 转码，系统无法解码的视频会在处理阶段返回错误。

模型系列包括 Gemma、Gemma 3n、Gemma 4、Qwen2.5、Qwen3、Qwen3.5、DeepSeek-R1-Distill-Qwen、MiniCPM5、SmolLM3、JOSIE、VibeThinker、TinyGarden 和 MobileActions。模型能力字段用于标识 thinking、speculative decoding、图片支持和音频支持。

## 推理运行时

文本、多模态和智能体模型主要使用 LiteRT-LM。应用保留 CPU / GPU / NPU 后端选择，并允许模型配置覆盖上下文窗口、温度、topK、topP、maxTokens 等参数。兼容工具调用路径用于处理原生 Function Calling 不稳定或模型输出格式不完全可靠的情况。

视觉创作使用独立的图像生成运行时：

- `LOCAL_DREAM_QNN_MNN`：Local Dream 后端，覆盖 QNN NPU 与 MNN CPU 模型。
- `STABLE_DIFFUSION_CPP`：stable-diffusion.cpp 原生链路，作为本地图像生成后端能力保留。

## 智能体工具系统

Agent Chat 里有两类扩展工具。

第一类是本地技能系统。当前打包 14 个内置技能：

- `calculate-hash`：计算文本或文件哈希。
- `create-calendar-event`：生成日历事件。
- `exa-search`：Exa 联网搜索。
- `file-workspace`：访问用户授权的本地文件工作区。
- `interactive-map`：地图交互。
- `kitchen-adventure`：示例交互技能。
- `langsearch-search`：LangSearch 搜索。
- `long-text-writer`：长文本写作。
- `mood-tracker`：情绪记录。
- `qr-code`：二维码生成。
- `query-wikipedia`：维基百科查询。
- `send-email`：邮件草稿或发送相关技能。
- `tavily-search`：Tavily 搜索。
- `text-spinner`：文本改写示例。

第二类是 MCP。默认预设 4 个远程 MCP Server：DeepWiki、Microsoft Learn、Context7、GitMCP: Google AI Edge Gallery。用户可以添加自定义 MCP URL，查看工具列表，控制工具启用状态，并把 MCP 工具提示注入智能体上下文。

## AI 键盘技术链路

AI 键盘由应用内任务页和 Android 输入法服务两部分组成。

应用内任务页负责：

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

文本模型推理不直接压在输入法服务中，而是通过应用内链路运行，避免 IME 服务持有大模型上下文造成系统卡死。流水线日志会记录原文、提示词、原始输出、清洗输出、提交后读回、目标编辑器包名、inputType、imeOptions、首 token 延迟、推理耗时、提交耗时、总耗时和输出速度。

当前预设 18 条流水线：润色、校对纠正、重写、简化、专业风格、日常风格、缩写、扩写、总结、要点、电子邮件、聊天、Twitter、列表、表格、翻译、文本补全、自定义。预设流水线不可删除，但可编辑提示词；用户新增的自定义流水线可删除。文本补全使用 `APPEND` 提交模式，其余流水线默认使用 `REPLACE` 提交模式。

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

中文 small-cn-0.22 随 APK 打包，其他模型按需下载。下载源包含官方源、Hugging Face 源和 HF 中国镜像。

## 视觉创作模型

视觉创作当前注册 32 个模型：

- SD1.5 QNN 8gen2：Absolute Reality、DreamShaper V8、Realistic Vision Hyper、MajicMix Realistic V7、Anything V5、MeinaMix V12、AbyssOrangeMix3。
- MNN CPU：Absolute Reality、Anything V5、ChilloutMix、CuteYukiMix、QteaMix。
- SDXL QNN 8gen3：SDXL Base、Illustrious v16 / v17、Illustrious DMD2、RealVisXL V5、Juggernaut XL、CyberRealistic V10、WAI Illustrious、IntoRealism Ultra、DreamShaper SDXL、Epic Realism、Perfect Deliberate、Perfection Realistic、Pony Diffusion、Animagine、Anikawa、MopMix、JRD Renderspec XL Turbo 等。

生成完成的图片可以回送给本地视觉语言模型做描述、问答和质量检查。这条链路把图像生成和多模态理解连接成一个本地闭环。

## 无障碍与隐私

视觉旁白和 AI 键盘都是围绕无障碍场景扩展的模块。视觉旁白把相机画面转成可播报文本；AI 键盘让用户在任意输入框中调用本地模型改写、补全、翻译和整理文字。

默认推理、日志、模型文件和历史记录留在本机。联网只发生在用户主动下载模型、调用搜索技能、连接 MCP Server、打开外部链接或使用需要第三方服务的技能时。

## 发布验证

手机本地 Ubuntu 环境已多次出现 AAPT2 daemon 无法启动，导致 `processDebugResources` 前置阶段失败。因此正式 APK 以 GitHub Actions 云端构建为准。每次发布需要确认：

- Actions 构建成功。
- APK 签名校验通过。
- `aapt dump badging` 中 package、versionCode、versionName 符合预期。
- APK sha256 与发布资产一致。
- 需要归档到本地测试目录时，下载必须从第一次开始使用 `curl -C -` 断点续传。
