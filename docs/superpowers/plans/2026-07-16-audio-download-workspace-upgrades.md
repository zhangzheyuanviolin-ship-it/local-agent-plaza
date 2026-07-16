# Audio Download Workspace Upgrades Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add practical long-audio handling, resumable model download UX, and workspace document text extraction for common office files.

**Architecture:** Keep the existing chat and worker architecture. Add focused helpers for audio normalization and document extraction, then wire them into the existing UI/tool entry points. Use existing `.gallerytmp` partial downloads and make the UI expose resume semantics instead of replacing the download engine.

**Tech Stack:** Android MediaExtractor/MediaCodec, WorkManager, Jetpack Compose, direct Office Open XML zip parsing for DOCX/XLSX, PdfBox-Android for PDF extraction.

---

### Task 1: Audio File Normalization and Long Audio

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Utils.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt`
- Test: `Android/src/app/src/test/java/com/google/ai/edge/gallery/common/AudioProcessingTest.kt`

- [ ] Write tests for optional audio trimming and split chunk planning.
- [ ] Implement `normalizeAudioToMonoPcm()` using existing WAV path first and Android media decode path for non-WAV.
- [ ] Change file picker text and MIME filters to accept common audio and video containers.
- [ ] Add visible warning text that long audio can consume more memory and time.
- [ ] Verify compile on GitHub Actions.

### Task 2: Download Resume UX

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/modelitem/DownloadModelPanel.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt`

- [ ] Treat `PARTIALLY_DOWNLOADED` as a first-class download-start state.
- [ ] Change button label from generic download to resume when partial bytes exist.
- [ ] Preserve `.gallerytmp` on cancel and failure.
- [ ] Add connection and read timeouts so stalled downloads fail back to a resumable state.
- [ ] Verify compile on GitHub Actions.

### Task 3: Workspace Document Text Extraction

**Files:**
- Modify: `Android/src/app/build.gradle.kts`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/WorkspaceDocumentTextExtractor.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/IntentHandler.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTooling.kt`
- Test: `Android/src/app/src/test/java/com/google/ai/edge/gallery/customtasks/agentchat/WorkspaceDocumentTextExtractorTest.kt`

- [ ] Add PdfBox-Android dependency and parse DOCX/XLSX directly from their Office Open XML zip contents.
- [ ] Implement extension-based extraction for txt, md, csv, json, xml, log, html, pdf, docx, and xlsx.
- [ ] Return `content_type`, `detected_format`, `truncated`, and `bytes_read` in read results.
- [ ] Update tool descriptions so the agent knows office documents can be read.
- [ ] Verify compile on GitHub Actions.

### Task 4: Release

**Files:**
- Modify docs only if needed for user-facing notes.

- [ ] Run local static checks where available.
- [ ] Commit and push experimental.
- [ ] Wait for branch build.
- [ ] Tag an MCP experimental release.
- [ ] Download APK with `curl -L -C -`, verify sha256, package, version, and signer.
- [ ] Archive to current APK folder and install over `com.localagent.plaza.mcp`.
- [ ] Delete temporary GitHub Actions artifacts but keep release assets.
