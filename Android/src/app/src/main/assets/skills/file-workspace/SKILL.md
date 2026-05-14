---
name: file-workspace
description: 在用户授权的单一文件夹内执行文件与文件夹操作，并返回结构化结果。
---

# File Workspace

## When to use

Use this skill when the user asks to inspect, create, update, move, copy, rename, or delete files inside the mounted workspace folder.

## Instructions

Call `run_configured_intent` with:

- `skillName`: `file-workspace`
- `intent`: `file_workspace`
- `parameters`: a compact JSON string

The app already injects the mounted folder. Never ask for absolute paths.

Rules:

- Work inside the mounted folder only.
- Prefer omitting `path` to mean the workspace root.
- Use relative paths like `notes/todo.txt`.
- Do not use `/home`, `/data`, `/mnt`, `..`, or any system path.
- If the target path is uncertain, call `list` or `stat` first.
- To create a text file with content, use `write_text` directly.
- If the file body is long, do not put the whole body in tool JSON. First call `write_text` with `content` set to `__ASSISTANT_RESPONSE__`. Then output the file body only. Do not add commentary, summary, markdown fences, or any extra text.
- If the task needs multiple file steps, keep calling tools until the task is actually finished.

Supported operations:

- `status`: Check whether a folder is mounted and writable.
- `list`: List files in a directory. Fields: `operation`, optional `path`, optional `max_entries`.
- `stat`: Inspect one file or folder. Fields: `operation`, required `path`.
- `read_text`: Read a text-like file. Fields: `operation`, required `path`, optional `max_bytes`.
- `write_text`: Replace or create a text file. Fields: `operation`, required `path`, required `content`, optional `create_parents`.
- `append_text`: Append to a text file. Fields: `operation`, required `path`, required `content`, optional `create_parents`.
- `create_dir`: Create a directory. Fields: `operation`, required `path`.
- `delete`: Delete a file or directory. Fields: `operation`, required `path`, optional `recursive`.
- `copy`: Copy a file or directory. Fields: `operation`, required `path`, required `destination_path`, optional `overwrite`.
- `move`: Move or rename a file or directory. Fields: `operation`, required `path`, required `destination_path`, optional `overwrite`.

Examples:

- List root: `{"operation":"list"}`
- Read file: `{"operation":"read_text","path":"notes/todo.txt"}`
- Create or replace a file: `{"operation":"write_text","path":"notes/intro.txt","content":"你好"}`
- Prepare a long file body: `{"operation":"write_text","path":"stories/city.txt","content":"__ASSISTANT_RESPONSE__"}`
- Move file: `{"operation":"move","path":"todo.txt","destination_path":"archive/todo.txt"}`

After the tool returns, answer in the same language as the user.
