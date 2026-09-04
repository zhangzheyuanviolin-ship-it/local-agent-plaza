---
name: word-document
description: 创建、读取和编辑 DOCX：写入标题与正文、替换文字、追加段落/标题、分页、创建表格和修改表格单元格。
---

# Word Document

Use this skill only for Microsoft Word DOCX work inside the mounted workspace.

Call `run_configured_intent` with:
- `skillName`: `word-document`
- `intent`: `file_workspace`
- `parameters`: one compact JSON string

All generated Word files are saved under `file/`. Use workspace-relative paths only. If the user gives only a file name, use `file/<name>.docx`.

Supported operations:

- Create: `{"operation":"word_create","output_path":"file/report.docx","title":"标题","content":"正文"}`
- Read: `{"operation":"word_read","input_path":"file/report.docx"}`
- Modify: `{"operation":"word_modify","input_path":"file/report.docx","output_path":"file/report.docx","operations":[...]}`

Modify actions:
- `replace_text`: params `old`, `new`
- `add_paragraph`: params `text`
- `add_heading`: params `text`, optional `level` 1-6
- `add_page_break`: no params required
- `add_table`: params `rows` as a 2D array
- `update_table_cell`: params `table`, `row`, `col` (zero-based), `text`

When editing an existing DOCX, prefer one `word_modify` call containing all requested operations. Do not rewrite or convert unrelated files. After success, report the exact workspace path returned by the tool.
