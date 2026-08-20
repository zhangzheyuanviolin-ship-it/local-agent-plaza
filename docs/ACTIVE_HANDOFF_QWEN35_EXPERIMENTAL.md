# Local Agent Plaza — Qwen3.5 experimental live handoff

Last checkpoint: 2026-08-20 18:47+08:00 — MCP245 frozen model + signed APK build/release SUCCESS
Branch: `experimental`
Default branch `main` must remain untouched.
Stable product reference: `golden/mcp-223-product-stable`.

## Mandatory collaboration / safety rules

1. This file is the live continuity point. Keep it current after material changes so a replacement agent can resume without asking the user to reconstruct history.
2. Do not send intermediate chat reports during builds. Continue to a signed APK + real Release/artifact link, then report once.
3. Do not touch `main` for experiments.
4. Any high-risk low-level change (JNI/AAR/native inference engine/runtime replacement, ABI surgery, unproven model-binary surgery) requires ~97% confidence and explicit justification. MCP242 proved that build success does not establish runtime safety.
5. Do not add host forced truncation, automatic repetition watchdog cancellation, or hard NoRepeatNgram unless explicitly authorized.
6. Large APK/model delivery uses GitHub Release/Actions, not sandbox.
7. Planned later sequence remains: stabilize 2B/4096, then exact context `8792`, then 4B, then 9B. Do not normalize 8792 to 8192.

## Historical baseline that must remain conceptually intact

MCP238/MCP239 physically proved Qwen3.5-2B Q8/4096 can load on Android CPU and complete a coherent COMPAT network tool call. Original model:
- file `Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm`
- tag `qwen35-2b-q8-4096-v1`
- size `4,780,966,112`
- SHA256 `d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73`
- topK 20 / topP 0.8 / temperature 0.6 / maxTokens 1536 / context 4096 / CPU in MCP239.

Its remaining defect was post-tool final-answer repetition inside one continuation decode. That was separate from later repeated/malformed tool behavior.

## MCP240/MCP243 tool-regression diagnosis

MCP240 replaced the original ~154-line official Qwen3.5 tool-aware Jinja with a ~20-line generic ChatML template. The deleted material included tools schema injection, `<tool_call><function=...><parameter=...>`, `message.tool_calls`, `<tool_response>`, tool-role handling and multi-step tool history. MCP243 exposed repeated/malformed tool behavior with that simplified-template model.

Confidence that this simplified template caused the NEW tool-loop/tool-call-leak regression remains >97%.

Evidence:
- `docs/qwen35_mcp238_vs_mcp240_metadata_compare.json`
- `docs/checkpoints/QWEN35_MCP243_ROOT_CAUSE_AND_MCP244_PLAN_2026-08-20.md`
- `docs/checkpoints/QWEN35_MCP244_ISOLATION_CORRECTION_2026-08-20_1636.md`

## MCP242 — rejected product route

MCP242 custom LiteRT-LM JNI/AAR caused severe random process-level crashes on the physical device. Keep official Maven LiteRT-LM 0.15.0 for this line of work. Do not revive custom runtime surgery without explicit re-authorization.

## MCP244 model semantics were correct, delivery integrity failed

MCP244 correctly restored the full original official Qwen3.5 tool-aware template byte-for-byte and added natural stop token 248046 beside 248044. Its strict application boundary also returned to MCP238/MCP239 behavior.

However the MCP244 model Release tag was mutable. The model-building workflow used `gh release upload --clobber`, and later workflow runs repacked the same semantic model again. LiteRT-LM pack generated different container bytes due regenerated UUID/timestamp metadata, then overwrote the same Release assets.

The strict MCP244 APK had embedded expected SHA:
`7cdf8232b949d184e3cee81694cea7b21bac36a538d495d1a83581a4ce2c5d44`

Physical-device diagnostic:
`docs/model_diagnostic_mcp-244_2026-08-20_102047.txt`

Device reconstructed SHA:
`535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`

Independent Actions forensic reconstruction of the ten CURRENT GitHub Release parts produced exactly the same:
`535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`

The size was correct at `4,780,966,112` bytes and all ten part boundaries/URLs were correct. Therefore the multipart downloader reconstructed the published assets correctly; the fault was the Release contents drifting after APK creation.

Forensics:
- `docs/mcp244_multipart_forensics.json`
- `docs/mcp244_manifest_and_runs_probe.json`

The current MCP244 Release manifest itself now records SHA `535c3296...`, confirming it was overwritten after the APK had captured `7cdf8232...`.

Do not use MCP244 as the next physical-test candidate.

## MCP245 — CURRENT PHYSICAL-TEST CANDIDATE

Workflow run: `32359008843`
Result: `docs/mcp245_apk_result.json`
Checkpoint: `docs/checkpoints/QWEN35_MCP245_FROZEN_DOWNLOAD_FIX_2026-08-20.md`

### Frozen model

Display: `Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245`
File: `Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp245.litertlm`
Tag: `qwen35-2b-q8-4096-mcp245-frozen-v1`
Size: `4,780,966,112`
SHA256: `535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`
Template SHA256: `273d8e0e683b885071fb17e08d71e5f2a5ddfb5309756181681de4f5a1822d80`
Stop IDs: `[248044, 248046]`
New model download required: YES.

MCP245 freeze procedure completed successfully before APK build:
1. Reconstructed the current MCP244 Release and verified full SHA `535c3296...`.
2. Reconstructed the original physically tool-proven MCP238 bundle and verified SHA `d5e975f...`.
3. Unpacked both with official `litert-lm==0.15.0`.
4. Proved the current 535c model Jinja is byte-for-byte equal to the original MCP238 official tool-aware Jinja and still contains all required tool markers.
5. Proved 248044 + 248046 are present and ExecutorMetadata is retained; model.toml functional structure matches after excluding generated UUID/timestamp.
6. Copied the already-verified 535c model parts byte-for-byte under NEW MCP245 filenames; NO further model repack occurred.
7. Published a NEW one-time Release tag without `--clobber`.
8. Re-downloaded the newly published MCP245 assets from GitHub, concatenated all ten parts again, and verified exact size + exact full SHA `535c3296...` after publication.

This closes the MCP244 mutable-release failure mode before the APK embeds the hash.

### MCP245 APK

VersionName: `1.0.14-mcp.245`
VersionCode: `345`
Package: `com.localagent.plaza.mcp`
APK SHA256: `b31851709d6c0e969bbba631ed9748378517cd3c6ea455ea85cf74a7571b47e3`
Permanent APK Release:
`https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp245-qwen35-frozen-download-fix/local-agent-plaza-1.0.14-mcp.245.apk`
Actions artifact ID: `9403353880`
Artifact:
`https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32359008843/artifacts/9403353880`

### MCP245 strict product boundary

- official Maven LiteRT-LM `0.15.0`;
- app patch chain exactly MCP238 + MCP239 + MCP245;
- no MCP240 app patch;
- no MCP241 app patch;
- CPU forced;
- resident Engine + ordinary fresh Conversation as the known tool-working baseline;
- topK 20 / topP 0.8 / temperature 0.6;
- maxTokens 1536 / context 4096;
- no RepetitionPenaltyConfig;
- no NoRepeatNgram;
- no repetition watchdog;
- no host forced truncation;
- no custom LiteRT-LM JNI/AAR.

## Exact next physical-device gate

1. Overwrite-install MCP245 APK.
2. Download the NEW MCP245 model. The expected full SHA is `535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`.
3. First verify model download completes. A repeat of the MCP244 SHA mismatch would contradict the already completed post-publication Release reconstruction and should be diagnosed immediately.
4. Then run explicit Agent web-search/tool use.
5. Observe whether tool flow returns to MCP238/MCP239 coherence.
6. Observe whether dual natural stops also cure the old long final-answer repetition.

Interpretation:
- coherent tool flow + natural final stop => 2B/4096 baseline is ready to close;
- coherent tool flow + old final repetition => current safe tool ceiling is MCP238/MCP239 behavior represented by MCP245, with manual Stop if necessary; do not resume native-runtime surgery;
- malformed tool flow => preserve diagnostic and compare COMPAT orchestration against the physical MCP238 baseline before any further model/runtime changes.

Do not proceed to 8792/4B/9B until this MCP245 physical test is complete.
