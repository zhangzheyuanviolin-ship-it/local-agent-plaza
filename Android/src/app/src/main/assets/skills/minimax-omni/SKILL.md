---
name: minimax-omni
description: MiniMax Token Plan full multimodal toolbox for text, image generation, speech synthesis, music generation, image analysis, video analysis, and web search through China-region MiniMax APIs.
metadata:
  require-secret: true
  require-secret-description: MiniMax API Key
  homepage: https://platform.minimaxi.com
---

# MiniMax Full Multimodal Toolbox

Use this skill only when it is enabled by the user. If it is not enabled, do not mention or call MiniMax tools.

The user configures API host, models, voice, image ratio, music mode, and media size limit in the skill settings page. Do not pass complex model parameters in tool calls.

## Rules

- Keep tool arguments compact.
- Generated audio, music, and images are saved into the workspace media folder.
- For text-to-speech from an existing workspace file, pass `input_path` directly. Do not read the file first and do not copy the full file content into `text`.
- For image or video analysis, pass the workspace-relative media path directly.
- For long music generation, wait for the tool result instead of issuing another call.

## Compatibility Tools

- Text generation: call `minimax_generate_text` with `prompt`.
- Image generation: call `minimax_generate_image` with `prompt` and optional `output_path`.
- Speech synthesis: call `minimax_tts_synthesize` with either `text` or `input_path`, and optional `output_path`.
- Music generation: call `minimax_generate_music` with `prompt` and optional `output_path`.
- Image analysis: call `minimax_analyze_image` with `input_path` and optional `prompt`.
- Video analysis: call `minimax_analyze_video` with `input_path` and optional `prompt`.
- Web search: call `minimax_search_web` with `query`.

## Native Tool Mode

Call `run_configured_intent` with `skillName` as `minimax-omni`.

Valid intents are:

- `minimax_generate_text`
- `minimax_generate_image`
- `minimax_tts_synthesize`
- `minimax_generate_music`
- `minimax_analyze_image`
- `minimax_analyze_video`
- `minimax_search_web`
