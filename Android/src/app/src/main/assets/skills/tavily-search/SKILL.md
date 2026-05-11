---
name: tavily-search
description: 使用 Tavily 搜索实时网络内容，并向端侧模型返回带来源依据的结果。
metadata:
  require-secret: true
  require-secret-description: 请输入您的 Tavily API 密钥，通常以 tvly- 开头。
  homepage: https://tavily.com
---

# Tavily Search

## When to use

Use this skill when the user asks for live web information, current events, product changes, or facts that may be newer than the model's local knowledge.

## Instructions

Call the `run_js` tool using `index.html` and pass a compact JSON string in `data`.

Required field:

- `query`: The real search intent, rewritten only enough to make the search concise.

Optional fields:

- `topic`: Use `general`, `news`, or `finance` only when the query clearly needs it.
- `time_range`: Use `day`, `week`, `month`, or `year` only when the user explicitly asks for recency.
- `exact_match`: Use `true` only when the user clearly needs an exact phrase.
- `include_domains`: Optional array of domains when the user explicitly restricts sources.
- `exclude_domains`: Optional array of domains when the user explicitly wants some sources excluded.

The app already applies the user's saved defaults for search depth, result count, and detail level. Do not send extra fields unless they materially improve the search.

## Constraints

- Keep calls minimal to reduce context cost.
- After the tool returns, answer in the same language as the user.
- If sources conflict or coverage is thin, say so clearly.
