# Qwen3.5 MCP243 tool-loop root cause and MCP244 plan — 2026-08-20

Read first:
- `docs/ACTIVE_HANDOFF_QWEN35_EXPERIMENTAL.md`
- `docs/checkpoints/QWEN35_MCP243_DEVICE_DIAG_2026-08-20_1619.md`
- `docs/qwen35_mcp238_vs_mcp240_metadata_compare.md`
- `docs/model_diagnostic_mcp-243_2026-08-20_081924.txt`

## New decisive evidence

Inspection workflow `Qwen3.5 original vs MCP240 metadata inspection` completed successfully (run `32348948358`). It reconstructed and SHA-verified both the physically tool-proven original MCP238 model bundle and the MCP240 repaired bundle, unpacked them with official LiteRT-LM 0.15.0, and diffed metadata.

Machine report: `docs/qwen35_mcp238_vs_mcp240_metadata_compare.json`.

Hard result:
- original stop IDs: `[248044]`
- MCP240 stop IDs: `[248044, 248046]`
- ExecutorMetadata present in both
- model.toml semantic content is unchanged; the only model.toml diff is regenerated UUID + creation timestamp
- Jinja chat template changed radically

The ORIGINAL MCP238 bundle contains the full Qwen3.5 tool-aware 154-line template. It explicitly:
- serializes the supplied `tools` list in the system message;
- teaches the required `<tool_call><function=...><parameter=...>` wire format;
- serializes assistant `message.tool_calls` back into tool-call XML;
- serializes `message.role == "tool"` as `<tool_response>...</tool_response>` inside a user turn;
- tracks multi-step tool history and last real user query;
- handles enable_thinking / empty think block at generation prompt;
- handles system/user/assistant/tool roles separately.

MCP240 replaced that entire tool-aware template with a 20-line generic ChatML loop that only special-cases `assistant` and treats every other role generically. It has no `tools`, `tool_calls`, `<tool_response>`, multi-step tool-history, or tool-wire-format logic.

This is not a subtle difference. It directly removes the model's native Qwen3.5 tool-conversation serialization from the bundle.

The official current `Qwen/Qwen3.5-2B` `tokenizer_config.json` on Hugging Face contains the same full tool-aware template as the ORIGINAL MCP238 converted bundle. Therefore the original bundle's template is the official Qwen3.5 template, while MCP240's simplified template is a custom replacement.

Official reference:
`https://huggingface.co/Qwen/Qwen3.5-2B/blob/main/tokenizer_config.json`

## Historical behavior lines up with this diff

MCP238 original bundle physically completed one COMPAT tool call and one continuation. Its final continuation then repeated long answer sections, but tool orchestration itself was coherent.

MCP240 repaired bundle could not be properly tool-tested in MCP240 due the separate repetition-processor logits error. MCP241/MCP243 finally allowed it to run, and MCP243 exposed repeated/malformed tool behavior. This is the first valid Agent test of the simplified-template model.

The template regression therefore has strong direct causal evidence and should be fixed before touching any other subsystem.

## MCP244 authorized conservative plan

MCP244 will make ONE model-side behavioral correction relative to MCP243:

1. Start from the SHA-verified ORIGINAL MCP238 model bundle (`d5e975f...`).
2. Preserve its original full official Qwen3.5 tool-aware Jinja template byte-for-byte.
3. Preserve ExecutorMetadata.
4. Add only stop token `248046` alongside the original `248044`.
5. Repack with official LiteRT-LM 0.15.0 packager.
6. Re-unpack and assert the Jinja template is EXACTLY unchanged from the original source bundle, and both stop IDs are present.
7. Publish as a NEW model identity/file/tag so the broken MCP240 model can never be silently reused.

App/runtime boundary:
- official Maven LiteRT-LM 0.15.0 only;
- no custom AAR/JNI;
- no runtime repetition/presence penalty;
- no NoRepeatNgram;
- no repetition watchdog;
- no per-call hard maxOutputToken workaround;
- CPU forced;
- retain MCP243's already physically stable app/runtime path;
- retain the already-stable MCP240 fresh Engine reset because it does not crash in MCP243 and is intended to remove recurrent-state contamination across COMPAT passes;
- retain current 4096 context/output product configuration and sampler for this isolated template repair; do not mix in a sampler experiment in the same build.

Version target:
- `1.0.14-mcp.244`
- versionCode `344`
- package `com.localagent.plaza.mcp`

A new ~4.78 GB model download WILL be required for MCP244 because the on-device MCP240 bundle contains the wrong simplified template. This is intentional and unavoidable for a clean model artifact correction.

## Why this is not another blind micro-fix

This plan returns the model to the only template already proven on the user's phone to complete an Agent tool call, while carrying forward only the missing natural stop token. The change is metadata-only; weights/executor graph/native runtime are untouched. It is low blast-radius, reversible, and grounded in a byte-level diff plus physical-device A/B history.

Do not proceed to 8792, 4B, 9B, 26B-family or other ecosystems until MCP244 2B/4096 confirms coherent tool flow. If MCP244 restores tool flow but final-answer repetition persists, that remaining issue can then be isolated to stop/state/sampling rather than tool serialization.
