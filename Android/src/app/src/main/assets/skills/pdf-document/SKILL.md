---
name: pdf-document
description: 创建、读取和整理 PDF：文本生成 PDF、合并、提取/拆分页面、页面重排、删除页面和旋转页面。
---

# PDF Document

Use this skill for PDF creation, reading, and page-level organization inside the mounted workspace.

Call `run_configured_intent` with:
- `skillName`: `pdf-document`
- `intent`: `file_workspace`
- `parameters`: one compact JSON string

Generated PDF files are saved under `file/`. Use workspace-relative paths only.

Supported operations:

- Create from text: `{"operation":"pdf_create","output_path":"file/report.pdf","title":"标题","content":"正文"}`
- Read: `{"operation":"pdf_read","input_path":"file/report.pdf"}`
- Merge: `{"operation":"pdf_merge","input_paths":["file/a.pdf","file/b.pdf"],"output_path":"file/merged.pdf"}`
- Extract pages: `{"operation":"pdf_extract_pages","input_path":"file/a.pdf","pages":"1-3,5","output_path":"file/extract.pdf"}`
- Reorder pages: `{"operation":"pdf_reorder_pages","input_path":"file/a.pdf","pages":[3,1,2],"output_path":"file/reordered.pdf"}`
- Delete pages: `{"operation":"pdf_delete_pages","input_path":"file/a.pdf","pages":[2,4],"output_path":"file/clean.pdf"}`
- Rotate pages: `{"operation":"pdf_rotate_pages","input_path":"file/a.pdf","pages":"all","degrees":90,"output_path":"file/rotated.pdf"}`

Page numbers are one-based. Rotation must be a multiple of 90 degrees. Preserve the original file unless the user clearly requests in-place output. After success, report the exact returned workspace path.
