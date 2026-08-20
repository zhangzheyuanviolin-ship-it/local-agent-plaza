# Qwen3.5 MCP238 baseline → 2B / 4B / 9B / 32K handoff checkpoint

Date: **2026-08-20**
Branch: `experimental`
Status: **user-approved plan, documentation complete, execution intentionally not started in this context**

Authoritative live entry point:
`docs/ACTIVE_HANDOFF_QWEN35_EXPERIMENTAL.md`

Machine-readable contract:
`docs/qwen35_mcp238_2b_4b_9b_32k_execution_spec.json`

---

## 1. Why this checkpoint exists

The Qwen3.5 LiteRT-LM investigation accumulated many experimental versions from MCP238 through MCP246. Several later attempts changed more than one layer at once: model metadata/template, repetition processing, Engine lifecycle, native runtime, artifact packaging and Agent continuation behavior. Physical tests eventually showed that this direction was consuming time while moving away from the one version that had demonstrated the cleanest Agent control flow.

The user has now explicitly closed that micro-debugging cycle and approved a clean experimental reset around the **MCP238 behavior and original conversion method**.

This checkpoint therefore freezes four decisions:

1. MCP238 is the sole behavior/conversion reference worth reproducing.
2. The two current unusable Qwen3.5 2B entries are future removal targets.
3. Clean official Qwen3.5 2B, 4B and 9B Q8 LiteRT-LM artifacts will be converted with the same MCP238-derived method.
4. All three new model lines use **32768 tokens (32K)** as their maximum product context, with smaller user-selected contexts handled by the existing settings UI.

The handoff-writing turn made **documentation changes only**. No converter, source file, allowlist, workflow, model artifact, APK or Release was changed.

---

## 2. Branch and product constraints

- Work branch: `experimental`.
- `main` must remain untouched by this experiment.
- Stable product reference remains `golden/mcp-223-product-stable`.
- Android/mobile-first behavior is authoritative.
- Accessibility with a screen reader is an acceptance condition, including model-selection/settings controls and final Agent output.
- CPU inference remains the intended conservative Qwen3.5 Android path unless later physical evidence supports another official runtime path.
- Do not require the user to obtain crash logs when the process dies.
- Large model delivery belongs in GitHub Releases/Actions with exact integrity metadata.

---

## 3. The exact MCP238 gold baseline

### 3.1 APK identity

- versionName: `1.0.14-mcp.238`
- versionCode: `338`
- package: `com.localagent.plaza.mcp`
- APK Release tag: `mcp238-qwen35-verified`

### 3.2 Model identity

- display name: `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza`
- file: `Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`
- model Release tag: `qwen35-2b-q8-4096-v1`
- exact size: `4,780,966,112` bytes
- SHA256: `d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73`
- official full tool-aware Jinja template SHA256: `273d8e0e683b885071fb17e08d71e5f2a5ddfb5309756181681de4f5a1822d80`
- stop tokens: `[248044, 248046]`
- topK: `20`
- topP: `0.8`
- temperature: `0.6`
- maxTokens: `1536`
- historical cache/context: `4096`
- MCP239 later forced the CPU path.

### 3.3 Physical diagnostic that makes MCP238 unique

Diagnostic:
`docs/agent_perf_diagnostic_mcp-238_2026-08-20_031044.txt`

The test asked an up-to-date question about the **2026 World Cup final**. The important behavior was:

- initial model pass understood that a network lookup was necessary;
- exactly one coherent `tool.run_js` call was emitted;
- the tool completed successfully;
- exactly one post-tool continuation ran;
- natural-language final-answer text became visible to the user;
- the final text stayed on the correct topic and contained coherent sources/notes/summary-style sections;
- the model then repeated equivalent final sections for minutes and did not naturally terminate, so manual stopping was required.

Diagnostic fingerprint:

- LLM passes: `2`
- continuation count: `1`
- tool executions: `1`
- continuation callbacks: `2868`
- visible continuation characters: `4476`
- continuation time: roughly `257 s`

This gives a very specific target behavior for larger models:

`understand task → one appropriate tool call → successful result → visible final answer`.

The remaining MCP238 defect is equally specific:

`final answer repeats / fails to terminate naturally`.

That defect is deliberately **not** the first problem to micro-debug again. The 4B and 9B models create a much cleaner experiment: hold converter/tool protocol/runtime method constant and change model capacity.

---

## 4. Historical MCP238 conversion recipe

Historical source commit:
`ec4d70383a06697f40d79c4a98bfb9428f087e65`

Historical tree SHA:
`c02cda72fd4e6032e3e40ac569502d42f9cd6fae`

Primary converter:
`model-conversion/qwen35/convert_qwen35.py`

Android bridge / CI helper:
`model-conversion/qwen35/run_android_bridge_ci.sh`

Historical config:

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

### 4.1 Conversion stages that must be preserved

1. Source weights come from the official `Qwen/Qwen3.5-*` Hugging Face repository.
2. Transformers loads the source model/config/tokenizer.
3. Use the Qwen3.5-specific LiteRT-Torch export extension.
4. Use `Qwen3_5StaticForCausalLM` rather than a generic architecture shim.
5. Quantization is dynamic INT8/Q8.
6. Quantized dtype is `int8`.
7. Quantization algorithm is `min_max`.
8. `linear_only = true`.
9. Apply/preserve the Qwen3.5 recurrent-state compatibility patch used in the successful lineage.
10. The compatibility patch supplies `sequence_axis=0` for **exactly three** recurrent-state descriptors.
11. Assert the number of recurrent descriptor replacements/checks so silent exporter drift is caught.
12. Convert/package as a LiteRT-LM CPU-capable bundle.
13. Preserve all required cache/blob/executor metadata and state tensor ordering.
14. Restore/preserve the tokenizer's complete official Qwen3.5 tool-aware `chat_template`.
15. Verify that template byte/text content round-trips exactly after packing.
16. Verify the expected LiteRT-LM signatures/metadata are present.
17. Smoke-load the final artifact with the intended official CPU runtime before publication.

### 4.2 Why the official chat template is an invariant

The original template contains Qwen3.5's tool semantics, including tool schema injection, tool-call serialization, function/parameter formatting, tool responses, tool-role history, multi-step history and thinking-related handling.

MCP240 demonstrated that replacing this with a short generic ChatML template can radically change Agent behavior. Therefore template preservation is an artifact acceptance gate, not cosmetic metadata.

### 4.3 Prefill and context are independent

Historical `prefill_seq_len` was `128`.

Historical `cache_length` was `4096`.

Do not reinterpret `128` as the context size. For the new models, start from `prefill_seq_len=128` while changing the exported product context/cache target to `32768`. If the validated modern exporter requires a different prefill setting, record the concrete reason and resulting performance/compatibility evidence.

### 4.4 Reproducibility weakness in the old process

The historical bridge cloned LiteRT-Torch from upstream `main`. A moving branch can make identical project commits produce different model bytes later.

The fresh-context job must preserve MCP238 behavior while improving reproducibility:

- reconstruct the historically working path;
- identify the exact current/historical LiteRT-Torch and LiteRT-LM revision combination that reproduces required behavior;
- pin the validated revisions in the final conversion definitions;
- record revisions in artifact manifests.

Do not casually upgrade converter/runtime versions during the same 2B/4B/9B comparison. The comparison is valuable only if the infrastructure is held constant.

---

## 5. Authoritative 32K context policy

The user's approved product rule is:

**maximum configurable context = 32768 tokens for Qwen3.5 2B, 4B and 9B.**

This replaces all previous instructions to make `8792` the next context milestone.

### 5.1 Artifact requirement

Each new `.litertlm` artifact must actually be exported/configured with a 32768-token cache/context capacity, or the precise equivalent used by the validated converter/runtime.

A 4096-capacity artifact with a UI field set to 32768 does not satisfy this requirement.

### 5.2 Existing UI requirement

The app already has the Context Window / `MAX_CONTEXT_LENGTH` settings mechanism. Reuse it.

For each validated new model:

- maximum shown/accepted: `32768`;
- user can manually select/type a lower context;
- no parallel model-settings UI should be invented;
- lower user values should feed the existing runtime initialization path normally.

### 5.3 Memory rule

Do not guess 32K RSS budgets from the old 4096 build.

For 2B, 4B and 9B set conversion-planning memory expectations to `TBD_MEASURE` until actual conversion/load measurements exist.

If a 32K artifact for one model cannot physically load within the Redmi K70 Pro / official runtime constraints, capture the measured evidence and report the actual ceiling to the user before changing the product maximum. Do not silently lower 32768.

---

## 6. New canonical target set

### 6.1 2B

Source:
`Qwen/Qwen3.5-2B`

Target:

- Q8 / dynamic INT8;
- same MCP238-derived converter;
- max context/cache `32768`;
- `prefill_seq_len=128` initially;
- official full source tokenizer tool template preserved;
- official CPU LiteRT-LM smoke-load required.

### 6.2 4B

Source:
`Qwen/Qwen3.5-4B`

Target uses the identical conversion/validation principles as 2B. Do not invent a separate 4B prompt/tool protocol.

### 6.3 9B

Source:
`Qwen/Qwen3.5-9B`

Target uses the identical conversion/validation principles as 2B/4B. 9B memory/time will be larger, so treat its job and artifact independently rather than allowing a 9B failure to invalidate smaller completed artifacts.

### 6.4 Why all three are required before another long 2B debugging cycle

The experiment controls most variables:

- same architecture family;
- same official source organization;
- same conversion method;
- same quantization philosophy;
- same 32K product context;
- same official tool template semantics;
- same Agent task/tool path;
- primarily model capacity changes.

Interpretation after physical testing:

- if 2B repeats while 4B/9B terminate normally, model capacity becomes a strong explanation for the residual final-answer defect;
- if all three repeat/nonterminate in the same way, converter/runtime/prompt serialization becomes more likely and another 2B-only tuning loop has low value;
- if larger models introduce malformed repeated tool calls, compare their packed chat templates/metadata against the exact MCP238 invariant before changing Agent code.

---

## 7. Current model-list cleanup targets for later integration

No model list was changed while writing this checkpoint.

### 7.1 Non-MCP245 Plaza entry

Current latest physical diagnostic:
`docs/model_lifecycle_diagnostic_mcp-246_2026-08-20_125634.txt`

Selected name:
`Qwen3.5-2B LiteRT-LM Q8 4096 Plaza`

Selected file path:
`Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`

Physical facts:

- app version `1.0.14-mcp.246`;
- model lifecycle state `ERROR`;
- expected bytes `4,780,966,112`;
- actual bytes `503,316,480`;
- size validation fails;
- initialization additionally reports:
  `Status Code: 3. Message: signature_name not found. LiteRT model does not contain TF_LITE_PREFILL_DECODE`.

This currently installed/local artifact is unusable and cannot be treated as the original MCP238 artifact merely from its matching display/file naming.

Later integration action: remove this rejected current entry and replace it with the new validated canonical 32K 2B entry.

### 7.2 MCP245 entry

Display:
`Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245`

File:
`Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp245.litertlm`

Release tag:
`qwen35-2b-q8-4096-mcp245-frozen-v1`

Size:
`4,780,966,112`

SHA256:
`535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`

Physical diagnostic:
`docs/model_diagnostic_mcp-245_2026-08-20_113748.txt`

Facts:

- model downloads/loads;
- ordinary chat works;
- Agent produces one valid tool call;
- exactly one `tool.run_js` succeeds;
- continuation starts and decodes;
- continuation runs around `128.4 s` before manual Stop;
- `1687` visible callbacks / `10127` visible chars;
- no natural `onDone` before manual Stop.

Later integration action: remove MCP245 as a product model after the new canonical line is validated.

### 7.3 Legacy community experimental route

Earlier project history also referenced:

- `Qwen3.5-2B LiteRT-LM Q8 experimental`;
- `paulsp94/Qwen3.5-2B-LiteRT-LM`;
- a Q4 child/variant.

These are separate from the exact currently failing non-MCP245 Plaza entry above. During later model-list integration, inspect the current allowlist/source and remove or deprecate any remaining legacy community Qwen3.5 entries so users are presented with the clean official-source 2B/4B/9B family.

---

## 8. Failed experiment history that must not be rediscovered

### 8.1 MCP240 — simplified template and repetition experiment

MCP240 changed the strongest behavior invariant by replacing the full official Qwen3.5 tool-aware Jinja with a short generic ChatML template. It also experimented with Engine rebuilding, sampler changes and LiteRT-LM repetition processing.

The repetition processor failed before token 1:

`Status Code: 3. Message: Logits dimensions must be [batch_size, 1, vocab_size].`

The padded-vocabulary/logits shape path in LiteRT-LM 0.15 was incompatible with this attempted processor.

Permanent lesson: do not use repetition processor success/failure as a reason to rewrite the model/tool template.

### 8.2 MCP241

Removed the repetition processor but remained on the altered lineage. It does not restore MCP238 simply because one failed feature was removed.

### 8.3 MCP242 — custom native runtime

A rebuilt LiteRT-LM JNI/AAR was introduced to patch native/runtime behavior. Physical device testing produced severe random process crashes at app launch/model pages.

Permanent rule: no custom JNI/AAR/native LiteRT runtime surgery without explicit user reauthorization backed by compelling new evidence.

### 8.4 MCP243 — official runtime, still simplified model/template

Returned to official Maven LiteRT-LM but retained the simplified-template model and fresh-Engine behavior.

Physical Agent diagnostic:
`docs/model_diagnostic_mcp-243_2026-08-20_081924.txt`

Observed:

- 9 LLM passes;
- 8 continuations;
- search/file/search loops;
- tool-call leakage/malformed behavior.

A direct metadata/template comparison isolated the simplified template as the most likely source of this new regression.

Relevant forensic docs:

- `docs/qwen35_mcp238_vs_mcp240_metadata_compare.json`
- `docs/checkpoints/QWEN35_MCP243_ROOT_CAUSE_AND_MCP244_PLAN_2026-08-20.md`
- `docs/checkpoints/QWEN35_MCP244_ISOLATION_CORRECTION_2026-08-20_1636.md`

### 8.5 MCP244 — template restored, artifact delivery drift

MCP244 restored the full original tool-aware Jinja and stops but its model delivery exposed a separate release-engineering defect.

Multiple workflow runs repacked the same logical model. Packaging contained nondeterministic values such as UUID/timestamp. The same Release identity was then overwritten with `--clobber`, so the bytes published later no longer matched earlier APK expectations.

Important forensics:

- `docs/mcp244_multipart_forensics.json`
- `docs/mcp244_manifest_and_runs_probe.json`

Permanent lesson: every model build gets an immutable identity; after publication, reconstruct from the public Release assets and verify full SHA again.

### 8.6 MCP245 — frozen delivery, remaining nontermination

MCP245 solved the mutable delivery problem by freezing the then-current bytes under a new one-time tag. Physical behavior still did not provide a naturally completing Agent answer.

MCP245 is useful evidence. It is not the new conversion baseline.

### 8.7 MCP246 — Engine-reset hypothesis physically rejected

Patch:
`Android/src/scripts/patch_mcp246_qwen35_continuation_engine_reset.py`

Workflow:
`.github/workflows/mcp246_qwen35_continuation_engine_reset.yaml`

APK:

- versionName `1.0.14-mcp.246`;
- versionCode `346`;
- APK SHA256 `8dfd2b882f419ef85412b8b55a366147b1fb409e596bf9cf24cb8dced6c6c17a`.

The hypothesis was that a one-shot official Engine recreation before tool continuation could clear hybrid recurrent state retained across conversations.

Latest physical Agent diagnostic:
`docs/llm_model_diagnostic_mcp-246_2026-08-20_125407.txt`

Physical result:

- `4` LLM passes;
- `3` continuations;
- `3` tool executions;
- repeated web/workspace search/read cycles returned;
- user observed occasional unsolicited workspace writes;
- request ended only after manual Stop.

This removes Engine reset from the default next path. It also validates the user's decision to stop spending more iterations on small 2B lifecycle/template/runtime tweaks.

---

## 9. Release and integrity requirements for the new artifacts

Each model is independent.

For each of 2B/4B/9B, final artifact publication must include:

1. official source model ID and source revision;
2. pinned converter/runtime revisions;
3. complete conversion config including `cache_length=32768`;
4. quantization declaration;
5. exact output file name;
6. exact full byte size;
7. exact SHA256;
8. immutable Release tag;
9. multipart manifest if splitting is required;
10. post-publication re-download of every part;
11. reconstruction of the complete `.litertlm` file;
12. independent exact size/SHA verification against the pre-publication artifact.

Do not publish multiple newly repacked byte sequences under the same tag/name.

A failed 9B conversion must not cause successful 2B/4B artifacts to be overwritten or repacked.

---

## 10. Model artifact acceptance gates

A workflow green check alone is insufficient.

Each new model must pass all of the following before app integration:

- correct official source model/revision recorded;
- Qwen3.5-specific exporter used;
- dynamic INT8/Q8 (`int8`, `min_max`, `linear_only`) confirmed;
- actual exported maximum context/cache is 32768;
- complete source tokenizer official tool-aware chat template preserved;
- template equality/round-trip check passes;
- expected recurrent-state descriptor patch/check count is correct;
- state tensor ordering and metadata assertions pass;
- required LiteRT-LM signatures exist, including the prefill/decode signatures expected by the official runtime;
- official CPU LiteRT-LM smoke initialization succeeds;
- no custom JNI/AAR is required;
- immutable published bytes re-download to the exact recorded SHA/size.

Only then is an artifact ready to appear in the app model list.

---

## 11. Later app-integration requirements

After all desired model artifacts are validated:

1. remove the two physically rejected current Qwen3.5 2B user-visible entries;
2. audit/remove any remaining legacy `paulsp94` experimental Qwen3.5 entries/children;
3. add canonical official-source 2B/4B/9B entries;
4. set each model's supported Context Window maximum to `32768`;
5. preserve the existing user-editable context setting below that maximum;
6. preserve screen-reader accessibility of the model list and settings field;
7. do not add a new context settings screen;
8. do not carry MCP246's Engine-reset special case into the clean baseline without new justification;
9. do not automatically inherit MCP240–MCP246 experimental patches merely because their files remain in repository history.

The clean app behavior should be deliberately reconstructed from the stable product + minimum required Qwen3.5 support, using MCP238 as the Agent behavior reference.

---

## 12. Physical comparison test for 2B / 4B / 9B

Use the same simple web-dependent Agent prompt across all three models, preferably one where one network query is sufficient.

For each model record:

- load succeeds/fails;
- selected context value;
- initial TTFT;
- tool chosen;
- number of tool calls;
- tool success/failure;
- number of post-tool continuations;
- whether final natural-language text is visible;
- whether the answer naturally terminates;
- whether content repeats;
- whether the Agent unexpectedly reads/writes workspace files;
- peak/representative memory where available;
- whether the process remains stable.

Desired flow:

`one necessary tool → result → one visible final answer → natural completion`.

MCP238 provides the minimum acceptable structural reference:

`one necessary tool → result → visible final answer`, with repetition/nontermination recorded as the known residual 2B issue.

---

## 13. Interpretation and stop criteria

### Outcome A — 4B/9B fix final repetition naturally

If 2B preserves MCP238-like one-tool behavior but repeats, while 4B and/or 9B produce a clean terminating answer, the model-capacity hypothesis gains strong physical support. Favor the best practical model size for product use instead of resuming low-level 2B surgery.

### Outcome B — all three repeat/nonterminate after otherwise-correct one-tool flows

The defect likely belongs to a shared converter/runtime/prompt-termination path. Stop the capacity experiment and investigate the shared path with bounded, low-risk diagnostics. Do not jump to JNI/AAR modifications.

### Outcome C — malformed repeated tool loops appear

Verify packed official template, tool serialization and Agent orchestration against the MCP238 invariant before changing sampler/runtime. Multiple search/workspace cycles for a simple one-search question are a rejection result.

### Outcome D — 32K cannot load for one model

Collect exact artifact identity, context, memory/runtime error and physical result. Report the measured maximum feasible context to the user. Do not silently publish a smaller-context model under a 32K label.

### Outcome E — converter requires moving upstream code

Do not accept a moving branch as the long-term definition. Pin the proven revision once the artifact works.

---

## 14. Exact fresh-context action sequence

The new context should execute in this order:

1. read `docs/ACTIVE_HANDOFF_QWEN35_EXPERIMENTAL.md`;
2. read `docs/qwen35_mcp238_2b_4b_9b_32k_execution_spec.json`;
3. inspect the latest `experimental` head and any newer diagnostic commits;
4. inspect historical commit `ec4d70383a06697f40d79c4a98bfb9428f087e65` and reconstruct the exact MCP238 converter/bridge behavior;
5. identify the external converter/runtime revisions required to reproduce it and prepare to pin them;
6. create three independent conversion configs/jobs for official Qwen3.5 2B/4B/9B;
7. set target cache/context to `32768` for all three;
8. keep `prefill_seq_len=128` initially;
9. run model conversions independently;
10. validate templates/state/signatures/smoke-load/integrity for each artifact;
11. publish immutable model artifacts and independently reconstruct/verify their hashes;
12. only after model validation, update the app model list and Context Window maximum;
13. perform same-task physical comparison on 2B/4B/9B;
14. build a new APK only when artifact integration is ready and the user directs it.

Do not spend the first fresh-context iteration trying to repair MCP245/MCP246 again.

---

## 15. Superseded instructions

The following prior directions are no longer authoritative:

- “MCP246 is the current physical-test candidate.”
- “Do not proceed to 4B/9B until MCP246 succeeds.”
- “Next context target is exact 8792.”
- “Continue Engine-reset debugging after the MCP245 stall.”
- “Treat the MCP245 frozen bytes as the canonical new model lineage.”

Current authoritative direction is:

**MCP238 conversion/behavior reference → clean official Qwen3.5 2B/4B/9B Q8 conversions → actual 32K artifacts → validate models first → later clean app integration and physical comparison.**

---

## 16. Handoff completion state

At the moment this checkpoint was written:

- plan approved by user;
- 32K maximum explicitly approved by user;
- documentation updated on `experimental`;
- source code unchanged in this handoff phase;
- model conversion not started;
- app model list unchanged;
- APK build not started;
- Release publication not started.

The next context can treat this document plus the live handoff and JSON spec as the complete continuity package.
