---
name: powerpoint-presentation
description: 创建、读取和编辑 PPTX：创建多页演示文稿、替换文字、增删幻灯片、修改指定形状文字和新增文本框。
---

# PowerPoint Presentation

Use this skill only for PPTX presentation work inside the mounted workspace.

Call `run_configured_intent` with:
- `skillName`: `powerpoint-presentation`
- `intent`: `file_workspace`
- `parameters`: one compact JSON string

Generated PowerPoint files are saved under `file/`. Use workspace-relative paths only.

Supported operations:

- Create: `{"operation":"pptx_create","output_path":"file/deck.pptx","slides":[{"title":"第一页","content":"正文"},{"title":"第二页","content":"正文"}]}`
- Read: `{"operation":"pptx_read","input_path":"file/deck.pptx"}`
- Modify: `{"operation":"pptx_modify","input_path":"file/deck.pptx","output_path":"file/deck.pptx","operations":[...]}`

Modify actions:
- `replace_text`: params `old`, `new`
- `update_slide_text`: params `slide` (one-based), `shape_name`, `text`
- `add_textbox`: params `slide`, `text`, optional `name`, `left`, `top`, `width`, `height`, `font_size`, `bold`
- `add_slide`: params `title`, `content`
- `delete_slide`: params `slide` (one-based)

For files created by this skill, the standard text shape names are `Title` and `Content`. Prefer one `pptx_modify` call containing all requested edits. After success, report the exact returned workspace path.
