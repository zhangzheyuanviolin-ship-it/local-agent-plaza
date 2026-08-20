# Local Agent Plaza — Qwen3.5 experimental live handoff

Last checkpoint: 2026-08-20 19:55+08:00 — MCP245 physical post-tool stall diagnosed; MCP246 signed APK built/released
Branch: `experimental`
Default branch `main` must remain untouched.
Stable product reference: `golden/mcp-223-product-stable`.

## Mandatory collaboration / safety rules

1. This file is the authoritative continuity point. Update it or a timestamped checkpoint before/after material changes and before long builds.
2. Do not send intermediate build chat reports; continue through signed APK + real Release/artifact link, then report once.
3. Do not touch `main` for experiments.
4. High-blast-radius native/JNI/AAR/runtime surgery requires ~97% confidence and explicit justification. MCP242 custom runtime caused global random process crashes and is permanently rejected unless explicitly re-authorized.
5. Do not add host forced truncation, repetition-watchdog cancellation, or hard NoRepeatNgram unless explicitly authorized.
6. Large APK/model delivery uses GitHub Release/Actions.
7. Product sequence remains: stabilize 2B/4096 -> exact context `8792` -> 4B -> 9B. Do not normalize 8792 to 8192.

## Physically proven baseline: MCP238/MCP239

Original model:
- `Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`
- tag `qwen35-2b-q8-4096-v1`
- size `4,780,966,112`
- SHA256 `d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73`
- topK 20 / topP 0.8 / temperature 0.6 / maxTokens 1536 / context 4096 / CPU in MCP239.

Physical fact: one coherent COMPAT network tool call completed, followed by one continuation. That continuation ran ~257 s / 2868 callbacks / 4476 visible chars and repeated coherent final sections. Therefore the original unresolved defect was a single post-tool continuation that did not naturally terminate cleanly.

## MCP240–MCP243 findings

MCP240 replaced the original ~154-line official Qwen3.5 tool-aware Jinja with a ~20-line generic ChatML template. That deleted native tool schema injection, `<tool_call><function=...><parameter=...>`, `message.tool_calls`, `<tool_response>`, tool-role handling, and multi-step history. It also experimented with repetition penalty and a fresh-Engine reset.

The repetition processor itself failed before token 1 due LiteRT-LM 0.15 logits/vocab validation. MCP241 removed it. MCP242 then tried a custom LiteRT-LM JNI/AAR and caused severe global random crashes; do not use that route.

MCP243 restored the official Maven runtime but still used the simplified-template model. Physical Agent test showed 9 LLM passes / 8 continuations, repeated search/file loops, and tool-call leakage. Direct model diff plus physical A/B evidence gives >97% confidence that the simplified template caused this NEW malformed/repeated-tool regression.

## MCP244/MCP245 model correction and delivery fix

MCP244 restored the complete original official tool-aware Jinja byte-for-byte and added natural stop `248046` beside `248044`. Its first delivery failed because the same mutable Release tag was later `--clobber`-overwritten by a nondeterministic repack, causing APK expected SHA `7cdf8232...` while published parts reconstructed to `535c3296...`.

MCP245 froze the verified current bytes under a new one-time model tag with no repack and independently re-downloaded/reconstructed the published release before APK build.

Current frozen model reused by MCP246:
- display `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245`
- file `Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp245.litertlm`
- tag `qwen35-2b-q8-4096-mcp245-frozen-v1`
- size `4,780,966,112`
- SHA256 `535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`
- template SHA256 `273d8e0e683b885071fb17e08d71e5f2a5ddfb5309756181681de4f5a1822d80`
- stops `[248044, 248046]`
- full original official tool-aware template preserved byte-for-byte.

## MCP245 physical result — decisive current diagnostic

Physical MCP245 result:
- model download SUCCESS;
- model load SUCCESS;
- ordinary AI chat answers normally;
- Agent initial pass produced a coherent tool call;
- exactly one `tool.run_js` executed successfully;
- after tool result, UI appeared to hang with no final answer until manual Stop.

Diagnostic:
`docs/model_diagnostic_mcp-245_2026-08-20_113748.txt`
request `bb901454`.

Hard facts:
- status `STOPPED` only because user manually stopped;
- total request `147275.04 ms`;
- `llm_pass_count=2`, `continuation_count=1`;
- initial TTFT `3121.98 ms`, initial generation `4462.53 ms`, initial output `81 chars`;
- one tool only: `tool.run_js`, success=true, exec `4573.11 ms`;
- continuation input `2953 chars`;
- continuation first callback TTFT `8942.79 ms`;
- continuation then emitted `1687 callbacks`, all visible-channel, totaling `10127 visible chars` during `128426.65 ms`;
- continuation never reached `onDone` before manual Stop, so top-level `continuation_1_output_chars=0` remained unfinalized;
- no runtime error and no process crash; PSS remained roughly 3.4–3.9 GB after tool/continuation.

Interpretation: continuation prefill is working and decode starts. The model actively generates for minutes but does not reach natural completion. The MCP240/MCP243 malformed repeated-tool regression appears fixed in this run because there is exactly one coherent tool call and one continuation. The remaining defect reconnects directly to the old MCP238 post-tool nontermination/repetition problem.

Checkpoint:
`docs/checkpoints/QWEN35_MCP245_PHYSICAL_POST_TOOL_STALL_2026-08-20_1939.md`

## Why MCP246 targets shared-Engine recurrent state

Current MCP245 COMPAT lifecycle closes the Conversation and creates each top-level/tool-continuation Conversation on the SAME loaded Engine.

Fresh upstream LiteRT-LM evidence documents two directly matching hazards:
- issue #3165: running-state models including Qwen3.5 gated-delta hybrids can retain recurrent state across a new Conversation on a shared Engine under v0.15.0 because the step counter can rewind while recurrent state buffers remain stale;
- issue #2256: Qwen-family tool calling can wedge after the first successful tool call when `createConversation()` does not clear engine-level state; the reported shipping workaround is `engine.close()` + reload between turns.

Internal evidence also exists: MCP240's app patch already implemented an official-API Engine rebuild specifically for this Qwen3.5 recurrent-state hazard. MCP243 physically ran multiple fresh-Engine continuations without global crashes; MCP242's instability came from its custom JNI/AAR, not from the official Engine reload lifecycle.

## MCP246 — CURRENT PHYSICAL-TEST CANDIDATE

Purpose: one isolated lifecycle change on official LiteRT-LM only.

For the exact MCP245 Qwen3.5 model, ONLY when `prepared.freshConversationReason == tool_continuation`:
1. close current Conversation;
2. close current official Engine;
3. recreate official `Engine(instance.engineConfig)` and `initialize()`;
4. create the continuation Conversation on that zero-state Engine.

Top-level Agent passes and ordinary AI chat retain MCP245 lifecycle. The frozen MCP245 model is unchanged and does NOT need to be downloaded again.

Strict invariants:
- official Maven LiteRT-LM 0.15.0;
- patch chain MCP238 + MCP239 + MCP245 + MCP246;
- same frozen model SHA `535c3296...`;
- CPU;
- topK 20 / topP 0.8 / temperature 0.6;
- maxTokens 1536 / context 4096;
- no RepetitionPenaltyConfig;
- no NoRepeatNgram;
- no output watchdog;
- no forced truncation;
- no custom JNI/AAR;
- no model repack;
- no top-level Engine reset.

Workflow run: `32365370332` — SUCCESS.
Result: `docs/mcp246_apk_result.json`.
Final checkpoint: `docs/checkpoints/QWEN35_MCP246_FINAL_2026-08-20.md`.

APK:
- versionName `1.0.14-mcp.246`
- versionCode `346`
- package `com.localagent.plaza.mcp`
- SHA256 `8dfd2b882f419ef85412b8b55a366147b1fb409e596bf9cf24cb8dced6c6c17a`
- Release: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp246-qwen35-continuation-engine-reset/local-agent-plaza-1.0.14-mcp.246.apk`
- Artifact ID `9405392103`
- Artifact: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32365370332/artifacts/9405392103`

## Exact next physical gate

Install MCP246 over MCP245. Do NOT delete or re-download the 4.78 GB MCP245 model.

Repeat the same explicit Agent web-search request. Capture diagnostic if behavior remains abnormal.

Interpretation:
- one tool + continuation now finishes with normal final answer => shared-Engine recurrent state was the remaining blocker and 2B/4096 can be closed;
- one tool + continuation still emits indefinitely => Engine recurrent-state reset was insufficient; do not escalate to JNI/AAR. Re-evaluate current LiteRT-LM/Qwen3.5 natural termination and prompt serialization, and consider the safe product ceiling as coherent tool calling + manual Stop unless a new low-risk, strongly evidenced fix exists;
- malformed repeated tool calls return => compare against MCP245 immediately because MCP246 intentionally changed only continuation Engine lifecycle.

Do not proceed to 8792/4B/9B until MCP246 physical result is known.
