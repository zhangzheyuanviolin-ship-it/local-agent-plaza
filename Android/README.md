# Local Agent Plaza Android

This directory contains the Android application for Local Agent Plaza.

The app is based on Google AI Edge Gallery and extends it with local agent tooling, MCP integration, visual creation, real-time vision narration, AI keyboard workflows, video question answering, Vosk speech recognition, richer workspace tools, multimodal API skills, and media processing utilities.

Build entrypoint:

- Project directory: `Android/src`
- Main Gradle task: `./gradlew assembleRelease`
- Default package: `com.localagent.plaza`
- Stable version line: `1.0.14-plaza`
- Experimental package suffix: `.mcp`
- Experimental package: `com.localagent.plaza.mcp`

Release builds are produced by GitHub Actions. Local Android builds may fail on the phone Ubuntu runtime before tests because AAPT2 daemon startup is not reliable in that environment; use cloud Actions signing and version verification as the release source of truth.

The stable release branch is `main`. The long-running experimental branch is `experimental`; it is intentionally kept after releases so that new modules can be tested in the `.mcp` package before being promoted to stable.
