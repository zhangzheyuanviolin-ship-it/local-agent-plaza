# Local Agent Plaza — MCP247 黄金基线、8 模型扩展与 Gemma 4 26B 调查交接

**新上下文必须优先阅读本文件，然后再阅读历史 MCP247 / Qwen3.5 交接文件。**

最后权威检查点：**2026-08-21 23:38+08:00**

目标仓库：`zhangzheyuanviolin-ship-it/local-agent-plaza`

工作分支：`experimental`

`main` 必须保持不动。

本轮只完成调查、技术决策固化与本交接文件写入；**没有修改 Android 产品代码、模型 allowlist、运行时、构建脚本或媒体 native。**

---

## 0. 新上下文的执行摘要

当前产品黄金基线是 **MCP247**。用户已经在 Redmi K70 Pro 上实际验证：

- 本地图像生成模块恢复正常；
- SoundGen / 音乐生成恢复正常；
- 老 Agent 模型此前“工具调用后长时间卡住”的主要回归已消失；
- 当前明显异常集中在 Qwen3.5 2B Q8 32K 的工具调用 continuation；
- LocoOperator-4B 已由用户长期实际使用并确认可以稳定多轮调用工具，因此它应作为后续 Agent 模型兼容性的现实黄金对照之一。

下一版本的核心方向已经由用户明确：

1. 保持 MCP247 全部产品与媒体黄金基线；
2. 扩展模型下载列表，计划新增 **8 个不重复的模型**；
3. 其中包含实验性的 **Gemma 4 26B-A4B**；
4. 26B 不再继续无限做纸面猜测，下一版直接以内置模型条目方式提供下载，用户实机下载、加载、对话；若失败，使用现有模型诊断日志记录真实错误，再做定点修复；
5. 本轮暂不改代码，下一上下文再开始 APK 迭代。

建议下一上下文直接以本文件作为唯一最新入口。

---

# 第一部分：MCP247 当前黄金基线

## 1.1 APK 身份

当前已交付并经过 CI 与用户实机验证的版本：

```text
versionName = 1.0.14-mcp.247
versionCode = 347
package = com.localagent.plaza.mcp
```

GitHub Release：

```text
Tag:
mcp247-emergency-media-runtime-restore-v1

Release page:
https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/tag/mcp247-emergency-media-runtime-restore-v1

APK:
https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp247-emergency-media-runtime-restore-v1/local-agent-plaza-1.0.14-mcp.247.apk
```

APK SHA256：

```text
6558d14817e23cd239885caaecdb46803d8d1e16930ca36f154b116222184a65
```

CI：

```text
FULLY_VERIFIED_PASS
workflow run = 32471196974
```

## 1.2 MCP247 媒体 native 黄金集合

MCP247 已恢复 MCP237 / Box 0.4.9 实机验证过的 LiteRT native packaging：

```text
libLiteRt.so
SHA256 da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8

libLiteRtClGlAccelerator.so
SHA256 4b19f18f4ba9b1bde6060def4388b74d07f939db798c8c77c4f4e5125aeabcb1

liblitert_jni.so
SHA256 a98ea95cbb5ac4581bf483fa90df66454e15b965f4fa94a1b169a60319f4dc9a
```

这组三个 native 不允许在下一版为了模型实验被顺手替换。

## 1.3 MCP247 Agent 黄金不变量

MCP202～210 已经经过多轮手机实测，解决过工具结果返回后 TTFT 极长的问题。下一版继续保留：

```text
persistent Engine
fresh top-level Conversation
fresh tool-continuation Conversation
COMPAT thinking disabled
thinking_token_budget = 0
compact COMPAT prompt
bounded session/tool history
```

禁止回归路线：

```text
MCP212 idle / warm prefill
MCP242 custom LiteRT-LM AAR / JNI / native runtime
MCP246 Qwen continuation Engine reset
任何整树回退到 MCP194 / MCP201
为单一模型修改全局媒体 native
```

## 1.4 当前 Android LiteRT 版本

`experimental` 当前：

```text
LiteRT-LM = 0.15.0
LiteRT = 2.1.6
```

下一版模型池扩展首先只改模型注册/配置，除非实机诊断明确证明运行时必须变化，否则不要动这两个全局版本。

---

# 第二部分：Qwen3.5 2B 当前问题与后续定位边界

## 2.1 当前 canonical Qwen3.5 2B artifact

```text
Release tag:
qwen35-2b-q8-32768-mcp238lineage-v3

File:
Qwen3.5-2B-LiteRT-LM-Q8-32768.litertlm

Size:
4,780,966,112 bytes

SHA256:
364f975167ba9bb083d9c01f0d600e9b1bb2955962d320c30cf4375b1fe42cb1

Context:
32768

Backend:
CPU
```

## 2.2 MCP247 最新实机诊断

文件：

```text
docs/model_diagnostic_mcp-247_2026-08-21_120203.txt
```

关键数据：

```text
tool_mode = COMPAT
configured_context = 32768
max_output = 4096
original_user_input = 27 chars
runtime_input = 2563 chars
compat_added = 2536 chars

initial TTFT = 9.337s
initial generation total = 12.462s

tool = tool.run_js
tool execution = 4402.79ms

continuation input = 4083 chars
continuation TTFT = 49.100s
continuation generation total = 57.639s
```

最终正文几乎逐字复读内部 continuation 控制文本，并输出了字面 `<|im_end|>`。

当前最强结论：

- **直接症状高度符合 COMPAT continuation prompt / control-text echo。**
- 当前诊断中模型没有重复调用同一工具，`repeated_tool_call_count=0`；问题发生在工具成功后生成最终答复的第二次 LLM pass。
- 49.1 秒 continuation TTFT 本身也说明，即使 prompt echo 被修掉，该模型当前路径仍然可能不适合作为默认 Agent。

## 2.3 Qwen3.5 历史技术结论必须保留

历史文档：

```text
docs/checkpoints/QWEN35_MCP243_ROOT_CAUSE_AND_MCP244_PLAN_2026-08-20.md
```

已经确认：

- MCP238 原始模型采用完整官方 Qwen3.5 tool-aware Jinja；
- MCP240 曾替换成极简 generic ChatML，导致 assistant tool_calls、tool_response、多步工具历史等语义丢失；
- 原 MCP238 stop IDs 为 `[248044]`；
- MCP240 为 `[248044, 248046]`；
- Qwen tokenizer 中：
  - `248044 = <|endoftext|>`
  - `248045 = <|im_start|>`
  - `248046 = <|im_end|>`
  - tokenizer EOS 是 `<|im_end|>`；
- 当前 MCP247 最终诊断仍吐出字面 `<|im_end|>`，因此当前 32K artifact 的 stop metadata / runtime stop consumption 仍值得作为下一次 Qwen 专项取证的高优先级检查项，但目前没有直接证据证明它就是根因。

另一个非常重要的上游事实：当前 `litert-community/Qwen3.5-2B` / 4B 官方社区 LiteRT-LM 包为了满足 LiteRT-LM incremental conversation 的“后续 render 必须是前一 render 的字符串扩展”要求，主动换成 simplified ChatML；模型卡明确说明 tool-calling / vision 模板段被省略。官方 stock Qwen3.5 模板会清除历史空 think block，这会破坏 LiteRT-LM incremental conversation。

因此，Qwen3.5 的困难已经不能只按“某一条 prompt 写错”理解，它同时涉及：

```text
Qwen3.5 chat template semantics
LiteRT-LM incremental rendering constraints
Plaza COMPAT fresh-Conversation reconstruction
EOS / stop metadata
thinking behavior
```

下一版本不应再次开启 Qwen3.5 4B / 9B 的长时间自转换循环。

---

# 第三部分：模型下载列表去重调查

## 3.1 去重规则

用户明确规则：

- 名称相同、但 repo / 文件 / 量化 / 转换方式不同，可以作为不同版本保留；
- repo、model file、版本实际上完全相同，则禁止重复添加；
- 去重必须比较至少：`modelId + modelFile + commit/variant + quantization/conversion`。

## 3.2 已确认重复：LocoOperator

当前 Plaza `experimental` 已有：

```text
name:
LocoOperator-4B LiteRTLM

modelId:
4ntoine/LocoOperator-4B-LiteRTLM

modelFile:
model.litertlm

size:
4,059,223,584 bytes

commitHash:
6862d30e40d1c80d7b40207d91d66dfc2bec9b6a
```

这与上一轮候选完全相同，因此下一版 **禁止再次添加**。

更重要的是，用户实机已经长期使用它，并确认：

- 可以正常对话；
- 可以连续、多轮调用工具；
- 当前真实使用中工具链稳定。

LocoOperator 应转为后续新模型 Agent 资格测试的现实对照模型。

## 3.3 已确认重复：JOSIE

当前 Plaza 已经同时存在两个版本：

### WI4

```text
name:
JOSIE-1.1-4B-Instruct WI4

modelId:
Werve/JOSIE-1.1-4B-Instruct-litert-lm

modelFile:
JOSIE-1.1-4B-Instruct_DYNAMIC WI4 AFP32.litertlm

size:
2,244,326,320 bytes

commit:
3028d1f2fe6bb50547e3eb827017336d405a9864
```

### WI8

```text
name:
JOSIE-1.1-4B-Instruct WI8 experimental

modelId:
Werve/JOSIE-1.1-4B-Instruct-litert-lm

modelFile:
JOSIE-1.1-4B-Instruct_dynamic_wi8_afp32.litertlm

size:
4,449,940,400 bytes

commit:
3028d1f2fe6bb50547e3eb827017336d405a9864
```

因此上一轮候选 JOSIE 也不应重复添加。

## 3.4 当前未发现重复的候选

本轮对当前 Plaza allowlist 核对后，没有发现以下候选已经存在：

```text
Ministral-3-3B-Instruct-2512
Phi-4-mini-instruct
Llama-3.2-3B-Instruct LiteRT
Falcon-H1-3B-Instruct
Jan-nano
FastContext-1.0-4B-SFT
Laguna XS.2 phone k4
Gemma-4-26B-A4B-it
```

这 8 个形成下一版工作模型池。

---

# 第四部分：下一版计划新增的 8 个模型

**实现前还要对每一个 HF repo 做最后一次文件名、size、commit hash 冻结。禁止只按模型名字写 allowlist。**

## 4.1 Ministral 3B

候选 repo：

```text
litert-community/Ministral-3-3B-Instruct-2512
```

当前已确认主文件：

```text
Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm
```

已知特征：

- 约 2.34 GB；
- int4 block32 + OCTAV；
- INT8 embedding；
- KV/context 4096；
- 非 thinking、直接回答；
- LiteRT 包转换时使用较简单的 Mistral `[INST] ... [/INST]` template；
- EOS `</s>`；
- 当前模型卡明确支持近期 AI Edge Gallery Android 导入运行；
- 上游 Mistral 模型本身有 function-calling 能力，但该 LiteRT 转换没有完整保留复杂上游 Jinja，所以首先按 COMPAT control model 测试。

定位：**独立于 Qwen 的低复杂度 Agent 对照，优先级高。**

## 4.2 Phi-4-mini-instruct

repo：

```text
litert-community/Phi-4-mini-instruct
```

当前主文件：

```text
Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm
```

已知：

- 约 3.91 GB；
- Q8；
- KV/context 4096；
- Microsoft upstream 明确做过 function-calling 训练；
- upstream tool format 使用专门 tool wrapper；
- 支持中文在内的多语言；
- Android 社区 LiteRT 包已有 S24 Ultra CPU/GPU benchmark。

当前 HF 文件页 SHA256：

```text
7764d4deb53800578307be33039476b38a6c370fff71bedb3c0552563e23ab02
```

定位：**独立模型家族 + 显式 function-calling 训练，强 Agent 候选。**

## 4.3 Llama 3.2 3B Instruct LiteRT

已明确找到的 Instruct 转换 repo：

```text
mlboydaisuke/Llama-3.2-3B-Instruct-LiteRT
```

主文件：

```text
model.litertlm
```

约 2.1 GB，int4 block32，INT8 embedding，KV 4096。

另外当前 LiteRT Community GenAI collection 也列出了：

```text
litert-community/Llama-3.2-3B
```

下一上下文实现前必须比较两者文件格式、Android 说明、发布时间和具体 artifact，选一个明确的 Instruct/Android 可用版本加入，不要两个都加。

定位：**独立模型家族的通用控制组。**

## 4.4 Falcon-H1-3B-Instruct

repo：

```text
litert-community/Falcon-H1-3B-Instruct
```

主文件：

```text
Falcon-H1-3B-Instruct_int8.litertlm
```

已知：

- 约 3.15 GB；
- 要求 LiteRT-LM >= 0.15；
- hybrid attention + Mamba2；
- 支持 CPU / GPU correctness path；
- ChatML-style template。

定位：**与 Qwen / Mistral / Phi 都不同的 hybrid 架构兼容性测试。**

## 4.5 Jan-nano

repo：

```text
litert-community/Jan-nano
```

推荐主文件：

```text
model.litertlm
```

另有：

```text
model_block32.litertlm
```

已知：

- Qwen3-4B 派生；
- deep-research / MCP tool-use 取向；
- reasoning model，会输出 `<think>`；
- KV 4096；
- 模型卡明确给出 Android / AI Edge Gallery 导入用法；
- max_tokens 建议至少 2048，否则可能无法完成 reasoning + final。

定位：**思考 + 工具的 Qwen3 派生对照；不能套用纯 COMPAT thinking-off 的单一策略。**

## 4.6 FastContext 1.0 4B SFT

repo：

```text
litert-community/FastContext-1.0-4B-SFT
```

推荐质量版本：

```text
model.litertlm
```

另有较快的：

```text
model_block128.litertlm
```

已知：

- Qwen3-4B-Instruct 派生；
- Microsoft repository exploration subagent 方向；
- 专门训练 READ / GLOB / GREP 等工具调用；
- 可并行调用工具；
- KV 4096；
- `model.litertlm` block32 约 2.66 GB；
- block128 约 2.47 GB。

下一版默认优先质量版本 `model.litertlm`，除非最终文件核验说明发生变化。

定位：**专门工具训练模型，可验证 Plaza 在非 LocoOperator 路线上的多工具适配能力。**

## 4.7 Laguna XS.2 phone k4

首选 repo：

```text
poolside-laguna-hackathon/laguna-xs2-phone-k4-fold3-litert
```

已有调查显示 phone k4 LiteRT artifact 约 2.89 GB。

Laguna XS.2 base：

- 总参数约 33B MoE；
- 每 token 激活约 3B；
- 重点面向 agentic coding / long-horizon execution；
- 支持工具调用间 interleaved reasoning；
- thinking 可以按请求控制；
- 中文能力不是其强项，整体偏英文/编码。

定位：**独立于 Qwen 的 MoE + reasoning/tool 技术实验模型，不建议一开始当中文默认模型。**

## 4.8 Gemma 4 26B-A4B

repo：

```text
litert-community/gemma-4-26B-A4B-it-litert-lm
```

它是下一版最重要的“实机求证型”模型，详细见下一部分。

---

# 第五部分：Gemma 4 26B-A4B — Box 为什么可以跑的最新调查结论

## 5.1 最重要的新证据：Box v3.3.2 实际 APK 用的就是公开 web artifact

用户自己的逆向仓库：

```text
zhangzheyuanviolin-ship-it/box-local-music-android
```

其中保存了 Box v3.3.2 正式 APK 的完整 JADX 反编译：

```text
APK:
Box_v3.3.2_Main_Signed_Release.apk

APK SHA256:
b1d15fd046edd08ea2d7b6ba371a66e066a7a6a6b0159e383a5d143cfe400fcf

Decompile:
jadx_full_v2/
```

实际 APK 内的模型白名单：

```text
jadx_full_v2/resources/assets/model_allowlist.json
SHA(blob): 73271afae956d72a74b63d81f471f67731964913
size: 45,890 bytes
```

26B 的真实 entry：

```text
name:
Gemma-4-26B-A4B-it

modelId:
litert-community/gemma-4-26B-A4B-it-litert-lm

modelFile:
gemma-4-26B-A4B-it-web.litertlm

sizeInBytes:
15786524672

minDeviceMemoryInGb:
16

commitHash:
755026618afd72ebb6d970f784d42effa67398bc

defaultConfig:
topK = 64
topP = 0.95
temperature = 1.0
maxContextLength = 32000
maxTokens = 4000
accelerators = gpu

taskTypes:
llm_chat
llm_prompt_lab
llm_agent_chat

bestForTaskTypes:
llm_chat
llm_prompt_lab
```

Box 自己的文案甚至明确写着：

```text
Experimental — it may not load at all.
This is Google's web build, released ahead of official phone support.
Requires 16 GB+ RAM and a 15.8 GB download.
```

因此截至当前证据，**Box v3.3.2 没有使用一个隐藏的、另行改名的 26B 手机专用模型文件。它直接下载公开 HF 的 web artifact，并把它作为 GPU LLM 注册。**

这是本轮 26B 调查最重要的结论。

## 5.2 用户历史实机记录进一步证明该路径确实跑过

Box GitHub issue #136：

```text
https://github.com/jegly/Box/issues/136
```

标题：

```text
Gemma 4 26B (A4B) emits incompatible tool calls in Agent skills
```

该 issue 由用户 2026-08-10 提交，正文明确记录：

```text
The newly added Gemma 4 26B (A4B) model runs successfully and smoothly on my phone...
```

并进一步说明：

```text
The model itself is usable on this device and inference is smooth.
This does not appear to be a performance or memory problem;
it appears specific to tool-call formatting or chat-template integration.
```

设备：

```text
Redmi K70 Pro
RAM 24 GB
Storage 1 TB
```

因此“Box 内置下载的 26B 能在同一台手机运行”已经有独立历史书面证据。

## 5.3 为什么 Plaza 手工导入可能表现不同

当前 Plaza `ModelImportDialog.kt` 对本地导入模型走 generic ImportedModel metadata：

- 用户/默认值决定 compatible accelerators；
- 用户/默认值决定 maxContextLength；
- 用户/默认值决定 maxTokens；
- thinking/image/audio/speculative 等能力也按通用开关记录；
- 文件被复制到 `IMPORTS_DIR`，随后按 generic imported-model 路径注册。

而 Box 内置 allowlist 的 26B entry 是确定性的：

```text
exact model file = web.litertlm
accelerator = gpu
context = 32000
max tokens = 4000
min RAM = 16 GB
```

所以“内置下载成功”和“手工导入失败”完全可能出现在同一个模型文件上。两条路径的模型 metadata、默认 backend、能力声明以及后续 Model 构造过程并不相同。

目前还没有证据说明 Plaza 失败的唯一原因究竟是哪一项；下一版直接以内置 allowlist 复刻 Box 参数，是最干净的 A/B 实验。

## 5.4 当前 HF repo 又出现了一个新的 GPU artifact

截至 2026-08-21，公开 repo 已同时出现：

```text
gemma-4-26B-A4B-it-web.litertlm

gemma-4-26B-A4B-it-gpu.litertlm
```

新 GPU 文件约 15.8 GB，当前调查记录的 SHA256：

```text
94bbde2453dd9b67c61c16017af331e5841cbbd9edf83bd2f84bc73e2a7cbdb1
```

GPU artifact 是最近才加入的；当前 HF 模型卡仍主要把该 repo 描述为 web deployment，并没有充分的 Android 官方说明。

因此下一版第一次 26B 实验建议保持单变量：

### 第一优先：复刻 Box 已被用户实机证明过的路径

```text
modelFile = gemma-4-26B-A4B-it-web.litertlm
commit = 755026618afd72ebb6d970f784d42effa67398bc
accelerator = gpu
context = 32000
maxTokens = 4000
minDeviceMemory = 16 GB
```

### 第二优先：只有第一条在 Plaza 实机失败后，再单独测试当前新 GPU artifact

不要在同一个版本里同时新增两个 15.8 GB 26B 条目，否则实机失败后会增加变量，降低诊断价值。

## 5.5 关于“Box 是否做过 26B 专项模型优化”的当前结论

已经找到的 APK 级证据不支持“Box 使用了另一份私有模型转换文件”这一猜想。

仍可能存在差异的层面包括：

```text
Box 实际 release runtime / JNI / native packaging
Box 的 Model 构造与 accelerator 选择
内置 allowlist 与 imported-model path 的 metadata 差异
LiteRT-LM 在不同版本的 web/gpu graph loader 行为
GPU delegate / memory allocation policy
模型初始化时上下文和 cache 配置
```

由于下一版可以直接做 Box allowlist 参数复刻，继续在这一阶段纯静态推测的边际价值已经很低。用户已经决定采用“内置下载 → 实机模型诊断 → 定点修复”的路线。

---

# 第六部分：为什么公开 Box GitHub source 不能单独作为 26B 事实来源

本轮还发现一个重要的 source/release mismatch：

公开的 Box `v.3.3.1` Git tag / source 中：

- `Android/src/app/src/main/assets/model_allowlist.json` 没有 26B；
- `Android/src/gradle/libs.versions.toml` 仍能看到较旧的 LiteRT-LM version 信息。

但用户保存的 **Box v3.3.2 正式 APK 反编译资源**拥有 45KB 的真实 allowlist，并明确包含 26B。

因此后续涉及 Box release 的模型与 runtime 行为时，证据优先级应为：

```text
1. 用户保存的实际 release APK / JADX / apktool 产物
2. 用户实际手机测试与诊断日志
3. Box release notes / issue
4. 同版本公开源码
5. 更老 tag / README
```

如果公开源码和实际 APK 冲突，必须把 APK 产物作为该 release 行为的主要证据。

---

# 第七部分：下一版 8 模型实施策略

## 7.1 计划列表

下一版工作列表固定为以下 8 个候选：

```text
1. litert-community/Ministral-3-3B-Instruct-2512
2. litert-community/Phi-4-mini-instruct
3. Llama-3.2-3B-Instruct LiteRT（实现前在 mlboydaisuke 与当前 litert-community artifact 中二选一）
4. litert-community/Falcon-H1-3B-Instruct
5. litert-community/Jan-nano
6. litert-community/FastContext-1.0-4B-SFT
7. poolside-laguna-hackathon/laguna-xs2-phone-k4-fold3-litert
8. litert-community/gemma-4-26B-A4B-it-litert-lm
```

明确排除重复：

```text
4ntoine/LocoOperator-4B-LiteRTLM
Werve/JOSIE-1.1-4B-Instruct-litert-lm WI4
Werve/JOSIE-1.1-4B-Instruct-litert-lm WI8
```

## 7.2 模型角色分层

### 直接回答 / 非 thinking control

```text
Ministral 3B
Phi-4-mini-instruct
Llama 3.2 3B Instruct
Falcon-H1 3B Instruct
```

用于验证 Plaza COMPAT / native tool 适配在不同模型家族是否稳定。

### 工具训练 / reasoning experiment

```text
Jan-nano
FastContext
Laguna XS.2
```

这些不能简单假设与普通 non-thinking 模型使用同一种 prompt/history/thinking policy。

### 大型旗舰实机实验

```text
Gemma 4 26B-A4B
```

第一目标是：下载成功、初始化成功、普通聊天成功。

工具调用属于第二阶段资格测试。

## 7.3 模型资格测试顺序

每个新增模型建议按以下层级测试，失败后停止进入更高层：

```text
Stage 0: download integrity
Stage 1: load / initialize
Stage 2: one-turn plain chat
Stage 3: multi-turn plain chat
Stage 4: one simple tool call
Stage 5: tool result continuation
Stage 6: repeated / multi-tool session
Stage 7: long context / workspace / search
```

26B 首轮至少完成 Stage 0～3；如果 Stage 1 就失败，直接交模型诊断，不先改 Agent prompt。

---

# 第八部分：下一版代码修改边界

下一上下文开始迭代时，第一版改动应尽可能窄：

1. 冻结 8 个 model repo / exact file / size / commit hash；
2. 只向当前 model allowlist 增加新的模型条目；
3. 根据模型能力设置合理的 taskTypes / accelerator / context / maxTokens；
4. 保持 MCP247 媒体 native 不动；
5. 保持 Agent runtime 与 continuation 生命周期不动；
6. 构建新的 APK；
7. 用户手机实测后，再按具体模型诊断做第二阶段 adapter 修复。

第一版禁止顺手引入：

```text
全局 Engine lifecycle 重构
LiteRT-LM 版本升级/降级
LiteRT native 更换
媒体模块重新移植
Qwen3.5 4B / 9B 转换
新的 warm prefill
Qwen continuation Engine reset
全局 prompt 大改
同时加入 26B web + gpu 两个 artifact
```

---

# 第九部分：后续推荐的 ModelAgentProfile / Adapter 架构

模型池扩张后，不应该继续依赖“所有模型共享一个 prompt + parser + history 策略”。

建议后续逐步建立：

```text
ModelAgentProfile
  model family
  chat template family
  tool wire format
  native tool capability
  COMPAT tool capability
  thinking capability
  thinking default
  EOS / stop tokens
  history strategy
  continuation strategy
  context limits
  output limits
```

Adapter 至少分三类：

```text
Native Tool Adapter
Compat Tool Adapter
Thinking-Aware Tool Adapter
```

LocoOperator 目前是实机成功对照；新增模型通过实测后逐一固化 profile，避免为了某一个模型改坏所有模型。

---

# 第十部分：下一上下文执行清单

新对话接手后建议严格按顺序：

## Step 1 — 读取权威交接

优先读本文件，然后读：

```text
docs/ACTIVE_HANDOFF_MCP247_EMERGENCY_MEDIA_RUNTIME_RESTORE_2026-08-21.md
docs/checkpoints/QWEN35_MCP243_ROOT_CAUSE_AND_MCP244_PLAN_2026-08-20.md
docs/model_diagnostic_mcp-247_2026-08-21_120203.txt
```

## Step 2 — 确认 MCP247 仍是基线

确认分支、版本、media native hashes、Agent runtime invariants，禁止从旧分支重新起步。

## Step 3 — 对 8 个模型做最后 file-level freeze

逐个确认：

```text
modelId
exact modelFile
commitHash / revision
sizeInBytes
quantization
context/cache
recommended accelerator
thinking flag
Android/LiteRT-LM requirement
```

其中 Llama 3.2 3B 需要在两个当前转换来源中二选一。

## Step 4 — 加 8 个内置下载条目

不要重复加入 LocoOperator/JOSIE。

Gemma 26B 第一版按 Box v3.3.2 实际 APK 参数复刻 `web.litertlm`。

## Step 5 — 构建下一版 APK

版本号由新上下文结合仓库现状确定；如果没有其他并行版本占号，可以自然进入 MCP248，但不要在没有检查仓库的情况下盲目覆盖已有版本。

继续使用 GitHub Release / Actions 作为大型 APK 交付方式，不向用户发送大型 sandbox APK 链接。

## Step 6 — 用户实机测试

先测试新增模型的 download/load/chat。

26B 失败时直接导出模型诊断，保留完整：

```text
model identity
file identity/hash if available
runtime version
accelerator
context
initialization stage
exception / native error
memory info
conversation stage
```

## Step 7 — 依据真实错误修复

只有诊断明确指出某一层问题后，才进入：

```text
model-specific config
loader
adapter
template
stop token
thinking
continuation
```

避免在没有实机证据时再次扩大变量。

---

# 第十一部分：当前重要事实与置信度

## 已经确认

- MCP247 当前媒体功能已被用户实机验证恢复。
- 当前 LocoOperator 在 Plaza 已存在且用户确认多轮工具调用可用。
- JOSIE WI4/WI8 已存在，禁止重复新增。
- 当前 8 模型计划中其余模型未在 Plaza allowlist 发现同一 artifact。
- Box v3.3.2 实际 APK allowlist 的 26B 指向 `gemma-4-26B-A4B-it-web.litertlm`。
- Box 26B 配置为 GPU、32K context、4000 max tokens、16GB minimum RAM。
- 用户历史 issue 明确记录 26B 在 Redmi K70 Pro 上成功、流畅运行。
- 当前 Plaza imported model path 使用 generic ImportedModel metadata，与内置 allowlist path 存在配置路径差异。
- 当前公开 HF repo 同时存在 web 和较新的 gpu 26B artifact。

## 高概率但需要实机继续验证

- Plaza 以内置 allowlist 完整复刻 Box web artifact 参数后，有明显高于手工 generic import 的成功概率。
- 如果仍加载失败，差异更可能落在 Plaza 与 Box release runtime/loader/native/backend 初始化层。
- Qwen3.5 2B 当前工具后异常的直接表现主要是 continuation control-text echo。

## 尚未确认

- 当前 Plaza 26B 失败到底发生在 LiteRT-LM graph compatibility、GPU delegate、memory allocation、metadata 还是其他 loader 层。
- 新 `gemma-4-26B-A4B-it-gpu.litertlm` 是否已经真正 Android-ready。
- 当前 Qwen3.5 2B 32K artifact 是否确实存在 stop ID / `<|im_end|>` metadata 缺陷。

---

# 第十二部分：永久保护规则

后续所有模型实验必须遵守：

1. `main` 不动，除非用户明确授权。
2. MCP247 作为当前手机实测黄金产品基线。
3. 大 APK 交付只提供 GitHub Release/Actions 链接。
4. 媒体 native 与 Agent runtime 不能为了模型实验一起变化。
5. 一次尽量只引入一类新变量。
6. 用户实机诊断优先于纯理论推测。
7. exact duplicate 模型不重复加入下载列表。
8. 同名不同 artifact / quantization / conversion 可以并存，但名称必须清晰标注版本差异。
9. 26B 首轮只加入一个 artifact，优先复刻 Box 已实机成功的 web artifact。
10. 新模型先完成 load/chat 资格，再进入 tool qualification。

---

# 最终状态

本轮研究完成后：

```text
PRODUCT GOLDEN BASELINE = MCP247
NEXT MODEL ADDITIONS = 8
DUPLICATES EXCLUDED = LocoOperator + JOSIE
GEMMA 4 26B FIRST EXPERIMENT = Box v3.3.2 exact web-artifact allowlist replication
CODE CHANGES THIS TURN = NONE
DOCUMENTATION CHANGE = THIS HANDOFF ONLY
```

新上下文可以直接从“核对 8 个 artifact 的最终文件级信息 → 修改 allowlist → 构建下一 APK”开始，不需要重新重复本轮调查。
