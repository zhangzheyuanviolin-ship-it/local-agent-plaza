# MCP244 final strict checkpoint

Build/release SUCCESS. This supersedes any earlier MCP244 APK generated with MCP240/MCP241 app patches.

Run `32350287008`; APK SHA256 `0cd9c114a062a3cbb986f3a0f37d8e23bdb20f408cd09bcba647abb4b1541776`.

Release: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/mcp244-qwen35-official-tool-template/local-agent-plaza-1.0.14-mcp.244.apk`
Artifact: `https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/actions/runs/32350287008/artifacts/9399915719`

Exact boundary: official LiteRT-LM 0.15; app patch chain MCP238+MCP239+MCP244 only; sampler 20/0.8/0.6, maxTokens1536, context4096, CPU; no fresh-engine reset; no repetition processor; no custom JNI/AAR. Model source is the physically tool-proven MCP238 bundle, original full tool-aware Jinja preserved byte-for-byte, with only stop 248046 added beside 248044. New model download is required. Next: physical device explicit web-search/tool test, then observe natural final stop. If old long repetition remains, retain this tool-working baseline/manual Stop and do not attempt native-runtime surgery.
