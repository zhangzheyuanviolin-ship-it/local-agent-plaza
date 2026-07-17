---
name: agnes-omni
description: 调用 Agnes 免费图片与视频生成 API，把生成结果保存到工作区 media 文件夹。
metadata:
  require-secret: true
  require-secret-description: Agnes API Key
  homepage: https://agnes-ai.com
---

# Agnes 全模态工具箱

## When to use

Use this skill when the user asks to generate an image or a video through Agnes AI.

## Requirements

- The user must enable this skill manually.
- The user must configure an Agnes API key in the skill manager.
- The user must configure the file workspace folder, because generated media is saved under `media/`.
- Generation settings such as model, image size, image ratio, video duration, and video resolution are controlled by the skill settings page. Do not pass those settings in tool arguments.

## Tools

For image generation:

- Compatibility mode: call `generate_agnes_image`.
- Native mode: call `run_configured_intent` with `skillName` as `agnes-omni`, `intent` as `agnes_generate_image`, and compact JSON parameters.
- Parameters: `prompt` is required. `output_path` is optional and should point to `media/name.png`.

For video generation:

- Compatibility mode: call `generate_agnes_video`.
- Native mode: call `run_configured_intent` with `skillName` as `agnes-omni`, `intent` as `agnes_generate_video`, and compact JSON parameters.
- Parameters: `prompt` is required. `output_path` is optional and should point to `media/name.mp4`.

## Rules

- Only pass the visual prompt and optional output path.
- If the user gives a rough idea, improve it into a concise visual prompt before calling the tool.
- Do not invent API keys or ask the user for model names during tool calling.
- After success, tell the user the saved workspace path.

## Examples

- Image: `{"prompt":"A quiet rainy cyberpunk alley, neon reflections, cinematic lighting","output_path":"media/rainy-alley.png"}`
- Video: `{"prompt":"A cinematic 5-second shot of a violinist walking through soft morning fog, slow camera push-in","output_path":"media/violinist-fog.mp4"}`
