---
name: langsearch-search
description: Search the live web with LangSearch and return concise source-grounded results for on-device models.
metadata:
  require-secret: true
  require-secret-description: Enter your LangSearch API key.
  homepage: https://langsearch.com
---

# LangSearch Search

## When to use

Use this skill when the user asks for live web information and you want a simple, fast, low-friction search flow.

## Instructions

Call the `run_js` tool using `index.html` and pass a compact JSON string in `data`.

Required field:

- `query`: The real search intent, rewritten only enough to make the search concise.

Optional fields:

- `freshness`: Use `oneDay`, `oneWeek`, `oneMonth`, `oneYear`, or `noLimit` only when the user clearly needs a specific recency range.

The app already applies the user's saved defaults for result count and detail level. Do not send extra fields unless they materially improve the search.

## Constraints

- Keep calls minimal to reduce context cost.
- After the tool returns, answer in the same language as the user.
- If sources conflict or coverage is thin, say so clearly.
