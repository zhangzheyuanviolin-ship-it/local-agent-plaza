---
name: edge-tts
description: 使用 Microsoft Edge TTS 将文字或工作区文本文件合成为 MP3 音频，并保存到工作区 media 文件夹。
---

# Edge TTS

## When to use

Use this skill when the user asks to convert text to speech, synthesize narration, create an MP3 voice file, list available voices, or read a workspace text file aloud.

## Instructions

To list supported voices:

- In native tool mode, call `run_intent` with `intent` as `list_edge_tts_voices` and `parameters` as `{}`.
- In compatibility tool mode, call `list_edge_tts_voices` with `{}`.

To synthesize speech:

- In native tool mode, call `run_configured_intent` with:
  - `skillName`: `edge-tts`
  - `intent`: `file_workspace`
  - `parameters`: a compact JSON string
- In compatibility tool mode, call `edge_tts_synthesize` directly.

Synthesis parameters:

- `operation`: `edge_tts_synthesize` when using `run_configured_intent`.
- `text`: text to synthesize. Use this for short direct text.
- `input_path`: workspace text file path, such as `file/article.txt`. Use this instead of `text` when the user wants to synthesize a file.
- `voice`: voice id, such as `zh-CN-XiaoxiaoNeural`, `zh-CN-YunxiNeural`, `zh-CN-YunyangNeural`, `en-US-JennyNeural`, or `en-US-GuyNeural`.
- `output_path`: MP3 output path. Prefer `media/name.mp3`.
- Optional: `rate`, `pitch`, and `volume`.

Rules:

- The user must configure a writable workspace folder for this skill before saving audio.
- Prefer the `media/` folder for generated MP3 files.
- Use `zh-CN-XiaoxiaoNeural` if the user does not choose a voice.
- If the user asks to synthesize the contents of an existing workspace file, you MUST pass that workspace file path as `input_path` directly.
- Do NOT call `read_text` first and do NOT copy the file content into the `text` argument when the source is already a workspace file.
- For long text already in a file, always use `input_path` to avoid wasting context.
- After synthesis, tell the user the saved MP3 path and voice used.

Examples:

- List voices: `{}`
- Direct text to MP3: `{"operation":"edge_tts_synthesize","text":"你好，欢迎使用本地智能体广场。","voice":"zh-CN-XiaoxiaoNeural","output_path":"media/welcome.mp3"}`
- File to MP3: `{"operation":"edge_tts_synthesize","input_path":"file/story.txt","voice":"zh-CN-YunxiNeural","output_path":"media/story.mp3"}`
