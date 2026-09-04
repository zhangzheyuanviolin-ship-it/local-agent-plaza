# MCP224 Model Diagnostics

This build preserves the MCP223 runtime baseline and adds:

- copyable LiteRT-LM model lifecycle diagnostics for download/import/Engine/Conversation/inference failures;
- true manual Native tool-mode selection for A/B testing;
- no fixed COMPAT tool-call step limit;
- repeated identical COMPAT calls are counted for diagnostics but are no longer auto-blocked;
- the existing user Stop button remains the generic tool-loop termination control.

Primary first-device probe: Qwen3.5 2B LiteRT-LM on Android.

CI validation: the MCP224 source changes passed the full Android assembleRelease compile probe before this MCP-channel build trigger.
