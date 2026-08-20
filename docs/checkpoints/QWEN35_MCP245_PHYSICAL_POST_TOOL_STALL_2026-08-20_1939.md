# MCP245 physical-device post-tool continuation stall — 2026-08-20 19:39+08:00

User physical result on `1.0.14-mcp.245`:
- MCP245 model downloaded successfully and loaded successfully.
- Ordinary AI chat can answer normally.
- In Agent/COMPAT, the initial model pass produced a valid tool call and `tool.run_js` completed successfully.
- After the tool result was fed back, the UI appeared to produce no final answer and remained running until the user manually stopped it.

Diagnostic: `docs/model_diagnostic_mcp-245_2026-08-20_113748.txt`.

Hard facts from diagnostic request `bb901454`:
- status `STOPPED` after user manual stop.
- total request time `147275.04 ms`.
- `llm_pass_count=2`, `continuation_count=1`, so there was exactly one tool continuation, not an outer repeated-tool loop.
- initial pass: TTFT `3121.98 ms`, generation `4462.53 ms`, output `81 chars`.
- one tool event only: `tool.run_js`, success=true, exec `4573.11 ms`.
- continuation input `2953 chars`; pre-continuation gap `5471.45 ms`.
- continuation first callback TTFT `8942.79 ms`.
- continuation stream actually produced `1687 callbacks`, all classified visible, totaling `10127 visible chars` across `128426.65 ms` before the user stopped.
- yet top-level request timing still reports `continuation_1_output_chars=0` because the continuation never reached its completion callback / final assembled return path before manual stop.
- no errors were recorded.
- PSS remained roughly 3.4–3.9 GB during tool/continuation; no process crash.

Immediate interpretation:
The model is NOT stuck in prefill and is NOT failing to start decoding after tool return. It begins decoding ~8.9 s after continuation submission and then keeps generating for >128 s. The user sees no final answer because the COMPAT orchestration/UI path does not commit the continuation as a final assistant answer until generation completes. Since this Qwen3.5 continuation again fails to naturally terminate, the UI can appear frozen despite active token generation underneath.

This strongly reconnects the remaining MCP245 failure to the original MCP238 single-continuation non-termination/repetition defect, while the MCP240/MCP243 malformed repeated-tool regression appears resolved in this run: exactly one successful tool call, exactly one continuation.

Before any code/runtime change, inspect the COMPAT streaming/orchestration path to determine why continuation callbacks are not surfaced live and whether a low-blast-radius UI/streaming correction can expose generated final-answer text without introducing forced truncation/watchdog behavior. Preserve official LiteRT-LM 0.15 and all existing safety constraints.