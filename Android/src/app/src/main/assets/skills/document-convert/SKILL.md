---
name: document-convert
description: 在 TXT、DOCX、PDF、HTML 之间进行文档格式转换，并把转换结果安全写入工作区 file/。
---

# Document Convert

Use this skill when the user asks to convert a document between TXT, DOCX, PDF, and HTML.

Call `run_configured_intent` with:
- `skillName`: `document-convert`
- `intent`: `file_workspace`
- `parameters`: one compact JSON string

Supported operation:

- Convert: `{"operation":"document_convert","input_path":"file/source.docx","output_format":"pdf","output_path":"file/source.pdf"}`

Supported inputs: `txt`, `docx`, `pdf`, `html`, `htm`.
Supported outputs: `txt`, `docx`, `pdf`, `html`.

Path tolerance:
- A bare input filename such as `report.txt` or `report.docx` is treated as `file/report.txt` or `file/report.docx`.
- Existing explicit workspace folders such as `file/` and `download/` are preserved.
- If `output_path` is omitted, the app derives a collision-safe path under `file/`.
- The source file is always preserved. A conversion may not write back onto the source path.

Conversion behavior:
- Cross-format conversion is text-oriented. Complex layout, embedded media, formulas, images, and advanced Office styling may be simplified.
- Same-format conversion (`txt→txt`, `docx→docx`, `pdf→pdf`, `html→html`) uses an exact byte-preserving copy instead of a text round-trip.
- Cross-format conversion refuses to silently truncate source text that exceeds the supported conversion text budget.
- A scanned/image-only PDF with no extractable text cannot be converted to another text-oriented format by this skill because OCR is not part of this conversion path. Same-format PDF copying still works.
- PDF output is reopened and verified with layout-whitespace-insensitive semantic checks so line wrapping does not cause a valid PDF to be rejected.

After success, report the exact returned workspace path.
