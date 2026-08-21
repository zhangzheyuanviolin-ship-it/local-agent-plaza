# Local Agent Plaza — MCP247 紧急媒体运行时恢复交接文档

**新上下文必须优先阅读本文件。**

最后权威检查点：**2026-08-21 17:38+08:00**

分支：`experimental`

`main` 必须保持不动。

本文件在当前紧急阶段的优先级高于 `docs/ACTIVE_HANDOFF_QWEN35_EXPERIMENTAL.md`。原 Qwen3.5 模型转换交接文档继续保留作为转换历史与技术依据；**4B/9B 模型转换工作全部暂停，直到 MCP247 紧急恢复 APK 完成、发布、重新下载验收，并由用户在手机上开始实测。**

---

## 1. 当前最高优先级与用户明确指令

MCP246 安装到手机后发生核心产品故障：

- 本地音乐生成无法使用；
- 至少一个本地图像生成模块无法使用；
- 用户要求立即停止模型转换线；
- 必须恢复音乐与全部本地图像生成核心功能；
- 同时必须完整保留 MCP194 之后已经完成的 Agent 推理速度、工具调用、联网搜索、Prompt 压缩、Conversation 生命周期以及诊断能力；
- 禁止整树回退到 MCP194、MCP201 或其他旧版本；
- 新 APK 必须可以从 MCP246 原地覆盖升级；
- 新版本目标为 `1.0.14-mcp.247` / `versionCode=347` / `com.localagent.plaza.mcp`；
- Qwen3.5 模型列表只接入已经完成全链路验证的 2B Q8 32K 模型，并移除旧的不可用 Qwen3.5 2B 条目；
- 其他产品模块原则上保持现有行为不变；
- 最终只接受经过严格 CI 审计、GitHub Release 发布、重新下载后再次 SHA/签名/包名/版本核验通过的 APK。

这次恢复必须采取“分层恢复”方案：**产品/Agent 行为保留后期黄金成果，只恢复媒体所依赖的 MCP237 / Box 0.4.9 黄金 LiteRT native packaging。**

---

## 2. 灾难性故障已经定位到 MCP237 → MCP238 的构建断点

### 2.1 最后一个媒体 native 正确 APK：MCP237

从保存的 MCP206、MCP235、MCP237 APK 中提取 ARM64 native libraries 后，三个关键 LiteRT 文件完全一致：

- `libLiteRt.so`
  - SHA256: `da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8`
- `libLiteRtClGlAccelerator.so`
  - SHA256: `4b19f18f4ba9b1bde6060def4388b74d07f939db798c8c77c4f4e5125aeabcb1`
- `liblitert_jni.so`
  - SHA256: `a98ea95cbb5ac4581bf483fa90df66454e15b965f4fa94a1b169a60319f4dc9a`

这是 Box 0.4.9 路线曾经在手机上实际验证过的黄金 native set。

### 2.2 第一个媒体 native 确定损坏 APK：MCP238

MCP238 APK 中：

- `libLiteRt.so`
  - 变成 `366e3e040b00692158f9f8f9105870672c93348a3d8e9024120b40045a074b0b`
- `libLiteRtClGlAccelerator.so`
  - 变成 `d22d9490c43a9428a6047564560dae83ce32a616658baa324b43843bfb066e89`
- `liblitert_jni.so`
  - 仍为 `a98ea95cbb5ac4581bf483fa90df66454e15b965f4fa94a1b169a60319f4dc9a`

该组合产生确定性 ABI mismatch：

- MCP237 `libLiteRt.so` 导出 `LiteRtCreateModelFromFd`；
- MCP238 的 `libLiteRt.so` 不再导出该 symbol；
- `liblitert_jni.so` 仍然需要该 symbol。

当前 MCP246 手机故障日志再次出现同一错误：

```text
dlopen failed: cannot locate symbol "LiteRtCreateModelFromFd"
referenced by liblitert_jni.so
```

FLUX 同一 APK 中也在 LiteRT Environment 层失败。

### 2.3 根因：MCP238 开始的 Qwen3.5 专用 APK workflow 漏掉媒体 native post-build pin/audit

正常产品构建链在 `assembleRelease` 后会执行：

```text
patch_box_music_apk.py
→ zipalign
→ re-sign
→ audit_box_music_apk.py
```

MCP238 的 Qwen3.5 专用 APK workflow 绕开了这条后处理链。因此 Gradle 默认打包的 LiteRT 2.1.6 core 进入最终 APK，Box / 图像所需的已实机验证 native set 没有重新 pin 回去。

这解释了为什么 MCP237 媒体模块正常，而 MCP238 之后的专用 Qwen 构建线把音乐和图像共同依赖的底层运行环境破坏了。

---

## 3. 禁止整树回退：必须保留的 Agent / 产品黄金能力

### 3.1 MCP210 低延迟 Agent 架构必须完整保留

MCP202～210 专门解决过本地 Agent 工具调用后的异常长等待：

- 复用同一 Conversation 时，工具结果回填后 TTFT 曾达到约 69～82 秒；
- 跨用户轮次还曾出现约 91 秒等待；
- MCP203～205 逐步形成 `Engine` 常驻、top-level 与 tool continuation 使用 fresh `Conversation` 的架构；
- MCP206 去掉 continuation 中重复的 session history；
- MCP207 定位 hidden thinking 是剩余延迟的重要来源；
- MCP208～210 对 COMPAT 路线实施 hard thinking off，并压缩 Prompt；
- 最终 top-level 与 continuation TTFT 收敛到约 13 秒数量级，达到用户实际可接受状态。

必须保留的关键不变量包括：

```text
persistent Engine
fresh top-level Conversation
fresh tool-continuation Conversation
enable_thinking=false
thinking_token_budget=0
compact COMPAT prompt
bounded session/tool history
```

### 3.2 MCP218 / MCP223 产品能力必须保留

继续保留：

- 用户明确要求联网时强制 live search；
- 多种模型工具调用格式兼容 parser；
- Qwen/Gemma/DeepSeek/GLM 等兼容调用格式的统一处理；
- 长多行 workspace write 恢复；
- MCP223 产品稳定线的综合行为。

`golden/mcp-223-product-stable` 仍是后期产品行为的重要黄金参考。

### 3.3 MCP224～235 的诊断能力按现状保留

包括但不限于：

- 模型生命周期诊断；
- 手动 Native 选择；
- 工具循环 / 重复调用诊断；
- 后续性能和模型运行信息记录。

紧急媒体恢复不得顺手删除这些已经存在的后期功能。

---

## 4. 明确排除的历史路线

以下方案不得进入 MCP247：

1. MCP212 idle/warm prefill：手机上曾造成可重复闪退。
2. MCP242 custom LiteRT-LM AAR/JNI/native runtime：曾造成随机进程级崩溃。
3. MCP240 simplified ChatML / recurrent reset 等实验性 Qwen 行为补丁。
4. MCP241 logits/repetition 专项补丁。
5. MCP246 Qwen continuation Engine reset：实机回归为重复工具调用，已经否决。
6. 任何整树回退到 MCP194/MCP201 的方案。
7. 为了让旧测试变绿而修改与当前紧急恢复无关的产品代码。

---

## 5. MCP247 唯一允许的产品变化范围

紧急构建以 MCP246 故障诊断完成后的产品树为基线：

```text
SOURCE_BASE_COMMIT=1aa2f87a22a50d9133a1776556201672c1707c77
```

产品代码采用窄范围 Qwen3.5 2B 32K 注册补丁。CI 已验证实际 workspace delta 只包含以下五个产品文件：

```text
Android/src/app/src/main/assets/model_allowlists/1_0_14.json
Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt
Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt
Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt
model_allowlists/1_0_14.json
```

除了这五个明确允许的产品变化之外，紧急构建不得修改 Android 产品树。

媒体修复发生在 APK post-build packaging 层：把 MCP237 / Box 0.4.9 黄金 native set 注入已经编译完成的 APK，然后重新 zipalign、签名和审计。

---

## 6. 唯一接入的 Qwen3.5 2B 32K 模型

canonical artifact：

```text
Release tag:
qwen35-2b-q8-32768-mcp238lineage-v3

File:
Qwen3.5-2B-LiteRT-LM-Q8-32768.litertlm

Size:
4,780,966,112 bytes

SHA256:
364f975167ba9bb083d9c01f0d600e9b1bb2955962d320c30cf4375b1fe42cb1

Context/cache:
32768

Android backend:
CPU
```

该 artifact 之前已经通过 Executor metadata、32K cache/signatures、LiteRT-LM 0.15 CPU runtime、官方模板 round-trip、multipart Release 重组 SHA 等完整验证。

MCP247 CI 每次构建前仍要再次：

1. 查询 Release 的 10 个分片；
2. 确认分片数量和每片大小；
3. 全部分片重新下载；
4. 拼接完整 `.litertlm`；
5. 检查 `LITERTLM` magic；
6. 检查完整文件字节数；
7. 重新计算 SHA256；
8. 只有完全匹配以后才允许继续 APK 构建。

旧的不可用 Qwen3.5 2B Q4/Q8 社区条目从 allowlist 移除。

---

## 7. MCP247 目标 APK 身份

```text
versionName=1.0.14-mcp.247
versionCode=347
package=com.localagent.plaza.mcp
```

MCP246 为 `versionCode=346`，因此 MCP247 必须满足原地覆盖升级条件。

预定永久 Release 身份：

```text
Tag:
mcp247-emergency-media-runtime-restore-v1

APK:
local-agent-plaza-1.0.14-mcp.247.apk
```

只有工作流达到最终 `FULLY_VERIFIED_PASS` 并且 Release 重新下载验证完成后，才允许把下面地址当成正式交付链接：

```text
https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/tag/mcp247-emergency-media-runtime-restore-v1

https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp247-emergency-media-runtime-restore-v1/local-agent-plaza-1.0.14-mcp.247.apk
```

当前检查点尚未完成 Release 发布，因此现在不得宣称以上 APK 已经存在或已经验收通过。

---

## 8. 当前紧急构建文件

Workflow：

```text
.github/workflows/mcp247_emergency_media_runtime_restore.yaml
```

核心脚本：

```text
.github/scripts/run_mcp247_emergency_build.sh
.github/scripts/run_mcp247_emergency_build_v2.sh
.github/scripts/run_mcp247_emergency_build_v3.sh
.github/scripts/patch_mcp247_qwen35_verified_2b_32k.py
```

运行标记：

```text
docs/mcp247_emergency_run.json
```

最终成功后才应该出现：

```text
docs/mcp247_emergency_media_restore_result.json
```

在本交接文档生成时，最终 result JSON 仍不存在，说明还没有任何一次工作流达到最终发布验收完成状态。

---

## 9. CI 单元测试的正确解释：必须做 baseline A/B，而不能要求历史旧测试全部变绿

早期 MCP247 紧急工作流曾错误地把整个 `testDebugUnitTest` 作为“必须全部 0 failure”的门槛。

后续检查证明，受保护的 `SOURCE_BASE_COMMIT` 本身已有旧测试失败。因此正确的紧急恢复回归门槛是：

> 在完全相同 CI 测试依赖和环境下，先跑 untouched protected baseline，再跑 MCP247 窄补丁 workspace；MCP247 不得引入任何新的失败。

Run 8 的实际结果：

```text
baseline: 117 tests, 8 failed
patched:  117 tests, 7 failed

MCP247_UNIT_REGRESSION_NO_NEW_FAILURES_PASS
baseline_failures=8
patched_failures=7
```

受保护 baseline 的 8 个既有失败：

```text
AgentCompatRuntimeCoordinatorTest#thirdConsecutiveIdenticalToolCallIsBlockedBeforeExecution
AgentToolingTest#compatToolResultPromptFitsEightKContextAndKeepsXlsxFacts
CompatToolCallWireAdapterTest#normalizesOfficialGemmaAndFunctionGemmaArguments
AiKeyboardCommitVerifierTest#needsClipboardFallback_whenCommittedTextIsShortPrefix
BoxSoundGenCoreTest#requestedSamples_matchBoxRoundingAndClamp
MusicGenerationModelsTest#toPcm16_roundsLikeBoxWavWriter
VisualCreationDomainTest#visualProcessPromptsMatchExpectedTaskIntent
ModelAllowlistSourceTest#bundledPlazaAllowlistContainsExpandedModelCatalog
```

MCP247 patched workspace 剩余 7 个失败，恰好是前七个；`ModelAllowlistSourceTest` 在新模型列表补丁后已经消失。

因此当前紧急补丁没有引入新的单测回归，并且减少了一个 baseline 既有失败。

这些历史旧测试不得成为修改音乐、图像、Agent 等产品代码的理由。后续可单独安排测试债务清理。

---

## 10. 最新 Run 8：已经通过的实质性硬门槛

最新已完成运行：

```text
Run ID: 32466388850
Run number: 8
Job ID: 96723765609
Semantic head SHA: 0ddf7f72120e246bdbe9f3a9e9fb3cb18ca3c765
Run URL:
https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32466388850
```

工作流自动更新 `docs/mcp247_emergency_run.json` 后，`experimental` 分支出现 status-only bot commit；该自动标记提交不改变本次 APK 产品语义。

Run 8 已经通过以下关键门槛：

### 10.1 受保护产品基线

```text
MCP247_PROTECTED_PRODUCT_BASELINE_PASS
```

确认：

- 官方 `litertlm=0.15.0` 路线仍在；
- 没有 MCP242 custom AAR；
- MCP210 persistent Engine / fresh Conversation 关键代码存在；
- hard thinking off 仍存在；
- Search Required / compat parser / coordinator 仍存在；
- Box049 runtime preparation / patch / audit 脚本仍存在；
- Bonsai / FLUX / Z-Image 业务入口仍存在。

### 10.2 Qwen3.5 Release 完整重组

```text
QWEN35_EXACT_TEN_PART_INVENTORY_PASS
QWEN35_FULL_RELEASE_RECONSTRUCTION_SHA_PASS
```

完整文件再次核对：

```text
size=4780966112
sha256=364f975167ba9bb083d9c01f0d600e9b1bb2955962d320c30cf4375b1fe42cb1
```

### 10.3 窄范围产品补丁与 Agent 不变量

```text
MCP247_NARROW_PATCH_AND_AGENT_INVARIANTS_PASS
```

### 10.4 baseline A/B 回归比较

```text
MCP247_UNIT_REGRESSION_NO_NEW_FAILURES_PASS
baseline_failures=8
patched_failures=7
```

### 10.5 Release APK 编译

```text
:app:assembleRelease
BUILD SUCCESSFUL
```

### 10.6 MCP237 / Box 0.4.9 native set 真正注入最终 APK

打包前错误值：

```text
libLiteRt.so
366e3e040b00692158f9f8f9105870672c93348a3d8e9024120b40045a074b0b

libLiteRtClGlAccelerator.so
d22d9490c43a9428a6047564560dae83ce32a616658baa324b43843bfb066e89
```

post-build pin 后正确值：

```text
libLiteRt.so
da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8

libLiteRtClGlAccelerator.so
4b19f18f4ba9b1bde6060def4388b74d07f939db798c8c77c4f4e5125aeabcb1

liblitert_jni.so
a98ea95cbb5ac4581bf483fa90df66454e15b965f4fa94a1b169a60319f4dc9a
```

这项结果再次直接验证了 MCP238 后专用构建线的 packaging 根因：正常 Gradle APK 确实仍会打入 `366e...`，紧急恢复脚本必须在最终 APK 层重新 pin 黄金 native set。

### 10.7 Canonical Box native/ABI 审计

```text
Box music runtime audit passed:
exact 0.4.9 hashes;
JNI LiteRT ABI 166/166 satisfied;
LiteRT-LM direct-core isolation preserved;
golden engine/bridge/commit/cache fingerprints present in DEX.

MCP247_CANONICAL_BOX_NATIVE_AUDIT_PASS
```

### 10.8 第二套独立 ZIP / DEX / model / native 审计

```text
MCP247_INDEPENDENT_ZIP_DEX_MODEL_NATIVE_HASH_AUDIT_PASS
```

### 10.9 关键 LiteRT symbols 与 LiteRT-LM 隔离

Run 8 在最终 APK 中实际检查到：

```text
LiteRtCreateModelFromFd@@VERS_1.0
LiteRtGetBlockWiseQuantization@@VERS_1.0
```

同时：

```text
liblitertlm_jni.so
```

没有直接 `DT_NEEDED -> libLiteRt.so`，因此 Agent LiteRT-LM 路线与被 pin 的媒体 LiteRT core 继续保持隔离。

Marker：

```text
MCP247_CRITICAL_SYMBOL_AND_LITERTLM_ISOLATION_PASS
```

### 10.10 APK 签名和升级身份本身已经正确

Run 8 `apksigner`：

```text
Verifies
Verified using v3 scheme: true
Number of signers: 1
```

当前工具输出的 certificate SHA256：

```text
38a9a4f15ed53f47abee1a0343b2fe3d825687acb148ac8c522fa1d29f3e292d
```

`aapt dump badging`：

```text
package: name='com.localagent.plaza.mcp'
versionCode='347'
versionName='1.0.14-mcp.247'
```

所以 Run 8 已经证明 APK 的包名和版本满足从 MCP246 `versionCode=346` 向上覆盖升级的基础条件。

---

## 11. Run 8 当前唯一阻塞：certificate digest 日志解析器兼容 Build Tools 37

Run 8 最终失败发生在所有上述实质性构建/native/ABI/package 审计通过以后。

当前脚本仍用旧输出格式提取证书 SHA：

```text
Signer #1 certificate SHA-256 digest: ...
```

Android Build Tools 37 当前实际输出格式为：

```text
V3.0 Signer: certificate SHA-256 digest: 38a9a4f15ed53f47abee1a0343b2fe3d825687acb148ac8c522fa1d29f3e292d
```

因此旧 `sed` 表达式没有匹配到文本：

```text
CERT_SHA=
test -n ''
```

随后工作流 exit 1。

**该失败属于 CI acceptance harness 的文本解析兼容问题。Run 8 没有出现新的 APK 编译失败、native ABI 失败、Agent invariant 失败、Qwen artifact 失败、签名验证失败或包名/版本失败。**

下一步只允许做一个极窄修复：让 certificate digest parser 同时接受 Build Tools 37 的 `V3.0 Signer:` 输出，并继续 fail-closed。

推荐逻辑：

```text
优先解析：V3.0 Signer: certificate SHA-256 digest:
兼容解析：Signer #1 certificate SHA-256 digest:
结果必须是 64 位十六进制 SHA256
结果为空或格式错误时立即失败
```

修复该 parser 后触发新一轮完整工作流，不允许跳过前面的任何 hard gate。

---

## 12. 当前没有最终 APK Release

在本检查点：

```text
docs/mcp247_emergency_media_restore_result.json
```

仍不存在。

Run 8 在证书 SHA 解析处停止，所以后面的步骤没有执行：

- `gh release create/upload`；
- 从 Release 重新下载最终 APK；
- Release APK SHA256 精确匹配；
- Release APK 签名再次校验；
- Release APK 包名/versionCode 再次校验；
- 写入 machine-readable `FULLY_VERIFIED_PASS` result JSON。

因此任何新上下文都不得根据“Run 8 APK 已编译完成”就把它当成交付成功。

---

## 13. 最终交付前必须全部满足的硬验收条件

只有以下门槛全部通过，才能向用户发送 MCP247 APK 下载链接：

1. protected baseline 与 patched workspace A/B 单测比较：**无新增失败**。
2. Qwen 2B 32K 10 个分片重新下载、完整重组、magic/size/SHA 全匹配。
3. 产品 workspace delta 仍严格限制在授权的五个文件。
4. MCP210 Engine/Conversation/hard-thinking-off 不变量通过。
5. MCP218/223 Search Required、compat parser、workspace tool 行为文件仍在。
6. 禁止 MCP212 warm prefill。
7. 禁止 MCP242 custom AAR/JNI。
8. 禁止 MCP246 Qwen Engine reset。
9. `assembleRelease` 成功。
10. post-build 注入黄金 native set。
11. 最终 APK 三个关键 native SHA 精确匹配 MCP237 / Box 0.4.9 黄金值。
12. `audit_box_music_apk.py` 通过。
13. LiteRT JNI ABI `166/166` 满足。
14. `LiteRtCreateModelFromFd` 存在。
15. `LiteRtGetBlockWiseQuantization` 存在。
16. `liblitertlm_jni.so` 保持与被 pin LiteRT core 的 direct-core isolation。
17. 独立 ZIP/DEX/model/native hash audit 通过。
18. APK 签名验证通过。
19. certificate SHA256 能被严格解析并记录。
20. 包名 `com.localagent.plaza.mcp`。
21. `versionCode=347`。
22. `versionName=1.0.14-mcp.247`。
23. 创建/更新永久 GitHub Release。
24. 从 GitHub Release 重新下载用户将实际收到的 APK。
25. Release 下载 APK 的字节数和 SHA256 与本地产物完全相同。
26. Release 下载 APK 再次 `apksigner verify`。
27. Release 下载 APK 再次核对包名和 `versionCode=347`。
28. 写入 `docs/mcp247_emergency_media_restore_result.json`。
29. result JSON 必须明确：`status=FULLY_VERIFIED_PASS`。
30. 只有完成以上全部步骤以后，才向用户发送 GitHub Release 直接 APK 链接。

手机上的最终功能验收仍由用户安装后进行，重点至少包括：

- SoundGen；
- SoundGen HD；
- SoundGen HD Long；
- Bonsai；
- FLUX；
- Z-Image；
- 12B Agent 普通对话；
- 12B Agent 联网搜索 → 工具结果回填 → 最终回答延迟；
- Qwen3.5 2B 32K 模型下载、加载、普通聊天及 Agent 工具调用。

CI 可以把二进制、依赖、ABI、符号、模型、签名和代码不变量做到 fail-closed；真实手机 GPU/CPU/文件系统/Android linker 的最终运行体验仍必须由真机确认。

---

## 14. 新上下文接手时的唯一执行顺序

新上下文收到“继续”“继续修”“拿到 APK 链接”为止等指令时：

1. 先读取本文件。
2. 刷新 `experimental` 最新 HEAD。
3. 读取 `docs/mcp247_emergency_run.json`。
4. 检查 `docs/mcp247_emergency_media_restore_result.json` 是否已经出现。
5. 如果 result 已是 `FULLY_VERIFIED_PASS`，独立核验 Release 与 APK 后再交付。
6. 如果最新 run 仍是 Run 8 这一阻塞，**只修 certificate SHA parser**。
7. 修复限定在 CI/workflow/script 层；不要修改 Android 产品源码。
8. 触发新一轮完整 `mcp247_emergency_media_runtime_restore` 工作流。
9. 不得跳过 baseline A/B、Qwen reconstruction、native SHA、ABI、symbols、package、signature、Release re-download 等任一 gate。
10. 若出现新失败，读取完整日志，按实际新 blocker 处理；不得回到整树 rollback 或新的 native 实验路线。
11. 直到出现 machine-readable `FULLY_VERIFIED_PASS`，并且 Release APK 重新下载验收通过，任务才算完成。
12. 最终给用户的是 GitHub Release 直接 APK 链接，不发送大型 APK 的 sandbox 链接。

---

## 15. 当前最简状态摘要

```text
紧急目标：MCP247 恢复音乐 + 图像核心媒体运行时，同时保留后期 Agent 全部黄金优化

最后媒体 native 正确 APK：MCP237
第一媒体 native 确定损坏 APK：MCP238
根因：Qwen 专用 APK workflow 漏掉 Box 0.4.9 post-build native pin/audit

Agent 行为保留：MCP210 + MCP218 + MCP223 + 后续诊断能力
排除：MCP212 warm prefill / MCP242 custom runtime / MCP246 Engine reset

Qwen：只接已验证 Qwen3.5-2B Q8 32768

Run 8：APK 已成功编译；黄金 native 已注入；ABI 166/166；关键 symbols；LiteRT-LM isolation；包名/347/247；签名验证均已通过

Run 8 唯一失败：Build Tools 37 证书 SHA 输出格式变化，旧 sed parser 没取到 CERT_SHA

当前无最终 Release、无 FULLY_VERIFIED_PASS result JSON

下一步：只修 certificate digest parser → 完整重跑 → Release → 重新下载 APK → SHA/签名/包名/versionCode 二次验证 → result JSON FULLY_VERIFIED_PASS → 向用户交付 GitHub APK 直链
```
