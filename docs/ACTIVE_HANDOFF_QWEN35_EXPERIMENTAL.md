# Local Agent Plaza — Qwen3.5 experimental live handoff

Last checkpoint: 2026-08-20 15:40+08:00
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
MCP241 is the conservative functional rollback reference for the next build.

## MCP242 — REJECTED / DO NOT USE

MCP242 attempted to retain real presence/repetition penalty by rebuilding LiteRT-LM v0.15.0 arm64 JNI and patching `RepetitionPenaltyProcessor` to accept padded model vocabularies.

The user installed MCP242 and observed severe process-level instability: frequent random app crashes/returns to launcher at multiple unrelated points, including app launch, entering the model list, and entering the Qwen chat page.

There is no app diagnostic log because the process dies outright. Do not ask the user for a crash log as a prerequisite.

Given the timing and blast radius, the custom JNI/AAR runtime is now treated as the primary regression suspect. This entire custom-runtime route is rejected for product use. The fact that its APK compiled, signed, and contained the intended binary does not establish runtime safety.

MCP242 files may remain in repository history for forensic reference, but no future product workflow should apply `patch_mcp242_qwen35_compatible_repetition.py` or package `litertlm-android-0.15.0-mcp242.aar` unless the user explicitly re-authorizes low-level runtime experimentation.

## Official LiteRT-LM status checked at this checkpoint

Current official upstream `main` still has the same strict check in `runtime/components/logits_processor/repetition_penalty_processor.cc`:

`logits_dims[2] != vocab_size_` -> error `Logits dimensions must be [batch_size, 1, vocab_size].`

Therefore there is no verified official upstream runtime fix available at this checkpoint that can safely restore the repetition processor for this padded-vocab Qwen3.5 export.

## Current mandated action — MCP243 safe rollback

Create MCP243 as an overwrite-installable rollback (`versionCode` > 342) that is intentionally equivalent in Qwen behavior to MCP241:

- official Maven `libs.litertlm` only;
- NO custom LiteRT-LM AAR/JNI;
- apply MCP238 + MCP239 + MCP240 + MCP241 patches only;
- `RepetitionPenaltyConfig` absent;
- `presencePenalty`/`repetitionPenalty` runtime injection absent;
- no `NoRepeatNgramConfig`;
- no per-call `maxOutputToken` hard cap;
- no repetition watchdog;
- retain repaired MCP240 model, CPU, ChatML, stop IDs 248044/248046, top_k=20/top_p=1.0/temp=1.0;
- reuse the model already downloaded on device;
- sign with the existing release key;
- verify package `com.localagent.plaza.mcp`;
- use versionName `1.0.14-mcp.243`, versionCode `343`;
- publish permanent GitHub Release APK plus Actions artifact;
- update this file and a machine-readable `docs/mcp243_apk_result.json` only after the build actually succeeds.

This is a deliberately low-risk rollback. Do not introduce additional fixes into MCP243.

### MCP243 live build checkpoint

Workflow: `.github/workflows/mcp243_safe_runtime_rollback.yaml`
Run ID: `32345060689`
Run URL: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32345060689`
Workflow source commit: `3e1acf4165724b6dffcca567ce08ae4f295ea57b`

At this checkpoint the following gates have already passed:
- conservative source boundary check: official `implementation(libs.litertlm)` and `litertlm = "0.15.0"`;
- no MCP242 custom AAR/JNI dependency;
- repaired MCP240 model release availability;
- MCP238/MCP239/MCP240/MCP241 patch application;
- MCP243 product invariants, including complete absence of `RepetitionPenaltyConfig`, presence/frequency injection, no-repeat ngram, and per-call hard output cap;
- release signing prerequisites.

Current active step at checkpoint: `Build MCP243 release APK` (Gradle assembleRelease). If the session dies now, inspect run `32345060689`, finish from that run, then update this file and `docs/mcp243_apk_result.json` only after signed APK/release publication succeeds.

## Later work after MCP243 is physically verified stable

Only after the user confirms the app no longer crashes and Agent can respond/call tools should repetition-loop mitigation be revisited. Safe avenues should prioritize model metadata/template behavior, official-runtime capabilities, or other changes with strong evidence. Do not replace the native inference engine/runtime again without explicit authorization and ~97% confidence.

The planned exact longer context value is `8792` (do not normalize it to 8192), and it is a later phase only after the 2B/4096 behavior is stable. Then 4B, then 9B CPU testing.
