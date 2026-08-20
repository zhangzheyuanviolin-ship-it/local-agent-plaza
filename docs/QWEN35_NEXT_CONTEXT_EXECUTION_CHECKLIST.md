# Qwen3.5 Next-Context Execution Checklist

Branch: `experimental`
Read first: `docs/HANDOFF_QWEN35_2B_4B_9B_REBASE_2026-08-20.md`

## Hard rules

- Do not touch `main`.
- Do not continue MCP245/MCP246 debugging.
- Do not use custom JNI/AAR/native runtime surgery.
- Do not simplify the official Qwen3.5 chat template.
- Do not reuse mutable Release tags or `--clobber` accepted model artifacts.
- Keep the three model jobs isolated.
- 32K means `32768` maximum context for all three models.
- Users must be able to select lower context values through the existing settings UI.

## Read-only verification before editing

- [ ] Read active handoff.
- [ ] Read rebase master handoff.
- [ ] Read MCP238 handoff and physical diagnostic.
- [ ] Inspect MCP238 conversion config/script/workflows.
- [ ] Inspect current allowlist entries for experimental Q8/Q4 and MCP245.
- [ ] Inspect existing context setting code/UI and confirm maximum-bound plumbing.
- [ ] Confirm official source IDs: `Qwen/Qwen3.5-2B`, `Qwen/Qwen3.5-4B`, `Qwen/Qwen3.5-9B`.
- [ ] Identify and pin an exact compatible LiteRT-Torch revision.

## Conversion implementation

- [ ] Build one shared Qwen3.5 conversion pipeline based on MCP238.
- [ ] Preserve Qwen3.5-specific exporter path.
- [ ] Preserve dynamic INT8/Q8, `linear_only=true`, `min_max` unless a documented incompatibility requires change.
- [ ] Preserve recurrent-state `sequence_axis=0` compatibility or prove equivalent upstream fix.
- [ ] Preserve each exact model's official tokenizer chat template.
- [ ] Verify chat-template round trip and save template SHA256.
- [ ] Set target max cache/context `32768` for 2B.
- [ ] Set target max cache/context `32768` for 4B.
- [ ] Set target max cache/context `32768` for 9B.
- [ ] Keep `prefill_seq_len` concept separate from context/cache size.
- [ ] Produce independent jobs/artifacts/manifests.

## Artifact verification

For every successful model:

- [ ] record source model revision;
- [ ] record converter revisions;
- [ ] record quantization config;
- [ ] record context `32768`;
- [ ] record full byte size;
- [ ] record full SHA256;
- [ ] record template SHA256;
- [ ] publish to unique immutable Release tag;
- [ ] redownload/reconstruct published bytes;
- [ ] verify final SHA256 after publication.

## Application cleanup/integration

Only after model artifacts are deterministic:

- [ ] remove `Qwen3.5-2B LiteRT-LM Q8 experimental`;
- [ ] remove associated Q4 child/related experimental entry;
- [ ] remove `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245`;
- [ ] add clean 2B Q8 32K;
- [ ] add 4B Q8 32K;
- [ ] add 9B Q8 32K;
- [ ] expose maximum context `32768` to existing settings control;
- [ ] preserve manual lower-context selection;
- [ ] avoid carrying MCP246 special Engine-reset behavior forward without new evidence.

## Physical comparison

Use the same Agent network-search request on all models.

For each model record:

- [ ] download success;
- [ ] hash success;
- [ ] load success;
- [ ] ordinary chat success;
- [ ] number of tool calls;
- [ ] tool name and success;
- [ ] whether workspace was read unnecessarily;
- [ ] whether workspace was written without instruction;
- [ ] whether final answer became visible;
- [ ] whether final answer terminated normally;
- [ ] whether coherent repetition occurred;
- [ ] whether manual Stop was needed.

## Interpretation

- 2B repeats, 4B/9B terminate: treat repetition primarily as small-model capability ceiling.
- 2B/4B/9B all repeat similarly: investigate LiteRT/Qwen3.5 termination after the three-model comparison, without jumping to native runtime surgery.
- 4B/9B enter malformed tool loops: compare conversion metadata/chat templates before touching runtime code.
- 9B cannot load at 32K: document physical memory/runtime blocker and ask user before reducing the 9B maximum context.

## Completion condition

Do not call the rebase successful until there is a deterministic model identity and physical result for 2B and 4B, plus either a physical 9B result or a precisely documented 9B resource blocker.