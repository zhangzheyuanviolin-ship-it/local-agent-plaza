---
name: media-toolbox
description: Local multimedia toolbox for basic image, audio, and video processing in the mounted workspace.
metadata:
  require-secret: false
  homepage: https://github.com/ffmpegkit-maintained/ffmpeg-kit
---

# Media Toolbox

Use this skill only when it is enabled by the user. If it is not enabled, do not mention or call media toolbox tools.

The user can enable image mode, audio mode, and video mode independently in the skill settings page. Use only the tools exposed in the current tool list.

All generated files are saved into the workspace media folder unless the user provides a workspace-relative output path. Do not write FFmpeg commands yourself. Call one compact tool for one operation.

## Image Mode

- Image info: call `media_image_info` with `input_path`.
- Resize image: call `media_image_resize` with `input_path`, optional `target` such as `512`, `720p`, `1080p`, or `4k`, and optional `output_path`.
- Convert image format: call `media_image_convert` with `input_path`, `target_format` as `jpg`, `png`, or `webp`, and optional `output_path`.
- Make video from image: call `media_image_to_video` with `input_path`, optional `duration_seconds`, and optional `output_path`. Default duration is 5 seconds.

## Audio Mode

- Audio info: call `media_audio_info` with `input_path`.
- Convert audio format: call `media_audio_convert` with `input_path`, `target_format` such as `mp3`, `wav`, `m4a`, `aac`, `ogg`, or `flac`, and optional `output_path`.
- Concatenate audio: call `media_audio_concat` with `input_paths` containing 2 to 5 workspace audio paths and optional `output_path`.
- Trim audio: call `media_audio_trim` with `input_path`, `start`, `end`, and optional `output_path`. Time format can be seconds, `mm:ss`, or `hh:mm:ss`.
- Mix audio: call `media_audio_mix` with `primary_path`, `secondary_path`, optional `primary_volume`, `secondary_volume`, `secondary_start`, `loop_secondary`, and optional `output_path`.

## Video Mode

- Video info: call `media_video_info` with `input_path`.
- Convert video format: call `media_video_convert` with `input_path`, `target_format` such as `mp4`, `mov`, `mkv`, or `webm`, and optional `output_path`.
- Concatenate video: call `media_video_concat` with `input_paths` containing 2 to 5 workspace video paths and optional `output_path`. Best results require videos with matching codec, resolution, and container.
- Trim video: call `media_video_trim` with `input_path`, `start`, `end`, and optional `output_path`. Time format can be seconds, `mm:ss`, or `hh:mm:ss`.
- Extract audio from video: call `media_video_extract_audio` with `input_path`, optional `target_format` as `mp3` or `wav`, and optional `output_path`.
- Mute video: call `media_video_mute` with `input_path` and optional `output_path`.
- Add audio to video: call `media_video_add_audio` with `video_path`, `audio_path`, optional `original_volume`, `audio_volume`, and optional `output_path`.

## Native Tool Mode

Call `run_configured_intent` with `skillName` as `media-toolbox`.

Valid intents are:

- `media_image_info`
- `media_image_resize`
- `media_image_convert`
- `media_image_to_video`
- `media_audio_info`
- `media_audio_convert`
- `media_audio_concat`
- `media_audio_trim`
- `media_audio_mix`
- `media_video_info`
- `media_video_convert`
- `media_video_concat`
- `media_video_trim`
- `media_video_extract_audio`
- `media_video_mute`
- `media_video_add_audio`
