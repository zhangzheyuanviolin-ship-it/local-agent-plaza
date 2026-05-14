---
name: long-text-writer
description: 只负责创建或覆盖一个 txt 或 md 文档，并把完整正文一次性写入文档，适合长文本写作。
---

# Long Text Writer

## When to use

Use this skill when the user wants a text document to be created and filled with the final body content, especially for longer writing tasks.

## Instructions

Call `run_configured_intent` with:

- `skillName`: `long-text-writer`
- `intent`: `file_workspace`
- `parameters`: a compact JSON string

Rules:

- This skill only writes one text document.
- Work inside the mounted folder only.
- Use a relative file path ending in `.txt` or `.md`.
- Use exactly one `write_text` call for the document body whenever possible.
- Put the full final body directly in `content`.
- Never use `__ASSISTANT_RESPONSE__`.
- Never call `list`, `read_text`, `delete`, `copy`, `move`, or `create_dir` unless the user explicitly changes the task away from writing.
- If the user specifies a minimum length, satisfy it in the text you place in `content`.
- Do not put commentary, summaries, or status text into the file body unless the user asked for them.

Examples:

- `{"operation":"write_text","path":"自我介绍.txt","content":"这里直接放完整正文"}`
- `{"operation":"write_text","path":"stories/city.md","content":"这里直接放完整正文，哪怕正文较长也直接写入 content"}`

After the tool returns, answer in the same language as the user with a short confirmation.
