---
name: anysearch-search
description: 使用 AnySearch 为智能体执行实时网络搜索、垂直领域搜索和网页全文提取。
metadata:
  require-secret: true
  require-secret-description: 请输入您的 AnySearch API 密钥，通常以 as_sk_ 开头。
  homepage: https://github.com/anysearch-ai/anysearch-skill
---

# AnySearch Search

Use this skill only when it is enabled by the user. If it is not enabled, do not mention or call AnySearch tools.

Use this skill when the user asks for live web information, current facts, structured vertical sources, or full page extraction through AnySearch. The app stores the AnySearch API key and search defaults in the skill settings page.

## Compatibility Tool Mode

For normal web search, prefer `search_web` with one compact argument:

`{"query":"search keywords"}`

The app routes `search_web` to AnySearch when this skill is enabled and preferred.

For direct AnySearch calls:

- `anysearch_search` arguments: `{"query":"...","max_results":5}`. Optional fields `domain`, `sub_domain`, and `sub_domain_params` are accepted only when vertical domain mode is enabled in settings.
- `anysearch_extract` arguments: `{"url":"https://example.com/page"}`. Extracts full page content through AnySearch.
- `anysearch_get_sub_domains` arguments: `{"domain":"finance"}` or `{"domains":["finance","academic"]}`. Use before vertical search when the user needs structured domain sources.

## Native Tool Mode

Call `run_configured_intent` with `skillName` as `anysearch-search`. Valid intents are:

- `anysearch_search`
- `anysearch_extract`
- `anysearch_get_sub_domains`

## Constraints

- Keep queries concise and factual.
- Do not pass complex fields unless the user clearly needs vertical search.
- After results are returned, answer only from the returned content and cite uncertainty when coverage is thin.
