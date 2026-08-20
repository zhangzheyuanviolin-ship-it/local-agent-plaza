# Qwen3.5 MCP244 strict isolation correction

Timestamp: 2026-08-20 16:36+08:00
Branch: `experimental`

## Why this checkpoint exists

The MCP243 physical-device diagnostic and the verified MCP238-vs-MCP240 bundle diff now localize the NEW tool-use regression with very high confidence to MCP240's replacement Jinja template. MCP240 replaced the original ~154-line Qwen3.5 tool-aware template with a ~20-line generic ChatML template, deleting tool schema injection, `message.tool_calls` serialization, `<tool_response>` handling, multi-step tool semantics, and related role logic. The MCP238 source bundle had already demonstrated successful tool execution on the user's phone.

The first draft MCP244 workflow still applied MCP240/MCP241 app patches, retained the MCP240 fresh-Engine reset, and retained MCP240 sampler/max-output changes. That would leave multiple variables changed and weaken the controlled test. Do not use the first-draft MCP244 product if it is produced.

## Correct MCP244 isolation boundary

MCP244 must reproduce the physically tool-proven MCP238/MCP239 application behavior as closely as possible and change exactly one model-semantic item: add stop token `248046` while preserving the ORIGINAL Qwen3.5 tool-aware Jinja template byte-for-byte.

App build must apply only:
1. MCP238 multipart model-download patch.
2. MCP239 CPU-only patch.
3. MCP244 identity/download retarget patch.

It must NOT apply MCP240 or MCP241 app patches.

Therefore MCP244 must retain MCP238/MCP239 generation behavior:
- official Maven LiteRT-LM 0.15.0;
- resident Engine + ordinary fresh Conversation behavior from the known tool-working baseline;
- no MCP240 hard Engine rebuild on every COMPAT fresh boundary;
- top_k = 20;
- top_p = 0.8;
- temperature = 0.6;
- maxTokens = 1536;
- context = 4096;
- CPU forced by MCP239;
- no RepetitionPenaltyConfig;
- no NoRepeatNgramConfig;
- no host repetition watchdog;
- no per-call hard output cap beyond the baseline model config.

## MCP244 model build

Source model is the SHA-verified, physically tool-proven MCP238 bundle:
- tag: `qwen35-2b-q8-4096-v1`
- file: `Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`
- size: 4,780,966,112 bytes
- SHA256: `d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73`

The pack step must:
- preserve the full original Jinja template exactly;
- preserve ExecutorMetadata;
- add only stop token 248046 next to original 248044;
- verify the re-unpacked template hash exactly matches source;
- reject any semantic `model.toml` change beyond packager-generated UUID/timestamp.

## Confidence and interpretation

Confidence that MCP240's simplified template caused the NEW repeated-tool/tool-call-leak regression: >97% based on direct bundle diff plus physical-device A/B history.

Confidence that restoring the original template will restore the old MCP238/MCP239 tool behavior: >97%, provided the app path is also restored to that baseline.

Confidence that adding 248046 alone will fix the ORIGINAL long final-answer repetition: materially lower and still requires physical-device validation. The two problems are separated: tool regression has a high-confidence cause; old long-answer repetition remains a natural-stop hypothesis.

If MCP244 restores tool behavior but final-answer repetition remains, the safe current Qwen3.5-2B tool ceiling is the MCP238/MCP239 behavior plus manual Stop until an official/runtime-safe mitigation is available. No native runtime replacement is authorized.
