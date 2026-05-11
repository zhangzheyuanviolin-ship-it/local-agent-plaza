---
name: tavily-search
description: Search the live web with Tavily and return source-grounded results optimized for on-device models.
metadata:
  require-secret: true
  require-secret-description: Enter your Tavily API key. It usually starts with tvly-.
  homepage: https://tavily.com
---

# Tavily Search

## When to use

Use this skill when the user asks for live web information, recent updates, current events, product changes, or facts that are likely newer than the model's local knowledge.

## Instructions

Call the `run_js` tool using `index.html` and a JSON string for `data` with the following fields:

- `query`: Required. Keep the user's real search intent. Rewrite only enough to make the search query concise.
- `topic`: Required. One of `general`, `news`, or `finance`. Use `news` for recent events, `finance` for market or company financial topics, otherwise use `general`.
- `search_depth`: Required. One of `basic` or `advanced`. Default to `basic`. Use `advanced` only when the user explicitly asks for more detail or when `detail_mode` is `full`.
- `max_results`: Required. Must be one of `1`, `3`, `5`, or `8`. Default to `3`. Keep it as small as possible unless the user clearly asks for broader coverage.
- `detail_mode`: Required. One of `summary`, `light`, `standard`, or `full`. Default to `summary`.
  - `summary`: lowest context cost.
  - `light`: short snippets from each source.
  - `standard`: richer snippets and metadata.
  - `full`: highest context cost and includes truncated extracted passages.
- `time_range`: Optional. One of `day`, `week`, `month`, or `year`. Use it only when the user clearly wants recent results.
- `exact_match`: Optional boolean. Use `true` only when the user clearly needs an exact quoted phrase.
- `include_domains`: Optional array of domains when the user explicitly restricts sources.
- `exclude_domains`: Optional array of domains when the user explicitly wants some sources excluded.

## Constraints

- Prefer `summary` or `light` unless the user explicitly asks for more detail.
- Do not request more data than needed.
- After the tool returns, answer in the same language as the user.
- If sources conflict or the answer is incomplete, say so clearly.
