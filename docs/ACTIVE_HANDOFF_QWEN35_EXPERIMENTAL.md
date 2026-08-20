# Local Agent Plaza — Qwen3.5 experimental live handoff

**READ THIS FILE FIRST IN A FRESH CONTEXT.**

Last authoritative checkpoint: **2026-08-20 21:13+08:00**
Branch: `experimental`
Default branch `main` must remain untouched.
Stable product reference: `golden/mcp-223-product-stable`.

This handoff **supersedes all earlier MCP246-as-current-path and `8792` roadmap instructions**. The user has explicitly ended the MCP240–MCP246 micro-debugging line and approved a clean return to the physically proven MCP238 conversion lineage, followed by same-method 2B/4B/9B model conversion at a 32K maximum context.

Detailed checkpoint:
`docs/checkpoints/QWEN35_MCP238_BASELINE_2B_4B_9B_32K_HANDOFF_2026-08-20.md`

Machine-readable execution contract:
`docs/qwen35_mcp238_2b_4b_9b_32k_execution_spec.json`

## 1. Current handoff state

This checkpoint is **documentation only**. During the handoff-writing turn:

- no source code was modified;
- no workflow/converter script was modified;
- no model-list entry was modified;
- no model conversion was started;
- no APK was built;
- no Release was created or overwritten;
- `main` was not touched.

The next context should first read this file and the JSON execution contract, refresh `experimental`, and then execute the approved model-first plan when the user instructs it to proceed.

## 2. Mandatory collaboration / safety rules

1. `experimental` is the only branch for this work. Keep `main` untouched.
2. Accessibility is a product acceptance requirement. The user's Android workflow must remain usable with a screen reader and must not depend on inaccessible auxiliary interfaces.
3. Mobile-first product behavior is authoritative. Do not redirect the user to PC/cloud/SSH workflows.
4. Do not repeat MCP242-style JNI/AAR/native-runtime surgery. Any future native/JNI/AAR/runtime intervention requires explicit user re-authorization and very strong new evidence.
5. Do not add repetition processors, hard NoRepeatNgram, watchdog cancellation, forced output truncation, simplified generic ChatML, or similar micro-debug hacks to this new baseline.
6. Large model artifacts use immutable GitHub Release/Actions delivery with exact SHA/size verification.
7. Converter/runtime revisions used for the new artifacts must be pinned after validation. Do not depend on a moving upstream `main` as the final reproducible build definition.
8. Model conversion and artifact validation come first. Do not spend time producing a new APK until the three model artifacts are validated and the user asks for integration/build work.
9. Keep the existing model-settings Context Window control. No replacement UI is required.

## 3. Sole behavioral gold baseline: MCP238

The one Qwen3.5 2B run that physically demonstrated the desired Agent control flow is **MCP238**.

APK identity:

- versionName: `1.0.14-mcp.238`
- versionCode: `338`
- package: `com.localagent.plaza.mcp`
- APK Release tag: `mcp238-qwen35-verified`

Original model identity:

- display: `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza`
- file: `Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`
- model tag: `qwen35-2b-q8-4096-v1`
- size: `4,780,966,112` bytes
- SHA256: `d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73`
- full official tool-aware template SHA256: `273d8e0e683b885071fb17e08d71e5f2a5ddfb5309756181681de4f5a1822d80`
- stops: `[248044, 248046]`
- topK: `20`
- topP: `0.8`
- temperature: `0.6`
- maxTokens: `1536`
- historical exported cache/context: `4096`
- MCP239 later forced CPU; preserve CPU as the intended Android inference path.

Physical diagnostic:
`docs/agent_perf_diagnostic_mcp-238_2026-08-20_031044.txt`

Physically proven Agent behavior in the 2026 World Cup final test:

1. user asks a web-dependent question;
2. model understands the task;
3. model emits one coherent tool call;
4. exactly one `tool.run_js` is executed successfully;
5. tool result returns to the model;
6. the model produces visible natural-language final-answer text in the Agent page;
7. the final answer then repeats coherent sections around the same subject and fails to terminate naturally, requiring manual stopping.

Diagnostic fingerprint:

- LLM passes: `2`
- continuations: `1`
- tool executions: exactly `1`
- continuation callbacks: `2868`
- visible continuation text: `4476` characters
- continuation duration: roughly `257 s`

This is the desired **control-flow baseline**, with one known deferred defect: final-answer repetition/nontermination. Do not describe MCP238 as fully stable. The new 4B and 9B experiments are specifically intended to test whether greater model capacity removes this remaining behavior while preserving the good one-tool-call flow.

## 4. Original MCP238 conversion lineage to reproduce

Historical source commit:
`ec4d70383a06697f40d79c4a98bfb9428f087e65`

Historical tree:
`c02cda72fd4e6032e3e40ac569502d42f9cd6fae`

Historical converter:
`model-conversion/qwen35/convert_qwen35.py`

Historical Android bridge / CI conversion helper:
`model-conversion/qwen35/run_android_bridge_ci.sh`

Historical 2B config:

```json
{
  "model_id": "Qwen/Qwen3.5-2B",
  "model_size": "2b",
  "quantization": "q8",
  "cache_length": 4096,
  "prefill_seq_len": 128,
  "output_name": "/tmp/qwen35/qwen35-2b-q8-4096.litertlm",
  "expected_max_rss_mb": 8192
}
```

The new work must preserve the **method**, while changing the target cache/context to `32768` and adding official 4B/9B sources.

Required conversion chain:

1. load official Hugging Face Qwen3.5 weights with Transformers;
2. use the Qwen3.5-specific LiteRT-Torch export extension and `Qwen3_5StaticForCausalLM`;
3. dynamic INT8 / Q8 quantization;
4. `dtype = int8`;
5. `algorithm = min_max`;
6. `linear_only = true`;
7. preserve the recurrent-state compatibility handling used by the MCP238 conversion path;
8. specifically preserve the `sequence_axis=0` fix for exactly three recurrent-state descriptors and assert the expected count;
9. convert to a LiteRT-LM CPU bundle;
10. restore/preserve the exact full official Hugging Face Qwen3.5 tool-aware `chat_template` for each source model;
11. verify template round-trip equality rather than simplifying/re-authoring the Jinja;
12. verify recurrent/state tensor ordering and required signatures/metadata;
13. smoke-load the completed LiteRT-LM artifact through the intended official CPU runtime before publication.

Historical `prefill_seq_len=128` and context/cache are different settings. Keep `128` unless converter/runtime evidence gives a concrete reason to change it.

### Reproducibility warning

The historical bridge cloned LiteRT-Torch from a moving upstream `main`. That was acceptable for discovery but is unsafe as a final reproducible definition. In the fresh execution context, reconstruct the MCP238-compatible converter first, identify and validate the working external revisions, then pin the exact tested revisions for the final 2B/4B/9B jobs.

## 5. New authoritative context rule: 32K

The user explicitly approved **32K = 32768 tokens** as the maximum configurable context for all three new Qwen3.5 artifacts, using the practical maximum used by Google's mobile inference/model examples as the product reference point.

This decision supersedes the previous roadmap instruction to test exact context `8792`.

For each new 2B/4B/9B artifact:

- export/configure the model itself with `cache_length = 32768` or the exact equivalent required by the validated converter;
- expose `32768` as the maximum in the existing Context Window setting;
- allow the user to manually select/type smaller values below `32768` using the existing settings control;
- do not create a second context UI;
- do not merely raise the UI maximum while leaving a 4096-capacity artifact underneath it;
- measure actual Android memory/loading behavior; do not invent `expected_max_rss_mb` values for the 32K artifacts.

Official Qwen3.5 source models may advertise larger theoretical native context. For this project the approved Android LiteRT-LM product maximum is **32768**.

## 6. Three clean conversion targets

Use official Qwen Hugging Face sources only for the new target artifacts:

- 2B: `Qwen/Qwen3.5-2B`
- 4B: `Qwen/Qwen3.5-4B`
- 9B: `Qwen/Qwen3.5-9B`

All three must use the same MCP238-derived conversion logic and validation rules. Model size, memory consumption and final artifact size may differ; tool template semantics, quantization method, 32K product context policy and validation philosophy must remain aligned.

Do not use the `paulsp94` community LiteRT model route for these new canonical targets.

## 7. Current 2B entries are rejected / removal targets

The user has physically rejected the two current Qwen3.5 2B entries visible in the app. They must be removed from the model list during the later integration phase; do not continue debugging either as the product path.

### A. Current non-MCP245 Plaza entry — cannot load in latest physical test

Latest diagnostic:
`docs/model_lifecycle_diagnostic_mcp-246_2026-08-20_125634.txt`

Selected display:
`Qwen3.5-2B LiteRT-LM Q8 4096 Plaza`

Selected path:
`Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`

Hard facts:

- app: `1.0.14-mcp.246`
- state: `ERROR`
- expected size: `4,780,966,112`
- actual local size: `503,316,480` bytes
- initialization fails with:
  `Status Code: 3. Message: signature_name not found. LiteRT model does not contain TF_LITE_PREFILL_DECODE`

Treat this current entry/local artifact as unusable. Do not assume its current bytes are the original MCP238 `d5e975...` gold artifact merely because the display/file name resembles it.

### B. MCP245 frozen entry — loads, but Agent behavior is unusable

Display:
`Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245`

File:
`Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp245.litertlm`

Tag:
`qwen35-2b-q8-4096-mcp245-frozen-v1`

SHA256:
`535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`

Size:
`4,780,966,112`

MCP245 physical diagnostic:
`docs/model_diagnostic_mcp-245_2026-08-20_113748.txt`

MCP245 proved ordinary chat/load and one tool continuation could run, but the continuation decoded for minutes without natural completion. It is no longer the product baseline.

### C. Legacy community experimental entries

Earlier source history also contains/contained a community Qwen3.5 experimental route such as `Qwen3.5-2B LiteRT-LM Q8 experimental` and its Q4 child based on `paulsp94/Qwen3.5-2B-LiteRT-LM`. In the fresh integration phase, audit the current allowlist and remove/deprecate any such legacy entries that remain, so the model list contains only the newly validated canonical 2B/4B/9B line. Keep this separate from the exact non-MCP245 physical failure above.

## 8. MCP240–MCP246 are historical evidence, not the next implementation path

### MCP240

- replaced the original roughly 154-line full Qwen3.5 tool-aware Jinja with a roughly 20-line generic ChatML template;
- removed native tool-schema/tool-call/tool-response/history behavior;
- experimented with repetition penalty and Engine rebuild;
- repetition processor failed before token 1 with:
  `Status Code: 3. Message: Logits dimensions must be [batch_size, 1, vocab_size].`

### MCP241

- removed the incompatible repetition processor, but remained in the altered experimental lineage.

### MCP242

- custom rebuilt LiteRT-LM JNI/AAR/native patch;
- caused severe random process crashes at launch/model pages on the physical Android device;
- permanently reject this route unless the user explicitly authorizes a future native experiment based on strong new evidence.

### MCP243

- returned to official Maven LiteRT-LM 0.15 while retaining the simplified-template lineage;
- physical Agent test produced 9 LLM passes / 8 continuations with repeated search/file/search loops and tool leakage;
- model/template comparison localized the new malformed repeated-tool regression to the simplified MCP240 template with high confidence.

### MCP244

- restored the original full official tool-aware template and natural stop metadata;
- delivery integrity failed because the same model Release was repacked multiple times and uploaded with `--clobber`, producing nondeterministic UUID/timestamp bytes and SHA drift;
- preserve its forensics as a permanent release-engineering lesson:
  - `docs/mcp244_multipart_forensics.json`
  - `docs/mcp244_manifest_and_runs_probe.json`

### MCP245

- froze the then-current repacked bytes under a new immutable tag;
- loads and ordinary chat works;
- physical Agent continuation still failed to terminate after a successful tool result.

### MCP246 — physically rejected

Patch:
`Android/src/scripts/patch_mcp246_qwen35_continuation_engine_reset.py`

Workflow:
`.github/workflows/mcp246_qwen35_continuation_engine_reset.yaml`

APK:
`1.0.14-mcp.246`, versionCode `346`

Latest physical Agent diagnostic:
`docs/llm_model_diagnostic_mcp-246_2026-08-20_125407.txt`

Hard outcome:

- 4 LLM passes;
- 3 continuations;
- 3 tool executions;
- repeated search/workspace-search/read behavior returned;
- user reported occasional unsolicited workspace writes during this abnormal loop;
- request ended only after manual Stop.

Therefore the MCP246 Engine-reset direction is rejected. Do not continue iterating on Engine reset as the default repair path.

## 9. Immutable artifact / release policy for new 2B, 4B and 9B

Each target must have an independent job and independent immutable artifact identity.

Required publication discipline:

1. independent 2B / 4B / 9B conversion jobs;
2. pin validated converter/runtime source revisions;
3. record exact source model/revision;
4. record conversion config;
5. record exact final byte size;
6. record SHA256 of the full `.litertlm` artifact;
7. use distinct immutable Release tags;
8. never regenerate and `--clobber` the same published model identity;
9. split into multipart Release assets only when required by hosting limits;
10. after publication, independently re-download all assets, reconstruct the full file and verify exact size/SHA before declaring the model deliverable;
11. retain a machine-readable manifest for each artifact.

MCP244 proved that a successful workflow upload is insufficient if a later run mutates the same tag. Post-publication reconstruction verification is mandatory.

## 10. Fresh-context execution order

When the user opens the next context and asks execution to begin:

1. read this file;
2. read `docs/qwen35_mcp238_2b_4b_9b_32k_execution_spec.json`;
3. refresh `experimental` and verify no newer user-approved checkpoint supersedes these files;
4. keep `main` untouched;
5. reconstruct/inspect the exact MCP238 converter at historical commit `ec4d70383a06697f40d79c4a98bfb9428f087e65`;
6. identify and pin the validated external LiteRT-Torch/LiteRT-LM revisions while preserving MCP238 behavior;
7. create aligned conversion definitions for 2B, 4B and 9B using official Qwen HF sources, dynamic INT8/Q8, `prefill_seq_len=128`, and **max context/cache 32768**;
8. build/convert the three model artifacts first, independently;
9. validate each artifact's template, recurrent-state descriptors/order, signatures/metadata and official CPU runtime smoke load;
10. publish each with immutable tags/manifests and perform post-release re-download/reconstruction SHA verification;
11. only after model artifacts pass validation, update the app model list: remove the two physically rejected current 2B entries, audit/remove stale community experimental entries, add the new canonical 2B/4B/9B artifacts and cap the existing Context Window UI at `32768` for each;
12. perform the same physical Agent task on 2B, 4B and 9B: one necessary tool call, successful result, visible final answer, and observe natural termination;
13. interpret the comparison:
    - 2B repeats but 4B/9B terminate cleanly: strong support for the model-capacity hypothesis;
    - all three show the same post-tool repetition/nontermination: stop model-capacity assumptions and re-evaluate converter/runtime/prompt behavior without native surgery;
    - malformed repeated tool loops appear before a normal final answer: stop and compare directly against the MCP238 template/control-flow invariant;
14. build/integrate a new APK only after validated model work and user direction.

## 11. Acceptance gates

A converted model artifact is not accepted merely because conversion exits zero.

For each of 2B/4B/9B, require:

- official source identity recorded;
- correct 32K export/cache capacity;
- Q8 dynamic INT8 method confirmed;
- full official tool-aware template preserved and verified;
- recurrent state compatibility checks pass;
- required LiteRT-LM signatures/metadata present;
- official CPU runtime smoke-load succeeds;
- immutable published artifact exact size/SHA verified after re-download.

Agent/product acceptance later requires:

- model loads on the physical Android target;
- ordinary chat remains usable;
- one web-dependent request leads to one appropriate tool call when one is sufficient;
- tool executes successfully;
- final answer is visible in the Agent UI;
- natural termination is preferred and is the key comparison metric across 2B/4B/9B;
- no unsolicited workspace writes during a simple web-answer task;
- VoiceOver/screen-reader interaction remains predictable.

## 12. Stop criteria

Stop and report rather than entering another endless micro-debug loop if:

- a proposed fix requires custom JNI/AAR/native runtime surgery without explicit new authorization;
- a fix requires replacing the official Qwen3.5 tool-aware template with a generic simplified template;
- all three aligned 2B/4B/9B models reproduce the same fundamental failure and no low-risk, strongly evidenced converter/runtime correction exists;
- publication cannot produce immutable bytes with independently verified SHA;
- a model cannot physically load at 32K within the Android device/runtime limits after reasonable converter validation.

If 32K proves physically impossible for a specific model on the target device/runtime, preserve the evidence and report the measured ceiling to the user before reducing the product maximum. Do not silently lower it.

## 13. Final authoritative decisions at handoff

- MCP238 is the sole behavioral gold baseline.
- The MCP238 one-tool-call + visible-answer behavior is worth preserving even though its 2B final text repeats and does not terminate naturally.
- Current non-MCP245 Plaza and MCP245 2B lines are rejected as product entries and are future removal targets.
- New canonical targets are official Qwen3.5 2B, 4B and 9B.
- All three use the same MCP238-derived conversion method.
- Maximum product context is **32768**, user-adjustable downward through the existing Context Window control.
- The former `8792` roadmap is superseded.
- Model artifacts and validation come before APK work.
- MCP240–MCP246 low-level/simplified-template/Engine-reset experimentation is closed unless the user later explicitly reopens a specific avenue.
