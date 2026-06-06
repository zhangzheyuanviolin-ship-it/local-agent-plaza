# Local Agent Plaza · 本地智能体广场

> 一个完全运行在 Android 设备本地的 AI Agent 应用：本地对话、图片理解、视觉旁白、可扩展工具调用。
>
> 包名 `com.localagent.plaza` · versionName `1.0.13-plaza.1` · 当前稳定版 v1.0.13-plaza.1

---

## 它是什么

Local Agent Plaza 是一款把「本地大模型推理」变成日常可用的 Android 应用。所有模型权重、对话历史、附件与工具结果都保存在设备本地。

它是 Google AI Edge Gallery v1.0.13 的二次开发版本，移除了开发测试痕迹，重塑了产品命名与包名，但完整保留了上游的全部能力并补齐了无障碍侧的关键模块。

## 核心能力

- **本地 LLM 对话**：导入 LiteRT-LM / HuggingFace 来源模型及应用内支持的本地模型格式后即可离线推理，支持 CPU / GPU / NPU 多后端切换
- **多模态图片问答**：上传图片让本地视觉模型理解、回答、描述
- **智能体工具三档模式**：
  - **AUTO**：自动选择最佳工具调用方式
  - **NATIVE**：LiteRT-LM 原生 Function Calling
  - **COMPAT**：兼容老模型的 JSON 工具调用
- **网络搜索工具**：内置 Tavily / Exa / LangSearch 三个搜索后端
- **文件工作区**：统一管理本地模型、对话附件与导出文件
- **视觉旁白（差异化亮点）**：
  - 实时相机抓帧并由本地多模态模型理解场景
  - 自定义提示词，把"看到的画面"翻译成你想要的描述
  - TTS 语音播报，让画面里的信息"被说出来"
  - 历史记录可导出为文本 / Markdown
  - 为视障用户设计的无障碍工作流

## 适用人群

- 想在 Android 手机 / 平板上跑本地大模型的研究者、爱好者
- 对隐私敏感、不愿把数据交给云端的用户
- 视障用户和无障碍需求方：视觉旁白模块让环境感知成为可能
- 想要在端侧探索 Agent 工具调用范式的开发者

## 隐私与数据

- 本地对话、模型推理、视觉旁白默认在设备端完成，模型权重与对话历史都保存在本机存储。
- 仅当用户主动使用网络搜索、下载模型白名单、打开外部链接或调用联网工具（Tavily / Exa / LangSearch、HuggingFace OAuth 等）时，才会访问对应第三方服务。
- 仓库源码中虽保留 Firebase Analytics 与 Firebase Messaging 的依赖引用与 `AndroidManifest.xml` 中的相关声明，但 `google-services` Gradle 插件默认处于 `apply false` 状态，仓库内未提供 `google-services.json`，因此默认构建产物不包含真正启用的 Firebase 实例，不会向 Google 发送分析或推送数据。
- 启动与运行所需的模型白名单可由用户在首次启动时选择下载到本地，之后即可完全离线使用。

## 安装

前往 [Releases](https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases) 下载最新的 `local-agent-plaza-*.apk`。

```bash
adb push local-agent-plaza-1.0.13-plaza.1.apk /data/local/tmp/
adb shell pm install -t /data/local/tmp/local-agent-plaza-1.0.13-plaza.1.apk
```

最低支持 Android 12（API 31），建议 Android 14+ 以获得最佳 NPU 体验。

## 路线图

- v1.0.x：稳定本地推理与视觉旁白，模型白名单跟随上游
- v1.1.x：端侧 Agent 工具扩展（更多搜索后端、本地 RAG）
- v1.2.x：自定义视觉旁白提示词模板与导出格式

## 上游与致谢

本项目 Fork 自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache License 2.0。感谢 Google AI Edge 团队在端侧 GenAI 上的开源贡献。

## 许可

Apache License 2.0
