#!/usr/bin/env python3
"""Run the MCP249 targeted patch after the protected MCP218 wire patch has been materialized.

The product already applies patch_tool_call_wire_compat.py during Gradle configuration. MCP249 must
adapt the post-MCP218 helper shape so Gradle can later re-run MCP218 idempotently without losing any
previously validated tool-call normalization/search-required behavior.
"""
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
orig = repo / ".github/scripts/patch_mcp249_ministral_phi_compat.py"
src = orig.read_text(encoding="utf-8")
old = '''prepare_old = ''' + "'''    val compactedRawInput = compactCompatEnvelope(input)\\n'''" + '''
prepare_new = ''' + "'''    val compactedRawInput = compactCompatEnvelope(model = model, input = input)\\n'''" + '''
'''
new = '''prepare_old = ''' + "'''    val compactedRawInput =\\n      CompatSearchRequiredPolicy.injectIntoCompatInput(compactCompatEnvelope(input))\\n'''" + '''
prepare_new = ''' + "'''    val compactedRawInput =\\n      CompatSearchRequiredPolicy.injectIntoCompatInput(\\n        compactCompatEnvelope(model = model, input = input)\\n      )\\n'''" + '''
'''
if src.count(old) != 1:
    raise SystemExit(f"MCP249 v2 patch wrapper: expected one post-wire compaction anchor source, got {src.count(old)}")
src = src.replace(old, new, 1)
# Execute with the original __file__ so REPO_ROOT resolution remains exact.
g = {"__name__":"__main__", "__file__":str(orig)}
exec(compile(src, str(orig), "exec"), g, g)
