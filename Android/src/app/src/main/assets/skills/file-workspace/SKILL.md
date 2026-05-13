---
name: file-workspace
description: 在用户授权的单一文件夹内执行文件与文件夹操作，并返回结构化结果。
---

# File Workspace

## When to use

Use this skill when the user asks to inspect, create, update, move, copy, rename, or delete files inside the mounted workspace folder.

## Instructions

Call the `run_configured_intent` tool with:

- `skillName`: `file-workspace`
- `intent`: `file_workspace`
- `parameters`: a compact JSON string

The app already injects the saved mounted folder configuration. Never ask the user for absolute paths. Always work with relative paths inside the mounted folder only.

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

## Constraints

- For multi-step tasks, make as many tool calls as needed. Do not stop after a single call if the task still requires more file operations.
- If you are unsure where the target file is, start with `list` before using `read_text`, `move`, `copy`, or `delete`.
- Prefer the smallest operation that solves the task.
- After the tool returns, answer in the same language as the user.
