# Local Agent Plaza — Qwen3.5 experimental live handoff

Last checkpoint: 2026-08-20 16:55+08:00 — MCP244 strict final build/release SUCCESS
Branch: `experimental`
Default branch `main` must remain untouched.
Stable product reference: `golden/mcp-223-product-stable`.

## Mandatory collaboration / safety rules

1. This file is the live continuity point. Update it or a timestamped `docs/checkpoints/` file before and after material changes and before long builds, so another agent can resume if the active session dies.
2. Do not send intermediate chat reports during builds. Continue to a signed APK + real Release/artifact link, then report once.
3. Do not touch `main` for experiments.
4. Any high-risk low-level change (JNI/AAR/native inference engine/runtime replacement, ABI surgery, unproven model-binary surgery) requires ~97% confidence and explicit justification. MCP242 proved that build success does not establish runtime safety.
5. Do not add host forced truncation, automatic repetition watchdog cancellation, or hard NoRepeatNgram unless the user explicitly authorizes it.
6. Large APK/model delivery uses GitHub Release/Actions, not sandbox.
7. Planned later sequence remains: first stabilize 2B/4096, then exact context `8792`, then 4B, then 9B. Do not normalize 8792 to 8192.

## Physically proven historical baseline: MCP238/MCP239

Original model:
- display: `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza`
- file: `Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`
- tag: `qwen35-2b-q8-4096-v1`
- size: `4,780,966,112`
- SHA256: `d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73`
- context 4096; maxTokens 1536; topK 20; topP 0.8; temperature 0.6; CPU in MCP239.

Physical-device fact: this bundle completed a coherent COMPAT network tool call and one continuation. The continuation then ran ~257 s / 2868 callbacks / 4476 visible chars and repeated coherent final-answer sections. The bad repetition was one continuation decode after one tool call, not an outer Agent/tool loop.

Diagnostic: `docs/agent_perf_diagnostic_mcp-238_2026-08-20_031044.txt`.

## MCP240/MCP241 findings

MCP240 repacked the model and replaced its full original Qwen3.5 Jinja template with a simplified ~20-line generic ChatML template, added stop 248046 beside 248044, changed sampler/max output, added fresh-Engine reset, and injected repetition/presence penalty.

The repetition processor failed before token 1 in Agent due LiteRT-LM 0.15 logits-shape constraints. MCP241 removed that processor.

More importantly, later byte-level comparison proved the model-template replacement removed the model's native tool semantics. The original ~154-line template includes tool schema injection, required `<tool_call><function=...><parameter=...>` format, `message.tool_calls`, `<tool_response>` serialization, tool-role handling and multi-step tool history. MCP240's simplified template deleted those mechanisms.

Comparison evidence:
- `docs/qwen35_mcp238_vs_mcp240_metadata_compare.json`
- `docs/checkpoints/QWEN35_MCP243_ROOT_CAUSE_AND_MCP244_PLAN_2026-08-20.md`
- `docs/checkpoints/QWEN35_MCP244_ISOLATION_CORRECTION_2026-08-20_1636.md`

## MCP242 — rejected permanently for product use

MCP242 rebuilt/patched LiteRT-LM 0.15 arm64 JNI to try to retain repetition penalty with padded vocab logits. Physical device showed severe random process-level crashes at app launch, model list, and chat entry. No app diagnostic existed because the process died outright.

Do not use MCP242 custom AAR/JNI route again unless explicitly re-authorized under the high-confidence rule.

## MCP243 physical-device result

MCP243 restored official Maven LiteRT-LM 0.15 and eliminated the MCP242 global random crashes.

However it still used the MCP240 simplified-template model. Physical diagnostic:
`docs/model_diagnostic_mcp-243_2026-08-20_081924.txt`

Observed behavior:
- 9 LLM passes / 8 continuations in one user turn;
- repeated tool/continuation behavior;
- user observed search -> file/read failure -> search -> file/read -> ... loops;
- sometimes raw tool-call function text leaked into final output.

This was the first meaningful physical Agent test of the MCP240 simplified-template model after the repetition-processor crash was removed.

## Root-cause confidence for NEW tool regression

Current confidence that MCP240's simplified template caused the NEW repeated-tool/tool-call-leak regression: **>97%**.

Evidence chain:
1. MCP238 same converted weights/package lineage + original full tool-aware template physically completed tool use.
2. MCP243 with MCP240 simplified template showed repeated/malformed tool behavior.
3. Direct unpacked bundle diff shows the major functional metadata change is the Jinja template; ExecutorMetadata is present in both, while model.toml semantic structure differs only by packager UUID/timestamp.
4. The deleted template sections are exactly the sections that define tool schema, tool calls, tool responses, roles and multi-step semantics.

This high confidence applies to the NEW tool regression. It does not prove that adding stop 248046 will cure the ORIGINAL long final-answer repetition.

## MCP244 — CURRENT PHYSICAL-TEST CANDIDATE (STRICT FINAL)

Important: use only the strict-final MCP244 product described here. It supersedes any earlier MCP244 draft APK that may have been built with MCP240/MCP241 app patches.

Final run: `32350287008` (successful retry attempt)
Result: `docs/mcp244_apk_result.json`
Checkpoint: `docs/checkpoints/QWEN35_MCP244_FINAL_STRICT_2026-08-20.md`

APK:
- versionName `1.0.14-mcp.244`
- versionCode `344`
- package `com.localagent.plaza.mcp`
- SHA256 `0cd9c114a062a3cbb986f3a0f37d8e23bdb20f408cd09bcba647abb4b1541776`
- Release: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp244-qwen35-official-tool-template/local-agent-plaza-1.0.14-mcp.244.apk`
- Artifact: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32350287008/artifacts/9399915719`

MCP244 model:
- display: `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP244`
- file: `Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp244.litertlm`
- tag: `qwen35-2b-q8-4096-mcp244-official-tool-template-v1`
- size: `4,780,966,112`
- SHA256: `7cdf8232b949d184e3cee81694cea7b21bac36a538d495d1a83581a4ce2c5d44`
- original tool template SHA256: `273d8e0e683b885071fb17e08d71e5f2a5ddfb5309756181681de4f5a1822d80`
- stop IDs: `[248044, 248046]`
- new model download required: YES.

Strict app boundary:
- official Maven LiteRT-LM 0.15.0 only;
- app patch chain exactly MCP238 + MCP239 + MCP244;
- MCP240 app patch absent;
- MCP241 app patch absent;
- no custom JNI/AAR;
- no fresh-Engine rebuild;
- resident Engine + ordinary fresh Conversation, matching the physically tool-working baseline;
- topK 20 / topP 0.8 / temperature 0.6;
- maxTokens 1536 / context 4096;
- CPU forced;
- no RepetitionPenaltyConfig;
- no NoRepeatNgram;
- no repetition watchdog;
- no host forced truncation.

Model construction boundary:
- source is the SHA-verified physically tool-proven MCP238 bundle;
- original full Qwen3.5 tool-aware Jinja is preserved byte-for-byte;
- ExecutorMetadata preserved;
- ONLY functional model-metadata change is adding stop token 248046 beside 248044;
- weights/executor graph/native runtime are untouched.

## Exact next step / decision gate

User should install the strict-final MCP244 APK, delete/ignore the broken MCP240 model, and download the NEW MCP244 model (~4.78 GB). First test explicit web search/tool use. Then observe final-answer stopping.

Expected interpretation:
- If tool flow returns to MCP238/MCP239 coherence, the NEW tool regression is closed and the template diagnosis is confirmed.
- If final output also stops naturally, 2B/4096 reaches the intended product baseline.
- If tools are coherent but the old long final-answer repetition remains, the safe current Qwen3.5-2B tool ceiling is the MCP238/MCP239 behavior represented by MCP244, with manual Stop if needed. Do not resume native-runtime surgery. At that point investigate only low-blast-radius, strongly evidenced options before considering 8792/4B/9B.
- If tool behavior is still malformed under MCP244, preserve the diagnostic and re-evaluate the COMPAT prompt/orchestration against the physically proven MCP238 run before any new model/runtime change.
