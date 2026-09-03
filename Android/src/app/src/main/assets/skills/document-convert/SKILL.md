---
name: document-convert
description: 在 TXT、DOCX、PDF、HTML 之间进行文本导向的文档格式转换，并把转换结果写入工作区 file/。
---

# Document Convert

Use this skill when the user asks to convert a document between TXT, DOCX, PDF, and HTML.

Call `run_configured_intent` with:
- `skillName`: `document-convert`
- `intent`: `file_workspace`
- `parameters`: one compact JSON string

Use workspace-relative input paths. Converted files are always written under `file/`.

Supported operation:

- Convert: `{"operation":"document_convert","input_path":"file/source.docx","output_format":"pdf","output_path":"file/source.pdf"}`

Supported inputs: `txt`, `docx`, `pdf`, `html`, `htm`.
Supported outputs: `txt`, `docx`, `pdf`, `html`.

This is a text-oriented conversion path. Complex layout, embedded media, formulas, and advanced Office styling can be simplified during conversion. Preserve the source file. If `output_path` is omitted, the app derives a file name under `file/`. After success, report the exact returned workspace path.
