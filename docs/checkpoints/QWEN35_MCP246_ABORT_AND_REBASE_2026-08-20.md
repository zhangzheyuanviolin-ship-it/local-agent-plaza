# Qwen3.5 MCP246 Abort and Rebase Decision — 2026-08-20

Branch: `experimental`
Status: physical test rejected / route closed

## Physical result that closes the MCP245/MCP246 line

The user physically tested the latest Qwen3.5 2B state and reports that the Agent remains unusable in practice:

- repeated network search;
- repeated workspace reads;
- search/read-workspace/search/read-workspace cycling;
- unsolicited workspace writes can occur;
- behavior is effectively the same class of malformed repeated-tool loop already rejected earlier.

The currently exposed Qwen3.5 2B choices are both rejected:

1. `Qwen3.5-2B LiteRT-LM Q8 experimental` / `paulsp94/Qwen3.5-2B-LiteRT-LM`: fails to load physically.
2. `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245`: loads and generates, but Agent behavior is physically rejected because of repeated tool/workspace loops.

MCP246 does not establish a useful product improvement and is no longer the next test target.

## New baseline

Restore MCP238 as the only proven behavioral baseline:

- APK `1.0.14-mcp.238`;
- model `Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`;
- tag `qwen35-2b-q8-4096-v1`;
- size `4,780,966,112` bytes;
- SHA256 `d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73`.

The World Cup physical test on MCP238 demonstrated the desired control-flow skeleton:

- one coherent tool call;
- tool success;
- one continuation;
- user-visible final answer;
- final answer repeated coherently and required manual stopping.

That repetition is now accepted as a 2B baseline defect. The project will test 4B and 9B before spending more time on repetition-specific runtime surgery.

## Approved rebase

Next implementation phase:

- remove the rejected experimental Q8/Q4 line;
- remove MCP245;
- rebuild official-source Qwen3.5-2B from the MCP238 conversion lineage;
- build official-source Qwen3.5-4B using the same conversion lineage;
- build official-source Qwen3.5-9B using the same conversion lineage;
- target Q8/dynamic INT8 matching MCP238;
- retain official Qwen3.5 tool-aware chat templates;
- retain Qwen3.5 recurrent-state export compatibility behavior;
- pin conversion dependencies;
- set maximum context for all three to `32768`;
- reuse the existing application Context Window control so users can manually choose lower values;
- avoid new JNI/AAR/native runtime modifications.

## Superseded direction

The following old roadmap statement is superseded:

`2B/4096 -> 8792 -> 4B -> 9B`

The approved direction is now:

`MCP238-lineage 2B Q8 32K + 4B Q8 32K + 9B Q8 32K -> common physical Agent comparison`

## Documentation-only checkpoint

This checkpoint records the decision only. No application code, model allowlist, conversion workflow, APK, or model artifact was changed as part of this checkpoint.