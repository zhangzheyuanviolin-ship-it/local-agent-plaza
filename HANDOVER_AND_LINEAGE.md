# 项目传承与上游关系 · Handover & Lineage

> 给未来接手这个项目的 AI 或工程师的一份"快速上手 + 完整家谱"。

## 1. 一句话介绍

**Local Agent Plaza** 是一款完全本地化的 Android AI Agent 应用。它 Fork 自 Google AI Edge Gallery（一个 Apache 2.0 开源的端侧 GenAI Showcase），移除了开发测试痕迹，重塑了产品命名与包名，完整保留了上游的全部能力并补齐了无障碍侧的视觉旁白模块。

## 2. 上游家谱

```
google-ai-edge/gallery          ← Apache 2.0，上游原始仓库
        |
        |  Fork（2026-04-06）
        v
zhangzheyuanviolin-ship-it/gallery     ← 旧仓库名（开发期）
        |
        |  1c6c8ef  rebrand to Local Agent Plaza
        |  b58dc5e  fix versionName to 1.0.13-plaza.1
        |  798c94f  docs: README
        |  f6fdfbb  docs: VISION_NARRATION
        |  5dfc3bf  docs: RELEASE_NOTES
        |  README/VISION_NARRATION/RELEASE_NOTES/HANDOVER_AND_LINEAGE
        v
zhangzheyuanviolin-ship-it/local-agent-plaza  ← 仓库重命名（2026-06-06）
```

上游 `google-ai-edge/gallery` 当前状态（继承时）：
- Star: 23,584
- Fork: 2,453
- License: Apache License 2.0
- 最新发布: 1.0.15

## 3. 关键里程碑

| 日期 | 事件 |
|---|---|
| 2025-03-31 | Google 发布 `google-ai-edge/gallery` |
| 2026-04-06 | 创建 `zhangzheyuanviolin-ship-it/gallery` fork |
| 2026-04-06 ~ 2026-06-05 | 内部中文无障碍开发期，发布过 v1.0.11-cn / v1.0.12-cn / v1.0.12-cn-final |
| 2026-06-05 | 交接文档生成，移交 AI 接手 |
| 2026-06-05 | 代码清理提交 `1c6c8ef`，确立 Local Agent Plaza 命名 |
| 2026-06-05 | 版本号修复提交 `b58dc5e`，匹配上游 1_0_13.json 白名单 |
| 2026-06-06 | 创建 tag `v1.0.13-plaza.1` 与正式 GitHub Release |
| 2026-06-06 | 仓库更名为 `local-agent-plaza` |
| 2026-06-06 | 文档体系（README / VISION_NARRATION / RELEASE_NOTES / HANDOVER_AND_LINEAGE）就位 |

## 4. 关键基线

- **代码基线**：`b58dc5e7c89dfa953dbf925b099e4d465a9fca91`（含两处提交：`1c6c8ef` 重命名 + `b58dc5e` 版本号修复）
- **稳定版 tag**：`v1.0.13-plaza.1`（annotated tag）
- **APK 产物**：通过 GitHub Actions `build_android.yaml` 在 push 到 `feature/tavily-skill-20260511` 时自动构建
- **工作流构件名**：`local-agent-plaza-release`

## 5. 修改记录（与上游相比的全部改动）

总计 **3 处文件、约 20 行变更**，零功能回退：

1. `Android/src/app/build.gradle.kts` —— `applicationId` / `versionName` / `versionCode`
2. `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/HomeScreen.kt` —— 移除 `suppressHomeIntro` / `suppressBranding` 中的 `visiontest` 触发条件
3. `.github/workflows/build_android.yaml` —— 构件名 `gallery-app-release` → `local-agent-plaza-release`

外加 README 等文档体系更新（与代码无关）。

## 6. 接手指南

如果你是下一位接手者：

1. **先读** [README.md](./README.md) 了解产品定位
2. **再读** [VISION_NARRATION.md](./VISION_NARRATION.md) 了解核心差异化能力
3. **接着**看 [RELEASE_NOTES.md](./RELEASE_NOTES.md) 了解版本历史
4. **看代码**：`Android/src/app/src/main/java/com/google/ai/edge/gallery/`，核心模块：
   - `ui/home/HomeScreen.kt` —— 视觉旁白入口
   - `visionnarration/` —— 视觉旁白实现
   - `ui/modelmanager/ModelManagerViewModel.kt` —— 模型加载与白名单
5. **想 sync 上游**：建议在 v1.0.13 基线上手动 cherry-pick 上游 fix，大版本升级时需要重新评估 `versionName` 与白名单文件的对应关系
6. **想发版**：修改 `versionName` 与 `versionCode`，push 到 `feature/tavily-skill-20260511`，等 Actions 跑完，下载 `local-agent-plaza-release` 构件后发布

## 7. 环境与权限备忘

- **GitHub 账号**：`zhangzheyuanviolin-ship-it`（fork 主用户）
- **仓库**：从 `gallery` 改名为 `local-agent-plaza`
- **默认分支**：`main`（已与 `feature/tavily-skill-20260511` 同步到 `b58dc5e`）
- **GitHub Actions**：使用 `signingConfig debug` 自动签名（无 Play Store 上架能力）
- **Android 最低 SDK**：31（Android 12）
- **目标 SDK**：35（Android 15）
- **本地构建依赖**：Android Studio Hedgehog+ / JDK 17 / Gradle Wrapper

## 8. 风险与边界

- **debug 签名**：发版用的 keystore 是 Actions 内置 debug，**不具备商业分发能力**。如需正式发布，应配置自定义 keystore 到 GitHub Secrets
- **模型白名单依赖**：`versionName` 解析出的 `1_0_XX.json` 必须在上游仓库存在。升级基线版本前需先确认上游已发布对应白名单
- **fork 关系**：本仓库仍保留与 `google-ai-edge/gallery` 的 fork 关系，**不会**从 GitHub 同步上游更新到本仓库主线，需要人工 cherry-pick
- **APK 大小**：约 116 MB 主要是 JNI 库和 AndroidX 依赖，建议后续按需拆分 ABI

## 9. 许可与致谢

本项目遵循 Apache License 2.0。完整许可条款见 [LICENSE](./LICENSE)。

感谢 **Google AI Edge 团队** 在端侧 GenAI 上的开源贡献。
