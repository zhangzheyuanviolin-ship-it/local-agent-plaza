# Local Agent Plaza — Qwen3.5 2B / 4B / 9B Rebase Handoff

Date: 2026-08-20
Branch: `experimental`
Default branch: `main` — DO NOT TOUCH
Stable product reference: `golden/mcp-223-product-stable`
Status: INVESTIGATION COMPLETE / IMPLEMENTATION NOT STARTED

## 0. Scope and hard stop

This handoff supersedes the previous MCP246-next-test direction.

The user has physically tested the current Qwen3.5 2B options and has explicitly terminated the MCP240–MCP246 repair line. The next context must not continue micro-debugging MCP245/MCP246.

This handoff records the approved new direction only. At the time this document was written:

- no application code was modified for the new direction;
- no model allowlist entry was removed yet;
- no 2B/4B/9B conversion was launched yet;
- no APK was built;
- no model artifact was published;
- only documentation on `experimental` was changed.

The next context must read this file and `docs/ACTIVE_HANDOFF_QWEN35_EXPERIMENTAL.md` before making any material change.

---

## 1. User-approved product decision

The user has approved the following reset:

1. Treat MCP238 as the only proven Qwen3.5 2B behavioral baseline worth preserving.
2. Remove both currently exposed unusable Qwen3.5 2B lines from the application model list in the implementation phase.
3. Rebuild a clean Qwen3.5-2B using the original MCP238 conversion method and metadata behavior.
4. Build Qwen3.5-4B and Qwen3.5-9B using the same conversion method and the same Agent/tool protocol assumptions.
5. Use `32768` tokens (32K) as the maximum configurable context for all three converted models.
6. Preserve the application’s existing context-length setting UI so the user can manually choose any supported value at or below 32K.
7. Compare 2B, 4B, and 9B under the same Agent workflow to determine whether the final-answer repetition seen on 2B is mainly a small-model capability problem.
8. Stop spending time on custom JNI/AAR, repetition processors, Engine-reset variations, simplified templates, watchdog truncation, or other MCP240–MCP246-style micro-fixes unless the user explicitly reauthorizes them.

The user’s priority is now: obtain three comparable Qwen3.5 LiteRT-LM models as quickly and conservatively as possible, using the only conversion/behavior line that ever reached a usable single-tool final-answer state.

---

## 2. The exact baseline to recover: MCP238

### 2.1 APK identity

The physical baseline application version is:

- `versionName`: `1.0.14-mcp.238`
- `versionCode`: `338`
- package: `com.localagent.plaza.mcp`
- release tag: `mcp238-qwen35-verified`

Relevant repository workflow/materials include:

- `.github/workflows/mcp238_verified_build.yaml`
- `.github/workflows/mcp238_final.yaml`
- `.github/workflows/mcp238_build_retry.yaml`
- `docs/mcp238_apk_result.json`
- `docs/mcp238_verified_run.json`
- `docs/HANDOFF_MCP238_2026-08-20.md`

### 2.2 Exact model identity

The model that produced the physically useful Agent behavior was:

- display name: `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza`
- file: `Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`
- release tag: `qwen35-2b-q8-4096-v1`
- size: `4,780,966,112` bytes
- SHA256: `d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73`
- sampler: topK `20`, topP `0.8`, temperature `0.6`
- max output tokens: `1536`
- exported context/cache for that artifact: `4096`
- MCP239 later forced CPU for this line.

### 2.3 Why MCP238 is the baseline

Physical diagnostic:

`docs/agent_perf_diagnostic_mcp-238_2026-08-20_031044.txt`

The user’s remembered test was the 2026 World Cup final request. The diagnostic uniquely matches that test.

Observed behavior:

1. the model loaded successfully;
2. the Agent understood the web-search request;
3. it made one coherent tool invocation;
4. `tool.run_js` succeeded;
5. the tool result was returned to the model;
6. the model entered the final natural-language answer path;
7. final answer text was visible to the user;
8. the answer then repeated coherent sections around the same World Cup subject until the user stopped it.

Diagnostic-level fingerprint:

- LLM passes: `2`
- continuations: `1`
- tool count: `1`
- tool: `tool.run_js`
- tool success: `true`
- continuation callbacks: approximately `2868`
- visible continuation text: approximately `4476` characters

This behavior is imperfect, but it is the highest-value proven baseline because it did not enter the later search/read-workspace/search/read-workspace behavioral loop and it did reach user-visible final answer text.

The final-answer repetition is now an accepted known defect for the 2B baseline. Do not block 4B/9B work on solving it first.

---

## 3. Current two Qwen3.5 2B lines are rejected

The next implementation phase must remove the current unusable 2B choices from the application model list.

### 3.1 Community experimental line — reject

Current model line:

- `Qwen3.5-2B LiteRT-LM Q8 experimental`
- source/model ID: `paulsp94/Qwen3.5-2B-LiteRT-LM`
- file: `Qwen3.5-2B-Q8.litertlm`
- approximate size: `1,901,762,208` bytes
- associated release: `qwen35-2b-q8-v2-fixed`

Physical user result: this model fails to load.

There is also a Q4 child/related entry under this experimental line. During implementation, remove the unusable Q8 experimental entry and its associated Q4 child so no orphaned Qwen3.5 experimental option remains.

Do not spend time repairing this community artifact.

### 3.2 MCP245 frozen line — reject

Current line:

- display: `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245`
- file: `Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp245.litertlm`
- tag: `qwen35-2b-q8-4096-mcp245-frozen-v1`
- size: `4,780,966,112` bytes
- SHA256: `535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`

Earlier MCP245 diagnostics sometimes showed one tool call followed by a non-terminating continuation. The latest physical user test of the current application line is more important for product acceptance: the Agent repeatedly cycles through search and workspace reads, may write unwanted content into the workspace, and behaves similarly to the previously rejected malformed multi-tool loop.

The user has explicitly declared this model/application line unusable.

MCP246 Engine-reset experimentation does not rescue this line. The user physically reports the same practical repeated-tool behavior after the latest attempt.

Therefore:

- remove MCP245 from the model list in the next implementation phase;
- do not continue MCP246 debugging;
- retain its diagnostics only as historical evidence.

---

## 4. Conversion method to preserve from MCP238

The new 2B/4B/9B work must use the MCP238 conversion lineage as the mother implementation.

### 4.1 Source model family

Use official Qwen Hugging Face model sources:

- `Qwen/Qwen3.5-2B`
- `Qwen/Qwen3.5-4B`
- `Qwen/Qwen3.5-9B`

Do not use the current `paulsp94` preconverted 2B artifact as the source of truth.

### 4.2 Original 2B MCP238 conversion configuration

The historical 2B conversion config used:

- model ID: `Qwen/Qwen3.5-2B`
- model size: `2b`
- quantization: `q8`
- `cache_length`: `4096`
- `prefill_seq_len`: `128`
- output name similar to `/tmp/qwen35/qwen35-2b-q8-4096.litertlm`
- expected max RSS budget around `8192 MB` in the original workflow

Important: `prefill_seq_len=128` and `cache_length=4096` are different concepts. Do not accidentally convert one into the other.

### 4.3 Qwen3.5 export path

Preserve the original architectural path:

1. load official Transformers/Hugging Face Qwen3.5 model weights;
2. use the Qwen3.5-specific LiteRT-Torch export extension;
3. construct/export through `Qwen3_5StaticForCausalLM` or the equivalent matching upstream implementation;
4. apply dynamic INT8/Q8 quantization;
5. preserve `linear_only=true`;
6. preserve the `min_max` quantization algorithm unless a hard upstream incompatibility requires an explicitly documented change;
7. convert through LiteRT-Torch;
8. bundle for LiteRT-LM CPU execution;
9. preserve/restore the official model tokenizer chat template into LiteRT-LM metadata;
10. validate the final packed `.litertlm` metadata and model hash.

Historical quantization behavior:

- `QuantizationMode.DYNAMIC_INT8`
- `dtype=torch.int8`
- algorithm: `min_max`
- `linear_only=true`

### 4.4 Recurrent/delta-state compatibility patch

The historical MCP238 conversion CI patched Qwen3.5 recurrent-state export descriptors so the relevant recurrent state descriptors used:

`sequence_axis=0`

The workflow expected exactly three targeted replacements.

This is part of the MCP238 conversion lineage and should be retained unless the pinned upstream LiteRT-Torch revision already contains an equivalent correct implementation.

If upstream already fixes it, document that fact and prove equivalence before deleting the historical patch.

### 4.5 Official Qwen3.5 chat template is mandatory

This is a hard invariant.

The successful baseline conversion preserved/restored the full tokenizer-provided Qwen3.5 chat template into model metadata and verified it.

The template contains Qwen3.5-native tool behavior including tool schema injection, tool call formatting, tool responses, tool-role history, multi-step history behavior, and related control tokens.

Do not replace it with a shortened generic ChatML template.

MCP240 physically demonstrated that simplifying the template can destroy Agent behavior and produce repeated malformed tool loops.

For each of 2B/4B/9B:

- obtain the official tokenizer/chat template that belongs to that exact source model;
- embed/preserve it in the converted artifact;
- round-trip verify it after packing;
- record a template SHA256 in the build manifest.

### 4.6 Pin conversion dependencies

Historical conversion scripts pulled LiteRT-Torch from a moving `main` branch in at least part of the old workflow. That creates reproducibility risk.

For the new three-model conversion line:

- preserve MCP238 behavior;
- identify a working LiteRT-Torch revision compatible with the Qwen3.5 exporter;
- pin the exact revision/commit in the workflow;
- pin other critical converter dependencies where practical;
- write all exact revisions into the model manifest.

Do not silently track upstream `main` for a release-producing conversion job.

---

## 5. New context policy: 32K maximum, user-adjustable below it

The user has explicitly set the product target:

`32768` tokens is the maximum configurable context for the new 2B, 4B, and 9B LiteRT-LM models.

This replaces the older roadmap note that said `4096 -> 8792 -> 4B -> 9B`.

Do not normalize or carry forward the old `8792` requirement. It is superseded by this handoff.

### 5.1 Required model target

For all three new conversions:

- target exported maximum context/cache capacity: `32768`;
- expose `32768` as the model’s maximum context value to the application;
- allow the user to choose lower context values manually using the existing model settings UI.

### 5.2 Existing UI should be reused

The application already has a context-length control path (including `MAX_CONTEXT_LENGTH`/Context Window-style settings and editable numeric input/slider behavior).

Do not invent a new context settings page.

Implementation goal:

- model declares max supported context `32768`;
- existing UI uses that as the upper bound;
- user can manually select smaller values such as 4096, 8192, 16384, 32768, or another accepted value below the cap;
- runtime initialization must respect the selected value without exceeding the converted model’s actual capacity.

### 5.3 Practical memory caveat

The official Qwen3.5 family may support much larger native context at the transformer level, but the product target here is deliberately 32K for the Android LiteRT-LM build.

Do not attempt a 262K mobile LiteRT bundle in this phase.

If a specific 9B 32K export cannot load within the physical Android memory/runtime constraints, preserve the 32K conversion target for investigation and report the actual failure. Do not silently reduce only the 9B context without user approval.

---

## 6. Required three-model conversion matrix

The next context should create one reproducible conversion system that can produce all three variants.

### Model A — Qwen3.5-2B Q8 32K

Source:

`Qwen/Qwen3.5-2B`

Target:

- LiteRT-LM
- Q8 / dynamic INT8 path matching MCP238
- CPU-compatible bundle
- max context `32768`
- full official Qwen3.5 tool-aware chat template

Purpose:

- replacement for both currently unusable 2B entries;
- direct behavioral successor to MCP238;
- establishes whether MCP238 behavior survives the larger context conversion.

### Model B — Qwen3.5-4B Q8 32K

Source:

`Qwen/Qwen3.5-4B`

Target:

- same exporter family;
- same quantization methodology;
- same metadata policy;
- same official tool template policy;
- CPU-compatible LiteRT-LM bundle;
- max context `32768`.

Purpose:

- first capacity A/B comparison against 2B;
- test whether larger model capacity removes or reduces repeated final-answer generation while preserving single-tool behavior.

### Model C — Qwen3.5-9B Q8 32K

Source:

`Qwen/Qwen3.5-9B`

Target:

- same exporter family;
- same quantization methodology;
- same metadata policy;
- same official tool template policy;
- CPU-compatible LiteRT-LM bundle;
- max context `32768`.

Purpose:

- highest-capacity model in this comparison;
- test whether Agent/tool behavior and final-answer termination improve substantially with model capacity.

### Matrix isolation requirement

Prefer separate conversion jobs or a matrix where each model is isolated.

A 9B failure must not invalidate a successful 2B or 4B conversion.

Each successful job must produce its own immutable manifest and artifact identity.

---

## 7. Artifact publication rules

The MCP244 delivery incident proved that mutable repacking and `--clobber` can corrupt reproducibility.

For each new model:

1. create the final `.litertlm` exactly once for the release candidate;
2. compute full-file SHA256 and byte size;
3. record the source model revision;
4. record converter revisions;
5. record chat-template SHA256;
6. record context/cache setting `32768`;
7. record quantization settings;
8. split only if GitHub release size limits require multipart delivery;
9. publish under a unique immutable release tag;
10. do not repack the same logical release later under the same tag;
11. do not `--clobber` an already accepted model release;
12. after upload, download/reconstruct the published bytes and verify the full SHA256 again;
13. only then point the application allowlist at that artifact.

Each model should have an explicit machine-readable result JSON/manifest under `docs/`.

---

## 8. Application integration requirements after model conversion

Only after the three model artifacts are known should application integration proceed.

Implementation phase requirements:

1. remove `Qwen3.5-2B LiteRT-LM Q8 experimental`;
2. remove its associated Q4 child/related experimental entry;
3. remove `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245`;
4. add the new clean 2B Q8 32K entry;
5. add 4B Q8 32K;
6. add 9B Q8 32K;
7. preserve existing model settings UI;
8. set each new model’s maximum context/config upper bound to `32768`;
9. keep user-selected lower context values supported through the existing control;
10. prefer CPU execution unless later physical evidence supports another backend;
11. do not carry MCP246’s special continuation Engine-reset behavior forward as a model-specific requirement without fresh evidence;
12. do not add custom runtime/JNI/AAR code to make the models load unless separately authorized.

The target model list after cleanup should contain one clean Qwen3.5 2B line plus 4B and 9B, rather than multiple confusing 2B experimental/frozen variants.

---

## 9. Physical acceptance test for all three models

Use the same test pattern for 2B, 4B, and 9B so results are comparable.

Minimum gates per model:

### Gate A — download and identity

- model downloads successfully;
- reconstructed full SHA256 matches manifest;
- model appears once in the intended application list.

### Gate B — load

- model loads successfully on the physical Android device;
- no immediate process crash;
- selected context is honored;
- ordinary chat can generate a normal answer.

### Gate C — single web tool behavior

Use an explicit Agent request requiring one web/network lookup.

Preferred behavioral target:

1. model chooses the correct tool;
2. one tool invocation occurs;
3. tool succeeds;
4. tool result is returned;
5. model produces user-visible final answer;
6. no search/read-workspace/search/read-workspace loop;
7. no unsolicited workspace write;
8. no tool-call markup leaks into final answer.

### Gate D — termination/repetition comparison

Record whether final answer:

- terminates normally;
- repeats coherent sections but remains on topic;
- enters tool loop;
- writes unsolicited workspace content;
- requires manual Stop.

The central experiment is model capacity:

- if 4B/9B terminate normally while 2B repeats, treat the 2B repetition as mainly a model-capability ceiling;
- if all three repeat similarly, investigate LiteRT/Qwen3.5 termination/runtime behavior after this comparison;
- if larger models introduce new malformed tool behavior, compare template/metadata and conversion output before modifying runtime code.

---

## 10. Explicitly rejected routes

Do not restart any of these without user authorization:

- generic/simplified ChatML replacement for the Qwen3.5 tool template;
- `RepetitionPenaltyConfig` in the current LiteRT-LM 0.15 padded-vocab path;
- custom LiteRT-LM JNI/AAR patching;
- native logits surgery;
- NoRepeatNgram hard suppression;
- forced output truncation/watchdog as a substitute for natural completion;
- repeated Engine-reset experiments on MCP245/MCP246;
- continued repair of `paulsp94/Qwen3.5-2B-LiteRT-LM`;
- repeated micro-iterations on the rejected MCP245 artifact;
- repacking/overwriting an already published immutable model release.

Historical reason summary:

- MCP240 simplified template caused severe tool protocol regression;
- LiteRT-LM repetition processor failed logits-shape validation;
- MCP242 custom runtime caused severe random process crashes;
- MCP244 mutable model release was overwritten and broke SHA expectations;
- MCP245/MCP246 current physical behavior is rejected by the user due repeated tool/workspace loops.

---

## 11. Safety and collaboration constraints

These remain mandatory:

- work only on `experimental` unless the user explicitly changes branch scope;
- leave `main` untouched;
- preserve `golden/mcp-223-product-stable` as stable reference;
- accessibility is a product acceptance condition;
- mobile/Android physical behavior outranks desktop-only assumptions;
- do not require crash logs when the app process dies;
- native/JNI/AAR/runtime surgery needs very high confidence and explicit authorization;
- use GitHub Actions/Release for large artifacts;
- do not publish mutable model bytes under reused tags;
- provide a real APK/model download link only after actual successful publication;
- do not claim success from CI alone when physical testing is required.

---

## 12. Next-context execution order

The next context should follow this exact order unless the user changes it:

### Phase 1 — read-only verification

1. read this handoff;
2. read `docs/ACTIVE_HANDOFF_QWEN35_EXPERIMENTAL.md`;
3. inspect MCP238 conversion scripts/config/workflows and exact source revisions;
4. inspect current model allowlist and context UI paths;
5. verify official Qwen3.5 2B/4B/9B model identifiers and compatible exporter support;
6. identify/pin the exact LiteRT-Torch revision to use.

### Phase 2 — conversion workflow implementation

1. create clean reproducible conversion configs for 2B/4B/9B;
2. set `cache/context=32768` target for all three;
3. preserve MCP238 quantization/export/template behavior;
4. create isolated jobs/matrix;
5. add immutable artifact manifests and post-upload reconstruction verification.

### Phase 3 — convert models

1. run 2B conversion;
2. run 4B conversion;
3. run 9B conversion;
4. retain successful outputs independently if one job fails;
5. record hashes, sizes, source revisions, converter revisions, template hashes.

### Phase 4 — application model-list cleanup/integration

1. remove rejected experimental Q8/Q4 line;
2. remove MCP245 line;
3. add new 2B/4B/9B lines;
4. expose max context 32768 to existing UI;
5. preserve user-selectable lower values;
6. do not add new runtime hacks.

### Phase 5 — APK build and physical testing

Build a signed APK only after the new model artifacts and allowlist entries are deterministic.

Then physically test the same Agent request on 2B/4B/9B and compare behavior.

---

## 13. Definition of success for the next major milestone

The milestone is reached when:

- the two rejected current Qwen3.5 2B lines are gone;
- a clean MCP238-lineage Qwen3.5-2B Q8 32K model is published and loads;
- a Qwen3.5-4B Q8 32K model is published and loads;
- a Qwen3.5-9B Q8 32K model is published and loads, or there is a precisely documented physical resource blocker;
- all three use official model-specific Qwen chat templates;
- all three expose maximum context 32768 with user-adjustable lower values;
- at least the 4B/9B comparison tells us whether larger model capacity fixes the 2B final-answer repetition;
- no custom JNI/AAR/runtime workaround is required to reach that comparison.

---

## 14. One-sentence handoff

Abandon the MCP245/MCP246 repair line, restore MCP238 as the only behavioral/conversion baseline, remove the two unusable current 2B choices, and build clean official-source Qwen3.5 2B/4B/9B Q8 LiteRT-LM models with the MCP238 conversion method and a 32K maximum context exposed through the existing user-adjustable context control.