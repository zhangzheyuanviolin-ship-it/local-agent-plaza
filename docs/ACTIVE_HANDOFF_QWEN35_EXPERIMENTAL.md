# Local Agent Plaza — Qwen3.5 experimental live handoff

Last checkpoint: 2026-08-20 — MCP243 build/release completed
Branch: `experimental`
Default branch `main` must remain untouched.
Stable product reference branch: `golden/mcp-223-product-stable`.

## Purpose of this file

This is the live handoff/checkpoint for the current Qwen3.5 LiteRT-LM integration work. It MUST be updated at material milestones so that, if the active agent/session is killed at any point, another agent can read this file plus the referenced repo files and continue without asking the user to reconstruct history.

## User-mandated development rules

1. Do not touch `main` for experiments.
2. For any high-risk low-level change (runtime/JNI/AAR/ABI/native engine replacement, model binary surgery beyond already validated metadata repair, etc.), do not proceed unless confidence is at least ~97% that it will work and not destabilize the whole app.
3. Prefer conservative product usability over clever root-cause experiments.
4. Do not add host-side forced truncation, automatic repetition watchdog cancellation, or hard no-repeat ngram unless the user explicitly authorizes it.
5. Large APK/model delivery must use GitHub Release or GitHub Actions artifact links, never sandbox links.
6. Keep this handoff updated before/after material changes and before long builds.
7. The user does not want intermediate chat reports during builds. Continue until a real signed APK/release link exists, then report once.

## Device/product facts already established

- Qwen3.5-2B GGUF on the user's iPhone can use tools and naturally summarize, so the 2B model capability itself is not considered the root problem.
- Android target uses LiteRT-LM and CPU for this model. GPU path is known incompatible and is deprioritized.
- Original MCP238/MCP239 Qwen3.5 LiteRT-LM model worked on CPU and could call the network tool, but the final continuation after tool results could enter very long coherent section-level repetition.
- The bad repetition was one continuation decode after one tool call, not an outer Agent/tool loop.

## Model artifact currently on device and to be reused

Display name: `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP240`

Model file:
`Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp240.litertlm`

Release tag:
`qwen35-2b-q8-4096-mcp240-natural-v2`

Size:
`4,780,966,112` bytes

SHA-256:
`119bdfb5464b9c3746b26d43211474fcfcbbe8a8c36693e3c0413b8d5b9d7d0f`

The model contains the repaired stable ChatML template and both natural stop IDs `248044` and `248046`. Do not force another 4.78 GB redownload unless the model binary itself is intentionally changed in a later authorized phase.

## MCP240

MCP240 repaired model-side ChatML/stop metadata, forced CPU, and changed Qwen conversation reset to rebuild the Engine at the fresh COMPAT boundary to avoid recurrent-state contamination risk.

It also injected `RepetitionPenaltyConfig(repetitionPenalty=1.0, presencePenalty=2.0)` only for Qwen Agent/COMPAT generation.

Real-device result: normal AI chat could generate, but Agent could fail before token 1 with:
`Logits dimensions must be [batch_size, 1, vocab_size].`

## MCP241

MCP241 removed `RepetitionPenaltyConfig` entirely while retaining:
- official Maven LiteRT-LM 0.15 runtime,
- repaired MCP240 model,
- CPU,
- sampler top_k=20, top_p=1.0, temperature=1.0,
- dual natural stop IDs,
- no hard truncation,
- no repetition watchdog,
- MCP240 fresh-Engine Qwen reset.

Build metadata: `docs/mcp241_apk_result.json`.
MCP241 is the conservative functional rollback reference.

## MCP242 — REJECTED / DO NOT USE

MCP242 attempted to retain real presence/repetition penalty by rebuilding LiteRT-LM v0.15.0 arm64 JNI and patching `RepetitionPenaltyProcessor` to accept padded model vocabularies.

The user installed MCP242 and observed severe process-level instability: frequent random app crashes/returns to launcher at multiple unrelated points, including app launch, entering the model list, and entering the Qwen chat page.

There is no app diagnostic log because the process dies outright. Do not ask the user for a crash log as a prerequisite.

Given the timing and blast radius, the custom JNI/AAR runtime is treated as the primary regression suspect. This entire custom-runtime route is rejected for product use. The fact that its APK compiled, signed, and contained the intended binary does not establish runtime safety.

MCP242 files may remain in repository history for forensic reference, but no future product workflow should apply `patch_mcp242_qwen35_compatible_repetition.py` or package `litertlm-android-0.15.0-mcp242.aar` unless the user explicitly re-authorizes low-level runtime experimentation.

## Official LiteRT-LM status checked before MCP243

Current official upstream `main` still has the same strict check in `runtime/components/logits_processor/repetition_penalty_processor.cc`:

`logits_dims[2] != vocab_size_` -> error `Logits dimensions must be [batch_size, 1, vocab_size].`

Therefore no verified official upstream runtime fix was available that could safely restore the repetition processor for this padded-vocab Qwen3.5 export.

## MCP243 — CURRENT PHYSICAL-TEST CANDIDATE

MCP243 was built as an overwrite-installable emergency rollback intentionally equivalent to MCP241 Qwen behavior.

Workflow: `.github/workflows/mcp243_safe_runtime_rollback.yaml`
Run ID: `32345060689`
Workflow source commit: `3e1acf4165724b6dffcca567ce08ae4f295ea57b`
Result metadata: `docs/mcp243_apk_result.json`

Build conclusion: SUCCESS. Every workflow step passed, including source-boundary validation, model-release validation, patch invariants, Gradle release build, APK signing/identity verification, artifact upload, permanent GitHub Release upload, and machine-readable result publication.

MCP243 product invariants:
- runtime: official Maven LiteRT-LM `0.15.0`;
- custom LiteRT-LM AAR: false;
- custom LiteRT-LM JNI: false;
- applies MCP238 + MCP239 + MCP240 + MCP241 only;
- `RepetitionPenaltyConfig`: absent;
- presence/repetition runtime injection: absent;
- `NoRepeatNgramConfig`: absent;
- per-call `maxOutputToken` hard cap: absent;
- repetition watchdog: absent;
- model: existing repaired MCP240 Qwen3.5-2B Q8 4096 file;
- model redownload required: false;
- backend: CPU forced;
- sampler: top_k 20, top_p 1.0, temperature 1.0;
- natural stop IDs: 248044 and 248046;
- package: `com.localagent.plaza.mcp`;
- versionName: `1.0.14-mcp.243`;
- versionCode: `343`.

Final APK:
`local-agent-plaza-1.0.14-mcp.243.apk`

SHA-256:
`59762f9794b26d469ebc2a4ddc2b8bb22e5b896d2e362eee28e359c78f69ba8b`

Permanent Release URL:
`https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp243-safe-official-runtime-rollback/local-agent-plaza-1.0.14-mcp.243.apk`

Actions artifact ID: `9397941131`
Artifact URL:
`https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32345060689/artifacts/9397941131`

### Current exact stopping point

Engineering/build work for MCP243 is complete. The next required evidence is the user's physical-device test of MCP243. Do not make another inference-runtime change before that result.

First physical-test goal: verify that the global random process crashes introduced by MCP242 disappear after overwrite-installing MCP243. Then verify ordinary AI chat, Agent first response, tool invocation, tool-result continuation, and whether the original long repetition still occurs.

If MCP243 is globally stable but the old long repetition persists, record that result and continue only with conservative/high-confidence mitigation. Do not reintroduce a custom JNI/AAR runtime.

## Later work after MCP243 is physically verified stable

Only after the user confirms the app no longer crashes and Agent can respond/call tools should repetition-loop mitigation be revisited. Safe avenues should prioritize model metadata/template behavior, official-runtime capabilities, or other changes with strong evidence. Do not replace the native inference engine/runtime again without explicit authorization and ~97% confidence.

The planned exact longer context value is `8792` (do not normalize it to 8192), and it is a later phase only after the 2B/4096 behavior is stable. Then 4B, then 9B CPU testing.

## MCP244 — CURRENT PHYSICAL-TEST CANDIDATE

MCP244 build/release completed successfully after MCP243 device diagnostics identified the MCP240 simplified Jinja template as the tool-orchestration regression. The original MCP238 bundle was reconstructed and SHA-verified, and its full official Qwen3.5 tool-aware Jinja template was preserved byte-for-byte. Only natural stop ID 248046 was added beside original 248044. ExecutorMetadata was retained.

Runtime remains official Maven LiteRT-LM 0.15.0. No custom JNI/AAR, runtime repetition penalty, hard truncation, repetition watchdog, or NoRepeatNgram is used. CPU and the Qwen fresh-Engine reset remain.

Version: `1.0.14-mcp.244` / versionCode `344`.
APK SHA256: `98346dc8421846bfd5625e184d1441a3ff0b33eaef82c0301a6cbbbf3a102201`.
APK Release: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp244-qwen35-official-tool-template/local-agent-plaza-1.0.14-mcp.244.apk`.
Actions artifact: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32349526045/artifacts/9399705980` (ID `9399705980`).

Model: `Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp244.litertlm`.
Model release: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/tag/qwen35-2b-q8-4096-mcp244-official-tool-template-v1`.
Model size: `4780966112` bytes.
Model SHA256: `7cdf8232b949d184e3cee81694cea7b21bac36a538d495d1a83581a4ce2c5d44`.
Model redownload is REQUIRED because the MCP240 on-device file contains the wrong simplified template.
Official tool-template SHA256: `273d8e0e683b885071fb17e08d71e5f2a5ddfb5309756181681de4f5a1822d80`.

Exact stopping point: engineering/build work is complete. Await physical-device test. First verify model download/load, ordinary chat, then Agent search/tool call, tool-result continuation, absence of search/file-read loops or leaked function syntax, and finally whether long final-answer repetition remains. Do not proceed to 8792/4B/9B until this test result is recorded.
