---
name: web-page-extract
description: 提取指定网页的标题、摘要和正文内容，并以端侧模型更容易理解的 Markdown 风格文本返回。
metadata:
  require-secret: false
  homepage: https://jsoup.org
---

# Web Page Extract

Use this skill only when it is enabled by the user. If it is not enabled, do not mention or call web page extraction tools.

Use this skill when the user provides a fixed web page URL and asks to read, summarize, audit, rewrite, quote, or extract the page content. It fetches normal HTML pages directly, removes navigation and boilerplate where possible, and returns compact readable content.

## Compatibility Tool Mode

Call `extract_web_page` with:

- `url`: required, an `http://` or `https://` page URL.
- `max_chars`: optional, default 12000. Use a smaller value when the model context is tight.

Example:

`{"url":"https://example.com/article","max_chars":12000}`

## Native Tool Mode

Call `run_configured_intent` with `skillName` as `web-page-extract`, intent `extract_web_page`, and the same JSON arguments.

## Constraints

- This is for HTML or text pages, not PDF, image, audio, video, or login-only pages.
- If extraction is truncated, tell the user that only the visible content was analyzed.
- Do not invent page content beyond the returned tool result.
