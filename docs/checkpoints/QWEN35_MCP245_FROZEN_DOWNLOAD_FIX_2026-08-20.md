# MCP245 frozen download fix — final checkpoint

Build/release SUCCESS.

Run `32359008843`; APK SHA256 `b31851709d6c0e969bbba631ed9748378517cd3c6ea455ea85cf74a7571b47e3`.

APK: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp245-qwen35-frozen-download-fix/local-agent-plaza-1.0.14-mcp.245.apk`

Artifact: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32359008843/artifacts/9403353880`

Frozen model tag `qwen35-2b-q8-4096-mcp245-frozen-v1`, SHA256 `535c32962d7d00be409abe9d7a4135733a362b6d1e5c81b9004f4a6e74a49db4`, size `4780966112`.

Root cause: MCP244 model release assets were mutable and were overwritten by a later nondeterministic repack after the APK had embedded the earlier SHA. Independent GitHub Actions reconstruction produced exactly the same 535c3296... hash reported by the physical device, proving the downloader itself reconstructed the published bytes correctly. MCP245 copies the verified current model bytes without repacking into a new one-time release tag, re-downloads that release, reconstructs it, verifies the exact SHA, and embeds that same SHA in the APK.

Product boundary remains MCP238/MCP239 Agent behavior + full original official tool template + dual natural stops; official LiteRT-LM 0.15; no MCP240/MCP241 app patches; no custom runtime.

Next physical test: install MCP245, download the MCP245 model, then test Agent web-search/tool flow and final natural termination.
