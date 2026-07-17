---
name: weather-query
description: 查询指定城市或当前位置的当前天气、未来24小时天气和未来一周天气。
---

# Weather Query

## When to use

Use this skill when the user asks about weather, temperature, rainfall, wind, current conditions, the next 24 hours, or the next week.

## Instructions

For native tool mode, call the `run_intent` tool with:

- `intent`: `query_weather`
- `parameters`: a compact JSON string with:
  - `location`: city or place name, such as `昆明`, `北京`, `London`, or `New York`. Use `current` only when the user explicitly asks for the current device location.
  - `mode`: `current`, `24h`, or `week`.

For compatibility tool mode, call `query_weather` directly with the same `location` and `mode` fields.

Rules:

- Prefer a city name when the user provides one.
- If the user asks for local weather, use `location` as `current`.
- If current location is unavailable or permission is denied, ask the user for a city name instead of guessing.
- Keep the final answer concise and in the same language as the user.

Examples:

- Current weather in Kunming: `{"location":"昆明","mode":"current"}`
- Next 24 hours in Beijing: `{"location":"北京","mode":"24h"}`
- One week in London: `{"location":"London","mode":"week"}`
