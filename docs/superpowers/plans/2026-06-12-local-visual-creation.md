# Local Visual Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-stage "本地视觉创作" module to Local Agent Plaza that exposes the complete visual creation workflow shell and starts with local text-to-image generation.

**Architecture:** Implement the module as a new CustomTask under `customtasks/visualcreation`, with focused Kotlin data models, a model registry, a repository, a Compose screen, and a replaceable `ImageGenerationEngine` interface. The first milestone keeps VLM review as an explicit bridge interface and reserved UI area, while image generation is wired through a native bridge backed by stable-diffusion.cpp after the Kotlin shell is test-covered.

**Tech Stack:** Android Kotlin, Jetpack Compose, Hilt CustomTask multibinding, JUnit, Android MediaStore, CMake/JNI, stable-diffusion.cpp, GitHub Actions.

---

### Task 1: Branch Workspace And Plan

**Files:**
- Create: `docs/superpowers/plans/2026-06-12-local-visual-creation.md`

- [ ] **Step 1: Create isolated worktree**

Run `git worktree add /data/user/0/com.codex.mobile.pocketlobster.test/files/home/codex/worktrees/local-visual-creation feature/local-visual-creation`.

- [ ] **Step 2: Verify clean branch**

Run `git status --short --branch`.
Expected: branch `feature/local-visual-creation`, no changed files before edits.

### Task 2: Visual Creation Domain Models

**Files:**
- Create: `Android/src/app/src/test/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationDomainTest.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationModels.kt`

- [ ] **Step 1: Write failing tests**

Test default generation settings, random seed resolution, session creation, and visual process mode labels.

- [ ] **Step 2: Run focused unit test**

Run `bash ./gradlew :app:testDebugUnitTest --tests com.google.ai.edge.gallery.customtasks.visualcreation.VisualCreationDomainTest`.
Expected before implementation: compilation fails because visual creation domain classes do not exist.

- [ ] **Step 3: Implement minimal domain models**

Add `VisualCreationStatus`, `VisualProcessMode`, `ImageGenerationSettings`, `VisualCreationSession`, and `VisualCreationMessage`.

- [ ] **Step 4: Verify test passes**

Run the same focused unit test.
Expected after implementation: test passes.

### Task 3: Image Generation Model Registry

**Files:**
- Create: `Android/src/app/src/test/java/com/google/ai/edge/gallery/customtasks/visualcreation/ImageGenerationModelRegistryTest.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/ImageGenerationModelRegistry.kt`

- [ ] **Step 1: Write failing tests**

Test that the registry includes an engineering verification model, a Chinese-capable Z-Image Turbo candidate, and model package metadata for required files.

- [ ] **Step 2: Run focused unit test**

Run `bash ./gradlew :app:testDebugUnitTest --tests com.google.ai.edge.gallery.customtasks.visualcreation.ImageGenerationModelRegistryTest`.
Expected before implementation: compilation fails because the registry does not exist.

- [ ] **Step 3: Implement minimal registry**

Add `ImageGenerationModelInfo`, `ImageGenerationModelFile`, `ImageGenerationBackend`, and `ImageGenerationModelRegistry`.

- [ ] **Step 4: Verify test passes**

Run the same focused unit test.
Expected after implementation: test passes.

### Task 4: First Screen Skeleton And CustomTask Entry

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationTask.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationScreen.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationViewModel.kt`

- [ ] **Step 1: Add task entry**

Register CustomTask id `llm_local_visual_creation`, label `本地视觉创作`, and description `生成图片、理解图片，并基于图片继续创作`.

- [ ] **Step 2: Add screen skeleton**

Build prompt input, negative prompt input, parameter area, model management entry, result area, save buttons, and reserved VLM processing controls.

- [ ] **Step 3: Build Kotlin**

Run `bash ./gradlew :app:compileDebugKotlin`.
Expected: Kotlin compilation succeeds.

### Task 5: Repository, File Saving, And Fake Engine

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/ImageGenerationEngine.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/ImageGenerationRepository.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationFileRepository.kt`

- [ ] **Step 1: Add tests for metadata serialization and output paths**

Use JVM tests for deterministic file names and metadata fields.

- [ ] **Step 2: Implement fake image generation path**

Generate or copy a local placeholder PNG so the UI can exercise result display, metadata saving, and save actions before native integration.

- [ ] **Step 3: Verify app compiles**

Run `bash ./gradlew :app:compileDebugKotlin`.
Expected: Kotlin compilation succeeds.

### Task 6: Native Bridge And stable-diffusion.cpp

**Files:**
- Create: `Android/src/app/src/main/cpp/visual_creation/image_generation_bridge.cpp`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/ImageGenerationNativeBridge.kt`
- Modify: `Android/src/app/build.gradle.kts`
- Modify: `.github/workflows/build_android.yaml`

- [ ] **Step 1: Add CMake wiring**

Configure `externalNativeBuild` for arm64-v8a and vendor stable-diffusion.cpp source.

- [ ] **Step 2: Add JNI load, generate, cancel, unload methods**

Wrap `new_sd_ctx`, `generate_image`, and `free_sd_ctx`.

- [ ] **Step 3: Cloud build**

Push branch, trigger `Build Android APK`, download `local-agent-plaza-release`, and copy the APK into `/storage/emulated/0/下载管理/local-visual-creation/apks/`.

### Task 7: Android Device Verification

**Files:**
- Update: `docs/local-visual-creation-stage1-notes.md`

- [ ] **Step 1: Install APK manually on device**

User installs downloaded APK.

- [ ] **Step 2: Verify package and launch**

Use system shell package checks and activity launch checks for `com.localagent.plaza`.

- [ ] **Step 3: Capture feedback**

Record generated APK path, run id, known issues, and user test feedback.
