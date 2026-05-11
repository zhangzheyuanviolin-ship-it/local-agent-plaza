---
name: exa-search
description: 使用 Exa 搜索实时网络内容，并向端侧模型返回更丰富的来源依据结果。
metadata:
  require-secret: true
  require-secret-description: 请输入您的 Exa API 密钥。
  homepage: https://exa.ai
---

# Exa Search

## When to use

Use this skill when the user asks for live web information and you want stronger semantic retrieval or richer returned source text.

## Instructions

Call the `run_js` tool using `index.html` and pass a compact JSON string in `data`.

Required field:

- `query`: The real search intent, rewritten only enough to make the search concise.

Optional fields:

- `topic`: Use `general`, `news`, or `finance` only when it is clearly implied.
- `time_range`: Use `day`, `week`, `month`, or `year` only when the user explicitly asks for recency.
- `include_domains`: Optional array of domains when the user explicitly restricts sources.
- `exclude_domains`: Optional array of domains when the user explicitly wants some sources excluded.

The app already applies the user's saved defaults for search type, result count, and detail level. Do not send extra fields unless they materially improve the search.

## Constraints

- Keep calls minimal to reduce context cost.
- After the tool returns, answer in the same language as the user.
- If sources conflict or coverage is thin, say so clearly.
