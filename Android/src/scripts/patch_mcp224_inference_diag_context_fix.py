#!/usr/bin/env python3
"""MCP224 CI follow-up plus MCP238 model/download patch chaining."""

from pathlib import Path
import runpy
import sys

ROOT = Path(__file__).resolve().parents[1]
REL = "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
path = ROOT / REL
text = path.read_text(encoding="utf-8")

old = '''              ModelLifecycleDiagnostics.recordThrowable(
                context = context,
                model = model,
                stage = "litert.inference.failure",
                throwable = throwable,
                detail = "input_chars=${effectiveInput.length} | compat_pass=$isCompatPass | compat_pass_kind=$compatPassKind",
              )
'''
new = '''              ModelLifecycleDiagnostics.recordThrowable(
                modelName = model.name,
                stage = "litert.inference.failure",
                throwable = throwable,
                detail = "input_chars=${effectiveInput.length} | compat_pass=$isCompatPass | compat_pass_kind=$compatPassKind",
              )
'''

if new in text:
    print(f"MCP224 inference diagnostics context fix already applied to {REL}")
else:
    count = text.count(old)
    if count != 1:
        print(
            f"MCP224 inference diagnostics context fix expected one marker in {REL}, found {count}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"MCP224 fixed Context-free inference diagnostics in {REL}")

# MCP238 runs last. v3 injects the conversion workflow's verified ten-part release metadata and
# performs the Python-3.11 f-string compatibility rewrite before executing the retained patch.
mcp238 = ROOT / "scripts/patch_mcp238_qwen35_model_download_v3.py"
if not mcp238.exists():
    print(f"MCP238 patch script missing: {mcp238}", file=sys.stderr)
    raise SystemExit(1)
runpy.run_path(str(mcp238), run_name="__main__")
