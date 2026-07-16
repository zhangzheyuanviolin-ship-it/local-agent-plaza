# Video Question Answering Implementation Plan

Goal: Add an experimental video question-answering task that extracts frames from user-selected videos and sends them to local visual-language models as ordered image context.

Architecture: Implement a new CustomTask backed by the existing LiteRT-LM image input path. Use Android MediaMetadataRetriever for frame extraction, system Photo Picker for gallery video selection, and ACTION_OPEN_DOCUMENT for file picker video selection. Keep the feature on experimental only and publish an MCP-tagged APK for the currently installed package.

Tasks:
1. Add video QA task id and model routing so every image-capable model also appears in video QA, including imported models.
2. Add frame planning and frame extraction helpers with unit-testable pure timing logic.
3. Add VideoQuestionAnswer ViewModel and Compose screen with complete-video and keyframe modes, frame count and frame size controls, gallery and file video pickers, processing status, thumbnails, prompt field, and local model invocation.
4. Add strings/docs and build verification.
5. Push experimental, tag MCP release, download with curl -C, verify APK, archive, and install over com.localagent.plaza.mcp.
