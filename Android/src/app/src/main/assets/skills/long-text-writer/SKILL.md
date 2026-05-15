---
name: long-text-writer
description: 只负责创建或覆盖一个 txt 或 md 文档，并把完整正文一次性写入文档，适合长文本写作。
---

# Long Text Writer

只在用户要求创建或覆盖一个 txt 或 md 文档并写入正文时使用本技能。

强制规则：

- 加载本技能后，下一步优先调用 `write_workspace_text_file`。
- 严禁调用 `run_intent`。
- 严禁调用 `run_configured_intent`。
- 严禁使用 `__ASSISTANT_RESPONSE__`。
- `path` 必须是工作区相对路径，并且以 `.txt` 或 `.md` 结尾。
- `content` 必须直接放完整最终正文。
- 如果用户要求不少于 300 字、500 字、3000 字或更多，先把完整正文写好，再一次性放进 `content`。
- 在工具真正返回成功之前，不要输出“执行失败”“我会重试”“参数缺失”之类的说明。
- 成功后只用一句中文确认已经写入哪个文件。

标准调用样例：

- `write_workspace_text_file(path="自我介绍.txt", content="这里直接放完整正文")`
- `write_workspace_text_file(path="都市悬疑故事.txt", content="这里直接放完整正文，哪怕正文较长也必须一次性写入")`
